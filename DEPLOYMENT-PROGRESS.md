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

## Session 5 — On-host first boot, ON-SITE — Jun 18, 2026 — 🟡 IN PROGRESS

On-site at the office host. This is the live first boot that Session 4 paused for. The host
is a fresh Windows box (user `PC`, no Docker, no `.env`). Pre-flight blockers from Session 4
are now being cleared in order.

### Resolved this session
| Item | Result |
|------|--------|
| **Host LAN IP assigned** | `192.168.0.234` (adapter `Ethernet 2`, gateway/DNS `192.168.0.1`, MAC `00-E0-4C-A0-D6-52`). Port 80 is **free** (no IIS/W3SVC). |
| **CORS placeholder resolved** | `RRBM_CORS_ALLOWED_ORIGINS=http://192.168.0.234` — the Session-4 🔴 blocker is cleared. |
| **`.env` created on host** | Strong random secrets via `openssl`: `DB_PASSWORD` (32 hex), `RRBM_JWT_SECRET` (64 hex), `RRBM_INITIAL_MASTER_KEY` (24 hex), real CORS origin, `RRBM_FAIL_ON_DEFAULT_JWT_SECRET=true`. Confirmed **gitignored** (`git check-ignore .env` ✓). Does not travel via git — lives only on the host. |
| **Docker Desktop installed** | `winget install Docker.DockerDesktop` → **v4.78.0**, Docker CLI **29.5.3**. Exit 0. |

### Boot completed — stack is LIVE ✅
Post-reboot, WSL2 active, Docker engine running. Full sequence executed:
`compose config` ✓ → `build` ✓ → image secret-exclusion verified ✓ → `up -d` → **Flyway head V74** ✓
→ **V19 purge** ✓. All three containers healthy; app serves on `http://192.168.0.234` (and `localhost`).

| Container | Status |
|-----------|--------|
| `rrbm_db` (postgres:16-alpine) | Up (healthy) |
| `rrbm_backend` | Up (healthy) — 8080 **not** host-exposed (fix 1E verified) |
| `rrbm_frontend` (nginx) | Up — `0.0.0.0:80→80` |

Smoke test: `GET /` → 200 (SPA, 253 KB) on localhost + LAN IP; `GET /api/products` → 401
(proxy works, auth enforced); `:8080` not reachable from host (correct).

### 🐞 Two fresh-deploy bugs found & fixed on-host (NOT in any prior audit)
Both only manifest on a **clean DB / image without `application-local.properties`** — i.e. they
were invisible in dev and would have blocked any first deploy.

1. **Flyway V5 ↔ V7 ordering bug.** `V5__phase5_features.sql` `ALTER`s `activity_log`, but that
   table is created in `V7`. On a fresh DB Flyway applies in ascending order (V5 before V7) →
   `relation "activity_log" does not exist`, backend crash-loop. In dev it never surfaced because
   V7 was applied before V5 was authored (out-of-order history).
   **Fix:** prepended V7's exact `CREATE TABLE IF NOT EXISTS activity_log (...)` to the top of V5,
   making it self-contained; V7's own `IF NOT EXISTS` is then a no-op. *(Edits file content, not
   version → changes V5's checksum. See "Uncommitted changes" note below.)*
2. **Missing `JavaMailSender` bean.** `LowStockEmailService` requires a `JavaMailSender` (hard
   constructor dep) even though the email feature is flag-gated off (`rrbm.mail.enabled=false`).
   Spring Boot only creates that bean when `spring.mail.host` is set — config that lived in the
   (correctly-excluded) `application-local.properties`. Result: context init failed.
   **Fix:** added `SPRING_MAIL_HOST: ${SPRING_MAIL_HOST:-localhost}` to the backend service in
   `docker-compose.yml` (runtime env, no rebuild). Placeholder host → bean exists; no SMTP
   connection unless mail is enabled later. Also removed the obsolete compose `version:` key.

### V19 purge result (committed)
Deleted 142 orders, 283 order_items, 141 transactions, 42 expense_items, 14 expenses,
35 daily_reports, 1 delivery_log (+item); the 1 test payable cascade-deleted via the
V74 payables→delivery_log FK. **Post-purge:** all transactional tables = 0; seed intact
(products 111, settings 12, users 1 = `admin@rrbm.com`).

### ⚠️ Uncommitted changes on the host (tracked files)
`V5__phase5_features.sql` and `docker-compose.yml` are edited but **not committed**. Note: the
V5 edit changes its Flyway checksum, so a pre-existing dev DB will report a checksum mismatch on
next boot (run `flyway repair` or rebuild the dev DB). Commit + push when ready so the fixes are
in the canonical history.

### 🔑 Admin login — V2 seed password was wrong (3rd fresh-deploy issue)
The `V2__seed_initial_data.sql` comment says the super-admin password is `ChangeMe123!`, but
the seeded BCrypt hash (`$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy`) does
**not** verify against `ChangeMe123!`, `password`, or any common candidate (checked via
`pgcrypto crypt()` — no login attempts consumed). The intended plaintext is effectively
unrecoverable. Combined with the in-memory login lockout (`LoginAttemptService`, 5 tries /
15 min), the default credentials were a dead end.

**Resolution:** restarted `rrbm_backend` to clear the lockout, then reset the admin password
directly in the DB to a known temp value using pgcrypto (Spring-compatible `$2a$` bcrypt):
`UPDATE users SET password_hash = crypt('<TEMP_PASSWORD>', gen_salt('bf',10)) WHERE email='admin@rrbm.com';`
(the temp password was shared out-of-band during the deploy session, **not** stored here — it
must be changed in the UI on first login.)
Verified **200 + token** through the real `/api/auth/login`. **This temp password must be
changed in the UI immediately** (it lives in the deploy transcript). The bogus
`settings.master_key_hash` row (same hash) is legacy/unused — master key is seeded from
`RRBM_INITIAL_MASTER_KEY`; no action.

> **Repo follow-up:** fix the V2 seed so a future fresh deploy has working, documented
> credentials (regenerate the hash to match a known password, or correct the comment).

### Still TODO this session
| Item | Status | Notes |
|------|--------|-------|
| **First-boot security rotation** | 🟡 **login restored — rotation still required** | See "🔑 Admin login" note below. Temp password set via DB; user must change password, set admin security key, and rotate master key in the UI. |
| **Backups** | 🟡 built & tested; **schedule pending** | Script `C:\rrbm-backups\rrbm-backup.ps1` (online `pg_dump -Fc`, copy out, 14-day retention) authored + run once (valid 231 KB archive); **full restore test into a throwaway DB passed** (products 111, settings 12 — live DB untouched). Nightly Task Scheduler job **not yet registered** (harness blocks persistent-task creation without explicit per-action OK; see register command in doc/below). **Still need: one off-machine copy** (USB/share) — `C:\rrbm-backups` is on the same disk as the DB. |
| Firewall inbound TCP 80 (elevated) + DHCP reservation (router) | ⏳ | See manual items above. Verify reachability from another LAN PC. |

### Server operation & resilience (Jun 18)
This unit is now the **always-on office server**; staff reach it from their own devices at
`http://192.168.0.234`. Nobody works on the box directly — lock it (Win+L), don't sign out.
- ✅ **Sleep disabled** (`powercfg /change standby-timeout-ac 0`, hibernate + disk too). Screen
  may turn off; machine stays running.
- ✅ **Containers auto-restart** (`restart: unless-stopped`); ✅ **Docker Desktop auto-starts on
  login** (`autoStart: true`).
- ❌ **Windows auto-login is OFF** (`AutoAdminLogon=0`). After a power loss the box stops at the
  lock screen and Docker won't start until someone signs in → **app stays down**. Decide:
  enable auto-login (hands-off recovery, physically-secure the unit) vs. manual login after
  outages. A small **UPS** is recommended either way.

### Register the nightly backup task (run in a normal PowerShell window)
```powershell
$action  = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument '-NonInteractive -NoProfile -ExecutionPolicy RemoteSigned -File "C:\rrbm-backups\rrbm-backup.ps1"'
$trigger = New-ScheduledTaskTrigger -Daily -At 2:00AM
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 30)
Register-ScheduledTask -TaskName 'RRBM Nightly Backup' -Action $action -Trigger $trigger -Settings $settings -Description 'Nightly pg_dump of rrbm_db.' -Force
```

### Manual items deferred (need elevated shell / router — NOT blocking first boot)
- **Firewall inbound TCP 80** — `New-NetFirewallRule -DisplayName "RRBM web" -Direction Inbound -Protocol TCP -LocalPort 80 -Action Allow -Profile Private` (attempted non-elevated → Access denied; run elevated).
- **DHCP reservation** on router `192.168.0.1` for MAC `00-E0-4C-A0-D6-52` → `192.168.0.234` (so the address staff type never floats).

### Remaining sequence after Docker engine is up (resume here)
1. `docker compose config` — all `${VARS}` resolve from `.env`, no warnings.
2. `docker compose build` → inspect backend image: `application-local.properties` **absent** (validates `.dockerignore`).
3. `docker compose up -d` → confirm **Flyway head V74** in `docker compose logs backend`, no errors.
4. Run the **V19 test-data purge** (block below).
5. First-boot security: rotate `admin@rrbm.com` password, set admin security keys, rotate master key, disable inactive accounts.
6. Backups: nightly `pg_dump` via Windows Task Scheduler + one off-machine copy.

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
*(Status as of Session 5, Jun 18 — see Session 5 section above.)*
1. ✅ **Assign the host's LAN IP/hostname** and set `RRBM_CORS_ALLOWED_ORIGINS` in `.env`
   to that exact origin. **Done:** `http://192.168.0.234`. Placeholder replaced; `.env`
   created on host with strong secrets.
2. ✅ `docker compose config` — all variables resolved, no warnings (only the obsolete
   `version:` warning, since removed).
3. ✅ `docker compose build` + backend-image inspection — `application-local.properties`
   **absent** from the jar (only `application.properties` present). `.dockerignore` verified.
4. ✅ `pg_dump` any existing DB first — **N/A**, brand-new host.
5. ✅ `docker compose up -d`; **Flyway reached head V74** ("Successfully applied 71
   migrations, now at version v74"), no errors. *(Required two code fixes first — see
   Session 5 "Two fresh-deploy bugs".)*
6. ✅ Ran the **V19 purge** — books clean, seed intact.
7. 🔴 **First-boot security: NOT DONE YET — do now** (default admin password is live/public).

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
