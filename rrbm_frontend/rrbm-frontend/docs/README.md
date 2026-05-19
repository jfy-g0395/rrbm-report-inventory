# RRBM Packaging Supplies and Trading — Frontend

A web-based order and inventory management system for RRBM Packaging, built as a static HTML/CSS/JavaScript prototype ready to be wired up to a Java Spring Boot backend.

---

## What this is

This is the complete **frontend prototype** for the RRBM management system. It runs entirely in the browser with no backend required — all data is currently mocked in JavaScript for demonstration purposes. The goal is to show the full UI/UX so the team can review every screen before backend development begins.

## What's included

Seven fully designed modules:

1. **Login Screen** — Branded sign-in with the RRBM logo
2. **Dashboard** — KPI cards, sales charts, top products, low-stock alerts
3. **New Order** — Form with auto-generated Order IDs (`DDMMYY-000001` format), live total calculation, and source-conditional fields
4. **Order List** — Full searchable order history with master-key-protected cancellation
5. **Inventory** — 3-warehouse stock tracking, color-coded by threshold status
6. **Reports** — Monthly trends, Direct vs E-commerce comparison
7. **Employees** — Team roster with role badges
8. **Settings** — Company info, stock thresholds, theme toggle

Plus: dark/light mode toggle, live clock, toast notifications, and responsive design.

---

## Quick start

### Option 1: Just open it
Double-click `index.html` to open in your browser. That's it.

### Option 2: Use a local server (recommended)
Some browsers may block local file loading. To avoid this, serve the folder with any simple HTTP server:

**With Python (already installed on most systems):**
```bash
cd rrbm-frontend
python3 -m http.server 8000
```
Then open `http://localhost:8000` in your browser.

**With Node.js:**
```bash
cd rrbm-frontend
npx serve .
```

**With VS Code:**
Install the "Live Server" extension, right-click `index.html`, choose "Open with Live Server".

### Demo login
- Email: `admin@rrbm.com`
- Password: anything (the demo accepts any password)

---

## Folder structure

```
rrbm-frontend/
├── index.html              ← Main entry point. Open this.
├── css/
│   └── styles.css          ← All styling (brand colors, themes, components)
├── js/
│   └── app.js              ← All application logic
├── assets/
│   └── rrbm-logo.png       ← Your company logo
└── docs/
    ├── README.md           ← This file
    ├── SETUP.md            ← Detailed setup & deployment guide
    ├── FEATURES.md         ← Module-by-module feature breakdown
    └── BACKEND-NOTES.md    ← Hooks and notes for backend integration
```

---

## Brand & design

- **Primary dark:** `#2C1A0E` (dark brown — sidebar, login background)
- **Primary accent:** `#D4860A` (honey gold — buttons, active states)
- **Yellow highlight:** `#FAD16A` (avatar gradient, accents)
- **Font:** DM Sans (loaded from Google Fonts)
- **Icons:** Tabler Icons (loaded from jsDelivr CDN)
- **Charts:** Chart.js 4.4.1 (loaded from cdnjs)

All design tokens live in `css/styles.css` under `:root` and `[data-theme="..."]` selectors. Edit those variables to retheme the entire app at once.

---

## What works (live in the prototype)

✅ Login → workspace flow
✅ Navigation between all 7 modules
✅ New order creation with auto-incrementing IDs
✅ Order ID format: `DDMMYY-000001`
✅ Source-dependent fields (Agent name appears only when "Agent" is selected, etc.)
✅ Live total calculation
✅ Order cancellation flow with master-key modal
✅ Color-coded inventory rows (critical / hot-selling-low / out-of-stock / OK)
✅ Selling tags (HOT / SELLING / SLOW)
✅ Sales charts (Direct vs E-commerce trend, e-commerce platform split)
✅ Monthly revenue & comparison charts in Reports
✅ Dark/light mode toggle (works across every screen)
✅ Live clock in the topbar
✅ Toast notifications for actions
✅ Responsive layout — collapses sidebar on tablets/phones

## What's mocked (needs backend)

🔌 Authentication — accepts any password
🔌 Order persistence — orders reset on page reload
🔌 Inventory data — hardcoded array in `app.js`
🔌 Employee data — hardcoded table
🔌 Reports analytics — static numbers
🔌 PDF report generation
🔌 Daily reset cron (currently no scheduled jobs)

See `docs/BACKEND-NOTES.md` for which JavaScript functions to swap with real API calls.

---

## Next steps

1. Review every screen with your team
2. Note any UI changes you'd like
3. Once approved, move to backend planning (Java Spring Boot + database schema)

---

**Company:** RRBM Packaging Supplies and Trading
**Document version:** Frontend Prototype v1.0
