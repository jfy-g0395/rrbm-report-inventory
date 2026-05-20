/* ================================================================
   RRBM Packaging Supplies and Trading — Management System
   Main Application Script
   ================================================================ */

(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const $$ = (sel) => document.querySelectorAll(sel);

  let appState = {
    theme: 'light',
    cancelTargetId: null,
    toastId: 0,
    cachedProducts: [],
    itemRowCounter: 0,
    deliveryLineCounter: 0,
  };

  function pad(n, w) { n = '' + n; while (n.length < w) n = '0' + n; return n; }

  function formatSource(src) {
    const map = { 'WALK_IN': 'Walk-in', 'AGENT': 'Agent', 'ECOMMERCE': 'E-Commerce', 'FACEBOOK_PAGE': 'Facebook Page' };
    return map[src] || src || '';
  }

  function formatDate(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    const m = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    return m[d.getMonth()] + ' ' + d.getDate() + ', ' + d.getFullYear();
  }

  function formatTime(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    let h = d.getHours(); const mm = pad(d.getMinutes(), 2);
    const ap = h >= 12 ? 'PM' : 'AM'; h = h % 12 || 12;
    return h + ':' + mm + ' ' + ap;
  }

  function statusBadge(st) {
    const map = {
      'ACTIVE':    { dot: 'dot-active',    label: 'Active' },
      'PENDING':   { dot: 'dot-pending',   label: 'Pending' },
      'DELIVERED': { dot: 'dot-delivered',  label: 'Delivered' },
      'CANCELLED': { dot: 'dot-cancelled', label: 'Cancelled' },
      'CLOSED':    { dot: 'dot-cancelled', label: 'Closed' },
    };
    const info = map[st] || { dot: '', label: st };
    return '<span class="status-dot ' + info.dot + '"></span>' + info.label;
  }

  function authHeaders() {
    return { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + sessionStorage.getItem('rrbm_token') };
  }

  // Inventory data now comes from GET /api/products/all — no seed data needed

  // ================================================================
  // Render: Today's Orders (fetches from GET /api/orders/today)
  // ================================================================
  async function renderOrders() {
    const tb = $('orders-tbody');
    if (!tb) return;
    const token = sessionStorage.getItem('rrbm_token');
    if (!token) { tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:#999;">Please login first</td></tr>'; return; }

    try {
      const res = await fetch('http://localhost:8080/api/orders/today', { headers: { 'Authorization': 'Bearer ' + token } });
      if (!res.ok) { tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:#999;">Failed to load orders</td></tr>'; return; }
      const orders = await res.json();
      if (orders.length === 0) { tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:#999;">No orders today yet</td></tr>'; return; }

      tb.innerHTML = orders.map(function (o) {
        const first = o.items && o.items[0];
        const itemText = first ? first.productName + (o.items.length > 1 ? ' +' + (o.items.length - 1) + ' more' : '') : '-';
        const totalQty = o.items ? o.items.reduce(function (s, i) { return s + i.quantity; }, 0) : 0;
        return '<tr>'
          + '<td><code style="font-size:11px;">' + o.id + '</code></td>'
          + '<td>' + o.customerName + '</td>'
          + '<td>' + itemText + '</td>'
          + '<td>' + totalQty + '</td>'
          + '<td>₱' + Number(o.total).toLocaleString() + '</td>'
          + '<td>' + formatSource(o.source) + '</td>'
          + '<td>' + o.paymentMode + '</td>'
          + '<td>' + statusBadge(o.status) + '</td>'
          + '</tr>';
      }).join('');
    } catch (err) {
      console.error('Error loading today\'s orders:', err);
      tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:#999;">Connection error</td></tr>';
    }
  }

  // ================================================================
  // Render: All Orders (fetches from GET /api/orders)
  // ================================================================
  async function renderOrderList() {
    const tb = $('list-tbody');
    if (!tb) return;
    const token = sessionStorage.getItem('rrbm_token');
    if (!token) { tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:#999;">Please login first</td></tr>'; return; }

    try {
      const res = await fetch('http://localhost:8080/api/orders', { headers: { 'Authorization': 'Bearer ' + token } });
      if (!res.ok) { tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:#999;">Failed to load orders</td></tr>'; return; }
      const orders = await res.json();
      if (orders.length === 0) { tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:#999;">No orders found</td></tr>'; return; }

      tb.innerHTML = orders.map(function (o) {
        const first = o.items && o.items[0];
        const itemText = first ? first.productName + ' x' + first.quantity + (o.items.length > 1 ? ' +' + (o.items.length - 1) + ' more' : '') : '-';

        let actions = '';
        if (o.status === 'ACTIVE') {
          actions = '<button class="btn btn-success btn-sm" onclick="updateOrderStatus(\'' + o.id + '\', \'DELIVERED\')" title="Mark Delivered"><i class="ti ti-truck-delivery"></i></button>'
            + '<button class="btn btn-warning btn-sm" onclick="updateOrderStatus(\'' + o.id + '\', \'PENDING\')" title="Put on Hold"><i class="ti ti-clock-pause"></i></button>'
            + '<button class="btn btn-danger btn-sm" onclick="askCancel(\'' + o.id + '\')" title="Cancel"><i class="ti ti-x"></i></button>';
        } else if (o.status === 'PENDING') {
          actions = '<button class="btn btn-primary btn-sm" onclick="updateOrderStatus(\'' + o.id + '\', \'ACTIVE\')" title="Resume"><i class="ti ti-player-play"></i></button>'
            + '<button class="btn btn-danger btn-sm" onclick="askCancel(\'' + o.id + '\')" title="Cancel"><i class="ti ti-x"></i></button>';
        } else if (o.status === 'DELIVERED') {
          actions = '<span style="color:#10B981;font-size:12px;"><i class="ti ti-check"></i> Done</span>';
        } else if (o.status === 'CANCELLED') {
          actions = '<span style="color:#999;font-size:12px;">Cancelled</span>';
        }

        // Format source — include agent name or platform when present
        let srcDisplay = formatSource(o.source);
        if (o.source === 'AGENT' && o.agentName) srcDisplay += ' <span style="color:#666;font-size:11px;">(' + o.agentName + ')</span>';
        if (o.source === 'ECOMMERCE' && o.ecommercePlatform) srcDisplay += ' <span style="color:#666;font-size:11px;">/ ' + o.ecommercePlatform.charAt(0) + o.ecommercePlatform.slice(1).toLowerCase() + '</span>';
        if (o.source === 'FACEBOOK_PAGE' && o.fbPage) srcDisplay += ' <span style="color:#666;font-size:11px;">(' + o.fbPage + ')</span>';

        return '<tr>'
          + '<td><code style="font-size:11px;">' + o.id + '</code></td>'
          + '<td>' + formatDate(o.createdAt) + '</td>'
          + '<td>' + o.customerName + '</td>'
          + '<td>' + itemText + '</td>'
          + '<td>₱' + Number(o.total).toLocaleString() + '</td>'
          + '<td>' + srcDisplay + '</td>'
          + '<td>' + statusBadge(o.status) + '</td>'
          + '<td><div class="d-flex gap-1">' + actions + '</div></td>'
          + '</tr>';
      }).join('');
    } catch (err) {
      console.error('Error loading orders:', err);
      tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:#999;">Connection error</td></tr>';
    }
  }

  // ----------------------------------------------------------------
  // Update Order Status (calls backend)
  // ----------------------------------------------------------------
  window.updateOrderStatus = async function (orderId, newStatus) {
    try {
      const res = await fetch('http://localhost:8080/api/orders/' + orderId + '/status', {
        method: 'PUT', headers: authHeaders(), body: JSON.stringify({ status: newStatus })
      });
      if (res.ok) {
        const updated = await res.json();
        showToast('Order ' + orderId + ' → ' + updated.status, 'success');
        renderOrderList();
      } else {
        const err = await res.json();
        showToast('Error: ' + (err.message || 'Failed to update'), 'error');
      }
    } catch (error) {
      console.error('Error updating status:', error);
      showToast('Connection error', 'error');
    }
  };

  function populateInvCategoryDropdown(categories, previousValue) {
    const sel = $('inv-category-filter');
    if (!sel) return;
    previousValue = previousValue != null ? previousValue : sel.value;
    sel.innerHTML = '';
    const allOpt = document.createElement('option');
    allOpt.value = '';
    allOpt.textContent = 'All categories';
    sel.appendChild(allOpt);
    const list = (categories || []).filter(function (c) { return c != null && String(c).trim() !== ''; })
      .map(function (c) { return String(c).trim(); })
      .sort(function (a, b) { return a.localeCompare(b); });
    list.forEach(function (c) {
      const opt = document.createElement('option');
      opt.value = c;
      opt.textContent = c;
      sel.appendChild(opt);
    });
    if (previousValue && [...sel.options].some(function (o) { return o.value === previousValue; })) {
      sel.value = previousValue;
    }
  }

  window.onInventoryCategoryChange = function () {
    renderInventory();
  };

  // ----------------------------------------------------------------
  // Render: Inventory Table (real data from GET /api/products/all + category filter)
  // ----------------------------------------------------------------
  async function renderInventory() {
    const tb = $('inv-tbody');
    if (!tb) return;

    const token = sessionStorage.getItem('rrbm_token');
    if (!token) { tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:#999;">Please login first</td></tr>'; return; }

    const catSel = $('inv-category-filter');
    const previousCat = catSel ? catSel.value : '';

    // Show a loading state while fetching
    tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:#999;">Loading inventory...</td></tr>';

    try {
      const headers = { 'Authorization': 'Bearer ' + token };
      const [catRes, prodRes] = await Promise.all([
        fetch('http://localhost:8080/api/products/categories', { headers }),
        fetch('http://localhost:8080/api/products/all', { headers })
      ]);

      if (catRes.ok) {
        const cats = await catRes.json();
        populateInvCategoryDropdown(cats, previousCat);
      }

      if (!prodRes.ok) { tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:#999;">Failed to load inventory</td></tr>'; return; }

      let products = await prodRes.json();
      const filterCat = (catSel && catSel.value) ? catSel.value.trim() : '';
      if (filterCat) {
        products = products.filter(function (p) { return (p.category || '') === filterCat; });
      }
      if (products.length === 0) { tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:#999;">No products in this category</td></tr>'; return; }

      tb.innerHTML = products.map(function (p) {
        const wh1 = p.stockWh1 || 0;
        const wh2 = p.stockWh2 || 0;
        const wh3 = p.stockWh3 || 0;
        const total = wh1 + wh2 + wh3;

        // Use the product's actual thresholds from the database
        const critLevel = p.thresholdCritical || 0;
        const lowLevel  = p.thresholdLow || 0;
        const tag       = (p.sellingTag || 'SELLING').toUpperCase();

        // Stock bar: scale to 2× the low threshold (so "OK" fills the bar)
        const barMax = lowLevel > 0 ? lowLevel * 2 : 1000;
        const pct = Math.min(100, (total / barMax) * 100);

        let rowClass = '', badge = '', barColor = '#10B981';
        if (total === 0) {
          rowClass = 'row-oos';
          badge = '<span class="badge badge-crit">Out of Stock</span>';
          barColor = '#888';
        } else if (total <= critLevel) {
          rowClass = 'row-crit';
          badge = '<span class="badge badge-crit">Critical</span>';
          barColor = '#EF4444';
        } else if (total <= lowLevel) {
          rowClass = 'row-hot';
          badge = '<span class="badge badge-low">Low</span>';
          barColor = '#F59E0B';
        } else {
          badge = '<span class="badge badge-ok">OK</span>';
        }

        const tagHTML = tag === 'HOT'
          ? '<span class="selling-tag tag-hot">HOT</span>'
          : tag === 'SELLING'
          ? '<span class="selling-tag tag-sell">SELLING</span>'
          : '<span class="selling-tag tag-slow">SLOW</span>';

        // Grey out inactive (discontinued) products
        const nameStyle = p.active ? 'font-weight:600;' : 'font-weight:600;color:#aaa;text-decoration:line-through;';
        const inactiveLabel = p.active ? '' : ' <span style="font-size:10px;color:#aaa;">(inactive)</span>';

        return '<tr class="' + rowClass + '">'
          + '<td><span style="' + nameStyle + '">' + p.name + '</span>' + inactiveLabel + '</td>'
          + '<td>' + tagHTML + '</td>'
          + '<td>' + wh1.toLocaleString() + '</td>'
          + '<td>' + wh2.toLocaleString() + '</td>'
          + '<td>' + wh3.toLocaleString() + '</td>'
          + '<td><strong>' + total.toLocaleString() + '</strong></td>'
          + '<td><div class="stock-bar-wrap"><div class="stock-bar" style="width:' + pct + '%;background:' + barColor + ';"></div></div></td>'
          + '<td>' + badge + '</td>'
          + '</tr>';
      }).join('');

    } catch (err) {
      console.error('Error loading inventory:', err);
      tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:#999;">Connection error</td></tr>';
    }
  }

  // ----------------------------------------------------------------
  // Receive stock — delivery receipt (POST /api/products/delivery)
  // ----------------------------------------------------------------
  function escapeHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');
  }

  function buildDeliveryProductOptionsHtml() {
    const list = appState.cachedProducts || [];
    let html = '<option value="">Select product</option>';
    list.forEach(function (p) {
      html += '<option value="' + p.id + '">' + escapeHtml(p.name) + '</option>';
    });
    return html;
  }

  function addDeliveryLineRow() {
    appState.deliveryLineCounter = (appState.deliveryLineCounter || 0) + 1;
    const n = appState.deliveryLineCounter;
    const container = $('delivery-items-container');
    if (!container) return;
    const opts = buildDeliveryProductOptionsHtml();
    container.insertAdjacentHTML('beforeend',
      '<div class="row align-items-end mb-2 delivery-line" id="delivery-row-' + n + '">'
      + '<div class="col-md-5"><label class="form-label">Product <span class="text-danger">*</span></label>'
      + '<select class="form-select delivery-product" id="delivery-product-' + n + '">' + opts + '</select></div>'
      + '<div class="col-md-2"><label class="form-label">Qty <span class="text-danger">*</span></label>'
      + '<input type="number" class="form-control delivery-qty" id="delivery-qty-' + n + '" min="1" value="1" /></div>'
      + '<div class="col-md-2"><label class="form-label">Warehouse</label>'
      + '<select class="form-select delivery-wh" id="delivery-wh-' + n + '"><option value="wh1">WH1</option><option value="wh2">WH2</option><option value="wh3">WH3</option></select></div>'
      + '<div class="col-md-2 text-end"><label class="form-label">&nbsp;</label>'
      + '<button type="button" class="btn btn-outline-danger btn-sm" onclick="removeDeliveryLine(\'delivery-row-' + n + '\')"><i class="ti ti-trash"></i></button></div>'
      + '</div>');
  }

  window.removeDeliveryLine = function (rowId) {
    const row = $(rowId);
    if (row) row.remove();
  };

  async function initDeliveryForm() {
    await loadProducts();
    if ($('delivery-receipt')) $('delivery-receipt').value = '';
    if ($('delivery-receiver')) $('delivery-receiver').value = '';
    if ($('delivery-verifier')) $('delivery-verifier').value = '';
    if ($('delivery-notes')) $('delivery-notes').value = '';
    const c = $('delivery-items-container');
    if (c) { c.innerHTML = ''; appState.deliveryLineCounter = 0; }
    addDeliveryLineRow();
    const addBtn = $('delivery-add-line');
    if (addBtn) {
      const fresh = addBtn.cloneNode(true);
      addBtn.parentNode.replaceChild(fresh, addBtn);
      fresh.addEventListener('click', addDeliveryLineRow);
    }
  }

  window.clearDeliveryForm = function () {
    initDeliveryForm();
  };

  window.submitDeliveryReceipt = async function () {
    const token = sessionStorage.getItem('rrbm_token');
    if (!token) { showToast('Please login first', 'error'); return; }

    const receipt = (($('delivery-receipt') || {}).value || '').trim();
    if (!/^[A-Za-z0-9]{6,7}$/.test(receipt)) {
      showToast('Receipt # must be 6–7 letters or numbers (no spaces)', 'error');
      return;
    }

    const rows = $$('.delivery-line');
    const items = [];
    for (let i = 0; i < rows.length; i++) {
      const row = rows[i];
      const sel = row.querySelector('.delivery-product');
      const qtyEl = row.querySelector('.delivery-qty');
      const whEl = row.querySelector('.delivery-wh');
      const pid = sel ? parseInt(sel.value, 10) : NaN;
      const qty = qtyEl ? parseInt(qtyEl.value, 10) : 0;
      const warehouse = whEl ? whEl.value : 'wh1';
      if (!sel || !sel.value) { showToast('Select a product on every line', 'error'); return; }
      if (!qty || qty < 1) { showToast('Quantity must be at least 1', 'error'); return; }
      items.push({ productId: pid, quantity: qty, warehouse: warehouse });
    }

    if (items.length === 0) { showToast('Add at least one line item', 'error'); return; }

    const body = {
      receiptNumber: receipt,
      receiverName: (($('delivery-receiver') || {}).value || '').trim() || null,
      verifierName: (($('delivery-verifier') || {}).value || '').trim() || null,
      notes: (($('delivery-notes') || {}).value || '').trim() || null,
      items: items
    };

    try {
      const res = await fetch('http://localhost:8080/api/products/delivery', {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify(body)
      });
      if (!res.ok) {
        let msg = 'Failed to post receipt';
        try { const d = await res.json(); msg = d.message || msg; } catch (e) {}
        showToast(msg, 'error');
        return;
      }
      showToast('Stock updated from receipt ' + receipt, 'success');
      await loadProducts();
      initDeliveryForm();
    } catch (err) {
      console.error('Delivery receipt error:', err);
      showToast('Connection error. Is the backend running?', 'error');
    }
  };

  // ----------------------------------------------------------------
  // Navigation
  // ----------------------------------------------------------------
  window.navigateTo = function (view) {
    $$('.nav-item').forEach((n) => n.classList.toggle('active', n.dataset.view === view));
    $$('.view').forEach((w) => w.classList.remove('active'));
    const target = $('view-' + view);
    if (target) target.classList.add('active');
    const titles = { dash: ['Dashboard', "Today's overview"], new: ['New Order', 'Create order'], list: ['Order List', 'All orders'], inv: ['Inventory', 'Stock levels'], delivery: ['Receive stock', 'Delivery receipt and stock in'], rep: ['Reports', 'Analytics'], emp: ['Employees', 'Team management'], set: ['Settings', 'System configuration'] };
    if (titles[view]) { $('page-title').textContent = titles[view][0]; $('page-subtitle').textContent = titles[view][1]; }
    if (view === 'new') { initOrderForm(); renderOrders(); }
    if (view === 'list') renderOrderList();
    if (view === 'inv') renderInventory();
    if (view === 'delivery') initDeliveryForm();
    if (view === 'rep') setTimeout(initReportCharts, 50);
  };

  // ----------------------------------------------------------------
  // Theme Toggle
  // ----------------------------------------------------------------
  window.toggleTheme = function () {
    appState.theme = appState.theme === 'light' ? 'dark' : 'light';
    document.body.dataset.theme = appState.theme;
    const icon = $('theme-icon');
    if (icon) icon.className = appState.theme === 'light' ? 'ti ti-sun' : 'ti ti-moon';
  };

  // ================================================================
  // NEW ORDER FORM
  // ================================================================
  async function loadProducts() {
    try {
      const res = await fetch('http://localhost:8080/api/products', { headers: { 'Authorization': 'Bearer ' + sessionStorage.getItem('rrbm_token') } });
      if (res.ok) { appState.cachedProducts = await res.json(); console.log('Loaded ' + appState.cachedProducts.length + ' products from backend'); }
      else { appState.cachedProducts = []; }
    } catch (e) { console.error('Error loading products:', e); appState.cachedProducts = []; }
  }

  window.onSourceChange = function () {
    const v = $('field-source').value;
    const agentWrap = $('field-agent-wrap'); if (agentWrap) agentWrap.style.display = v === 'AGENT' ? '' : 'none';
    const fbWrap = $('field-fb-wrap'); if (fbWrap) fbWrap.style.display = v === 'FACEBOOK_PAGE' ? '' : 'none';
    const ecomWrap = $('ecommercePlatformGroup');
    if (ecomWrap) { ecomWrap.style.display = v === 'ECOMMERCE' ? '' : 'none'; if (v !== 'ECOMMERCE' && $('ecommercePlatform')) $('ecommercePlatform').value = ''; }
  };

  async function initOrderForm() {
    await loadProducts();
    const container = $('orderItemsContainer'); if (!container) return;
    container.innerHTML = ''; appState.itemRowCounter = 0; addItemRow();
    const addBtn = $('addItemBtn');
    if (addBtn) { const fresh = addBtn.cloneNode(true); addBtn.parentNode.replaceChild(fresh, addBtn); fresh.addEventListener('click', addItemRow); }
    const disc = $('orderDiscount');
    if (disc) { disc.removeEventListener('input', calculateOrderTotals); disc.addEventListener('input', calculateOrderTotals); }
    calculateOrderTotals();
  }

  function addItemRow() {
    appState.itemRowCounter++;
    const num = appState.itemRowCounter;
    const rowId = 'item-row-' + num;
    const container = $('orderItemsContainer'); if (!container) return;
    container.insertAdjacentHTML('beforeend', '<div class="order-item-row" id="' + rowId + '"><div class="row align-items-end">'
      + '<div class="col-md-4"><label class="form-label">Product <span class="text-danger">*</span></label><div class="product-autocomplete-wrapper"><input type="text" class="form-control product-input" id="productInput-' + num + '" placeholder="Type to search products..." autocomplete="off" required><input type="hidden" class="product-id-hidden" id="productId-' + num + '" value=""><input type="hidden" id="warehouse-' + num + '" value="wh1"><div class="product-dropdown" id="productDropdown-' + num + '"></div></div></div>'
      + '<div class="col-md-2"><label class="form-label">Qty <span class="text-danger">*</span></label><input type="number" class="form-control item-quantity" id="quantity-' + num + '" min="1" value="1" required></div>'
      + '<div class="col-md-2"><label class="form-label">Unit Price (₱) <span class="text-danger">*</span></label><input type="number" class="form-control item-unit-price" id="unitPrice-' + num + '" min="0" step="0.01" value="0" required></div>'
      + '<div class="col-md-2"><label class="form-label">Stock Info</label><div class="stock-info-display" id="stockInfo-' + num + '" style="font-size:11px;padding:6px 8px;background:#f0f0f0;border-radius:6px;min-height:34px;display:flex;align-items:center;color:#666;">Select a product</div></div>'
      + '<div class="col-md-1"><label class="form-label">Subtotal</label><input type="text" class="form-control item-subtotal" id="subtotal-' + num + '" value="₱0.00" readonly style="background-color:#e9ecef;font-size:12px;"></div>'
      + '<div class="col-md-1 text-center"><label class="form-label">&nbsp;</label><button type="button" class="remove-item-btn d-block" onclick="removeItemRow(\'' + rowId + '\')"><i class="ti ti-trash"></i></button></div>'
      + '</div></div>');
    setupProductAutocomplete(num);
    const q = $('quantity-' + num); if (q) q.addEventListener('input', () => calcItemSubtotal(num));
    const p = $('unitPrice-' + num); if (p) p.addEventListener('input', () => calcItemSubtotal(num));
  }

  window.removeItemRow = function (rowId) { const row = $(rowId); if (row) { row.remove(); calculateOrderTotals(); } };

  function setupProductAutocomplete(rowNum) {
    const input = $('productInput-' + rowNum); const dropdown = $('productDropdown-' + rowNum);
    if (!input || !dropdown) return;
    input.addEventListener('input', function () { const t = this.value.toLowerCase().trim(); renderProductDropdown(dropdown, t.length === 0 ? appState.cachedProducts : appState.cachedProducts.filter(p => p.name.toLowerCase().includes(t)), rowNum); });
    input.addEventListener('focus', function () { const t = this.value.toLowerCase().trim(); renderProductDropdown(dropdown, t.length === 0 ? appState.cachedProducts : appState.cachedProducts.filter(p => p.name.toLowerCase().includes(t)), rowNum); });
    document.addEventListener('click', function (e) { if (!input.contains(e.target) && !dropdown.contains(e.target)) dropdown.classList.remove('show'); });
  }

  function renderProductDropdown(dropdown, products, rowNum) {
    if (products.length === 0) { dropdown.innerHTML = '<div class="product-dropdown-item" style="color:#999;cursor:default;">No products found</div>'; dropdown.classList.add('show'); return; }
    let html = '';
    products.forEach(function (product) {
      const wh1 = product.stockWh1 || 0, wh2 = product.stockWh2 || 0, wh3 = product.stockWh3 || 0, total = wh1 + wh2 + wh3;
      let stockClass = 'ok', stockLabel = total.toLocaleString() + ' pcs';
      if (total <= 0) { stockClass = 'critical'; stockLabel = 'Out of stock'; }
      else if (total <= (product.thresholdCritical || 0)) { stockClass = 'critical'; stockLabel = total.toLocaleString() + ' (critical)'; }
      else if (total <= (product.thresholdLow || 0)) { stockClass = 'low'; stockLabel = total.toLocaleString() + ' (low)'; }
      let whParts = []; if (wh1 > 0) whParts.push('WH1:' + wh1.toLocaleString()); if (wh2 > 0) whParts.push('WH2:' + wh2.toLocaleString()); if (wh3 > 0) whParts.push('WH3:' + wh3.toLocaleString());
      let primaryWh = 'wh1'; if (wh2 > wh1 && wh2 >= wh3) primaryWh = 'wh2'; if (wh3 > wh1 && wh3 > wh2) primaryWh = 'wh3';
      html += '<div class="product-dropdown-item" data-id="' + product.id + '" data-name="' + product.name.replace(/"/g, '&quot;') + '" data-price="' + product.unitPrice + '" data-wh="' + primaryWh + '" data-wh1="' + wh1 + '" data-wh2="' + wh2 + '" data-wh3="' + wh3 + '" data-row="' + rowNum + '"><div style="flex:1;"><div class="product-name">' + product.name + '</div><div style="font-size:10px;color:#888;">' + (whParts.join(' · ') || 'No stock') + '</div></div><div style="text-align:right;"><span class="product-price">₱' + parseFloat(product.unitPrice).toFixed(2) + '</span><br><span class="product-stock ' + stockClass + '">' + stockLabel + '</span></div></div>';
    });
    dropdown.innerHTML = html; dropdown.classList.add('show');
    dropdown.querySelectorAll('.product-dropdown-item[data-id]').forEach(function (item) {
      item.addEventListener('click', function () {
        const rn = this.getAttribute('data-row'), wh1 = parseInt(this.getAttribute('data-wh1')) || 0, wh2 = parseInt(this.getAttribute('data-wh2')) || 0, wh3 = parseInt(this.getAttribute('data-wh3')) || 0, pw = this.getAttribute('data-wh');
        $('productInput-' + rn).value = this.getAttribute('data-name');
        $('productId-' + rn).value = this.getAttribute('data-id');
        $('unitPrice-' + rn).value = parseFloat(this.getAttribute('data-price')).toFixed(2);
        $('warehouse-' + rn).value = pw;
        const si = $('stockInfo-' + rn);
        if (si) { let pts = []; if (wh1 > 0) pts.push('<span style="color:' + (pw==='wh1'?'#10B981;font-weight:600':'#666') + '">WH1: ' + wh1.toLocaleString() + '</span>'); if (wh2 > 0) pts.push('<span style="color:' + (pw==='wh2'?'#10B981;font-weight:600':'#666') + '">WH2: ' + wh2.toLocaleString() + '</span>'); if (wh3 > 0) pts.push('<span style="color:' + (pw==='wh3'?'#10B981;font-weight:600':'#666') + '">WH3: ' + wh3.toLocaleString() + '</span>'); si.innerHTML = pts.length > 0 ? '📦 ' + pts.join(' · ') : '<span style="color:#EF4444;">No stock</span>'; }
        dropdown.classList.remove('show'); calcItemSubtotal(rn);
      });
    });
  }

  function calcItemSubtotal(rn) {
    const q = parseFloat(($('quantity-' + rn) || {}).value) || 0, p = parseFloat(($('unitPrice-' + rn) || {}).value) || 0;
    const el = $('subtotal-' + rn); if (el) el.value = '₱' + (q * p).toFixed(2);
    calculateOrderTotals();
  }

  function calculateOrderTotals() {
    let sub = 0; $$('.item-subtotal').forEach(function (i) { sub += parseFloat(i.value.replace('₱', '')) || 0; });
    const disc = parseFloat(($('orderDiscount') || {}).value) || 0;
    if ($('orderSubtotal')) $('orderSubtotal').textContent = '₱' + sub.toFixed(2);
    if ($('orderDiscountAmount')) $('orderDiscountAmount').textContent = '-₱' + disc.toFixed(2);
    if ($('orderTotal')) $('orderTotal').textContent = '₱' + (sub - disc).toFixed(2);
  }

  // ================================================================
  // Submit Order
  // ================================================================
  window.addOrder = async function () {
    const customerName = (($('field-customer') || {}).value || '').trim();
    const source = ($('field-source') || {}).value;
    const agentName = ($('field-agent') || {}).value;
    const fbPage = ($('field-fb') || {}).value;
    const paymentMode = ($('field-payment') || {}).value;
    const discount = parseFloat(($('orderDiscount') || {}).value) || 0;
    const notes = ($('orderNotes') || {}).value || '';

    // Validate required header fields first
    if (!customerName.trim()) { showToast('Please enter customer name', 'error'); return; }
    if (!source) { showToast('Please select order source', 'error'); return; }
    if (!paymentMode) { showToast('Please select payment mode', 'error'); return; }

    let ecommercePlatform = null;
    if (source === 'ECOMMERCE') { ecommercePlatform = ($('ecommercePlatform') || {}).value; if (!ecommercePlatform) { showToast('Please select an e-commerce platform', 'error'); return; } }

    const itemRows = $$('.order-item-row');
    if (itemRows.length === 0) { showToast('Please add at least one item', 'error'); return; }

    const items = []; let hasError = false;
    itemRows.forEach(function (row) {
      if (hasError) return;
      const rn = row.id.replace('item-row-', '');
      const productName = ($('productInput-' + rn) || {}).value || '';
      const productId = ($('productId-' + rn) || {}).value || '';
      const quantity = parseInt(($('quantity-' + rn) || {}).value) || 0;
      const unitPrice = parseFloat(($('unitPrice-' + rn) || {}).value) || 0;
      const warehouse = ($('warehouse-' + rn) || {}).value || 'wh1';
      if (!productName.trim()) { showToast('Please select a product for all items', 'error'); hasError = true; return; }
      if (quantity <= 0) { showToast('Quantity must be at least 1 for ' + productName, 'error'); hasError = true; return; }
      if (unitPrice <= 0) { showToast('Unit price must be greater than 0 for ' + productName, 'error'); hasError = true; return; }
      const item = { productName: productName.trim(), quantity: quantity, unitPrice: unitPrice, warehouse: warehouse };
      if (productId && productId !== '') item.productId = parseInt(productId);
      items.push(item);
    });
    if (hasError) return;

    const orderRequest = { customerName, source, agentName: source === 'AGENT' ? agentName : null, fbPage: source === 'FACEBOOK_PAGE' ? fbPage : null, ecommercePlatform, paymentMode, discount, notes, items };
    console.log('Submitting order:', JSON.stringify(orderRequest, null, 2));

    try {
      const token = sessionStorage.getItem('rrbm_token');
      if (!token) { showToast('Please login first', 'error'); return; }
      const res = await fetch('http://localhost:8080/api/orders', { method: 'POST', headers: authHeaders(), body: JSON.stringify(orderRequest) });
      if (!res.ok) { let msg = 'Failed to create order'; try { const d = await res.json(); msg = d.message || d.error || msg; } catch (e) {} showToast('Error: ' + msg, 'error'); return; }
      const created = await res.json();
      showToast('✅ Order created: ' + created.id, 'success');
      clearOrderForm();
      renderOrders(); // Refresh today's orders from database
    } catch (err) {
      console.error('Error creating order:', err);
      showToast('Connection error. Is the backend running?', 'error');
    }
  };

  function clearOrderForm() {
    if ($('field-customer')) $('field-customer').value = '';
    if ($('field-source')) $('field-source').value = '';
    if ($('field-agent')) $('field-agent').value = '';
    if ($('field-fb')) $('field-fb').value = '';
    if ($('field-payment')) $('field-payment').value = '';
    if ($('orderDiscount')) $('orderDiscount').value = '0';
    if ($('orderNotes')) $('orderNotes').value = '';
    if ($('field-agent-wrap')) $('field-agent-wrap').style.display = 'none';
    if ($('field-fb-wrap')) $('field-fb-wrap').style.display = 'none';
    if ($('ecommercePlatformGroup')) $('ecommercePlatformGroup').style.display = 'none';
    if ($('ecommercePlatform')) $('ecommercePlatform').value = '';
    const c = $('orderItemsContainer'); if (c) { c.innerHTML = ''; appState.itemRowCounter = 0; addItemRow(); }
    calculateOrderTotals();
  }
  window.clearOrderForm = clearOrderForm;

  // ----------------------------------------------------------------
  // Cancel Order (calls backend)
  // ----------------------------------------------------------------
  window.askCancel = function (id) { appState.cancelTargetId = id; $('cancel-order-id').textContent = id; $('cancel-key-input').value = ''; $('modal-cancel').classList.add('open'); };
  window.closeModal = function () { $('modal-cancel').classList.remove('open'); };

  window.confirmCancel = async function () {
    const key = $('cancel-key-input').value;
    if (!key) { showToast('Master key is required', 'error'); return; }
    try {
      const res = await fetch('http://localhost:8080/api/orders/' + appState.cancelTargetId + '/cancel', { method: 'POST', headers: authHeaders(), body: JSON.stringify({ masterKey: key, reason: 'Cancelled by admin' }) });
      if (res.ok) { closeModal(); showToast('Order ' + appState.cancelTargetId + ' cancelled', 'success'); renderOrderList(); renderOrders(); }
      else { const err = await res.json(); showToast('Error: ' + (err.message || 'Failed to cancel'), 'error'); }
    } catch (error) { console.error('Error cancelling:', error); showToast('Connection error', 'error'); }
  };

  // ----------------------------------------------------------------
  // Login / Logout
  // ----------------------------------------------------------------
  window.doLogin = async function () {
    const email = $('login-email').value, password = $('login-password').value;
    if (!email || !password) { showToast('Please enter email and password', 'error'); return; }
    try {
      const res = await fetch('http://localhost:8080/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password }) });
      if (!res.ok) { const err = await res.json(); showToast(err.message || 'Invalid credentials', 'error'); return; }
      const { token, user } = await res.json();
      sessionStorage.setItem('rrbm_token', token); sessionStorage.setItem('rrbm_user', JSON.stringify(user));
      const av = document.querySelector('.avatar'), un = document.querySelector('.user-name');
      if (av && user.fullName) av.textContent = user.fullName.split(' ').map(n => n[0]).join('');
      if (un) un.textContent = user.fullName;
      $('login-screen').style.display = 'none';
      showToast('Welcome, ' + user.fullName + '!', 'success');
    } catch (err) { console.error('Login error:', err); showToast('Connection error. Is the backend running?', 'error'); }
  };

  window.doLogout = function () { sessionStorage.removeItem('rrbm_token'); sessionStorage.removeItem('rrbm_user'); $('login-screen').style.display = 'flex'; showToast('Logged out successfully', 'success'); };

  // ----------------------------------------------------------------
  // Toast Notifications
  // ----------------------------------------------------------------
  window.showToast = function (msg, type) {
    type = type || ''; const id = 'toast-' + (appState.toastId++);
    const el = document.createElement('div'); el.className = 'rrbm-toast ' + type; el.id = id;
    const icon = type === 'success' ? 'check' : type === 'error' ? 'alert-circle' : 'info-circle';
    el.innerHTML = '<i class="ti ti-' + icon + '"></i><span>' + msg + '</span>';
    const c = $('rrbm-toast-container'); if (c) c.appendChild(el);
    setTimeout(() => { const t = $(id); if (t) t.remove(); }, 3000);
  };

  // ----------------------------------------------------------------
  // Live Clock
  // ----------------------------------------------------------------
  function updateClock() { const d = new Date(); const el = $('clock'); if (el) el.textContent = pad(d.getHours(), 2) + ':' + pad(d.getMinutes(), 2) + ':' + pad(d.getSeconds(), 2); }

  // ----------------------------------------------------------------
  // Charts — Dashboard
  // ----------------------------------------------------------------
  function initDashboardCharts() {
    if (typeof Chart === 'undefined') { setTimeout(initDashboardCharts, 100); return; }
    const c1 = $('chart-sales');
    if (c1 && !c1.dataset.init) { c1.dataset.init = '1'; new Chart(c1, { type: 'line', data: { labels: ['Mon','Tue','Wed','Thu','Fri','Sat','Sun'], datasets: [{ label: 'Direct', data: [28000,32000,29000,35000,42000,48000,38000], borderColor: '#D4860A', backgroundColor: 'rgba(212,134,10,0.1)', tension: 0.4, fill: true },{ label: 'E-commerce', data: [8000,12000,10000,14000,18000,22000,15000], borderColor: '#10B981', backgroundColor: 'rgba(16,185,129,0.1)', tension: 0.4, fill: true }] }, options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { position: 'bottom', labels: { boxWidth: 10, font: { size: 11 } } } } } }); }
    const c2 = $('chart-ecommerce');
    if (c2 && !c2.dataset.init) { c2.dataset.init = '1'; new Chart(c2, { type: 'doughnut', data: { labels: ['Shopee','TikTok','Lazada'], datasets: [{ data: [8,4,2], backgroundColor: ['#EE4D2D','#00B37E','#4F46E5'] }] }, options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { position: 'bottom', labels: { boxWidth: 10, font: { size: 11 } } } } } }); }
  }

  // ----------------------------------------------------------------
  // Charts — Reports
  // ----------------------------------------------------------------
  function initReportCharts() {
    if (typeof Chart === 'undefined') { setTimeout(initReportCharts, 100); return; }
    const c3 = $('chart-monthly');
    if (c3 && !c3.dataset.init) { c3.dataset.init = '1'; new Chart(c3, { type: 'bar', data: { labels: ['Jun','Jul','Aug','Sep','Oct','Nov'], datasets: [{ label: 'Revenue (₱K)', data: [850,920,1050,1100,1180,1240], backgroundColor: '#D4860A' }] }, options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { display: false } } } }); }
    const c4 = $('chart-comparison');
    if (c4 && !c4.dataset.init) { c4.dataset.init = '1'; new Chart(c4, { type: 'line', data: { labels: ['Jun','Jul','Aug','Sep','Oct','Nov'], datasets: [{ label: 'Direct', data: [620,680,750,790,830,880], borderColor: '#D4860A', tension: 0.4 },{ label: 'E-commerce', data: [230,240,300,310,350,360], borderColor: '#10B981', tension: 0.4 }] }, options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { position: 'bottom', labels: { boxWidth: 10, font: { size: 11 } } } } } }); }
  }

  // ----------------------------------------------------------------
  // Initialization
  // ----------------------------------------------------------------
  document.addEventListener('DOMContentLoaded', function () {
    setInterval(updateClock, 1000); updateClock(); setTimeout(initDashboardCharts, 100);
  });
})();
