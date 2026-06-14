# RRBM — LAN Deployment Progress & First-Boot Runbook

Living status doc for the office-LAN deployment. Pairs with `DEPLOYMENT-AUDIT.md`
(the original findings) and the plan at
`C:\Users\franc\.claude\plans\okay-let-s-do-your-witty-axolotl.md`.

**Target:** office LAN only — one host running Docker Compose (db + backend + nginx
frontend on port 80). No public domain, no internet exposure.

---

## Session 3 — Deploy-prep config (LAN scope) — Jun 13, 2026

| Item | Status | Notes |
|------|--------|-------|
| `.dockerignore` excludes `application-local.properties` | ✅ Done | `rrbm-backend/.dockerignore` (build-context root) excludes `src/main/resources/application-local.properties` + `.env*` + build cruft. The secret file really exists (644 B), so the exclusion is meaningful. **Docker rebuild confirmation deferred to Session 4** (no Docker on the dev machine). |
| `.env` authored with strong secrets | ✅ Done | `DB_PASSWORD` (32), `RRBM_JWT_SECRET` (64 hex), `RRBM_INITIAL_MASTER_KEY` (24), `RRBM_FAIL_ON_DEFAULT_JWT_SECRET=true`. Gitignored + untracked. |
| `.env` CORS origin | ⏳ Placeholder | Office host LAN IP/hostname not assigned yet. Set to `http://SET-OFFICE-HOST-LAN-ORIGIN-BEFORE-FIRST-BOOT` — **must be edited before Session-4 first boot** (see below). |
| All compose `${VARS}` resolve from `.env` | ✅ Verified (static) | DB_PASSWORD, RRBM_JWT_SECRET, RRBM_INITIAL_MASTER_KEY, RRBM_CORS_ALLOWED_ORIGINS all present. `docker compose config` dry-run deferred to Session 4. |
| V63 audit-trail wipe decision | ✅ Resolved | **Fresh DB chosen** → see below. |

### CORS / architecture note
In production the nginx frontend **proxies `/api/` to `backend:8080` same-origin**
(`app.js` sets `API_BASE=''` for any non-localhost host). So browser→API traffic is
same-origin and CORS is low-risk on a trusted LAN. We still set the exact origin in
`RRBM_CORS_ALLOWED_ORIGINS` (no wildcard) per the audit. The backend reads this var
(`SecurityConfig`); the old "wildcard CORS" audit finding is **stale**.

---

## V63 decision — FRESH DB → no action

`V63__restore_seed_data.sql` does three things: deletes a couple of test rows,
`DELETE FROM activity_log`, then seeds 12 settings + 110 products.

**Office DB = brand-new (confirmed).** On a fresh host:
- Flyway runs V1→V74 in order. `DELETE FROM activity_log` runs against an **empty**
  log — harmless.
- V63 **seeds the 110-product catalog + settings** — this is desired for go-live.
- **No edit to V63** (never alter an applied migration — checksum break).

**Latent foot-gun (documented, not active):** if a *pre-V63* backup is ever restored
and re-migrated, V63 would wipe `activity_log`. Mitigation: always `pg_dump` before any
migration, and never restore a pre-V63 dump into the live DB without a baseline.

---

## Session 4 — Pre-flight (Jun 13, 2026) — ⏸ PAUSED (no on-site access yet)

Session 4 is the **on-host first boot**; it runs on the office machine. Pre-flighted the
repo from the dev machine (at home) — everything that *can* be verified statically is green.
**Not started:** the host is not yet accessible (building from home), so the live boot,
backup, and smoke steps wait until on-site.

### Pre-flight results (static, dev machine)
| Check | Result |
|-------|--------|
| Session 3 committed + tag `v-predeploy-lan` present | ✅ |
| `.env` strong secrets + `RRBM_FAIL_ON_DEFAULT_JWT_SECRET=true` | ✅ |
| `nginx.conf` proxies `/api/` → `backend:8080` same-origin + SPA fallback + security headers | ✅ (`rrbm_frontend/nginx.conf`, baked into the frontend image) |
| `/actuator/health` is `permitAll()` (`SecurityConfig.java:62`) **and** excluded from PageAccessInterceptor (`WebMvcConfig.java:26`) | ✅ compose readiness gate works — `frontend` starts only after `backend` is healthy |
| `app.js` sets `API_BASE=''` for non-localhost hosts (`js/app.js:15`) | ✅ same-origin in prod |
| `.dockerignore` excludes `application-local.properties` | ✅ present (build-context root) |

### Blockers / open items before first boot
1. 🔴 **CORS origin still a placeholder.** `.env` has
   `RRBM_CORS_ALLOWED_ORIGINS=http://SET-OFFICE-HOST-LAN-ORIGIN-BEFORE-FIRST-BOOT`.
   The office host LAN IP/hostname is **not available yet** — assign on-site and replace
   the placeholder before first boot. (Lower risk because traffic is same-origin via nginx,
   but it is not a valid origin value.)
2. ⚠️ **Three steps can only run on the host** (no Docker on the dev machine):
   `docker compose config`, `docker compose build` + backend-image secret-exclusion check,
   and `docker compose up -d` + Flyway-head check. Sequenced into the on-host runbook below.
3. ✅ **DB is brand-new** — confirmed; fresh-DB assumptions (no pre-existing `pg_dump`, V63
   harmless) hold.

**Next action when on-site:** assign the host LAN origin → finalize `.env` → run the
finalize checklist below in order.

---

## First-boot facts (fresh DB) — for Session 4

1. **Login:** the only seeded account is super-admin **`admin@rrbm.com` / `ChangeMe123!`**
   (Flyway `V2`). First-boot task: log in, rotate this password, set a per-user admin
   security key. (Dev accounts like `jfyg0310` do **not** exist on a fresh DB.)
2. **Master key:** seeded from **`RRBM_INITIAL_MASTER_KEY` (.env)** into the `master_keys`
   table on first boot (`RrbmBackendApplication.seedMasterKey`, only when the table is
   empty). V63's `settings.master_key_hash` row (BCrypt of the old `rrbm2024`) is **legacy
   and read by no Java code** — no conflict. Still: rotate the master key via Settings
   after go-live.
3. **V19 test data ⚠️:** `V19__test_seed.sql` runs on a fresh deploy too and inserts
   `TEST_`-tagged **orders / order_items / transactions / daily_reports / expenses /
   payables / delivery_log**. V63 does *not* purge these. **Purge before go-live** so the
   books start clean (SQL below). It does **not** create extra user accounts and does
   **not** adjust product stock (raw inserts).

### V19 test-data purge (run once on the fresh DB, before go-live)
Preview first, run inside a transaction, verify counts, then COMMIT. Confirm column
names against the live schema before COMMIT.
```sql
BEGIN;
-- preview
SELECT count(*) FROM orders        WHERE customer_name LIKE 'TEST\_%';
SELECT count(*) FROM transactions  WHERE notes = 'Test seed sale' OR transaction_code LIKE 'TEST-%';
SELECT count(*) FROM daily_reports WHERE notes = 'TEST_SEED';
SELECT count(*) FROM expenses      WHERE admin_name LIKE 'TEST\_%';
SELECT count(*) FROM payables      WHERE supplier_name LIKE 'TEST%';
SELECT count(*) FROM delivery_log  WHERE receipt_number = '888001';

-- delete in FK-safe order
DELETE FROM transactions        WHERE notes = 'Test seed sale' OR transaction_code LIKE 'TEST-%';
DELETE FROM order_items         WHERE order_id IN (SELECT id FROM orders WHERE customer_name LIKE 'TEST\_%');
DELETE FROM orders              WHERE customer_name LIKE 'TEST\_%';
DELETE FROM expense_items       WHERE expense_id IN (SELECT id FROM expenses WHERE admin_name LIKE 'TEST\_%');
DELETE FROM expenses            WHERE admin_name LIKE 'TEST\_%';
DELETE FROM daily_reports       WHERE notes = 'TEST_SEED';
DELETE FROM delivery_log_items  WHERE delivery_log_id IN (SELECT id FROM delivery_log WHERE receipt_number = '888001');
DELETE FROM delivery_log        WHERE receipt_number = '888001';
DELETE FROM payables            WHERE supplier_name LIKE 'TEST%';
-- COMMIT;  -- uncomment after verifying counts; otherwise ROLLBACK;
```

---

## Office server prep — Windows host (do BEFORE going on-site)

Deploy target is a **Windows** office server (confirmed Jun 13). The repo ships to it via
**GitHub** (`origin` = `github.com/jfy-g0395/rrbm-report-inventory`, private) — the server
does a fresh `git clone` and builds with Docker. Lay all of this in advance; only the LAN IP
(item 4) needs you physically on-site.

| # | Prepare | Detail / gotcha |
|---|---------|-----------------|
| 1 | **Docker Desktop + WSL2** | Needs the WSL2 backend → requires **virtualization enabled in BIOS/UEFI** (VT-x/AMD-V) + Windows features *Virtual Machine Platform* and *WSL*. Check first; may need a BIOS reboot. |
| 1a | **Unattended auto-start** | `restart: unless-stopped` only restarts *containers* once the engine runs. Set Docker Desktop → **"Start when you sign in"** **and** enable machine **auto-login** (or run the engine as a service) so a power-loss/reboot brings the stack back with nobody logged in. |
| 2 | **Git for Windows + GitHub auth** | Repo is private → server needs a **Personal Access Token** (simplest) or SSH key to clone. *Alt:* bring the repo on USB (but loses easy `git pull` for updates). |
| 3 | **Port 80 free** | Windows IIS / *World Wide Web Publishing Service* often holds it. Check `netstat -ano | findstr :80`; stop/disable the owner (frontend binds `80:80`). |
| 4 | **Static LAN IP** ⏳on-site | Static IPv4 on the adapter **or** a DHCP reservation on the router. Becomes both the `RRBM_CORS_ALLOWED_ORIGINS` value and the address staff type — must not float. |
| 5 | **Firewall — inbound TCP 80** | `New-NetFirewallRule -DisplayName "RRBM web" -Direction Inbound -Protocol TCP -LocalPort 80 -Action Allow` (private profile). |
| 6 | **Backup destination + Task Scheduler** | A second drive / USB / network share + one off-machine copy. Nightly `pg_dump` runs via **Windows Task Scheduler** (a `.ps1`/`.bat` calling `docker exec rrbm_db pg_dump ...`) — script authored in Session 4. |
| 7 | **Disk** | ~5–10 GB free for the Postgres volume + Docker images. |

### `.env` does NOT travel via git (it's gitignored)
`git clone` brings everything **except** `.env`. Before `docker compose up`, the repo root on
the server must contain `.env` with the **real LAN origin** in `RRBM_CORS_ALLOWED_ORIGINS`.
Two options:
- **Reuse the dev `.env`** — copy it over USB / paste values. Its secrets are already strong
  random values; just fix the CORS line.
- **Regenerate on the server** — recreate from `.env.example` with fresh `openssl rand -hex 32`
  etc. (cleaner dev/prod separation, not required).

---

## Before Session-4 first boot — finalize checklist
1. **Assign the host's LAN IP/hostname** and set `RRBM_CORS_ALLOWED_ORIGINS` in `.env`
   to that exact origin, e.g. `http://192.168.1.50` (add `,http://rrbm-host` if a
   hostname is also used). Replace the `SET-OFFICE-HOST-LAN-ORIGIN-BEFORE-FIRST-BOOT`
   placeholder.
2. `docker compose config` — confirm every variable resolves, no warnings.
3. `docker compose build` then inspect the backend image to confirm
   `application-local.properties` is **absent** (verifies `.dockerignore`):
   `docker run --rm rrbm_daily-backend sh -c "ls -la /app && unzip -l app.jar | grep -i application-local || echo 'NOT in jar ✓'"`.
4. `pg_dump` any existing DB first (N/A for a brand-new host).
5. `docker compose up -d`; confirm Flyway reaches head **V74** in
   `docker compose logs backend` with no errors.
6. Run the **V19 purge** above.
7. First-boot security: rotate `admin@rrbm.com` password, set admin security keys,
   rotate the master key, disable any inactive accounts.

---

## Deferred LAN hardening (optional, post-go-live — NOT blocking)
On a trusted office LAN these are out of the critical path; revisit if scope changes
(e.g. remote access):
- **HTTPS/TLS** termination (self-signed or internal CA at nginx).
- **nginx rate-limiting** for login / public endpoints.
- **CSP / HSTS** response headers.
- **Drop the `postgres` superuser** for app DB access — create a least-privilege role
  (Phase 2B).

---

## DEPLOYMENT-AUDIT.md cross-reference (status)
| Audit finding | Sev | Status for LAN deploy |
|---------------|-----|-----------------------|
| Required secrets crash app if unset | CRITICAL | ✅ `.env` provides all; `RRBM_FAIL_ON_DEFAULT_JWT_SECRET=true` |
| Committed JWT dev-fallback secret | HIGH | ✅ Overridden by `RRBM_JWT_SECRET`; boot refuses the fallback |
| Dev secrets baked into image | HIGH | ✅ `.dockerignore` excludes `application-local.properties` (confirm on build) |
| Default super-admin & master key (public hash) | HIGH | ⏳ Procedural — rotate at first boot (Session 4) |
| V63 wipes audit trail | HIGH | ✅ N/A on fresh DB (runs against empty log); foot-gun documented |
| Permissive CORS | HIGH | ✅ Stale finding — exact origin via env var, no wildcard |
