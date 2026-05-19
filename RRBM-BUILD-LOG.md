# RRBM Project — Complete Build Log
**Last Updated:** May 19, 2026
**Developer:** HTML/CSS background, learning backend from scratch
**Location:** Pasig City, Philippines (GMT+8)

---

## SESSION HISTORY

### Session 1 — May 12, 2026: Frontend Prototype
**Transcript:** `2026-05-12-12-14-01-rrbm-packaging-system-prototype.txt`
- Built initial web app prototype for RRBM Packaging Supplies and Trading
- 10-person PH company specializing in pizza boxes
- Converted Excel-based order/inventory system concept into web UI
- Created all modules: Dashboard, Order Form, Order List, Inventory, Reports, Employees, Settings

### Session 2 — May 13, 2026: Frontend Project Structure
**Transcript:** `2026-05-13-07-20-14-rrbm-frontend-project-build.txt`
- Organized frontend into proper folder structure (CSS/JS/assets/docs)
- Embedded company logo
- Created comprehensive documentation
- Planned backend (Java Spring Boot) as next step

### Session 3 — May 18, 2026 (Morning): Backend Phase 1 & 2 Start
**Transcript:** `2026-05-18-09-25-23-rrbm-backend-auth-phase2.txt`
- Set up development environment (Java 21, PostgreSQL 18.4, Maven, Cursor IDE)
- Phase 1: Created database schema via Flyway migrations (8 tables)
- Phase 2: Started JWT authentication — got blocked on BCrypt password hash mismatch

### Session 4 — May 18, 2026 (Afternoon): Phase 2 Complete + Phase 3
**Transcript:** `2026-05-18-14-02-59-rrbm-backend-phase2-auth-complete.txt`
- Fixed BCrypt hash corruption (root cause: psql corrupts special chars in hashes)
- Solution: Created `/api/test-auth/reset-password` endpoint to set passwords programmatically
- Phase 2 Authentication: COMPLETE ✅
- Login credentials: admin@rrbm.com / test123
- Connected frontend login to real backend

### Session 5 — May 19, 2026 (Current): Phase 3 Orders + Frontend Wiring
- Completed Phase 3: Orders Module (create, list, cancel orders)
- Wired frontend "New Order" form to real backend API
- Fixed toast notification system (Bootstrap CSS conflict)
- All frontend files updated and working

---

## CURRENT PROJECT STATUS

### ✅ Phase 0: Environment Setup — COMPLETE
- Java 21 (Eclipse Adoptium)
- PostgreSQL 18.4
- Git 2.47.1
- IDE: Cursor
- Spring Boot 3.5.14 with Maven

### ✅ Phase 1: Database Schema — COMPLETE
- 8 tables created via Flyway migrations
- Tables: users, products, orders, order_items, inventory_movements, order_id_counter, settings, audit_log
- Seed data: 1 admin user + 6 settings rows

### ✅ Phase 2: Authentication — COMPLETE
- JWT token generation (8-hour expiry, HMAC-SHA256)
- BCrypt password hashing
- Login endpoint: POST /api/auth/login
- Frontend connected to real backend login
- Spring Security temporarily disabled for development

### ✅ Phase 3: Orders Module — COMPLETE
- Order & OrderItem JPA entities
- Order ID Generator with database row-level locking (DDMMYY-NNNNNN format)
- OrderService with business logic
- OrderController with 6 REST endpoints
- Frontend "New Order" form connected to real backend
- Sequential numbering verified working (000001, 000002, etc.)

### ✅ Frontend-Backend Integration — COMPLETE
- Login form authenticates against real database
- JWT token stored in sessionStorage
- New Order form creates real orders in database
- Toast notifications working (fixed Bootstrap conflict)
- Logout clears session

### ⏳ Phase 4: Products & Inventory — NOT STARTED
### ⏳ Phase 5: Dashboard & Reports — NOT STARTED
### ⏳ Phase 6: Settings & Employees — NOT STARTED
### ⏳ Phase 7: Extras (PDF, email, backups) — NOT STARTED

---

## FILE LOCATIONS

### Backend Project
```
D:\ClaudeProjects\rrbm_daily\rrbm-backend\
├── pom.xml
├── mvnw, mvnw.cmd
├── src/main/java/rrbm_backend/
│   ├── RrbmBackendApplication.java    ← Main entry point
│   ├── User.java                      ← JPA Entity (users table)
│   ├── UserRepository.java            ← Spring Data JPA
│   ├── JwtUtil.java                   ← JWT token generation/validation
│   ├── AuthService.java               ← Login business logic
│   ├── AuthController.java            ← POST /api/auth/login
│   ├── TestController.java            ← GET /api/test, /api/health
│   ├── TestAuthController.java        ← Debug endpoints (check-user, generate-hash, reset-password)
│   ├── Order.java                     ← JPA Entity (orders table)
│   ├── OrderItem.java                 ← JPA Entity (order_items table)
│   ├── OrderIdCounter.java            ← JPA Entity (order_id_counter table)
│   ├── OrderIdCounterRepository.java  ← Repository with pessimistic locking
│   ├── OrderIdGenerator.java          ← Generates DDMMYY-NNNNNN IDs
│   ├── OrderRepository.java           ← Spring Data JPA for orders
│   ├── OrderService.java              ← Order business logic
│   ├── OrderController.java           ← REST endpoints for orders
│   └── dto/
│       ├── LoginRequest.java
│       ├── LoginResponse.java
│       ├── CreateOrderRequest.java
│       └── OrderResponse.java
├── src/main/resources/
│   ├── application.properties
│   └── db/migration/
│       ├── V1__initial_schema.sql
│       └── V2__seed_initial_data.sql
```

### Frontend Project
```
D:\ClaudeProjects\rrbm_daily\rrbm_frontend\rrbm-frontend\
├── index.html          ← Main HTML (login + all views)
├── css/
│   └── styles.css      ← All styling (uses .rrbm-toast NOT .toast)
├── js/
│   └── app.js          ← All JavaScript (login, orders, UI logic)
├── assets/
│   └── rrbm-logo.png   ← Company logo (user needs to add this)
```

---

## WORKING API ENDPOINTS

### Authentication
| Method | URL | Description |
|--------|-----|-------------|
| POST | /api/auth/login | Login, returns JWT + user info |
| GET | /api/test | Backend status check |
| GET | /api/health | Database connection check |

### Debug (for development only)
| Method | URL | Description |
|--------|-----|-------------|
| GET | /api/test-auth/check-user | Shows user info + password match status |
| GET | /api/test-auth/generate-hash?password=X | Generates BCrypt hash |
| POST | /api/test-auth/reset-password?email=X&newPassword=Y | Sets password via Java |

### Orders
| Method | URL | Description |
|--------|-----|-------------|
| POST | /api/orders | Create new order |
| GET | /api/orders | Get all orders |
| GET | /api/orders/today | Get today's orders |
| GET | /api/orders/{id} | Get order by ID |
| POST | /api/orders/{id}/cancel | Cancel order (requires masterKey) |
| GET | /api/orders/search?customerName=X | Search orders |

---

## DATABASE

### Connection
- Host: localhost:5432
- Database: rrbm_db
- User: postgres
- Password: (user's postgres password)

### Current Data
- **users table:** 1 user — admin@rrbm.com (id: 3, SUPER_ADMIN, password: test123)
- **orders table:** 2 test orders (180526-000001, 180526-000002)
- **order_items table:** 2 items linked to above orders
- **order_id_counter table:** 1 row (date_key: 180526, last_number: 2)
- **settings table:** 6 rows (company_name, thresholds, master_key_hash, etc.)
- **products table:** Empty (Phase 4)
- **inventory_movements table:** Empty (Phase 4)
- **audit_log table:** Empty (Phase 6)

### Key Schema Notes
- Order ID is VARCHAR primary key (format: DDMMYY-NNNNNN)
- order_items has FK to products (currently nullable since products table is empty)
- product_name is denormalized in order_items (preserves history if product renamed)
- inventory_movements tracks every stock change for audit trail

---

## IMPORTANT CONFIGURATION

### application.properties
```properties
server.port=8080
spring.datasource.url=jdbc:postgresql://localhost:5432/rrbm_db
spring.datasource.username=postgres
spring.datasource.password=[user's password]
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
# Temporarily disabled for development:
spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
```

### JWT Configuration (in JwtUtil.java)
- Secret: "rrbm-secret-key-minimum-256-bits-for-hs256-algorithm-security" (hardcoded for dev)
- Expiration: 8 hours
- Algorithm: HMAC-SHA256

### OrderController Note
- User ID is hardcoded to 3L (admin user's ID)
- TODO: Extract user ID from JWT token properly

---

## BUGS FIXED & LESSONS LEARNED

### 1. Files in Wrong Location (404 errors)
**Problem:** Java files created at project root instead of `src/main/java/rrbm_backend/`
**Fix:** Always create files inside the correct package directory
**Lesson:** Spring Boot only scans files in the package directory

### 2. Maven Cache (stale compilation)
**Problem:** `mvnw.cmd spring-boot:run` says "Nothing to compile" even with new files
**Fix:** Run `mvnw.cmd clean` first to delete compiled classes, then `mvnw.cmd spring-boot:run`

### 3. BCrypt Hash Corruption via psql
**Problem:** Pasting BCrypt hashes into psql corrupts special characters (. and /)
**Fix:** Never paste hashes into SQL. Use the `/api/test-auth/reset-password` endpoint to set passwords programmatically
**Lesson:** Always set passwords through Java code, not raw SQL

### 4. User ID Changed After DELETE/INSERT
**Problem:** Hardcoded `userId = 1L` but actual user ID was 3 after re-creating the user
**Fix:** Changed to `userId = 3L` in OrderController
**TODO:** Extract user ID from JWT token instead of hardcoding

### 5. Foreign Key Constraint on product_id
**Problem:** Creating orders failed because order_items.product_id references products table which is empty
**Fix:** Don't send productId in order creation requests until Products module is built

### 6. BigDecimal Null Pointer
**Problem:** `order.calculateTotals()` crashed with "Cannot read field intCompact because augend is null"
**Fix:** Made calculateTotals() defensive — checks for null before calculations

### 7. Toast Notifications Not Visible (Bootstrap Conflict)
**Problem:** Bootstrap 5 CSS has its own `.toast` class with `display: none` — overriding our custom toast
**Root Cause:** `<link href="bootstrap@5.3.3/dist/css/bootstrap.min.css">` in index.html
**Fix:** Renamed all toast classes from `.toast` to `.rrbm-toast` to avoid conflict
**Also Fixed:** Removed debug code (`el.style.cssText` and `console.log` statements) that was also breaking display
**Lesson:** When using Bootstrap, avoid naming custom classes the same as Bootstrap components (.toast, .modal, .alert, .card, etc.)

### 8. Toast z-index Behind Login Screen
**Problem:** Toast container z-index (2000) was lower than login screen z-index (3000)
**Fix:** Changed toast container z-index to 9999 in styles.css

---

## COMMANDS REFERENCE

### Start Backend
```bash
cd D:\ClaudeProjects\rrbm_daily\rrbm-backend
mvnw.cmd spring-boot:run
```

### Clean Rebuild (when files don't compile)
```bash
mvnw.cmd clean
mvnw.cmd spring-boot:run
```

### Database Access
```bash
psql -U postgres -d rrbm_db
```

### Useful SQL Queries
```sql
-- View all users
SELECT id, email, full_name, role FROM users;

-- View all orders
SELECT id, customer_name, total, status, created_at FROM orders ORDER BY created_at DESC;

-- View order items
SELECT order_id, product_name, quantity, unit_price, subtotal FROM order_items;

-- View order counter
SELECT * FROM order_id_counter;

-- Reset password (use the API endpoint instead!)
-- POST http://localhost:8080/api/test-auth/reset-password?email=admin@rrbm.com&newPassword=test123
```

### Test Endpoints (Browser Console)
```javascript
// Login
fetch('http://localhost:8080/api/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ email: 'admin@rrbm.com', password: 'test123' })
}).then(r => r.json()).then(console.log);

// Create order
fetch('http://localhost:8080/api/orders', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Authorization': 'Bearer ' + sessionStorage.getItem('rrbm_token')
  },
  body: JSON.stringify({
    customerName: 'Test Customer',
    source: 'WALK_IN',
    paymentMode: 'CASH',
    discount: 0,
    items: [{ productName: 'Pizza Box 10" White', quantity: 100, unitPrice: 15.00, warehouse: 'WH1' }]
  })
}).then(r => r.json()).then(console.log);

// Get today's orders
fetch('http://localhost:8080/api/orders/today').then(r => r.json()).then(console.log);
```

---

## USER'S LEARNING STYLE & PREFERENCES

- **Background:** HTML/CSS, new to backend development
- **Pace:** Ambitious but methodical
- **Depth:** Prefers step-by-step with explanations ("walk me through every line")
- **Response to errors:** Sends screenshots, follows instructions carefully, persistent
- **Preferred approach:** Explain WHY not just WHAT
- **Testing style:** Uses browser console for quick API tests
- **IDE:** Cursor (VS Code fork)
- **OS:** Windows
- **Important:** User gets confused by too many options/suggestions at once — give clear, single-path instructions

---

## NEXT STEPS

### Immediate (Phase 4: Products & Inventory)
1. Create Product entity (maps to products table)
2. Create ProductRepository
3. Create ProductService with CRUD operations
4. Create ProductController with REST endpoints
5. Wire frontend Inventory screen to real backend
6. Auto-decrement stock when orders are created
7. Color-coded inventory status (Critical/Low/OK)

### Later Phases
- **Phase 5:** Dashboard with real KPIs from database queries
- **Phase 6:** Settings & Employees CRUD, role-based access
- **Phase 7:** PDF export, email alerts, scheduled jobs, deployment

### Technical Debt to Address
- Extract user ID from JWT token (currently hardcoded to 3L)
- Re-enable Spring Security with proper configuration
- Add foreign key constraint back for order_items.product_id after products exist
- Move JWT secret to environment variable
- Add proper error handling and validation
- Remove test/debug endpoints before production

---

## LOGO NOTE
The frontend expects a logo file at: `assets/rrbm-logo.png`
The user has the logo but needs to copy it to the correct location.
The login screen CSS applies `filter: brightness(0) invert(1)` to make it white on dark background.
The sidebar also uses the same logo file.

---

**END OF BUILD LOG**

Next Claude: Read this log thoroughly. The user will likely want to continue with Phase 4 (Products & Inventory) or fix/improve existing features. All code is in the file locations listed above. The backend must be running (`mvnw.cmd spring-boot:run`) for the frontend to work.
