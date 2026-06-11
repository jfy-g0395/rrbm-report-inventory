# RRBM — Production Audit Remediation Plan

**Date:** 2026-06-11
**Auditor pass scope:** API contract (frontend↔backend), auth/JWT internals, per-controller role enforcement, DB migrations & schema-validation mode, env/secrets/deploy config, error handling, frontend design tokens.
**Build:** not re-run this pass (prior `mvnw compile` recorded clean in `DEPLOYMENT-AUDIT.md`).

This plan chops the audit findings into **work sessions ordered by priority**. Each session is self-contained and can be completed and verified independently. Do them top-to-bottom: Session 1 is a production blocker.

---

## Severity legend

| Tag | Meaning |
|-----|---------|
| 🔴 CRITICAL | Blocks production. Exploitable now or guarantees a deploy failure. |
| 🟠 HIGH | Must fix before go-live. Security/data-loss exposure. |
| 🟡 MEDIUM | Fix soon after launch. Hardening / robustness / UX correctness. |
| ⚪ LOW/STYLE | Cosmetic or low-risk. Fix when convenient. |

---

# SESSION 1 — 🔴 CRITICAL: Authorization & boot blockers

> Do not deploy until both items below are closed.

## 1.1 Privilege escalation — `POST /api/users` & `PUT /api/users/{id}` have no role check
- **file:** `rrbm-backend/src/main/java/rrbm_backend/UserController.java:69` (`createUser`), `:125` (`updateUser`)
- **problem:** Every other privileged user op is gated to `SUPER_ADMIN` (`updateRole` :187, `updateStatus` :218, `setSecurityKey` :250, `updatePermissions` :281, `deleteUser` :315). **Create** and **full update** were missed. `createUser` accepts `role` from the body (only validated against `VALID_ROLES`); `updateUser` sets `role` (:159-164) and overwrites `passwordHash` (:167-173) with no caller check. `SecurityConfig` only requires `.authenticated()` on `/api/**`.
- **impact:** Any authenticated user (incl. `STANDARD_USER`/`STAFF`) can create a new `SUPER_ADMIN`, or `PUT` any user to reset their password / self-promote to `SUPER_ADMIN` → full account takeover and authorization bypass.
- **fix:** Add the same `SUPER_ADMIN` caller gate used in `updateRole`/`updateStatus` to `createUser` and `updateUser`. At minimum, in `updateUser` reject `role` and `password` changes unless the caller is `SUPER_ADMIN`.
- **verify:** As a non-admin token, `POST /api/users` with `role=SUPER_ADMIN` → expect 403; `PUT /api/users/{otherId}` with a new password → expect 403.

## 1.2 App will not boot if `.env` is absent; no `.env` template tracked in git
- **file:** `docker-compose.yml:13,33,34,35`; `rrbm-backend/src/main/java/rrbm_backend/JwtUtil.java:23-24`; `.gitignore` (`.env.*`)
- **problem:** Compose substitutes `${DB_PASSWORD}`, `${RRBM_JWT_SECRET}`, `${RRBM_INITIAL_MASTER_KEY}` from an uncommitted `.env`. If missing → empty strings: `DB_PASSWORD=""` fails Postgres auth; `RRBM_JWT_SECRET=""` → `Keys.hmacShaKeyFor("")` throws `WeakKeyException` → boot crash. `.env.example` exists on disk but is matched by `.env.*` in `.gitignore`, so it is **not tracked** — a fresh clone has no template.
- **impact:** Clean deploy crash-loops on first `docker compose up`; operator has no committed variable reference.
- **fix:** Create `.env` with `DB_PASSWORD`, `RRBM_JWT_SECRET` (`openssl rand -hex 32`), `RRBM_INITIAL_MASTER_KEY`. Track `.env.example` in git (force-add, or adjust the ignore rule so `.env.example` is committed while `.env` stays ignored).
- **verify:** Fresh clone contains `.env.example`; `docker compose up` with a populated `.env` boots cleanly.

---

# SESSION 2 — 🟠 HIGH: Secrets & exposure

## 2.1 Full-staff PII exposed to any authenticated user
- **file:** `rrbm-backend/src/main/java/rrbm_backend/UserController.java:46-51` (`listUsers`)
- **problem:** `GET /api/users` has no role gate; returns `UserDto` for every user incl. `address`, `contactNumber`, `profileImage`, `email`, `allowedPages`.
- **impact:** Any logged-in account (incl. `STANDARD_USER`) can enumerate all employees' PII.
- **fix:** Restrict list (and `getUser` :56) to admin roles, or strip PII fields for non-admin callers.

## 2.2 Committed JWT dev-fallback secret
- **file:** `rrbm-backend/src/main/resources/application.properties:38`
- **problem:** Hardcoded fallback secret used whenever `RRBM_JWT_SECRET` is unset.
- **impact:** If deployed without the env var, anyone with the repo can forge admin JWTs → auth bypass.
- **fix:** Always set a unique `RRBM_JWT_SECRET` in production; never rely on the fallback.

## 2.3 Real dev secrets baked into the backend image
- **file:** `rrbm-backend/Dockerfile` (`COPY src ./src`), `rrbm-backend/src/main/resources/application-local.properties`; no `.dockerignore`
- **problem:** `application-local.properties` (DB password `web_temp#0127`, `admin123`, mail creds) is gitignored but not docker-ignored, so it is bundled into image layers (still extractable even though runtime env overrides values).
- **impact:** Secret leakage to anyone who can pull the image.
- **fix:** Add `rrbm-backend/.dockerignore` excluding `src/main/resources/application-local.properties`.

## 2.4 Permissive CORS (wildcard origin + credentials)
- **file:** `rrbm-backend/src/main/java/rrbm_backend/SecurityConfig.java:82-85`
- **problem:** `setAllowedOriginPatterns(List.of("*"))` + `setAllowCredentials(true)`; controllers also carry `@CrossOrigin(origins = "*")`.
- **impact:** Any website can make credentialed requests in a browser context.
- **fix:** Restrict `allowedOriginPatterns` (and `@CrossOrigin`) to the exact production domain(s).

## 2.5 Default super-admin & master key with a publicly-committed hash
- **file:** `rrbm-backend/src/main/resources/db/migration/V63__restore_seed_data.sql:25`; `V2__seed_initial_data.sql`
- **problem:** Seed installs a default admin and a `master_key_hash` whose bcrypt value is committed; initial master key falls back to `rrbm2024`.
- **impact:** Known credentials/master key on a fresh deploy until rotated.
- **fix:** Rotate admin password and master key immediately after first boot; set `RRBM_INITIAL_MASTER_KEY`.

---

# SESSION 3 — 🟠 HIGH: Data-loss migration

## 3.1 Migration V63 unconditionally wipes the audit trail
- **file:** `rrbm-backend/src/main/resources/db/migration/V63__restore_seed_data.sql:16`
- **problem:** `DELETE FROM activity_log;` runs with no guard. On any existing DB not yet past V63, upgrading deletes the entire activity log.
- **impact:** Permanent loss of full audit history on upgrade.
- **fix (deploy-time branch — do NOT blanket-edit V63):**
  1. **Back up the live DB first** (`pg_dump … -F c -f rrbm_pre_v63_backup.dump`). Makes everything reversible.
  2. **Check the target's Flyway version:** `SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;`
  3. **Branch A — version ≥ 63 (expected):** no code change. V63 already ran once and won't re-run; the audit log is safe going forward.
     **Branch B — version ≤ 62 with real history:** remove line 16 from V63 *before* deploying, then run `flyway repair` on every environment already past V63.
- **⚠ checksum caveat:** `validate-on-migrate` defaults **ON** (no override in `application.properties`). Editing an *already-applied* V63 changes its checksum and makes every environment past it fail boot until `flyway repair`. This is why the edit is Branch-B-only.
- **forward-migration dead end:** a new V70 can't fix this — within one `flyway migrate` V63 deletes *before* V70 runs, and V70 can't restore deleted rows.
- **note:** Treated as its own session because it requires confirming the *target DB's* current Flyway version before touching anything — a deploy-time decision, not just a code edit. **Pre-flight complete 2026-06-11; see PRODUCTION-AUDIT-PROGRESS.md "Pre-flight findings".**
- **verify:** Branch A — boot backend, Flyway validation passes (no checksum error), `activity_log` row count unchanged. Branch B — on a ≤V62 copy, migrate with edited V63, confirm `SELECT count(*) FROM activity_log` is unchanged (not 0) and Flyway reaches V69.

---

# SESSION 4 — 🟡 MEDIUM: Auth hardening & error handling

## 4.1 No rate limiting / lockout on credential endpoints
- **file:** `AuthController` `/login`, `/verify-security-key`, `/verify-superadmin-key`, `/master-key`; `AuthService.login`
- **problem:** No throttling/lockout/failed-attempt tracking. Master key & admin security key minimums are only 6 chars.
- **impact:** Login, master-key, and security-key checks are brute-forceable.
- **fix:** Add attempt throttling/lockout (or a filter-level rate limiter) on auth endpoints.

## 4.2 No global exception handler
- **file:** none — no `@ControllerAdvice`/`@RestControllerAdvice` exists
- **problem:** Malformed JSON, type-conversion failures, and unexpected runtime errors fall through to Spring's default handling instead of the `{message}`/`{error}` JSON the frontend expects.
- **impact:** Inconsistent error shapes to the frontend on edge cases; possible default-error detail leakage.
- **fix:** Add a `@RestControllerAdvice` mapping exceptions to the existing `{message}`/`{error}` shape with correct status codes.

## 4.3 Backend has no readiness/health gate
- **file:** `docker-compose.yml:42-43`; no actuator in `pom.xml`/`application.properties`
- **problem:** `backend` has no healthcheck / `/health`; `frontend depends_on: backend` waits only for container start. nginx `proxy_pass http://backend:8080` returns 502 while Spring Boot is still booting/migrating.
- **impact:** Window of 502s after `up`/restarts until backend is ready.
- **fix:** Add a backend healthcheck and `depends_on: condition: service_healthy` for the frontend.

---

# SESSION 5 — ⚪ LOW / STYLE: Frontend polish & cleanup

## 5.1 Dynamic colors hardcoded, bypassing dark-mode tokens
- **file:** `rrbm_frontend/rrbm-frontend/js/app.js` (raw hex e.g. `#EF4444`×94, `#10B981`×61, `#F59E0B`×41, …)
- **problem:** CSS defines a full light/dark token palette (`styles.css:11-66`), but JS-rendered badges/charts/pills use hardcoded light-theme hex instead of `--badge-*`/`--text-*` vars.
- **impact:** In dark mode, JS-generated UI renders with light colors → contrast/aesthetic mismatch.
- **fix:** Have JS-generated elements consume the existing CSS variables.

## 5.2 `DM Mono` font referenced but never loaded
- **file:** `rrbm_frontend/rrbm-frontend/css/styles.css:283` vs `@import` at `styles.css:6`
- **problem:** The Google Fonts `@import` loads `DM Sans` + `Public Sans` only; `font-family: 'DM Mono', monospace;` falls back to system monospace.
- **impact:** Monospace UI (receipt #s, codes, key inputs) renders in the wrong typeface.
- **fix:** Add `DM+Mono` to the existing Google Fonts `@import`.

## 5.3 nginx serves non-asset files
- **file:** `rrbm_frontend/Dockerfile` (`COPY rrbm-frontend /usr/share/nginx/html`)
- **problem:** Whole dir copied to web root, incl. `docs/`, `Notes/`, `frontend.log` → publicly fetchable.
- **impact:** Internal docs/logs reachable over HTTP.
- **fix:** Copy only runtime assets (or exclude `docs/`, `Notes/`, `*.log`).

---

# PASS — verified, no action needed

- **Frontend ↔ backend contract:** every distinct `/api/*` path called from `app.js` maps to an existing backend route with matching HTTP method (orders, reports, commissions, agents, suppliers, payables, import, users, settings, transactions all reconciled).
- **`/api/reports` shared base** (`DailyReportController` + `ReportsController`): sub-paths disjoint, no mapping collision.
- **JWT internals:** HS256, signed claims, 8h expiry; caller identity read from the signed token, not spoofable headers (`JwtAuthFilter`, `JwtUtil`).
- **Schema ↔ code:** `spring.jpa.hibernate.ddl-auto=validate` (`application.properties:22`) enforces entity/column consistency at boot.
- **Migrations:** Flyway `out-of-order=true` tolerates missing V6/V47/V52 gaps; no ordering failure.
- **Correct authorization:** master-key list/create/delete gated to `SUPER_ADMIN`/`ADMINISTRATOR`; order void/cancel/return require a bcrypt-verified admin security key; payable status and commission pay/release/close are role-gated; user role/status/permissions/delete are `SUPER_ADMIN`-gated.
- **Auth flow:** `DISABLED` accounts rejected before token issue (`AuthService.java:48-51`); bcrypt password storage; `UserDto` never exposes `passwordHash`/`adminSecurityKey`.
- **CSS design system:** coherent brand palette with complete light/dark token sets; Bootstrap 5.3.3 + Tabler icons loaded consistently.

---

## Suggested order of execution

1. **Session 1** (blockers) → must be green before any deploy.
2. **Session 2 + 3** (HIGH) → before go-live.
3. **Session 4** (MEDIUM) → first hardening sprint after launch.
4. **Session 5** (LOW/STYLE) → backlog / polish.
