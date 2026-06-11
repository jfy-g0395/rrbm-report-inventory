# FINAL DEPLOYMENT AUDIT — RRBM

Date: 2026-06-11
Scope: Production-breaking issues only (build, runtime, env, migrations, auth, API, security blockers, deploy config, dependencies, data loss).
Build status: `mvnw compile` → exit 0 (clean).

---

## CRITICAL

### Required secrets crash the app if unset
`docker-compose.yml` injects `${RRBM_JWT_SECRET}` and `${DB_PASSWORD}` from an uncommitted `.env`. If `.env` is missing, Compose substitutes empty strings:
- `RRBM_JWT_SECRET=""` → `Keys.hmacShaKeyFor("")` throws `WeakKeyException` (HS256 needs ≥256 bits) → bean init fails → **boot crash**.
- `DB_PASSWORD=""` → PostgreSQL authentication fails → **boot crash**.

- file: `docker-compose.yml`, `rrbm-backend/src/main/java/rrbm_backend/JwtUtil.java:24`
- fix: Create `.env` from `.env.example` and set `DB_PASSWORD`, `RRBM_JWT_SECRET` (`openssl rand -hex 32`), and `RRBM_INITIAL_MASTER_KEY` before `docker compose up`.

---

## HIGH

### Committed JWT dev-fallback secret
`application.properties` ships a hardcoded fallback secret. If it is ever used, anyone with the repo can forge valid admin JWTs (full authentication bypass).

- file: `rrbm-backend/src/main/resources/application.properties:38`
- fix: Always set a unique `RRBM_JWT_SECRET` in production; never deploy relying on the fallback.

### Dev secrets baked into the backend image
The Dockerfile `COPY src ./src` bundles `application-local.properties` (DB password `web_temp#0127`, `admin123`, mail creds) into image layers. Runtime env overrides the values, but the secrets remain embedded and extractable from the image.

- file: `rrbm-backend/Dockerfile:11`, `rrbm-backend/src/main/resources/application-local.properties`
- fix: Add a `.dockerignore` excluding `src/main/resources/application-local.properties`.

### Default super-admin & master key with a public hash
Seed migration creates `admin@rrbm.com` / `ChangeMe123!` and a `master_key_hash` using a publicly known bcrypt hash committed in the repo.

- file: `V2__seed_initial_data.sql`, `V63__restore_seed_data.sql`
- fix: Change the admin password and master key immediately after first boot.

### Migration V63 wipes the audit trail (data loss)
`DELETE FROM activity_log;` runs unconditionally. On any existing database that has not yet applied V63, upgrading deletes the entire activity log.

- file: `V63__restore_seed_data.sql:16`
- fix: Confirm the target DB is already past V63, or remove the `DELETE` before upgrading production.

### Permissive CORS
`setAllowedOriginPatterns(List.of("*"))` combined with `setAllowCredentials(true)` permits every origin.

- file: `rrbm-backend/src/main/java/rrbm_backend/SecurityConfig.java:82`
- fix: Restrict `allowedOriginPatterns` to the exact production domain(s).

---

## PASS — areas checked with no blockers

- Backend compiles cleanly (`mvnw compile`, exit 0); no build failures.
- Frontend `API_BASE` auto-switches to relative URLs outside localhost; nginx proxies `/api/*` to `backend:8080` (`app.js:14`, `nginx.conf`).
- Dockerfiles valid; backend runs as non-root user; multi-stage build.
- Flyway `out-of-order=true` tolerates version gaps (V47, V52 missing) — no migration ordering failure.
- `import_commit_log` FK/type fixes (V68/V69) are idempotent (`DROP CONSTRAINT IF EXISTS`).
- Migration destructive statements other than V63 are in already-applied historical migrations or guarded.
- Dependencies resolve; `spring-boot-devtools` is excluded from the repackaged fat JAR.
- Backend exposes no host port directly; only nginx (`:80`) is published.
