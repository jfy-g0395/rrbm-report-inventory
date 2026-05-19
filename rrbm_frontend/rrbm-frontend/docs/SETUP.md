# Setup & Deployment Guide

Step-by-step instructions for running, customizing, and deploying the RRBM frontend.

---

## 1. Running locally

### Method A: Direct file open (simplest)

1. Locate the `rrbm-frontend` folder on your computer
2. Double-click `index.html`
3. Your default browser will open the app

**Note:** Some browsers (especially Chrome) restrict local file features. If the logo doesn't appear or charts look broken, use Method B instead.

### Method B: Local server (recommended)

**Using Python** (already installed on Mac/Linux, easy install on Windows):

```bash
# Open Terminal / Command Prompt
cd path/to/rrbm-frontend
python3 -m http.server 8000
```

Then visit: **http://localhost:8000**

To stop the server, press `Ctrl + C` in the terminal.

**Using Node.js:**

```bash
cd path/to/rrbm-frontend
npx serve .
```

**Using VS Code:**

1. Install the **Live Server** extension by Ritwick Dey
2. Open the `rrbm-frontend` folder in VS Code
3. Right-click `index.html` → **Open with Live Server**

---

## 2. Customizing the branding

### Change the logo

Replace `assets/rrbm-logo.png` with your new logo file. Keep the filename the same — everything else will continue to work.

If you want to use a different filename, update these references:
- `index.html`: search for `rrbm-logo.png` (3 occurrences — favicon, login, sidebar)

### Change brand colors

Open `css/styles.css` and find the `:root` block at the top. Edit these variables:

```css
:root {
  --brand-dark:   #2C1A0E;   /* Dark brown — sidebar, login bg */
  --brand-brown:  #4A2C17;   /* Brown — accents */
  --brand-honey:  #D4860A;   /* Honey gold — primary buttons */
  --brand-honey-lt: #F0A830;
  --brand-yellow: #FAD16A;   /* Yellow highlight */
  --brand-yellow-lt: #FEF1C0;
}
```

Save the file and refresh the browser. All buttons, badges, charts, and accents will update automatically.

### Change the company name

Search for "RRBM Packaging Supplies and Trading" in `index.html` and replace with your company name (appears in the title bar, settings page, and login tagline).

---

## 3. Editing the demo data

All mock data is in `js/app.js`. Look for these blocks near the top:

**Seed orders** (lines ~50-56):
```javascript
const seedData = [
  { c: 'Pizza Hut Cubao', p: 'Pizza Box 10" White', q: 200, ... },
  ...
];
```

**Inventory** (lines ~62-73):
```javascript
const inventory = [
  { n: 'Pizza Box 10" White', tag: 'hot', w1: 1000, w2: 800, w3: 600, thr: 5000 },
  ...
];
```

Field reference:
- `c` = customer
- `p` = product name
- `q` = quantity
- `t` = total
- `src` = source (Walk-in / Agent / Ecommerce / Facebook Page)
- `pm` = payment mode
- `st` = status (Active / Pending / Cancelled)
- `tag` = selling tag (`hot` / `sel` / `slw`)
- `w1`, `w2`, `w3` = stock per warehouse
- `thr` = threshold for low-stock warning

The **Employees** and **Top Selling Products** tables are hardcoded in `index.html` for now — edit them directly there.

---

## 4. Deployment options

### Static hosting (free)

This frontend is fully static, so it can be deployed for free on any of these:

**Netlify** — drag-and-drop deploy:
1. Go to https://app.netlify.com/drop
2. Drag the `rrbm-frontend` folder onto the page
3. Get a live URL instantly

**Vercel:**
1. Install Vercel CLI: `npm i -g vercel`
2. Run `vercel` in the folder
3. Follow prompts

**GitHub Pages:**
1. Push the folder to a GitHub repository
2. Go to Settings → Pages
3. Select branch → save

### Self-hosted (on your own server)

Upload the entire `rrbm-frontend` folder to your web server's public directory (e.g., `/var/www/html/` on Linux, or the `htdocs` folder for XAMPP). Visit your domain to see the app.

Apache and Nginx serve static files out of the box — no configuration needed.

---

## 5. Browser support

Tested and working on:
- ✅ Chrome / Edge (recent versions)
- ✅ Firefox (recent versions)
- ✅ Safari 14+
- ✅ Mobile Safari (iOS)
- ✅ Chrome Mobile (Android)

Internet Explorer is **not** supported.

---

## 6. Troubleshooting

**Logo doesn't show up**
→ You opened the file directly. Use a local server (see Method B above).

**Charts are empty / look broken**
→ Wait a second after page load — Chart.js initializes asynchronously. If still broken, check the browser console (F12) for errors. Make sure you have internet — Chart.js loads from a CDN.

**Buttons don't work / clicking does nothing**
→ Open the browser console (F12 → Console tab). Look for red error messages and report them.

**Dark mode looks weird**
→ Click the sun icon in the topbar again to toggle back to light. The setting doesn't persist between page reloads in this prototype.

**Sidebar shrinks on small screens**
→ This is intentional — the sidebar collapses to icon-only on screens under 900px wide.

**My changes don't show up**
→ Hard refresh the browser: `Ctrl + Shift + R` (Windows / Linux) or `Cmd + Shift + R` (Mac). Browsers cache CSS and JS files aggressively.

---

## 7. Internet dependencies

This prototype loads three resources from CDNs:

| Resource | URL | Purpose |
|----------|-----|---------|
| Bootstrap 5 | jsdelivr.net | Utility classes (optional, can be removed) |
| Tabler Icons | jsdelivr.net | All icons in the UI |
| Chart.js | cdnjs.com | Sales/revenue charts |
| Google Fonts | fonts.googleapis.com | DM Sans typography |

For **fully offline** use, download these libraries and host them in your `assets/` folder, then update the URLs in `index.html`. Or skip this — most deployments will have internet anyway.
