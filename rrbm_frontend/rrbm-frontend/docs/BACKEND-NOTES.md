# Backend Integration Notes

Notes for the developer who will wire this frontend up to a Java Spring Boot backend.

---

## Architecture overview

```
┌─────────────────┐         ┌──────────────────┐         ┌────────────┐
│   Browser       │  HTTPS  │  Spring Boot     │  JDBC   │  MySQL /   │
│   (this app)    │ ──────► │  REST API        │ ──────► │  Postgres  │
└─────────────────┘         └──────────────────┘         └────────────┘
```

The frontend stays exactly as-is. Only `js/app.js` changes: every function that currently manipulates the local `appState` arrays will instead call an HTTP endpoint and update the UI from the response.

---

## Functions to swap with API calls

Open `js/app.js` and find each function below. Replace the mock logic with `fetch()` calls.

### `doLogin()` — Authentication

**Current:** Accepts any non-empty email.
**Replace with:**
```javascript
window.doLogin = async function () {
  const email = $('login-email').value;
  const password = $('login-password').value;
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password })
  });
  if (!res.ok) {
    showToast('Invalid credentials', 'error');
    return;
  }
  const { token, user } = await res.json();
  sessionStorage.setItem('rrbm_token', token);
  sessionStorage.setItem('rrbm_user', JSON.stringify(user));
  $('login-screen').style.display = 'none';
};
```

### `addOrder()` — Create order

**Current:** Pushes to `appState.orders` array.
**Replace with:**
```javascript
window.addOrder = async function () {
  const payload = {
    customer: $('field-customer').value || 'Walk-in',
    product: $('field-product').value,
    quantity: +$('field-qty').value,
    unitPrice: +$('field-price').value,
    source: $('field-source').value,
    agentName: $('field-agent').value || null,
    fbPage: $('field-fb').value || null,
    paymentMode: $('field-payment').value,
  };
  const res = await apiPost('/api/orders', payload);
  showToast('Order added: ' + res.id, 'success');
  await refreshOrders();
};
```

### `confirmCancel()` — Cancel order with master key

**Current:** Mutates `order.st = 'Cancelled'`.
**Replace with:**
```javascript
window.confirmCancel = async function () {
  const res = await apiPost(`/api/orders/${appState.cancelTargetId}/cancel`, {
    masterKey: $('cancel-key-input').value
  });
  if (!res.ok) {
    showToast('Invalid master key', 'error');
    return;
  }
  closeModal();
  await refreshOrders();
  showToast('Order cancelled', 'success');
};
```

### `renderOrders()`, `renderOrderList()`, `renderInventory()`

These currently read from local arrays. Add a fetch at the top:
```javascript
async function renderOrders() {
  const data = await apiGet('/api/orders/today');
  // ... existing rendering logic, but use `data` instead of `seedOrders`
}
```

### Dashboard KPI cards

The hardcoded values in `index.html` should be replaced with values fetched from `/api/dashboard/summary` on page load.

---

## Suggested REST endpoints

| Method | Path                              | Purpose                          |
|--------|-----------------------------------|----------------------------------|
| POST   | `/api/auth/login`                 | Authenticate, return JWT         |
| POST   | `/api/auth/logout`                | Invalidate session               |
| GET    | `/api/dashboard/summary`          | Today's KPIs for dashboard       |
| GET    | `/api/dashboard/sales-trend`      | 7-day sales chart data           |
| GET    | `/api/dashboard/ecommerce-split`  | Shopee/TikTok/Lazada breakdown   |
| GET    | `/api/orders`                     | List all orders (paginated)      |
| GET    | `/api/orders/today`               | Today's orders only              |
| POST   | `/api/orders`                     | Create new order                 |
| POST   | `/api/orders/{id}/cancel`         | Cancel with master key           |
| POST   | `/api/orders/close-sales`         | End-of-day closeout              |
| GET    | `/api/inventory`                  | All products with stock          |
| POST   | `/api/inventory`                  | Add new product                  |
| PUT    | `/api/inventory/{id}`             | Update stock                     |
| GET    | `/api/reports/monthly`            | Monthly revenue series           |
| GET    | `/api/reports/comparison`         | Direct vs E-commerce             |
| GET    | `/api/employees`                  | Team roster                      |
| POST   | `/api/employees`                  | Add team member                  |
| GET    | `/api/settings`                   | Company settings                 |
| PUT    | `/api/settings`                   | Update settings (Super Admin)    |

---

## Request/Response shapes

### Order object
```json
{
  "id": "131125-000007",
  "customer": "Pizza Hut Cubao",
  "product": "Pizza Box 10\" White",
  "quantity": 200,
  "unitPrice": 15.00,
  "total": 3000.00,
  "source": "Agent",
  "agentName": "Maria Santos",
  "fbPage": null,
  "paymentMode": "GCash",
  "status": "Active",
  "createdAt": "2025-11-13T08:32:11Z",
  "createdBy": "ryan@rrbm.com"
}
```

### Inventory product
```json
{
  "id": 1,
  "name": "Pizza Box 10\" White",
  "category": "Pizza Boxes",
  "tag": "hot",
  "warehouses": { "wh1": 1000, "wh2": 800, "wh3": 600 },
  "total": 2400,
  "thresholdCritical": 3000,
  "thresholdLow": 5000,
  "status": "Critical"
}
```

### Dashboard summary
```json
{
  "totalSales": 48750,
  "salesChangePercent": 12.5,
  "activeOrders": 23,
  "pendingPayment": 5,
  "pizzaBoxesSold": 3420,
  "ecommerceOrders": 14,
  "cashOnHand": 32400,
  "ewalletTotal": 16350,
  "shopeeOrders": 8,
  "lowStockItems": 7
}
```

---

## Suggested database schema

```sql
-- Users (employees with login)
CREATE TABLE users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  email VARCHAR(120) UNIQUE NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  full_name VARCHAR(120),
  role ENUM('SUPER_ADMIN', 'ADMIN', 'STAFF') NOT NULL,
  status ENUM('ACTIVE', 'AWAY', 'DISABLED') DEFAULT 'ACTIVE',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Products + warehouse stock
CREATE TABLE products (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(200) NOT NULL,
  category VARCHAR(80),
  selling_tag ENUM('HOT', 'SELLING', 'SLOW') DEFAULT 'SELLING',
  unit_price DECIMAL(10, 2),
  threshold_critical INT DEFAULT 1000,
  threshold_low INT DEFAULT 0,
  stock_wh1 INT DEFAULT 0,
  stock_wh2 INT DEFAULT 0,
  stock_wh3 INT DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Orders (header)
CREATE TABLE orders (
  id VARCHAR(13) PRIMARY KEY,  -- DDMMYY-NNNNNN
  customer_name VARCHAR(200),
  source ENUM('WALK_IN', 'AGENT', 'ECOMMERCE', 'FACEBOOK_PAGE'),
  agent_name VARCHAR(120),
  fb_page VARCHAR(120),
  ecommerce_platform ENUM('SHOPEE', 'TIKTOK', 'LAZADA'),
  payment_mode ENUM('CASH', 'GCASH', 'PAYMAYA', 'BANK_TRANSFER', 'BANK_DEPOSIT'),
  total DECIMAL(12, 2),
  status ENUM('ACTIVE', 'PENDING', 'CANCELLED', 'CLOSED') DEFAULT 'ACTIVE',
  created_by BIGINT REFERENCES users(id),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  cancelled_at TIMESTAMP NULL,
  cancelled_by BIGINT NULL REFERENCES users(id)
);

-- Order line items
CREATE TABLE order_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id VARCHAR(13) REFERENCES orders(id),
  product_id BIGINT REFERENCES products(id),
  product_name VARCHAR(200),  -- denormalized for history
  quantity INT,
  unit_price DECIMAL(10, 2),
  subtotal DECIMAL(12, 2)
);

-- Daily counter for Order ID generation
CREATE TABLE order_id_counter (
  date_key VARCHAR(6) PRIMARY KEY,  -- DDMMYY
  last_number INT NOT NULL DEFAULT 0
);

-- System settings
CREATE TABLE settings (
  key_name VARCHAR(80) PRIMARY KEY,
  value VARCHAR(255),
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
-- Seed: company_name, daily_reset_time, master_key_hash,
--       threshold_hot_critical, threshold_hot_low, threshold_sel_critical

-- Audit log (recommended for cancellations)
CREATE TABLE audit_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT REFERENCES users(id),
  action VARCHAR(80),
  entity_type VARCHAR(40),
  entity_id VARCHAR(40),
  details JSON,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

---

## Spring Boot project structure (suggested)

```
rrbm-backend/
├── src/main/java/com/rrbm/
│   ├── RrbmApplication.java
│   ├── config/          (SecurityConfig, CorsConfig, JwtConfig)
│   ├── controller/      (AuthController, OrderController, InventoryController, ...)
│   ├── service/         (OrderService, InventoryService, ReportService, ...)
│   ├── repository/      (Spring Data JPA interfaces)
│   ├── entity/          (User, Product, Order, OrderItem, ...)
│   ├── dto/             (Request/Response DTOs)
│   └── scheduler/       (DailyResetJob — runs at 12:00 AM)
├── src/main/resources/
│   ├── application.yml
│   └── static/          ← Copy the frontend folder contents here
└── pom.xml
```

When you put the frontend files inside `src/main/resources/static/`, Spring Boot will serve them automatically. The app will be accessible at the root URL (e.g., `http://localhost:8080`).

---

## Daily reset job

The boss wants a daily reset at midnight (12:00 AM). Implement with Spring's `@Scheduled`:

```java
@Component
public class DailyResetJob {
  @Scheduled(cron = "0 0 0 * * *")  // 12:00 AM every day
  public void resetDailyCounters() {
    // Archive yesterday's "ACTIVE" orders → "CLOSED"
    // Reset the order ID counter for the new day
    // Snapshot the day's totals into reports
  }
}
```

---

## Role-based access control

Spring Security with method-level annotations:

```java
@PreAuthorize("hasRole('SUPER_ADMIN')")
public void updateSettings(...) { ... }

@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
public ReportData generateReports(...) { ... }

@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'STAFF')")
public Order createOrder(...) { ... }
```

In the frontend, after login, hide menu items based on the user's role:
```javascript
const user = JSON.parse(sessionStorage.getItem('rrbm_user'));
if (user.role === 'STAFF') {
  document.querySelector('[data-view="set"]').style.display = 'none';
  document.querySelector('[data-view="emp"]').style.display = 'none';
}
```

---

## CORS configuration

If the frontend is served from a different origin than the API (e.g., during development with the frontend on port 8000 and Spring Boot on 8080), configure CORS:

```java
@Configuration
public class CorsConfig implements WebMvcConfigurer {
  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/**")
      .allowedOrigins("http://localhost:8000")
      .allowedMethods("GET", "POST", "PUT", "DELETE")
      .allowCredentials(true);
  }
}
```

For production, set `allowedOrigins` to your actual domain.

---

## Helper to add to app.js

A small wrapper that handles auth headers and JSON automatically:

```javascript
async function apiGet(path) {
  const res = await fetch(path, {
    headers: { 'Authorization': 'Bearer ' + sessionStorage.getItem('rrbm_token') }
  });
  if (res.status === 401) { doLogout(); return null; }
  return res.json();
}

async function apiPost(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer ' + sessionStorage.getItem('rrbm_token')
    },
    body: JSON.stringify(body)
  });
  if (res.status === 401) { doLogout(); return null; }
  return res.json();
}
```

---

## Order ID generation (server-side)

To avoid race conditions when multiple staff create orders at the same instant, do the counter increment server-side inside a transaction:

```java
@Transactional
public String generateOrderId() {
  String dateKey = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyy"));
  OrderIdCounter counter = counterRepo.findById(dateKey)
    .orElseGet(() -> new OrderIdCounter(dateKey, 0));
  counter.setLastNumber(counter.getLastNumber() + 1);
  counterRepo.save(counter);
  return String.format("%s-%06d", dateKey, counter.getLastNumber());
}
```

---

## Where the frontend already supports the backend

These pieces are ready and don't need changes:
- All HTML form fields have IDs (`field-customer`, `field-qty`, etc.) — easy to grab values
- All render functions use a single source of truth (mock arrays) — swap to API and the UI works
- Toast notifications for success/error feedback already work
- The modal pattern for confirmations is reusable
- The theme toggle, clock, and navigation don't need any backend changes

Good luck!
