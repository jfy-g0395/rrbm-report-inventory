# RRBM — Production Audit Remediation Progress

**Companion to:** [PRODUCTION-AUDIT-REMEDIATION.md](PRODUCTION-AUDIT-REMEDIATION.md)
**Started:** 2026-06-11
**Last updated:** 2026-06-11 (Session 5 — 5.2 font + 5.3 docs/log exposure done & verified; 5.1 dark-mode colors scoped to orders table + verified, remainder deferred as tech debt)

Status legend: ⬜ Not started · 🔄 In progress · ✅ Done · ⏭️ Deferred · ❌ Won't fix

---

## Overall progress

| Session | Priority | Items | Done | Status |
|---------|----------|-------|------|--------|
| 1 — Authorization & boot blockers | 🔴 CRITICAL | 2 | 2/2 | ✅ Done + verified |
| 2 — Secrets & exposure | 🟠 HIGH | 5 | 4/5 | 🔄 4 code-fixed; 2.5 operational ⏭️ |
| 3 — Data-loss migration | 🟠 HIGH | 1 | 1/1 | ✅ Resolved — Branch A (DB at V69, V63 inert); cleanup deferred as tech debt |
| 4 — Auth hardening & error handling | 🟡 MEDIUM | 3 | 3/3 | ✅ Done + verified |
| 5 — Frontend polish & cleanup | ⚪ LOW/STYLE | 3 | 2/3 | 🔄 5.2 + 5.3 done; 5.1 scoped-done (orders table), remainder deferred |
| **Total** | | **14** | **12/14** | **86%** |

**Deploy gate:** 🟡 Sessions 1 + 2 code-complete (Session 4 MEDIUM hardening also done); suite 159/159. Verified live: 403 auth gate, restricted CORS, standalone boot. Remaining before deploy: (1) **2.5 operational** — rotate master key + default admin password after first boot; (2) set real `RRBM_CORS_ALLOWED_ORIGINS` in `.env`; (3) **Session 3** — ✅ resolved: target DB confirmed at **V69** (past V63), so the destructive `DELETE FROM activity_log` is already recorded-applied and will never re-run; no code change (Branch A). On a future fresh live deploy it runs against an empty `activity_log` → harmless. Optional cleanup of the V63 recovery script is deferred as tech debt (see §Session 3); (4) run the full `docker compose up` stack on a Docker host (Docker unavailable in this env).

---

## SESSION 1 — 🔴 CRITICAL: Authorization & boot blockers

| # | Item | File | Status | Verified | Notes |
|---|------|------|--------|----------|-------|
| 1.1 | Privilege escalation on `POST`/`PUT /api/users` | `UserController.java`, `UserCreateUpdateGateTest.java` | ✅ | ✅ | Approach A: `createUser`/`updateUser` gated to SUPER_ADMIN+ADMINISTRATOR; ADMINISTRATOR can't assign SUPER_ADMIN role nor edit a SUPER_ADMIN target. 6 MockMvc regression tests added; full suite 148/148. **Live HTTP verified** (real STANDARD_USER token → `POST`/`PUT /api/users` both 403). |
| 1.2 | `.env`/secret boot crash; `.env.example` untracked | `.gitignore`, `.env` (new), `.env.example` | ✅ | 🔄 | `.gitignore` `!.env.example` negation added → template now tracked (staged `A`); `.env` generated with `openssl`-random `DB_PASSWORD`/`RRBM_JWT_SECRET`(256-bit)/`RRBM_INITIAL_MASTER_KEY`, stays git-ignored (`git check-ignore` confirms). Backend **boot verified standalone** (Flyway validated 66 migrations, `Started RrbmBackendApplication`, `validate` schema check passed). Full `docker compose up` stack NOT run here — Docker is not installed in this environment; run on a Docker host before deploy. |

**Verify 1.1:** non-admin token → `POST /api/users role=SUPER_ADMIN` returns 403; `PUT /api/users/{otherId}` password change returns 403. — _✅ done: MockMvc suite + live HTTP curl against a running instance both return 403._
**Verify 1.2:** fresh clone contains `.env.example`; `docker compose up` with populated `.env` boots clean. — _ignore-rule ✅; backend boot ✅ (standalone, real Postgres); container-stack boot still to run on a Docker host._

---

## SESSION 2 — 🟠 HIGH: Secrets & exposure

| # | Item | File | Status | Verified | Notes |
|---|------|------|--------|----------|-------|
| 2.1 | Full-staff PII via unguarded `GET /api/users` | `UserController.java` | ✅ | ✅ | `listUsers` gated to managers; `getUser` = manager-or-self. 4 MockMvc tests added (403 for STANDARD_USER list/other, 200 for manager list + self). |
| 2.2 | Committed JWT dev-fallback secret | `JwtUtil.java`, `application.properties` | ✅ | ✅ | `JwtUtil` now WARNs on the dev fallback and hard-fails when `rrbm.security.fail-on-default-jwt-secret=true`. docker-compose sets that flag `true`, so a prod boot without `RRBM_JWT_SECRET` aborts instead of signing with a known key. Suite 152/152 (dev fallback path still boots). |
| 2.3 | Dev secrets baked into backend image | `rrbm-backend/.dockerignore` (new), `Dockerfile` | ✅ | ✅ | Added `.dockerignore` excluding `application-local.properties` (+ target/.env/logs). **Confirmed it was worse than reported**: the file sits under `src/main/resources`, so `mvnw package` bundled it *inside app.jar* in the final runtime image (not just build layers). |
| 2.4 | Permissive CORS (wildcard + credentials) | `SecurityConfig.java`, 20 controllers, `application.properties`, `docker-compose.yml` | ✅ | ✅ | Wildcard removed; origins now driven by `rrbm.cors.allowed-origins` (`RRBM_CORS_ALLOWED_ORIGINS`). Removed `@CrossOrigin(origins="*")` from all 20 controllers so they can't re-open it. **Live-verified**: preflight from allowed origin → 200 + exact `Access-Control-Allow-Origin`; disallowed origin → `403 Invalid CORS request`. ⚠ Operator must set the real domain in `.env`. |
| 2.5 | Default admin & master key (public hash) | `V63__restore_seed_data.sql:25` | ⏭️ | — | **Operational, not code-fixed.** The committed `master_key_hash` is bcrypt("password"); `rrbm.initial.master.key` falls back to `rrbm2024`. Not editing V63 (Flyway checksum / Session 3 data-loss risk). `.env` now sets a random `RRBM_INITIAL_MASTER_KEY`. **Action required:** rotate the master key + default admin password immediately after first boot. Optional follow-up: a forward migration (V64) to null the default seeded key and force re-seed from env. |

---

## SESSION 3 — 🟠 HIGH: Data-loss migration

| # | Item | File | Status | Verified | Notes |
|---|------|------|--------|----------|-------|
| 3.1 | V63 unconditional `DELETE FROM activity_log` | `V63__restore_seed_data.sql:16` | ✅ | ✅ | **Branch A confirmed.** Target DB queried live: head = **V69**, V63 recorded applied 2026-06-08 `success=t`. The DELETE fired once historically and **cannot re-run** (Flyway never re-applies a recorded migration). No code change — editing applied V63 would break checksum validation on every env past it. Fresh future deploy: DELETE hits an empty `activity_log` → harmless. Optional hygiene cleanup → tech debt below. |

### Pre-flight findings (2026-06-11)

- **Only line 16 is destructive.** The other two V63 deletes are id/name-guarded; Parts 2 & 3 (settings + 110 products) are idempotent (`ON CONFLICT DO NOTHING` / `WHERE NOT EXISTS`).
- **V63 is NOT the head migration.** On-disk set runs V1→**V69** (gaps V6/V47/V52); V64–V69 follow V63.
- **Flyway checksums are enforced.** `application.properties:28-31` → `baseline-on-migrate=true`, `out-of-order=true`, **no `validate-on-migrate` override → defaults TRUE**. Editing an *already-applied* V63 makes every environment past it fail boot on checksum mismatch (needs `flyway repair`).
- **Local dev DB is past V63** (Session 1's "66 migrations" = the 66 files on disk → at head V69).
- **Forward migration (V70) is a dead end:** within one `flyway migrate`, V63 deletes *before* any V70 runs, and V70 can't restore deleted rows. Does not prevent or recover the loss.
- **User intent:** the live DB (inventory, settings, registered users) must be preserved — not a greenfield deploy. So a backup precedes everything.

### Resolution (2026-06-11) — Branch A ✅

Version check run live against the target DB (`flyway_schema_history`):

- Head version = **V69**; V63 row present, `installed_on = 2026-06-08 09:37`, `success = t`.
- DB is **past V63** → the destructive `DELETE FROM activity_log` already executed once during setup and **will not run again** (Flyway never re-applies a recorded migration).
- **No code change** — and editing the applied V63 now would be actively harmful (checksum mismatch → boot failure on this and every env past V63).
- **Live-server safety:** a future greenfield deploy runs V63 against a brand-new, empty `activity_log`, so the DELETE removes nothing. Safe to go live as-is.

3.1 closed. No code touched.

### Deferred — TECH DEBT (optional hygiene, not a blocker)

V63 is a one-time *manual recovery* script that became permanent migration history; its `DELETE FROM activity_log` is harmless today but is a latent foot-gun (e.g. restoring a pre-V63 backup and re-migrating would wipe the log).

- **Severity:** low — does not affect current testing or first go-live.
- **Only safe window to clean it:** *before* the live server runs its first-ever migration (remove line 16 then), **or** fold it into a pre-go-live migration squash/baseline. After any DB has applied V63, it's locked.
- **Do NOT edit V63 now** — local dev + any deployed env are already past it; an edit breaks their checksum.
- **Universal safety net regardless:** always `pg_dump` the DB before any deploy: `pg_dump -U <user> -d <dbname> -F c -f rrbm_pre_deploy_backup.dump`.

---

## SESSION 4 — 🟡 MEDIUM: Auth hardening & error handling

| # | Item | File | Status | Verified | Notes |
|---|------|------|--------|----------|-------|
| 4.1 | No rate limiting / lockout on credential endpoints | `LoginAttemptService.java` (new), `AuthController.java`, `LoginAttemptServiceTest.java` (new) | ✅ | ✅ | **In-memory** per-identifier throttle: 5 consecutive failures → 15-min lockout (returns **429**); a success clears the counter. Keyed by normalized (lowercased/trimmed) identifier so case/whitespace can't bypass it. No DB migration (acceptable for single-instance; move to Redis if scaled). 5 unit tests + **live-verified**: attempts 1–5 → 401, attempt 6 → 429. |
| 4.2 | No global exception handler | `GlobalExceptionHandler.java` (new), `GlobalExceptionHandlerTest.java` (new) | ✅ | ✅ | `@RestControllerAdvice` returns consistent `{"status","message"}` JSON. Handles validation (400), unreadable body (400), `ResponseStatusException` (passthrough), and a catch-all (500, generic message, real cause logged server-side only — no stack-trace leak). Existing per-controller try/catch untouched (only uncaught exceptions reach the advice; Spring Security 401/403 unaffected). 2 standalone MockMvc tests. |
| 4.3 | Backend has no readiness/health gate | `pom.xml`, `application.properties`, `SecurityConfig.java`, `docker-compose.yml` | ✅ | ✅ | Added `spring-boot-starter-actuator`; exposed **only** `health`, `show-details=never`. `/actuator/health` permitted unauthenticated in `SecurityConfig`. docker-compose: `backend` healthcheck (busybox `wget` → grep `UP`) + frontend `depends_on: backend: condition: service_healthy`. **Found & fixed during verify:** the `spring-boot-starter-mail` auto-registered `MailHealthIndicator` reported DOWN (Gmail SMTP auth fail) and dragged aggregate health to 503 — disabled via `management.health.mail.enabled=false` so mail can't block readiness (DB still gates). **Live-verified**: health 200 `{"status":"UP"}` unauthenticated, protected endpoint still 401. |

**Verify 4.1:** 6th bad login for one identifier returns 429. — _✅ live (attempts 1–5 → 401, 6 → 429); +5 unit tests._
**Verify 4.2:** uncaught controller exception → clean JSON, no stack trace; explicit status preserved. — _✅ standalone MockMvc (500→generic message; 404 reason preserved)._
**Verify 4.3:** `/actuator/health` → 200 `{"status":"UP"}` unauthenticated; DB down → DOWN. — _✅ live 200/UP; mail excluded from gate, DB retained._

---

## SESSION 5 — ⚪ LOW / STYLE: Frontend polish & cleanup

| # | Item | File | Status | Verified | Notes |
|---|------|------|--------|----------|-------|
| 5.1 | Hardcoded colors bypass dark-mode tokens | `app.js`, `styles.css` | 🔄 | ✅ | **Scoped fix (live UI only).** Pre-flight found this is ~5x bigger than labelled: **577** hardcoded `#hex` in a 12.5k-line file, the *majority* live-UI (not print as first assumed), and intermixed with report/PDF builders that **must stay literal** (print windows are separate docs with no `styles.css`/`[data-theme]`, so `var(--…)` would resolve empty). Per decision, converted only the confirmed **daily-orders table + order-history render** (status sub-lines, empty-state rows, source/agent labels, COD/cancel/collected meta) to tokens. Added 4 accent text tokens — `--accent-success/warn/danger/info` — to **both** `[data-theme]` blocks (light keeps prior hex for parity; dark uses brighter variants matching the badge palette). Action-button backgrounds (`background:#…;color:#fff`) left as intentional literals. **Live-verified** in preview: all 5 converted token types resolve to correct **dark** values under `body[data-theme="dark"]` (the real switch is on `<body>`, not `<html>`); no console errors. **Remaining ~live-UI colors elsewhere = deferred tech debt** (see §Session 5 below). |
| 5.2 | `DM Mono` referenced but not loaded | `styles.css:6` | ✅ | ✅ | Added `family=DM+Mono:wght@400;500` to the Google Fonts `@import`. **Live-verified**: `document.fonts.check("12px 'DM Mono'")` → `true` (previously fell back to default monospace). |
| 5.3 | nginx serves docs/logs | `rrbm_frontend/.dockerignore` (new) | ✅ | 🔄 | Build context is `./rrbm_frontend` and the Dockerfile does `COPY rrbm-frontend /usr/share/nginx/html` — wholesale, exposing `docs/*.md` + `frontend.log` at the web root (info disclosure). Added `.dockerignore` **at the context root** (not the subdir — Docker only reads `<context>/.dockerignore`) excluding `rrbm-frontend/docs/` + `**/*.log`. Verified the right path/patterns; container build itself still to run on a Docker host (Docker unavailable here). |

### Session 5 residual — TECH DEBT (deferred dark-mode color cleanup)

5.1 was scoped to the highest-traffic live screen. The remaining hardcoded `#hex` in `app.js` that render in the live DOM (inventory tables, dashboards, modals, etc.) still bypass the theme tokens in dark mode.

- **Severity:** low — cosmetic only; light mode unaffected.
- **Why deferred:** ~400–500 scattered occurrences across a 12.5k-line file, *intermixed* with print/report/PDF HTML builders that must keep literal hex (separate documents with no stylesheet/`[data-theme]`). Each call site needs a live-DOM-vs-print judgment — not a safe blind find-replace.
- **How to resume:** the token vocabulary now exists (`--accent-success/warn/danger/info` + `--text-*`/`--badge-*`). Walk each live render function (skip anything written via `w.document.write(...)`), swap inline `color:#HEX` → the matching `var(--…)`, and browser-verify that screen in dark mode.

---

## Change log

| Date | Session/Item | Change | By |
|------|--------------|--------|-----|
| 2026-06-11 | — | Progress file created from audit | auditor |
| 2026-06-11 | 1.1 | Approach A authorization gate added to `createUser`/`updateUser` (UserController). Compile clean, full suite 142/142 green. | claude |
| 2026-06-11 | 1.2 | `.gitignore` negation tracks `.env.example`; local `.env` generated with random secrets, stays ignored. | claude |
| 2026-06-11 | 1.1 | Added `UserCreateUpdateGateTest` (6 MockMvc tests, all green; suite now 148/148). Live HTTP verification: real STANDARD_USER token → `POST`/`PUT /api/users` both returned 403 against a running instance. | claude |
| 2026-06-11 | 1.2 | Backend boot verified standalone against local Postgres (Flyway validated 66 migrations, app `Started`). Note: full `docker compose up` not runnable here — Docker not installed; must run on a Docker host pre-deploy. | claude |
| 2026-06-11 | 2.1 | Gated `GET /api/users` (managers) and `GET /api/users/{id}` (manager-or-self); +4 MockMvc tests. | claude |
| 2026-06-11 | 2.2 | `JwtUtil` warns on dev fallback secret; hard-fails when `rrbm.security.fail-on-default-jwt-secret=true` (enabled in docker-compose). | claude |
| 2026-06-11 | 2.3 | Added `rrbm-backend/.dockerignore` — `application-local.properties` was being packaged into `app.jar`; now excluded. | claude |
| 2026-06-11 | 2.4 | Env-driven CORS (`RRBM_CORS_ALLOWED_ORIGINS`), removed wildcard + 20 `@CrossOrigin("*")`. Live-verified preflight: allowed→200, disallowed→403. | claude |
| 2026-06-11 | 2.5 | Classified operational (no safe code fix without touching V63). `.env` sets random master key; rotation required post-boot. | claude |
| 2026-06-11 | — | Full suite 152/152 (added 4 PII-gate tests to `UserCreateUpdateGateTest`). | claude |
| 2026-06-11 | 3.1 | Pre-flight complete: confirmed line 16 is the only destructive statement, V63 not at head (V1→V69), Flyway checksum validation enforced, V70 forward-migration ruled out. Status → 🔄, blocked on operator's target-DB version check (backup-first). No code touched. | claude |
| 2026-06-11 | 3.1 | **Resolved (Branch A).** Queried live `flyway_schema_history`: head = V69, V63 applied 2026-06-08 `success=t` → DELETE inert, can't re-run; harmless on a fresh deploy (empty log). Closed ✅, no code change. Optional V63 cleanup logged as deferred tech debt (window = before live server's first migration). | claude |
| 2026-06-11 | 4.2 | Added `GlobalExceptionHandler` (`@RestControllerAdvice`) → consistent `{"status","message"}` JSON; catch-all logs cause server-side, no stack-trace leak. +2 standalone MockMvc tests. | claude |
| 2026-06-11 | 4.3 | Added `spring-boot-starter-actuator`; exposed only `health` (`show-details=never`); permitted `/actuator/health` in `SecurityConfig`; docker-compose backend healthcheck + frontend `service_healthy`. **Verify caught** mail `MailHealthIndicator` forcing DOWN (SMTP auth fail) → disabled `management.health.mail.enabled=false`. Live: 200/UP. | claude |
| 2026-06-11 | 4.1 | Added in-memory `LoginAttemptService` (5 fails → 15-min lockout → 429; success clears; identifier normalized). Wired into `AuthController.login`. +5 unit tests. Live-verified attempts 1–5 → 401, 6 → 429. | claude |
| 2026-06-11 | — | Full suite **159/159** green (added 2 exception-handler + 5 lockout tests). Session 4 closed 3/3. | claude |
| 2026-06-11 | 5.2 | Added `DM Mono` to the Google Fonts `@import` (`styles.css:6`). Live-verified `document.fonts.check` → true. | claude |
| 2026-06-11 | 5.3 | Added `rrbm_frontend/.dockerignore` (context root) excluding `rrbm-frontend/docs/` + `**/*.log` — they were being `COPY`d into the public nginx root. Container build to confirm on a Docker host. | claude |
| 2026-06-11 | 5.1 | Pre-flight: item is ~5x larger than labelled (577 hex, mostly live-UI, intermixed with print builders that must stay literal). Per user decision, scoped to the daily-orders table + order-history render: added `--accent-success/warn/danger/info` tokens to both themes and converted those fragments. Live-verified all resolve to correct dark values under `body[data-theme=dark]`; no console errors. Remainder logged as deferred tech debt. Status 🔄 (scoped-complete). | claude |

---

## How to use this file

1. When you start an item, set its **Status** to 🔄 and add a note.
2. When code is merged, set **Status** to ✅; run the verify step and tick **Verified**.
3. Update the **Overall progress** table counts + percentage.
4. Add a row to the **Change log** for each meaningful update.
5. Flip the **Deploy gate** to ✅ only once every Session 1 item is ✅ + Verified.
