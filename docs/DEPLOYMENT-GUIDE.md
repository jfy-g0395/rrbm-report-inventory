# RRBM Pre-Deployment Guide
> Based on: Production Readiness Report · Deployment Checklist · Structure Checklist  
> Generated: 2026-06-06  
> Do this in order. Each section must be complete before the next.

---

## 🟡 DEPLOYMENT STATUS — updated Jun 18, 2026 (Session 5, on-site)

**Scope note:** the live deployment is **office-LAN only** (one Windows host, Docker Compose,
nginx on port 80 — no public domain, no internet exposure). The authoritative, current runbook
is **[`DEPLOYMENT-PROGRESS.md`](../DEPLOYMENT-PROGRESS.md)** (repo root). This guide remains the
generic reference; the domain/HTTPS-dependent parts below (1C, 2D, 5.4) are **descoped** for the
LAN deploy — CORS is locked to the exact LAN origin instead.

| Phase | Guide section | LAN-deploy status |
|-------|---------------|-------------------|
| Phase 1 — Security fixes (code) | 1A–1E | ✅ Done (1A/1B/1D/1E committed; **1C** satisfied via exact LAN origin, not a domain) |
| Phase 2 — Server setup | 2A `.env` | ✅ `.env` created on host (strong secrets, `RRBM_CORS_ALLOWED_ORIGINS=http://192.168.0.234`, gitignored) |
| | Docker Desktop + WSL2 | 🟡 Installed v4.78; **reboot pending** to activate WSL2, engine not yet up |
| | 2B/2C/2E hardening | ⏳ Deferred post-go-live (trusted LAN) |
| | 2D HTTPS/TLS | ⛔ Descoped (no domain on LAN) |
| Phase 3 — First boot | build → up → Flyway → security | ⏳ Blocked on Docker engine; **Flyway head is now V74** (not V58 as written below) |
| Phase 4 — Backups | nightly `pg_dump` | ⏳ Via **Windows Task Scheduler** (not cron) — not yet configured |
| Phase 5 — Post-go-live | smoke checks | ⏳ Not started |

**Host facts:** LAN IP `192.168.0.234`, MAC `00-E0-4C-A0-D6-52`, port 80 free.
**Next action:** reboot host → launch Docker Desktop → `docker compose config/build/up` → Flyway
V74 check → V19 test-data purge → first-boot security rotation. See the progress doc for the
exact ordered steps and the V19 purge SQL.

---

## PHASE 1 — Critical Security Fixes (Code Changes)

These are code changes that must be made before any server work begins.

---

### 1A — Remove hardcoded master key seed

**File:** `rrbm-backend/src/main/java/rrbm_backend/RrbmBackendApplication.java`

**Problem:** `"rrbm2024"` is hardcoded in source. Anyone who reads the repo knows the default master key.

**Step 1** — Add to `application.properties`:
```properties
rrbm.initial.master.key=${RRBM_INITIAL_MASTER_KEY}
```

**Step 2** — Update `RrbmBackendApplication.java`:
```java
@Value("${rrbm.initial.master.key}")
private String initialMasterKey;

@Bean
ApplicationRunner seedMasterKey(MasterKeyRepository repo, MasterKeyService svc) {
    return args -> {
        if (repo.count() == 0) {
            svc.rotateMasterKey(initialMasterKey, null);
        }
    };
}
```

**Step 3** — Add to `.env.example`:
```
RRBM_INITIAL_MASTER_KEY=change_me_strong_key_min_8_chars
```

**Step 4** — Add to `docker-compose.yml` under `backend > environment`:
```yaml
RRBM_INITIAL_MASTER_KEY: ${RRBM_INITIAL_MASTER_KEY}
```

---

### 1B — Lock `POST /api/auth/verify-password` behind JWT

**File:** `rrbm-backend/src/main/java/rrbm_backend/AuthController.java`  
**File:** `rrbm-backend/src/main/java/rrbm_backend/SecurityConfig.java`

**Problem:** Currently unauthenticated — anyone can brute-force any account's password.

**Step 1** — Update the method signature in `AuthController.java`:
```java
@PostMapping("/verify-password")
public ResponseEntity<?> verifyPassword(
        @RequestBody Map<String, String> body,
        @RequestHeader(value = "Authorization", required = false) String authHeader) {

    Long userId = extractUserId(authHeader);
    if (userId == null)
        return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized"));

    // Load by userId from JWT — not by email from body
    User user = userRepository.findById(userId).orElse(null);
    if (user == null)
        return ResponseEntity.status(401).body(new ErrorResponse("User not found"));

    String password = body.get("password");
    if (password == null || password.isBlank())
        return ResponseEntity.badRequest().body(new ErrorResponse("Password is required"));

    boolean valid = passwordEncoder.matches(password, user.getPasswordHash());
    if (valid) return ResponseEntity.ok(Map.of("valid", true));
    return ResponseEntity.status(401).body(new ErrorResponse("Invalid password"));
}
```

**Step 2** — Remove `verify-password` from `permitAll()` in `SecurityConfig.java`:
```java
// DELETE this line:
.requestMatchers("/api/auth/verify-password").permitAll()
```

---

### 1C — Fix CORS — remove wildcard

**File:** `rrbm-backend/src/main/java/rrbm_backend/SecurityConfig.java`

**Problem:** `allowedOriginPatterns("*")` allows any website to make authenticated requests.

```java
// BEFORE
config.setAllowedOriginPatterns(List.of("*"));

// AFTER — replace with your actual production domain
config.setAllowedOriginPatterns(List.of("https://yourdomain.com"));
```

> You can set this right before deploy once the domain is confirmed. Placeholder is fine during dev.

---

### 1D — Fix SQL logging and logging package

**File:** `rrbm-backend/src/main/resources/application.properties`

**Step 1** — Remove or comment these lines:
```properties
# DELETE or comment both:
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
```

**Step 2** — Fix the logging package (currently points at the wrong package — all app logs are silent):
```properties
# BEFORE (wrong — no match, logs are discarded)
logging.level.com.rrbm=INFO

# AFTER
logging.level.rrbm_backend=INFO
```

---

### 1E — Remove backend port exposure

**File:** `docker-compose.yml`

**Problem:** Port 8080 is mapped to the host, bypassing nginx entirely.

```yaml
# REMOVE these 2 lines from the backend service:
ports:
  - "8080:8080"
```

Nginx already proxies `/api/` to `http://backend:8080` internally. No host mapping needed.

---

## PHASE 2 — Server Setup

Do this on the production server before running docker-compose.

---

### 2A — Create the `.env` file on the server

Create `/path/to/project/.env` (never commit this file):

```env
# PostgreSQL password
DB_PASSWORD=<strong_random_password>

# JWT secret — minimum 32 characters
# Generate with: openssl rand -hex 32
RRBM_JWT_SECRET=<64_char_random_hex>

# Initial master key — seeded on first boot only
RRBM_INITIAL_MASTER_KEY=<your_chosen_master_key>
```

---

### 2B — Create a least-privilege database user

Run this once against the PostgreSQL container after first boot:

```sql
CREATE USER rrbm_app WITH PASSWORD '<strong_password>';
GRANT CONNECT ON DATABASE rrbm_db TO rrbm_app;
GRANT USAGE ON SCHEMA public TO rrbm_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO rrbm_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO rrbm_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rrbm_app;
```

Then update `docker-compose.yml`:
```yaml
SPRING_DATASOURCE_USERNAME: rrbm_app
SPRING_DATASOURCE_PASSWORD: ${DB_APP_PASSWORD}
```

Add `DB_APP_PASSWORD` to `.env`.

---

### 2C — Add login rate limiting to nginx

**File:** `rrbm_frontend/nginx.conf`

```nginx
# Add near the top, outside the server block:
limit_req_zone $binary_remote_addr zone=login:10m rate=5r/m;

# Add inside the server block, before the general location /:
location /api/auth/login {
    limit_req zone=login burst=3 nodelay;
    proxy_pass         http://backend:8080;
    proxy_set_header   Host $host;
    proxy_set_header   X-Real-IP $remote_addr;
    proxy_set_header   X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

---

### 2D — Add HTTPS (TLS)

Option A — Certbot with Let's Encrypt (recommended if you have a domain):
```bash
# On the host, install certbot then:
certbot certonly --standalone -d yourdomain.com
```

Update `nginx.conf`:
```nginx
server {
    listen 443 ssl;
    server_name yourdomain.com;
    ssl_certificate     /etc/letsencrypt/live/yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/yourdomain.com/privkey.pem;

    # ... rest of existing config ...
}

server {
    listen 80;
    server_name yourdomain.com;
    return 301 https://$host$request_uri;
}
```

Option B — Cloudflare proxy (simplest if you use Cloudflare DNS): enable "Full (strict)" mode — TLS terminates at Cloudflare, no nginx cert config needed.

---

### 2E — Add Content-Security-Policy header

**File:** `rrbm_frontend/nginx.conf`

Add inside the `server` block alongside the existing security headers:
```nginx
add_header Content-Security-Policy "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self';" always;
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
```

> `'unsafe-inline'` is needed because the frontend uses inline scripts/styles. Tighten after go-live if possible.

---

## PHASE 3 — First-Boot Sequence

Do these steps in exact order on first deploy.

```
1. git pull on the server (or scp files)
2. Confirm .env file exists with all 3 secrets
3. docker-compose build
4. docker-compose up -d
5. Watch logs: docker-compose logs -f backend
   - Confirm "Started RrbmBackendApplication" appears
   - Confirm Flyway shows "Successfully applied X migrations" (head should be **V74**)
   - Confirm NO "rrbm2024" appears in logs (seed used RRBM_INITIAL_MASTER_KEY instead)
6. Open the app in browser — log in with SUPER_ADMIN credentials
7. Navigate to Settings → immediately change the master key from the value in .env
8. Set adminSecurityKey for all SUPER_ADMIN and ADMINISTRATOR accounts
9. Disable any inactive employee accounts
10. Run the DB user migration (Phase 2B) to switch off postgres superuser
```

---

## PHASE 4 — Backup Strategy

No backup exists yet. This must be set up before the system goes live.

### Minimum viable backup

Add a cron job on the host:

```bash
# /etc/cron.d/rrbm-backup
0 2 * * * root docker exec rrbm_db pg_dump -U postgres rrbm_db | gzip > /var/backups/rrbm/rrbm_$(date +\%Y\%m\%d).sql.gz
```

Create the backup directory:
```bash
mkdir -p /var/backups/rrbm
```

Keep 7 days of rolling backups:
```bash
# Add second cron line to purge old backups
0 3 * * * root find /var/backups/rrbm -name "*.sql.gz" -mtime +7 -delete
```

### Test restore before go-live
```bash
# 1. Take a dump
docker exec rrbm_db pg_dump -U postgres rrbm_db > test_dump.sql

# 2. Drop and recreate DB (TEST ENVIRONMENT ONLY)
docker exec -it rrbm_db psql -U postgres -c "DROP DATABASE rrbm_db;"
docker exec -it rrbm_db psql -U postgres -c "CREATE DATABASE rrbm_db;"

# 3. Restore
cat test_dump.sql | docker exec -i rrbm_db psql -U postgres rrbm_db

# 4. Verify — check order count matches
docker exec -it rrbm_db psql -U postgres rrbm_db -c "SELECT COUNT(*) FROM orders;"
```

---

## PHASE 5 — Post-Go-Live Checks

Run these within 30 minutes of going live:

- [ ] Login works with correct credentials
- [ ] Login fails with wrong credentials (returns 401, not 500)
- [ ] Rate limiting active — 6+ rapid login attempts should get `503`
- [ ] `http://yourdomain.com` redirects to `https://` (if HTTPS configured)
- [ ] `http://yourserverip:8080` is NOT accessible from outside (port closed)
- [ ] Create a test order end-to-end and confirm it appears in today's report
- [ ] Close daily report and confirm it locks
- [ ] `docker-compose logs backend` shows no errors

---

## DO NOT TOUCH (Stable — Do Not Modify)

| File / Feature | Reason |
|---|---|
| `TransactionService` ledger paths | M-26 net-basis fix; any change risks phantom debits |
| All Flyway migrations V1–V58 | Applied to production; renaming breaks Flyway checksum |
| `OrderController` void/cancel/collect | Full redesign complete and tested in QA |
| `closeDaily` report logic | Immutability invariant — post-close writes go to effective_date |
