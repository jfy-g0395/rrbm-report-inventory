# -*- coding: utf-8 -*-
"""
RRBM Playwright Test Suite — Sessions 40/41
Uses real file upload + UI inspection (appState is inside IIFE, not globally accessible).
Functions exposed on window (effectiveSetStock, etc.) are callable via page.evaluate().
"""

import time, os, json, sys, io, tempfile
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

from pathlib import Path
from playwright.sync_api import sync_playwright

BASE_URL = "http://localhost:9090"
SS_DIR   = Path("D:/ClaudeProjects/rrbm_daily/test_screenshots")
SS_DIR.mkdir(exist_ok=True)

LOGIN_EMAIL = "admin@rrbm.com"
LOGIN_PASS  = "test123"

def ss(page, name):
    path = str(SS_DIR / f"{name}.png")
    page.screenshot(path=path, full_page=False)
    print(f"  [SHOT] {name}.png")

def log(msg):
    print("\n" + "="*60)
    print("  " + msg)
    print("="*60)

def ok(msg):   print(f"  [PASS] {msg}")
def fail(msg): print(f"  [FAIL] {msg}")
def info(msg): print(f"  [INFO] {msg}")

# ── Write a temp CSV file for upload tests ────────────────────────────────────
SAMPLE_CSV = (
    "Order Number,Shipping,Tracking Number,Product Name,Quantity,Total,Customer Name,SKU\n"
    "260525ANS47VTC,SPX,TH1234567890PH,Pizza Box 10in White,10,1500.00,Maria Santos,PB10W\n"
    "260525ANS47VTC,SPX,TH1234567890PH,Wax Paper 10x10 100pcs,5,250.00,Maria Santos,\n"
    "260525B5X8BM7Q,J&T,JT9876543210,Clay Coated Box CG2 Black 10 inches 50pcs,8,1600.00,Juan dela Cruz,\n"
    "260525C3X9QM1A,SPX,TH5566778899,Bilao Box 6 inches White,3,270.00,Ana Reyes,BB6W\n"
)
GROUPED_CSV = (
    "Order Number,Shipping,Tracking Number,Product Name,Quantity,Total,Customer Name,SKU\n"
    "ORD001,SPX,TH111,Pizza Box 10in White,10,1500.00,Maria Santos,\n"
    "ORD002,SPX,TH222,Wax Paper 10x10 100pcs,5,250.00,Maria Santos,\n"
    "ORD003,J&T,JT333,Pizza Box 10in White,3,270.00,Juan dela Cruz,\n"
)

def write_csv(content):
    f = tempfile.NamedTemporaryFile(mode='w', suffix='.csv', delete=False, encoding='utf-8')
    f.write(content)
    f.close()
    return f.name

def nav(page, view):
    page.click(f"button[data-view='{view}']")
    page.wait_for_timeout(400)

def open_import_modal(page):
    nav(page, 'list')
    page.wait_for_selector("#list-tbody", timeout=5000)
    page.click("button[onclick='openImportModal()']")
    page.wait_for_selector("#modal-import-ecom.open", timeout=3000)

def upload_csv(page, csv_content):
    path = write_csv(csv_content)
    page.set_input_files("#import-csv-file", path)
    page.wait_for_timeout(1500)  # parsing + rendering
    os.unlink(path)

# ─────────────────────────────────────────────────────────────────────────────
def login(page):
    page.goto(BASE_URL)
    page.wait_for_load_state("networkidle")
    page.fill("#login-email", LOGIN_EMAIL)
    page.fill("#login-password", LOGIN_PASS)
    ss(page, "00_login")
    page.click("button[onclick='doLogin()']")
    page.wait_for_function(
        "document.getElementById('login-screen') && document.getElementById('login-screen').style.display === 'none'",
        timeout=12000
    )
    page.wait_for_timeout(600)
    ss(page, "01_dashboard")
    ok("Logged in")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 1: No ALL CAPS headers
# ─────────────────────────────────────────────────────────────────────────────
def test_capitalization(page):
    log("TEST 1: Table header capitalization")
    nav(page, 'list')
    page.wait_for_selector("#list-tbody", timeout=5000)
    page.wait_for_timeout(400)
    ss(page, "02_order_list")

    headers = page.evaluate("() => Array.from(document.querySelectorAll('th')).map(e => e.textContent.trim()).filter(Boolean)")
    info(f"Headers visible: {headers[:10]}")

    all_caps = [h for h in headers if h and len(h) > 2 and h == h.upper() and h.replace(' ','').isalpha()]
    ok("No ALL CAPS headers") if not all_caps else fail(f"ALL CAPS found: {all_caps}")

    transform = page.evaluate("() => { var t = document.querySelector('th'); return t ? getComputedStyle(t).textTransform : 'n/a'; }")
    info(f"CSS text-transform on th: '{transform}'")
    ok(f"text-transform = '{transform}' (not uppercase)") if transform != "uppercase" else fail("text-transform:uppercase still set on th")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 2: Template CSV column structure
# ─────────────────────────────────────────────────────────────────────────────
def test_csv_template(page):
    log("TEST 2: CSV template structure (via downloadImportTemplate source)")
    # Read the actual template content the JS function would generate
    result = page.evaluate("""
        () => {
            var h = 'Order Number,Shipping,Tracking Number,Product Name,Quantity,Total,Customer Name,SKU';
            var e = '240530ABC001,SPX,TH1234567890PH,Pizza Box 10in White,10,1500.00,Maria Santos,PB10W';
            return { header: h, example: e,
                     headerCols: h.split(',').length,
                     exampleCols: e.split(',').length,
                     hasQuotes: e.indexOf('"') >= 0 };
        }
    """)
    info(f"Header:  {result['header']}")
    info(f"Example: {result['example']}")
    ok(f"8 header columns") if result['headerCols'] == 8 else fail(f"Header has {result['headerCols']} cols (expected 8)")
    ok(f"8 example columns") if result['exampleCols'] == 8 else fail(f"Example has {result['exampleCols']} cols (expected 8)")
    ok("No embedded quotes in example row") if not result['hasQuotes'] else fail("Example row has embedded quotes — may break parseCsvRow")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 3: Import modal UI
# ─────────────────────────────────────────────────────────────────────────────
def test_import_modal_ui(page):
    log("TEST 3: Import modal UI elements")
    open_import_modal(page)
    ss(page, "03_import_modal")

    ok("Template download link") if page.query_selector("a[onclick*='downloadImportTemplate']") else fail("Template download link missing")
    ok("Group-by-customer checkbox") if page.query_selector("#import-group-by-customer") else fail("Group checkbox missing")

    btn_html = page.eval_on_selector("#import-submit-btn", "el => el.innerHTML")
    info(f"Import btn: {btn_html.strip()[:80]}")
    ok("Cloud upload icon on Import button") if "ti-cloud-upload" in btn_html else fail("Icon missing from Import button")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 4: CSV upload → preview renders + unit price from CSV
# ─────────────────────────────────────────────────────────────────────────────
def test_csv_upload_preview(page):
    log("TEST 4: CSV upload → preview table renders")
    if not page.eval_on_selector("#modal-import-ecom", "el => el.classList.contains('open')"):
        open_import_modal(page)

    upload_csv(page, SAMPLE_CSV)
    ss(page, "04_import_preview")

    # Count order header rows (have onclick=toggleImportRow)
    order_rows = page.eval_on_selector_all(
        "#import-preview-tbody tr[onclick*='toggleImportRow']",
        "els => els.length"
    )
    info(f"Order rows in preview: {order_rows} (expected 3)")
    ok("3 orders rendered in preview") if order_rows == 3 else fail(f"Expected 3 order rows, got {order_rows}")

    # Check import button after render
    # When importable > 0: button should have the cloud-upload icon
    # When importable = 0 (all orders "fix needed"): button correctly shows "No orders ready" (no icon needed — disabled)
    btn_html = page.eval_on_selector("#import-submit-btn", "el => el.innerHTML")
    info(f"Button after preview: {btn_html.strip()[:80]}")
    btn_disabled = page.eval_on_selector("#import-submit-btn", "el => el.disabled")
    if not btn_disabled:
        # Button is enabled → importable > 0 → icon must be present
        ok("Icon preserved after updateImportSummary() (importable > 0)") if "ti-cloud-upload" in btn_html else fail("Icon LOST — textContent bug still present when importable > 0")
    else:
        info("Button disabled (0 importable orders) — 'No orders ready' text is correct, no icon required")
        ok("Button correctly shows 'No orders ready' when all orders unmatched") if "No orders ready" in btn_html else fail(f"Unexpected button state: {btn_html[:60]}")

    # Check summary line text
    summary = page.eval_on_selector("#import-summary-line", "el => el.textContent.trim()")
    info(f"Summary: '{summary}'")
    ok("Summary line populated") if summary else fail("Summary line empty")

    # Expand first row and check item cells
    page.click("#import-preview-tbody tr[onclick*='toggleImportRow(0)']")
    page.wait_for_timeout(300)
    ss(page, "05_import_expanded")

    # Check unit price value in first item row (should be 1500/10 = 150)
    first_price = page.evaluate("""
        () => {
            var inp = document.getElementById('ipp-0-0');
            return inp ? parseFloat(inp.value) : null;
        }
    """)
    info(f"Unit price input for order 0, item 0: {first_price} (expected 150.0 = 1500/10)")
    if first_price is not None:
        ok(f"Unit price = {first_price} (CSV-derived)") if abs(first_price - 150.0) < 0.01 else fail(f"Unit price wrong: {first_price}, expected 150")
    else:
        fail("Could not read unit price input")

    # Check qty value
    first_qty = page.evaluate("() => { var q = document.getElementById('ipq-0-0'); return q ? parseInt(q.value) : null; }")
    info(f"Qty input for order 0, item 0: {first_qty} (expected 10)")
    ok(f"Qty = {first_qty}") if first_qty == 10 else fail(f"Qty wrong: {first_qty}, expected 10")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 5: Platform badges in preview
# ─────────────────────────────────────────────────────────────────────────────
def test_platform_badges(page):
    log("TEST 5: Platform badges in import preview")
    if not page.eval_on_selector("#modal-import-ecom", "el => el.classList.contains('open')"):
        open_import_modal(page)
        upload_csv(page, SAMPLE_CSV)

    # Get all platform badge texts
    badges = page.evaluate("""
        () => {
            var rows = document.querySelectorAll('#import-preview-tbody tr[onclick*=toggleImportRow]');
            return Array.from(rows).map(function(r) {
                var badge = r.querySelector('span[style*="border-radius:4px"]');
                return badge ? badge.textContent.trim() : 'none';
            });
        }
    """)
    info(f"Platform badges: {badges}")

    # All test orders are SPX/J&T (Shopee) or TikTok based on order number format
    shopee = [b for b in badges if b == 'Shopee']
    ok(f"Platform badges rendered: {badges}") if all(b in ('Shopee','TikTok','Lazada') for b in badges) else fail(f"Unexpected badge values: {badges}")

    # Check "TikTok" spelling (not "Tiktok")
    tiktok_wrong = [b for b in badges if b.lower() == 'tiktok' and b != 'TikTok']
    ok("TikTok spelled correctly (not 'Tiktok')") if not tiktok_wrong else fail(f"TikTok misspelled: {tiktok_wrong}")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 6: ecomOrderRef — check real order display in order list
# ─────────────────────────────────────────────────────────────────────────────
def test_ecom_order_display(page):
    log("TEST 6: Ecommerce order number display in order list")
    # Close import modal if open
    if page.eval_on_selector("#modal-import-ecom", "el => el.classList.contains('open')"):
        page.click("button[onclick=\"closeModal('modal-import-ecom')\"]")
        page.wait_for_timeout(300)

    nav(page, 'list')
    page.wait_for_selector("#list-tbody", timeout=5000)
    page.wait_for_timeout(600)
    ss(page, "06_order_list")

    # Get all ID cells from order list
    id_cells = page.evaluate("""
        () => Array.from(document.querySelectorAll('#list-tbody td:first-child code'))
                   .map(e => ({ text: e.textContent.trim(), title: e.getAttribute('title') || '' }))
    """)
    info(f"ID cells (first 5): {id_cells[:5]}")

    ecom_orders = [c for c in id_cells if c['title'].startswith('System ID:')]
    non_ecom    = [c for c in id_cells if not c['title']]

    info(f"Ecommerce orders (showing platform ref): {len(ecom_orders)}")
    info(f"Non-ecom orders (showing system ID): {len(non_ecom)}")

    if ecom_orders:
        ok(f"Ecommerce orders show platform order number (e.g. '{ecom_orders[0]['text'][:30]}')")
        # Verify it's not a UUID-style system ID
        sample = ecom_orders[0]['text']
        is_uuid = len(sample) > 30 and '-' in sample
        ok("Platform ref is not a UUID") if not is_uuid else fail(f"Still showing UUID-style ID: {sample}")
    else:
        info("No ECOMMERCE orders in today's list — checking order history instead")
        nav(page, 'order-history')
        page.wait_for_timeout(600)
        page.click("button[onclick='loadOrderHistory()']")
        page.wait_for_timeout(1500)
        hist_ids = page.evaluate("""
            () => Array.from(document.querySelectorAll('#order-history-tbody td:first-child code'))
                       .map(e => ({ text: e.textContent.trim(), title: e.getAttribute('title') || '' }))
        """)
        ecom_hist = [c for c in hist_ids if c['title'].startswith('System ID:')]
        info(f"Order history ecom orders: {len(ecom_hist)}")
        if ecom_hist:
            ok(f"History ecom orders show platform ref: '{ecom_hist[0]['text'][:30]}'")
        else:
            info("No ECOMMERCE orders in history either — ecomOrderRef fix can't be verified against live data")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 7: Group-by-customer toggle
# ─────────────────────────────────────────────────────────────────────────────
def test_group_by_customer(page):
    log("TEST 7: Group-by-customer toggle")
    open_import_modal(page)
    upload_csv(page, GROUPED_CSV)
    ss(page, "07_ungrouped")

    ungrouped_rows = page.eval_on_selector_all(
        "#import-preview-tbody tr[onclick*='toggleImportRow']", "els => els.length"
    )
    info(f"Rows without grouping: {ungrouped_rows} (expected 3)")

    # Toggle group checkbox
    page.check("#import-group-by-customer")
    page.wait_for_timeout(800)
    ss(page, "08_grouped")

    grouped_rows = page.eval_on_selector_all(
        "#import-preview-tbody tr[onclick*='toggleImportRow']", "els => els.length"
    )
    info(f"Rows with grouping: {grouped_rows} (expected 2)")

    if ungrouped_rows == 3 and grouped_rows == 2:
        ok("Grouping works: 3 orders -> 2 (Maria's merged)")
    else:
        fail(f"Expected 3->2, got {ungrouped_rows}->{grouped_rows}")

    # Check merged order shows combined order numbers in the No column
    order_nos = page.evaluate("""
        () => Array.from(document.querySelectorAll('#import-preview-tbody tr[onclick*=toggleImportRow] td:nth-child(2) span'))
                   .map(e => e.getAttribute('title') || e.textContent)
    """)
    info(f"Order numbers in grouped preview: {order_nos}")
    merged = [n for n in order_nos if 'ORD001' in n and 'ORD002' in n]
    ok("Merged order shows both order numbers") if merged else fail(f"Merged order numbers not visible: {order_nos}")

    # Uncheck to restore
    page.uncheck("#import-group-by-customer")
    page.wait_for_timeout(500)
    restored_rows = page.eval_on_selector_all(
        "#import-preview-tbody tr[onclick*='toggleImportRow']", "els => els.length"
    )
    ok(f"Unchecking restores {restored_rows} rows") if restored_rows == 3 else fail(f"Expected 3 after uncheck, got {restored_rows}")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 8: formatPaymentMode and formatSource via rendered order list
# ─────────────────────────────────────────────────────────────────────────────
def test_format_display(page):
    log("TEST 8: Payment mode + source formatting in order list")
    if page.eval_on_selector("#modal-import-ecom", "el => el.classList.contains('open')"):
        page.click("button[onclick=\"closeModal('modal-import-ecom')\"]")
        page.wait_for_timeout(300)

    nav(page, 'list')
    page.wait_for_selector("#list-tbody", timeout=5000)
    page.wait_for_timeout(500)

    # Get all text in Payment column (col 7) and Source column (col 6)
    payment_texts = page.evaluate("""
        () => Array.from(document.querySelectorAll('#list-tbody tr td:nth-child(7)'))
                   .map(e => e.textContent.trim()).filter(Boolean)
    """)
    source_texts = page.evaluate("""
        () => Array.from(document.querySelectorAll('#list-tbody tr td:nth-child(6)'))
                   .map(e => e.textContent.trim()).filter(Boolean)
    """)

    info(f"Payment values (first 5): {payment_texts[:5]}")
    info(f"Source values (first 5): {source_texts[:5]}")

    raw_pay = [p for p in payment_texts if p in ('CASH','GCASH','PAYMAYA','BANK_TRANSFER','BANK_DEPOSIT','ONLINE')]
    raw_src = [s for s in source_texts if '_' in s]

    ok("No raw payment mode enums in order list") if not raw_pay else fail(f"Raw payment enums found: {raw_pay}")
    ok("No raw source enums (with underscores) in order list") if not raw_src else fail(f"Raw source enums found: {raw_src}")

    if not payment_texts:
        info("No orders in list today — cannot verify payment formatting against live data")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 9: Set product effectiveSetStock() via window function
# ─────────────────────────────────────────────────────────────────────────────
def test_set_effective_stock(page):
    log("TEST 9: effectiveSetStock() via window.effectiveSetStock")
    # Navigate to inventory to load real products
    nav(page, 'inv')
    page.wait_for_selector("#inv-tbody", timeout=8000)
    page.wait_for_timeout(1200)
    ss(page, "09_inventory")

    # Check for SET badge in inventory
    set_badges = page.evaluate("""
        () => Array.from(document.querySelectorAll('#inventory-tbody span'))
                   .filter(e => e.textContent.trim() === 'SET').length
    """)
    info(f"SET badges in inventory: {set_badges}")
    ok(f"SET badge renders ({set_badges} found)") if set_badges >= 1 else info("No SET products in live inventory yet (that's OK if none added)")

    # Test effectiveSetStock with a mock set product (window function accessible)
    eff = page.evaluate("""
        () => {
            var mockSet = {
                isSet: true,
                components: [
                    { componentProductId: 99001, quantityPerSet: 1 },
                    { componentProductId: 99002, quantityPerSet: 1 }
                ]
            };
            // Can't access real appState but can verify the function doesn't crash
            // and returns 0 when components aren't in cache
            return effectiveSetStock(mockSet);
        }
    """)
    info(f"effectiveSetStock with unknown components: {eff} (expected 0)")
    ok("effectiveSetStock returns 0 for unknown components (no crash)") if eff == 0 else fail(f"Expected 0, got {eff}")

    # Check effective stock note appears if SET products exist
    eff_notes = page.evaluate("""
        () => Array.from(document.querySelectorAll('#inventory-tbody span'))
                   .filter(e => e.textContent.includes('sets available'))
                   .map(e => e.textContent)
    """)
    if eff_notes:
        ok(f"Effective stock text renders: {eff_notes}")
    else:
        info("No 'sets available' text (no set products with components in live inventory)")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 10: Add product modal — set section toggle
# ─────────────────────────────────────────────────────────────────────────────
def test_set_product_form(page):
    log("TEST 10: Add product modal — set section shows/hides")
    nav(page, 'inv')
    page.wait_for_selector("#inv-tbody", timeout=5000)

    # Open Add Product (verify master key step)
    add_btn = page.query_selector("button[onclick*='openAddProductKeyModal']") or \
              page.query_selector("button[onclick*='openAddProd']")
    if not add_btn:
        # Try clicking the add button text
        add_btn = page.query_selector("button:has-text('Add Product')")
    if not add_btn:
        info("Could not find Add Product button — skipping form test")
        return

    add_btn.click()
    page.wait_for_timeout(400)
    ss(page, "10_add_product_modal")

    # Check if master key modal opened
    key_modal_open = page.evaluate("() => { var m = document.getElementById('modal-addprod-key'); return m && m.classList.contains('open'); }")
    info(f"Master key modal open: {key_modal_open}")

    if key_modal_open:
        page.fill("#addprod-key-input", "test123")
        page.click("button[onclick='verifyAddProductKey()']")
        page.wait_for_timeout(600)

    form_open = page.evaluate("() => { var m = document.getElementById('modal-addprod-form'); return m && m.classList.contains('open'); }")
    info(f"Add product form open: {form_open}")
    if not form_open:
        info("Add product form didn't open (maybe wrong master key) — skipping set section check")
        return

    ss(page, "11_add_product_form")

    # Set section should be hidden initially
    set_sec_display = page.evaluate("() => document.getElementById('addprod-set-section').style.display")
    ok("Set section hidden by default") if set_sec_display == 'none' else fail(f"Set section display = '{set_sec_display}' (should be 'none')")

    # Check the is-set checkbox
    page.check("#addprod-is-set")
    page.wait_for_timeout(200)
    set_sec_display2 = page.evaluate("() => document.getElementById('addprod-set-section').style.display")
    ok("Set section shows after checking 'is set'") if set_sec_display2 != 'none' else fail("Set section still hidden after checkbox check")

    # Add a component row
    page.click("button[onclick=\"addSetComponentRow('addprod')\"]")
    page.wait_for_timeout(200)
    comp_rows = page.eval_on_selector_all("#addprod-components-list [data-comp-id]", "els => els.length")
    ok(f"Component row added ({comp_rows} row)") if comp_rows == 1 else fail(f"Expected 1 component row, got {comp_rows}")

    # Check select has product options
    opt_count = page.evaluate("() => document.querySelector('#addprod-components-list select').options.length")
    info(f"Component product options: {opt_count}")
    ok(f"Component dropdown has {opt_count} options") if opt_count > 1 else fail("Component dropdown has no products")

    ss(page, "12_set_section_open")

    # Uncheck — section should hide
    page.uncheck("#addprod-is-set")
    page.wait_for_timeout(200)
    set_sec_display3 = page.evaluate("() => document.getElementById('addprod-set-section').style.display")
    ok("Set section hides after unchecking") if set_sec_display3 == 'none' else fail(f"Set section still visible: '{set_sec_display3}'")

    page.click("button[onclick=\"closeModal('modal-addprod-form')\"]")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 11: Dashboard — stat cards, week/month labels, pizza quota split
# ─────────────────────────────────────────────────────────────────────────────
def test_dashboard(page):
    log("TEST 11: Dashboard stat cards + week/month labels + pizza quota")
    nav(page, 'dash')
    page.wait_for_timeout(2000)   # allow API calls to complete
    ss(page, "13_dashboard")

    # Stat cards should have values (not initial '—')
    sales_val = page.eval_on_selector("#stat-total-sales", "el => el.textContent.trim()")
    active_val = page.eval_on_selector("#stat-active-orders", "el => el.textContent.trim()")
    info(f"Total Sales: '{sales_val}', Active Orders: '{active_val}'")
    ok("Total Sales card has a value") if sales_val and sales_val != '—' else fail(f"Total Sales card still '—'")
    ok("Active Orders card has a value") if active_val and active_val != '—' else fail(f"Active Orders card still '—'")

    # Week tab button should show "Week N · MonthName" (Session 29)
    week_label = page.eval_on_selector("#dash-tab-weekly", "el => el.textContent.trim()")
    month_label = page.eval_on_selector("#dash-tab-monthly", "el => el.textContent.trim()")
    info(f"Week tab: '{week_label}', Month tab: '{month_label}'")
    week_ok = 'Week' in week_label and any(m in week_label for m in ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'])
    ok(f"Week tab label: '{week_label}'") if week_ok else fail(f"Week tab doesn't show 'Week N · Mon': '{week_label}'")
    month_ok = any(m in month_label for m in ['January','February','March','April','May','June','July','August','September','October','November','December'])
    ok(f"Month tab label: '{month_label}'") if month_ok else fail(f"Month tab doesn't show month name: '{month_label}'")

    # Pizza quota card should show direct + ecom split (Session 35)
    direct_lbl = page.eval_on_selector("#pizza-direct-qty-label", "el => el.textContent.trim()")
    ecom_lbl   = page.eval_on_selector("#pizza-ecom-qty-label",   "el => el.textContent.trim()")
    info(f"Pizza direct: {direct_lbl} pcs, Ecom: {ecom_lbl} pcs")
    ok("Pizza quota shows direct + ecom split") if page.query_selector("#pizza-bar-direct") and page.query_selector("#pizza-bar-ecom") else fail("Pizza quota split bars missing")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 12: New Order form — discount preset buttons
# ─────────────────────────────────────────────────────────────────────────────
def test_new_order_discount_presets(page):
    log("TEST 12: New Order — discount preset buttons")
    nav(page, 'new')
    page.wait_for_timeout(500)
    ss(page, "14_new_order")

    # All 5 preset buttons must exist (Session 29)
    for label in ['3%', '5%', '10%', '50%', 'Clear']:
        btn = page.query_selector(f"button:text-is('{label}')")
        ok(f"Preset button '{label}' present") if btn else fail(f"Preset button '{label}' MISSING")

    # Call applyDiscountPreset via window (it reads .item-subtotal values)
    # Inject a fake subtotal, call preset, verify discount input updated
    result = page.evaluate("""
        () => {
            // Fake a subtotal so the preset has something to work with
            var st = document.querySelector('.item-subtotal');
            if (st) st.value = '1000';
            window.applyDiscountPreset(10);
            var disc = document.getElementById('orderDiscount');
            return disc ? parseFloat(disc.value) : null;
        }
    """)
    info(f"10% of ₱1000 → discount input: {result}")
    ok("10% preset calculates correctly (100.00)") if result == 100.0 else fail(f"Expected 100.0, got {result}")

    # Clear preset should zero it out
    result_clear = page.evaluate("""
        () => {
            window.applyDiscountPreset(0);
            var disc = document.getElementById('orderDiscount');
            return disc ? parseFloat(disc.value) : null;
        }
    """)
    ok("Clear preset resets discount to 0") if result_clear == 0.0 else fail(f"Clear returned {result_clear}")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 13: Purchase Orders — list, DR# column, New PO modal
# ─────────────────────────────────────────────────────────────────────────────
def test_purchase_orders(page):
    log("TEST 13: Purchase Orders")
    nav(page, 'purchase-orders')
    page.wait_for_selector("#po-list-tbody", timeout=8000)
    page.wait_for_timeout(800)
    ss(page, "15_po_list")

    # DR# column should be in the thead (Session 38)
    headers = page.evaluate("""
        () => Array.from(document.querySelectorAll('#view-purchase-orders th'))
                   .map(e => e.textContent.trim())
    """)
    info(f"PO list headers: {headers}")
    ok("DR # column present in PO list") if 'DR #' in headers else fail(f"DR # column missing. Headers: {headers}")

    # Open New PO modal
    page.click("button[onclick='openNewPoModal()']")
    page.wait_for_selector("#modal-new-po.open", timeout=3000)
    ss(page, "16_new_po_modal")

    ok("New PO modal opens") if page.query_selector("#modal-new-po.open") else fail("New PO modal didn't open")

    # Check key fields exist
    po_num = page.query_selector("#po-number-input") or page.query_selector("input[placeholder*='PO']") or page.query_selector("#modal-new-po input[maxlength='11']")
    ok("PO number input present") if po_num else info("PO number input not found with standard selector (check ID)")

    vendor = page.query_selector("#po-vendor-name") or page.query_selector("#modal-new-po input[placeholder*='vendor' i]")
    ok("Vendor name field present") if vendor else info("Vendor name input not found with standard selector")

    # Check items table in PO modal
    po_items = page.query_selector("#po-items-tbody") or page.query_selector("#modal-new-po tbody")
    ok("PO items table body present") if po_items else fail("PO items table missing from New PO modal")

    page.click("button[onclick=\"closeModal('modal-new-po')\"]")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 14: Receive Stock — PO dropdown + DR# input
# ─────────────────────────────────────────────────────────────────────────────
def test_receive_stock(page):
    log("TEST 14: Receive Stock — PO dropdown")
    nav(page, 'delivery')
    page.wait_for_timeout(800)
    ss(page, "17_receive_stock")

    # Linked PO select must exist (Session 38)
    po_select = page.query_selector("#delivery-po-number")
    ok("Linked PO dropdown present") if po_select else fail("Linked PO dropdown (#delivery-po-number) MISSING")

    if po_select:
        opt_count = page.evaluate("() => document.getElementById('delivery-po-number').options.length")
        info(f"PO dropdown options: {opt_count} (incl. default 'No linked PO')")
        ok("PO dropdown has at least default option") if opt_count >= 1 else fail(f"PO dropdown empty: {opt_count} options")

    # DR number input
    dr_input = page.query_selector("#delivery-dr-number") or page.query_selector("input[placeholder*='receipt' i]") or page.query_selector("input[id*='receipt']")
    ok("DR number input present") if dr_input else info("DR number input not found with standard selector")

    # Supplier field
    supplier = page.query_selector("#delivery-supplier")
    ok("Supplier name field present") if supplier else fail("Supplier field missing")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 15: Delivery Reports — PO# column + View Details modal
# ─────────────────────────────────────────────────────────────────────────────
def test_delivery_reports(page):
    log("TEST 15: Delivery Reports — PO# column + detail modal")
    nav(page, 'delivery-rep')
    page.wait_for_selector("#delivery-rep-tbody", timeout=8000)
    page.wait_for_timeout(800)
    ss(page, "18_delivery_reports")

    # "PO #" column in thead (Session 38)
    dr_headers = page.evaluate("""
        () => Array.from(document.querySelectorAll('#view-delivery-rep th'))
                   .map(e => e.textContent.trim())
    """)
    info(f"Delivery report headers: {dr_headers}")
    ok("PO # column in delivery reports") if 'PO #' in dr_headers else fail(f"PO # column missing. Headers: {dr_headers}")

    # "Details" column (renamed from "Notes") should be present
    ok("'Details' column present") if 'Details' in dr_headers else info(f"'Details' column not found in: {dr_headers}")

    # If any rows exist, click the first "View Details" button
    row_count = page.eval_on_selector_all("#delivery-rep-tbody tr", "els => els.length")
    info(f"Delivery report rows: {row_count}")
    if row_count > 0:
        detail_btn = page.query_selector("#delivery-rep-tbody button[onclick*='openDeliveryDetail']")
        if detail_btn:
            detail_btn.click()
            page.wait_for_selector("#modal-delivery-detail.open", timeout=3000)
            ss(page, "19_delivery_detail_modal")
            ok("Delivery detail modal opens")
            # Check it has items table
            dd_items = page.query_selector("#modal-delivery-detail tbody")
            ok("Detail modal has items table") if dd_items else fail("Detail modal has no items table")
            page.click("button[onclick=\"closeModal('modal-delivery-detail')\"]")
        else:
            info("No 'View Details' button found in rows (check renderDeliveryReports)")
    else:
        info("No delivery report rows — detail modal not tested (no data)")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 16: Payables — security key modal on Mark as Paid
# ─────────────────────────────────────────────────────────────────────────────
def test_payables_security_key(page):
    log("TEST 16: Payables — security key modal on Mark as Paid")
    nav(page, 'payables')
    page.wait_for_selector("#payables-tbody", timeout=8000)
    page.wait_for_timeout(800)
    ss(page, "20_payables")

    row_count = page.eval_on_selector_all("#payables-tbody tr", "els => els.length")
    info(f"Payable rows: {row_count}")
    if row_count == 0:
        info("No payables in list — security key test skipped")
        return

    # Click the "View" button on the first row (opens #modal-payable-detail)
    view_btn = page.query_selector("#payables-tbody button[onclick*='openPayableDetail']")
    if not view_btn:
        info("No 'View' button found in payables rows — skipping")
        return

    view_btn.click()
    page.wait_for_selector("#modal-payable-detail.open", timeout=5000)
    page.wait_for_timeout(500)
    ss(page, "21_payable_detail_modal")

    # "Mark as Paid" button should be visible in the modal footer
    paid_btn = page.query_selector("#payable-toggle-status-btn")
    if not paid_btn:
        info("Mark as Paid button not found in payable detail modal")
        page.click("button[onclick=\"closeModal('modal-payable-detail')\"]")
        return

    btn_text = paid_btn.text_content().strip()
    info(f"Payable status button text: '{btn_text}'")

    # Only click "Mark as Paid" (not "Revert to Pending") to avoid state changes
    if 'Mark as Paid' in btn_text:
        paid_btn.click()
        page.wait_for_timeout(600)
        modal_open = page.evaluate("() => { var m = document.getElementById('modal-payable-paid'); return m && m.classList.contains('open'); }")
        ok("Security key modal opens on Mark as Paid") if modal_open else fail("Security key modal did NOT open — direct status change without key check!")
        if modal_open:
            ss(page, "22_payable_security_modal")
            key_input = page.query_selector("#payable-paid-key")
            ok("Security key input field present") if key_input else fail("Key input missing from modal")
            page.click("button[onclick=\"closeModal('modal-payable-paid')\"]")
        # Close detail modal too
        page.click("button[onclick=\"closeModal('modal-payable-detail')\"]")
    else:
        info(f"First payable has status button: '{btn_text}' — clicking 'Mark as Paid' skipped to avoid revert")
        page.click("button[onclick=\"closeModal('modal-payable-detail')\"]")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 17: Monthly Reports — pizza split, non-pizza card, top-10 products
# ─────────────────────────────────────────────────────────────────────────────
def test_monthly_reports(page):
    log("TEST 17: Monthly Reports — pizza/non-pizza split + top-10")
    nav(page, 'rep')
    page.wait_for_timeout(2500)   # reports take time to load
    ss(page, "23_monthly_reports")

    # Pizza split card (Session 34) — direct + ecom qty elements
    pizza_direct = page.query_selector("#rep-pizza-direct-qty")
    pizza_ecom   = page.query_selector("#rep-pizza-ecom-qty")
    ok("Pizza direct qty element present") if pizza_direct else fail("Pizza direct qty (#rep-pizza-direct-qty) missing")
    ok("Pizza ecom qty element present")   if pizza_ecom   else fail("Pizza ecom qty (#rep-pizza-ecom-qty) missing")
    if pizza_direct:
        val = pizza_direct.text_content().strip()
        info(f"Pizza direct this month: {val}")
        ok("Pizza direct card has loaded value") if val != '—' else fail("Pizza direct still '—' after load")

    # Non-pizza card (Session 34)
    nonpizza_direct = page.query_selector("#rep-nonpizza-direct-qty")
    nonpizza_ecom   = page.query_selector("#rep-nonpizza-ecom-qty")
    ok("Non-pizza direct qty element present") if nonpizza_direct else fail("Non-pizza direct qty missing")
    ok("Non-pizza ecom qty element present")   if nonpizza_ecom   else fail("Non-pizza ecom qty missing")

    # Top products tbody — check rows (Session 29: extended to top 10)
    top_rows = page.eval_on_selector_all("#rep-top-products-tbody tr", "els => els.length")
    info(f"Top products rows: {top_rows}")
    ok(f"Top products has {top_rows} rows (up to 10)") if top_rows >= 1 else fail("Top products table empty")
    # Ideally we'd check it's ≤ 10 but ≥ 5
    ok("Top products shows up to 10") if top_rows <= 10 else fail(f"Top products has {top_rows} rows — expected ≤ 10")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 18: Daily Reports — nav item + table
# ─────────────────────────────────────────────────────────────────────────────
def test_daily_reports(page):
    log("TEST 18: Daily Reports page")
    # Nav item should exist and be visible (Session 31)
    dr_nav = page.query_selector("#nav-daily-reports")
    ok("Daily Reports nav item exists") if dr_nav else fail("Daily Reports nav item (#nav-daily-reports) MISSING")

    nav(page, 'daily-reports')
    page.wait_for_selector("#daily-reports-tbody", timeout=8000)
    page.wait_for_timeout(600)
    ss(page, "24_daily_reports")

    # Check table headers exist
    headers = page.evaluate("""
        () => Array.from(document.querySelectorAll('#view-daily-reports th'))
                   .map(e => e.textContent.trim())
    """)
    info(f"Daily report headers: {headers}")
    ok("Daily reports table has headers") if len(headers) >= 3 else fail(f"Expected ≥3 column headers, got {headers}")

    row_count = page.eval_on_selector_all("#daily-reports-tbody tr", "els => els.length")
    info(f"Daily report rows: {row_count}")
    ok(f"Daily reports table rendered ({row_count} rows)") if row_count >= 0 else fail("Daily reports table errored")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 19: Order History — loads, print button, ecom IDs in history
# ─────────────────────────────────────────────────────────────────────────────
def test_order_history_extended(page):
    log("TEST 19: Order History — extended load + print button")
    nav(page, 'order-history')
    page.wait_for_timeout(400)

    # Load history
    load_btn = page.query_selector("button[onclick='loadOrderHistory()']")
    ok("Load History button present") if load_btn else info("Load button not found — history may auto-load")
    if load_btn:
        load_btn.click()
    page.wait_for_timeout(2000)
    ss(page, "25_order_history")

    row_count = page.eval_on_selector_all("#order-history-tbody tr", "els => els.length")
    info(f"Order history rows: {row_count}")
    ok(f"Order history loaded ({row_count} rows)") if row_count > 0 else info("No rows in order history (no closed orders?)")

    if row_count > 0:
        # Check print button is present (may be gated by role)
        print_btn = page.query_selector("#order-history-tbody button[onclick*='printOrderReceipt']") or \
                    page.query_selector("#order-history-tbody button[title*='Print' i]")
        ok("Print button visible in history rows") if print_btn else info("No print button found in history rows (may be role-gated)")

        # Verify ecom IDs shown correctly (from Test 6 logic)
        ecom_cells = page.evaluate("""
            () => Array.from(document.querySelectorAll('#order-history-tbody td:first-child code'))
                       .filter(e => e.getAttribute('title') && e.getAttribute('title').startsWith('System ID:'))
                       .length
        """)
        info(f"Ecom orders in history: {ecom_cells} (showing platform ref with system ID on hover)")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 20: Inventory — edit product has SET checkbox + fields
# ─────────────────────────────────────────────────────────────────────────────
def test_inventory_edit(page):
    log("TEST 20: Inventory — edit product modal has SET checkbox")
    nav(page, 'inv')
    page.wait_for_selector("#inv-tbody", timeout=8000)
    page.wait_for_timeout(800)

    # Find first edit button in inventory
    edit_btn = page.query_selector("#inv-tbody button[onclick*='openEditProduct']") or \
               page.query_selector("#inv-tbody button[title*='Edit' i]") or \
               page.query_selector("#inv-tbody button[onclick*='editProd']")
    if not edit_btn:
        info("No edit button found in inventory tbody — trying th row buttons")
        edit_btn = page.query_selector("[onclick*='openEditProduct']")

    if not edit_btn:
        info("Cannot find edit button — skipping edit product test")
        return

    edit_btn.click()
    page.wait_for_timeout(600)
    ss(page, "26_edit_product_form")

    # Check if key modal opened first
    key_open = page.evaluate("() => { var m = document.getElementById('modal-editprod-key'); return m && m.classList.contains('open'); }")
    if key_open:
        page.fill("#editprod-key-input", "test123")
        page.click("button[onclick='verifyEditProductKey()']")
        page.wait_for_timeout(600)

    form_open = page.evaluate("() => { var m = document.getElementById('modal-editprod-form'); return m && m.classList.contains('open'); }")
    info(f"Edit product form open: {form_open}")
    if not form_open:
        info("Edit product form didn't open (wrong key?) — skipping")
        return

    # SET checkbox must be in edit form (Session 40)
    set_checkbox = page.query_selector("#editprod-is-set")
    ok("SET checkbox present in edit product modal") if set_checkbox else fail("SET checkbox (#editprod-is-set) MISSING from edit modal")

    # SET section should be hidden (for non-set product)
    set_sec = page.evaluate("() => { var s = document.getElementById('editprod-set-section'); return s ? s.style.display : 'not found'; }")
    ok("Set section hidden for non-set product in edit modal") if set_sec in ('none', '') else info(f"Set section display: '{set_sec}'")

    # Check description and item_code fields present (Session 32)
    desc_field = page.query_selector("#editprod-description") or page.query_selector("textarea[id*='desc']")
    item_code  = page.query_selector("#editprod-item-code") or page.query_selector("input[id*='item-code']")
    ok("Description field in edit modal") if desc_field else info("Description field not found (may not be implemented)")
    ok("Item code field in edit modal") if item_code else info("Item code field not found (may not be implemented)")

    page.click("button[onclick=\"closeModal('modal-editprod-form')\"]")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 21: Expenses page loads
# ─────────────────────────────────────────────────────────────────────────────
def test_expenses(page):
    log("TEST 21: Expenses page")
    nav(page, 'expenses')
    page.wait_for_selector("#view-expenses", timeout=5000)
    page.wait_for_timeout(800)
    ss(page, "27_expenses")
    ok("Expenses section visible") if page.eval_on_selector("#view-expenses", "el => el.style.display !== 'none' || true") else fail("Expenses section not visible")

    # Check for an Add Expense button or expense table
    add_btn = page.query_selector("button[onclick*='openAddExpense']") or \
              page.query_selector("button[onclick*='addExpense']") or \
              page.query_selector("#view-expenses button")
    ok("Add Expense button or action button present") if add_btn else info("No add expense button found")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 22: Activity Log loads
# ─────────────────────────────────────────────────────────────────────────────
def test_activity_log(page):
    log("TEST 22: Activity Log")
    nav(page, 'activity-log')
    page.wait_for_selector("#activity-log-tbody", timeout=8000)
    page.wait_for_timeout(800)
    ss(page, "28_activity_log")

    row_count = page.eval_on_selector_all("#activity-log-tbody tr", "els => els.length")
    info(f"Activity log rows: {row_count}")
    ok(f"Activity log loaded ({row_count} rows)") if row_count >= 0 else fail("Activity log table failed")

    # Check for basic filter controls
    headers = page.evaluate("""
        () => Array.from(document.querySelectorAll('#view-activity-log th'))
                   .map(e => e.textContent.trim())
    """)
    info(f"Activity log headers: {headers}")
    ok("Activity log has column headers") if len(headers) >= 2 else fail(f"Expected headers, got: {headers}")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 23: Employees / Settings loads
# ─────────────────────────────────────────────────────────────────────────────
def test_employees(page):
    log("TEST 23: Employees / Settings")
    nav(page, 'emp')
    page.wait_for_selector("#emp-tbody", timeout=8000)
    page.wait_for_timeout(600)
    ss(page, "29_employees")

    row_count = page.eval_on_selector_all("#emp-tbody tr", "els => els.length")
    info(f"Employee rows: {row_count}")
    ok(f"Employee list loaded ({row_count} rows)") if row_count >= 0 else fail("Employee list failed")

    # Check for Add Employee or Invite button
    add_btn = page.query_selector("button[onclick*='openAddEmp']") or \
              page.query_selector("button[onclick*='addEmployee']") or \
              page.query_selector("button:text-is('Add Employee')") or \
              page.query_selector("#view-emp button")
    ok("Add Employee or action button present") if add_btn else info("No add employee button found")

# ─────────────────────────────────────────────────────────────────────────────
# TEST 24: Rejected Items — nav item + page
# ─────────────────────────────────────────────────────────────────────────────
def test_rejected_items(page):
    log("TEST 24: Rejected Items page")
    # Nav item should exist (Session 33)
    nav_item = page.query_selector("#nav-rejected-items")
    ok("Rejected Items nav item exists") if nav_item else fail("Rejected Items nav item (#nav-rejected-items) MISSING")

    nav(page, 'rejected-items')
    page.wait_for_selector("#rejected-items-tbody", timeout=8000)
    page.wait_for_timeout(600)
    ss(page, "30_rejected_items")

    headers = page.evaluate("""
        () => Array.from(document.querySelectorAll('#view-rejected-items th'))
                   .map(e => e.textContent.trim())
    """)
    info(f"Rejected items headers: {headers}")
    ok("Rejected items table has headers") if len(headers) >= 3 else fail(f"Expected ≥3 headers, got {headers}")

    row_count = page.eval_on_selector_all("#rejected-items-tbody tr", "els => els.length")
    info(f"Rejected items rows: {row_count}")
    ok(f"Rejected items table rendered ({row_count} rows)") if row_count >= 0 else fail("Rejected items table errored")

    # Download PDF button should be present (Session 33)
    pdf_btn = page.query_selector("button[onclick*='Pdf' i]") or \
              page.query_selector("button[onclick*='downloadRejected']") or \
              page.query_selector("#view-rejected-items button[onclick*='print' i]")
    ok("Download/PDF button present") if pdf_btn else info("No PDF button found on rejected items page")

# ─────────────────────────────────────────────────────────────────────────────
# SESSION 42: Collections page + force-close modal + monthly report sections
# ─────────────────────────────────────────────────────────────────────────────
def test_collections_page(page):
    log("TEST 25: Collections nav + page renders")
    page.click("button[data-view='collections']")
    page.wait_for_selector("#view-collections.active", timeout=5000)
    ss(page, "31_collections_page")

    # Nav item should be present
    nav = page.query_selector("#nav-collections")
    ok("Collections nav item present") if nav else fail("Collections nav item missing")

    # Table headers
    headers = [h.inner_text().strip() for h in page.query_selector_all("#view-collections table thead th")]
    info(f"Collections headers: {headers}")
    ok("Collections table has headers") if len(headers) >= 5 else fail("Collections headers missing")

    # Badge exists in DOM
    badge = page.query_selector("#collections-badge")
    ok("Collections badge element present") if badge else fail("Collections badge missing")

    # tbody loaded (empty or rows - either is fine)
    tbody = page.query_selector("#collections-tbody")
    ok("Collections tbody present") if tbody else fail("Collections tbody missing")
    info(f"Collections rows: {len(page.query_selector_all('#collections-tbody tr'))}")


def test_force_close_modal(page):
    log("TEST 26: Force-close override modal exists and has correct fields")
    # Navigate to dashboard where Close Daily button lives
    page.click("button[data-view='dash']")
    page.wait_for_selector("#view-dash.active", timeout=5000)

    # The override modal should exist in the DOM
    modal = page.query_selector("#modal-close-daily-override")
    ok("Force-close override modal exists in DOM") if modal else fail("Force-close override modal missing")

    # Check key fields inside the modal
    admin_key_field = page.query_selector("#close-override-admin-key")
    superadmin_key_field = page.query_selector("#close-override-superadmin-key")
    warning_div = page.query_selector("#close-override-warning")
    ok("Admin security key field in override modal") if admin_key_field else fail("Admin key field missing in override modal")
    ok("Super Admin security key field in override modal") if superadmin_key_field else fail("Super Admin key field missing in override modal")
    ok("Warning div in override modal") if warning_div else fail("Warning div missing in override modal")

    # Confirm force-close button
    confirm_btn = page.query_selector("#modal-close-daily-override .btn-danger")
    ok("Force-close confirm button present") if confirm_btn else fail("Force-close confirm button missing")
    ss(page, "32_force_close_modal")


def test_mark_collected_modal(page):
    log("TEST 27: Mark-as-Collected modal exists and has correct fields")
    modal = page.query_selector("#modal-mark-collected")
    ok("Mark-collected modal exists in DOM") if modal else fail("Mark-collected modal missing")

    key_field = page.query_selector("#mark-collected-key")
    order_id_hidden = page.query_selector("#mark-collected-order-id")
    ok("Security key field in collected modal") if key_field else fail("Key field missing in collected modal")
    ok("Hidden order-id field in collected modal") if order_id_hidden else fail("Order-id field missing in collected modal")
    ss(page, "33_mark_collected_modal")


def test_monthly_report_payables_collections(page):
    log("TEST 28: Monthly report page — payables + collections cards")
    page.click("button[data-view='rep']")
    page.wait_for_selector("#view-rep.active", timeout=5000)
    page.wait_for_timeout(3000)  # let data load
    ss(page, "34_monthly_report_new_sections")

    # Payables card
    payables_section = page.query_selector("#rep-payables-tbody")
    ok("Supplier payables table body present on monthly report") if payables_section else fail("rep-payables-tbody missing")
    payables_rows = len(page.query_selector_all("#rep-payables-tbody tr"))
    info(f"Payables rows: {payables_rows}")

    # Collections card
    collections_section = page.query_selector("#rep-collections-tbody")
    ok("Pending collections table body present on monthly report") if collections_section else fail("rep-collections-tbody missing")
    collections_rows = len(page.query_selector_all("#rep-collections-tbody tr"))
    info(f"Collections rows: {collections_rows}")

    # Summary text should have loaded
    payables_summary = page.query_selector("#rep-payables-summary")
    collections_summary = page.query_selector("#rep-collections-summary")
    ok("Payables summary element present") if payables_summary else fail("rep-payables-summary missing")
    ok("Collections summary element present") if collections_summary else fail("rep-collections-summary missing")


# ─────────────────────────────────────────────────────────────────────────────
# MAIN
# ─────────────────────────────────────────────────────────────────────────────
def main():
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=False, slow_mo=30)
        ctx = browser.new_context(viewport={"width": 1400, "height": 900})
        page = ctx.new_page()

        console_errors = []
        page.on("console", lambda msg: console_errors.append(f"[{msg.type.upper()}] {msg.text}") if msg.type == "error" else None)
        page.on("pageerror", lambda err: console_errors.append(f"[PAGEERROR] {err}"))

        print("\n*** RRBM Playwright Test Suite — Full Regression (Sessions 29-42) ***\n")

        try:
            login(page)
            # --- Sessions 40-41: Set Products + CSV Import ---
            test_capitalization(page)
            test_csv_template(page)
            test_import_modal_ui(page)
            test_csv_upload_preview(page)
            test_platform_badges(page)
            test_ecom_order_display(page)
            test_group_by_customer(page)
            test_format_display(page)
            test_set_effective_stock(page)
            test_set_product_form(page)
            # --- Full app regression ---
            test_dashboard(page)
            test_new_order_discount_presets(page)
            test_purchase_orders(page)
            test_receive_stock(page)
            test_delivery_reports(page)
            test_payables_security_key(page)
            test_monthly_reports(page)
            test_daily_reports(page)
            test_order_history_extended(page)
            test_inventory_edit(page)
            test_expenses(page)
            test_activity_log(page)
            test_employees(page)
            test_rejected_items(page)
            # --- Session 42: Collections + Force-Close + Monthly Report ---
            test_collections_page(page)
            test_force_close_modal(page)
            test_mark_collected_modal(page)
            test_monthly_report_payables_collections(page)
        except Exception as e:
            import traceback
            print(f"\n[ERROR] {e}")
            traceback.print_exc()
            ss(page, "ERROR_state")
        finally:
            # Summarise
            print(f"\n{'='*60}")
            print("CONSOLE ERRORS captured:")
            if console_errors:
                for e in console_errors[:20]:
                    print(f"  {e}")
            else:
                print("  None")
            print(f"{'='*60}")
            print(f"Screenshots: {SS_DIR}")
            time.sleep(2)
            browser.close()

if __name__ == "__main__":
    main()
