# RRBM Pre-Deployment Progress Tracker
> Guide: `docs/DEPLOYMENT-GUIDE.md`  
> Last updated: 2026-06-06 | Session 1  
> Update this file at the end of every deployment session.

---

## Overall Status

| Phase | Description | Status |
|---|---|---|
| Phase 1 | Critical security fixes (code) | 🟢 Done (1C blocked on domain) |
| Phase 2 | Server setup | 🔴 Not started |
| Phase 3 | First-boot sequence | 🔴 Not started |
| Phase 4 | Backup strategy | 🔴 Not started |
| Phase 5 | Post-go-live checks | 🔴 Not started |

**Legend:** 🔴 Not started · 🟡 In progress · 🟢 Done · ⏸ Blocked

---

## Phase 1 — Critical Security Fixes

> All items here require code changes + commit before deploying.

| # | Task | File | Status | Notes |
|---|---|---|---|---|
| 1A | Remove hardcoded `"rrbm2024"` seed; read from env var | `RrbmBackendApplication.java`, `application.properties`, `docker-compose.yml`, `.env.example` | 🟢 | All 4 files updated; local dev fallback `:rrbm2024` added; production reads from env var |
| 1B | Lock `verify-password` behind JWT; remove from permitAll | `AuthController.java`, `SecurityConfig.java` | 🟢 | Removed permitAll line and updated javadoc; no AuthController change needed |
| 1C | Replace CORS wildcard with production domain | `SecurityConfig.java:84` | ⏸ | Blocked: domain not yet confirmed |
| 1D-a | Remove `show-sql=true` and `format_sql=true` | `application.properties:23-25` | 🟢 | Removed both lines; dialect line kept |
| 1D-b | Fix logging package `com.rrbm` → `rrbm_backend` | `application.properties:52` | 🟢 | Changed to `logging.level.rrbm_backend=INFO` |
| 1E | Remove backend port `"8080:8080"` from docker-compose | `docker-compose.yml:37` | 🟢 | Removed entire `ports:` block from backend service |

---

## Phase 2 — Server Setup

> Requires access to the production server. Domain must be confirmed first.

| # | Task | Status | Notes |
|---|---|---|---|
| 2A | Create `.env` file on server with all 3 secrets | 🔴 | Needs: domain, server access |
| 2B | Create `rrbm_app` DB user; remove postgres superuser dependency | 🔴 | After first-boot only |
| 2C | Add login rate limiting to nginx | 🔴 | |
| 2D | Configure HTTPS / TLS | ⏸ | Blocked: domain not confirmed |
| 2E | Add CSP and HSTS headers to nginx | 🔴 | After HTTPS is live |

---

## Phase 3 — First-Boot Sequence

> Do in exact order. Confirm each step in logs before proceeding.

| Step | Action | Status | Notes |
|---|---|---|---|
| 3.1 | Deploy files to server | 🔴 | |
| 3.2 | Confirm `.env` exists with all secrets | 🔴 | |
| 3.3 | `docker-compose build` | 🔴 | |
| 3.4 | `docker-compose up -d` | 🔴 | |
| 3.5 | Confirm Flyway applied V58 in logs | 🔴 | |
| 3.6 | Log in as SUPER_ADMIN | 🔴 | |
| 3.7 | Change master key from Settings UI | 🔴 | Do not skip |
| 3.8 | Set `adminSecurityKey` for all admin accounts | 🔴 | |
| 3.9 | Disable inactive employee accounts | 🔴 | |
| 3.10 | Switch DB to `rrbm_app` user (Phase 2B) | 🔴 | |

---

## Phase 4 — Backup Strategy

| # | Task | Status | Notes |
|---|---|---|---|
| 4.1 | Create `/var/backups/rrbm/` on server | 🔴 | |
| 4.2 | Add nightly `pg_dump` cron job | 🔴 | |
| 4.3 | Add 7-day purge cron job | 🔴 | |
| 4.4 | Test restore procedure on staging/test DB | 🔴 | Required before go-live |
| 4.5 | Confirm off-server backup copy (USB / cloud) | 🔴 | |

---

## Phase 5 — Post Go-Live Checks

| # | Check | Status | Notes |
|---|---|---|---|
| 5.1 | Login works | 🔴 | |
| 5.2 | Wrong credentials returns 401 | 🔴 | |
| 5.3 | Rate limiting fires on 6+ rapid login attempts | 🔴 | |
| 5.4 | `http://` redirects to `https://` | ⏸ | After HTTPS done |
| 5.5 | Port 8080 is NOT reachable from outside | 🔴 | |
| 5.6 | End-to-end order → report test | 🔴 | |
| 5.7 | Close daily report and confirm it locks | 🔴 | |
| 5.8 | `docker-compose logs backend` shows no errors | 🔴 | |

---

## Blocked Items

| Item | Blocked By | Who Resolves |
|---|---|---|
| CORS domain lock (1C) | Production domain not confirmed | You |
| HTTPS setup (2D) | Domain not confirmed | You |
| Server setup (Phase 2) | Server/host not provisioned | You |

---

## Decisions Locked

| Decision | Rationale |
|---|---|
| JWT secret via env var `RRBM_JWT_SECRET` | Already implemented; do not move to DB |
| Master key BCrypt-hashed in DB | Correct; never store plaintext |
| `flyway.out-of-order=true` kept | Intentional; migration history has gaps |
| Phase 1 must be complete before server work | Code fixes must be in the deployed image |

---

## Risk Register

| Risk | Severity | Mitigation |
|---|---|---|
| `"rrbm2024"` is in git history | HIGH | Rotate key immediately on first boot via admin UI |
| No backup exists yet | HIGH | Phase 4 must be done before go-live |
| Port 8080 exposed until 1E is applied | HIGH | Apply 1E before pushing to server |
| verify-password open to brute force | HIGH | 1B fix required before any public exposure |
| DB on postgres superuser | MED | Phase 2B resolves; can be post-go-live if rushed |

---

---

## ── NEXT SESSION HANDOFF PROMPT ──────────────────────────

> Copy and paste the block below at the start of the next session.

---

```
═══ SESSION HANDOFF ══════════════════════════════════════
PROJECT : RRBM Packaging — Production Deployment Prep
DATE    : 2026-06-06
SESSION : 2
STATUS  : PHASE 1 COMPLETE (1C blocked on domain)
══════════════════════════════════════════════════════════

## COMPLETED THIS SESSION
- 1D-a: Removed show-sql=true and format_sql=true from application.properties ✅
- 1D-b: Fixed logging package com.rrbm → rrbm_backend ✅
- 1E: Removed "8080:8080" ports block from docker-compose.yml ✅
- 1B: Removed verify-password from permitAll in SecurityConfig.java ✅
- 1A: Replaced hardcoded "rrbm2024" seed with RRBM_INITIAL_MASTER_KEY env var ✅
      (application.properties, RrbmBackendApplication.java, docker-compose.yml, .env.example)
- CSV Import redesign: Replaced 3-section format with two separate flat uploads ✅
      (ImportController.java, app.js, index.html)

## ACTIVE TASK
Task    : Phase 2 — Server setup
Stopped : Waiting for server to be provisioned
Next    : Once server is ready, start Phase 2 (create .env, nginx config, HTTPS)

## STILL BLOCKED
- 1C: CORS wildcard in SecurityConfig.java:84 — needs production domain
- 2D: HTTPS/TLS — needs production domain
- Phase 2: Server setup — needs server provisioned

## DO NOT TOUCH
- TransactionService ledger paths — M-26 net-basis fix; regression risk high
- Flyway migrations V1–V58 — applied; renaming breaks checksum
- OrderController void/cancel/collect — redesign complete and QA tested
- closeDaily logic — immutability invariant enforced
- ImportController.java — just redesigned; do not revert to section-based format

## TEST STATUS
Suite   : No automated tests; manual QA complete as of Jun 5
Red     : NONE known
Last run: 2026-06-05

## FILES CHANGED THIS SESSION
- rrbm-backend/src/main/java/rrbm_backend/RrbmBackendApplication.java
- rrbm-backend/src/main/java/rrbm_backend/SecurityConfig.java
- rrbm-backend/src/main/java/rrbm_backend/ImportController.java
- rrbm-backend/src/main/resources/application.properties
- docker-compose.yml
- .env.example
- rrbm_frontend/rrbm-frontend/js/app.js
- rrbm_frontend/rrbm-frontend/index.html

## DECISIONS LOCKED — do not re-open or re-derive
- JWT via RRBM_JWT_SECRET env var — already live; do not change pattern
- Master key BCrypt in DB — correct; never store plaintext
- Phase 1 before server work — code must be in image before deploy
- flyway.out-of-order=true — intentional; do not change
- CSV import: flat sales format (one row per item, grouped by Receipt#)
- CSV import: two separate uploads (sales / expenses) — do not merge back

## KNOWN RISKS / WATCH LIST
- "rrbm2024" in git history — rotate on first boot via admin UI; do NOT change DB directly
- No backup exists — Phase 4 is go-live blocker
- CORS still wildcard (1C) — apply before any public exposure
- DB on postgres superuser — medium risk; Phase 2B resolves post-go-live
- verify-superadmin-key endpoint has no JWT guard in its own logic — flagged as
  separate task; it falls behind /api/** authenticated() in SecurityConfig so it IS
  protected, but worth a double-check during Phase 5

══ END HANDOFF ═══════════════════════════════════════════
```
