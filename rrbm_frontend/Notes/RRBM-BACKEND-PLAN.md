# RRBM Backend — Architecture & Implementation Plan

Complete planning document for the Java Spring Boot backend that will power the RRBM Packaging management system.

**Stack chosen:**
- **Java 21** (current LTS) + **Spring Boot 3.3.x**
- **PostgreSQL 16** (better concurrency than MySQL — important when multiple staff create orders simultaneously)
- **Spring Security + JWT** for authentication
- **Spring Data JPA** for database access
- **Flyway** for database migrations
- **iText 7** for PDF report generation
- **JavaMail** for low-stock email alerts (SMS optional, via Semaphore PH API)

---

## 1. System Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Browser (any PC in the office, or remote)                   │
│  ┌───────────────────────────────────────────────────────┐   │
│  │  index.html  +  css/styles.css  +  js/app.js          │   │
│  │  (the frontend we already built)                      │   │
│  └───────────────────────────────────────────────────────┘   │
└─────────────────────────┬────────────────────────────────────┘
                          │ HTTPS  (HTTP for LAN-only)
                          │ JSON   + JWT in Authorization header
                          ▼
┌──────────────────────────────────────────────────────────────┐
│  Spring Boot Application (rrbm-backend.jar)                  │
│                                                              │
│  ┌─ Controllers ─┐  ┌─ Services ─┐  ┌─ Scheduled Jobs ─┐    │
│  │  Auth         │  │  Order     │  │  DailyResetJob   │    │
│  │  Order        │  │  Inventory │  │  BackupJob       │    │
│  │  Inventory    │  │  Report    │  │  StockAlertJob   │    │
│  │  Report       │  │  Pdf       │  └──────────────────┘    │
│  │  Employee     │  │  Email     │                          │
│  │  Settings     │  │  Auth      │                          │
│  └───────────────┘  └────────────┘                          │
│                          │                                   │
│  ┌─ Repositories (Spring Data JPA) ────────────────────┐    │
│  │  UserRepo, OrderRepo, ProductRepo, etc.            │    │
│  └────────────────────────────────────────────────────┘    │
└─────────────────────────┬────────────────────────────────────┘
                          │ JDBC
                          ▼
┌──────────────────────────────────────────────────────────────┐
│  PostgreSQL 16                                               │
│  Database: rrbm_db                                           │
│  Tables: users, products, orders, order_items,               │
│          inventory_movements, settings, audit_log,           │
│          order_id_counter                                    │
└──────────────────────────────────────────────────────────────┘
```

### Why PostgreSQL over MySQL?
- True row-level locking with `SELECT ... FOR UPDATE` — critical when generating sequential Order IDs
- Better handling of concurrent transactions (when 2 staff click "Add Order" at the same instant)
- Free and open-source, runs on Windows / Mac / Linux
- Built-in JSON support for the audit log
- Easy backup with `pg_dump`

---

## 2. Project Structure

```
rrbm-backend/
├── pom.xml                              ← Maven dependencies
├── README.md
├── docker-compose.yml                   ← Optional: easy DB + app deployment
├── src/
│   ├── main/
│   │   ├── java/com/rrbm/
│   │   │   ├── RrbmApplication.java     ← Main entry point
│   │   │   │
│   │   │   ├── config/
│   │   │   │   ├── SecurityConfig.java
│   │   │   │   ├── CorsConfig.java
│   │   │   │   ├── JwtConfig.java
│   │   │   │   └── OpenApiConfig.java   ← Swagger UI
│   │   │   │
│   │   │   ├── controller/
│   │   │   │   ├── AuthController.java
│   │   │   │   ├── DashboardController.java
│   │   │   │   ├── OrderController.java
│   │   │   │   ├── InventoryController.java
│   │   │   │   ├── ReportController.java
│   │   │   │   ├── EmployeeController.java
│   │   │   │   └── SettingsController.java
│   │   │   │
│   │   │   ├── service/
│   │   │   │   ├── AuthService.java
│   │   │   │   ├── OrderService.java
│   │   │   │   ├── OrderIdGenerator.java
│   │   │   │   ├── InventoryService.java
│   │   │   │   ├── ReportService.java
│   │   │   │   ├── PdfReportService.java
│   │   │   │   ├── EmailService.java
│   │   │   │   └── BackupService.java
│   │   │   │
│   │   │   ├── scheduler/
│   │   │   │   ├── DailyResetJob.java
│   │   │   │   ├── BackupJob.java
│   │   │   │   └── StockAlertJob.java
│   │   │   │
│   │   │   ├── repository/
│   │   │   │   ├── UserRepository.java
│   │   │   │   ├── OrderRepository.java
│   │   │   │   ├── OrderItemRepository.java
│   │   │   │   ├── ProductRepository.java
│   │   │   │   ├── SettingsRepository.java
│   │   │   │   ├── OrderIdCounterRepository.java
│   │   │   │   └── AuditLogRepository.java
│   │   │   │
│   │   │   ├── entity/
│   │   │   │   ├── User.java
│   │   │   │   ├── Order.java
│   │   │   │   ├── OrderItem.java
│   │   │   │   ├── Product.java
│   │   │   │   ├── InventoryMovement.java
│   │   │   │   ├── Settings.java
│   │   │   │   ├── OrderIdCounter.java
│   │   │   │   └── AuditLog.java
│   │   │   │
│   │   │   ├── dto/
│   │   │   │   ├── request/
│   │   │   │   │   ├── LoginRequest.java
│   │   │   │   │   ├── CreateOrderRequest.java
│   │   │   │   │   ├── CancelOrderRequest.java
│   │   │   │   │   └── UpdateInventoryRequest.java
│   │   │   │   └── response/
│   │   │   │       ├── LoginResponse.java
│   │   │   │       ├── OrderResponse.java
│   │   │   │       ├── DashboardSummaryResponse.java
│   │   │   │       └── ApiError.java
│   │   │   │
│   │   │   ├── security/
│   │   │   │   ├── JwtFilter.java
│   │   │   │   ├── JwtTokenProvider.java
│   │   │   │   └── CustomUserDetailsService.java
│   │   │   │
│   │   │   └── exception/
│   │   │       ├── GlobalExceptionHandler.java
│   │   │       ├── ResourceNotFoundException.java
│   │   │       └── InvalidMasterKeyException.java
│   │   │
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       ├── application-prod.yml
│   │       ├── db/migration/
│   │       │   ├── V1__initial_schema.sql
│   │       │   ├── V2__seed_data.sql
│   │       │   └── V3__seed_settings.sql
│   │       └── static/                  ← Place the rrbm-frontend files here
│   │           ├── index.html
│   │           ├── css/
│   │           ├── js/
│   │           └── assets/
│   │
│   └── test/java/com/rrbm/
│       ├── controller/                  ← Controller tests
│       ├── service/                     ← Service tests
│       └── repository/                  ← Repository tests
```

---

## 3. Database Schema

PostgreSQL DDL. This goes in `src/main/resources/db/migration/V1__initial_schema.sql` so Flyway runs it on first startup.

```sql
-- ========================================================================
-- USERS / EMPLOYEES (with login credentials)
-- ========================================================================
CREATE TYPE user_role AS ENUM ('SUPER_ADMIN', 'ADMIN', 'STAFF');
CREATE TYPE user_status AS ENUM ('ACTIVE', 'AWAY', 'DISABLED');

CREATE TABLE users (
  id              BIGSERIAL PRIMARY KEY,
  email           VARCHAR(120) UNIQUE NOT NULL,
  password_hash   VARCHAR(255) NOT NULL,           -- BCrypt-hashed
  full_name       VARCHAR(120) NOT NULL,
  role            user_role NOT NULL DEFAULT 'STAFF',
  permissions     JSONB DEFAULT '[]'::jsonb,       -- e.g., ["orders", "inventory"]
  status          user_status DEFAULT 'ACTIVE',
  last_login_at   TIMESTAMPTZ,
  created_at      TIMESTAMPTZ DEFAULT NOW(),
  updated_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);

-- ========================================================================
-- PRODUCTS (catalog + warehouse stock)
-- ========================================================================
CREATE TYPE selling_tag AS ENUM ('HOT', 'SELLING', 'SLOW');

CREATE TABLE products (
  id                   BIGSERIAL PRIMARY KEY,
  sku                  VARCHAR(50) UNIQUE,
  name                 VARCHAR(200) NOT NULL,
  category             VARCHAR(80),
  selling_tag          selling_tag DEFAULT 'SELLING',
  unit_price           NUMERIC(10, 2) NOT NULL DEFAULT 0,
  unit_cost            NUMERIC(10, 2) DEFAULT 0,     -- for profit reports
  threshold_critical   INT DEFAULT 1000,
  threshold_low        INT DEFAULT 0,                -- 0 = no low warning, only critical
  stock_wh1            INT NOT NULL DEFAULT 0,
  stock_wh2            INT NOT NULL DEFAULT 0,
  stock_wh3            INT NOT NULL DEFAULT 0,
  active               BOOLEAN DEFAULT TRUE,
  created_at           TIMESTAMPTZ DEFAULT NOW(),
  updated_at           TIMESTAMPTZ DEFAULT NOW(),
  CHECK (stock_wh1 >= 0 AND stock_wh2 >= 0 AND stock_wh3 >= 0)
);

CREATE INDEX idx_products_category ON products(category);
CREATE INDEX idx_products_active ON products(active);

-- ========================================================================
-- ORDERS (header)
-- ========================================================================
CREATE TYPE order_source AS ENUM ('WALK_IN', 'AGENT', 'ECOMMERCE', 'FACEBOOK_PAGE');
CREATE TYPE order_payment AS ENUM ('CASH', 'GCASH', 'PAYMAYA', 'BANK_TRANSFER', 'BANK_DEPOSIT', 'ONLINE');
CREATE TYPE order_status AS ENUM ('ACTIVE', 'PENDING', 'CANCELLED', 'CLOSED');
CREATE TYPE ecom_platform AS ENUM ('SHOPEE', 'TIKTOK', 'LAZADA');

CREATE TABLE orders (
  id                    VARCHAR(13) PRIMARY KEY,    -- DDMMYY-NNNNNN
  customer_name         VARCHAR(200) NOT NULL DEFAULT 'Walk-in',
  source                order_source NOT NULL DEFAULT 'WALK_IN',
  agent_name            VARCHAR(120),
  fb_page               VARCHAR(120),
  ecommerce_platform    ecom_platform,
  payment_mode          order_payment NOT NULL DEFAULT 'CASH',
  subtotal              NUMERIC(12, 2) NOT NULL DEFAULT 0,
  discount              NUMERIC(12, 2) DEFAULT 0,
  total                 NUMERIC(12, 2) NOT NULL DEFAULT 0,
  status                order_status DEFAULT 'ACTIVE',
  notes                 TEXT,
  created_by            BIGINT REFERENCES users(id),
  created_at            TIMESTAMPTZ DEFAULT NOW(),
  cancelled_at          TIMESTAMPTZ,
  cancelled_by          BIGINT REFERENCES users(id),
  cancellation_reason   VARCHAR(255)
);

CREATE INDEX idx_orders_created_at ON orders(created_at DESC);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_source ON orders(source);
CREATE INDEX idx_orders_customer ON orders(customer_name);

-- ========================================================================
-- ORDER ITEMS (line items)
-- ========================================================================
CREATE TABLE order_items (
  id             BIGSERIAL PRIMARY KEY,
  order_id       VARCHAR(13) NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  product_id     BIGINT REFERENCES products(id),
  product_name   VARCHAR(200) NOT NULL,    -- denormalized (preserves history if product renamed)
  quantity       INT NOT NULL CHECK (quantity > 0),
  unit_price     NUMERIC(10, 2) NOT NULL,
  subtotal       NUMERIC(12, 2) NOT NULL,
  warehouse      VARCHAR(10) DEFAULT 'wh1' -- which warehouse fulfilled this
);

CREATE INDEX idx_order_items_order ON order_items(order_id);

-- ========================================================================
-- INVENTORY MOVEMENTS (audit trail for stock changes)
-- ========================================================================
CREATE TYPE movement_type AS ENUM ('ORDER_OUT', 'CANCELLED_RETURN', 'MANUAL_ADJUST', 'RESTOCK', 'TRANSFER');

CREATE TABLE inventory_movements (
  id             BIGSERIAL PRIMARY KEY,
  product_id     BIGINT NOT NULL REFERENCES products(id),
  movement_type  movement_type NOT NULL,
  warehouse      VARCHAR(10) NOT NULL,     -- wh1, wh2, wh3
  quantity       INT NOT NULL,             -- negative = out, positive = in
  reference_id   VARCHAR(50),              -- e.g., order ID
  reason         VARCHAR(255),
  user_id        BIGINT REFERENCES users(id),
  created_at     TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_movements_product ON inventory_movements(product_id);
CREATE INDEX idx_movements_date ON inventory_movements(created_at DESC);

-- ========================================================================
-- ORDER ID COUNTER (per-day sequence)
-- ========================================================================
CREATE TABLE order_id_counter (
  date_key       VARCHAR(6) PRIMARY KEY,   -- DDMMYY
  last_number    INT NOT NULL DEFAULT 0
);

-- ========================================================================
-- SETTINGS (key-value config)
-- ========================================================================
CREATE TABLE settings (
  key_name       VARCHAR(80) PRIMARY KEY,
  value          VARCHAR(500),
  description    VARCHAR(255),
  updated_at     TIMESTAMPTZ DEFAULT NOW(),
  updated_by     BIGINT REFERENCES users(id)
);

-- Seed default settings
INSERT INTO settings (key_name, value, description) VALUES
  ('company_name',              'RRBM Packaging Supplies and Trading', 'Display name'),
  ('daily_reset_time',          '00:00',                                'Daily reset (HH:mm)'),
  ('master_key_hash',           '$2a$10$placeholder',                   'BCrypt hash of master key'),
  ('threshold_hot_critical',    '3000',                                 'Critical stock for HOT items (pcs)'),
  ('threshold_hot_low',         '5000',                                 'Low warning for HOT items (pcs)'),
  ('threshold_sel_critical',    '1000',                                 'Critical stock for SELLING/SLOW items (pcs)'),
  ('alert_email_to',            'boss@rrbm.com',                        'Where to send low-stock alerts'),
  ('alert_email_enabled',       'true',                                 'Send daily stock alert emails'),
  ('backup_enabled',            'true',                                 'Run nightly backup'),
  ('backup_retention_days',     '30',                                   'Keep backups for N days');

-- ========================================================================
-- AUDIT LOG (every important action)
-- ========================================================================
CREATE TABLE audit_log (
  id             BIGSERIAL PRIMARY KEY,
  user_id        BIGINT REFERENCES users(id),
  action         VARCHAR(80) NOT NULL,     -- LOGIN, CREATE_ORDER, CANCEL_ORDER, etc.
  entity_type    VARCHAR(40),              -- ORDER, PRODUCT, USER, SETTING
  entity_id      VARCHAR(40),
  details        JSONB,
  ip_address     VARCHAR(45),
  created_at     TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_audit_user ON audit_log(user_id);
CREATE INDEX idx_audit_action ON audit_log(action);
CREATE INDEX idx_audit_date ON audit_log(created_at DESC);

-- ========================================================================
-- SEED INITIAL SUPER ADMIN
-- ========================================================================
-- Password: ChangeMe123! (BCrypt hash)
INSERT INTO users (email, password_hash, full_name, role) VALUES
  ('admin@rrbm.com',
   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
   'Ryan Reyes',
   'SUPER_ADMIN');
```

### Why these design choices?

- **Order ID as primary key (VARCHAR)** — the human-readable ID *is* the natural key, no need for a separate surrogate
- **Denormalized `product_name` in order_items** — preserves history if a product gets renamed later
- **Stock columns on `products` table (not separate)** — simpler, faster for the common "show current stock" query; movements table tracks the audit trail
- **`inventory_movements` table** — every stock change is logged so you can answer "where did those 200 boxes go?"
- **ENUM types** — type-safe at the DB level, prevents bad data
- **JSONB for permissions and audit details** — flexible without needing extra tables
- **All timestamps as `TIMESTAMPTZ`** — timezone-aware, important for Philippine deployments

---

## 4. REST API Endpoints

All endpoints prefixed with `/api`. Authenticated endpoints require `Authorization: Bearer <jwt>` header.

### Authentication
| Method | Path                       | Auth | Description                          |
|--------|----------------------------|------|--------------------------------------|
| POST   | `/api/auth/login`          | ❌   | Email + password → JWT               |
| POST   | `/api/auth/logout`         | ✅   | Invalidate session (server-side)     |
| GET    | `/api/auth/me`             | ✅   | Current user info                    |
| POST   | `/api/auth/change-password`| ✅   | Change own password                  |

### Dashboard
| Method | Path                                  | Auth | Description                  |
|--------|---------------------------------------|------|------------------------------|
| GET    | `/api/dashboard/summary`              | ✅   | All KPI card values          |
| GET    | `/api/dashboard/sales-trend?days=7`   | ✅   | Sales line chart data        |
| GET    | `/api/dashboard/ecommerce-split`      | ✅   | Shopee/TikTok/Lazada doughnut|
| GET    | `/api/dashboard/top-products?limit=5` | ✅   | Best sellers today           |

### Orders
| Method | Path                              | Auth      | Description                       |
|--------|-----------------------------------|-----------|-----------------------------------|
| GET    | `/api/orders`                     | ✅        | List all (paginated, filterable)  |
| GET    | `/api/orders/today`               | ✅        | Today only                        |
| GET    | `/api/orders/{id}`                | ✅        | Single order detail               |
| POST   | `/api/orders`                     | ✅ Staff+ | Create new order                  |
| POST   | `/api/orders/{id}/cancel`         | ✅ Staff+ | Cancel (master key required)      |
| POST   | `/api/orders/close-sales`         | ✅ Admin+ | End-of-day closeout               |

### Inventory
| Method | Path                              | Auth      | Description                     |
|--------|-----------------------------------|-----------|---------------------------------|
| GET    | `/api/inventory`                  | ✅        | All products with stock         |
| GET    | `/api/inventory/{id}`             | ✅        | Single product detail           |
| GET    | `/api/inventory/low-stock`        | ✅        | Critical + low items only       |
| POST   | `/api/inventory`                  | ✅ Admin+ | Add new product                 |
| PUT    | `/api/inventory/{id}`             | ✅ Admin+ | Update product (price, name)    |
| POST   | `/api/inventory/{id}/adjust`      | ✅ Admin+ | Adjust stock with reason        |
| POST   | `/api/inventory/{id}/transfer`    | ✅ Admin+ | Move stock between warehouses   |

### Reports
| Method | Path                                       | Auth      | Description                  |
|--------|--------------------------------------------|-----------|------------------------------|
| GET    | `/api/reports/monthly?year=2025`           | ✅ Admin+ | Monthly revenue              |
| GET    | `/api/reports/sales-comparison?months=6`   | ✅ Admin+ | Direct vs E-commerce         |
| GET    | `/api/reports/best-day?month=11&year=2025` | ✅ Admin+ | Best day this month          |
| GET    | `/api/reports/export/pdf?type=monthly&...` | ✅ Admin+ | Download PDF report          |

### Employees
| Method | Path                              | Auth            | Description           |
|--------|-----------------------------------|-----------------|-----------------------|
| GET    | `/api/employees`                  | ✅ Admin+       | All employees         |
| GET    | `/api/employees/{id}`             | ✅ Admin+       | Single employee       |
| POST   | `/api/employees`                  | ✅ Super Admin  | Create employee       |
| PUT    | `/api/employees/{id}`             | ✅ Super Admin  | Update employee       |
| DELETE | `/api/employees/{id}`             | ✅ Super Admin  | Disable employee      |

### Settings
| Method | Path                              | Auth            | Description             |
|--------|-----------------------------------|-----------------|-------------------------|
| GET    | `/api/settings`                   | ✅ Admin+       | All settings            |
| PUT    | `/api/settings`                   | ✅ Super Admin  | Update settings (batch) |

---

## 5. Authentication Flow

### Login

```
1. Frontend POST /api/auth/login
   { email, password }

2. Backend:
   a. Find user by email
   b. BCrypt.verify(password, user.password_hash)
   c. If valid:
      - Generate JWT (signed with HS256, 8-hour expiry)
      - Update last_login_at
      - Insert audit_log row
   d. Return:
      {
        token: "eyJhbGc...",
        user: {
          id, email, fullName, role, permissions
        }
      }

3. Frontend:
   - Store token in sessionStorage
   - Store user info for role-based UI hiding
   - Hide login screen, show app
```

### Authenticated request

```
1. Frontend adds header:
   Authorization: Bearer eyJhbGc...

2. JwtFilter (Spring Security):
   - Extract token from header
   - Verify signature + expiry
   - Load user from DB
   - Set SecurityContext

3. Controller method runs with authenticated user
   - Use @PreAuthorize("hasRole('ADMIN')") for role checks
```

### Token refresh

For simplicity in v1, use 8-hour tokens without refresh. If the user is still active near expiry, return a fresh token in any response header. Users who stay idle get logged out — fine for an office app.

---

## 6. Order ID Generation (the tricky part)

Multiple staff might click "Add Order" at the exact same millisecond. We need every order to get a unique sequential ID. Solution: row-level lock on the counter table.

```java
@Service
@Transactional
public class OrderIdGenerator {

  private final OrderIdCounterRepository counterRepo;

  public String generate() {
    String dateKey = LocalDate.now(ZoneId.of("Asia/Manila"))
        .format(DateTimeFormatter.ofPattern("ddMMyy"));

    // SELECT ... FOR UPDATE — locks the row until transaction commits
    OrderIdCounter counter = counterRepo.findByIdForUpdate(dateKey)
        .orElseGet(() -> counterRepo.save(new OrderIdCounter(dateKey, 0)));

    counter.setLastNumber(counter.getLastNumber() + 1);
    // JPA auto-saves on transaction commit

    return String.format("%s-%06d", dateKey, counter.getLastNumber());
  }
}
```

The `FOR UPDATE` lock guarantees that even if two requests hit at the same instant, one will wait for the other to commit. No duplicate IDs possible.

---

## 7. Scheduled Jobs

Three background jobs run on a schedule.

### Daily Reset Job — runs at 12:00 AM Manila time

```java
@Component
public class DailyResetJob {
  @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Manila")
  public void run() {
    // 1. Close any ACTIVE orders from previous day → status CLOSED
    // 2. Insert daily totals into reports cache (optional optimization)
    // 3. Order ID counter automatically uses new date on next order
    log.info("Daily reset completed");
  }
}
```

### Backup Job — runs at 2:00 AM Manila time

```java
@Component
public class BackupJob {
  @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Manila")
  public void run() {
    // 1. Execute pg_dump to /backups/rrbm-YYYY-MM-DD.sql.gz
    // 2. Delete backups older than backup_retention_days
    // 3. Optionally upload to cloud storage (S3, Google Drive)
    // 4. Email confirmation to admin
  }
}
```

The actual command: `pg_dump -U rrbm_user -d rrbm_db | gzip > /backups/rrbm-$(date +%F).sql.gz`

### Stock Alert Job — runs daily at 8:00 AM Manila time

```java
@Component
public class StockAlertJob {
  @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Manila")
  public void run() {
    List<Product> criticalItems = productRepo.findCriticalStock();
    if (!criticalItems.isEmpty()) {
      emailService.sendStockAlert(
        settingsRepo.get("alert_email_to"),
        criticalItems
      );
    }
  }
}
```

Email content (HTML, branded with RRBM colors):
> **Low Stock Alert — RRBM Packaging**
>
> The following items are running low:
> - Pizza Box 10" White — **2,400 pcs** (Critical, threshold 3,000)
> - Take-out Bag XL — **0 pcs** (Out of stock)
>
> [Open Inventory]

---

## 8. PDF Report Generation

Using iText 7 to generate branded PDF reports.

```java
@Service
public class PdfReportService {

  public byte[] generateMonthlyReport(int year, int month) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PdfWriter writer = new PdfWriter(baos);
    PdfDocument pdf = new PdfDocument(writer);
    Document document = new Document(pdf);

    // Header with RRBM logo
    document.add(new Image(ImageDataFactory.create("classpath:logo.png"))
        .setWidth(120));
    document.add(new Paragraph("Monthly Sales Report")
        .setFontSize(18).setBold().setFontColor(BRAND_BROWN));
    document.add(new Paragraph(monthName + " " + year));

    // Summary numbers
    Table summary = new Table(2);
    summary.addCell("Total Sales");
    summary.addCell("₱" + totalSales);
    summary.addCell("Total Orders");
    summary.addCell(String.valueOf(totalOrders));
    // ...
    document.add(summary);

    // Order breakdown by source
    // ...

    // Top products table
    // ...

    document.close();
    return baos.toByteArray();
  }
}
```

The controller returns it as a downloadable file:

```java
@GetMapping("/reports/export/pdf")
public ResponseEntity<byte[]> exportPdf(@RequestParam int year, @RequestParam int month) {
  byte[] pdf = pdfService.generateMonthlyReport(year, month);
  return ResponseEntity.ok()
      .contentType(MediaType.APPLICATION_PDF)
      .header("Content-Disposition", "attachment; filename=\"rrbm-report-" + year + "-" + month + ".pdf\"")
      .body(pdf);
}
```

---

## 9. Deployment Options

Since you said "not sure yet" — here are all three options ranked by simplicity. **Start with Option A**, scale up later.

### Option A: Office LAN (recommended starting point)

**One Windows PC** in the office runs everything. Staff access from their browsers.

**Setup:**
1. Install Java 21 on the host PC
2. Install PostgreSQL 16 on the host PC
3. Run `rrbm-backend.jar` (the built application)
4. From any other PC on the same WiFi, open `http://192.168.1.50:8080` (replace with host PC's IP)

**Pros:** No internet required for daily use, full control, free
**Cons:** Office PC must be on all day, only accessible in office, manual updates

### Option B: Single PC (smallest setup)

Same as A but only one person uses it. Skip the network step.

**Setup:**
1. Install Java 21 + PostgreSQL
2. Run the jar
3. Open `http://localhost:8080` in the browser

**Pros:** Simplest possible, no networking
**Cons:** Single user only

### Option C: Cloud Hosting (most flexible)

Deploy to a cloud VPS so it's accessible anywhere.

**Recommended providers (PH-friendly):**
- **DigitalOcean** (Singapore datacenter, fast for PH) — ~$6/month for a small droplet
- **Hetzner** — ~$5/month
- **AWS Lightsail** — ~$5/month

**Setup:**
1. Create a Ubuntu 22.04 droplet
2. Install Java 21, PostgreSQL, Nginx
3. Upload the jar, configure systemd service
4. Configure Nginx as reverse proxy
5. Get free SSL via Let's Encrypt
6. Point your domain to the droplet

**Pros:** Accessible anywhere, automatic backups via provider, scales easily
**Cons:** Monthly cost, requires basic Linux knowledge, internet outage = no app

### Architecture is identical for all three

The same jar file runs everywhere. Only the `application.yml` config differs (DB host, allowed origins, etc.).

---

## 10. Implementation Phases

Realistic timeline assuming one developer working part-time:

### Phase 1 — Foundation (Week 1-2)
- Project setup, Maven dependencies
- Database schema + Flyway migrations
- User entity + auth service + JWT
- Login endpoint + frontend integration
- **Milestone:** Can log in, JWT works

### Phase 2 — Core Orders (Week 3-4)
- Order entity + repository + service
- Order ID generator with lock
- Create order endpoint
- Today's orders endpoint
- Cancel order with master key
- Frontend wire-up for these
- **Milestone:** Can create and cancel orders end-to-end

### Phase 3 — Inventory (Week 5)
- Product entity + service
- Inventory CRUD
- Stock adjustment + inventory_movements log
- Auto-decrement on order creation
- Frontend wire-up
- **Milestone:** Stock changes reflect in real-time

### Phase 4 — Dashboard & Reports (Week 6-7)
- Dashboard summary aggregations
- Sales trend queries
- Monthly report queries
- Frontend wire-up
- **Milestone:** Dashboard shows real numbers

### Phase 5 — Polish (Week 8)
- Settings management
- Employee management UI
- Audit log
- Error handling polish
- **Milestone:** Feature-complete

### Phase 6 — Extras (Week 9-10)
- PDF report export
- Email service + low stock alerts
- Backup job
- Deployment to chosen environment
- **Milestone:** Production-ready

### Phase 7 — Testing & Training (Week 11)
- User acceptance testing with the team
- Training session
- Documentation
- Soft launch
- **Milestone:** Live

**Total: ~10-11 weeks** for a single developer working part-time. Half that with a full-time developer.

---

## 11. Dependencies (pom.xml highlights)

```xml
<dependencies>
  <!-- Spring Boot core -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
  </dependency>

  <!-- PostgreSQL driver -->
  <dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
  </dependency>

  <!-- Flyway for DB migrations -->
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
  </dependency>
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
  </dependency>

  <!-- JWT -->
  <dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.5</version>
  </dependency>
  <dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.5</version>
    <scope>runtime</scope>
  </dependency>
  <dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.5</version>
    <scope>runtime</scope>
  </dependency>

  <!-- iText 7 for PDF generation -->
  <dependency>
    <groupId>com.itextpdf</groupId>
    <artifactId>itext-core</artifactId>
    <version>8.0.4</version>
    <type>pom</type>
  </dependency>

  <!-- Swagger / OpenAPI docs -->
  <dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.5.0</version>
  </dependency>

  <!-- Lombok (less boilerplate) -->
  <dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
  </dependency>

  <!-- Testing -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

---

## 12. Configuration (application.yml)

```yaml
spring:
  application:
    name: rrbm-backend

  datasource:
    url: jdbc:postgresql://localhost:5432/rrbm_db
    username: rrbm_user
    password: ${DB_PASSWORD:changeme}
    hikari:
      maximum-pool-size: 10

  jpa:
    hibernate:
      ddl-auto: validate     # Flyway handles schema, JPA just verifies
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true
    show-sql: false

  flyway:
    enabled: true
    baseline-on-migrate: true

  mail:
    host: smtp.gmail.com
    port: 587
    username: ${MAIL_USERNAME}
    password: ${MAIL_APP_PASSWORD}
    properties:
      mail.smtp.starttls.enable: true

server:
  port: 8080
  servlet:
    context-path: /

rrbm:
  jwt:
    secret: ${JWT_SECRET:change-this-to-a-256-bit-secret-key-in-production}
    expiration-hours: 8
  cors:
    allowed-origins:
      - http://localhost:8000
      - http://localhost:8080
  backup:
    directory: /var/backups/rrbm
    pg-dump-path: /usr/bin/pg_dump

logging:
  level:
    com.rrbm: INFO
    org.springframework.security: WARN
```

---

## 13. Security Checklist

- [x] BCrypt password hashing (cost 10)
- [x] JWT signed with 256-bit secret
- [x] HTTPS required in production
- [x] SQL injection prevented (JPA parameterized queries)
- [x] CORS allowlist (not wildcard)
- [x] Role-based access control with `@PreAuthorize`
- [x] Audit log on sensitive actions (login, cancel, settings change)
- [x] Master key hashed (not plaintext)
- [x] Rate limiting on login endpoint (5 attempts / minute)
- [x] No sensitive data in JWT payload
- [x] No passwords ever returned in API responses
- [x] CSRF disabled (we use JWT, not cookies)

---

## 14. What's NOT in v1

To ship sooner, these get deferred to v2:

- Real-time updates (WebSocket / Server-Sent Events) — staff need to refresh to see new orders
- Multi-language support — Filipino translation
- Mobile app — frontend is responsive, that's enough for now
- Customer database — orders just store the name as text
- Supplier management
- Purchase orders / restocking workflow
- Sales targets and commission tracking
- Integration with Shopee/TikTok/Lazada APIs for auto-import

---

## 15. Next Step

Pick **one** of these to do next:

1. **Set up the project skeleton** — I generate the actual Maven project with all the boilerplate files (pom.xml, application.yml, main class, security config, Flyway migrations). You can immediately run `mvn spring-boot:run` and get a working empty backend.

2. **Build Phase 1 (auth)** — actual working code for the login endpoint, JWT generation, and frontend wire-up. End result: real login works.

3. **Refine this plan** — anything in here you want to change, simplify, or add before writing code? E.g., maybe you want SQLite instead of PostgreSQL for offline use, or different role names, or specific reports.

Tell me which one (or something else) and we keep moving.
