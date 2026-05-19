# Features — Module by Module

Detailed walkthrough of every screen in the RRBM management system.

---

## 1. Login Screen

**Purpose:** Authenticate users before granting workspace access.

**What's shown:**
- Full RRBM logo (white, on dark brown background)
- "Packaging Supplies and Trading" tagline
- Email + password fields
- "Sign In" button
- Demo credentials hint

**Demo behavior:**
- Email: `admin@rrbm.com` (pre-filled)
- Password: any value (validation is mocked)
- Empty email shows an error toast

**To make this real:** Replace `doLogin()` in `app.js` with an API call to your auth endpoint. Store the returned JWT in `sessionStorage` or `localStorage`, and add a check on page load to redirect unauthenticated users back here.

---

## 2. Dashboard

**Purpose:** At-a-glance daily metrics for the boss.

**Top row — 4 KPI cards:**
- Total Sales (with day-over-day change indicator)
- Active Orders (with pending count)
- **Pizza Boxes Sold** (boss requested this prominently — total pcs sold today)
- E-commerce Orders

**Second row — 4 KPI cards:**
- Cash on Hand
- E-wallet / Online (GCash, PayMaya, etc. combined)
- Shopee Orders specifically
- Low Stock Items (in red — alert color)

**Daily / Weekly / Monthly tabs** at the top right let users switch the timeframe (currently visual only — needs backend hookup).

**Charts:**
- Sales Trend — line chart, last 7 days, Direct vs E-commerce
- E-commerce Split — doughnut chart of Shopee / TikTok / Lazada

**Tables:**
- Top Selling Products — sorted by units sold
- Low Stock Alerts — color-coded by severity

---

## 3. New Order

**Purpose:** Create new orders quickly during business hours.

**Order ID** is auto-generated and shown as a badge at the top. Format: `DDMMYY-000001` (e.g., `131125-000007` for the 7th order on Nov 13, 2025).

**Form fields:**
- Customer Name (defaults to "Walk-in")
- Source — dropdown: Walk-in / Agent / Ecommerce / Facebook Page
- **Agent Name** — only shows when source = "Agent"
- **FB Page** — only shows when source = "Facebook Page"
- Payment Mode — Cash / GCash / PayMaya / Bank Transfer / Bank Deposit

**Product line:**
- Product dropdown
- Quantity, Unit Price (both editable)
- **Total** auto-calculates as you type
- "Add" button appends to today's orders

**Today's Orders table** shows everything entered today, with:
- Order ID
- Customer
- Product
- Qty, Total
- Source (with Shopee chip for ecommerce orders)
- Payment mode
- Status (with colored dot)

**"Close Sales" button** at the top of the table — for end-of-day closeout. Currently shows a confirmation toast; backend hookup will finalize the day's records.

---

## 4. Order List

**Purpose:** Search and manage all historical orders.

**Filters:**
- Search bar (by order ID, customer, etc.)
- Status filter dropdown (All / Active / Pending / Cancelled)

**Table columns:**
- Order ID, Date, Customer, Items, Total, Status
- **Cancel button** on each non-cancelled row

**Cancellation flow:**
1. Click "Cancel" on any order
2. Modal pops up showing the order ID
3. Enter the **master key** (set in Settings)
4. Confirm — order status changes to "Cancelled"
5. Empty key shows an error; correct key (anything non-empty in the demo) succeeds

This protects against accidental cancellations by staff.

---

## 5. Inventory

**Purpose:** Track stock across 3 warehouses with smart status indicators.

**Columns:**
- Product
- **Tag** — Hot Selling / Selling / Slow Moving (visual pill)
- WH1, WH2, WH3 — quantity in each warehouse
- **Total** — sum across all warehouses
- Stock Bar — visual progress bar (color-coded)
- Status badge

**Threshold logic** (matches your requirements):
- **Hot Selling** items:
  - Critical (red row) when total < 3,000 pcs
  - Low warning (yellow row) when total < 5,000 pcs
- **Selling / Slow Moving** items:
  - Critical (red row) when total < 1,000 pcs
- **Out of Stock** (grey row) when total = 0

**Filters:**
- Category dropdown (Pizza Boxes / Burger Boxes / Bags / etc.)
- Search (planned)

**Add Product button** — opens product creation form (backend hookup needed).

---

## 6. Reports

**Purpose:** Analyze trends over time.

**Top summary cards:**
- Best Day This Month — highest single-day revenue
- Month Total — running total with comparison to previous month

**Monthly Revenue Trend** — bar chart, last 6 months
**Direct vs E-commerce** — line chart comparing the two channels over 6 months

**Planned additions** (need backend):
- Export to PDF
- Date range picker
- Per-product breakdowns
- Per-employee sales reports

---

## 7. Employees

**Purpose:** Manage team access and permissions.

**Columns:**
- Name
- Email
- **Role** — Super Admin / Admin / Staff (color-coded badge)
- Permissions (text summary)
- Status — Active / Away

**Roles defined:**
- **Super Admin** (1 person — Ryan) — Full access including Settings and master key
- **Admin** — Orders, Inventory, Reports (no Settings)
- **Staff** — Orders only by default; can be granted Inventory access

**Add Member button** — opens user creation form (backend hookup needed).

---

## 8. Settings

**Purpose:** Configure system-wide options.

**Company card:**
- Company Name
- Daily Reset Time (defaults to 12:00 AM)
- Master Key (for cancellation flow)

**Stock Thresholds card:**
- Hot Selling — Critical threshold
- Hot Selling — Low Warning threshold
- Selling / Slow — Critical threshold

These thresholds drive the color coding in the Inventory view.

**Appearance card:**
- Dark Mode toggle

---

## Cross-cutting features

### Dark / Light Mode

Click the sun/moon icon in the topbar (or the Toggle button in Settings). Every screen — login, sidebar, cards, tables, charts — adapts to the theme automatically.

### Live Clock

The current time displays in the topbar (HH:MM:SS, updates every second).

### Toast Notifications

Actions like "Order added" or "Master key required" show as small notification cards in the top-right corner. They auto-dismiss after 3 seconds.

### Responsive Design

- **Desktop (>900px):** Full sidebar with labels
- **Tablet (600–900px):** Icon-only sidebar
- **Mobile (<600px):** Stacked single-column layout

### Order ID Format

`DDMMYY-NNNNNN`
- DD = day (01–31)
- MM = month (01–12)
- YY = 2-digit year
- NNNNNN = 6-digit sequential counter that resets daily

Example: `131125-000003` = 3rd order on November 13, 2025.

---

## Keyboard shortcuts

None implemented yet. Planned for backend phase:
- `Ctrl + N` — New order
- `Ctrl + K` — Quick search
- `Esc` — Close modal
