/* ================================================================
   RRBM Packaging Supplies and Trading — Management System
   Main Application Script
   ================================================================ */

(function () {
  'use strict';

  // ── API base URL ──────────────────────────────────────────────────────────
  // Local dev  → '' + API_BASE + ''
  // Docker / production → ''  (nginx proxies /api/* to the backend container)
  // Local dev  (file:// or localhost): backend runs on its own port → use full URL.
  // Production (real hostname via nginx): nginx proxies /api/* → use relative URLs.
  const _h = window.location.hostname;
  const API_BASE = (_h === 'localhost' || _h === '127.0.0.1' || _h === '' || window.location.protocol === 'file:')
    ? 'http://localhost:8080'
    : '';

  const $ = (id) => document.getElementById(id);
  const $$ = (sel) => document.querySelectorAll(sel);

  let appState = {
    theme: 'light',
    cancelTargetId: null,
    codResumeTargetId: null,    // for COD resume password confirmation
    toastId: 0,
    cachedProducts: [],
    itemRowCounter: 0,
    deliveryLineCounter: 0,
    allOrders: [],              // today's orders cache (Order List view)
    inventoryAllProducts: [],   // full product cache for client-side filter
    addProductVerifiedKey: null,
    orderFormReady: false,      // form state persistence
    deliveryFormReady: false,   // form state persistence
    orderHistoryAll: [],        // order history cache for client-side search
    allUsers: [],               // users/employees cache
    refundTargetId: null,       // order id currently in the refund modal
    voidTargetId: null,         // order id currently in the void modal
    dailyClosed: false,         // true when today's daily report is closed (gates void button)
    ivmOrder: null,             // full order object loaded when item-void modal opens
    ivmTier: null,              // 'TIER_1' | 'TIER_2' | null — current void tier
  };

  function pad(n, w) { n = '' + n; while (n.length < w) n = '0' + n; return n; }

  function formatSource(src) {
    const map = {
      'WALK_IN': 'Walk-in', 'AGENT': 'Agent',
      'ECOMMERCE': 'E-Commerce', 'FACEBOOK_PAGE': 'Facebook Page',
      'RESELLER': 'Reseller', 'DISTRIBUTOR': 'Distributor',
    };
    return map[src] || src || '';
  }

  function formatOrderType(t) {
    const map = { 'STANDARD': 'Standard', 'PICK_UP': 'Pick Up', 'COD': 'COD', 'DELIVERY': 'Delivery' };
    return map[t] || t || 'Standard';
  }

  function formatPaymentMode(mode) {
    const map = {
      'CASH':          'Cash',
      'GCASH':         'GCash',
      'PAYMAYA':       'PayMaya',
      'BANK_TRANSFER': 'Bank Transfer',
      'BANK_DEPOSIT':  'Bank Deposit',
      'ONLINE':        'Online',
      'COD':           'COD',
    };
    return map[mode] || mode || '';
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
      'ACTIVE':              { dot: 'dot-active',      label: 'Active' },
      'PENDING':             { dot: 'dot-pending',     label: 'Pending' },
      'DELIVERED':           { dot: 'dot-delivered',   label: 'Delivered' },
      'CANCELLED':           { dot: 'dot-cancelled',   label: 'Cancelled' },
      'CLOSED':              { dot: 'dot-cancelled',   label: 'Closed' },
      'PENDING_COLLECTION':  { dot: 'dot-collection',  label: 'Pending Collection' },
    };
    const info = map[st] || { dot: '', label: st };
    return '<span class="status-dot ' + info.dot + '"></span>' + info.label;
  }

  // Step 10 — navigate to Order History and filter to a specific order ID.
  // Uses a short delay because navigateTo('order-history') calls renderOrderHistory()
  // which is async and resets the search field after its fetch completes.
  window.jumpToOrder = function (orderId) {
    var alreadyThere = document.querySelector('.view.active') &&
                       document.querySelector('.view.active').id === 'view-order-history';
    var apply = function () {
      var si = document.getElementById('history-search');
      if (si) si.value = orderId;
      if (typeof window.filterOrderHistory === 'function') window.filterOrderHistory();
    };
    if (alreadyThere) {
      // Data already loaded — apply filter immediately; no re-fetch
      apply();
    } else {
      // navigateTo triggers an async re-fetch; wait for it to settle before filtering
      navigateTo('order-history');
      setTimeout(apply, 1500);
    }
  };

  // Step 10 — returns the full status-cell HTML for an order row, including
  // supplementary badges for voided / replacement states
  function orderStatusCell(o) {
    var base = statusBadge(o.status);

    // Fully voided (Tier 2) — amber dot + "Fully Voided" overrides standard Cancelled badge
    if (o.cancellationType === 'VOIDED') {
      return '<span class="status-dot dot-pending"></span>Fully Voided';
    }

    // Cancelled for replacement — keep Cancelled badge, add replacement sub-line
    if (o.cancellationType === 'REPLACEMENT') {
      var replSub = o.replacementOrderId
        ? '<div style="font-size:10px;color:var(--accent-success);margin-top:2px;line-height:1.3;">Replaced by '
            + '<a href="#" onclick="event.preventDefault();jumpToOrder(\'' + o.replacementOrderId + '\')" '
            + 'style="color:var(--accent-success);font-weight:600;">' + o.replacementOrderId + '</a></div>'
        : '<div style="font-size:10px;color:var(--text-muted);margin-top:2px;line-height:1.3;">Replacement pending</div>';
      return base + replSub;
    }

    // Replacement order — keep normal status badge, add purple "Replacement for" sub-line
    if (o.originalOrderId) {
      return base + '<div style="font-size:10px;color:var(--accent-info);margin-top:2px;line-height:1.3;">Replacement for '
        + '<a href="#" onclick="event.preventDefault();jumpToOrder(\'' + o.originalOrderId + '\')" '
        + 'style="color:var(--accent-info);font-weight:600;">' + o.originalOrderId + '</a></div>';
    }

    // Partially voided — keep normal status badge, add amber sub-line
    if (Number(o.voidedAmount || 0) > 0 && o.status !== 'CANCELLED') {
      return base + '<div style="font-size:10px;color:var(--accent-warn);margin-top:2px;line-height:1.3;">⚠ Partial void</div>';
    }

    // Standard — no change
    return base;
  }

  /**
   * For ECOMMERCE orders, extract the ecommerce order number(s) from the notes field.
   * Notes format: "Order No: 260525ANS47VTC | tracking via courier"
   *           or: "Orders: 260525ANS47VTC, 260525B5X8BM7Q | ..."  (grouped)
   * Returns the reference string (e.g. "260525ANS47VTC") or null for non-ecom orders.
   */
  function ecomOrderRef(o) {
    if (o.source !== 'ECOMMERCE' || !o.notes) return null;
    var m = (o.notes || '').match(/^(?:Orders?|Order No):\s*([^|]+)/i);
    if (!m) return null;
    var ref = m[1].trim();
    return ref || null;
  }

  // True for roles that can issue refunds / voids
  function canManageOrders() {
    const u = JSON.parse(localStorage.getItem('rrbm_user') || '{}');
    return ['SUPER_ADMIN', 'ADMINISTRATOR', 'ADMIN', 'ACCOUNTING'].includes(u.role);
  }

  function roleBadge(role) {
    const map = {
      'SUPER_ADMIN':    { cls: 'badge-honey', label: 'Super Admin' },
      'ADMIN':          { cls: 'badge-ok',    label: 'Admin' },
      'ADMINISTRATOR':  { cls: 'badge-ok',    label: 'Administrator' },
      'ACCOUNTING':     { cls: 'badge-ok',    label: 'Accounting' },
      'STAFF':          { cls: 'badge-low',   label: 'Staff' },
      'STANDARD_USER':  { cls: 'badge-low',   label: 'Standard User' },
    };
    const info = map[role] || { cls: '', label: role };
    return '<span class="badge ' + info.cls + '">' + info.label + '</span>';
  }

  function authHeaders() {
    return { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + localStorage.getItem('rrbm_token') };
  }

  function currentUserName() {
    try { return JSON.parse(localStorage.getItem('rrbm_user') || '{}').fullName || 'Admin'; }
    catch (e) { return 'Admin'; }
  }
  function currentUserRole() {
    try { return JSON.parse(localStorage.getItem('rrbm_user') || '{}').role || ''; }
    catch (e) { return ''; }
  }
  function currentUserEmail() {
    try { return JSON.parse(localStorage.getItem('rrbm_user') || '{}').email || ''; }
    catch (e) { return ''; }
  }
  function currentUserId() {
    try { return JSON.parse(localStorage.getItem('rrbm_user') || '{}').id || null; }
    catch (e) { return null; }
  }

  function escapeHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');
  }

  function isSuperAdmin() { return currentUserRole() === 'SUPER_ADMIN'; }

  /** True while the logged-in user must change their password before continuing. */
  var _forcedPasswordChange = false;

  /** Show/hide the force-change checkbox wrapper in Edit Employee when password is typed. */
  window.toggleForceChangeWrap = function (inp) {
    var wrap = $('edit-emp-force-change-wrap');
    if (wrap) wrap.style.display = inp.value ? '' : 'none';
    // Also clear confirm field when password is cleared
    if (!inp.value) {
      var conf = $('edit-emp-password-confirm');
      if (conf) conf.value = '';
    }
  };

  /** Toggle a password-type input between hidden and revealed; swap eye icon. */
  window.toggleCredVisibility = function (inputId, btn) {
    var inp = $(inputId); if (!inp) return;
    var revealing = inp.type === 'password';
    inp.type = revealing ? 'text' : 'password';
    var icon = btn ? btn.querySelector('i') : null;
    if (icon) icon.className = revealing ? 'ti ti-eye-off' : 'ti ti-eye';
  };

  function canManageEmployees() {
    const r = currentUserRole();
    return r === 'SUPER_ADMIN' || r === 'ADMINISTRATOR';
  }

  function canEditInventory() {
    const r = currentUserRole();
    if (r === 'SUPER_ADMIN' || r === 'ADMINISTRATOR') return true;
    if (r === 'ADMIN') {
      const pages = getAllowedPages();
      return pages === null || pages.includes('inventory');
    }
    return false;
  }

  // ================================================================
  // MODAL HELPERS
  // ================================================================
  /** Clear all cached app state between user sessions to prevent data leakage. */
  function _clearSessionState() {
    appState.allOrders = [];
    appState.cachedProducts = [];
    appState.inventoryAllProducts = [];
    appState.orderHistoryAll = [];
    appState.itemRowCounter = 0;
    _collectionsParsed = []; // N-5: was never cleared; next user briefly saw previous session's collections
    // Clear stale rendered table bodies
    ['list-tbody', 'order-history-tbody', 'collections-tbody',
     'inventory-tbody', 'activity-log-tbody', 'rep-top-products-tbody',
     'rep-daily-tbody', 'acc-daily-tbody'].forEach(function (id) {
      var el = $(id); if (el) el.innerHTML = '';
    });
    // Hide any closed-day banner
    var banner = $('daily-closed-banner');
    if (banner) banner.style.display = 'none';
  }

  window.openModal = function (id) {
    var el = $(id);
    if (el) el.classList.add('open');
  };

  window.closeModal = function (id) {
    // Block dismissal of the Change Password modal when a forced change is pending
    if (id === 'modal-change-password' && _forcedPasswordChange) return;
    const el = $(id);
    if (el) el.classList.remove('open');
    // Clear sensitive key fields on close
    var secFields = ['cancel-key-input',
                     'rtn-security-key',
                     'add-emp-security-key', 'add-emp-security-key-confirm',
                     'edit-emp-security-key', 'edit-emp-security-key-confirm',
                     'payable-paid-key', 'delete-emp-key',
                     'cp-current-pw', 'cp-new-pw', 'cp-confirm-pw',
                     'ivm-security-key', 'ivm-master-key'];
    secFields.forEach(function (fid) { var f = $(fid); if (f) f.value = ''; });
  };

  // ================================================================
  // LOGOUT CONFIRMATION
  // ================================================================
  window.askLogout = function () {
    $('modal-logout').classList.add('open');
  };

  window._doLogout = function () {
    closeModal('modal-logout');
    // N-9: fire-and-forget logout log — must read token before clearing localStorage
    var _logoutToken = localStorage.getItem('rrbm_token');
    if (_logoutToken) {
      fetch(API_BASE + '/api/auth/logout', {
        method: 'POST',
        headers: { 'Authorization': 'Bearer ' + _logoutToken }
      }).catch(function () {}); // ignore network errors — don't block logout
    }
    localStorage.removeItem('rrbm_token');
    localStorage.removeItem('rrbm_user');
    appState.orderFormReady = false;
    appState.deliveryFormReady = false;
    // Hide role-restricted UI
    applyRoleRestrictions(null);
    $('login-screen').style.display = 'flex';
    showToast('Logged out successfully', 'success');
  };

  // ================================================================
  // ROLE-BASED UI RESTRICTIONS + PAGE ACCESS CONTROL
  // ================================================================

  // Map view ID → page key used in allowedPages JSON array
  function viewToPageKey(view) {
    const map = {
      'new': 'orders', 'list': 'orders', 'order-history': 'order-history',
      'daily-reports': 'daily-reports',
      'inv': 'inventory',
      'delivery': 'receive-stocks', 'rejected-items': 'rejected-items',
      'purchase-orders': 'purchase-orders',
      'rep': 'reports',
      'delivery-rep': 'delivery-reports',
      'activity-log': 'activity-log',
      'emp': 'employees',
      'expenses': 'expenses',
      'payables': 'payables',
      'suppliers': 'suppliers',
    };
    return map[view] || null; // null = no restriction (dash, set)
  }

  function getAllowedPages() {
    try {
      const u = JSON.parse(localStorage.getItem('rrbm_user') || '{}');
      if (!u.allowedPages) return null; // null = unrestricted
      return JSON.parse(u.allowedPages);
    } catch (e) { return null; }
  }

  function canAccessPage(view) {
    if (currentUserRole() === 'SUPER_ADMIN') return true;
    const key = viewToPageKey(view);
    if (!key) return true; // dashboard, settings — not page-restricted
    const pages = getAllowedPages();
    if (pages === null) return true; // null = unrestricted
    return pages.includes(key);
  }

  function applyRoleRestrictions(role) {
    const isSuper   = role === 'SUPER_ADMIN';
    const canManage = role === 'SUPER_ADMIN' || role === 'ADMINISTRATOR';
    // Settings nav — Super Admin only
    const navSet = $('nav-set');
    if (navSet) navSet.style.display = isSuper ? '' : 'none';
    // Employee List nav — Super Admin and Administrator only
    const navEmp = $('nav-emp');
    if (navEmp) navEmp.style.display = canManage ? '' : 'none';
    // Add Employee button — managers only
    const btnAddEmp = $('btn-add-employee');
    if (btnAddEmp) btnAddEmp.style.display = canManage ? '' : 'none';
    // Employees actions column header — managers only
    const empActHdr = $('emp-actions-header');
    if (empActHdr) empActHdr.style.display = canManage ? '' : 'none';

    // Page-based nav hiding (applied AFTER role restrictions)
    if (role) {
      applyPageAccessToNav();
    }
  }

  function applyPageAccessToNav() {
    if (currentUserRole() === 'SUPER_ADMIN') return; // Super Admin sees all nav
    const pages = getAllowedPages();
    if (pages === null) return; // unrestricted
    // Hide nav buttons whose page key is not in allowedPages
    document.querySelectorAll('.nav-item[data-view]').forEach(function (btn) {
      const view = btn.getAttribute('data-view');
      const key  = viewToPageKey(view);
      if (key && !pages.includes(key)) {
        btn.style.display = 'none';
      } else if (key) {
        btn.style.display = ''; // restore if previously hidden
      }
    });
  }

  // ================================================================
  // Render: Today's Orders (New Order view summary table)
  // ================================================================
  async function renderOrders() {
    const tb = $('orders-tbody');
    if (!tb) return;
    const token = localStorage.getItem('rrbm_token');
    if (!token) { tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:var(--text-muted);">Please login first</td></tr>'; return; }

    try {
      const res = await fetch('' + API_BASE + '/api/orders/today', { headers: { 'Authorization': 'Bearer ' + token } });
      if (!res.ok) { tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:var(--text-muted);">Failed to load orders</td></tr>'; return; }
      const orders = await res.json();
      if (orders.length === 0) { tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:var(--text-muted);">No orders today yet</td></tr>'; return; }

      tb.innerHTML = orders.map(function (o) {
        const first = o.items && o.items[0];
        const itemText = first ? first.productName + (o.items.length > 1 ? ' +' + (o.items.length - 1) + ' more' : '') : '-';
        const totalQty = o.items ? o.items.reduce(function (s, i) { return s + i.quantity; }, 0) : 0;
        const ecomRef  = ecomOrderRef(o);
        const displayId = ecomRef || o.id;
        return '<tr>'
          + '<td><code style="font-size:11px;" title="' + (ecomRef ? 'System ID: ' + o.id : '') + '">' + displayId + '</code></td>'
          + '<td>' + o.customerName + '</td>'
          + '<td>' + itemText + '</td>'
          + '<td>' + totalQty + '</td>'
          + '<td>₱' + Number(o.total).toLocaleString() + '</td>'
          + '<td>' + formatSource(o.source) + '</td>'
          + '<td>' + formatPaymentMode(o.paymentMode) + '</td>'
          + '<td>' + statusBadge(o.status) + '</td>'
          + '</tr>';
      }).join('');
    } catch (err) {
      tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:var(--text-muted);">Connection error</td></tr>';
    }
  }

  // ================================================================
  // ORDER LIST — Today Only
  // ================================================================
  async function renderOrderList() {
    const tb = $('list-tbody');
    if (!tb) return;
    const token = localStorage.getItem('rrbm_token');
    if (!token) { tb.innerHTML = '<tr><td colspan="9" style="text-align:center;color:var(--text-muted);">Please login first</td></tr>'; return; }

    tb.innerHTML = '<tr><td colspan="9" style="text-align:center;color:var(--text-muted);">Loading…</td></tr>';

    try {
      const res = await fetch('' + API_BASE + '/api/orders/today', { headers: { 'Authorization': 'Bearer ' + token } });
      if (!res.ok) { tb.innerHTML = '<tr><td colspan="9" style="text-align:center;color:var(--text-muted);">Failed to load orders</td></tr>'; return; }
      appState.allOrders = await res.json();
      const search = $('order-search');
      if (search) search.value = '';
      if ($('order-source-filter')) $('order-source-filter').value = '';
    } catch (err) {
      tb.innerHTML = '<tr><td colspan="9" style="text-align:center;color:var(--text-muted);">Connection error</td></tr>';
      return;
    }

    // Check daily-close status BEFORE rendering rows — appState.dailyClosed must be current
    // when void button visibility is calculated in renderOrderRows().
    await _checkDailyClosedBanner();
    renderOrderRows(appState.allOrders);
  }

  /** Show/hide the closed-day banner in the order list.
   *  Also stores appState.dailyClosed so renderOrderRows() can gate the void button.
   *  Must be awaited before renderOrderRows() to guarantee a fresh value each render cycle. */
  async function _checkDailyClosedBanner() {
    var banner = $('daily-closed-banner');
    try {
      var res = await fetch(API_BASE + '/api/reports/daily-status', { headers: authHeaders() });
      if (!res.ok) {
        appState.dailyClosed = false;
        if (banner) banner.style.display = 'none';
        return;
      }
      var data = await res.json();
      appState.dailyClosed = !!data.closed;
      if (data.closed) {
        var today = new Date().toLocaleDateString('en-PH', {year:'numeric', month:'long', day:'numeric'});
        var lbl = $('closed-date-label');
        if (lbl) lbl.textContent = today;
        var byLbl = $('closed-by-label');
        if (byLbl && data.report) {
          var at = data.report.closedAt ? formatTime(data.report.closedAt) : '';
          // N-1: show who closed it using the name the backend now provides
          var closedByName = data.closedByName || '';
          byLbl.textContent = 'Closed' + (at ? ' at ' + at : '') + (closedByName ? ' by ' + closedByName : '');
        }
        if (banner) banner.style.display = 'flex';
      } else {
        if (banner) banner.style.display = 'none';
      }
    } catch (e) {
      appState.dailyClosed = false;
      if (banner) banner.style.display = 'none';
    }
  }

  function renderOrderRows(orders) {
    const tb = $('list-tbody');
    if (!tb) return;
    if (orders.length === 0) {
      tb.innerHTML = '<tr><td colspan="9" style="text-align:center;color:var(--text-muted);padding:24px;">No results found</td></tr>';
      return;
    }

    tb.innerHTML = orders.map(function (o) {
      const activeItems = (o.items || []).filter(function(it) { return (it.quantity - (it.voidedQuantity || 0)) > 0; });
      const first = activeItems[0];
      const leadQty = first ? (first.quantity - (first.voidedQuantity || 0)) : 0;
      const itemText = first
        ? first.productName + ' x' + leadQty + (activeItems.length > 1 ? ' +' + (activeItems.length - 1) + ' more' : '')
        : ((o.items && o.items.length > 0) ? '<span style="color:var(--text-muted);font-size:11px;">(all voided)</span>' : '-');
      const safeId = o.id.replace(/'/g, "\\'");

      // Print receipt button always available
      const printBtn = '<button class="btn btn-secondary btn-sm" onclick="printOrderReceipt(\'' + safeId + '\')" title="Print Receipt"><i class="ti ti-printer"></i></button>';
      const viewBtn  = ' <button class="btn btn-secondary btn-sm" onclick="openOrderDetail(\'' + safeId + '\')" title="View Details"><i class="ti ti-eye"></i></button>';

      // New item-level void button — shown on non-CANCELLED today's orders when day is not closed
      const ivmBtn = (canManageOrders() && !appState.dailyClosed)
        ? ' <button class="btn btn-sm" onclick="openItemVoidModal(\'' + safeId + '\')" title="Void Items" style="background:#F97316;color:#fff;"><i class="ti ti-scissors"></i></button>'
        : '';

      let actions = '';
      if (o.status === 'ACTIVE') {
        actions = '<button class="btn btn-success btn-sm" onclick="updateOrderStatus(\'' + safeId + '\', \'DELIVERED\')" title="Mark Delivered"><i class="ti ti-truck-delivery"></i></button>'
          + '<button class="btn btn-warning btn-sm" onclick="updateOrderStatus(\'' + safeId + '\', \'PENDING\')" title="Put on Hold"><i class="ti ti-clock-pause"></i></button>'
          + ivmBtn
          + '<button class="btn btn-danger btn-sm" onclick="askCancel(\'' + safeId + '\')" title="Cancel"><i class="ti ti-x"></i></button>';
      } else if (o.status === 'PENDING') {
        // All pending orders require password to resume — action is logged for audit
        actions = '<button class="btn btn-primary btn-sm" onclick="askCodResume(\'' + safeId + '\')" title="Resume (password required)"><i class="ti ti-player-play"></i><i class="ti ti-lock" style="font-size:9px;margin-left:2px;"></i></button>'
          + ivmBtn
          + '<button class="btn btn-danger btn-sm" onclick="askCancel(\'' + safeId + '\')" title="Cancel"><i class="ti ti-x"></i></button>';
      } else if (o.status === 'DELIVERED') {
        const safeTotal = Number(o.total || 0).toFixed(2);
        actions = '<span style="color:var(--accent-success);font-size:12px;"><i class="ti ti-check"></i> Done</span>';
        if (canManageOrders()) {
          actions += ivmBtn;
          actions += ' <button class="btn btn-sm" onclick="openReturnModal(\'' + safeId + '\')" title="Process Return" style="background:#F59E0B;color:#fff;margin-left:4px;"><i class="ti ti-arrow-back-up"></i></button>';
          actions += ' <button class="btn btn-danger btn-sm" onclick="askCancel(\'' + safeId + '\')" title="Cancel" style="margin-left:4px;"><i class="ti ti-x"></i></button>';
        }
      } else if (o.status === 'CANCELLED') {
        if (canManageOrders() && o.cancellationType === 'REPLACEMENT' && !o.replacementOrderId) {
          actions = '<button class="btn btn-sm" onclick="openReplacementForm(\'' + safeId + '\')" title="Create Replacement Order" style="background:#10B981;color:#fff;"><i class="ti ti-replace"></i> Create Replacement</button>';
        } else if (o.cancellationType === 'REPLACEMENT' && o.replacementOrderId) {
          actions = '<span style="color:var(--accent-success);font-size:12px;font-weight:600;"><i class="ti ti-check"></i> Replaced</span>';
        } else {
          actions = '<span style="color:var(--text-muted);font-size:12px;">Cancelled</span>';
        }
      } else if (o.status === 'PENDING_COLLECTION') {
        actions = '<span style="color:var(--accent-info);font-size:12px;font-weight:600;"><i class="ti ti-clock-dollar"></i> Pending Collection</span>';
      }

      let srcDisplay = formatSource(o.source);
      if (o.source === 'AGENT' && o.agentName) srcDisplay += ' <span style="color:var(--text-secondary);font-size:11px;">(' + o.agentName + ')</span>';
      if ((o.source === 'RESELLER' || o.source === 'DISTRIBUTOR') && o.agentName) srcDisplay += ' <span style="color:var(--text-secondary);font-size:11px;">(' + o.agentName + ')</span>';
      if (o.source === 'ECOMMERCE' && o.ecommercePlatform) {
        var _platMap = { SHOPEE: 'Shopee', TIKTOK: 'TikTok', LAZADA: 'Lazada' };
        var _platLabel = _platMap[o.ecommercePlatform] || (o.ecommercePlatform.charAt(0) + o.ecommercePlatform.slice(1).toLowerCase());
        srcDisplay += ' <span style="color:var(--text-secondary);font-size:11px;">/ ' + _platLabel + '</span>';
      }
      if (o.source === 'FACEBOOK_PAGE' && o.fbPage) srcDisplay += ' <span style="color:var(--text-secondary);font-size:11px;">(' + o.fbPage + ')</span>';

      const payBadge = o.paymentMode === 'COD'
        ? '<span style="color:var(--accent-info);font-weight:600;font-size:11px;">COD</span>'
        : formatPaymentMode(o.paymentMode);

      const cancelNote = (o.status === 'CANCELLED' && o.cancellationReason)
        ? '<div style="font-size:10px;color:var(--accent-danger);margin-top:3px;white-space:normal;max-width:130px;line-height:1.3;">'
            + escapeHtml(o.cancellationReason) + '</div>'
        : '';
      const cancelMeta = (o.status === 'CANCELLED' && o.cancelledByName)
        ? '<div style="font-size:10px;color:var(--text-muted);line-height:1.3;">by ' + escapeHtml(o.cancelledByName)
            + (o.cancelledAt ? ' &middot; ' + new Date(o.cancelledAt).toLocaleString('en-PH',{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'}) : '')
            + '</div>'
        : '';

      const ecomRef2  = ecomOrderRef(o);
      const displayId2 = ecomRef2 || o.id;
      const _vAmt2 = Number(o.voidedAmount || 0);
      const _eff2  = Number(o.total || 0) - _vAmt2;
      const _totalCell2 = _vAmt2 > 0
        ? '₱' + _eff2.toLocaleString()
          + '<div style="font-size:10px;color:var(--text-muted);text-decoration:line-through;line-height:1.2;">₱' + Number(o.total || 0).toLocaleString() + '</div>'
        : '₱' + Number(o.total || 0).toLocaleString();
      return '<tr>'
        + '<td><code style="font-size:11px;" title="' + (ecomRef2 ? 'System ID: ' + o.id : '') + '">' + displayId2 + '</code></td>'
        + '<td>' + formatDate(o.createdAt) + '</td>'
        + '<td>' + o.customerName + '</td>'
        + '<td style="font-size:12px;">' + itemText + '</td>'
        + '<td>' + _totalCell2 + '</td>'
        + '<td>' + srcDisplay + '</td>'
        + '<td>' + payBadge + '</td>'
        + '<td>' + orderStatusCell(o) + cancelNote + cancelMeta + '</td>'
        + '<td><div class="d-flex gap-1">' + printBtn + viewBtn + actions + '</div></td>'
        + '</tr>';
    }).join('');
  }

  window.filterOrderList = function () {
    const src = (($('order-source-filter') || {}).value || '').trim();
    const q   = (($('order-search')        || {}).value || '').toLowerCase().trim();
    const ecomSubs = ['SHOPEE', 'TIKTOK', 'LAZADA'];
    let filtered = appState.allOrders || [];

    if (src) {
      filtered = filtered.filter(function (o) {
        if (ecomSubs.includes(src)) {
          return (o.source || '') === 'ECOMMERCE' && (o.ecommercePlatform || '').toUpperCase() === src;
        }
        return (o.source || '') === src;
      });
    }

    if (q) {
      filtered = filtered.filter(function (o) {
        return (o.id || '').toLowerCase().includes(q) ||
               (ecomOrderRef(o) || '').toLowerCase().includes(q) ||
               (o.customerName || '').toLowerCase().includes(q) ||
               (o.agentName || '').toLowerCase().includes(q);
      });
    }

    renderOrderRows(filtered);
  };

  window.updateOrderStatus = async function (orderId, newStatus, securityKey) {
    try {
      const bodyPayload = { status: newStatus };
      if (securityKey) bodyPayload.securityKey = securityKey;
      const res = await fetch('' + API_BASE + '/api/orders/' + orderId + '/status', {
        method: 'PUT', headers: authHeaders(), body: JSON.stringify(bodyPayload)
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
      showToast('Connection error', 'error');
    }
  };

  // ================================================================
  // COD RESUME — admin password confirmation
  // ================================================================
  window.askCodResume = function (orderId) {
    appState.codResumeTargetId = orderId;
    $('cod-resume-order-id').textContent = orderId;
    $('cod-resume-password').value = '';
    $('modal-cod-resume').classList.add('open');
  };

  window.confirmCodResume = async function () {
    const key = ($('cod-resume-password') || {}).value || '';
    if (!key) { showToast('Admin security key is required', 'error'); return; }

    const btn = document.querySelector('#modal-cod-resume .btn-primary');
    if (btn) { btn.disabled = true; btn.textContent = 'Resuming…'; }

    try {
      closeModal('modal-cod-resume');
      await updateOrderStatus(appState.codResumeTargetId, 'ACTIVE', key);
    } catch (err) {
      showToast('Connection error', 'error');
    } finally {
      if (btn) { btn.disabled = false; btn.innerHTML = '<i class="ti ti-player-play"></i> Confirm &amp; Resume'; }
    }
  };

  // ================================================================
  // PRINT RECEIPT — PDF / print for a single order
  // ================================================================
  window.printOrderReceipt = function (orderId) {
    // Try to find the order in either the today cache or history cache
    const order = appState.allOrders.find(function (o) { return o.id === orderId; })
               || appState.orderHistoryAll.find(function (o) { return o.id === orderId; });
    if (!order) { showToast('Order data not available — try refreshing the list', 'error'); return; }

    // For ecommerce orders use the platform order number; fall back to system ID for all others
    const receiptRef = ecomOrderRef(order) || order.id;

    const itemRows = (order.items || []).map(function (item) {
      const sub = Number(item.subtotal || 0).toFixed(2);
      return '<tr>'
        + '<td style="padding:8px 10px;border-bottom:1px solid #f0e8d0;">' + escapeHtml(item.productName) + '</td>'
        + '<td style="padding:8px 10px;border-bottom:1px solid #f0e8d0;text-align:center;">' + item.quantity + '</td>'
        + '<td style="padding:8px 10px;border-bottom:1px solid #f0e8d0;text-align:right;">₱' + Number(item.unitPrice || 0).toFixed(2) + '</td>'
        + '<td style="padding:8px 10px;border-bottom:1px solid #f0e8d0;text-align:right;">₱' + sub + '</td>'
        + '</tr>';
    }).join('');

    const voidedAmt    = Number(order.voidedAmount  || 0);
    const subtotal     = Number(order.subtotal     || 0).toFixed(2);
    const discount     = Number(order.discount     || 0).toFixed(2);
    const deliveryFee  = Number(order.deliveryFee  || 0);
    const total        = (Number(order.total       || 0) - voidedAmt).toFixed(2);
    const payMode  = order.paymentMode || 'CASH';
    const orderType = formatOrderType(order.orderType || 'STANDARD');
    const address  = order.address ? ('<div style="margin-top:4px;font-size:12px;color:#555;">📍 ' + escapeHtml(order.address) + '</div>') : '';
    const preparedBy = currentUserName();

    const w = window.open('', '_blank', 'width=680,height=880');
    if (!w) { showToast('Pop-up blocked — allow pop-ups and try again', 'error'); return; }

    var payDisplay = (payMode || 'CASH')
      .replace('BANK_TRANSFER', 'Bank Transfer')
      .replace('GCASH', 'GCash')
      .replace('PAYMAYA', 'PayMaya')
      .replace('CASH', 'Cash')
      .replace('COD', 'Cash on Delivery');

    var srcDisplay = '';
    if (order.source === 'WALK_IN')        srcDisplay = 'Walk-in';
    else if (order.source === 'AGENT')     srcDisplay = 'Agent';
    else if (order.source === 'RESELLER')  srcDisplay = 'Reseller' + (order.agentName ? ' (' + order.agentName + ')' : '');
    else if (order.source === 'ECOMMERCE') srcDisplay = order.ecommercePlatform
      ? order.ecommercePlatform.charAt(0) + order.ecommercePlatform.slice(1).toLowerCase()
      : 'E-commerce';
    else if (order.source === 'FACEBOOK_PAGE') srcDisplay = 'Facebook Page';
    else srcDisplay = order.source || '';

    w.document.write('<!DOCTYPE html><html><head>'
      + '<meta charset="UTF-8">'
      + '<title>Receipt — ' + receiptRef + '</title>'
      + '<style>'
      + '*{box-sizing:border-box;margin:0;padding:0;}'
      + 'body{font-family:Arial,sans-serif;font-size:13px;color:#1a1a1a;background:#fff;padding:28px;}'
      + '.rc{max-width:560px;margin:0 auto;background:#fff;border:1px solid #ddd;}'
      + '.rc-head{padding:22px 24px 16px;border-bottom:3px solid #D4860A;display:flex;justify-content:space-between;align-items:center;}'
      + '.rc-logo{height:52px;object-fit:contain;}'
      + '.rc-head-right{text-align:right;font-size:11px;color:#555;line-height:1.7;}'
      + '.rc-title-row{display:flex;justify-content:space-between;align-items:center;padding:9px 24px;background:#f7f7f7;border-bottom:1px solid #e8e8e8;}'
      + '.rc-title{font-size:11px;font-weight:700;letter-spacing:.13em;color:#2C1A0E;text-transform:uppercase;}'
      + '.rc-order-no{font-size:12px;font-weight:700;color:#2C1A0E;font-family:monospace;}'
      + '.rc-meta{display:grid;grid-template-columns:1fr 1fr;border-bottom:1px solid #ebebeb;}'
      + '.rc-mc{padding:10px 16px;}'
      + '.rc-mc.br{border-right:1px solid #ebebeb;}'
      + '.rc-mc.bt{border-top:1px solid #ebebeb;}'
      + '.rc-ml{font-size:9px;font-weight:700;color:#999;text-transform:uppercase;letter-spacing:.08em;margin-bottom:3px;}'
      + '.rc-mv{font-size:13px;color:#1a1a1a;}'
      + '.rc-cust{padding:11px 16px;border-bottom:1px solid #ebebeb;}'
      + '.rc-cl{font-size:9px;font-weight:700;color:#999;text-transform:uppercase;letter-spacing:.08em;margin-bottom:3px;}'
      + '.rc-cn{font-size:14px;font-weight:700;color:#1a1a1a;}'
      + '.rc-ca{font-size:12px;color:#555;margin-top:2px;}'
      + 'table{width:100%;border-collapse:collapse;}'
      + 'thead tr{background:#2C1A0E;}'
      + 'thead th{padding:8px 14px;font-size:10px;font-weight:700;letter-spacing:.07em;text-transform:uppercase;color:#FAF7F2;text-align:left;}'
      + 'thead th.r{text-align:right;}'
      + 'tbody tr{border-bottom:1px solid #f2f2f2;}'
      + 'tbody tr:last-child{border-bottom:none;}'
      + 'tbody td{padding:9px 14px;font-size:13px;color:#1a1a1a;}'
      + 'tbody td.r{text-align:right;}'
      + 'tbody td.m{color:#666;}'
      + 'tbody td.b{font-weight:700;}'
      + '.rc-totals{padding:9px 16px;background:#fafafa;border-top:1px solid #ebebeb;border-bottom:1px solid #ebebeb;}'
      + '.rc-trow{display:flex;justify-content:space-between;font-size:12px;padding:2px 0;color:#666;}'
      + '.rc-grand{display:flex;justify-content:space-between;align-items:center;padding:12px 16px;border-top:3px solid #D4860A;}'
      + '.rc-grand-lbl{font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.09em;color:#1a1a1a;}'
      + '.rc-grand-val{font-size:20px;font-weight:700;color:#2C1A0E;}'
      + '.rc-sigs{display:flex;gap:32px;padding:28px 20px 18px;border-top:1px solid #ebebeb;}'
      + '.rc-sig{flex:1;text-align:center;}'
      + '.rc-sig-line{border-top:1px solid #1a1a1a;padding-top:5px;margin-top:40px;}'
      + '.rc-sig-name{font-size:12px;font-weight:700;color:#1a1a1a;}'
      + '.rc-sig-role{font-size:11px;color:#888;}'
      + '.rc-foot{padding:9px 16px;border-top:1px solid #ebebeb;background:#fafafa;display:flex;justify-content:space-between;align-items:center;}'
      + '.rc-foot-txt{font-size:10px;color:#aaa;}'
      + '.rc-foot-stamp{font-size:9px;font-weight:700;letter-spacing:.09em;text-transform:uppercase;color:#D4860A;border:1.5px solid #D4860A;padding:2px 8px;}'
      + '@media print{body{padding:0;}.rc{border:none;box-shadow:none;}}'
      + '</style></head><body><div class="rc">'

      // Header — logo left, address right
      + '<div class="rc-head">'
      + '<img class="rc-logo" src="assets/logo-two.png" alt="RRBM Packaging Supplies" onerror="this.style.display=\'none\'">'
      + '<div class="rc-head-right">'
      + '116 Santan St., Fortune, Marikina City<br>'
      + '+63 966 846 9993'
      + '</div>'
      + '</div>'

      // Title bar
      + '<div class="rc-title-row">'
      + '<span class="rc-title">Sales Receipt</span>'
      + '<span class="rc-order-no">#' + receiptRef + '</span>'
      + '</div>'

      // Meta 2x2 grid
      + '<div class="rc-meta">'
      + '<div class="rc-mc br"><div class="rc-ml">Date</div><div class="rc-mv">' + formatDate(order.createdAt) + '</div></div>'
      + '<div class="rc-mc"><div class="rc-ml">Payment</div><div class="rc-mv">' + escapeHtml(payDisplay) + '</div></div>'
      + '<div class="rc-mc br bt"><div class="rc-ml">Order type</div><div class="rc-mv">' + escapeHtml(orderType) + '</div></div>'
      + '<div class="rc-mc bt"><div class="rc-ml">Source</div><div class="rc-mv">' + escapeHtml(srcDisplay || '—') + '</div></div>'
      + '</div>'

      // Customer block
      + '<div class="rc-cust">'
      + '<div class="rc-cl">Bill to</div>'
      + '<div class="rc-cn">' + escapeHtml(order.customerName || '—') + '</div>'
      + (order.address ? '<div class="rc-ca">&#128205; ' + escapeHtml(order.address) + '</div>' : '')
      + '</div>'

      // Items table — dark brown header
      + '<table>'
      + '<thead><tr>'
      + '<th>Item</th>'
      + '<th class="r" style="width:50px;">Qty</th>'
      + '<th class="r" style="width:90px;">Unit price</th>'
      + '<th class="r" style="width:90px;">Amount</th>'
      + '</tr></thead>'
      + '<tbody>'
      + (order.items || []).filter(function(item) {
          return (item.quantity - (item.voidedQuantity || 0)) > 0;
        }).map(function (item) {
          var _effQty = item.quantity - (item.voidedQuantity || 0);
          var _effAmt = _effQty * Number(item.unitPrice || 0);
          return '<tr>'
            + '<td>' + escapeHtml(item.productName || '') + '</td>'
            + '<td class="r m">' + _effQty + '</td>'
            + '<td class="r m">&#8369;' + Number(item.unitPrice || 0).toFixed(2) + '</td>'
            + '<td class="r b">&#8369;' + _effAmt.toFixed(2) + '</td>'
            + '</tr>';
        }).join('')
      + '</tbody></table>'

      // Subtotals
      + '<div class="rc-totals">'
      + '<div class="rc-trow"><span>Subtotal</span><span>&#8369;' + subtotal + '</span></div>'
      + '<div class="rc-trow"><span>Discount</span><span>&minus;&#8369;' + discount + '</span></div>'
      + (deliveryFee > 0 ? '<div class="rc-trow"><span>Delivery fee</span><span>+&#8369;' + deliveryFee.toFixed(2) + '</span></div>' : '')
      + (voidedAmt > 0 ? '<div class="rc-trow" style="color:#9CA3AF;"><span>Void adjustment</span><span>&minus;&#8369;' + voidedAmt.toFixed(2) + '</span></div>' : '')
      + '</div>'

      // Grand total
      + '<div class="rc-grand">'
      + '<span class="rc-grand-lbl">Total due</span>'
      + '<span class="rc-grand-val">&#8369;' + total + '</span>'
      + '</div>'

      // Signatures
      + '<div class="rc-sigs">'
      + '<div class="rc-sig"><div class="rc-sig-line"><div class="rc-sig-name">' + escapeHtml(preparedBy) + '</div><div class="rc-sig-role">Prepared by</div></div></div>'
      + '<div class="rc-sig"><div class="rc-sig-line"><div class="rc-sig-name">&nbsp;</div><div class="rc-sig-role">Received by</div></div></div>'
      + '</div>'

      // Footer
      + '<div class="rc-foot">'
      + '<span class="rc-foot-txt">Thank you for your business &mdash; RRBM Packaging Supplies and Trading</span>'
      + '<span class="rc-foot-stamp">Official</span>'
      + '</div>'

      + '</div>'
      + '<script>window.onload=function(){window.print();}<\/script>'
      + '</body></html>');
    w.document.close();
  };

  // ================================================================
  // ORDER HISTORY — historical orders with date range + PDF
  // ================================================================
  window.renderOrderHistory = async function () {
    const tb = $('order-history-tbody');
    if (!tb) return;
    const token = localStorage.getItem('rrbm_token');
    if (!token) { tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:var(--text-muted);">Please login first</td></tr>'; return; }

    tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:var(--text-muted);">Loading…</td></tr>';

    const start = ($('history-start') || {}).value;
    const end   = ($('history-end')   || {}).value;

    let url = '' + API_BASE + '/api/orders/history';
    const params = [];
    if (start) params.push('start=' + start);
    if (end)   params.push('end='   + end);
    if (params.length) url += '?' + params.join('&');

    try {
      const res = await fetch(url, { headers: { 'Authorization': 'Bearer ' + token } });
      if (!res.ok) { tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:var(--text-muted);">Failed to load</td></tr>'; return; }
      appState.orderHistoryAll = await res.json();
      if ($('history-search')) $('history-search').value = '';
      if ($('history-source-filter')) $('history-source-filter').value = '';
      renderOrderHistoryRows(appState.orderHistoryAll);
    } catch (err) {
      tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:var(--text-muted);">Connection error</td></tr>';
    }
  };

  function renderOrderHistoryRows(orders) {
    const tb = $('order-history-tbody');
    if (!tb) return;
    if (!orders.length) {
      tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:var(--text-muted);padding:24px;">No orders found for this date range</td></tr>';
      return;
    }
    const canAdmin = canManageOrders();
    tb.innerHTML = orders.map(function (o) {
      const activeItems = (o.items || []).filter(function(it) { return (it.quantity - (it.voidedQuantity || 0)) > 0; });
      const first = activeItems[0];
      const leadQty = first ? (first.quantity - (first.voidedQuantity || 0)) : 0;
      const itemText = first
        ? first.productName + ' x' + leadQty + (activeItems.length > 1 ? ' +' + (activeItems.length - 1) + ' more' : '')
        : ((o.items && o.items.length > 0) ? '<span style="color:var(--text-muted);font-size:11px;">(all voided)</span>' : '-');
      const notCancelled = o.status !== 'CANCELLED';
      const safeId    = o.id.replace(/'/g, "\\'");
      const safeTotal = Number(o.total || 0).toFixed(2);

      // Action buttons — Ledger visible to all; Print, Refund, Void gated by canManageOrders()
      let actions = '<button class="btn btn-sm" onclick="viewOrderLedger(\'' + safeId + '\')" title="View Ledger" style="background:#7C3AED;color:#fff;margin-right:3px;"><i class="ti ti-scale"></i></button>'
        + '<button class="btn btn-secondary btn-sm" onclick="openOrderDetail(\'' + safeId + '\')" title="View Details" style="margin-right:3px;"><i class="ti ti-eye"></i></button>';
      if (canAdmin) {
        actions += '<button class="btn btn-secondary btn-sm" onclick="printOrderReceipt(\'' + safeId + '\')" title="Print Receipt" style="margin-right:3px;"><i class="ti ti-printer"></i></button>';
      }
      // N-6: only DELIVERED orders can have returns processed
      if (canAdmin && o.status === 'DELIVERED') {
        actions += '<button class="btn btn-sm" onclick="openReturnModal(\'' + safeId + '\')" title="Process Return" style="background:#F59E0B;color:#fff;margin-right:3px;"><i class="ti ti-arrow-back-up"></i></button>';
      }
      // Step 9: CANCELLED + cancellationType REPLACEMENT → Create Replacement or Replaced indicator
      if (canAdmin && o.status === 'CANCELLED' && o.cancellationType === 'REPLACEMENT') {
        if (!o.replacementOrderId) {
          actions += '<button class="btn btn-sm" onclick="openReplacementForm(\'' + safeId + '\')" title="Create Replacement Order" style="background:#10B981;color:#fff;margin-right:3px;"><i class="ti ti-replace"></i> Create Replacement</button>';
        } else {
          actions += '<span style="color:#10B981;font-size:12px;font-weight:600;margin-right:3px;"><i class="ti ti-check"></i> Replaced</span>';
        }
      }

      const importedBadgeHtml = o.imported
        ? ' <span class="badge badge-info" style="font-size:10px;vertical-align:middle;">Imported</span>'
        : '';
      const cancelReasonHtml = (o.status === 'CANCELLED' && o.cancellationReason)
        ? '<div style="font-size:10px;color:var(--accent-danger);margin-top:3px;max-width:140px;white-space:normal;line-height:1.3;">'
            + escapeHtml(o.cancellationReason) + '</div>'
        : '';
      const cancelMetaHtml = (o.status === 'CANCELLED' && o.cancelledByName)
        ? '<div style="font-size:10px;color:var(--text-muted);line-height:1.3;">by ' + escapeHtml(o.cancelledByName)
            + (o.cancelledAt ? ' &middot; ' + new Date(o.cancelledAt).toLocaleString('en-PH',{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'}) : '')
            + '</div>'
        : '';
      const collectedMetaHtml = o.collectedAt
        ? '<div style="font-size:10px;color:var(--text-muted);line-height:1.3;">collected by ' + escapeHtml(o.collectedBy || '—')
            + ' &middot; ' + new Date(o.collectedAt).toLocaleString('en-PH',{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'})
            + '</div>'
        : '';
      const refundedMetaHtml = o.refundedAt
        ? '<div style="font-size:10px;color:#F59E0B;font-weight:600;line-height:1.3;margin-top:2px;"><i class="ti ti-receipt-refund" style="margin-right:2px;"></i>Refunded</div>'
        : '';

      const ecomRef3  = ecomOrderRef(o);
      const displayId3 = ecomRef3 || o.id;
      const _vAmt3 = Number(o.voidedAmount || 0);
      const _eff3  = Number(o.total || 0) - _vAmt3;
      const _totalCell3 = _vAmt3 > 0
        ? '₱' + _eff3.toLocaleString()
          + '<div style="font-size:10px;color:var(--text-muted);text-decoration:line-through;line-height:1.2;">₱' + Number(o.total || 0).toLocaleString() + '</div>'
        : '₱' + Number(o.total || 0).toLocaleString();
      return '<tr>'
        + '<td><code style="font-size:11px;" title="' + (ecomRef3 ? 'System ID: ' + o.id : '') + '">' + displayId3 + '</code></td>'
        + '<td>' + formatDate(o.createdAt) + '</td>'
        + '<td>' + o.customerName + '</td>'
        + '<td style="font-size:12px;">' + itemText + '</td>'
        + '<td>' + _totalCell3 + '</td>'
        + '<td>' + formatSource(o.source) + '</td>'
        + '<td>' + orderStatusCell(o) + importedBadgeHtml + cancelReasonHtml + cancelMetaHtml + collectedMetaHtml + refundedMetaHtml + '</td>'
        + '<td style="white-space:nowrap;">' + actions + '</td>'
        + '</tr>';
    }).join('');
  }

  window.filterOrderHistory = function () {
    const src = (($('history-source-filter') || {}).value || '').trim();
    const q   = (($('history-search')        || {}).value || '').toLowerCase().trim();
    const ecomSubs = ['SHOPEE', 'TIKTOK', 'LAZADA'];
    let filtered = appState.orderHistoryAll || [];

    if (src) {
      filtered = filtered.filter(function (o) {
        if (ecomSubs.includes(src)) {
          return (o.source || '') === 'ECOMMERCE' && (o.ecommercePlatform || '').toUpperCase() === src;
        }
        return (o.source || '') === src;
      });
    }

    if (q) {
      filtered = filtered.filter(function (o) {
        return (o.id || '').toLowerCase().includes(q) ||
               (ecomOrderRef(o) || '').toLowerCase().includes(q) ||
               (o.customerName || '').toLowerCase().includes(q) ||
               (o.agentName || '').toLowerCase().includes(q);
      });
    }

    renderOrderHistoryRows(filtered);
  };

  window.exportOrderHistoryPDF = function () {
    const orders = appState.orderHistoryAll || [];
    if (!orders.length) { showToast('No data to export', 'error'); return; }
    const start = ($('history-start') || {}).value || '—';
    const end   = ($('history-end')   || {}).value || '—';

    // Rebuild table from data — no action buttons, no DOM cloning
    const dataRows = orders.map(function (o) {
      const _pdfActive  = (o.items || []).filter(function(it) { return (it.quantity - (it.voidedQuantity || 0)) > 0; });
      const _pdfFirst   = _pdfActive[0];
      const _pdfLeadQty = _pdfFirst ? (_pdfFirst.quantity - (_pdfFirst.voidedQuantity || 0)) : 0;
      const itemText = _pdfFirst
        ? escapeHtml(_pdfFirst.productName + ' x' + _pdfLeadQty + (_pdfActive.length > 1 ? ' +' + (_pdfActive.length - 1) + ' more' : ''))
        : (o.items && o.items.length > 0 ? '(all voided)' : '-');
      const statusMap = { PENDING:'Pending', PROCESSING:'Processing', DELIVERED:'Delivered', CANCELLED:'Cancelled', COMPLETED:'Completed' };
      const pdfRef = ecomOrderRef(o) || o.id;
      return '<tr>'
        + '<td><code>' + escapeHtml(pdfRef) + '</code></td>'
        + '<td>' + formatDate(o.createdAt) + '</td>'
        + '<td>' + escapeHtml(o.customerName || '-') + '</td>'
        + '<td>' + itemText + '</td>'
        + '<td style="text-align:right;">₱' + (Number(o.total || 0) - Number(o.voidedAmount || 0)).toLocaleString('en-PH', {minimumFractionDigits:2}) + '</td>'
        + '<td>' + escapeHtml(formatSource(o.source) || '-') + '</td>'
        + '<td>' + (statusMap[o.status] || o.status || '-') + '</td>'
        + '</tr>';
    }).join('');

    const w = window.open('', '_blank', 'width=960,height=720');
    if (!w) { showToast('Pop-up blocked — allow pop-ups and try again', 'error'); return; }
    w.document.write('<!DOCTYPE html><html><head><title>Order History — RRBM</title>'
      + '<style>body{font-family:Arial,sans-serif;font-size:12px;margin:24px;color:#1A1208;}'
      + 'h2{color:#2C1A0E;margin-bottom:4px;}p{color:#888;font-size:11px;margin-bottom:12px;}'
      + 'table{width:100%;border-collapse:collapse;margin-top:8px;}'
      + 'th{background:#FAD16A;padding:7px 10px;text-align:left;font-size:11px;text-transform:uppercase;letter-spacing:.5px;}'
      + 'td{padding:7px 10px;border-bottom:1px solid #eee;font-size:12px;}code{font-family:monospace;}'
      + '@media print{body{margin:12px;}}'
      + '</style></head><body>'
      + '<h2>RRBM Packaging — Order History</h2>'
      + '<p>Period: ' + start + ' to ' + end + ' &nbsp;|&nbsp; Exported: ' + new Date().toLocaleString() + ' &nbsp;|&nbsp; ' + orders.length + ' records</p>'
      + '<table><thead><tr>'
      + '<th>Order #</th><th>Date</th><th>Customer</th><th>Items</th><th style="text-align:right;">Total</th><th>Source</th><th>Status</th>'
      + '</tr></thead><tbody>' + dataRows + '</tbody></table>'
      + '<script>window.onload=function(){window.print();}<\/script>'
      + '</body></html>');
    w.document.close();
  };

  // ================================================================
  // CLOSE DAILY SALES
  // ================================================================
  window.askCloseDailySales = function () {
    $('close-daily-key').value = '';
    $('modal-close-daily').classList.add('open');
  };

  window.confirmCloseDailySales = async function () {
    const key = ($('close-daily-key') || {}).value || '';
    if (!key) { showToast('Master key is required', 'error'); return; }
    _closeDailyMasterKey = key;   // preserve for potential force-close

    const btn = document.querySelector('#modal-close-daily .btn-danger');
    if (btn) { btn.disabled = true; btn.textContent = 'Closing…'; }

    try {
      const userId = currentUserId();
      const res = await fetch('' + API_BASE + '/api/reports/close-daily', {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ masterKey: key, userName: currentUserName(), userId: userId })
      });
      const data = await res.json();

      if (res.ok) {
        closeModal('modal-close-daily');
        var msg = 'Daily sales closed successfully!';
        // N-3: unify accessor — force-close returns count under data.report, normal close at root
        var unfulfilled = data.unfulfilledOrders != null ? data.unfulfilledOrders : ((data.report || {}).unfulfilledOrders || 0);
        if (unfulfilled > 0) {
          msg += ' (' + unfulfilled + ' order(s) moved to Collections)';
        }
        showToast(msg, 'success');
        renderOrderList();  // will also call _checkDailyClosedBanner()
        updateCollectionsBadge();
        return;
      }

      // 409 = active orders exist → offer the force-close override
      if (res.status === 409 && data.error === 'ACTIVE_ORDERS') {
        const fmt = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
        const warning = $('close-override-warning');
        if (warning) {
          warning.innerHTML = '<strong>⚠ ' + (data.count||0) + ' order(s) totalling ' + fmt(data.amount)
            + ' cannot be collected today.</strong><br>'
            + 'Force-closing will move them to <em>Pending Collections</em>. Your admin security key is required.';
        }
        // Clear key fields
        var aKey = $('close-override-admin-key');
        if (aKey) aKey.value = '';
        var saKey = $('close-override-super-admin-key');
        if (saKey) saKey.value = '';
        closeModal('modal-close-daily');
        var overrideModal = $('modal-close-daily-override');
        if (overrideModal) overrideModal.classList.add('open');
        return;
      }

      showToast('Error: ' + (data.message || 'Failed to close'), 'error');
    } catch (err) {
      showToast('Connection error: ' + (err.message || err), 'error');
    } finally {
      if (btn) { btn.disabled = false; btn.innerHTML = '<i class="ti ti-lock"></i> Close Daily Sales'; }
    }
  };

  window.confirmForceCloseDailySales = async function () {
    const adminKey = ($('close-override-admin-key') || {}).value || '';
    if (!adminKey) { showToast('Your admin security key is required', 'error'); return; }
    const superAdminKey = ($('close-override-super-admin-key') || {}).value || '';
    if (!superAdminKey) { showToast('Super admin security key is required', 'error'); return; }

    const btn = document.querySelector('#modal-close-daily-override .btn-danger');
    if (btn) { btn.disabled = true; btn.textContent = 'Force closing…'; }

    try {
      const userId = currentUserId();
      const res = await fetch('' + API_BASE + '/api/reports/close-daily', {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({
          masterKey:             _closeDailyMasterKey,
          userName:              currentUserName(),
          userId:                userId,
          forceClose:            true,
          adminSecurityKey:      adminKey,
          superAdminSecurityKey: superAdminKey
        })
      });
      const data = await res.json();
      if (res.ok) {
        closeModal('modal-close-daily-override');
        var msg = 'Daily sales force-closed.';
        // N-3: same unified accessor as normal-close path
        var unfulfilled = data.unfulfilledOrders != null ? data.unfulfilledOrders : ((data.report || {}).unfulfilledOrders || 0);
        if (unfulfilled > 0) {
          msg += ' ' + unfulfilled + ' order(s) moved to Collections.';
        }
        showToast(msg, 'success');
        renderOrderList();
        updateCollectionsBadge();
      } else {
        showToast('Error: ' + (data.message || 'Force close failed'), 'error');
      }
    } catch (err) {
      showToast('Connection error: ' + (err.message || err), 'error');
    } finally {
      if (btn) { btn.disabled = false; btn.innerHTML = '<i class="ti ti-lock"></i> Force Close'; }
    }
  };

  // ── Collections: load, render, badge, collect ────────────────────

  window.loadCollections = async function () {
    const tb = $('collections-tbody');
    if (tb) tb.innerHTML = '<tr><td colspan="9" style="text-align:center;color:var(--text-muted);padding:24px;">Loading…</td></tr>';
    try {
      const res = await fetch(API_BASE + '/api/orders/collections', { headers: authHeaders() });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const orders = await res.json();
      _collectionsParsed = orders;
      renderCollectionRows(orders);
      updateCollectionsBadge(orders.length);
    } catch (err) {
      if (tb) tb.innerHTML = '<tr><td colspan="9" style="text-align:center;color:#EF4444;padding:24px;">Failed to load: ' + (err.message||err) + '</td></tr>';
    }
  };

  window.renderCollectionRows = function (orders) {
    const tb = $('collections-tbody');
    if (!tb) return;
    if (!orders || !orders.length) {
      tb.innerHTML = '<tr><td colspan="9" style="text-align:center;color:var(--text-muted);padding:32px;font-size:13px;">'
        + '<i class="ti ti-circle-check" style="font-size:32px;display:block;margin-bottom:8px;color:#10B981;"></i>'
        + 'No pending collections — all orders are settled!</td></tr>';
      updateBatchCollectButton();
      return;
    }
    var fmt = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
    var payBadge = function(mode){
      var map = { CASH:'Cash', COD:'COD', GCASH:'GCash', PAYMAYA:'PayMaya',
                  BANK_TRANSFER:'Bank Transfer', BANK_DEPOSIT:'Bank Deposit', ONLINE:'Online' };
      return '<span class="badge badge-honey">' + (map[mode] || mode || '—') + '</span>';
    };
    var payStatusBadge = function(ps) {
      if (!ps) return '';
      var map = { PAID:'<span style="color:#059669;font-weight:600;font-size:11px;">● PAID</span>',
                  UNPAID:'<span style="color:#DC2626;font-weight:600;font-size:11px;">● UNPAID</span>' };
      return ' ' + (map[ps] || '');
    };
    var sectionHeader = function(label, count) {
      return '<tr style="background:var(--bg-secondary);"><td colspan="9" style="padding:8px 12px;font-size:12px;font-weight:600;color:var(--text-muted);">'
        + label + ' <span style="font-weight:400;">(' + count + ' order' + (count !== 1 ? 's' : '') + ')</span></td></tr>';
    };
    function rowHtml(o) {
      var created = o.createdAt ? new Date(o.createdAt) : null;
      var dateStr = created ? created.toLocaleDateString('en-PH',{year:'numeric',month:'short',day:'numeric'}) : '—';
      var daysOut;
      if (created) {
        var todayMidnight   = new Date(); todayMidnight.setHours(0,0,0,0);
        var createdMidnight = new Date(created); createdMidnight.setHours(0,0,0,0);
        daysOut = Math.round((todayMidnight - createdMidnight) / 86400000);
      } else { daysOut = '—'; }
      var daysCol = typeof daysOut === 'number'
        ? '<span style="color:' + (daysOut >= 3 ? '#EF4444' : daysOut >= 1 ? '#F59E0B' : '#10B981') + ';font-weight:600;">'
          + daysOut + (daysOut === 1 ? ' day' : ' days') + '</span>'
        : '—';
      var itemPreview = (o.items || []).slice(0,2).map(function(i){ return (i.quantity||1) + '× ' + (i.productName||''); }).join(', ')
        + (o.items && o.items.length > 2 ? ' +' + (o.items.length - 2) + ' more' : '');
      var displayId = ecomOrderRef(o) || o.id || '—';
      var safeId = escapeHtml(o.id);
      return '<tr>'
        + '<td style="text-align:center;"><input type="checkbox" class="collection-checkbox" value="' + safeId + '" onchange="updateBatchCollectButton()"></td>'
        + '<td style="font-size:12px;">' + dateStr + '</td>'
        + '<td><span class="product-code" style="font-size:11px;">' + escapeHtml(displayId) + '</span></td>'
        + '<td>' + escapeHtml(o.customerName || '—') + '</td>'
        + '<td>' + payBadge(o.paymentMode) + payStatusBadge(o.paymentStatus) + '</td>'
        + '<td style="font-size:11px;color:var(--text-muted);">' + escapeHtml(itemPreview) + '</td>'
        + '<td style="font-weight:600;">' + fmt(o.total) + '</td>'
        + '<td>' + daysCol + '</td>'
        + '<td>'
        + '<button class="btn btn-primary btn-sm" onclick="openCollectionDetail(\'' + safeId + '\')">'
        + '<i class="ti ti-eye"></i> View</button>'
        + '</td>'
        + '</tr>';
    }
    var codRows = orders.filter(function(o){ return o.paymentMode === 'COD'; }).map(rowHtml).join('');
    var nonCodRows = orders.filter(function(o){ return o.paymentMode !== 'COD'; }).map(rowHtml).join('');
    var codCount = orders.filter(function(o){ return o.paymentMode === 'COD'; }).length;
    var nonCodCount = orders.length - codCount;
    var html = '';
    if (codCount > 0) {
      html += sectionHeader('COD', codCount) + codRows;
    }
    if (nonCodCount > 0) {
      html += sectionHeader('Non-COD (GCash · PayMaya · Bank Transfer · Cash)', nonCodCount) + nonCodRows;
    }
    tb.innerHTML = html;
    updateBatchCollectButton();
  };

  window.toggleAllCollectionCheckboxes = function (checked) {
    $$('.collection-checkbox').forEach(function (cb) { cb.checked = checked; });
    updateBatchCollectButton();
  };

  window.updateBatchCollectButton = function () {
    var checked = $$('.collection-checkbox:checked');
    var count = checked.length;
    var btn = $('btn-batch-collect');
    var label = $('batch-collect-label');
    if (btn && label) {
      if (count > 0) {
        btn.style.display = '';
        label.textContent = 'Mark Selected (' + count + ')';
      } else {
        btn.style.display = 'none';
      }
    }
  };

  window.openBatchCollectModal = function () {
    var checked = $$('.collection-checkbox:checked');
    var count = checked.length;
    if (count === 0) { showToast('No orders selected', 'error'); return; }
    var countEl = $('batch-collect-count');
    if (countEl) countEl.textContent = count;
    var keyEl = $('batch-collect-key');
    if (keyEl) keyEl.value = '';
    var resultEl = $('batch-collect-result');
    if (resultEl) resultEl.innerHTML = '';
    var confirmBtn = $('btn-batch-collect-confirm');
    if (confirmBtn) confirmBtn.disabled = false;
    openModal('modal-batch-collect');
  };

  window.confirmBatchCollect = async function () {
    var key = (($('batch-collect-key') || {}).value || '').trim();
    if (!key) { showToast('Security key is required', 'error'); return; }
    var checked = $$('.collection-checkbox:checked');
    var orderIds = Array.from(checked).map(function (cb) { return cb.value; });
    if (orderIds.length === 0) { showToast('No orders selected', 'error'); return; }
    var confirmBtn = $('btn-batch-collect-confirm');
    var resultEl = $('batch-collect-result');
    if (confirmBtn) confirmBtn.disabled = true;
    if (resultEl) resultEl.innerHTML = '<span style="color:var(--text-muted);">Processing…</span>';
    try {
      var res = await fetch(API_BASE + '/api/orders/batch-mark-collected', {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ orderIds: orderIds, securityKey: key })
      });
      var data = await res.json();
      if (res.ok) {
        var msg = data.message || 'Batch collect completed.';
        showToast(msg, 'success');
        if (resultEl) {
          var parts = [];
          if (data.collected > 0) parts.push('<span style="color:#10B981;">✔ ' + data.collected + ' collected</span>');
          if (data.skipped && data.skipped.length > 0) {
            var skipList = data.skipped.map(function (s) {
              return '<div style="font-size:12px;color:#F59E0B;margin-left:12px;">— ' + escapeHtml(s.orderId || '') + ': ' + escapeHtml(s.reason || '') + '</div>';
            }).join('');
            parts.push('<div><span style="color:#F59E0B;">⚠ ' + data.skipped.length + ' skipped</span>' + skipList + '</div>');
          }
          if (data.errors && data.errors.length > 0) {
            var errList = data.errors.map(function (e) {
              return '<div style="font-size:12px;color:#EF4444;margin-left:12px;">— ' + escapeHtml(e.orderId || '') + ': ' + escapeHtml(e.error || '') + '</div>';
            }).join('');
            parts.push('<div><span style="color:#EF4444;">✘ ' + data.errors.length + ' errors</span>' + errList + '</div>');
          }
          resultEl.innerHTML = parts.join('<br>');
        }
        closeModal('modal-batch-collect');
        loadCollections();
      } else {
        showToast(data.message || 'Batch collect failed', 'error');
        if (confirmBtn) confirmBtn.disabled = false;
      }
    } catch (err) {
      showToast('Connection error', 'error');
      if (confirmBtn) confirmBtn.disabled = false;
    }
  };

  window.updateCollectionsBadge = async function (count) {
    if (typeof count === 'undefined') {
      try {
        const res = await fetch(API_BASE + '/api/orders/collections', { headers: authHeaders() });
        if (res.ok) count = (await res.json()).length;
        else count = 0;
      } catch(e) { count = 0; }
    }
    var badge = $('collections-badge');
    if (!badge) return;
    if (count > 0) {
      badge.textContent = count;
      badge.style.display = 'inline';
    } else {
      badge.style.display = 'none';
    }
  };

  /** Open the collection detail modal for a specific order — always fetches fresh data from server */
  window.openCollectionDetail = async function (orderId) {
    let order;
    try {
      const res = await fetch(API_BASE + '/api/orders/' + encodeURIComponent(orderId), { headers: authHeaders() });
      if (!res.ok) { showToast('Failed to load order details', 'error'); return; }
      order = await res.json();
    } catch (err) {
      showToast('Connection error loading order', 'error');
      return;
    }
    if (!order) { showToast('Order not found', 'error'); return; }

    var fmt = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
    var created = order.createdAt ? new Date(order.createdAt).toLocaleDateString('en-PH',{year:'numeric',month:'long',day:'numeric'}) : '—';
    var displayId = ecomOrderRef(order) || order.id;

    var hasVoids = (order.items || []).some(function(it) { return (it.voidedQuantity || 0) > 0; });
    var itemsHtml = (order.items || []).map(function(it) {
      var _voided = it.voidedQuantity || 0;
      var _rem = it.quantity - _voided;
      var _full = _rem <= 0;
      var _nameStyle = _full ? 'font-size:12px;text-decoration:line-through;color:var(--text-muted);' : 'font-size:12px;';
      var _sub = _full
        ? '<span style="color:var(--text-muted);text-decoration:line-through;">' + fmt(it.subtotal || (it.quantity||1)*(it.unitPrice||0)) + '</span>'
        : fmt(_rem * (it.unitPrice || 0));
      var _qtyHtml;
      if (hasVoids) {
        var _vCell = _voided > 0
          ? '<td style="text-align:center;color:#EF4444;font-weight:600;">&#8722;' + _voided + '</td>'
          : '<td style="text-align:center;color:var(--text-muted);">&#8212;</td>';
        var _aCell = '<td style="text-align:center;color:' + (_full ? '#EF4444' : '#10B981') + ';font-weight:600;">' + _rem + '</td>';
        _qtyHtml = '<td style="text-align:center;color:var(--text-muted);">' + it.quantity + '</td>' + _vCell + _aCell;
      } else {
        _qtyHtml = '<td style="text-align:center;">' + (it.quantity || 1) + '</td>';
      }
      return '<tr><td style="' + _nameStyle + '">' + escapeHtml(it.productName||'') + '</td>'
        + _qtyHtml
        + '<td style="text-align:right;">' + fmt(it.unitPrice) + '</td>'
        + '<td style="text-align:right;font-weight:600;">' + _sub + '</td></tr>';
    }).join('') || '<tr><td colspan="' + (hasVoids ? 6 : 4) + '" style="text-align:center;color:var(--text-muted);">No items</td></tr>';

    var body = $('collection-detail-body');
    if (body) {
      body.innerHTML =
        '<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:12px;font-size:12px;">' +
          '<div><strong>Order ID:</strong> ' + escapeHtml(displayId) + '</div>' +
          '<div><strong>Date:</strong> ' + created + '</div>' +
          '<div><strong>Customer:</strong> ' + escapeHtml(order.customerName||'—') + '</div>' +
          '<div><strong>Payment:</strong> ' + formatPaymentMode(order.paymentMode) + (order.paymentStatus ? ' <span style="font-size:11px;font-weight:600;color:' + (order.paymentStatus==='PAID' ? '#059669' : '#DC2626') + ';">(' + order.paymentStatus + ')</span>' : '') + '</div>' +
          (order.notes ? '<div style="grid-column:1/3;"><strong>Notes:</strong> ' + escapeHtml(order.notes) + '</div>' : '') +
        '</div>' +
        '<table class="table" style="margin-bottom:12px;">' +
          '<thead><tr><th>Product</th>'
          + (hasVoids
              ? '<th style="text-align:center;color:var(--text-muted);">Ordered</th><th style="text-align:center;color:#EF4444;">Voided</th><th style="text-align:center;color:#10B981;">Active</th>'
              : '<th style="text-align:center;">Qty</th>')
          + '<th style="text-align:right;">Price</th><th style="text-align:right;">Subtotal</th></tr></thead>' +
          '<tbody>' + itemsHtml + '</tbody>' +
          '<tfoot><tr><td colspan="' + (hasVoids ? 5 : 3) + '" style="text-align:right;font-weight:700;">Total</td><td style="text-align:right;font-weight:700;font-size:14px;">' + (Number(order.voidedAmount || 0) > 0 ? fmt(Number(order.total || 0) - Number(order.voidedAmount || 0)) + '<div style="font-size:10px;color:var(--text-muted);text-decoration:line-through;line-height:1.2;">' + fmt(order.total) + '</div>' : fmt(order.total)) + '</td></tr></tfoot>' +
        '</table>' +
        '<div style="display:flex;gap:8px;margin-bottom:12px;">' +
          '<button class="btn btn-secondary btn-sm" onclick="printOrderReceipt(\'' + escapeHtml(order.id) + '\')">' +
            '<i class="ti ti-receipt"></i> Print Receipt</button>' +
        '</div>' +
        '<div style="border-top:1px solid var(--border);padding-top:12px;">' +
          '<div style="font-size:12px;font-weight:600;margin-bottom:8px;">Mark as Collected</div>' +
          '<div style="display:flex;gap:8px;align-items:flex-end;">' +
            '<div style="flex:1;">' +
              '<label class="form-label" style="font-size:11px;">Your Security Key</label>' +
              '<input type="password" class="form-control" id="coll-detail-key" placeholder="Enter security key" />' +
            '</div>' +
            '<button class="btn btn-success btn-sm" id="coll-detail-btn" onclick="confirmCollectFromDetail(\'' + escapeHtml(order.id) + '\')">' +
              '<i class="ti ti-check"></i> Collect Payment</button>' +
          '</div>' +
        '</div>';
    }

    var title = $('collection-detail-title');
    if (title) title.textContent = 'Collection — ' + escapeHtml(displayId);

    var modal = $('modal-collection-detail');
    if (modal) modal.classList.add('open');
  };

  /** Confirm collection from the detail modal */
  window.confirmCollectFromDetail = async function (orderId) {
    var key = ($('coll-detail-key') || {}).value || '';
    if (!key) { showToast('Security key is required', 'error'); return; }

    var btn = $('coll-detail-btn');
    if (btn) { btn.disabled = true; btn.textContent = 'Processing…'; }

    try {
      var res = await fetch(API_BASE + '/api/orders/' + encodeURIComponent(orderId) + '/collect', {
        method: 'PATCH', headers: authHeaders(),
        body: JSON.stringify({ securityKey: key })
      });
      var data = await res.json();
      if (res.ok) {
        closeModal('modal-collection-detail');
        var origDate = data.createdAt ? new Date(data.createdAt).toLocaleDateString('en-PH',{month:'short',day:'numeric',year:'numeric'}) : 'original date';
        showToast('Payment collected! Revenue posted to ' + origDate, 'success');
        loadCollections();
      } else {
        showToast('Error: ' + (data.message || 'Collection failed'), 'error');
      }
    } catch (err) {
      showToast('Connection error', 'error');
    } finally {
      if (btn) { btn.disabled = false; btn.innerHTML = '<i class="ti ti-check"></i> Collect Payment'; }
    }
  };

  // ================================================================
  // INVENTORY — two-tier search
  // ================================================================
  function getTagThresholds(tag) {
    if ((tag || '').toUpperCase() === 'HOT')  return { low: 5000, critical: 2500 };
    if ((tag || '').toUpperCase() === 'SLOW') return { low: 1000, critical: 500 };
    return { low: 2000, critical: 1000 };
  }

  function populateInvCategoryDropdown(categories, previousValue) {
    const sel = $('inv-category-filter');
    if (!sel) return;
    const prev = previousValue != null ? previousValue : sel.value;
    sel.innerHTML = '<option value="">All categories</option>';
    (categories || [])
      .filter(function (c) { return c != null && String(c).trim() !== ''; })
      .map(function (c) { return String(c).trim(); })
      .sort(function (a, b) { return a.localeCompare(b); })
      .forEach(function (c) {
        const opt = document.createElement('option');
        opt.value = c; opt.textContent = c;
        sel.appendChild(opt);
      });
    if (prev && [...sel.options].some(function (o) { return o.value === prev; })) sel.value = prev;
  }

  function populateInvSubCategoryDropdown(filterCategory) {
    const sel = $('inv-subcategory-filter');
    if (!sel) return;
    const products = appState.inventoryAllProducts || [];
    let subs = products
      .filter(function (p) { return !filterCategory || (p.category || '') === filterCategory; })
      .map(function (p) { return (p.subCategory || '').trim(); })
      .filter(function (s) { return s !== ''; });
    subs = [...new Set(subs)].sort();
    sel.innerHTML = '<option value="">All sub-categories</option>';
    subs.forEach(function (s) {
      const opt = document.createElement('option');
      opt.value = s; opt.textContent = s;
      sel.appendChild(opt);
    });
  }

  // Called when the category filter changes — refresh sub-category list then re-filter
  window.onInvCategoryChange = function () {
    const cat = ($('inv-category-filter') || {}).value || '';
    populateInvSubCategoryDropdown(cat);
    const subSel = $('inv-subcategory-filter');
    if (subSel) subSel.value = '';   // reset sub-category selection
    applyInventoryFilters();
  };

  // Called when category input changes in Add Product modal — refresh sub-category datalist
  window.onAddProdCategoryInput = function () {
    const cat = (($('addprod-category') || {}).value || '').trim();
    const dl  = $('addprod-subcategory-list');
    if (!dl) return;
    const products = appState.inventoryAllProducts || [];
    const subs = [...new Set(
      products
        .filter(function (p) { return !cat || (p.category || '').toLowerCase() === cat.toLowerCase(); })
        .map(function (p) { return (p.subCategory || '').trim(); })
        .filter(function (s) { return s !== ''; })
    )].sort();
    dl.innerHTML = subs.map(function (s) { return '<option value="' + escapeHtml(s) + '">'; }).join('');
  };

  // ================================================================
  // EDIT PRODUCT — open modal with current values, save via PATCH
  // ================================================================
  window.openEditProductModal = function (productId) {
    const p = (appState.inventoryAllProducts || []).find(function (x) { return String(x.id) === String(productId); });
    if (!p) { showToast('Product not found — try refreshing inventory', 'error'); return; }

    $('editprod-id').value          = p.id;
    $('editprod-code').value         = p.productCode  || '';
    $('editprod-name').value         = p.name         || '';
    $('editprod-category').value     = p.category     || '';
    $('editprod-subcategory').value  = p.subCategory  || '';
    $('editprod-item-code').value    = p.itemCode      || '';
    $('editprod-description').value  = p.description   || '';
    $('editprod-price').value        = p.unitPrice    != null ? p.unitPrice  : '';
    $('editprod-cost').value         = p.unitCost     != null ? p.unitCost   : '';
    $('editprod-wh1').value          = p.stockWh1     != null ? p.stockWh1   : 0;
    $('editprod-wh2').value          = p.stockWh2     != null ? p.stockWh2   : 0;
    $('editprod-wh3').value          = p.stockWh3     != null ? p.stockWh3   : 0;
    $('editprod-master-key').value   = '';

    const activeYes = $('editprod-active-yes');
    const activeNo  = $('editprod-active-no');
    if (activeYes) activeYes.checked = (p.active !== false);
    if (activeNo)  activeNo.checked  = (p.active === false);

    var nameLabel = $('editprod-name-label');
    if (nameLabel) nameLabel.textContent = '— ' + (p.name || '');

    // Populate set section
    var editIsSetCb = $('editprod-is-set');
    var editSetSection = $('editprod-set-section');
    var editCompList = $('editprod-components-list');
    if (editIsSetCb) editIsSetCb.checked = !!p.isSet;
    if (editSetSection) editSetSection.style.display = p.isSet ? '' : 'none';
    if (editCompList) {
      editCompList.innerHTML = '';
      if (p.isSet && p.components && p.components.length) {
        // Pre-populate component rows from the product data
        var _cached = appState.cachedProducts;
        var _inv    = appState.inventoryAllProducts;
        var allProds = (_cached && _cached.length) ? _cached : (_inv && _inv.length) ? _inv : [];
        p.components.forEach(function (c) {
          var rowId = 'editprod-comp-row-' + c.componentProductId;
          var opts = '<option value="">— Select component product —</option>'
            + allProds.filter(function (x) { return !x.isSet; })
              .map(function (x) {
                return '<option value="' + x.id + '"' + (x.id === c.componentProductId ? ' selected' : '') + '>' + escapeHtml(x.name) + '</option>';
              }).join('');
          editCompList.insertAdjacentHTML('beforeend',
            '<div id="' + rowId + '" style="display:flex;gap:6px;align-items:center;">'
            + '<select class="form-select form-select-sm" style="flex:1;" data-comp-id>' + opts + '</select>'
            + '<input type="number" class="form-control form-control-sm" style="width:72px;" min="1" value="' + (c.quantityPerSet || 1) + '" placeholder="Qty" data-comp-qty title="Qty per set" />'
            + '<button type="button" class="icon-btn" style="color:#EF4444;" onclick="document.getElementById(\'' + rowId + '\').remove()" title="Remove"><i class="ti ti-trash"></i></button>'
            + '</div>'
          );
        });
      }
    }

    // Populate category datalist
    var catList = $('editprod-category-list');
    if (catList) {
      var cats = [...new Set((appState.inventoryAllProducts || []).map(function (x) { return x.category || ''; }).filter(Boolean))].sort();
      catList.innerHTML = cats.map(function (c) { return '<option value="' + escapeHtml(c) + '">'; }).join('');
    }

    // Populate sub-category datalist based on selected category
    onEditProdCategoryInput();

    $('modal-editprod-form').classList.add('open');
  };

  window.onEditProdCategoryInput = function () {
    const cat = (($('editprod-category') || {}).value || '').trim();
    const dl  = $('editprod-subcategory-list');
    if (!dl) return;
    const products = appState.inventoryAllProducts || [];
    const subs = [...new Set(
      products
        .filter(function (p) { return !cat || (p.category || '').toLowerCase() === cat.toLowerCase(); })
        .map(function (p) { return (p.subCategory || '').trim(); })
        .filter(function (s) { return s !== ''; })
    )].sort();
    dl.innerHTML = subs.map(function (s) { return '<option value="' + escapeHtml(s) + '">'; }).join('');
  };

  window.submitEditProduct = async function () {
    const id        = ($('editprod-id') || {}).value;
    const name      = (($('editprod-name') || {}).value || '').trim();
    const masterKey = (($('editprod-master-key') || {}).value || '').trim();
    if (!id)         { showToast('No product selected', 'error'); return; }
    if (!name)       { showToast('Product name is required', 'error'); return; }
    if (!masterKey)  { showToast('Master key is required', 'error'); return; }
    const activeVal = document.querySelector('input[name="editprod-active"]:checked');
    const editIsSet = !!($('editprod-is-set') && $('editprod-is-set').checked);
    const editComps = editIsSet ? getSetComponentsFromForm('editprod') : [];
    if (editIsSet && editComps.length < 2) {
      showToast('A set product must have at least 2 components', 'error'); return;
    }
    const body = {
      masterKey:     masterKey,
      name:          name,
      productCode:   (($('editprod-code')        || {}).value || '').trim() || null,
      category:      (($('editprod-category')    || {}).value || '').trim() || null,
      subCategory:   (($('editprod-subcategory') || {}).value || '').trim() || null,
      itemCode:      (($('editprod-item-code')   || {}).value || '').trim() || null,
      description:   (($('editprod-description') || {}).value || '').trim() || null,
      unitPrice:     parseFloat(($('editprod-price') || {}).value) || 0,
      unitCost:      parseFloat(($('editprod-cost')  || {}).value) || 0,
      stockWh1:      parseInt(($('editprod-wh1')  || {}).value, 10) || 0,
      stockWh2:      parseInt(($('editprod-wh2')  || {}).value, 10) || 0,
      stockWh3:      parseInt(($('editprod-wh3')  || {}).value, 10) || 0,
      active:        activeVal ? activeVal.value === 'true' : true,
      isSet:         editIsSet,
      components:    editComps,
      encodedByName: currentUserName(),
    };
    try {
      const res = await fetch(API_BASE + '/api/products/' + id, {
        method: 'PATCH', headers: authHeaders(), body: JSON.stringify(body)
      });
      if (!res.ok) {
        const d = await res.json().catch(function () { return {}; });
        showToast('Error: ' + (d.message || 'Failed to save changes'), 'error');
        return;
      }
      closeModal('modal-editprod-form');
      showToast('Product updated successfully', 'success');
      await renderInventory();
      await loadProducts();
    } catch (err) {
      showToast('Connection error. Is the backend running?', 'error');
    }
  };

  async function renderInventory() {
    const tb = $('inv-tbody');
    if (!tb) return;
    const token = localStorage.getItem('rrbm_token');
    if (!token) { tb.innerHTML = '<tr><td colspan="10" style="text-align:center;color:var(--text-muted);">Please login first</td></tr>'; return; }

    const catSel = $('inv-category-filter');
    const prevCat = catSel ? catSel.value : '';
    tb.innerHTML = '<tr><td colspan="10" style="text-align:center;color:var(--text-muted);">Loading inventory…</td></tr>';

    try {
      const headers = { 'Authorization': 'Bearer ' + token };
      const [catRes, prodRes] = await Promise.all([
        fetch('' + API_BASE + '/api/products/categories', { headers }),
        fetch('' + API_BASE + '/api/products/all', { headers })
      ]);
      if (catRes.ok) populateInvCategoryDropdown(await catRes.json(), prevCat);
      if (!prodRes.ok) { tb.innerHTML = '<tr><td colspan="10" style="text-align:center;color:var(--text-muted);">Failed to load inventory</td></tr>'; return; }
      appState.inventoryAllProducts = await prodRes.json();
      const curCat = catSel ? catSel.value : '';
      populateInvSubCategoryDropdown(curCat);
      applyInventoryFilters();
    } catch (err) {
      tb.innerHTML = '<tr><td colspan="10" style="text-align:center;color:var(--text-muted);">Connection error</td></tr>';
    }
  }

  window.filterInventory = function () { applyInventoryFilters(); };

  function applyInventoryFilters() {
    const tb = $('inv-tbody');
    if (!tb) return;

    // Show/hide the Actions column header based on edit permission
    const canEdit = canEditInventory();
    const actTh = $('inv-actions-th');
    if (actTh) actTh.style.display = canEdit ? '' : 'none';

    let products = appState.inventoryAllProducts.slice();

    const filterCat = ($('inv-category-filter') ? $('inv-category-filter').value : '').trim();
    if (filterCat) products = products.filter(function (p) { return (p.category || '') === filterCat; });

    const filterSub = ($('inv-subcategory-filter') ? $('inv-subcategory-filter').value : '').trim();
    if (filterSub) products = products.filter(function (p) { return (p.subCategory || '') === filterSub; });

    const kw = (($('inv-keyword') ? $('inv-keyword').value : '') || '').toLowerCase().trim();
    if (kw) products = products.filter(function (p) {
      return (p.name || '').toLowerCase().includes(kw)
          || (p.productCode || '').toLowerCase().includes(kw)
          || (p.itemCode || '').toLowerCase().includes(kw)
          || (p.sku || '').toLowerCase().includes(kw)
          || (p.description || '').toLowerCase().includes(kw);
    });

    if (products.length === 0) {
      tb.innerHTML = '<tr><td colspan="' + (canEdit ? '12' : '11') + '" style="text-align:center;color:var(--text-muted);padding:20px;">No products found</td></tr>';
      return;
    }

    tb.innerHTML = products.map(function (p) {
      const wh1 = p.stockWh1 || 0, wh2 = p.stockWh2 || 0, wh3 = p.stockWh3 || 0;
      const total = wh1 + wh2 + wh3;
      const tag = (p.sellingTag || 'SELLING').toUpperCase();

      const thresh = getTagThresholds(tag);
      const lowLevel = thresh.low;
      const critLevel = thresh.critical;

      const barMax = lowLevel * 2;
      const pct = Math.min(100, (total / barMax) * 100);

      let rowClass = '', badge = '', barColor = '#10B981';
      if (total === 0) {
        rowClass = 'row-oos'; badge = '<span class="badge badge-crit">Out of Stock</span>'; barColor = '#888';
      } else if (total <= critLevel) {
        rowClass = 'row-crit'; badge = '<span class="badge badge-crit">Critical</span>'; barColor = '#EF4444';
      } else if (total <= lowLevel) {
        rowClass = 'row-hot'; badge = '<span class="badge badge-low">Low</span>'; barColor = '#F59E0B';
      } else {
        badge = '<span class="badge badge-ok">OK</span>';
      }

      const tagSelect = '<select class="tag-select tag-select-' + tag + '" data-id="' + p.id + '" onchange="updateProductTag(this)">'
        + '<option value="HOT"' + (tag === 'HOT' ? ' selected' : '') + '>HOT</option>'
        + '<option value="SELLING"' + (tag === 'SELLING' ? ' selected' : '') + '>SELLING</option>'
        + '<option value="SLOW"' + (tag === 'SLOW' ? ' selected' : '') + '>SLOW</option>'
        + '</select>';

      const nameStyle = p.active ? 'font-weight:600;' : 'font-weight:600;color:#aaa;text-decoration:line-through;';
      const inactiveLabel = p.active ? '' : ' <span style="font-size:10px;color:#aaa;">(inactive)</span>';
      const codeCell = p.productCode
        ? '<span class="product-code">' + escapeHtml(p.productCode) + '</span>'
        : '<span style="color:var(--border);font-size:11px;">—</span>';
      const itemCodeCell = p.itemCode
        ? '<span style="font-size:11px;color:var(--text-muted);font-family:monospace;">' + escapeHtml(p.itemCode) + '</span>'
        : '<span style="color:var(--border);font-size:11px;">—</span>';
      const subCatLabel = p.subCategory
        ? '<br><span style="font-size:11px;color:var(--text-muted);font-weight:400;">' + p.subCategory + '</span>'
        : '';
      const descCell = p.description
        ? '<span style="font-size:12px;color:var(--text-secondary);">' + escapeHtml(p.description) + '</span>'
        : '<span style="color:var(--border);font-size:11px;">—</span>';

      // SET badge + effective stock for set products
      const setBadge = p.isSet
        ? ' <span style="font-size:10px;font-weight:700;background:#D4860A;color:#fff;padding:1px 5px;border-radius:3px;vertical-align:middle;">SET</span>'
        : '';
      let setEffectiveNote = '';
      if (p.isSet) {
        const eff = effectiveSetStock(p);
        setEffectiveNote = '<br><span style="font-size:10px;color:#D4860A;">' + (eff !== null ? eff + ' sets available' : 'no components') + '</span>';
      }

      const editCell = canEdit
        ? '<td><button class="btn btn-sm" style="background:#D4860A;color:#fff;padding:4px 8px;border:none;border-radius:5px;cursor:pointer;" onclick="openEditProductModal(' + p.id + ')" title="Edit Product"><i class="ti ti-edit"></i></button></td>'
        : '';

      return '<tr class="' + rowClass + '">'
        + '<td>' + codeCell + '</td>'
        + '<td>' + itemCodeCell + '</td>'
        + '<td><span style="' + nameStyle + '">' + p.name + '</span>' + setBadge + subCatLabel + inactiveLabel + setEffectiveNote + '</td>'
        + '<td style="max-width:240px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="' + (p.description ? escapeHtml(p.description) : '') + '">' + descCell + '</td>'
        + '<td>' + tagSelect + '</td>'
        + '<td>' + (p.isSet ? '<span style="color:var(--text-muted);font-size:11px;">—</span>' : wh1.toLocaleString()) + '</td>'
        + '<td>' + (p.isSet ? '<span style="color:var(--text-muted);font-size:11px;">—</span>' : wh2.toLocaleString()) + '</td>'
        + '<td>' + (p.isSet ? '<span style="color:var(--text-muted);font-size:11px;">—</span>' : wh3.toLocaleString()) + '</td>'
        + '<td>' + (p.isSet ? '<span style="color:var(--text-muted);font-size:11px;">—</span>' : '<strong>' + total.toLocaleString() + '</strong>') + '</td>'
        + '<td>' + (p.isSet ? '' : '<div class="stock-bar-wrap"><div class="stock-bar" style="width:' + pct + '%;background:' + barColor + ';"></div></div>') + '</td>'
        + '<td>' + (p.isSet ? '<span style="color:var(--text-muted);font-size:11px;">—</span>' : badge) + '</td>'
        + editCell
        + '</tr>';
    }).join('');
  }

  window.updateProductTag = async function (selectEl) {
    const id = selectEl.getAttribute('data-id');
    const tag = selectEl.value;
    selectEl.className = 'tag-select tag-select-' + tag;
    try {
      const res = await fetch('' + API_BASE + '/api/products/' + id + '/tag', {
        method: 'PATCH',
        headers: authHeaders(),
        body: JSON.stringify({ sellingTag: tag, userName: currentUserName() })
      });
      if (res.ok) {
        const p = appState.inventoryAllProducts.find(function (x) { return String(x.id) === String(id); });
        if (p) p.sellingTag = tag;
        showToast('Tag updated to ' + tag, 'success');
        applyInventoryFilters();
      } else {
        const d = await res.json();
        showToast('Error: ' + (d.message || 'Failed to update tag'), 'error');
        applyInventoryFilters();
      }
    } catch (err) {
      showToast('Connection error', 'error');
      applyInventoryFilters();
    }
  };

  // ================================================================
  // ADD PRODUCT — two-step flow
  // ================================================================
  window.askAddProductKey = function () {
    appState.addProductVerifiedKey = null;
    $('addprod-key-input').value = '';
    $('modal-addprod-key').classList.add('open');
  };

  window.verifyAddProductKey = async function () {
    const key = ($('addprod-key-input') || {}).value || '';
    if (!key) { showToast('Master key is required', 'error'); return; }
    try {
      await fetch('' + API_BASE + '/api/reports/daily-status', { headers: authHeaders() });
      appState.addProductVerifiedKey = key;
      closeModal('modal-addprod-key');
      openAddProductForm();
    } catch (err) {
      showToast('Connection error. Is the backend running?', 'error');
    }
  };

  function openAddProductForm() {
    ['addprod-code','addprod-name','addprod-category','addprod-subcategory','addprod-cost',
     'addprod-item-code','addprod-description'].forEach(function (id) {
      if ($(id)) $(id).value = '';
    });
    if ($('addprod-price')) $('addprod-price').value = '';
    if ($('addprod-wh1')) $('addprod-wh1').value = '0';
    if ($('addprod-wh2')) $('addprod-wh2').value = '0';
    if ($('addprod-wh3')) $('addprod-wh3').value = '0';
    if ($('addprod-thresh-crit')) $('addprod-thresh-crit').value = 1000;
    if ($('addprod-thresh-low'))  $('addprod-thresh-low').value  = 2000;
    // Reset set section
    var isSetCb = $('addprod-is-set');
    if (isSetCb) isSetCb.checked = false;
    var setSection = $('addprod-set-section');
    if (setSection) setSection.style.display = 'none';
    var compList = $('addprod-components-list');
    if (compList) compList.innerHTML = '';

    const dl = $('addprod-category-list');
    if (dl) {
      const cats = [...new Set(appState.inventoryAllProducts.map(function (p) { return p.category; }).filter(Boolean))].sort();
      dl.innerHTML = cats.map(function (c) { return '<option value="' + escapeHtml(c) + '">'; }).join('');
    }
    // Populate sub-category datalist with all known sub-categories
    const dlSub = $('addprod-subcategory-list');
    if (dlSub) {
      const subs = [...new Set(appState.inventoryAllProducts.map(function (p) { return (p.subCategory || '').trim(); }).filter(Boolean))].sort();
      dlSub.innerHTML = subs.map(function (s) { return '<option value="' + escapeHtml(s) + '">'; }).join('');
    }
    $('modal-addprod-form').classList.add('open');
  }

  window.submitAddProduct = async function () {
    const name     = (($('addprod-name')     || {}).value || '').trim();
    const category = (($('addprod-category') || {}).value || '').trim();
    const price    = parseFloat(($('addprod-price') || {}).value) || 0;
    const code     = (($('addprod-code')     || {}).value || '').trim().toUpperCase();

    if (!name)  { showToast('Product name is required', 'error'); return; }
    if (!category) { showToast('Category is required', 'error'); return; }
    if (price <= 0) { showToast('Unit price must be greater than 0', 'error'); return; }
    if (code && !/^[A-Z0-9]{1,6}$/.test(code)) {
      showToast('Product Code must be 1–6 alphanumeric characters', 'error'); return;
    }

    const isSet = !!($('addprod-is-set') && $('addprod-is-set').checked);
    const components = isSet ? getSetComponentsFromForm('addprod') : [];
    if (isSet && components.length < 2) {
      showToast('A set product must have at least 2 components', 'error'); return;
    }

    const body = {
      masterKey:         appState.addProductVerifiedKey,
      productCode:       code || null,
      name:              name,
      category:          category,
      subCategory:       (($('addprod-subcategory')  || {}).value || '').trim() || null,
      itemCode:          (($('addprod-item-code')     || {}).value || '').trim() || null,
      description:       (($('addprod-description')  || {}).value || '').trim() || null,
      sellingTag:        'SELLING',
      unitPrice:         price,
      unitCost:          parseFloat(($('addprod-cost') || {}).value) || 0,
      thresholdCritical: parseInt(($('addprod-thresh-crit') || {}).value) || 1000,
      thresholdLow:      parseInt(($('addprod-thresh-low')  || {}).value) || 2000,
      stockWh1:          parseInt(($('addprod-wh1') || {}).value) || 0,
      stockWh2:          parseInt(($('addprod-wh2') || {}).value) || 0,
      stockWh3:          parseInt(($('addprod-wh3') || {}).value) || 0,
      isSet:             isSet,
      components:        components,
      encodedByName:     currentUserName(),
    };

    try {
      const res = await fetch('' + API_BASE + '/api/products', {
        method: 'POST', headers: authHeaders(), body: JSON.stringify(body)
      });
      const data = await res.json();
      if (res.ok) {
        closeModal('modal-addprod-form');
        showToast('Product "' + name + '" added!', 'success');
        appState.addProductVerifiedKey = null;
        await renderInventory();
        await loadProducts();
      } else {
        showToast('Error: ' + (data.message || 'Failed to add product'), 'error');
      }
    } catch (err) {
      showToast('Connection error', 'error');
    }
  };

  // ================================================================
  // SET PRODUCT HELPERS
  // ================================================================

  /**
   * Toggle the set-components section visibility.
   * prefix = 'addprod' | 'editprod'
   */
  window.toggleSetSection = function (prefix) {
    var cb  = $(prefix + '-is-set');
    var sec = $(prefix + '-set-section');
    if (!cb || !sec) return;
    if (cb.checked) {
      sec.style.display = '';
    } else {
      sec.style.display = 'none';
      var list = $(prefix + '-components-list');
      if (list) list.innerHTML = '';
    }
  };

  /**
   * Add a component row inside the set section.
   * Each row: [product select] [qty per set] [remove btn]
   */
  window.addSetComponentRow = function (prefix) {
    var list = $(prefix + '-components-list');
    if (!list) return;

    // Use whichever product list is populated — [] is truthy so cannot use ||
    var cached = appState.cachedProducts;
    var inv    = appState.inventoryAllProducts;
    var products = (cached && cached.length) ? cached : (inv && inv.length) ? inv : [];
    var opts = '<option value="">— Select component product —</option>'
      + products
          .filter(function (p) { return !p.isSet; })
          .map(function (p) {
            return '<option value="' + p.id + '">' + escapeHtml(p.name) + '</option>';
          }).join('');

    var rowId = prefix + '-comp-row-' + Date.now();
    var rowHtml = '<div id="' + rowId + '" style="display:flex;gap:6px;align-items:center;">'
      + '<select class="form-select form-select-sm" style="flex:1;" data-comp-id>'
      +   opts
      + '</select>'
      + '<input type="number" class="form-control form-control-sm" style="width:72px;" min="1" value="1" placeholder="Qty" data-comp-qty title="Qty per set" />'
      + '<button type="button" class="icon-btn" style="color:#EF4444;" onclick="document.getElementById(\'' + rowId + '\').remove()" title="Remove"><i class="ti ti-trash"></i></button>'
      + '</div>';
    list.insertAdjacentHTML('beforeend', rowHtml);
  };

  /**
   * Read all component rows from the set section.
   * Returns [{ componentProductId, quantityPerSet }] or []
   */
  window.getSetComponentsFromForm = function (prefix) {
    var list = $(prefix + '-components-list');
    if (!list) return [];
    var rows = list.querySelectorAll('[data-comp-id]');
    var result = [];
    rows.forEach(function (sel) {
      var compId = parseInt(sel.value, 10);
      var qtyInput = sel.parentElement.querySelector('[data-comp-qty]');
      var qty = qtyInput ? (parseInt(qtyInput.value, 10) || 1) : 1;
      if (compId) result.push({ componentProductId: compId, quantityPerSet: qty });
    });
    return result;
  };

  /**
   * Compute effective stock for a set product.
   * Returns Math.floor(min of (compStock / quantityPerSet)) across all components.
   */
  window.effectiveSetStock = function (setProduct) {
    var _c = appState.cachedProducts, _i = appState.inventoryAllProducts;
    var all = (_c && _c.length) ? _c : (_i && _i.length) ? _i : [];
    if (!setProduct.isSet || !setProduct.components || !setProduct.components.length) return null;
    var min = Infinity;
    setProduct.components.forEach(function (c) {
      var comp = all.find(function (p) { return p.id === c.componentProductId; });
      if (!comp) return;
      var avail = Math.floor((comp.stockWh1 + comp.stockWh2 + comp.stockWh3) / c.quantityPerSet);
      if (avail < min) min = avail;
    });
    return min === Infinity ? 0 : min;
  };

  // ================================================================
  // DELIVERY RECEIPT — searchable product dropdown, form state
  // ================================================================
  function addDeliveryLineRow() {
    appState.deliveryLineCounter = (appState.deliveryLineCounter || 0) + 1;
    const n = appState.deliveryLineCounter;
    const container = $('delivery-items-container');
    if (!container) return;

    container.insertAdjacentHTML('beforeend',
      '<div class="row align-items-end mb-2 delivery-line" id="delivery-row-' + n + '">'
      + '<div class="col-md-3"><label class="form-label">Product <span class="text-danger">*</span></label>'
      + '<div class="product-autocomplete-wrapper">'
      + '<input type="text" class="form-control delivery-product-input" id="d-prod-in-' + n + '" '
      + 'placeholder="Type to search products…" autocomplete="off">'
      + '<input type="hidden" class="delivery-product-id" id="d-prod-id-' + n + '" value="">'
      + '<div class="product-dropdown" id="d-prod-dd-' + n + '"></div>'
      + '</div></div>'
      + '<div class="col-md-1"><label class="form-label">Qty <span class="text-danger">*</span></label>'
      + '<input type="number" class="form-control delivery-qty" id="delivery-qty-' + n + '" min="1" value="1" /></div>'
      + '<div class="col-md-2"><label class="form-label">Total Received</label>'
      + '<input type="number" class="form-control delivery-received" id="delivery-received-' + n + '" min="0" value="0" placeholder="0" /></div>'
      + '<div class="col-md-1"><label class="form-label">Rejected</label>'
      + '<input type="number" class="form-control delivery-rejected" id="delivery-rejected-' + n + '" min="0" value="0" placeholder="0" /></div>'
      + '<div class="col-md-2"><label class="form-label">Unit Cost (₱)</label>'
      + '<input type="number" class="form-control delivery-unit-cost" id="delivery-unit-cost-' + n + '" min="0" step="0.01" placeholder="Invoice cost" /></div>'
      + '<div class="col-md-2"><label class="form-label">Warehouse</label>'
      + '<select class="form-select delivery-wh" id="delivery-wh-' + n + '"><option value="wh1">WH1</option><option value="wh2">WH2</option><option value="wh3">Balagtas</option></select></div>'
      + '<div class="col-md-1 text-end"><label class="form-label">&nbsp;</label>'
      + '<button type="button" class="btn btn-outline-danger btn-sm d-block" onclick="removeDeliveryLine(\'delivery-row-' + n + '\')"><i class="ti ti-trash"></i></button></div>'
      + '</div>');

    setupDeliveryAutocomplete(n);
  }

  function setupDeliveryAutocomplete(n) {
    const input    = $('d-prod-in-' + n);
    const dropdown = $('d-prod-dd-' + n);
    if (!input || !dropdown) return;

    function showDeliveryDropdown() {
      const t = input.value.toLowerCase().trim();
      const matches = t.length === 0
        ? appState.cachedProducts
        : appState.cachedProducts.filter(function (p) {
            return p.name.toLowerCase().includes(t)
                || (p.productCode || '').toLowerCase().includes(t)
                || (p.sku || '').toLowerCase().includes(t);
          });
      renderDeliveryProductDropdown(dropdown, matches, n);
    }

    input.addEventListener('input', showDeliveryDropdown);
    input.addEventListener('focus', showDeliveryDropdown);
    document.addEventListener('click', function (e) {
      if (!input.contains(e.target) && !dropdown.contains(e.target)) {
        dropdown.classList.remove('show');
      }
    });
  }

  function renderDeliveryProductDropdown(dropdown, products, rowNum) {
    if (!products || products.length === 0) {
      dropdown.innerHTML = '<div class="product-dropdown-item" style="color:#999;cursor:default;">No products found</div>';
      dropdown.classList.add('show');
      return;
    }
    let html = '';
    products.slice(0, 50).forEach(function (p) {
      const wh1 = p.stockWh1 || 0, wh2 = p.stockWh2 || 0, wh3 = p.stockWh3 || 0;
      const total = wh1 + wh2 + wh3;
      const codeTag = p.productCode
        ? '<span class="product-code" style="margin-right:5px;font-size:10px;">' + escapeHtml(p.productCode) + '</span>'
        : '';
      let stockClass = 'ok';
      if (total <= 0) stockClass = 'critical';
      else if (total <= (p.thresholdCritical || 0)) stockClass = 'critical';
      else if (total <= (p.thresholdLow || 0)) stockClass = 'low';

      html += '<div class="product-dropdown-item" '
        + 'data-id="' + p.id + '" '
        + 'data-name="' + escapeHtml(p.name) + '" '
        + 'data-row="' + rowNum + '">'
        + '<div style="flex:1;">'
        + '<div class="product-name">' + codeTag + escapeHtml(p.name) + '</div>'
        + '<div style="font-size:10px;color:#888;">Total: <span class="product-stock ' + stockClass + '">' + total.toLocaleString() + ' pcs</span></div>'
        + '</div>'
        + '</div>';
    });
    dropdown.innerHTML = html;
    dropdown.classList.add('show');

    dropdown.querySelectorAll('.product-dropdown-item[data-id]').forEach(function (item) {
      item.addEventListener('click', function () {
        const rn = this.getAttribute('data-row');
        $('d-prod-in-' + rn).value = this.getAttribute('data-name');
        $('d-prod-id-' + rn).value = this.getAttribute('data-id');
        dropdown.classList.remove('show');
      });
    });
  }

  window.removeDeliveryLine = function (rowId) {
    const row = $(rowId); if (row) row.remove();
  };

  async function initDeliveryForm() {
    await loadProducts();

    const encEl = $('delivery-encoded-by');
    if (encEl) encEl.value = currentUserName();

    // Always refresh the PO dropdown with current incomplete POs
    const poSel = $('delivery-po-number');
    if (poSel) {
      poSel.innerHTML = '<option value="">— No linked PO (manual entry) —</option>';
      _deliveryPoCache = {};
      try {
        const token = localStorage.getItem('rrbm_token');
        const res = await fetch(API_BASE + '/api/purchase-orders', { headers: { 'Authorization': 'Bearer ' + token } });
        if (res.ok) {
          const pos = await res.json();
          (Array.isArray(pos) ? pos : [])
            .filter(function(p){ return p.status === 'INCOMPLETE' || p.status === 'PARTIALLY_RECEIVED'; })
            .forEach(function(p){
              // Only show POs that still have unfulfilled items
              var unfulfilled = (p.items || []).filter(function(it){ return !it.isFulfilled; });
              if (unfulfilled.length === 0) return;
              _deliveryPoCache[p.poNumber] = p;
              var opt = document.createElement('option');
              opt.value = p.poNumber;
              opt.textContent = p.poNumber + ' — ' + (p.vendorName || '');
              opt.dataset.vendor = p.vendorName || '';
              poSel.appendChild(opt);
            });
        }
      } catch(e) { /* silently skip — dropdown is optional */ }
      poSel.value = '';
    }

    if (appState.deliveryFormReady) return;

    if ($('delivery-supplier')) $('delivery-supplier').value = '';
    if ($('delivery-receipt'))  $('delivery-receipt').value = '';
    if ($('delivery-receiver')) $('delivery-receiver').value = '';
    if ($('delivery-verifier')) $('delivery-verifier').value = '';
    if ($('delivery-notes'))    $('delivery-notes').value = '';

    const c = $('delivery-items-container');
    if (c) { c.innerHTML = ''; appState.deliveryLineCounter = 0; }
    addDeliveryLineRow();

    const addBtn = $('delivery-add-line');
    if (addBtn) {
      addBtn.style.display = '';  // ensure visible on form reset
      const fresh = addBtn.cloneNode(true);
      addBtn.parentNode.replaceChild(fresh, addBtn);
      fresh.addEventListener('click', addDeliveryLineRow);
    }
    appState.deliveryFormReady = true;
  }

  window.onDeliveryPoChange = function(sel) {
    var opt     = sel.options[sel.selectedIndex];
    var supEl   = $('delivery-supplier');
    var addBtn  = $('delivery-add-line');
    var c       = $('delivery-items-container');

    if (sel.value && opt && opt.dataset.vendor) {
      if (supEl) supEl.value = opt.dataset.vendor;
      // Auto-populate line items from the selected PO
      var po = _deliveryPoCache[sel.value];
      if (po) {
        _populateDeliveryItemsFromPo(po);
        if (addBtn) addBtn.style.display = 'none'; // hide manual Add Line when PO selected
      }
    } else {
      // No PO selected — clear auto-rows and restore manual entry
      if (supEl) supEl.value = '';
      if (c) { c.innerHTML = ''; appState.deliveryLineCounter = 0; }
      addDeliveryLineRow();
      if (addBtn) addBtn.style.display = '';
    }
  };

  /** Resolve a PO item's productId from the cached product list by itemCode or name. */
  function _resolveProductIdForPoItem(poItem) {
    var prods = appState.cachedProducts || [];
    if (poItem.itemCode) {
      var byCode = prods.find(function(p){ return p.itemCode && p.itemCode.toLowerCase() === poItem.itemCode.toLowerCase(); });
      if (byCode) return byCode.id;
    }
    if (poItem.itemDescription) {
      var byName = prods.find(function(p){ return p.name && p.name.toLowerCase() === (poItem.itemDescription || '').toLowerCase(); });
      if (byName) return byName.id;
    }
    return null;
  }

  /** Build auto-populated delivery rows from a PO's unfulfilled items. */
  function _populateDeliveryItemsFromPo(po) {
    var c = $('delivery-items-container');
    if (!c) return;
    c.innerHTML = '';
    appState.deliveryLineCounter = 0;

    var unfulfilled = (po.items || []).filter(function(it){ return !it.isFulfilled; });
    unfulfilled.forEach(function(item, idx) {
      var n = 'po' + idx;
      var productId = _resolveProductIdForPoItem(item);
      var resolved  = productId ? '' : ' style="border-left:3px solid #F59E0B;"';
      var hint      = productId ? '' :
        '<div style="font-size:10px;color:#F59E0B;margin-top:2px;">&#9888; Product not found — select manually</div>';

      c.insertAdjacentHTML('beforeend',
        '<div class="row align-items-end mb-2 delivery-line delivery-line-po" id="delivery-row-' + n + '"' + resolved + '>' +
        // Product name (read-only label + hidden productId)
        '<div class="col-md-3">' +
          '<label class="form-label" style="font-size:11px;">Product</label>' +
          (productId
            ? '<div class="form-control" style="background:var(--surface-alt,#f8f9fa);font-size:12px;cursor:default;color:var(--text-primary);height:auto;min-height:38px;display:flex;align-items:center;">' +
              escapeHtml(item.itemDescription || '') + '</div>' +
              '<input type="hidden" class="delivery-product-id" value="' + productId + '">'
            // Fallback: searchable when product not found
            : '<div class="product-autocomplete-wrapper">' +
              '<input type="text" class="form-control delivery-product-input" id="d-prod-in-' + n + '" ' +
              'value="' + escapeHtml(item.itemDescription || '') + '" placeholder="Search product…" autocomplete="off">' +
              '<input type="hidden" class="delivery-product-id" id="d-prod-id-' + n + '" value="">' +
              '<div class="product-dropdown" id="d-prod-dd-' + n + '"></div>' +
              '</div>') +
          '<div style="font-size:10px;color:var(--text-muted);margin-top:2px;">' +
          (item.itemCode ? 'Code: ' + escapeHtml(item.itemCode) + ' &nbsp;&middot;&nbsp; ' : '') +
          'PO Qty: ' + (item.quantityOrdered || 0) + '</div>' +
          hint +
        '</div>' +
        // PO Qty (read-only reference)
        '<div class="col-md-1">' +
          '<label class="form-label" style="font-size:11px;">PO Qty</label>' +
          '<input type="number" class="form-control delivery-qty" value="' + (item.quantityOrdered || 1) + '" readonly ' +
          'style="background:var(--surface-alt,#f8f9fa);text-align:center;" title="Quantity from PO" />' +
        '</div>' +
        // Received
        '<div class="col-md-2">' +
          '<label class="form-label" style="font-size:11px;">Received <span class="text-danger">*</span></label>' +
          '<input type="number" class="form-control delivery-received" min="0" placeholder="Enter qty" />' +
        '</div>' +
        // Rejected
        '<div class="col-md-2">' +
          '<label class="form-label" style="font-size:11px;">Rejected</label>' +
          '<input type="number" class="form-control delivery-rejected" min="0" value="0" />' +
        '</div>' +
        // Warehouse
        '<div class="col-md-2">' +
          '<label class="form-label" style="font-size:11px;">Warehouse</label>' +
          '<select class="form-select delivery-wh">' +
            '<option value="wh1">WH1</option><option value="wh2">WH2</option><option value="wh3">Balagtas</option>' +
          '</select>' +
        '</div>' +
        // Delete button
        '<div class="col-md-1 text-end" style="padding-top:22px;">' +
          '<button type="button" class="btn btn-outline-danger btn-sm d-block" ' +
          'onclick="removeDeliveryLine(\'delivery-row-' + n + '\')" title="Remove this item">' +
          '<i class="ti ti-trash"></i></button>' +
        '</div>' +
        '</div>');

      // If product wasn't resolved, set up autocomplete on the fallback input
      if (!productId) {
        setupDeliveryAutocomplete(n);
      }
    });

    // Show a note if all items are mapped
    if (unfulfilled.length === 0) {
      c.innerHTML = '<div style="padding:12px;color:var(--text-muted);font-size:13px;">All items in this PO are already fulfilled.</div>';
    }
  }

  window.clearDeliveryForm = function () {
    appState.deliveryFormReady = false;
    initDeliveryForm();
  };

  window.submitDeliveryReceipt = async function () {
    const token = localStorage.getItem('rrbm_token');
    if (!token) { showToast('Please login first', 'error'); return; }

    const supplierName = (($('delivery-supplier') || {}).value || '').trim();
    if (!supplierName) { showToast('Supplier name is required', 'error'); return; }

    const receipt = (($('delivery-receipt') || {}).value || '').trim();
    if (!/^[A-Za-z0-9\-]{2,20}$/.test(receipt)) {
      showToast('Receipt number must be 2–20 characters (letters, numbers, hyphens)', 'error'); return;
    }

    const receiverName = (($('delivery-receiver') || {}).value || '').trim();
    if (!receiverName) { showToast('Received by is required', 'error'); return; }

    const rows = $$('.delivery-line');
    if (rows.length === 0) { showToast('Add at least one line item', 'error'); return; }

    const isPoBacked = !!($('delivery-po-number') || {}).value;
    const items = [];
    for (let i = 0; i < rows.length; i++) {
      const row        = rows[i];
      const prodIdEl   = row.querySelector('.delivery-product-id');
      const qtyEl      = row.querySelector('.delivery-qty');
      const receivedEl = row.querySelector('.delivery-received');
      const rejectedEl = row.querySelector('.delivery-rejected');
      const whEl       = row.querySelector('.delivery-wh');

      if (!prodIdEl || !prodIdEl.value) {
        showToast('Select a product on every line', 'error'); return;
      }

      const unitCostEl = row.querySelector('.delivery-unit-cost');
      const qty      = qtyEl      ? parseInt(qtyEl.value, 10)        : 0;
      const received = receivedEl ? parseInt(receivedEl.value, 10) || 0 : (isPoBacked ? 0 : qty);
      const rejected = rejectedEl ? parseInt(rejectedEl.value, 10) || 0 : 0;
      const unitCost = unitCostEl && unitCostEl.value ? parseFloat(unitCostEl.value) || null : null;

      if (!isPoBacked) {
        // Manual entry: qty and received must both be at least 1
        if (!qty || qty < 1) { showToast('Quantity must be at least 1', 'error'); return; }
        if (received < 1) { showToast('Enter a received quantity on every line before posting', 'error'); return; }
      } else {
        // PO-backed: skip lines where nothing was received or rejected
        if (received === 0 && rejected === 0) continue;
      }

      items.push({ productId: parseInt(prodIdEl.value, 10), quantity: qty || received || 1, received: received, rejected: rejected, warehouse: whEl ? whEl.value : 'wh1', unitCost: unitCost });
    }

    // PO-backed: warn if some lines were excluded, block if all excluded
    if (isPoBacked) {
      var skippedLineCount = rows.length - items.length;
      if (items.length === 0) {
        showToast('Enter at least one received quantity before submitting', 'error'); return;
      }
      if (skippedLineCount > 0) {
        showToast(skippedLineCount + ' line' + (skippedLineCount !== 1 ? 's' : '') + ' with no received qty were excluded from this receipt', 'warning');
      }
    }

    const poNumber = (($('delivery-po-number') || {}).value || '').trim() || null;
    const body = {
      receiptNumber: receipt,
      supplierName:  supplierName,
      receiverName:  receiverName,
      verifierName:  (($('delivery-verifier') || {}).value || '').trim() || null,
      encodedByName: (($('delivery-encoded-by') || {}).value || '').trim() || currentUserName(),
      notes:         (($('delivery-notes') || {}).value || '').trim() || null,
      poNumber:      poNumber,
      items:         items
    };

    try {
      const res = await fetch('' + API_BASE + '/api/products/delivery', {
        method: 'POST', headers: authHeaders(), body: JSON.stringify(body)
      });
      if (!res.ok) {
        let msg = 'Failed to post receipt';
        try { const d = await res.json(); msg = d.message || msg; } catch (e) {}
        showToast(msg, 'error'); return;
      }
      showToast('Stock updated from receipt ' + receipt, 'success');
      appState.deliveryFormReady = false;
      await loadProducts();
      initDeliveryForm();
      if (appState.currentView === 'purchase-orders') loadPurchaseOrders();
      if (typeof loadDeliveryReports === 'function') loadDeliveryReports(); // N-7: refresh delivery reports table
    } catch (err) {
      showToast('Connection error. Is the backend running?', 'error');
    }
  };

  // ================================================================
  // DELIVERY REPORTS
  // ================================================================
  window.renderDeliveryReports = async function (all) {
    const tb = $('delivery-rep-tbody');
    if (!tb) return;
    const token = localStorage.getItem('rrbm_token');
    if (!token) { tb.innerHTML = '<tr><td colspan="9" style="text-align:center;color:var(--text-muted);">Please login first</td></tr>'; return; }

    tb.innerHTML = '<tr><td colspan="9" style="text-align:center;color:var(--text-muted);">Loading…</td></tr>';

    let url = '' + API_BASE + '/api/reports/deliveries';
    if (!all) {
      const dateVal = ($('delivery-rep-date') || {}).value;
      if (dateVal) url += '/' + dateVal;
    }

    try {
      const res = await fetch(url, { headers: { 'Authorization': 'Bearer ' + token } });
      if (!res.ok) { tb.innerHTML = '<tr><td colspan="10" style="text-align:center;color:var(--text-muted);">Failed to load</td></tr>'; return; }
      const records = await res.json();
      _deliveryRecords = Array.isArray(records) ? records : [];
      if (!_deliveryRecords.length) { tb.innerHTML = '<tr><td colspan="9" style="text-align:center;color:var(--text-muted);padding:20px;">No deliveries found</td></tr>'; return; }

      tb.innerHTML = _deliveryRecords.map(function (r) {
        var isCancelled = r.status === 'CANCELLED';
        var statusBadge = '<span class="badge ' + (isCancelled ? 'badge-slow' : 'badge-selling') + '">'
          + escapeHtml(r.status || 'RECEIVED') + '</span>';
        var cancelBtn = isCancelled ? '' :
          '<button class="btn btn-sm" style="background:#EF4444;color:#fff;border:none;padding:4px 10px;border-radius:6px;font-size:11px;cursor:pointer;" '
          + 'onclick="cancelDelivery(' + r.id + ')"><i class="ti ti-x"></i> Cancel</button>';

        return '<tr>'
          + '<td><span class="product-code">' + escapeHtml(r.receiptNumber) + '</span></td>'
          + '<td>' + escapeHtml(r.supplierName || '—') + '</td>'
          + '<td>' + formatDate(r.reportDate || r.createdAt) + '</td>'
          + '<td><span style="font-family:monospace;font-size:11px;">' + escapeHtml(r.poNumber || '—') + '</span></td>'
          + '<td>' + (r.receivedBy || '—') + '</td>'
          + '<td>' + (r.verifiedBy || '—') + '</td>'
          + '<td>' + (r.encodedByName || '—') + '</td>'
          + '<td>' + statusBadge + '</td>'
          + '<td style="display:flex;gap:6px;align-items:center;">'
          +   '<button class="btn btn-secondary btn-sm" onclick="openDeliveryDetail(' + r.id + ')"><i class="ti ti-list-details"></i> View</button>'
          +   cancelBtn
          + '</td>'
          + '</tr>';
      }).join('');
    } catch (err) {
      tb.innerHTML = '<tr><td colspan="9" style="text-align:center;color:var(--text-muted);">Connection error</td></tr>';
    }
  };

  window.cancelDelivery = function (id) {
    var keyEl = $('cancel-delivery-key');
    var idEl  = $('cancel-delivery-id');
    if (keyEl) keyEl.value = '';
    if (idEl)  idEl.value  = id;
    openModal('modal-cancel-delivery');
    if (keyEl) setTimeout(function(){ keyEl.focus(); }, 120);
  };

  window.confirmCancelDelivery = async function () {
    var id  = parseInt(($('cancel-delivery-id') || {}).value || '0', 10);
    var key = (($('cancel-delivery-key') || {}).value || '').trim();
    if (!id)  { showToast('No delivery ID', 'error'); return; }
    if (!key) { showToast('Enter the master key to confirm cancellation', 'error'); return; }
    try {
      const res = await fetch(API_BASE + '/api/delivery-reports/' + id + '/cancel', {
        method: 'PATCH',
        headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
        body: JSON.stringify({ masterKey: key })
      });
      const d = await res.json().catch(function() { return {}; });
      if (!res.ok) { showToast(d.message || 'Cancel failed', 'error'); return; }
      closeModal('modal-cancel-delivery');
      showToast('Delivery cancelled — stock and PO items reversed', 'success');
      renderDeliveryReports(true);
    } catch (err) {
      showToast('Connection error', 'error');
    }
  };

  window.openDeliveryDetail = function (id) {
    var r = _deliveryRecords.find(function(x){ return x.id === id; });
    if (!r) { showToast('Record not found', 'error'); return; }

    var setText = function(elId, val){ var el = $(elId); if (el) el.textContent = val || '—'; };
    setText('dd-receipt',  r.receiptNumber);
    setText('dd-date',     formatDate(r.reportDate || r.createdAt));
    setText('dd-supplier', r.supplierName);
    setText('dd-po',       r.poNumber || '—');
    setText('dd-receiver', r.receivedBy);
    setText('dd-verifier', r.verifiedBy);
    setText('dd-encoder',  r.encodedByName);
    setText('dd-notes',    r.notes || '—');

    var fmt = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
    var items = r.items || [];
    var totalReceived = 0, totalRejected = 0;
    var totalLineCost = 0;

    var rows = items.map(function(item, idx){
      var received  = item.receivedQty  || item.quantity || 0;
      var rejected  = item.rejectedQty  || 0;
      var ordered   = item.quantity     || 0;
      var uc        = parseFloat(item.unitCost) || 0;
      var lineTotal = received * uc;
      totalReceived  += received;
      totalRejected  += rejected;
      totalLineCost  += lineTotal;
      return '<tr>'
        + '<td style="text-align:center;color:var(--text-muted);">' + (idx + 1) + '</td>'
        + '<td>' + escapeHtml(item.productName || '—') + '</td>'
        + '<td style="text-align:right;">' + ordered + '</td>'
        + '<td style="text-align:right;color:#10B981;font-weight:600;">' + received + '</td>'
        + '<td style="text-align:right;color:' + (rejected > 0 ? '#EF4444' : 'var(--text-muted)') + ';">' + rejected + '</td>'
        + '<td style="text-align:right;">' + fmt(uc) + '</td>'
        + '<td style="text-align:right;font-weight:600;">' + fmt(lineTotal) + '</td>'
        + '</tr>';
    }).join('') || '<tr><td colspan="7" style="text-align:center;color:var(--text-muted);">No items recorded</td></tr>';

    var tbody = $('dd-items-tbody');
    if (tbody) tbody.innerHTML = rows;

    var tfoot = $('dd-items-tfoot');
    if (tfoot) {
      tfoot.innerHTML = '<tr style="background:var(--bg-secondary);font-weight:700;">'
        + '<td colspan="3" style="text-align:right;font-size:11px;color:var(--text-muted);">Totals</td>'
        + '<td style="text-align:right;color:#10B981;">' + totalReceived + '</td>'
        + '<td style="text-align:right;color:' + (totalRejected > 0 ? '#EF4444' : 'var(--text-muted)') + ';">' + totalRejected + '</td>'
        + '<td></td>'
        + '<td style="text-align:right;">' + fmt(totalLineCost) + '</td>'
        + '</tr>';
    }

    $('modal-delivery-detail').classList.add('open');
  };

  window.viewNotes = function (notes) {
    const el = $('modal-notes-text');
    if (el) el.textContent = notes;
    $('modal-notes').classList.add('open');
  };

  // ================================================================
  // PRINT — Monthly Report (API-driven, all 9 endpoints)
  // ================================================================
  window.printMonthlyReport = async function () {
    var token = localStorage.getItem('rrbm_token');
    if (!token) { showToast('Please login first', 'error'); return; }

    var picker = $('rep-month-picker');
    var month  = picker ? picker.value : new Date().toISOString().slice(0, 7);
    if (!month) { showToast('Select a month first', 'error'); return; }

    // Capture live chart canvases as base64 images BEFORE opening print window
    // Charts must already be rendered on screen; blank = not yet rendered (scroll down first)
    var chartImages = {};
    ['chart-daily-revenue', 'chart-sales-vs-exp', 'chart-source-donut'].forEach(function(id) {
      var canvas = $(id);
      if (canvas && canvas.tagName === 'CANVAS' && canvas.width > 0) {
        try { chartImages[id] = canvas.toDataURL('image/png'); } catch(e) { chartImages[id] = null; }
      }
    });

    showToast('Preparing report…', 'success');

    var headers = { 'Authorization': 'Bearer ' + token };
    var base    = API_BASE + '/api/reports/';
    var q       = '?month=' + month;

    // ── Local helpers ──────────────────────────────────────────────
    function fmt(n) {
      return '&#8369;' + Number(n || 0).toLocaleString('en-PH', {
        minimumFractionDigits: 2, maximumFractionDigits: 2
      });
    }
    function num(n) { return Number(n || 0).toLocaleString('en-PH'); }
    function esc(s) {
      return String(s || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    }
    function box(label, value, color, extra) {
      return '<div class="stat-box">'
        + '<div class="stat-lbl">' + label + '</div>'
        + '<div class="stat-val" style="color:' + color + ';">' + value + '</div>'
        + (extra || '')
        + '</div>';
    }
    function heading(text) {
      return '<div class="sec-hdr">' + text + '</div>';
    }
    function tbl(hdrs, rows) {
      return '<table><thead><tr>'
        + hdrs.map(function (h) { return '<th>' + h + '</th>'; }).join('')
        + '</tr></thead><tbody>' + rows + '</tbody></table>';
    }

    try {
      var results = await Promise.all([
        fetch(base + 'insights-summary'      + q, { headers: headers }),
        fetch(base + 'accounting-summary'    + q, { headers: headers }),
        fetch(base + 'source-breakdown'      + q, { headers: headers }),
        fetch(base + 'top-agents'            + q, { headers: headers }),
        fetch(base + 'top-dates'             + q, { headers: headers }),
        fetch(base + 'pizza-summary'         + q, { headers: headers }),
        fetch(base + 'hot-selling'           + q, { headers: headers }),
        fetch(base + 'delivery-fees'         + q, { headers: headers }),
        fetch(base + 'expense-breakdown'     + q, { headers: headers }),
        fetch(base + 'ecommerce-breakdown'   + q, { headers: headers }),
        fetch(base + 'non-pizza-summary'     + q, { headers: headers }),
        fetch(API_BASE + '/api/payables',         { headers: headers }),
        fetch(API_BASE + '/api/orders/collections',{ headers: headers })
      ]);

      var ins  = results[0].ok  ? await results[0].json()  : {};
      var acc  = results[1].ok  ? await results[1].json()  : {};
      var src  = results[2].ok  ? await results[2].json()  : {};
      var agt  = results[3].ok  ? await results[3].json()  : {};
      var dts  = results[4].ok  ? await results[4].json()  : {};
      var pz   = results[5].ok  ? await results[5].json()  : {};
      var hot  = results[6].ok  ? await results[6].json()  : {};
      var df   = results[7].ok  ? await results[7].json()  : {};
      var exp  = results[8].ok  ? await results[8].json()  : {};
      var ecom = results[9].ok  ? await results[9].json()  : { totalOrders: 0, totalRevenue: 0, platforms: [] };
      var npz  = results[10].ok ? await results[10].json() : { topProducts: [] };
      var payAll = results[11].ok ? await results[11].json() : [];
      var collAll= results[12].ok ? await results[12].json() : [];

      // ── 1. Summary stat boxes ───────────────────────────────────
      var prevChange = '';
      if (ins.prevMonthRevenue && ins.prevMonthRevenue > 0) {
        var chg    = (ins.totalRevenue || 0) - ins.prevMonthRevenue;
        var chgPct = ((chg / ins.prevMonthRevenue) * 100).toFixed(1);
        prevChange = '<p style="margin:4px 0 0;font-size:11px;color:'
          + (chg >= 0 ? '#27500A' : '#791F1F') + ';">'
          + (chg >= 0 ? '&#9650;' : '&#9660;') + ' ' + Math.abs(chgPct)
          + '% vs ' + (ins.prevMonth || 'prev month') + '</p>';
      }

      var summaryBoxes =
        '<div style="display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-bottom:20px;">'
        + box('Total orders',   num(ins.totalOrders),   '#1A1208', '')
        + box('Total revenue',  fmt(ins.totalRevenue),  '#633806', prevChange)
        + box('Items sold',     num(ins.totalItemsSold),'#1A1208', '')
        + box('Total expenses', fmt(ins.totalExpenses), '#791F1F', '')
        + '</div>';

      // ── 2. Accounting summary boxes ────────────────────────────
      var accBoxes =
        '<div style="display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-bottom:20px;">'
        + box('Gross sales',     fmt(acc.grossSales),       '#27500A', '')
        + box('Refunds & voids', fmt(acc.refundsTotal),     '#791F1F', '')
        + box('Adjustments',     fmt(acc.adjustmentsTotal), '#633806', '')
        + box('Net sales',       fmt(acc.netSales),         '#042C53', '')
        + '</div>';

      // ── 3. Month-over-month ────────────────────────────────────
      var momSection = '';
      if (ins.prevMonth) {
        var revChange = (ins.totalRevenue || 0) - (ins.prevMonthRevenue || 0);
        momSection =
          heading('Month-over-month comparison')
          + '<div style="display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:20px;">'
          + box('This month orders',  num(ins.totalOrders),  '#1A1208',
              '<p style="font-size:11px;color:#888;margin:3px 0 0;">Prev (' + ins.prevMonth + '): ' + num(ins.prevMonthOrders) + '</p>')
          + box('This month revenue', fmt(ins.totalRevenue), '#633806',
              '<p style="font-size:11px;color:#888;margin:3px 0 0;">Prev: ' + fmt(ins.prevMonthRevenue) + '</p>')
          + box('Revenue change',
              (revChange >= 0 ? '+' : '') + fmt(revChange),
              revChange >= 0 ? '#27500A' : '#791F1F', '')
          + '</div>';
      }

      // ── 4. Top 5 products ──────────────────────────────────────
      var topProdSection = '';
      var topProds = ins.topProducts || [];
      if (topProds.length > 0) {
        var tpRows = topProds.map(function (r, i) {
          return '<tr>'
            + '<td>' + (i + 1) + '</td>'
            + '<td style="font-weight:500;">' + esc(r.name) + '</td>'
            + '<td style="text-align:right;">' + num(r.qty) + ' pcs</td>'
            + '<td style="text-align:right;">' + fmt(r.revenue) + '</td>'
            + '<td style="text-align:right;">' + Number(r.pct || 0).toFixed(1) + '%</td>'
            + '</tr>';
        }).join('');
        topProdSection = heading('Top 5 products this month')
          + tbl(['#','Product','Qty sold','Revenue','Share'], tpRows);
      }

      // ── 5. Source breakdown ────────────────────────────────────
      var srcSection = '';
      var srcTable   = '';   // table only (no heading) — used next to donut image in PDF
      var srcList = (src.breakdown || []);
      if (srcList.length > 0) {
        var srcLabel = { WALK_IN:'Walk-in', ECOMMERCE:'E-commerce',
          RESELLER:'Reseller', AGENT:'Agent', DISTRIBUTOR:'Distributor',
          FACEBOOK_PAGE:'Facebook Page' };
        var srcRows = srcList.map(function (r) {
          return '<tr>'
            + '<td>' + esc(srcLabel[r.source] || r.source) + '</td>'
            + '<td style="text-align:right;">' + num(r.orderCount) + '</td>'
            + '<td style="text-align:right;">' + fmt(r.revenue) + '</td>'
            + '<td style="text-align:right;">' + Number(r.pct || 0).toFixed(1) + '%</td>'
            + '</tr>';
        }).join('');
        srcTable   = tbl(['Source','Orders','Revenue','%'], srcRows);
        srcSection = heading('Orders by source') + srcTable;
      }

      // ── 5b. E-commerce breakdown ───────────────────────────────
      var ecomSection = '';
      var ecomPlatforms = ecom.platforms || [];
      if (ecomPlatforms.length > 0) {
        var platLabel = { SHOPEE: 'Shopee', TIKTOK: 'TikTok Shop', LAZADA: 'Lazada' };
        var ecomRows = ecomPlatforms.map(function(p) {
          return '<tr>'
            + '<td style="font-weight:500;">' + esc(platLabel[p.platform] || p.platform) + '</td>'
            + '<td style="text-align:right;">' + num(p.orderCount) + '</td>'
            + '<td style="text-align:right;">' + fmt(p.revenue) + '</td>'
            + '<td style="text-align:right;">' + Number(p.percentage || 0).toFixed(1) + '%</td>'
            + '<td style="text-align:right;">' + fmt(p.avgOrder) + '</td>'
            + '</tr>';
        }).join('');
        ecomSection = heading('E-commerce breakdown — Total: ' + fmt(ecom.totalRevenue))
          + tbl(['Platform', 'Orders', 'Revenue', '%', 'Avg. Order'], ecomRows);
      }

      // ── 6. Top agents ──────────────────────────────────────────
      var agtSection = '';
      var agtList = (agt.agents || []);
      if (agtList.length > 0) {
        var medals = ['&#127945;','&#127946;','&#127947;'];
        var agtRows = agtList.map(function (r, i) {
          return '<tr>'
            + '<td style="text-align:center;">' + (medals[i] || (i + 1)) + '</td>'
            + '<td style="font-weight:500;">' + esc(r.agentName) + '</td>'
            + '<td style="font-size:11px;color:#555;">' + esc(r.source) + '</td>'
            + '<td style="text-align:right;">' + num(r.orderCount) + '</td>'
            + '<td style="text-align:right;font-weight:500;">' + fmt(r.revenue) + '</td>'
            + '</tr>';
        }).join('');
        agtSection = heading('Top agents & resellers')
          + tbl(['#','Name','Type','Orders','Revenue'], agtRows);
      }

      // ── 7. Top 3 dates ─────────────────────────────────────────
      var dtsSection = '';
      var dtsList = (dts.dates || []);
      if (dtsList.length > 0) {
        var medals2 = ['&#127945;','&#127946;','&#127947;'];
        var dtsRows = dtsList.map(function (r, i) {
          return '<tr>'
            + '<td style="text-align:center;">' + (medals2[i] || (i+1)) + '</td>'
            + '<td style="font-weight:500;">' + esc(r.date) + '</td>'
            + '<td style="text-align:right;">' + num(r.orderCount) + ' orders</td>'
            + '<td style="text-align:right;font-weight:500;">' + fmt(r.revenue) + '</td>'
            + '</tr>';
        }).join('');
        dtsSection = heading('Top 3 highest sales dates')
          + tbl(['#','Date','Orders','Revenue'], dtsRows);
      }

      // ── 8. Pizza box summary ───────────────────────────────────
      var pzSection = heading('Pizza box summary — ' + num(pz.totalQty) + ' pcs total');
      var pzList = (pz.top5 || []);
      if (pzList.length > 0) {
        var pzRows = pzList.map(function (r, i) {
          return '<tr>'
            + '<td>' + (i + 1) + '</td>'
            + '<td>' + esc(r.productName) + '</td>'
            + '<td style="text-align:right;font-weight:500;">' + num(r.qty) + ' pcs</td>'
            + '<td style="text-align:right;">' + fmt(r.revenue) + '</td>'
            + '</tr>';
        }).join('');
        pzSection += tbl(['#','Pizza box','Qty sold','Revenue'], pzRows);
      } else {
        pzSection += '<p style="color:#888;font-size:12px;margin-bottom:16px;">No pizza box sales this month.</p>';
      }

      // ── 9. Hot & selling items ─────────────────────────────────
      var hotSection = '';
      var hotList = (hot.items || []);
      if (hotList.length > 0) {
        var hotRows = hotList.map(function (r, i) {
          var isHot = r.sellingTag === 'HOT';
          return '<tr>'
            + '<td>' + (i + 1) + '</td>'
            + '<td style="font-weight:500;">' + esc(r.productName) + '</td>'
            + '<td><span style="font-size:10px;padding:1px 6px;border-radius:3px;background:'
              + (isHot ? '#FAEEDA' : '#EAF3DE') + ';color:'
              + (isHot ? '#633806' : '#27500A') + ';">'
              + esc(r.sellingTag) + '</span></td>'
            + '<td style="text-align:right;">' + num(r.qty) + ' pcs</td>'
            + '<td style="text-align:right;">' + fmt(r.revenue) + '</td>'
            + '</tr>';
        }).join('');
        hotSection = heading('Hot & selling items')
          + tbl(['#','Product','Tag','Qty sold','Revenue'], hotRows);
      }

      // ── 10. Delivery fees ──────────────────────────────────────
      var dfSection = heading('Delivery fees — Total: ' + fmt(df.totalFees));
      var dfList = (df.orders || []);
      if (dfList.length > 0) {
        var dfRows = dfList.map(function (r) {
          return '<tr>'
            + '<td style="font-family:monospace;font-size:11px;">' + esc(r.orderId) + '</td>'
            + '<td>' + esc(r.customerName) + '</td>'
            + '<td>' + esc(r.date) + '</td>'
            + '<td style="text-align:right;font-weight:500;">' + fmt(r.deliveryFee) + '</td>'
            + '</tr>';
        }).join('');
        dfSection += tbl(['Order #','Customer','Date','Fee'], dfRows);
      } else {
        dfSection += '<p style="color:#888;font-size:12px;margin-bottom:16px;">No delivery fees this month.</p>';
      }

      // ── 11. Expense breakdown ──────────────────────────────────
      var expSection = heading('Monthly expense breakdown — Total: ' + fmt(exp.grandTotal));
      var expList = (exp.breakdown || []);
      if (expList.length > 0) {
        var expRows = expList.map(function (r) {
          var pct = exp.grandTotal > 0
            ? ((r.totalAmount / exp.grandTotal) * 100).toFixed(1) : '0.0';
          return '<tr>'
            + '<td style="font-weight:500;">' + esc(r.description) + '</td>'
            + '<td style="text-align:right;font-weight:500;">' + fmt(r.totalAmount) + '</td>'
            + '<td style="text-align:right;color:#555;">' + num(r.count) + '&times;</td>'
            + '<td style="text-align:right;">' + pct + '%</td>'
            + '</tr>';
        }).join('');
        expSection += tbl(['Expense type','Total','Times','%'], expRows);
      } else {
        expSection += '<p style="color:#888;font-size:12px;margin-bottom:16px;">No expenses this month.</p>';
      }

      // ── 12. Non-pizza items full breakdown ─────────────────────
      var npzSection = heading('Non-pizza items — product breakdown');
      var npzProds = (npz.topProducts || []);
      if (npzProds.length > 0) {
        var npzRows = npzProds.map(function(r, i) {
          return '<tr>'
            + '<td style="text-align:center;color:#888;">' + (i+1) + '</td>'
            + '<td style="font-weight:500;">' + esc(r.productName || '—') + '</td>'
            + '<td style="text-align:right;color:#F59E0B;">' + num(r.directQty || 0) + '</td>'
            + '<td style="text-align:right;color:#8B5CF6;">' + num(r.ecomQty || 0) + '</td>'
            + '<td style="text-align:right;font-weight:600;">' + num(r.totalQty || 0) + '</td>'
            + '<td style="text-align:right;">' + fmt(r.revenue || 0) + '</td>'
            + '</tr>';
        }).join('');
        npzSection += tbl(['#','Product','Direct Qty','E-com Qty','Total Qty','Revenue'], npzRows);
      } else {
        npzSection += '<p style="color:#888;font-size:12px;margin-bottom:16px;">No non-pizza items this month.</p>';
      }

      // ── 13. Supplier payables outstanding ──────────────────────
      var pendingPayables = (payAll || []).filter(function(p){ return p.status === 'PENDING'; });
      var paySection = heading('Supplier payables outstanding');
      if (pendingPayables.length > 0) {
        var payTotal = pendingPayables.reduce(function(s,p){ return s + Number(p.totalAmount||0); }, 0);
        paySection += '<p style="font-size:12px;color:#555;margin-bottom:8px;">Total outstanding: '
          + fmt(payTotal) + ' across ' + pendingPayables.length + ' payable(s).</p>';
        var payRows = pendingPayables.map(function(p) {
          var created = p.createdAt ? new Date(p.createdAt) : null;
          var days = created ? Math.floor((Date.now() - created.getTime()) / 86400000) : '—';
          return '<tr>'
            + '<td style="font-family:monospace;font-size:11px;">' + esc(p.receiptNumber||'—') + '</td>'
            + '<td>' + esc(p.supplierName||'—') + '</td>'
            + '<td style="text-align:right;font-weight:600;color:#791F1F;">' + fmt(p.totalAmount) + '</td>'
            + '<td>' + (created ? created.toLocaleDateString('en-PH') : '—') + '</td>'
            + '<td style="text-align:center;">' + (typeof days==='number' ? days + 'd' : '—') + '</td>'
            + '</tr>';
        }).join('');
        paySection += tbl(['Receipt #','Supplier','Amount','Date Created','Days Outstanding'], payRows);
      } else {
        paySection += '<p style="color:#10B981;font-size:12px;margin-bottom:16px;">No outstanding payables — all cleared.</p>';
      }

      // ── 14. Pending collections ─────────────────────────────────
      var collSection = heading('Pending collections');
      var payModeMap = { CASH:'Cash', COD:'COD', GCASH:'GCash', PAYMAYA:'PayMaya',
                         BANK_TRANSFER:'Bank Transfer', BANK_DEPOSIT:'Bank Deposit', ONLINE:'Online' };
      if (collAll && collAll.length > 0) {
        var collTotal = collAll.reduce(function(s,o){ return s + Number(o.total||0); }, 0);
        collSection += '<p style="font-size:12px;color:#555;margin-bottom:8px;">Total uncollected: '
          + fmt(collTotal) + ' across ' + collAll.length + ' order(s).</p>';
        var collRows = collAll.map(function(o) {
          var created = o.createdAt ? new Date(o.createdAt) : null;
          var dateStr = created ? created.toLocaleDateString('en-PH',{year:'numeric',month:'short',day:'numeric'}) : '—';
          var days = created ? Math.floor((Date.now() - created.getTime()) / 86400000) : '—';
          return '<tr>'
            + '<td style="font-size:11px;">' + dateStr + '</td>'
            + '<td style="font-family:monospace;font-size:11px;">' + esc(ecomOrderRef(o)||o.id||'—') + '</td>'
            + '<td>' + esc(o.customerName||'—') + '</td>'
            + '<td>' + (payModeMap[o.paymentMode]||o.paymentMode||'—') + '</td>'
            + '<td style="text-align:right;font-weight:600;">' + fmt(o.total) + '</td>'
            + '<td style="text-align:center;">' + (typeof days==='number' ? days + 'd' : '—') + '</td>'
            + '</tr>';
        }).join('');
        collSection += tbl(['Order Date','Order ID','Customer','Payment','Total','Days Outstanding'], collRows);
      } else {
        collSection += '<p style="color:#10B981;font-size:12px;margin-bottom:16px;">No pending collections — all orders settled.</p>';
      }

      // ── Assemble document ──────────────────────────────────────
      var w = window.open('', '_blank', 'width=960,height=820');
      if (!w) { showToast('Pop-up blocked — allow pop-ups and try again', 'error'); return; }

      w.document.write(
        '<!DOCTYPE html><html><head><meta charset="UTF-8">'
        + '<title>Monthly Report — ' + month + '</title>'
        + '<style>'
        + 'body{font-family:Arial,sans-serif;padding:32px;color:#1A1208;font-size:13px;max-width:900px;margin:0 auto;}'
        + '.co-hdr{display:flex;align-items:center;gap:14px;margin-bottom:6px;}'
        + '.logo-sq{width:40px;height:40px;border-radius:9px;background:#D4860A;display:flex;align-items:center;justify-content:center;font-size:16px;font-weight:700;color:#2C1A0E;flex-shrink:0;}'
        + '.co-name{font-size:16px;font-weight:700;margin:0;}'
        + '.co-sub{font-size:11px;color:#666;margin:2px 0 0;}'
        + '.rpt-title{font-size:22px;font-weight:700;margin:12px 0 2px;}'
        + '.rpt-meta{font-size:11px;color:#666;margin-bottom:20px;}'
        + '.divider{border:none;border-top:1px solid #e0d4c0;margin:20px 0;}'
        + '.sec-hdr{font-size:13px;font-weight:700;border-bottom:2px solid #D4860A;padding-bottom:5px;margin-bottom:12px;color:#2C1A0E;}'
        + '.stat-box{background:#FAF7F2;border:1px solid #e0d4c0;border-radius:8px;padding:10px 14px;}'
        + '.stat-lbl{font-size:10px;color:#888;text-transform:uppercase;letter-spacing:.05em;margin-bottom:3px;}'
        + '.stat-val{font-size:18px;font-weight:700;}'
        + 'table{width:100%;border-collapse:collapse;margin-bottom:20px;}'
        + 'th{background:#FAF7F2;padding:7px 10px;border:1px solid #ddd;font-size:11px;text-align:left;color:#666;}'
        + 'td{padding:7px 10px;border:1px solid #ddd;font-size:12px;}'
        + 'tr:nth-child(even) td{background:#FDFBF8;}'
        + '.footer{margin-top:32px;padding-top:14px;border-top:1px solid #e0d4c0;display:flex;justify-content:space-between;font-size:11px;color:#888;}'
        + '@media print{button{display:none!important;}.page-break{page-break-before:always;}}'
        + '</style></head><body>'
        + '<div class="co-hdr">'
        + '<div class="logo-sq">RB</div>'
        + '<div><p class="co-name">RRBM Packaging Supplies and Trading</p>'
        + '<p class="co-sub">116 Santan St., Fortune, Marikina City &nbsp;&middot;&nbsp; +63 966 846 9993</p></div>'
        + '</div>'
        + '<div class="rpt-title">Monthly Report</div>'
        + '<div class="rpt-meta">Period: ' + month + ' &nbsp;&middot;&nbsp; Generated: '
        + new Date().toLocaleString('en-PH') + '</div>'
        + '<hr class="divider">'
        + heading('Summary')
        + summaryBoxes
        + heading('Accounting summary')
        + accBoxes

        // ── Chart images (captured from live canvas before this window opened) ──
        + (chartImages['chart-daily-revenue']
           ? heading('Daily revenue breakdown')
             + '<img src="' + chartImages['chart-daily-revenue']
             + '" style="width:100%;border-radius:6px;border:1px solid #e0d4c0;margin-bottom:20px;" alt="Daily revenue chart" />'
           : '')
        + (chartImages['chart-sales-vs-exp']
           ? heading('Sales vs expenses — daily trend')
             + '<img src="' + chartImages['chart-sales-vs-exp']
             + '" style="width:100%;border-radius:6px;border:1px solid #e0d4c0;margin-bottom:20px;" alt="Sales vs expenses chart" />'
           : '')
        + (chartImages['chart-source-donut']
           ? heading('Orders by source')
             + '<img src="' + chartImages['chart-source-donut']
             + '" style="width:48%;border-radius:6px;border:1px solid #e0d4c0;margin-bottom:12px;" alt="Source breakdown donut" />'
             + srcTable     // table immediately below the donut — no confusion about what it shows
           : srcSection)    // fallback: heading + table when no chart image was captured

        + momSection
        + topProdSection
        + ecomSection
        + agtSection
        + dtsSection
        + pzSection
        + hotSection
        + dfSection
        + expSection
        + npzSection
        + paySection
        + collSection
        + '<div class="footer">'
        + '<span>RRBM Management System &nbsp;&middot;&nbsp; Confidential &nbsp;&middot;&nbsp; Internal use only</span>'
        + '<span>Monthly Report &mdash; ' + month + '</span>'
        + '</div>'
        + '<script>window.onload=function(){window.print();}<\/script>'
        + '</body></html>'
      );
      w.document.close();

    } catch (err) {
      showToast('Error generating report: ' + err.message, 'error');
    }
  };

  // ================================================================
  // PRINT — Delivery Reports
  // ================================================================
  window.printDeliveryReports = function () {
    const tb = $('delivery-rep-tbody');
    if (!tb || tb.children.length === 0) { showToast('No data to print', 'error'); return; }

    const dateLabel = ($('delivery-rep-date') || {}).value || 'All';
    const rows = Array.from(tb.querySelectorAll('tr')).map(function (tr) {
      return Array.from(tr.querySelectorAll('td')).slice(0, 8) // skip Notes column
        .map(function (td) { return '<td style="padding:6px 10px;border:1px solid #ddd;font-size:12px;">' + td.innerHTML + '</td>'; })
        .join('');
    }).map(function (r) { return '<tr>' + r + '</tr>'; }).join('');

    const w = window.open('', '_blank', 'width=900,height=650');
    w.document.write(`
      <!DOCTYPE html><html><head><meta charset="UTF-8">
      <title>Delivery Reports — ${dateLabel}</title>
      <style>
        body { font-family: Arial, sans-serif; padding: 24px; color: #111; }
        h2 { margin: 0 0 4px; } p { margin: 0 0 16px; font-size: 12px; color: #555; }
        table { width: 100%; border-collapse: collapse; }
        th { background: #f5f5f5; padding: 7px 10px; border: 1px solid #ddd; font-size: 12px; text-align: left; }
        @media print { button { display: none !important; } }
      </style></head><body>
      <h2>RRBM Packaging Supplies and Trading</h2>
      <p>Delivery Reports — ${dateLabel} &nbsp;|&nbsp; Printed: ${new Date().toLocaleString('en-PH')}</p>
      <table>
        <thead><tr>
          <th>Receipt #</th><th>Supplier</th><th>Date</th><th>Received By</th>
          <th>Verified By</th><th>Encoded By</th><th>Products</th><th>Total Qty</th>
        </tr></thead>
        <tbody>${rows}</tbody>
      </table>
      <script>window.onload=function(){window.print();}<\/script>
      </body></html>`);
    w.document.close();
  };

  // ================================================================
  // PRINT — Daily / Monthly Reports summary
  // ================================================================
  window.printDailyReport = async function () {
    const token = localStorage.getItem('rrbm_token');
    if (!token) { showToast('Please login first', 'error'); return; }

    // Fetch last 30 days of reports
    const end   = new Date(); end.setDate(end.getDate() - 1);
    const start = new Date(); start.setDate(start.getDate() - 30);
    const fmt   = function (d) { return d.toISOString().split('T')[0]; };

    try {
      const res = await fetch('' + API_BASE + '/api/reports/range?start=' + fmt(start) + '&end=' + fmt(end),
        { headers: { 'Authorization': 'Bearer ' + token } });
      if (!res.ok) { showToast('Failed to fetch report data', 'error'); return; }
      const reports = await res.json();

      if (!reports || reports.length === 0) { showToast('No closed reports in the last 30 days', 'error'); return; }

      const rows = reports.map(function (r) {
        return '<tr>'
          + '<td>' + (r.reportDate || '') + '</td>'
          + '<td style="text-align:right;">₱' + Number(r.totalRevenue || 0).toLocaleString('en-PH', {minimumFractionDigits:2}) + '</td>'
          + '<td style="text-align:right;">' + (r.totalOrders || 0) + '</td>'
          + '<td style="text-align:right;">' + (r.totalCancelled || 0) + '</td>'
          + '<td style="text-align:right;">' + (r.totalItemsSold || 0).toLocaleString() + '</td>'
          + '<td>' + (r.topProduct || '—') + ' ×' + (r.topProductQty || 0) + '</td>'
          + '</tr>';
      }).join('');

      const grandTotal = reports.reduce(function (sum, r) { return sum + Number(r.totalRevenue || 0); }, 0);

      const w = window.open('', '_blank', 'width=900,height=650');
      w.document.write(`
        <!DOCTYPE html><html><head><meta charset="UTF-8">
        <title>Sales Report</title>
        <style>
          body { font-family: Arial, sans-serif; padding: 24px; color: #111; }
          h2 { margin: 0 0 4px; } p { margin: 0 0 16px; font-size: 12px; color: #555; }
          table { width: 100%; border-collapse: collapse; }
          th { background: #f5f5f5; padding: 7px 10px; border: 1px solid #ddd; font-size: 12px; text-align: left; }
          td { padding: 6px 10px; border: 1px solid #ddd; font-size: 12px; }
          tfoot td { font-weight: 700; background: #fafafa; }
          @media print { button { display: none !important; } }
        </style></head><body>
        <h2>RRBM Packaging Supplies and Trading</h2>
        <p>Daily Sales Report — Last 30 Days &nbsp;|&nbsp; Printed: ${new Date().toLocaleString('en-PH')}</p>
        <table>
          <thead><tr>
            <th>Date</th><th>Revenue</th><th>Orders</th><th>Cancelled</th><th>Items Sold</th><th>Top Product</th>
          </tr></thead>
          <tbody>${rows}</tbody>
          <tfoot><tr>
            <td>TOTAL</td>
            <td style="text-align:right;">₱${Number(grandTotal).toLocaleString('en-PH',{minimumFractionDigits:2})}</td>
            <td colspan="4"></td>
          </tr></tfoot>
        </table>
        <script>window.onload=function(){window.print();}<\/script>
        </body></html>`);
      w.document.close();
    } catch (err) {
      showToast('Connection error', 'error');
    }
  };

  // ================================================================
  // ACTIVITY LOG — fixed ID column (show userId, not entityId)
  // ================================================================
  window.renderActivityLog = async function (today) {
    const tb = $('activity-log-tbody');
    if (!tb) return;
    const token = localStorage.getItem('rrbm_token');
    if (!token) { tb.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);">Please login first</td></tr>'; return; }

    tb.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);">Loading…</td></tr>';

    let url;
    if (today) {
      url = '' + API_BASE + '/api/activity-log/today';
      const todayStr = new Date().toISOString().split('T')[0];
      if ($('activity-log-date')) $('activity-log-date').value = todayStr;
    } else {
      const dateVal = ($('activity-log-date') || {}).value;
      url = dateVal
        ? '' + API_BASE + '/api/activity-log/' + dateVal
        : '' + API_BASE + '/api/activity-log/today';
    }

    try {
      const res = await fetch(url, { headers: { 'Authorization': 'Bearer ' + token } });
      if (!res.ok) { tb.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);">Failed to load</td></tr>'; return; }
      const logs = await res.json();
      if (!logs.length) { tb.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);padding:20px;">No activity for this date</td></tr>'; return; }

      const actionBadge = function (action) {
        const map = {
          'ADD_PRODUCT': 'badge-ok', 'RECEIVE_STOCK': 'badge-ok',
          'CANCEL_ORDER': 'badge-crit', 'CLOSE_DAILY_SALES': 'badge-honey',
          'LOGIN': 'badge-low', 'LOGOUT': 'badge-low',
          'UPDATE_PRODUCT_TAG': 'badge-low', 'CREATE_ORDER': 'badge-ok',
          'UPDATE_USER_ROLE':        'badge-honey',
          'CREATE_USER':             'badge-ok',
          'UPDATE_USER_STATUS':      'badge-low',
          'ORDER_ON_HOLD':           'badge-honey',
          'ORDER_RESUMED':           'badge-ok',
          'ORDER_STATUS_UPDATED':    'badge-low',
          'UPDATE_USER_PERMISSIONS': 'badge-honey',
          'DELIVER_ORDER':           'badge-ok',
          'EXPENSE_RECORDED':        'badge-honey',
          'EDIT_PRODUCT':            'badge-low',
          'CHANGE_MASTER_KEY':       'badge-crit',
          'REFUND':                  'badge-crit',
          'VOID':                    'badge-crit',
          'ADJUSTMENT':              'badge-low',
          'PAYABLE_STATUS_CHANGED':  'badge-honey',
          'DELETE_PAYABLE':          'badge-crit',
          'ASSIGN_SECURITY_KEY':     'badge-honey',
        };
        const cls = map[action] || 'badge-low';
        return '<span class="badge ' + cls + '" style="font-size:10px;">' + action.replace(/_/g, ' ') + '</span>';
      };

      tb.innerHTML = logs.map(function (l) {
        // Entity/Ref column: show entityType + entityId together
        const entityRef = l.entityType
          ? '<span style="font-size:11px;color:var(--text-muted);">' + l.entityType + '</span>'
            + (l.entityId ? '<br><code style="font-size:10px;">' + l.entityId + '</code>' : '')
          : '—';
        // User ID column: show the actor's userId (employee ID)
        const userIdDisplay = l.userId
          ? '<code style="font-size:10px;">' + l.userId + '</code>'
          : '<span style="color:#ccc;">—</span>';

        return '<tr>'
          + '<td style="font-size:11px;color:var(--text-muted);">' + formatTime(l.createdAt) + '</td>'
          + '<td style="font-size:12px;">' + (l.userName || '—') + '</td>'
          + '<td>' + actionBadge(l.action) + '</td>'
          + '<td style="font-size:12px;max-width:260px;">' + (l.description || '—') + '</td>'
          + '<td style="font-size:11px;">' + entityRef + '</td>'
          + '<td>' + userIdDisplay + '</td>'
          + '</tr>';
      }).join('');
    } catch (err) {
      tb.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);">Connection error</td></tr>';
    }
  };

  // ================================================================
  // ================================================================
  // EXPENSES
  // ================================================================
  // EXPENSE CATEGORY HELPERS
  // ================================================================
  var _expCatData = null; // cached { primaries: [...] }

  async function loadExpenseCategories() {
    if (_expCatData) return _expCatData;
    try {
      var res = await fetch(API_BASE + '/api/expense-categories');
      if (!res.ok) return null;
      _expCatData = await res.json();
      return _expCatData;
    } catch (e) { return null; }
  }

  window.onExpensePrimaryChange = function (primarySel) {
    var subSel = $('exp-sub-cat');
    if (!subSel || !_expCatData) return;
    var code = primarySel ? primarySel.value : '';
    var prim = (_expCatData.primaries || []).find(function (p) { return p.code === code; });
    var subs = prim ? (prim.subcategories || []) : [];
    subSel.innerHTML = '<option value="">Select sub-category…</option>';
    subs.forEach(function (s) {
      var opt = document.createElement('option');
      opt.value = s.id;
      opt.textContent = s.name;
      subSel.appendChild(opt);
    });
  };

  window.applyExpensePreset = async function (preset) {
    var PRESETS = {
      'office-rent': { primary: 'FACILITY',   sub: 'Monthly Office Rent' },
      'electric':    { primary: 'UTILITY',     sub: 'Electric Bill' },
      'internet':    { primary: 'UTILITY',     sub: 'Internet Bill (ISP)' },
      'water':       { primary: 'UTILITY',     sub: 'Water Utility Bill' },
      'gas':         { primary: 'OPERATIONS',  sub: 'Gas Allowance' },
      'food':        { primary: 'PERSONNEL',   sub: 'Food Allowance' },
      'delivery':    { primary: 'OPERATIONS',  sub: 'Delivery Budget' },
      'supplies':    { primary: 'SUPPLY',      sub: 'Office Supplies' },
      'shipping':    { primary: 'OPERATIONS',  sub: 'Shipping Fee' },
      'petty-cash':  { primary: 'MISC',        sub: 'Petty Cash' }
    };
    var p = PRESETS[preset];
    if (!p) return;
    var cats = await loadExpenseCategories();
    if (!cats) { showToast('Could not load categories', 'error'); return; }
    var primSel = $('exp-primary-cat');
    var subSel  = $('exp-sub-cat');
    if (!primSel || !subSel) return;
    // Populate primary if empty
    if (primSel.options.length <= 1) populateExpensePrimarySelect(cats);
    primSel.value = p.primary;
    window.onExpensePrimaryChange(primSel);
    // Set sub-category by name
    var subOpt = Array.from(subSel.options).find(function (o) { return o.textContent === p.sub; });
    if (subOpt) subSel.value = subOpt.value;
    // Pre-fill first item description if blank
    var firstDesc = document.querySelector('.exp-item-desc');
    if (firstDesc && !firstDesc.value) firstDesc.value = p.sub;
    var firstAmt = document.querySelector('.exp-item-amount');
    if (firstAmt) firstAmt.focus();
  };

  function populateExpensePrimarySelect(cats) {
    var primSel = $('exp-primary-cat');
    if (!primSel) return;
    primSel.innerHTML = '<option value="">Select category…</option>';
    (cats.primaries || []).forEach(function (p) {
      var opt = document.createElement('option');
      opt.value = p.code;
      opt.textContent = p.name;
      primSel.appendChild(opt);
    });
  }

  // ================================================================
  function initExpensesView() {
    // Set today's date
    const dateEl = $('exp-date');
    if (dateEl && !dateEl.value) {
      dateEl.value = new Date().toISOString().split('T')[0];
    }
    // Pre-fill admin name from session
    const nameEl = $('exp-admin-name');
    if (nameEl) {
      try {
        const u = JSON.parse(localStorage.getItem('rrbm_user') || '{}');
        nameEl.value = u.fullName || u.username || 'Admin';
      } catch (e) { nameEl.value = 'Admin'; }
    }
    loadTodaysExpenses();
    loadExpenseCategories().then(function (cats) {
      if (cats) populateExpensePrimarySelect(cats);
    });

    // Pre-fill expense history range (start of current month → today)
    const todayStr = new Date().toISOString().split('T')[0];
    const startOfMonthDate = new Date();
    startOfMonthDate.setDate(1);
    const startOfMonthStr = startOfMonthDate.toISOString().split('T')[0];
    const rangeStartEl = $('exp-range-start');
    const rangeEndEl   = $('exp-range-end');
    if (rangeStartEl && !rangeStartEl.value) rangeStartEl.value = startOfMonthStr;
    if (rangeEndEl   && !rangeEndEl.value)   rangeEndEl.value   = todayStr;

    // Pre-fill weekly report inputs with current ISO year and week
    const wkYearEl = $('exp-weekly-year');
    const wkWeekEl = $('exp-weekly-week');
    if (wkYearEl && !wkYearEl.value) {
      var _isoInfo = _currentISOWeekYear(new Date());
      wkYearEl.value = _isoInfo.year;
      if (wkWeekEl && !wkWeekEl.value) wkWeekEl.value = _isoInfo.week;
    }
  }

  // Returns { year, week } for a given date using ISO 8601 week numbering.
  function _currentISOWeekYear(date) {
    var d = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()));
    var day = d.getUTCDay() || 7; // Mon=1…Sun=7
    d.setUTCDate(d.getUTCDate() + 4 - day); // Nearest Thursday determines the year
    var yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
    var week = Math.ceil(((d - yearStart) / 86400000 + 1) / 7);
    return { year: d.getUTCFullYear(), week: week };
  }

  window.loadWeeklyReport = async function () {
    var year = parseInt(($('exp-weekly-year') || {}).value, 10);
    var week = parseInt(($('exp-weekly-week') || {}).value, 10);
    if (!year || !week || week < 1 || week > 53) {
      showToast('Enter a valid year and ISO week number (1–53)', 'error');
      return;
    }
    var resultsEl = $('exp-weekly-results');
    if (resultsEl) resultsEl.style.display = 'none';
    try {
      var res  = await fetch(API_BASE + '/api/expenses/report/weekly?year=' + year + '&week=' + week, { headers: authHeaders() });
      var data = await res.json();
      if (!res.ok) { showToast(data.error || 'Failed to load weekly report', 'error'); return; }

      // Stats row
      var wowText  = data.weekOverWeek != null
        ? (data.weekOverWeek >= 0 ? '+' : '') + data.weekOverWeek.toFixed(1) + '% vs prev week'
        : 'No prior-week data';
      var wowColor = data.weekOverWeek != null && data.weekOverWeek < 0 ? '#10B981' : '#EF4444';
      var statsEl  = $('exp-weekly-stats');
      if (statsEl) statsEl.innerHTML =
        '<div class="stat-card"><div class="stat-label">Week ' + data.week + ' · ' + data.weekStart + ' → ' + data.weekEnd + '</div>' +
          '<div class="stat-value" style="color:var(--accent);">₱' + Number(data.grandTotal||0).toFixed(2) + '</div>' +
          '<div class="stat-sub">Grand Total</div></div>' +
        '<div class="stat-card"><div class="stat-label">Week-over-Week</div>' +
          '<div class="stat-value" style="color:' + wowColor + ';font-size:20px;">' + wowText + '</div>' +
          '<div class="stat-sub">vs. Week ' + (data.week - 1 || '(prev)') + '</div></div>' +
        '<div class="stat-card"><div class="stat-label">Daily Average</div>' +
          '<div class="stat-value" style="color:var(--accent);">₱' + Number(data.dailyAvg||0).toFixed(2) + '</div>' +
          '<div class="stat-sub">' +
            (data.highestDay ? 'High: ₱' + Number(data.highestDay.total||0).toFixed(2) + ' (' + data.highestDay.date + ')' : 'No spend days') +
          '</div></div>';

      // Day-by-day table
      var daysTbody = $('exp-weekly-days-tbody');
      if (daysTbody) {
        daysTbody.innerHTML = (data.dayByDay || []).map(function(d) {
          var isToday = d.date === new Date().toISOString().split('T')[0];
          var rowStyle = isToday ? 'background:rgba(250,214,106,0.08);font-weight:600;' : '';
          return '<tr style="' + rowStyle + '">' +
            '<td>' + d.date + (isToday ? ' <span style="color:var(--accent);font-size:11px;">(today)</span>' : '') + '</td>' +
            '<td style="text-align:right;">' + Number(d.total||0).toFixed(2) + '</td>' +
            '<td style="text-align:right;">' + d.count + '</td>' +
            '</tr>';
        }).join('');
      }

      // Category breakdown
      var catSection = $('exp-weekly-category-section');
      var catTbody   = $('exp-weekly-cat-tbody');
      if (data.byCategory && data.byCategory.length > 0) {
        if (catTbody) catTbody.innerHTML = data.byCategory.map(function(c) {
          return '<tr><td>' + escapeHtml(c.categoryName) + ' <span style="color:var(--text-muted);font-size:11px;">(' + c.categoryCode + ')</span></td>' +
            '<td style="text-align:right;">' + Number(c.total||0).toFixed(2) + '</td>' +
            '<td style="text-align:right;">' + (c.pct != null ? c.pct.toFixed(1) + '%' : '—') + '</td></tr>';
        }).join('');
        if (catSection) catSection.style.display = '';
      } else {
        if (catSection) catSection.style.display = 'none';
      }

      // Voided entries
      var voidSection = $('exp-weekly-voided-section');
      var voidTbody   = $('exp-weekly-voided-tbody');
      if (data.voidedEntries && data.voidedEntries.length > 0) {
        if (voidTbody) voidTbody.innerHTML = data.voidedEntries.map(function(v) {
          return '<tr style="color:#EF4444;">' +
            '<td>#' + v.id + '</td>' +
            '<td>' + v.date + '</td>' +
            '<td>' + escapeHtml(v.voidReason || '—') + '</td>' +
            '<td style="text-align:right;">₱' + Number(v.totalAmount||0).toFixed(2) + '</td>' +
            '</tr>';
        }).join('');
        if (voidSection) voidSection.style.display = '';
      } else {
        if (voidSection) voidSection.style.display = 'none';
      }

      if (resultsEl) resultsEl.style.display = '';
    } catch (e) {
      showToast('Error loading weekly report', 'error');
    }
  };

  window.loadExpenseRange = async function () {
    const start = ($('exp-range-start') || {}).value;
    const end   = ($('exp-range-end')   || {}).value;
    if (!start || !end) { showToast('Select a start and end date', 'error'); return; }
    const tbody = $('exp-range-tbody');
    if (tbody) tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-muted);padding:20px;">Loading…</td></tr>';
    const pdfBtn = $('exp-range-pdf-btn');
    if (pdfBtn) pdfBtn.style.display = 'none';
    try {
      const res  = await fetch(`${API_BASE}/api/expenses/range?start=${start}&end=${end}`, { headers: authHeaders() });
      const data = await res.json();
      const totalEl = $('exp-range-total');
      if (!data.length) {
        if (tbody) tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-muted);padding:20px;">No expenses in range.</td></tr>';
        if (totalEl) totalEl.textContent = '';
        window._expRangeData  = [];
        window._expRangeStart = start;
        window._expRangeEnd   = end;
        return;
      }
      let grandTotal = 0;
      tbody.innerHTML = data.map(function (e) {
        const itemsList = (e.items || []).map(function (i) {
          return i.itemDescription + ' (₱' + Number(i.amount).toFixed(2) + ')';
        }).join(', ');
        const amt = Number(e.totalAmount || 0);
        grandTotal += amt;
        return `<tr>
          <td>${e.date || '—'}</td>
          <td>${e.adminName || '—'}</td>
          <td style="font-size:12px;color:var(--text-muted);">${itemsList || '—'}</td>
          <td style="text-align:right;font-weight:600;">₱${amt.toFixed(2)}</td>
          <td style="font-size:11px;">${e.lateImported ? '<span style="display:inline-block;padding:1px 6px;border-radius:10px;font-size:10px;font-weight:600;background:#FEF3C7;color:#92400E;">⚠ Late recorded</span>' : ''}</td>
        </tr>`;
      }).join('');
      if (totalEl) totalEl.textContent = 'Total: ₱' + grandTotal.toFixed(2);
      // Store for PDF export
      window._expRangeData  = data;
      window._expRangeStart = start;
      window._expRangeEnd   = end;
      if (pdfBtn) pdfBtn.style.display = 'inline-flex';
    } catch (err) {
      console.error('loadExpenseRange', err);
      const tb = $('exp-range-tbody');
      if (tb) tb.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--danger);">Failed to load.</td></tr>';
      window._expRangeData = [];
    }
  };

  window.printExpenseRange = function () {
    const tbody = $('exp-range-tbody');
    if (!tbody || tbody.children.length === 0 || tbody.querySelector('[colspan]')) {
      showToast('Load expense data first', 'error'); return;
    }
    const start     = ($('exp-range-start') || {}).value || '';
    const end       = ($('exp-range-end')   || {}).value || '';
    const totalText = ($('exp-range-total') || {}).textContent || '';
    const rows = Array.from(tbody.querySelectorAll('tr')).map(function (tr) {
      return '<tr>' + Array.from(tr.querySelectorAll('td')).map(function (td) {
        return '<td style="padding:6px 10px;border:1px solid #ddd;font-size:12px;">' + td.innerHTML + '</td>';
      }).join('') + '</tr>';
    }).join('');
    const w = window.open('', '_blank', 'width=800,height=600');
    w.document.write(`<!DOCTYPE html><html><head><title>Expense History ${start} to ${end}</title>
      <style>body{font-family:sans-serif;padding:20px;} table{width:100%;border-collapse:collapse;}
      th{background:#f5f5f5;padding:6px 10px;border:1px solid #ddd;font-size:12px;text-align:left;}</style></head>
      <body>
      <h2 style="margin:0 0 4px;">RRBM — Expense History</h2>
      <p style="margin:0 0 12px;font-size:13px;color:#555;">${start} to ${end}</p>
      <table><thead><tr>
        <th>Date</th><th>Recorded By</th><th>Items</th><th style="text-align:right;">Total</th>
      </tr></thead><tbody>${rows}</tbody></table>
      <p style="margin-top:12px;font-size:13px;font-weight:600;">${totalText}</p>
      </body></html>`);
    w.document.close();
    w.focus();
    setTimeout(function () { w.print(); }, 400);
  };

  window.downloadExpensePDF = function () {
    const data  = window._expRangeData  || [];
    const start = window._expRangeStart || '';
    const end   = window._expRangeEnd   || '';
    if (!data.length) { showToast('Load expense data first', 'error'); return; }

    const fmt = function(n){ return '₱' + Number(n||0).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ','); };
    let grandTotal = 0;

    // Day-by-day rows — uses i.itemDescription (correct API field name)
    const dayRows = data.map(function(e){
      const amt = Number(e.totalAmount || 0);
      grandTotal += amt;
      const itemsList = (e.items || []).map(function(i){
        return '<li>' + escapeHtml(i.itemDescription || '') + ' — ' + fmt(i.amount) + '</li>';
      }).join('');
      return '<tr>'
        + '<td>' + escapeHtml(e.date || '') + '</td>'
        + '<td>' + escapeHtml(e.adminName || '') + '</td>'
        + '<td style="font-size:11px;"><ul style="margin:0;padding-left:16px;">' + itemsList + '</ul></td>'
        + '<td style="text-align:right;font-weight:600;">' + fmt(amt) + '</td>'
        + '</tr>';
    }).join('');

    // Expense type summary — uses i.itemDescription (correct API field name)
    const summaryMap = {};
    data.forEach(function(e){
      (e.items || []).forEach(function(i){
        const desc = i.itemDescription || 'Unknown';
        summaryMap[desc] = (summaryMap[desc] || 0) + Number(i.amount || 0);
      });
    });
    const summaryRows = Object.entries(summaryMap)
      .sort(function(a, b){ return b[1] - a[1]; })
      .map(function(entry){
        const pct = grandTotal > 0 ? ((entry[1] / grandTotal) * 100).toFixed(1) : '0.0';
        return '<tr>'
          + '<td>' + escapeHtml(entry[0]) + '</td>'
          + '<td style="text-align:right;font-weight:600;">' + fmt(entry[1]) + '</td>'
          + '<td style="text-align:right;color:#888;">' + pct + '%</td>'
          + '</tr>';
      }).join('');

    const w = window.open('', '_blank', 'width=800,height=900');
    if (!w) { showToast('Pop-up blocked — allow pop-ups and try again', 'error'); return; }
    w.document.write('<!DOCTYPE html><html><head><title>Expense Report ' + start + ' to ' + end + '</title>'
      + '<style>'
      + 'body{font-family:Arial,sans-serif;font-size:13px;padding:24px;color:#1A1208;max-width:750px;margin:0 auto;}'
      + '.header{display:flex;align-items:center;gap:14px;border-bottom:3px solid #FAD16A;padding-bottom:12px;margin-bottom:16px;}'
      + '.logo-sq{width:44px;height:44px;background:#FAD16A;border-radius:6px;display:flex;align-items:center;justify-content:center;font-weight:900;font-size:18px;color:#2C1A0E;flex-shrink:0;}'
      + '.co-name{font-size:15px;font-weight:700;color:#2C1A0E;}'
      + '.co-sub{font-size:11px;color:#666;}'
      + 'h3{margin:20px 0 8px;font-size:14px;color:#2C1A0E;border-bottom:2px solid #FAD16A;padding-bottom:4px;}'
      + 'table{width:100%;border-collapse:collapse;}'
      + 'th{background:#FAD16A;padding:7px 10px;text-align:left;font-size:11px;text-transform:uppercase;}'
      + 'td{padding:6px 10px;border-bottom:1px solid #eee;vertical-align:top;}'
      + 'tfoot td{font-weight:700;font-size:14px;background:#fffbe6;}'
      + '.footer{margin-top:32px;font-size:10px;color:#999;text-align:center;border-top:1px solid #eee;padding-top:8px;}'
      + '@media print{body{padding:10px;}}'
      + '</style></head><body>'
      + '<div class="header">'
      + '<div class="logo-sq">R</div>'
      + '<div><div class="co-name">RRBM Packaging Supplies and Trading</div>'
      + '<div class="co-sub">Expense Report &nbsp;|&nbsp; Period: ' + escapeHtml(start) + ' to ' + escapeHtml(end) + '</div></div>'
      + '</div>'
      + '<h3>Day-by-Day Breakdown</h3>'
      + '<table><thead><tr><th>Date</th><th>Recorded By</th><th>Items</th><th style="text-align:right;">Total</th></tr></thead>'
      + '<tbody>' + dayRows + '</tbody>'
      + '<tfoot><tr class="grand-row"><td colspan="3" style="text-align:right;">Grand Total</td><td style="text-align:right;">' + fmt(grandTotal) + '</td></tr></tfoot>'
      + '</table>'
      + '<h3>Expense Type Summary</h3>'
      + '<table><thead><tr><th>Description</th><th style="text-align:right;">Total</th><th style="text-align:right;">Share</th></tr></thead>'
      + '<tbody>' + summaryRows + '</tbody></table>'
      + '<div class="footer">RRBM Management System &middot; Confidential &middot; Internal use only</div>'
      + '<script>window.onload=function(){window.print();}<\/script>'
      + '</body></html>');
    w.document.close();
  };

  window.exportExpenses = async function () {
    const start  = ($('exp-range-start') || {}).value;
    const end    = ($('exp-range-end')   || {}).value;
    if (!start || !end) { showToast('Select a date range first', 'error'); return; }

    const format = (($('exp-export-format') || {}).value || 'csv').toLowerCase();
    const url    = `${API_BASE}/api/expenses/export?start=${start}&end=${end}&format=${format}`;

    try {
      const res = await fetch(url, { headers: authHeaders() });
      if (!res.ok) { showToast('Export failed (' + res.status + ')', 'error'); return; }

      if (format === 'pdf') {
        // Fetch HTML, inject into a new window; the page auto-prints via window.onload
        const html = await res.text();
        const w = window.open('', '_blank', 'width=900,height=700');
        if (!w) { showToast('Pop-up blocked — allow pop-ups and try again', 'error'); return; }
        w.document.open();
        w.document.write(html);
        w.document.close();
      } else {
        // Trigger a file download for CSV and Excel
        const blob = await res.blob();
        const ext  = format === 'excel' ? 'xls' : 'csv';
        const burl = URL.createObjectURL(blob);
        const a    = document.createElement('a');
        a.href     = burl;
        a.download = 'expenses-' + start + '-to-' + end + '.' + ext;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(burl);
      }
    } catch (err) {
      console.error('exportExpenses', err);
      showToast('Export failed', 'error');
    }
  };

  window.addExpenseRow = function () {
    const container = $('exp-items-container');
    if (!container) return;
    const row = document.createElement('div');
    row.className = 'exp-item-row';
    row.style.cssText = 'display:grid;grid-template-columns:1fr 140px 36px;gap:8px;margin-bottom:8px;';
    row.innerHTML =
      '<input type="text" class="form-control exp-item-desc" placeholder="Item description" />' +
      '<input type="number" class="form-control exp-item-amount" placeholder="Amount" min="0" step="0.01" oninput="updateExpenseTotal()" />' +
      '<button class="btn btn-secondary btn-sm" onclick="removeExpenseRow(this)" title="Remove" style="padding:0 10px;">×</button>';
    container.appendChild(row);
  };

  window.removeExpenseRow = function (btn) {
    const container = $('exp-items-container');
    if (!container) return;
    if (container.querySelectorAll('.exp-item-row').length <= 1) {
      showToast('At least one item is required', 'error'); return;
    }
    btn.closest('.exp-item-row').remove();
    updateExpenseTotal();
  };

  window.updateExpenseTotal = function () {
    let total = 0;
    document.querySelectorAll('.exp-item-amount').forEach(function (inp) {
      total += parseFloat(inp.value) || 0;
    });
    const el = $('exp-running-total');
    if (el) el.textContent = '₱' + total.toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  };

  window.submitExpense = async function () {
    var date          = ($('exp-date')           || {}).value || '';
    var paymentMethod = ($('exp-payment-method') || {}).value || '';
    var referenceNum  = (($('exp-reference')     || {}).value || '').trim();
    var notes         = (($('exp-notes')         || {}).value || '').trim();
    var categoryId    = parseInt(($('exp-sub-cat') || {}).value) || null;
    var token = localStorage.getItem('rrbm_token');

    if (!token)         { showToast('Not authenticated', 'error'); return; }
    if (!date)          { showToast('Please select a date', 'error'); return; }
    if (!paymentMethod) { showToast('Please select a payment method', 'error'); return; }
    if (!categoryId)    { showToast('Please select a category and sub-category', 'error'); return; }

    var items = [];
    document.querySelectorAll('.exp-item-row').forEach(function (row) {
      var desc   = (row.querySelector('.exp-item-desc')   || {}).value || '';
      var amount = parseFloat((row.querySelector('.exp-item-amount') || {}).value) || 0;
      if (desc.trim()) items.push({ itemDescription: desc.trim(), amount: amount, categoryId: categoryId });
    });

    if (items.length === 0) { showToast('Add at least one expense item', 'error'); return; }

    try {
      var res = await fetch(API_BASE + '/api/expenses', {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ date: date, paymentMethod: paymentMethod, notes: notes, referenceNumber: referenceNum, items: items })
      });
      var data = await res.json();
      if (res.ok) {
        showToast('Expense added successfully', 'success');
        // Reset form
        var container = $('exp-items-container');
        if (container) {
          var rows = container.querySelectorAll('.exp-item-row');
          rows.forEach(function (r, i) {
            if (i === 0) {
              (r.querySelector('.exp-item-desc')   || {}).value = '';
              (r.querySelector('.exp-item-amount') || {}).value = '';
            } else { r.remove(); }
          });
        }
        if ($('exp-payment-method')) $('exp-payment-method').value = '';
        if ($('exp-primary-cat'))    { $('exp-primary-cat').value = ''; }
        if ($('exp-sub-cat'))        { $('exp-sub-cat').innerHTML = '<option value="">Select sub-category…</option>'; }
        if ($('exp-notes'))          $('exp-notes').value = '';
        if ($('exp-reference'))      $('exp-reference').value = '';
        updateExpenseTotal();
        loadTodaysExpenses();
        renderDashboard();
      } else {
        showToast('Error: ' + (data.error || 'Save failed'), 'error');
      }
    } catch (err) {
      showToast('Connection error', 'error');
    }
  };

  async function loadTodaysExpenses() {
    const tbody = $('exp-today-tbody');
    const totalEl = $('exp-today-total');
    if (!tbody) return;
    const token = localStorage.getItem('rrbm_token');
    if (!token) return;
    try {
      const today = new Date().toISOString().split('T')[0];
      const res   = await fetch(API_BASE + '/api/expenses?date=' + today, { headers: authHeaders() });
      if (!res.ok) { tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-muted);padding:20px;">Error</td></tr>'; return; }
      const data  = await res.json();
      const fmt   = function (n) { return '₱' + Number(n).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 }); };

      if (!data || data.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-muted);padding:20px;">No expenses recorded today</td></tr>';
        if (totalEl) totalEl.textContent = '';
        return;
      }

      let grandTotal = 0;
      tbody.innerHTML = data.map(function (e) {
        grandTotal += parseFloat(e.totalAmount) || 0;
        const time  = e.createdAt ? new Date(e.createdAt).toLocaleTimeString('en-PH', { hour: '2-digit', minute: '2-digit' }) : '—';
        const itemsSummary = (e.items || []).map(function (i) {
          return escapeHtml(i.itemDescription) + ' — ' + fmt(i.amount);
        }).join('<br>');
        return '<tr>'
          + '<td style="font-size:12px;">' + time + '</td>'
          + '<td>' + escapeHtml(e.adminName) + '</td>'
          + '<td style="font-size:12px;color:var(--text-muted);">' + itemsSummary + '</td>'
          + '<td style="text-align:right;font-weight:600;">' + fmt(e.totalAmount) + '</td>'
          + '<td style="font-size:11px;">' + (e.lateImported ? '<span style="display:inline-block;padding:1px 6px;border-radius:10px;font-size:10px;font-weight:600;background:#FEF3C7;color:#92400E;">⚠ Late recorded</span>' : '') + '</td>'
          + '</tr>';
      }).join('');

      if (totalEl) totalEl.textContent = 'Total: ' + fmt(grandTotal);
    } catch (err) {
      tbody.innerHTML = '<tr><td colspan="4" style="text-align:center;color:var(--text-muted);padding:20px;">—</td></tr>';
    }
  }

  // ================================================================
  // PAYABLES — list, detail modal, status toggle, delete
  // ================================================================
  var _currentPayableId     = null;
  var _currentPayableStatus = null;
  var _pendingPayableStatus = null;
  var _closeDailyMasterKey  = '';        // preserved across normal→override flow
  var _collectionsParsed    = [];        // cached collections list

  window.loadPayables = async function () {
    const token = localStorage.getItem('rrbm_token');
    const tb    = $('payables-tbody');
    if (!token || !tb) return;
    tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:var(--text-muted);padding:20px;">Loading…</td></tr>';
    try {
      const [listRes, sumRes] = await Promise.all([
        fetch(API_BASE + '/api/payables',         { headers: { 'Authorization': 'Bearer ' + token } }),
        fetch(API_BASE + '/api/payables/summary', { headers: { 'Authorization': 'Bearer ' + token } })
      ]);
      if (sumRes.ok) {
        const s = await sumRes.json();
        const fmt = function (n) { return '₱' + Number(n || 0).toLocaleString('en-PH', { minimumFractionDigits: 2 }); };
        setText('payable-total-outstanding', fmt(s.totalOutstanding));
        setText('payable-pending-count', (s.pendingCount || 0).toString());
      }
      if (!listRes.ok) {
        tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:#EF4444;padding:20px;">Failed to load payables</td></tr>';
        return;
      }
      const records = await listRes.json();
      var now  = new Date();
      var paid = records.filter(function (r) {
        if (r.status !== 'PAID' || !r.paidAt) return false;
        var d = new Date(r.paidAt);
        return d.getMonth() === now.getMonth() && d.getFullYear() === now.getFullYear();
      });
      setText('payable-paid-count', paid.length.toString());
      if (records.length === 0) {
        tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:var(--text-muted);padding:20px;">No payables recorded yet</td></tr>';
        return;
      }
      var fmt = function (n) { return '₱' + Number(n || 0).toLocaleString('en-PH', { minimumFractionDigits: 2 }); };
      var idx = 1;
      tb.innerHTML = records.map(function (r) {
        var statusBadge = r.status === 'PAID'
          ? '<span class="badge badge-ok">PAID</span>'
          : '<span class="badge badge-honey">PENDING</span>';
        var balance = Number(r.totalAmount || 0) - Number(r.amountPaid || 0);
        return '<tr>'
          + '<td>' + (idx++) + '</td>'
          + '<td><span class="product-code">' + escapeHtml(r.receiptNumber || '—') + '</span></td>'
          + '<td>' + formatDate(r.createdAt) + '</td>'
          + '<td style="font-size:11px;color:var(--text-muted);">' + escapeHtml(r.supplierName || '—') + '</td>'
          + '<td style="font-weight:600;">' + fmt(r.totalAmount) + '</td>'
          + '<td style="font-weight:600;color:' + (balance > 0 ? '#EF4444' : '#10B981') + ';">' + fmt(balance) + '</td>'
          + '<td>' + statusBadge + '</td>'
          + '<td><button class="btn btn-secondary btn-sm" onclick="openPayableDetail(' + r.id + ')"><i class="ti ti-eye"></i> View</button></td>'
          + '</tr>';
      }).join('');
    } catch (err) {
      tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:#EF4444;padding:20px;">Connection error</td></tr>';
    }
  };

  window.openPayableDetail = async function (id) {
    _currentPayableId     = id;
    _currentPayableStatus = null;
    const token = localStorage.getItem('rrbm_token');
    const body  = $('payable-detail-body');
    if (!body) return;
    body.innerHTML = '<p style="color:var(--text-muted);padding:20px;text-align:center;">Loading…</p>';
    $('modal-payable-detail').classList.add('open');
    try {
      const res = await fetch(API_BASE + '/api/payables/' + id, { headers: { 'Authorization': 'Bearer ' + token } });
      if (!res.ok) { body.innerHTML = '<p style="color:#EF4444;padding:20px;">Failed to load</p>'; return; }
      const p = await res.json();
      _currentPayableStatus = p.status;
      var fmt = function (n) { return '₱' + Number(n || 0).toLocaleString('en-PH', { minimumFractionDigits: 2 }); };
      var balance = Number(p.totalAmount || 0) - Number(p.amountPaid || 0);
      setText('payable-detail-receipt', '— Receipt #' + (p.receiptNumber || id));
      var toggleBtn = $('payable-toggle-status-btn');
      if (toggleBtn) {
        if (p.status === 'PAID') {
          toggleBtn.innerHTML = '<i class="ti ti-arrow-back-up" style="margin-right:4px;"></i>Revert to Pending';
          toggleBtn.style.background = '#6B5740';
        } else {
          toggleBtn.innerHTML = '<i class="ti ti-check" style="margin-right:4px;"></i>Mark as Paid';
          toggleBtn.style.background = '';
        }
      }
      // Build line items table
      var itemsHtml = '';
      if (p.items && p.items.length > 0) {
        itemsHtml = '<h4 style="font-size:13px;font-weight:600;margin:16px 0 8px;">Delivery Line Items</h4>'
          + '<table class="table" style="font-size:12px;">'
          + '<thead><tr><th>Product</th><th style="text-align:center;">Ordered</th><th style="text-align:center;">Received</th><th style="text-align:center;">Rejected</th><th style="text-align:right;">Unit Cost</th><th style="text-align:right;">Line Total</th></tr></thead>'
          + '<tbody>'
          + p.items.map(function (i) {
              return '<tr>'
                + '<td>' + escapeHtml(i.productName || '—') + '</td>'
                + '<td style="text-align:center;">' + (i.quantity || 0) + '</td>'
                + '<td style="text-align:center;">' + (i.receivedQty || 0) + '</td>'
                + '<td style="text-align:center;">' + (i.rejectedQty || 0) + '</td>'
                + '<td style="text-align:right;">' + fmt(i.unitCost) + '</td>'
                + '<td style="text-align:right;font-weight:600;">' + fmt(i.lineTotal) + '</td>'
                + '</tr>';
            }).join('')
          + '</tbody></table>';
      }
      body.innerHTML =
        '<div class="grid grid-2" style="gap:12px;margin-bottom:14px;">'
        + '<div class="stat-card"><div class="stat-label">Total Amount</div><div class="stat-value">' + fmt(p.totalAmount) + '</div></div>'
        + '<div class="stat-card"><div class="stat-label">Outstanding Balance</div><div class="stat-value" style="color:' + (balance > 0 ? '#EF4444' : '#10B981') + ';">' + fmt(balance) + '</div></div>'
        + '</div>'
        + '<table class="table" style="margin-bottom:12px;">'
        + '<tr><td style="color:var(--text-muted);width:40%;">Receipt Number</td><td><span class="product-code">' + escapeHtml(p.receiptNumber || '—') + '</span></td></tr>'
        + '<tr><td style="color:var(--text-muted);">Supplier</td><td>' + escapeHtml(p.supplierName || '—') + '</td></tr>'
        + '<tr><td style="color:var(--text-muted);">Status</td><td>' + (p.status === 'PAID' ? '<span class="badge badge-ok">PAID</span>' : '<span class="badge badge-honey">PENDING</span>') + '</td></tr>'
        + '<tr><td style="color:var(--text-muted);">Date Recorded</td><td>' + formatDate(p.createdAt) + '</td></tr>'
        + '<tr><td style="color:var(--text-muted);">Recorded By</td><td>' + escapeHtml(p.createdBy || '—') + '</td></tr>'
        + (p.status === 'PAID' ? '<tr><td style="color:var(--text-muted);">Paid At</td><td>' + formatDate(p.paidAt) + '</td></tr><tr><td style="color:var(--text-muted);">Paid By</td><td>' + escapeHtml(p.paidBy || '—') + '</td></tr>' : '')
        + (p.notes ? '<tr><td style="color:var(--text-muted);">Notes</td><td>' + escapeHtml(p.notes) + '</td></tr>' : '')
        + '</table>'
        + itemsHtml;
    } catch (err) {
      body.innerHTML = '<p style="color:#EF4444;padding:20px;">Connection error</p>';
    }
  };

  window.togglePayableStatus = function () {
    if (!_currentPayableId) return;
    _pendingPayableStatus = _currentPayableStatus === 'PAID' ? 'PENDING' : 'PAID';
    const titleEl = $('payable-paid-title');
    const descEl  = $('payable-paid-desc');
    if (titleEl) titleEl.textContent = _pendingPayableStatus === 'PAID' ? 'Mark as Paid' : 'Revert to Pending';
    if (descEl)  descEl.textContent  = _pendingPayableStatus === 'PAID'
      ? 'Enter your admin security key to confirm marking this payable as paid.'
      : 'Enter your admin security key to confirm reverting this payable back to pending.';
    if ($('payable-paid-key')) $('payable-paid-key').value = '';
    $('modal-payable-paid').classList.add('open');
  };

  window.confirmPayableStatusChange = async function () {
    const key = (($('payable-paid-key') || {}).value || '').trim();
    if (!key) { showToast('Admin security key is required', 'error'); return; }
    try {
      // Verify security key first
      const vRes = await fetch(API_BASE + '/api/auth/verify-security-key', {
        method: 'POST', headers: authHeaders(),
        body: JSON.stringify({ securityKey: key })
      });
      if (!vRes.ok) {
        const vData = await vRes.json().catch(function () { return {}; });
        showToast(vData.message || 'Incorrect security key', 'error'); return;
      }
      // Key verified — update payable status
      const res = await fetch(API_BASE + '/api/payables/' + _currentPayableId + '/status', {
        method: 'PATCH', headers: authHeaders(), body: JSON.stringify({ status: _pendingPayableStatus })
      });
      if (!res.ok) {
        const d = await res.json().catch(function () { return {}; });
        showToast('Error: ' + (d.message || 'Failed to update status'), 'error'); return;
      }
      showToast('Payable marked as ' + _pendingPayableStatus, 'success');
      closeModal('modal-payable-paid');
      closeModal('modal-payable-detail');
      loadPayables();
    } catch (err) {
      showToast('Connection error', 'error');
    }
  };

  window.askDeletePayable = function () {
    if (!_currentPayableId) return;
    $('payable-delete-key').value = '';
    $('modal-payable-delete').classList.add('open');
  };

  window.confirmDeletePayable = async function () {
    const masterKey = (($('payable-delete-key') || {}).value || '').trim();
    if (!masterKey) { showToast('Master key is required', 'error'); return; }
    if (!_currentPayableId) return;
    try {
      const res = await fetch(API_BASE + '/api/payables/' + _currentPayableId, {
        method: 'DELETE', headers: authHeaders(), body: JSON.stringify({ masterKey: masterKey })
      });
      if (!res.ok) {
        const d = await res.json().catch(function () { return {}; });
        showToast('Error: ' + (d.message || 'Failed to delete'), 'error'); return;
      }
      showToast('Payable deleted', 'success');
      closeModal('modal-payable-delete');
      closeModal('modal-payable-detail');
      loadPayables();
    } catch (err) {
      showToast('Connection error', 'error');
    }
  };

  // EMPLOYEES / USER MANAGEMENT
  // ================================================================
  async function renderUsers() {
    const tb = $('emp-tbody');
    if (!tb) return;
    const token = localStorage.getItem('rrbm_token');
    if (!token) { tb.innerHTML = '<tr><td colspan="9" style="text-align:center;color:var(--text-muted);">Please login first</td></tr>'; return; }

    tb.innerHTML = '<tr><td colspan="9" style="text-align:center;color:var(--text-muted);">Loading…</td></tr>';

    try {
      const res = await fetch('' + API_BASE + '/api/users', { headers: { 'Authorization': 'Bearer ' + token } });
      if (!res.ok) { tb.innerHTML = '<tr><td colspan="9" style="text-align:center;color:var(--text-muted);">Failed to load users</td></tr>'; return; }
      appState.allUsers = await res.json();
      const myId   = String(currentUserId());
      const canMgr = canManageEmployees();

      tb.innerHTML = appState.allUsers.map(function (u) {
        const statusDot = u.status === 'ACTIVE'   ? '<span class="status-dot dot-active"></span>Active'
          : u.status === 'AWAY'     ? '<span class="status-dot dot-pending"></span>Away'
          : '<span class="status-dot dot-cancelled"></span>Disabled';

        // Photo cell
        const photoHtml = u.profileImage
          ? '<img src="' + u.profileImage + '" style="width:36px;height:36px;border-radius:50%;object-fit:cover;" />'
          : '<div style="width:36px;height:36px;border-radius:50%;background:#e0e0e0;display:flex;align-items:center;justify-content:center;"><i class="ti ti-user" style="font-size:18px;color:#aaa;"></i></div>';

        let actionCell = '<td></td>';
        if (canMgr && String(u.id) !== myId) {
          actionCell = '<td style="white-space:nowrap;">'
            + '<button class="btn btn-primary btn-sm" onclick="askEditEmployee(' + u.id + ')" style="margin-right:4px;" title="Edit"><i class="ti ti-edit"></i> Edit</button>'
            + (u.status === 'ACTIVE'
              ? '<button class="btn btn-warning btn-sm" style="margin-right:4px;" onclick="setUserStatus(' + u.id + ', \'DISABLED\')"><i class="ti ti-ban"></i> Disable</button>'
              : '<button class="btn btn-success btn-sm" style="margin-right:4px;" onclick="setUserStatus(' + u.id + ', \'ACTIVE\')"><i class="ti ti-check"></i> Enable</button>')
            + (isSuperAdmin() ? '<button class="btn btn-danger btn-sm" onclick="askDeleteEmployee(' + u.id + ')" title="Delete account"><i class="ti ti-trash"></i></button>' : '')
            + '</td>';
        }

        return '<tr>'
          + '<td>' + photoHtml + '</td>'
          + '<td>' + escapeHtml(u.employeeId || '—') + '</td>'
          + '<td><strong>' + escapeHtml(u.fullName) + (String(u.id) === myId ? ' <span style="font-size:10px;color:#999;">(you)</span>' : '') + '</strong></td>'
          + '<td>' + escapeHtml(u.designation || '—') + '</td>'
          + '<td>' + escapeHtml(u.contactNumber || '—') + '</td>'
          + '<td>' + escapeHtml(u.email) + '</td>'
          + '<td>' + roleBadge(u.role) + '</td>'
          + '<td>' + statusDot + '</td>'
          + actionCell
          + '</tr>';
      }).join('');
    } catch (err) {
      tb.innerHTML = '<tr><td colspan="9" style="text-align:center;color:var(--text-muted);">Connection error</td></tr>';
    }
  }

  // ----------------------------------------------------------------
  // Profile image preview helper (shared by Add and Edit modals)
  // ----------------------------------------------------------------
  window.previewEmpImage = function (input, previewId) {
    const preview = $(previewId);
    if (!preview || !input.files || !input.files[0]) return;
    const reader = new FileReader();
    reader.onload = function (e) {
      preview.innerHTML = '<img src="' + e.target.result + '" style="width:100%;height:100%;object-fit:cover;" />';
      // Store base64 on the input element for later retrieval
      input._base64 = e.target.result;
    };
    reader.readAsDataURL(input.files[0]);
  };

  window.askAddEmployee = function () {
    if (!canManageEmployees()) { showToast('Administrator access required', 'error'); return; }
    // Clear all fields
    ['add-emp-name','add-emp-empid','add-emp-email','add-emp-username',
     'add-emp-password','add-emp-password-confirm',
     'add-emp-designation','add-emp-contact','add-emp-birthdate','add-emp-address']
      .forEach(function (id) { if ($(id)) $(id).value = ''; });
    // Reset force-change checkbox to default checked
    var addFcCheck = $('add-emp-force-change');
    if (addFcCheck) addFcCheck.checked = true;
    if ($('add-emp-role')) $('add-emp-role').value = 'STANDARD_USER';
    // Reset image preview
    const prev = $('add-emp-img-preview');
    if (prev) prev.innerHTML = '<i class="ti ti-user" style="font-size:32px;color:#bbb;"></i>';
    const imgInput = $('add-emp-image');
    if (imgInput) { imgInput.value = ''; imgInput._base64 = null; }
    // Show/hide security key section (Super Admin only)
    const addKeySection = $('add-emp-security-key-section');
    if (addKeySection) addKeySection.style.display = isSuperAdmin() ? '' : 'none';
    if ($('add-emp-security-key'))         $('add-emp-security-key').value = '';
    if ($('add-emp-security-key-confirm')) $('add-emp-security-key-confirm').value = '';
    $('modal-add-employee').classList.add('open');
  };

  window.submitAddEmployee = async function () {
    const fullName = (($('add-emp-name')      || {}).value || '').trim();
    const email    = (($('add-emp-email')     || {}).value || '').trim();
    const password = (($('add-emp-password')  || {}).value || '').trim();
    const role     = ($('add-emp-role')       || {}).value || 'STANDARD_USER';

    const passwordConfirm = (($('add-emp-password-confirm') || {}).value || '').trim();

    if (!fullName) { showToast('Employee name is required', 'error'); return; }
    if (!email)    { showToast('Email is required', 'error'); return; }
    if (!password || password.length < 6) { showToast('Password must be at least 6 characters', 'error'); return; }
    if (password !== passwordConfirm) { showToast('Passwords do not match', 'error'); return; }

    const forceChange = ($('add-emp-force-change') || {}).checked !== false;
    const imgInput = $('add-emp-image');

    // Collect page access checkboxes
    const addPages = [];
    document.querySelectorAll('#add-emp-page-access input[type=checkbox]:checked').forEach(function (cb) {
      addPages.push(cb.value);
    });

    const payload = {
      fullName,
      email,
      password,
      role,
      mustChangePassword: forceChange ? 'true' : 'false',
      employeeId:    (($('add-emp-empid')       || {}).value || '').trim(),
      username:      (($('add-emp-username')     || {}).value || '').trim(),
      designation:   (($('add-emp-designation')  || {}).value || '').trim(),
      contactNumber: (($('add-emp-contact')      || {}).value || '').trim(),
      birthdate:     (($('add-emp-birthdate')    || {}).value || '').trim(),
      address:       (($('add-emp-address')      || {}).value || '').trim(),
      profileImage:  (imgInput && imgInput._base64) ? imgInput._base64 : '',
      createdByName: currentUserName(),
      allowedPages:  JSON.stringify(addPages)
    };

    try {
      const res = await fetch('' + API_BASE + '/api/users', {
        method: 'POST', headers: authHeaders(),
        body: JSON.stringify(payload)
      });
      const data = await res.json();
      if (res.ok) {
        // Optionally assign security key (Super Admin only)
        if (isSuperAdmin()) {
          const sk     = (($('add-emp-security-key')         || {}).value || '').trim();
          const skConf = (($('add-emp-security-key-confirm') || {}).value || '').trim();
          if (sk) {
            if (sk !== skConf) {
              showToast('Security key mismatch — account created but key not saved', 'error');
            } else if (sk.length < 6) {
              showToast('Security key too short — account created but key not saved', 'error');
            } else {
              await fetch(API_BASE + '/api/users/' + data.id + '/security-key', {
                method: 'PATCH', headers: authHeaders(),
                body: JSON.stringify({ securityKey: sk })
              });
            }
          }
        }
        closeModal('modal-add-employee');
        showToast('Account created for ' + fullName, 'success');
        renderUsers();
      } else {
        showToast('Error: ' + (data.message || 'Failed to create account'), 'error');
      }
    } catch (err) {
      showToast('Connection error', 'error');
    }
  };

  // ----------------------------------------------------------------
  // Edit Employee
  // ----------------------------------------------------------------
  window.askEditEmployee = async function (userId) {
    if (!canManageEmployees()) { showToast('Administrator access required', 'error'); return; }
    // Fetch fresh user data
    try {
      const res = await fetch('' + API_BASE + '/api/users/' + userId, { headers: { 'Authorization': 'Bearer ' + localStorage.getItem('rrbm_token') } });
      if (!res.ok) { showToast('Failed to load employee data', 'error'); return; }
      const u = await res.json();

      $('edit-emp-id').value          = u.id;
      $('edit-emp-name').value        = u.fullName    || '';
      $('edit-emp-empid').value       = u.employeeId  || '';
      $('edit-emp-email').value       = u.email       || '';
      $('edit-emp-username').value    = u.username    || '';
      $('edit-emp-password').value    = '';
      $('edit-emp-role').value        = u.role        || 'STANDARD_USER';
      $('edit-emp-designation').value = u.designation || '';
      $('edit-emp-contact').value     = u.contactNumber || '';
      $('edit-emp-birthdate').value   = u.birthdate   || '';
      $('edit-emp-address').value     = u.address     || '';

      // Profile image preview
      const prev     = $('edit-emp-img-preview');
      const imgInput = $('edit-emp-image');
      if (imgInput) { imgInput.value = ''; imgInput._base64 = null; }
      if (prev) {
        prev.innerHTML = u.profileImage
          ? '<img src="' + u.profileImage + '" style="width:100%;height:100%;object-fit:cover;" />'
          : '<i class="ti ti-user" style="font-size:32px;color:#bbb;"></i>';
      }

      // Populate page access checkboxes
      let allowedPages = [];
      try { allowedPages = u.allowedPages ? JSON.parse(u.allowedPages) : null; } catch (e) {}
      // null = all pages; array = specific pages
      document.querySelectorAll('#edit-emp-page-access input[type=checkbox]').forEach(function (cb) {
        cb.checked = (allowedPages === null) || allowedPages.includes(cb.value);
      });
      // Only Super Admin can edit page access — disable for others
      const editPageAccess = $('edit-emp-page-access');
      if (editPageAccess) {
        const disabled = !isSuperAdmin();
        editPageAccess.querySelectorAll('input').forEach(function (cb) { cb.disabled = disabled; });
        editPageAccess.style.opacity = disabled ? '0.5' : '1';
      }

      // Show/hide security key section (Super Admin only)
      const editKeySection = $('edit-emp-security-key-section');
      if (editKeySection) editKeySection.style.display = isSuperAdmin() ? '' : 'none';
      if ($('edit-emp-security-key'))          $('edit-emp-security-key').value = '';
      if ($('edit-emp-security-key-confirm'))  $('edit-emp-security-key-confirm').value = '';
      // Reset password confirm + force-change wrapper
      if ($('edit-emp-password-confirm'))      $('edit-emp-password-confirm').value = '';
      var fcWrap = $('edit-emp-force-change-wrap');
      if (fcWrap) fcWrap.style.display = 'none';
      var fcCheck = $('edit-emp-force-change');
      if (fcCheck) fcCheck.checked = true;

      // Credential Viewer — Super Admin only
      const credSection = $('edit-emp-credential-viewer');
      if (credSection) {
        if (isSuperAdmin()) {
          credSection.style.display = '';
          // Reset inputs to hidden state
          ['cred-view-password', 'cred-view-seckey'].forEach(function (id) {
            var el = $(id); if (el) { el.value = ''; el.type = 'password'; }
          });
          document.querySelectorAll('#edit-emp-credential-viewer button i').forEach(function (i) {
            i.className = 'ti ti-eye';
          });
          $('cred-view-password-hint').textContent = 'Loading...';
          $('cred-view-seckey-hint').textContent   = 'Loading...';

          fetch(API_BASE + '/api/users/' + userId + '/credentials', {
            headers: { 'Authorization': 'Bearer ' + localStorage.getItem('rrbm_token') }
          }).then(function (r) { return r.ok ? r.json() : {}; })
            .then(function (c) {
              var pwInp = $('cred-view-password'), skInp = $('cred-view-seckey');
              if (pwInp) pwInp.value = c.passwordPlain    || '';
              if (skInp) skInp.value = c.securityKeyPlain || '';
              $('cred-view-password-hint').textContent = c.passwordPlain
                ? 'Click the eye icon to reveal'
                : 'Not available — set before tracking was enabled';
              if (c.hasSecurityKey === false) {
                $('cred-view-seckey-hint').textContent = 'No security key assigned';
              } else {
                $('cred-view-seckey-hint').textContent = c.securityKeyPlain
                  ? 'Click the eye icon to reveal'
                  : 'Not available — set before tracking was enabled';
              }
            }).catch(function () {
              $('cred-view-password-hint').textContent = 'Could not load credentials';
              $('cred-view-seckey-hint').textContent   = 'Could not load credentials';
            });
        } else {
          credSection.style.display = 'none';
        }
      }

      $('modal-edit-employee').classList.add('open');
    } catch (err) {
      showToast('Connection error', 'error');
    }
  };

  window.submitEditEmployee = async function () {
    const userId   = ($('edit-emp-id')   || {}).value;
    const fullName = (($('edit-emp-name') || {}).value || '').trim();
    const email    = (($('edit-emp-email')|| {}).value || '').trim();
    if (!userId)   { showToast('Missing employee ID', 'error'); return; }
    if (!fullName) { showToast('Employee name is required', 'error'); return; }
    if (!email)    { showToast('Email is required', 'error'); return; }

    const newPassword        = (($('edit-emp-password')         || {}).value || '').trim();
    const newPasswordConfirm = (($('edit-emp-password-confirm') || {}).value || '').trim();
    if (newPassword && newPassword.length < 6) { showToast('Password must be at least 6 characters', 'error'); return; }
    if (newPassword && newPassword !== newPasswordConfirm) { showToast('Passwords do not match', 'error'); return; }

    const imgInput = $('edit-emp-image');
    const payload = {
      fullName,
      email,
      employeeId:    (($('edit-emp-empid')       || {}).value || '').trim(),
      username:      (($('edit-emp-username')     || {}).value || '').trim(),
      role:          ($('edit-emp-role')          || {}).value || 'STANDARD_USER',
      designation:   (($('edit-emp-designation')  || {}).value || '').trim(),
      contactNumber: (($('edit-emp-contact')      || {}).value || '').trim(),
      birthdate:     (($('edit-emp-birthdate')    || {}).value || '').trim(),
      address:       (($('edit-emp-address')      || {}).value || '').trim(),
      changedByName: currentUserName()
    };
    // Only include password if provided
    if (newPassword) {
      payload.password = newPassword;
      // Include force-change flag only when a password is being reset
      var editForce = $('edit-emp-force-change');
      payload.mustChangePassword = (editForce && !editForce.checked) ? 'false' : 'true';
    }
    // Only include new image if one was selected
    if (imgInput && imgInput._base64) payload.profileImage = imgInput._base64;

    try {
      const res = await fetch('' + API_BASE + '/api/users/' + userId, {
        method: 'PUT', headers: authHeaders(),
        body: JSON.stringify(payload)
      });
      const data = await res.json();
      if (res.ok) {
        // If Super Admin, also save page permissions
        if (isSuperAdmin()) {
          const editPages = [];
          document.querySelectorAll('#edit-emp-page-access input[type=checkbox]:checked').forEach(function (cb) {
            editPages.push(cb.value);
          });
          await fetch(API_BASE + '/api/users/' + userId + '/permissions', {
            method: 'PATCH', headers: authHeaders(),
            body: JSON.stringify({ allowedPages: JSON.stringify(editPages), changedByName: currentUserName() })
          });
        }
        // Optionally update security key (Super Admin only)
        if (isSuperAdmin()) {
          const sk = (($('edit-emp-security-key') || {}).value || '').trim();
          if (sk) {
            await fetch(API_BASE + '/api/users/' + userId + '/security-key', {
              method: 'PATCH', headers: authHeaders(),
              body: JSON.stringify({ securityKey: sk })
            });
          }
        }
        closeModal('modal-edit-employee');
        showToast('Employee profile updated', 'success');
        renderUsers();
      } else {
        showToast('Error: ' + (data.message || 'Failed to update profile'), 'error');
      }
    } catch (err) {
      showToast('Connection error', 'error');
    }
  };

  window.askAssignRole = function (userId, userName, currentRole) {
    if (!canManageEmployees()) { showToast('Administrator access required', 'error'); return; }
    $('assign-role-user-id').value = userId;
    $('assign-role-user-name').textContent = userName;
    if ($('assign-role-select')) $('assign-role-select').value = currentRole;
    $('modal-assign-role').classList.add('open');
  };

  window.confirmAssignRole = async function () {
    const userId  = ($('assign-role-user-id')  || {}).value;
    const newRole = ($('assign-role-select')   || {}).value;
    if (!userId || !newRole) { showToast('Missing data', 'error'); return; }

    try {
      const res = await fetch('' + API_BASE + '/api/users/' + userId + '/role', {
        method: 'PATCH', headers: authHeaders(),
        body: JSON.stringify({ role: newRole, changedByName: currentUserName() })
      });
      const data = await res.json();
      if (res.ok) {
        closeModal('modal-assign-role');
        showToast('Role updated successfully', 'success');
        renderUsers();
      } else {
        showToast('Error: ' + (data.message || 'Failed to update role'), 'error');
      }
    } catch (err) {
      showToast('Connection error', 'error');
    }
  };

  window.setUserStatus = async function (userId, newStatus) {
    if (!canManageEmployees()) { showToast('Administrator access required', 'error'); return; }
    try {
      const res = await fetch('' + API_BASE + '/api/users/' + userId + '/status', {
        method: 'PATCH', headers: authHeaders(),
        body: JSON.stringify({ status: newStatus, changedByName: currentUserName() })
      });
      if (res.ok) {
        showToast('User status updated to ' + newStatus, 'success');
        renderUsers();
      } else {
        const d = await res.json();
        showToast('Error: ' + (d.message || 'Failed'), 'error');
      }
    } catch (err) {
      showToast('Connection error', 'error');
    }
  };

  // ================================================================
  // DELETE EMPLOYEE ACCOUNT (Super Admin only)
  // ================================================================
  window.askDeleteEmployee = function (userId) {
    if (!isSuperAdmin()) { showToast('Only Super Admin can delete accounts', 'error'); return; }
    var user = (appState.allUsers || []).find(function (u) { return String(u.id) === String(userId); });
    if (!user) { showToast('User not found', 'error'); return; }
    if ($('delete-emp-id'))   $('delete-emp-id').value = userId;
    if ($('delete-emp-name')) $('delete-emp-name').textContent = user.fullName + ' (' + user.email + ')';
    if ($('delete-emp-key'))  $('delete-emp-key').value = '';
    $('modal-delete-employee').classList.add('open');
  };

  window.confirmDeleteEmployee = async function () {
    const userId = (($('delete-emp-id') || {}).value || '').trim();
    const key    = (($('delete-emp-key') || {}).value || '').trim();
    if (!userId) { showToast('No account selected', 'error'); return; }
    if (!key)    { showToast('Admin security key is required', 'error'); return; }
    try {
      const res = await fetch(API_BASE + '/api/users/' + userId, {
        method: 'DELETE', headers: authHeaders(),
        body: JSON.stringify({ securityKey: key, confirmedByName: currentUserName() })
      });
      const data = await res.json();
      if (res.ok) {
        closeModal('modal-delete-employee');
        showToast(data.message || 'Account deleted', 'success');
        renderUsers();
      } else {
        showToast('Error: ' + (data.message || 'Delete failed'), res.status === 409 ? 'warning' : 'error');
      }
    } catch (err) {
      showToast('Connection error', 'error');
    }
  };

  // ================================================================
  // CHANGE PASSWORD (own — accessible to all logged-in users)
  // ================================================================
  window.openChangePasswordModal = function (forced) {
    if ($('cp-current-pw'))  $('cp-current-pw').value = '';
    if ($('cp-new-pw'))      $('cp-new-pw').value = '';
    if ($('cp-confirm-pw'))  $('cp-confirm-pw').value = '';

    var notice     = $('cp-notice');
    var curWrap    = $('cp-current-pw-wrap');
    var closeBtn   = $('cp-modal-close-btn');
    var cancelBtn  = $('cp-cancel-btn');

    if (forced) {
      // Forced mode: show notice, hide current-pw field, hide close/cancel buttons
      if (notice)   notice.style.display   = '';
      if (curWrap)  curWrap.style.display  = 'none';
      if (closeBtn) closeBtn.style.display = 'none';
      if (cancelBtn) cancelBtn.style.display = 'none';
    } else {
      // Normal mode: hide notice, show current-pw field and buttons
      if (notice)   notice.style.display   = 'none';
      if (curWrap)  curWrap.style.display  = '';
      if (closeBtn) closeBtn.style.display = '';
      if (cancelBtn) cancelBtn.style.display = '';
    }
    $('modal-change-password').classList.add('open');
  };

  window.submitChangePassword = async function () {
    const currentPw = (($('cp-current-pw')  || {}).value || '').trim();
    const newPw     = (($('cp-new-pw')      || {}).value || '').trim();
    const confirmPw = (($('cp-confirm-pw')  || {}).value || '').trim();
    // In forced mode the current-password field is hidden — skip its validation
    if (!_forcedPasswordChange && !currentPw) { showToast('Current password is required', 'error'); return; }
    if (!newPw || newPw.length < 6) { showToast('New password must be at least 6 characters', 'error'); return; }
    if (newPw !== confirmPw) { showToast('New passwords do not match', 'error'); return; }
    const myId = currentUserId();
    if (!myId) { showToast('Not logged in', 'error'); return; }
    try {
      var reqBody = { newPassword: newPw };
      if (!_forcedPasswordChange) reqBody.currentPassword = currentPw;
      const res = await fetch(API_BASE + '/api/users/' + myId + '/change-password', {
        method: 'PATCH', headers: authHeaders(),
        body: JSON.stringify(reqBody)
      });
      const data = await res.json();
      if (res.ok) {
        if (_forcedPasswordChange) {
          // Restore modal to normal and update stored user info
          _forcedPasswordChange = false;
          var stored = JSON.parse(localStorage.getItem('rrbm_user') || '{}');
          stored.mustChangePassword = false;
          localStorage.setItem('rrbm_user', JSON.stringify(stored));
          var closeBtn = $('cp-modal-close-btn'), cancelBtn = $('cp-cancel-btn');
          var notice = $('cp-notice'), curWrap = $('cp-current-pw-wrap');
          if (closeBtn) closeBtn.style.display = '';
          if (cancelBtn) cancelBtn.style.display = '';
          if (notice) notice.style.display = 'none';
          if (curWrap) curWrap.style.display = '';
        }
        closeModal('modal-change-password');
        showToast(data.message || 'Password updated successfully', 'success');
      } else {
        showToast('Error: ' + (data.message || 'Password change failed'), 'error');
      }
    } catch (err) {
      showToast('Connection error', 'error');
    }
  };

  // ================================================================
  // MASTER KEYS (Settings — up to 3 active keys)
  // ================================================================
  window.loadMasterKeys = async function () {
    var container = $('master-keys-list');
    if (!container) return;
    try {
      var res = await fetch(API_BASE + '/api/auth/master-keys', { headers: authHeaders() });
      if (!res.ok) { container.innerHTML = '<div style="color:#EF4444;font-size:12px;">Failed to load keys</div>'; return; }
      var keys = await res.json();
      if (keys.length === 0) {
        container.innerHTML = '<div style="color:var(--text-muted);font-size:12px;padding:8px;">No active master keys configured.</div>';
        return;
      }
      container.innerHTML = keys.map(function(k) {
        var created = k.createdAt ? new Date(k.createdAt).toLocaleDateString('en-PH', {year:'numeric',month:'short',day:'numeric'}) : '';
        return '<div style="display:flex;align-items:center;gap:10px;padding:8px 10px;border:1px solid var(--border);border-radius:6px;margin-bottom:6px;">' +
          '<i class="ti ti-key" style="color:var(--accent);font-size:16px;flex-shrink:0;"></i>' +
          '<div style="flex:1;">' +
            '<div style="font-size:13px;font-weight:600;">' + escapeHtml(k.label || 'Unnamed Key') + '</div>' +
            '<div style="font-size:10px;color:var(--text-muted);">Added ' + created + '</div>' +
          '</div>' +
          (keys.length > 1
            ? '<button class="btn btn-outline-danger btn-sm" onclick="removeMasterKey(' + k.id + ')" title="Remove this key"><i class="ti ti-trash"></i></button>'
            : '<span style="font-size:10px;color:var(--text-muted);">Last key</span>') +
        '</div>';
      }).join('');
      // Hide add form if at max
      var addForm = $('master-key-add-form');
      if (addForm) addForm.style.display = keys.length >= 3 ? 'none' : '';
    } catch (e) {
      container.innerHTML = '<div style="color:#EF4444;font-size:12px;">Connection error</div>';
    }
  };

  window.addMasterKey = async function () {
    var label   = (($('mk-new-label')   || {}).value || '').trim();
    var rawKey  = (($('mk-new-key')     || {}).value || '').trim();
    var confirm = (($('mk-new-confirm') || {}).value || '').trim();
    if (!rawKey || rawKey.length < 6) { showToast('Key must be at least 6 characters', 'error'); return; }
    if (rawKey !== confirm) { showToast('Keys do not match', 'error'); return; }
    try {
      var res = await fetch(API_BASE + '/api/auth/master-keys', {
        method: 'POST', headers: authHeaders(),
        body: JSON.stringify({ key: rawKey, label: label || null })
      });
      var data = await res.json();
      if (res.ok) {
        showToast('Master key added: ' + (data.label || 'Key'), 'success');
        if ($('mk-new-label'))   $('mk-new-label').value   = '';
        if ($('mk-new-key'))     $('mk-new-key').value     = '';
        if ($('mk-new-confirm')) $('mk-new-confirm').value = '';
        loadMasterKeys();
      } else {
        showToast('Error: ' + (data.message || 'Failed to add key'), 'error');
      }
    } catch (e) { showToast('Connection error', 'error'); }
  };

  window.removeMasterKey = async function (id) {
    if (!confirm('Remove this master key? This cannot be undone.')) return;
    try {
      var res = await fetch(API_BASE + '/api/auth/master-keys/' + id, {
        method: 'DELETE', headers: authHeaders()
      });
      var data = await res.json();
      if (res.ok) {
        showToast('Master key removed', 'success');
        loadMasterKeys();
      } else {
        showToast('Error: ' + (data.message || 'Failed to remove'), 'error');
      }
    } catch (e) { showToast('Connection error', 'error'); }
  };

  /** Legacy function kept for backward compatibility */
  window.changeMasterKey = async function () {
    showToast('Please use the new master keys manager above', 'error');
  };

  // ================================================================
  // NOTIFICATION EMAILS (Settings — low-stock alerts)
  // ================================================================
  window.loadNotificationEmails = async function () {
    var container = $('notif-emails-list');
    if (!container) return;
    try {
      var res = await fetch(API_BASE + '/api/settings/notification-emails', { headers: authHeaders() });
      if (!res.ok) { container.innerHTML = '<div style="color:#EF4444;font-size:12px;">Failed to load</div>'; return; }
      var emails = await res.json();
      if (emails.length === 0) {
        container.innerHTML = '<div style="color:var(--text-muted);font-size:12px;padding:6px;">No notification emails configured yet.</div>';
        return;
      }
      container.innerHTML = emails.map(function(e) {
        var addedBy = e.createdBy ? ' by ' + escapeHtml(e.createdBy) : '';
        var addedAt = e.createdAt ? new Date(e.createdAt).toLocaleDateString('en-PH',{year:'numeric',month:'short',day:'numeric'}) : '';
        return '<div style="display:flex;align-items:center;gap:10px;padding:7px 10px;border:1px solid var(--border);border-radius:6px;margin-bottom:5px;">' +
          '<i class="ti ti-mail" style="color:var(--text-muted);flex-shrink:0;"></i>' +
          '<div style="flex:1;font-size:13px;">' + escapeHtml(e.email) +
            '<div style="font-size:10px;color:var(--text-muted);">Added ' + addedAt + addedBy + '</div>' +
          '</div>' +
          '<button class="btn btn-outline-danger btn-sm" onclick="removeNotificationEmail(' + e.id + ')" title="Remove">' +
            '<i class="ti ti-trash"></i></button>' +
        '</div>';
      }).join('');
    } catch (err) {
      container.innerHTML = '<div style="color:#EF4444;font-size:12px;">Connection error</div>';
    }
  };

  window.addNotificationEmail = async function () {
    var input = $('notif-email-input');
    var email = (input ? input.value : '').trim().toLowerCase();
    if (!email || !email.includes('@')) { showToast('Enter a valid email address', 'error'); return; }
    try {
      var res = await fetch(API_BASE + '/api/settings/notification-emails', {
        method: 'POST', headers: authHeaders(),
        body: JSON.stringify({ email: email })
      });
      var data = await res.json();
      if (res.ok) {
        showToast('Notification email added: ' + email, 'success');
        if (input) input.value = '';
        loadNotificationEmails();
      } else {
        showToast('Error: ' + (data.message || 'Failed to add'), 'error');
      }
    } catch (e) { showToast('Connection error', 'error'); }
  };

  window.removeNotificationEmail = async function (id) {
    if (!confirm('Remove this notification email?')) return;
    try {
      var res = await fetch(API_BASE + '/api/settings/notification-emails/' + id, {
        method: 'DELETE', headers: authHeaders()
      });
      var data = await res.json();
      if (res.ok) {
        showToast('Email removed', 'success');
        loadNotificationEmails();
      } else {
        showToast('Error: ' + (data.message || 'Failed to remove'), 'error');
      }
    } catch (e) { showToast('Connection error', 'error'); }
  };

  // ================================================================
  // Navigation — settings restriction, form state, order-history
  // ================================================================
  window.navigateTo = function (view) {
    // Block Settings for non-Super-Admins
    if (view === 'set' && !isSuperAdmin()) {
      showToast('Settings is restricted to Super Admin only', 'error');
      return;
    }
    // Block Employee List for non-managers
    if (view === 'emp' && !canManageEmployees()) {
      showToast('Employee List is restricted to Administrators and above', 'error');
      return;
    }
    // Block pages not in user's allowedPages
    if (!canAccessPage(view)) {
      showToast('You do not have access to this page', 'error');
      return;
    }

    // Clear replacement mode when navigating away from the new order form
    if (view !== 'new' && appState.replacementMode) {
      appState.replacementMode = null;
      var rplcBanner = $('replacement-mode-banner');
      if (rplcBanner) rplcBanner.style.display = 'none';
      var rplcH4 = document.querySelector('#view-new h4');
      if (rplcH4) rplcH4.textContent = 'New Order';
      var rplcBtn = document.querySelector('button[onclick="addOrder()"]');
      if (rplcBtn) rplcBtn.innerHTML = '<i class="ti ti-check"></i> Submit Order';
    }

    $$('.nav-item').forEach(function (n) { n.classList.toggle('active', n.dataset.view === view); });
    $$('.view').forEach(function (w) { w.classList.remove('active'); });
    const target = $('view-' + view);
    if (target) target.classList.add('active');

    const titles = {
      dash:           ['Dashboard',        "Today's overview"],
      new:            ['New Order',         'Create an order'],
      list:           ['Order List',        "Today's orders"],
      inv:            ['Inventory',         'Stock levels'],
      delivery:       ['Receive Stock',     'Delivery receipt & stock in'],
      'rejected-items':    ['Rejected Items',    'Items rejected during delivery'],
      'purchase-orders':   ['Purchase Orders',   'Create and manage supplier purchase orders'],
      rep:            ['Monthly Report',     'Monthly analytics & insights'],
      'daily-reports':['Daily Reports',     'History of all closed daily sales'],
      'order-history':['Order History',     'Search and export past orders'],
      'collections':  ['Collections',       'Pending payment collection from customers'],
      'delivery-rep': ['Delivery Reports',  'Received stock history'],
      'activity-log': ['Activity Log',      'Admin action history'],
      agents:         ['Agent Registry',     'View and manage agents'],
      'import':       ['Import',             'Import orders and expenses from CSV'],
      transactions:   ['Transaction Ledger', 'Accounting ledger entries'],
      emp:            ['Employee List',      'Manage employees'],
      expenses:       ['Expenses',           'Daily expense tracking'],
      payables:       ['Payables',          'Company payable records'],
      suppliers:      ['Suppliers',         'Manage supplier accounts'],
      set:            ['Settings',          'System configuration'],
    };
    if (titles[view]) {
      $('page-title').textContent  = titles[view][0];
      $('page-subtitle').textContent = titles[view][1];
    }

    if (view === 'new') {
      if (!appState.orderFormReady) { initOrderForm(); }
      else { loadProducts(); }
      renderOrders();
    }
    if (view === 'list')     renderOrderList();
    if (view === 'inv')      renderInventory();
    if (view === 'delivery') {
      initDeliveryForm(); // always refreshes PO dropdown; form body resets only when !deliveryFormReady
    }
    if (view === 'rep')           setTimeout(initReportsView, 50);
    if (view === 'set')           { loadMasterKeys(); loadNotificationEmails(); }
    if (view === 'daily-reports')   loadDailyReports();
    if (view === 'rejected-items')  initRejectedItemsView();
    if (view === 'purchase-orders') loadPurchaseOrders();
    if (view === 'order-history') {
      if ($('history-start') && !$('history-start').value) {
        const d30 = new Date(); d30.setDate(d30.getDate() - 30);
        $('history-start').value = d30.toISOString().split('T')[0];
      }
      if ($('history-end') && !$('history-end').value) {
        const yest = new Date(); yest.setDate(yest.getDate() - 1);
        $('history-end').value = yest.toISOString().split('T')[0];
      }
      renderOrderHistory();
    }
    if (view === 'collections')  loadCollections();
    if (view === 'delivery-rep') renderDeliveryReports(true);
    if (view === 'activity-log') renderActivityLog(true);
    if (view === 'agents')       loadAgents();
    if (view === 'import')       loadImportHistory();
    if (view === 'transactions') loadTransactions();
    if (view === 'expenses')     initExpensesView();
    if (view === 'payables')     loadPayables();
    if (view === 'suppliers')    loadSuppliers();
    if (view === 'emp')          renderUsers();
    if (view === 'dash')         { renderDashboard(); renderTopProductsToday(); loadProductAnalytics(); }
    if (view === 'set')          loadSettings();
  };

  // ================================================================
  // Theme Toggle
  // ================================================================
  window.toggleTheme = function () {
    appState.theme = appState.theme === 'light' ? 'dark' : 'light';
    document.body.dataset.theme = appState.theme;
    const icon = $('theme-icon');
    if (icon) icon.className = appState.theme === 'light' ? 'ti ti-sun' : 'ti ti-moon';
    // Re-build charts with correct theme colors, then refill with live data
    if (typeof initDashboardCharts === 'function') {
      initDashboardCharts();
      renderDashboard(appState.dashPeriod || 'daily');
    }
  };

  // ================================================================
  // NEW ORDER FORM — form state, new fields
  // ================================================================
  async function loadProducts() {
    try {
      const res = await fetch('' + API_BASE + '/api/products', {
        headers: { 'Authorization': 'Bearer ' + localStorage.getItem('rrbm_token') }
      });
      if (res.ok) { appState.cachedProducts = await res.json(); }
      else { appState.cachedProducts = []; }
    } catch (e) { appState.cachedProducts = []; }
  }

  window.onSourceChange = function () {
    const v = $('field-source').value;
    const agentWrap    = $('field-agent-wrap');
    const resellerWrap = $('field-reseller-wrap');
    const fbWrap       = $('field-fb-wrap');
    const ecomWrap     = $('ecommercePlatformGroup');
    const resellerLbl  = $('label-reseller');

    if (agentWrap)    agentWrap.style.display    = (v === 'AGENT')        ? '' : 'none';
    if (resellerWrap) resellerWrap.style.display = (v === 'RESELLER' || v === 'DISTRIBUTOR') ? '' : 'none';
    if (fbWrap)       fbWrap.style.display       = (v === 'FACEBOOK_PAGE') ? '' : 'none';
    if (ecomWrap) {
      ecomWrap.style.display = (v === 'ECOMMERCE') ? '' : 'none';
      if (v !== 'ECOMMERCE' && $('ecommercePlatform')) $('ecommercePlatform').value = '';
    }
    if (resellerLbl) resellerLbl.textContent = v === 'DISTRIBUTOR' ? 'Distributor Name' : 'Reseller Name';
    // Load agent dropdown and show/hide per-item O.P. fields
    if (v === 'AGENT') loadAgentOptions();
    $$('.agent-op-row').forEach(function(r) { r.style.display = v === 'AGENT' ? '' : 'none'; });
  };

  var _cachedAgents = [];

  async function loadAgentOptions() {
    var input = $('field-agent-input');
    if (input) input.placeholder = 'Loading agents…';
    try {
      var res = await fetch(API_BASE + '/api/agents?status=ACTIVE', { headers: authHeaders() });
      if (!res.ok) { if (input) input.placeholder = 'Failed to load agents'; return; }
      _cachedAgents = await res.json();
      if (input) input.placeholder = 'Type to search agents…';
      setupAgentAutocomplete();
    } catch(e) {
      if (input) input.placeholder = 'Failed to load agents';
    }
  }

  function setupAgentAutocomplete() {
    var input    = $('field-agent-input');
    var dropdown = $('field-agent-dropdown');
    if (!input || !dropdown) return;
    // Re-attach by cloning to avoid duplicate listeners
    var fresh = input.cloneNode(true);
    input.parentNode.replaceChild(fresh, input);
    fresh.addEventListener('input', function () {
      var t = this.value.toLowerCase().trim();
      renderAgentDropdown(dropdown, t.length === 0 ? _cachedAgents : _cachedAgents.filter(function(a) {
        return a.fullName.toLowerCase().includes(t) || a.agentCode.toLowerCase().includes(t);
      }));
    });
    fresh.addEventListener('focus', function () {
      var t = this.value.toLowerCase().trim();
      renderAgentDropdown(dropdown, t.length === 0 ? _cachedAgents : _cachedAgents.filter(function(a) {
        return a.fullName.toLowerCase().includes(t) || a.agentCode.toLowerCase().includes(t);
      }));
    });
    document.addEventListener('click', function (e) {
      if (!fresh.contains(e.target) && !dropdown.contains(e.target)) dropdown.classList.remove('show');
    });
  }

  function renderAgentDropdown(dropdown, agents) {
    if (!dropdown) return;
    if (!agents || agents.length === 0) {
      dropdown.innerHTML = '<div class="product-dropdown-item" style="color:#999;cursor:default;">No agents found</div>';
      dropdown.classList.add('show');
      return;
    }
    dropdown.innerHTML = agents.map(function(a) {
      return '<div class="product-dropdown-item" data-id="' + a.id + '" data-name="' + escapeHtml(a.fullName) + '" data-code="' + escapeHtml(a.agentCode) + '">'
        + '<strong>' + escapeHtml(a.fullName) + '</strong>'
        + ' <span style="font-size:11px;color:var(--text-muted);">(' + escapeHtml(a.agentCode) + ')</span>'
        + (a.territory ? ' <span style="font-size:10px;color:var(--text-muted);">· ' + escapeHtml(a.territory) + '</span>' : '')
        + '</div>';
    }).join('');
    dropdown.classList.add('show');
    dropdown.querySelectorAll('.product-dropdown-item[data-id]').forEach(function(item) {
      item.addEventListener('click', function() {
        var agentInput = $('field-agent-input');
        var agentHidden = $('field-agent-id');
        if (agentInput)  agentInput.value  = this.getAttribute('data-name') + ' (' + this.getAttribute('data-code') + ')';
        if (agentHidden) agentHidden.value = this.getAttribute('data-id');
        dropdown.classList.remove('show');
      });
    });
  }

  window.openRegisterAgentModal = function () {
    ['reg-agent-name','reg-agent-contact','reg-agent-territory','reg-agent-email','reg-agent-notes']
      .forEach(function (id) { if ($(id)) $(id).value = ''; });
    var m = $('modal-register-agent');
    if (m) m.classList.add('open');
  };

  window.closeRegisterAgentModal = function () {
    var m = $('modal-register-agent');
    if (m) m.classList.remove('open');
  };

  window.submitRegisterAgent = async function () {
    var fullName      = (($('reg-agent-name')      || {}).value || '').trim();
    var contactNumber = (($('reg-agent-contact')   || {}).value || '').trim();
    var territory     = (($('reg-agent-territory') || {}).value || '').trim();
    var email         = (($('reg-agent-email')     || {}).value || '').trim();
    var notes         = (($('reg-agent-notes')     || {}).value || '').trim();

    if (!fullName)      { showToast('Full name is required', 'error'); return; }
    if (!contactNumber) { showToast('Contact number is required', 'error'); return; }
    if (!territory)     { showToast('Territory is required', 'error'); return; }

    try {
      var res = await fetch(API_BASE + '/api/agents', {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ fullName: fullName, contactNumber: contactNumber, territory: territory, email: email, notes: notes })
      });
      var data = await res.json();
      if (res.ok) {
        showToast('Agent ' + data.agentCode + ' registered', 'success');
        window.closeRegisterAgentModal();
        await loadAgentOptions();
        if ($('view-agents') && $('view-agents').classList.contains('active')) loadAgents();
        if (data.id) {
          if ($('field-agent-id'))    $('field-agent-id').value    = String(data.id);
          if ($('field-agent-input')) $('field-agent-input').value = data.fullName + ' (' + data.agentCode + ')';
        }
      } else {
        showToast('Error: ' + (data.error || 'Registration failed'), 'error');
      }
    } catch (err) {
      showToast('Connection error', 'error');
    }
  };

  async function initOrderForm(forceReset) {
    await loadProducts();
    if (!forceReset && appState.orderFormReady) return;

    const container = $('orderItemsContainer'); if (!container) return;
    container.innerHTML = ''; appState.itemRowCounter = 0; addItemRow();
    const addBtn = $('addItemBtn');
    if (addBtn) { const fresh = addBtn.cloneNode(true); addBtn.parentNode.replaceChild(fresh, addBtn); fresh.addEventListener('click', addItemRow); }
    const disc = $('orderDiscount');
    if (disc) { disc.removeEventListener('input', calculateOrderTotals); disc.addEventListener('input', calculateOrderTotals); }
    const fee = $('orderDeliveryFee');
    if (fee) { fee.removeEventListener('input', calculateOrderTotals); fee.addEventListener('input', calculateOrderTotals); }
    calculateOrderTotals();
    appState.orderFormReady = true;
  }

  function addItemRow() {
    appState.itemRowCounter++;
    const num = appState.itemRowCounter;
    const rowId = 'item-row-' + num;
    const container = $('orderItemsContainer'); if (!container) return;
    var isAgent = ($('field-source') || {}).value === 'AGENT';
    container.insertAdjacentHTML('beforeend', '<div class="order-item-row" id="' + rowId + '"><div class="row align-items-end">'
      + '<div class="col-md-4"><label class="form-label">Product <span class="text-danger">*</span></label><div class="product-autocomplete-wrapper"><input type="text" class="form-control product-input" id="productInput-' + num + '" placeholder="Type to search products..." autocomplete="off" required><input type="hidden" class="product-id-hidden" id="productId-' + num + '" value=""><input type="hidden" id="warehouse-' + num + '" value="wh1"><div class="product-dropdown" id="productDropdown-' + num + '"></div><div id="productStatus-' + num + '" style="display:none;font-size:11px;margin-top:3px;"></div></div></div>'
      + '<div class="col-md-2"><label class="form-label">Qty <span class="text-danger">*</span></label><input type="number" class="form-control item-quantity" id="quantity-' + num + '" min="1" value="1" required></div>'
      + '<div class="col-md-2"><label class="form-label">Unit Price (₱) <span class="text-danger">*</span></label><input type="number" class="form-control item-unit-price" id="unitPrice-' + num + '" min="0" step="0.01" value="0" required></div>'
      + '<div class="col-md-2"><label class="form-label">Stock Info</label><div class="stock-info-display" id="stockInfo-' + num + '" style="font-size:11px;padding:6px 8px;background:#f0f0f0;border-radius:6px;min-height:34px;display:flex;align-items:center;color:#666;">Select a product</div></div>'
      + '<div class="col-md-1"><label class="form-label">Subtotal</label><input type="text" class="form-control item-subtotal" id="subtotal-' + num + '" value="₱0.00" readonly style="background-color:#e9ecef;font-size:12px;"></div>'
      + '<div class="col-md-1 text-center"><label class="form-label">&nbsp;</label><button type="button" class="remove-item-btn d-block" onclick="removeItemRow(\'' + rowId + '\')"><i class="ti ti-trash"></i></button></div>'
      + '</div>'
      + '<div class="row agent-op-row g-2 mt-0" id="op-row-' + num + '" style="display:' + (isAgent ? '' : 'none') + ';padding:4px 0;">'
      + '<div class="col-md-3"><label class="form-label" style="font-size:11px;margin-bottom:2px;">Base Price/Unit (₱)</label><input type="number" class="form-control form-control-sm" id="basePrice-' + num + '" min="0" step="0.01" placeholder="Company price per unit"></div>'
      + '<div class="col-md-3"><label class="form-label" style="font-size:11px;margin-bottom:2px;">Over Price/Unit (₱)</label><input type="number" class="form-control form-control-sm" id="opPerUnit-' + num + '" min="0" step="0.01" placeholder="Agent commission per unit"></div>'
      + '<div class="col-md-6 d-flex align-items-end" style="padding-bottom:4px;"><small style="color:#888;font-size:11px;">Commission = Over Price × Qty</small></div>'
      + '</div>'
      + '</div>');
    setupProductAutocomplete(num);
    const q = $('quantity-' + num); if (q) q.addEventListener('input', function () { calcItemSubtotal(num); });
    const p = $('unitPrice-' + num); if (p) p.addEventListener('input', function () { calcItemSubtotal(num); });
  }

  window.removeItemRow = function (rowId) { const row = $(rowId); if (row) { row.remove(); calculateOrderTotals(); } };

  function setupProductAutocomplete(rowNum) {
    const input = $('productInput-' + rowNum); const dropdown = $('productDropdown-' + rowNum);
    if (!input || !dropdown) return;
    input.addEventListener('input', function () { const t = this.value.toLowerCase().trim(); renderProductDropdown(dropdown, t.length === 0 ? appState.cachedProducts : appState.cachedProducts.filter(function (p) { return p.name.toLowerCase().includes(t); }), rowNum); });
    input.addEventListener('focus', function () { const t = this.value.toLowerCase().trim(); renderProductDropdown(dropdown, t.length === 0 ? appState.cachedProducts : appState.cachedProducts.filter(function (p) { return p.name.toLowerCase().includes(t); }), rowNum); });
    // Clear productId and show amber indicator whenever staff edits the text field after a selection
    input.addEventListener('input', function () {
      var pid = $('productId-' + rowNum); if (pid) pid.value = '';
      var ps  = $('productStatus-' + rowNum);
      if (ps) {
        if (this.value.trim()) { ps.style.display = ''; ps.style.color = '#D97706'; ps.textContent = '⚠ Select from catalog'; }
        else { ps.style.display = 'none'; ps.textContent = ''; }
      }
    });
    document.addEventListener('click', function (e) { if (!input.contains(e.target) && !dropdown.contains(e.target)) dropdown.classList.remove('show'); });
  }

  function renderProductDropdown(dropdown, products, rowNum) {
    if (products.length === 0) { dropdown.innerHTML = '<div class="product-dropdown-item" style="color:#999;cursor:default;">No products found</div>'; dropdown.classList.add('show'); return; }
    let html = '';
    products.forEach(function (product) {
      const wh1 = product.stockWh1 || 0, wh2 = product.stockWh2 || 0, wh3 = product.stockWh3 || 0, total = wh1 + wh2 + wh3;
      let stockClass, stockLabel, whParts, primaryWh;

      if (product.isSet) {
        // For set products: show effective set stock (limited by components)
        const eff = effectiveSetStock(product);
        stockClass = (eff === null || eff <= 0) ? 'critical' : 'ok';
        stockLabel = eff === null ? 'no components' : (eff + ' sets');
        whParts = product.components && product.components.length
          ? product.components.map(function (c) { return escapeHtml(c.componentProductName || '') + ' ×' + c.quantityPerSet; })
          : ['no components'];
        primaryWh = 'wh1'; // set products use wh1 as default; deduction targets all components in same wh
      } else {
        stockClass = 'ok'; stockLabel = total.toLocaleString() + ' pcs';
        if (total <= 0) { stockClass = 'critical'; stockLabel = 'Out of stock'; }
        else if (total <= (product.thresholdCritical || 0)) { stockClass = 'critical'; stockLabel = total.toLocaleString() + ' (critical)'; }
        else if (total <= (product.thresholdLow || 0)) { stockClass = 'low'; stockLabel = total.toLocaleString() + ' (low)'; }
        whParts = []; if (wh1 > 0) whParts.push('WH1:' + wh1.toLocaleString()); if (wh2 > 0) whParts.push('WH2:' + wh2.toLocaleString()); if (wh3 > 0) whParts.push('Balagtas:' + wh3.toLocaleString());
        primaryWh = 'wh1'; if (wh2 > wh1 && wh2 >= wh3) primaryWh = 'wh2'; if (wh3 > wh1 && wh3 > wh2) primaryWh = 'wh3';
      }

      const setLabel = product.isSet ? ' <span style="font-size:9px;font-weight:700;background:#D4860A;color:#fff;padding:1px 4px;border-radius:2px;vertical-align:middle;">SET</span>' : '';
      html += '<div class="product-dropdown-item" data-id="' + product.id + '" data-name="' + escapeHtml(product.name) + '" data-price="' + product.unitPrice + '" data-wh="' + primaryWh + '" data-wh1="' + wh1 + '" data-wh2="' + wh2 + '" data-wh3="' + wh3 + '" data-row="' + rowNum + '"><div style="flex:1;"><div class="product-name">' + product.name + setLabel + '</div><div style="font-size:10px;color:#888;">' + (whParts.join(' · ') || 'No stock') + '</div></div><div style="text-align:right;"><span class="product-price">₱' + parseFloat(product.unitPrice).toFixed(2) + '</span><br><span class="product-stock ' + stockClass + '">' + stockLabel + '</span></div></div>';
    });
    dropdown.innerHTML = html; dropdown.classList.add('show');
    dropdown.querySelectorAll('.product-dropdown-item[data-id]').forEach(function (item) {
      item.addEventListener('click', function () {
        const rn = this.getAttribute('data-row'), wh1 = parseInt(this.getAttribute('data-wh1')) || 0, wh2 = parseInt(this.getAttribute('data-wh2')) || 0, wh3 = parseInt(this.getAttribute('data-wh3')) || 0, pw = this.getAttribute('data-wh');
        $('productInput-' + rn).value = this.getAttribute('data-name');
        $('productId-' + rn).value = this.getAttribute('data-id');
        var ps = $('productStatus-' + rn); if (ps) { ps.style.display = ''; ps.style.color = '#10B981'; ps.textContent = '✓ Catalog product'; }
        $('unitPrice-' + rn).value = parseFloat(this.getAttribute('data-price')).toFixed(2);
        $('warehouse-' + rn).value = pw;
        const si = $('stockInfo-' + rn);
        if (si) { let pts = []; if (wh1 > 0) pts.push('<span style="color:' + (pw === 'wh1' ? '#10B981;font-weight:600' : '#666') + '">WH1: ' + wh1.toLocaleString() + '</span>'); if (wh2 > 0) pts.push('<span style="color:' + (pw === 'wh2' ? '#10B981;font-weight:600' : '#666') + '">WH2: ' + wh2.toLocaleString() + '</span>'); if (wh3 > 0) pts.push('<span style="color:' + (pw === 'wh3' ? '#10B981;font-weight:600' : '#666') + '">Balagtas: ' + wh3.toLocaleString() + '</span>'); si.innerHTML = pts.length > 0 ? '📦 ' + pts.join(' · ') : '<span style="color:#EF4444;">No stock</span>'; }
        dropdown.classList.remove('show'); calcItemSubtotal(rn);
      });
    });
  }

  function calcItemSubtotal(rn) {
    const q = parseFloat(($('quantity-' + rn) || {}).value) || 0, p = parseFloat(($('unitPrice-' + rn) || {}).value) || 0;
    const el = $('subtotal-' + rn); if (el) el.value = '₱' + (q * p).toFixed(2);
    calculateOrderTotals();
  }

  window.applyDiscountPreset = function (pct) {
    let sub = 0;
    document.querySelectorAll('.item-subtotal').forEach(function (i) {
      sub += parseFloat(i.value.replace('₱', '')) || 0;
    });
    const disc = $('orderDiscount');
    if (disc) {
      disc.value = pct === 0 ? '0' : (sub * pct / 100).toFixed(2);
      calculateOrderTotals();
    }
  };

  function calculateOrderTotals() {
    let sub = 0; $$('.item-subtotal').forEach(function (i) { sub += parseFloat(i.value.replace('₱', '')) || 0; });
    const disc = parseFloat(($('orderDiscount')     || {}).value) || 0;
    const fee  = parseFloat(($('orderDeliveryFee')  || {}).value) || 0;
    if ($('orderSubtotal'))       $('orderSubtotal').textContent       = '₱' + sub.toFixed(2);
    if ($('orderDiscountAmount')) $('orderDiscountAmount').textContent  = '-₱' + disc.toFixed(2);
    if ($('orderDeliveryFeeDisplay')) {
      $('orderDeliveryFeeDisplay').style.display = fee > 0 ? 'flex' : 'none';
    }
    if ($('orderDeliveryFeeAmt'))    $('orderDeliveryFeeAmt').textContent = '+₱' + fee.toFixed(2);
    if ($('orderTotal'))          $('orderTotal').textContent           = '₱' + (sub - disc + fee).toFixed(2);
  }

  // ================================================================
  // Replacement Order — open pre-populated form
  // ================================================================
  window.openReplacementForm = async function (originalOrderId) {
    // Load original order from cache or fetch
    var order = (appState.allOrders || []).find(function(o) { return o.id === originalOrderId; })
             || (appState.orderHistoryAll || []).find(function(o) { return o.id === originalOrderId; });
    if (!order) {
      try {
        var r = await fetch(API_BASE + '/api/orders/' + originalOrderId, { headers: authHeaders() });
        if (r.ok) order = await r.json();
      } catch (e) {}
    }
    if (!order) { showToast('Could not load order details', 'error'); return; }

    // Set replacement mode flag BEFORE navigating so navigateTo('new') does not clear it
    appState.replacementMode = { originalOrderId: originalOrderId };

    // Navigate to new order view (initialises form if needed)
    navigateTo('new');

    // Pre-populate header fields from original order
    if ($('field-customer'))   $('field-customer').value   = order.customerName || '';
    if ($('field-source'))     $('field-source').value     = order.source       || '';
    // Trigger onSourceChange to show/hide conditional contact fields before filling them
    if (typeof window.onSourceChange === 'function') window.onSourceChange();
    // Contact name — fill whichever conditional field matches the source
    if (order.source === 'AGENT' && order.agentId) {
      loadAgentOptions().then(async function() {
        var match = _cachedAgents.find(function(a) { return a.id === order.agentId; });
        if (match) {
          if ($('field-agent-id'))    $('field-agent-id').value    = String(order.agentId);
          if ($('field-agent-input')) $('field-agent-input').value = match.fullName + ' (' + match.agentCode + ')';
        } else {
          // Agent is INACTIVE — clear hidden id to force re-selection; show name as placeholder
          if ($('field-agent-id')) $('field-agent-id').value = '';
          try {
            var agRes = await fetch(API_BASE + '/api/agents/' + order.agentId, { headers: authHeaders() });
            if (agRes.ok) {
              var ag = await agRes.json();
              if ($('field-agent-input')) $('field-agent-input').placeholder = ag.fullName + ' (' + ag.agentCode + ') — Inactive, select a new agent';
            }
          } catch (e) {}
        }
      });
    }
    if ((order.source === 'RESELLER' || order.source === 'DISTRIBUTOR') && $('field-reseller'))
      $('field-reseller').value = order.agentName || '';
    if (order.source === 'FACEBOOK_PAGE' && $('field-fb'))
      $('field-fb').value       = order.fbPage || '';
    if (order.source === 'ECOMMERCE' && $('ecommercePlatform'))
      $('ecommercePlatform').value = order.ecommercePlatform || '';
    if ($('field-payment'))    $('field-payment').value    = order.paymentMode  || '';
    if ($('field-order-type')) $('field-order-type').value = order.orderType    || 'STANDARD';
    if ($('field-address'))    $('field-address').value    = order.address      || '';

    // Show banner, update heading, update submit button label
    var banner = $('replacement-mode-banner');
    if (banner) banner.style.display = '';
    var origLabel = $('rplc-original-id');
    if (origLabel) origLabel.textContent = originalOrderId;
    var h4 = document.querySelector('#view-new h4');
    if (h4) h4.textContent = 'New Replacement Order';
    var submitBtn = document.querySelector('button[onclick="addOrder()"]');
    if (submitBtn) submitBtn.innerHTML = '<i class="ti ti-check"></i> Submit Replacement Order';
  };

  // ================================================================
  // Replacement Order — submit
  // ================================================================
  window.addReplacementOrder = async function () {
    var originalOrderId = appState.replacementMode && appState.replacementMode.originalOrderId;
    if (!originalOrderId) { showToast('Replacement mode lost — please start again', 'error'); return; }

    const customerName = (($('field-customer')  || {}).value || '').trim();
    const source       = ($('field-source')     || {}).value;
    const agentId      = source === 'AGENT' ? (parseInt(($('field-agent-id') || {}).value) || null) : null;
    const resellerName = ($('field-reseller')   || {}).value || '';
    const fbPage       = ($('field-fb')         || {}).value || '';
    const paymentMode  = ($('field-payment')    || {}).value;
    const orderType    = ($('field-order-type') || {}).value || 'STANDARD';
    const address      = ($('field-address')    || {}).value || '';
    const discount     = parseFloat(($('orderDiscount')    || {}).value) || 0;
    const deliveryFee  = parseFloat(($('orderDeliveryFee') || {}).value) || 0;
    const notes        = ($('orderNotes') || {}).value || '';

    if (!customerName) { showToast('Please enter customer name', 'error'); return; }
    if (!source)       { showToast('Please select order source', 'error'); return; }
    if (!paymentMode)  { showToast('Please select payment mode', 'error'); return; }
    if (source === 'AGENT' && !agentId) { showToast('Please select an agent', 'error'); return; }

    let ecommercePlatform = null;
    if (source === 'ECOMMERCE') {
      ecommercePlatform = ($('ecommercePlatform') || {}).value;
      if (!ecommercePlatform) { showToast('Please select an e-commerce platform', 'error'); return; }
    }

    let contactName = null;
    if (source === 'RESELLER' || source === 'DISTRIBUTOR') contactName = resellerName;

    const itemRows = $$('.order-item-row');
    if (itemRows.length === 0) { showToast('Please add at least one item', 'error'); return; }

    const items = []; let hasError = false;
    itemRows.forEach(function (row) {
      if (hasError) return;
      const rn          = row.id.replace('item-row-', '');
      const productName = ($('productInput-' + rn) || {}).value || '';
      const productId   = ($('productId-'    + rn) || {}).value || '';
      const quantity    = parseInt(($('quantity-'  + rn) || {}).value) || 0;
      const unitPrice   = parseFloat(($('unitPrice-'  + rn) || {}).value) || 0;
      const warehouse   = ($('warehouse-'    + rn) || {}).value || 'wh1';
      if (!productName.trim()) { showToast('Please select a product for all items', 'error'); hasError = true; return; }
      if (!productId || productId === '') { showToast('Select "' + productName + '" from the product catalog — type and choose from the list', 'error'); var ps0 = $('productStatus-' + rn); if (ps0) { ps0.style.display = ''; ps0.style.color = '#D97706'; ps0.textContent = '⚠ Select from catalog'; } hasError = true; return; }
      if (quantity <= 0)       { showToast('Quantity must be at least 1 for ' + productName, 'error'); hasError = true; return; }
      if (unitPrice <= 0)      { showToast('Unit price must be greater than 0 for ' + productName, 'error'); hasError = true; return; }
      const item = { productName: productName.trim(), quantity, unitPrice, warehouse };
      item.productId = parseInt(productId);
      if (source === 'AGENT') {
        const bp = parseFloat(($('basePrice-' + rn) || {}).value);
        const op = parseFloat(($('opPerUnit-' + rn) || {}).value);
        if (!isNaN(bp) && bp > 0) item.basePrice = bp;
        if (!isNaN(op) && op > 0) item.opPerUnit = op;
      }
      items.push(item);
    });
    if (hasError) return;

    const orderRequest = {
      customerName, source,
      agentId:          agentId,
      agentName:        contactName || null,
      fbPage:           source === 'FACEBOOK_PAGE' ? fbPage : null,
      ecommercePlatform,
      paymentMode, orderType,
      address:          address || null,
      discount, deliveryFee, notes, items
    };

    if (window._addOrderSubmitting) return;
    window._addOrderSubmitting = true;
    const _submitBtn = document.querySelector('button[onclick="addOrder()"]');
    if (_submitBtn) { _submitBtn.disabled = true; _submitBtn.innerHTML = 'Creating…'; }

    try {
      const token = localStorage.getItem('rrbm_token');
      if (!token) { showToast('Please login first', 'error'); return; }
      const res = await fetch(API_BASE + '/api/orders/' + originalOrderId + '/replacement', {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify(orderRequest)
      });
      if (!res.ok) {
        let msg = 'Failed to create replacement order';
        try { const d = await res.json(); msg = d.message || d.error || msg; } catch (e) {}
        showToast('Error: ' + msg, 'error');
        return;
      }
      const created = await res.json();
      showToast('Replacement order ' + created.id + ' created for ' + originalOrderId, 'success');
      clearOrderForm();      // clears replacementMode flag and restores form state
      navigateTo('list');
    } catch (err) {
      showToast('Connection error. Is the backend running?', 'error');
    } finally {
      window._addOrderSubmitting = false;
      // Restore correct button label based on whether replacement mode is still active (error path)
      if (_submitBtn) {
        _submitBtn.disabled = false;
        _submitBtn.innerHTML = appState.replacementMode
          ? '<i class="ti ti-check"></i> Submit Replacement Order'
          : '<i class="ti ti-check"></i> Submit Order';
      }
    }
  };

  // ================================================================
  // Submit Order
  // ================================================================
  window.addOrder = async function () {
    // Delegate to replacement flow when replacement mode is active
    if (appState.replacementMode) { addReplacementOrder(); return; }
    const customerName = (($('field-customer')  || {}).value || '').trim();
    const source       = ($('field-source')     || {}).value;
    const agentId      = source === 'AGENT' ? (parseInt(($('field-agent-id') || {}).value) || null) : null;
    const resellerName = ($('field-reseller')   || {}).value || '';
    const fbPage       = ($('field-fb')         || {}).value || '';
    const paymentMode  = ($('field-payment')    || {}).value;
    const orderType    = ($('field-order-type') || {}).value || 'STANDARD';
    const address      = ($('field-address')    || {}).value || '';
    const discount     = parseFloat(($('orderDiscount')    || {}).value) || 0;
    const deliveryFee  = parseFloat(($('orderDeliveryFee') || {}).value) || 0;
    const notes        = ($('orderNotes') || {}).value || '';

    if (!customerName) { showToast('Please enter customer name', 'error'); return; }
    if (!source)       { showToast('Please select order source', 'error'); return; }
    if (!paymentMode)  { showToast('Please select payment mode', 'error'); return; }
    if (source === 'AGENT' && !agentId) { showToast('Please select an agent', 'error'); return; }

    let ecommercePlatform = null;
    if (source === 'ECOMMERCE') {
      ecommercePlatform = ($('ecommercePlatform') || {}).value;
      if (!ecommercePlatform) { showToast('Please select an e-commerce platform', 'error'); return; }
    }

    let contactName = null;
    if (source === 'RESELLER' || source === 'DISTRIBUTOR') contactName = resellerName;

    const itemRows = $$('.order-item-row');
    if (itemRows.length === 0) { showToast('Please add at least one item', 'error'); return; }

    const items = []; let hasError = false;
    itemRows.forEach(function (row) {
      if (hasError) return;
      const rn        = row.id.replace('item-row-', '');
      const productName = ($('productInput-' + rn) || {}).value || '';
      const productId = ($('productId-' + rn) || {}).value || '';
      const quantity  = parseInt(($('quantity-' + rn) || {}).value) || 0;
      const unitPrice = parseFloat(($('unitPrice-' + rn) || {}).value) || 0;
      const warehouse = ($('warehouse-' + rn) || {}).value || 'wh1';
      if (!productName.trim()) { showToast('Please select a product for all items', 'error'); hasError = true; return; }
      if (!productId || productId === '') { showToast('Select "' + productName + '" from the product catalog — type and choose from the list', 'error'); var ps0 = $('productStatus-' + rn); if (ps0) { ps0.style.display = ''; ps0.style.color = '#D97706'; ps0.textContent = '⚠ Select from catalog'; } hasError = true; return; }
      if (quantity <= 0)       { showToast('Quantity must be at least 1 for ' + productName, 'error'); hasError = true; return; }
      if (unitPrice <= 0)      { showToast('Unit price must be greater than 0 for ' + productName, 'error'); hasError = true; return; }
      const item = { productName: productName.trim(), quantity, unitPrice, warehouse };
      item.productId = parseInt(productId);
      if (source === 'AGENT') {
        const bp = parseFloat(($('basePrice-' + rn) || {}).value);
        const op = parseFloat(($('opPerUnit-' + rn) || {}).value);
        if (!isNaN(bp) && bp > 0) item.basePrice = bp;
        if (!isNaN(op) && op > 0) item.opPerUnit = op;
      }
      items.push(item);
    });
    if (hasError) return;

    const orderRequest = {
      customerName, source,
      agentId:          agentId,
      agentName:        contactName || null,
      fbPage:           source === 'FACEBOOK_PAGE' ? fbPage : null,
      ecommercePlatform,
      paymentMode, orderType,
      address:          address || null,
      discount, deliveryFee, notes, items
    };

    // M-20: Double-submit lock — all validation early-returns have already fired above.
    // Lock here so a second click during the async fetch is a no-op.
    if (window._addOrderSubmitting) return;
    window._addOrderSubmitting = true;
    const _submitBtn = document.querySelector('button[onclick="addOrder()"]');
    if (_submitBtn) { _submitBtn.disabled = true; _submitBtn.innerHTML = 'Creating…'; }

    try {
      const token = localStorage.getItem('rrbm_token');
      if (!token) { showToast('Please login first', 'error'); return; }
      const res = await fetch('' + API_BASE + '/api/orders', { method: 'POST', headers: authHeaders(), body: JSON.stringify(orderRequest) });
      if (!res.ok) { let msg = 'Failed to create order'; try { const d = await res.json(); msg = d.message || d.error || msg; } catch (e) {} showToast('Error: ' + msg, 'error'); return; }
      const created = await res.json();
      showToast('Order created: ' + created.id, 'success');
      clearOrderForm();
      renderOrders();
    } catch (err) {
      showToast('Connection error. Is the backend running?', 'error');
    } finally {
      window._addOrderSubmitting = false;
      if (_submitBtn) { _submitBtn.disabled = false; _submitBtn.innerHTML = '<i class="ti ti-check"></i> Submit Order'; }
    }
  };

  function clearOrderForm() {
    if ($('field-customer'))         $('field-customer').value = '';
    if ($('field-source'))           $('field-source').value = '';
    if ($('field-agent-id'))          $('field-agent-id').value    = '';
    if ($('field-agent-input'))       $('field-agent-input').value  = '';
    if ($('field-reseller'))         $('field-reseller').value = '';
    if ($('field-fb'))               $('field-fb').value = '';
    if ($('field-payment'))          $('field-payment').value = '';
    if ($('field-order-type'))       $('field-order-type').value = 'STANDARD';
    if ($('field-address'))          $('field-address').value = '';
    if ($('orderDiscount'))          $('orderDiscount').value = '0';
    if ($('orderDeliveryFee'))       $('orderDeliveryFee').value = '0';
    if ($('orderDeliveryFeeDisplay'))$('orderDeliveryFeeDisplay').style.display = 'none';
    if ($('orderNotes'))             $('orderNotes').value = '';
    if ($('field-agent-wrap'))       $('field-agent-wrap').style.display = 'none';
    if ($('field-reseller-wrap'))    $('field-reseller-wrap').style.display = 'none';
    if ($('field-fb-wrap'))          $('field-fb-wrap').style.display = 'none';
    if ($('ecommercePlatformGroup')) $('ecommercePlatformGroup').style.display = 'none';
    if ($('ecommercePlatform'))      $('ecommercePlatform').value = '';
    const c = $('orderItemsContainer'); if (c) { c.innerHTML = ''; appState.itemRowCounter = 0; addItemRow(); }
    // Clear replacement mode if active
    appState.replacementMode = null;
    var rplcBanner2 = $('replacement-mode-banner');
    if (rplcBanner2) rplcBanner2.style.display = 'none';
    var rplcH42 = document.querySelector('#view-new h4');
    if (rplcH42) rplcH42.textContent = 'New Order';
    var rplcBtn2 = document.querySelector('button[onclick="addOrder()"]');
    if (rplcBtn2) rplcBtn2.innerHTML = '<i class="ti ti-check"></i> Submit Order';
    calculateOrderTotals();
  }
  window.clearOrderForm = clearOrderForm;

  // ================================================================
  // Low Stock — full-list modal
  // ================================================================
  window.openLowStockModal = function () {
    const items   = window._allLowStockItems || [];
    const tbody   = $('low-stock-all-tbody');
    const countEl = $('low-stock-modal-count');

    if (countEl) {
      countEl.textContent = '— ' + items.length + ' item' + (items.length !== 1 ? 's' : '');
    }

    if (tbody) {
      if (items.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-muted);padding:20px;">No low stock items.</td></tr>';
      } else {
        tbody.innerHTML = items.map(function (p) {
          const shortfall = (p.threshold || 0) - (p.stock || 0);
          const tagCls = p.tag === 'HOT' ? 'badge-hot' : p.tag === 'SELLING' ? 'badge-selling' : 'badge-slow';
          return '<tr>'
            + '<td><strong>' + escapeHtml(p.name) + '</strong></td>'
            + '<td><span class="badge ' + tagCls + '">' + (p.tag || '—') + '</span></td>'
            + '<td style="color:#EF4444;font-weight:600;">' + (p.stock || 0).toLocaleString() + ' pcs</td>'
            + '<td>' + (p.threshold || 0).toLocaleString() + ' pcs</td>'
            + '<td style="color:#EF4444;">−' + shortfall.toLocaleString() + ' pcs</td>'
            + '</tr>';
        }).join('');
      }
    }

    const overlay = $('modal-low-stock-all');
    if (overlay) overlay.classList.add('open');
  };

  // ================================================================
  // Cancel Order — master key protected
  // ================================================================
  // ================================================================
  // ITEM-LEVEL VOID MODAL (Change 4 — open, Change 5 — render items)
  // ================================================================

  /** Open the item-level void modal for a same-day order.
   *  Fetches the full order so per-item voidedQuantity is available. */
  window.openItemVoidModal = async function (orderId) {
    // Reset all state
    appState.ivmOrder = null;
    appState.ivmTier  = null;

    // Reset inputs
    var r = $('ivm-reason');   if (r) r.value = '';
    var sk = $('ivm-security-key'); if (sk) sk.value = '';
    var mk = $('ivm-master-key');   if (mk) mk.value = '';

    // Reset tier sections — start in Tier 1 state
    var t1 = $('ivm-tier1-section'); if (t1) t1.style.display = '';
    var t2 = $('ivm-tier2-section'); if (t2) t2.style.display = 'none';

    // Reset totals bar
    $('ivm-order-id').textContent  = orderId;
    $('ivm-orig-total').textContent  = '₱—';
    $('ivm-voiding-now').textContent = '₱0.00';
    $('ivm-new-total').textContent   = '₱—';
    $('ivm-new-total').style.color   = '';

    // Reset submit button
    var btn = $('ivm-submit-btn');
    if (btn) btn.disabled = true;
    var lbl = $('ivm-submit-label');
    if (lbl) lbl.textContent = 'Void Items';

    // Show loading state and open modal
    $('ivm-items-container').innerHTML =
      '<div style="text-align:center;color:var(--text-muted);padding:16px;font-size:13px;">Loading order…</div>';
    $('modal-item-void').classList.add('open');

    // Fetch full order (need voidedQuantity per item)
    try {
      var res = await fetch(API_BASE + '/api/orders/' + orderId, { headers: authHeaders() });
      if (!res.ok) {
        $('ivm-items-container').innerHTML =
          '<div style="color:#EF4444;font-size:13px;padding:8px 0;">Failed to load order items.</div>';
        return;
      }
      appState.ivmOrder = await res.json();

      // Populate item rows
      _renderVoidItems(appState.ivmOrder);

      // Populate totals bar — "Current Total" = effective total before this void
      var gross   = parseFloat(appState.ivmOrder.total       || 0);
      var voided  = parseFloat(appState.ivmOrder.voidedAmount || 0);
      var current = gross - voided;
      $('ivm-orig-total').textContent = '₱' + current.toLocaleString('en-PH', {minimumFractionDigits:2, maximumFractionDigits:2});
      $('ivm-new-total').textContent  = '₱' + current.toLocaleString('en-PH', {minimumFractionDigits:2, maximumFractionDigits:2});
    } catch (e) {
      $('ivm-items-container').innerHTML =
        '<div style="color:#EF4444;font-size:13px;padding:8px 0;">Connection error — could not load items.</div>';
    }
  };

  /** Close the item-level void modal and clear all related state. */
  window.closeItemVoidModal = function () {
    closeModal('modal-item-void');   // clears ivm-security-key / ivm-master-key via secFields
    appState.ivmOrder = null;
    appState.ivmTier  = null;
  };

  /** Build HTML for the item rows inside ivm-items-container.
   *  Only renders items with remaining quantity > 0.
   *  DELIVERED orders show disposition rows from the start (required for any tier).
   *  Non-DELIVERED orders start with disposition rows hidden (shown only on Tier 2). */
  function _renderVoidItems(order) {
    var container = $('ivm-items-container');
    if (!container) return;
    var isDelivered = order.status === 'DELIVERED';

    var rows = order.items.filter(function(item) {
      var remaining = item.quantity - (item.voidedQuantity || 0);
      return remaining > 0;
    });

    if (rows.length === 0) {
      container.innerHTML = '<div style="color:var(--text-muted);font-size:13px;padding:8px 0;">No active items to void.</div>';
      return;
    }

    container.innerHTML = rows.map(function(item) {
      var alreadyVoided = item.voidedQuantity || 0;
      var remaining = item.quantity - alreadyVoided;
      var dispDisplay = isDelivered ? '' : 'none';

      return '<div class="ivm-item-row" id="ivm-row-' + item.id + '">'
        + '<div class="ivm-item-head">'
        +   '<div class="ivm-item-name">' + escapeHtml(item.productName) + '</div>'
        +   '<div class="ivm-item-avail">Remaining: <strong>' + remaining + '</strong>'
        +     (alreadyVoided > 0 ? ' <span style="color:#F59E0B;font-size:10px;">('+alreadyVoided+' already voided)</span>' : '')
        +   ' of ' + item.quantity + '</div>'
        + '</div>'
        + '<div class="ivm-qty-wrap">'
        +   '<span class="ivm-qty-label">Void</span>'
        +   '<input type="number" class="form-control ivm-qty-input" id="ivm-qty-' + item.id + '"'
        +     ' min="0" max="' + remaining + '" value="0"'
        +     ' style="width:80px;text-align:center;padding:5px 8px;"'
        +     ' oninput="onVoidQtyChange()" />'
        +   '<span class="ivm-qty-unit">units</span>'
        + '</div>'
        + '<div class="ivm-disposition-row" id="ivm-disp-' + item.id + '" style="display:' + dispDisplay + ';">'
        +   '<label class="ivm-disp-opt">'
        +     '<input type="radio" name="ivm-disp-' + item.id + '" value="SELLABLE" checked'
        +     ' onchange="onVoidQtyChange()" /> Sellable'
        +   '</label>'
        +   '<label class="ivm-disp-opt">'
        +     '<input type="radio" name="ivm-disp-' + item.id + '" value="REJECTED"'
        +     ' onchange="onVoidQtyChange()" /> Rejected / Damaged'
        +   '</label>'
        + '</div>'
        + '<div class="rtn-wh-row ivm-wh-row" id="ivm-wh-row-' + item.id + '" style="display:none;margin-top:6px;">'
        +   '<div style="font-size:10px;color:var(--text-muted);margin-bottom:2px;">Restock to warehouse</div>'
        +   '<select class="form-select ivm-warehouse" id="ivm-wh-' + item.id + '"'
        +   ' style="font-size:12px;padding:4px 8px;" onchange="_ivmUpdateSubmitState()">'
        +   '<option value="">-- select --</option>'
        +   '<option value="wh1">WH1</option>'
        +   '<option value="wh2">WH2</option>'
        +   '<option value="wh3">Balagtas</option>'
        +   '</select>'
        + '</div>'
        + '</div>';
    }).join('');
  }

  // ================================================================
  // VOID TIER TRANSITION (Change 6 — onVoidQtyChange)
  // ================================================================

  /** Called on every keystroke in any qty input.
   *  Recalculates tier, transitions key sections, updates totals, updates submit state. */
  window.onVoidQtyChange = function () {
    var order = appState.ivmOrder;
    if (!order) return;
    var isDelivered = order.status === 'DELIVERED';

    var totalVoidingValue = 0;
    var allEffectiveZero  = true;   // true only if every item's effective qty reaches 0
    var anyBeingVoided    = false;  // true if at least one qty input > 0

    order.items.forEach(function(item) {
      var remaining = item.quantity - (item.voidedQuantity || 0);
      if (remaining <= 0) return; // item has no active units — skip

      var input  = $('ivm-qty-' + item.id);
      var qtyVal = input ? parseInt(input.value, 10) : 0;
      if (isNaN(qtyVal) || qtyVal < 0) qtyVal = 0;
      if (qtyVal > remaining) qtyVal = remaining; // clamp to max

      if (qtyVal > 0) anyBeingVoided = true;
      if (remaining - qtyVal > 0) allEffectiveZero = false;

      totalVoidingValue += qtyVal * parseFloat(item.unitPrice || 0);

      // Show warehouse select when this line will restock
      var whRow = $('ivm-wh-row-' + item.id);
      if (whRow) {
        var checkedDisp = document.querySelector('input[name="ivm-disp-' + item.id + '"]:checked');
        var disp = checkedDisp ? checkedDisp.value : 'SELLABLE';
        var willRestock = qtyVal > 0 && (!isDelivered || disp === 'SELLABLE');
        whRow.style.display = willRestock ? '' : 'none';
        if (!willRestock) { var whs = $('ivm-wh-' + item.id); if (whs) whs.value = ''; }
      }
    });

    // Determine new tier
    var newTier = null;
    if (anyBeingVoided) {
      newTier = allEffectiveZero ? 'TIER_2' : 'TIER_1';
    }

    var prevTier = appState.ivmTier;
    appState.ivmTier = newTier;

    // ── Tier transition UI ─────────────────────────────────────────
    if (newTier !== prevTier) {
      var t1  = $('ivm-tier1-section');
      var t2  = $('ivm-tier2-section');
      var lbl = $('ivm-submit-label');

      if (newTier === 'TIER_2') {
        // → master key + all disposition rows shown
        if (t1) t1.style.display = 'none';
        var sk = $('ivm-security-key'); if (sk) sk.value = ''; // clear on hide
        if (t2) t2.style.display = '';
        if (lbl) lbl.textContent = 'Void All Items (Tier 2)';
        if (!isDelivered) {
          // Disposition rows hidden during Tier 1 on non-DELIVERED — show them now
          order.items.forEach(function(item) {
            var d = $('ivm-disp-' + item.id); if (d) d.style.display = '';
          });
        }
      } else if (newTier === 'TIER_1') {
        // → security key; disposition rows hidden on non-DELIVERED
        if (t2) t2.style.display = 'none';
        var mk = $('ivm-master-key'); if (mk) mk.value = ''; // clear on hide
        if (t1) t1.style.display = '';
        if (lbl) lbl.textContent = 'Void Items';
        if (!isDelivered) {
          order.items.forEach(function(item) {
            var d = $('ivm-disp-' + item.id); if (d) d.style.display = 'none';
          });
        }
      } else {
        // newTier = null — nothing being voided — reset to Tier 1 appearance
        if (t2) t2.style.display = 'none';
        var mk2 = $('ivm-master-key'); if (mk2) mk2.value = '';
        if (t1) t1.style.display = '';
        if (lbl) lbl.textContent = 'Void Items';
        if (!isDelivered) {
          order.items.forEach(function(item) {
            var d = $('ivm-disp-' + item.id); if (d) d.style.display = 'none';
          });
        }
      }
    }

    // ── Totals bar update ──────────────────────────────────────────
    var gross   = parseFloat(order.total       || 0);
    var voided  = parseFloat(order.voidedAmount || 0);
    var current = gross - voided;
    var after   = Math.max(0, current - totalVoidingValue);

    var vNow = $('ivm-voiding-now');
    var nTot = $('ivm-new-total');
    if (vNow) vNow.textContent = '₱' + totalVoidingValue.toLocaleString('en-PH', {minimumFractionDigits:2, maximumFractionDigits:2});
    if (nTot) {
      nTot.textContent  = '₱' + after.toLocaleString('en-PH', {minimumFractionDigits:2, maximumFractionDigits:2});
      // Turn red when reaching zero (Tier 2 territory)
      nTot.style.color  = (newTier === 'TIER_2') ? '#EF4444' : '';
    }

    _ivmUpdateSubmitState();
  };

  // ================================================================
  // VOID SUBMIT STATE (Change 7 — _ivmUpdateSubmitState)
  // ================================================================

  /** Enable / disable the submit button.
   *  Called from onVoidQtyChange() and from oninput on reason / key fields. */
  window._ivmUpdateSubmitState = function () {
    var btn = $('ivm-submit-btn');
    if (!btn) return;
    var order = appState.ivmOrder;
    var tier  = appState.ivmTier;
    if (!order || !tier) { btn.disabled = true; return; }
    var isDelivered = order.status === 'DELIVERED';

    // Reason must be present
    var reason = ($('ivm-reason') || {}).value || '';
    if (!reason.trim()) { btn.disabled = true; return; }

    // Appropriate key must be present
    if (tier === 'TIER_1') {
      var sk = ($('ivm-security-key') || {}).value || '';
      if (!sk.trim()) { btn.disabled = true; return; }
    } else {
      var mk = ($('ivm-master-key') || {}).value || '';
      if (!mk.trim()) { btn.disabled = true; return; }
    }

    // Dispositions: required for each item being voided when DELIVERED or TIER_2
    // (radio buttons are pre-checked SELLABLE, so this check normally passes unless
    //  someone programmatically deselects all — kept as a safety guard)
    if (isDelivered || tier === 'TIER_2') {
      var allOk = true;
      order.items.forEach(function(item) {
        var input = $('ivm-qty-' + item.id);
        var qty   = input ? parseInt(input.value, 10) : 0;
        if (isNaN(qty) || qty <= 0) return; // not being voided
        var checked = document.querySelector('input[name="ivm-disp-' + item.id + '"]:checked');
        if (!checked) allOk = false;
      });
      if (!allOk) { btn.disabled = true; return; }
    }

    // Warehouse: required for each line that will restock
    var warehouseOk = true;
    order.items.forEach(function(item) {
      var input = $('ivm-qty-' + item.id);
      var qty   = input ? parseInt(input.value, 10) : 0;
      if (isNaN(qty) || qty <= 0) return;
      var checkedDisp = document.querySelector('input[name="ivm-disp-' + item.id + '"]:checked');
      var disp = checkedDisp ? checkedDisp.value : 'SELLABLE';
      if (!isDelivered || disp === 'SELLABLE') {
        var wh = $('ivm-wh-' + item.id);
        if (!wh || !wh.value) warehouseOk = false;
      }
    });
    if (!warehouseOk) { btn.disabled = true; return; }

    btn.disabled = false;
  };

  // ================================================================
  // VOID SUBMIT (Change 8 — confirmItemVoid)
  // ================================================================

  window.confirmItemVoid = async function () {
    var order = appState.ivmOrder;
    var tier  = appState.ivmTier;
    if (!order || !tier) return;
    var isDelivered = order.status === 'DELIVERED';

    var reason = ($('ivm-reason') || {}).value || '';
    if (!reason.trim()) { showToast('Reason is required', 'error'); return; }

    // Build items array — only include items with qty > 0
    var items = [];
    order.items.forEach(function(item) {
      var input = $('ivm-qty-' + item.id);
      var qty   = input ? parseInt(input.value, 10) : 0;
      if (isNaN(qty) || qty <= 0) return;

      var entry = { orderItemId: item.id, voidQuantity: qty };

      // Include disposition when required by backend (DELIVERED or TIER_2)
      if (isDelivered || tier === 'TIER_2') {
        var checked = document.querySelector('input[name="ivm-disp-' + item.id + '"]:checked');
        entry.disposition = checked ? checked.value : 'SELLABLE';
      }

      // Include restockWarehouse when line will restock
      var entryDisp = entry.disposition || 'SELLABLE';
      if (!isDelivered || entryDisp === 'SELLABLE') {
        var whEl = $('ivm-wh-' + item.id);
        entry.restockWarehouse = whEl ? whEl.value : '';
      }

      items.push(entry);
    });

    if (items.length === 0) { showToast('No items selected for voiding', 'error'); return; }

    var body = { items: items, reason: reason.trim() };
    if (tier === 'TIER_1') {
      body.securityKey = (($('ivm-security-key') || {}).value || '').trim();
    } else {
      body.masterKey = (($('ivm-master-key') || {}).value || '').trim();
    }

    // Disable button and show loading
    var btn = $('ivm-submit-btn');
    var lbl = $('ivm-submit-label');
    var origLabel = lbl ? lbl.textContent : 'Void Items';
    if (btn) btn.disabled = true;
    if (lbl) lbl.textContent = 'Voiding…';

    try {
      var res = await fetch(API_BASE + '/api/orders/' + order.id + '/void', {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify(body)
      });
      var data = await res.json();

      if (!res.ok) {
        showToast('Void failed: ' + (data.message || res.status), 'error');
        // Restore button
        if ($('ivm-submit-btn'))   $('ivm-submit-btn').disabled = false;
        if ($('ivm-submit-label')) $('ivm-submit-label').textContent = origLabel;
        return;
      }

      closeItemVoidModal();
      var voidedAmt = Number(data.voidedNow || 0).toLocaleString('en-PH', {minimumFractionDigits:2, maximumFractionDigits:2});
      var msg = (tier === 'TIER_2')
        ? 'Tier 2 void applied — ₱' + voidedAmt + ' removed from order ' + order.id + ' (order cancelled)'
        : 'Partial void applied — ₱' + voidedAmt + ' removed from order ' + order.id;
      showToast(msg, 'success');
      renderOrderList();

    } catch (e) {
      showToast('Connection error', 'error');
      if ($('ivm-submit-btn'))   $('ivm-submit-btn').disabled = false;
      if ($('ivm-submit-label')) $('ivm-submit-label').textContent = origLabel;
    }
  };

  window.askCancel = function (id) {
    appState.cancelTargetId = id;
    appState.cancelType = 'standard';
    appState.cancelTargetOrder = (appState.allOrders || []).find(function(o) { return o.id === id; }) || null;
    $('cancel-order-id').textContent = id;
    $('cancel-reason-input').value = '';
    $('cancel-key-input').value = '';
    $('cancel-master-key-input').value = '';
    $('cancel-disposition-rows').innerHTML = '';
    // Reset CFR footer button to standard state
    var cfrBtn = $('cancel-cfr-btn');
    if (cfrBtn) {
      cfrBtn.innerHTML = '<i class="ti ti-replace"></i> Cancel for Replacement';
      cfrBtn.onclick = function() { window.onCancelTypeChange('replacement'); };
      cfrBtn.className = 'btn btn-secondary';
    }
    // Reset key sections and disposition
    $('cancel-seckey-section').style.display = '';
    $('cancel-masterkey-section').style.display = 'none';
    $('cancel-disposition-section').style.display = 'none';
    $('cancel-submit-btn').textContent = 'Cancel Order';
    $('cancel-submit-btn').className = 'btn btn-danger';
    $('cancel-submit-btn').disabled = false;
    $('modal-cancel').classList.add('open');
  };

  // Called from the type toggle buttons in modal-cancel
  window.onCancelTypeChange = function (type) {
    appState.cancelType = type;
    var isCfr = type === 'replacement';
    // Drive the CFR footer button — becomes "← Back" in CFR mode
    var cfrBtn2 = $('cancel-cfr-btn');
    if (cfrBtn2) {
      if (isCfr) {
        cfrBtn2.innerHTML = '<i class="ti ti-arrow-left"></i> Back';
        cfrBtn2.onclick = function() { window.onCancelTypeChange('standard'); };
        cfrBtn2.className = 'btn btn-secondary';
      } else {
        cfrBtn2.innerHTML = '<i class="ti ti-replace"></i> Cancel for Replacement';
        cfrBtn2.onclick = function() { window.onCancelTypeChange('replacement'); };
        cfrBtn2.className = 'btn btn-secondary';
      }
    }
    // Swap key fields — clear the field being hidden so values never persist
    $('cancel-seckey-section').style.display = isCfr ? 'none' : '';
    $('cancel-masterkey-section').style.display = isCfr ? '' : 'none';
    if (isCfr) {
      $('cancel-key-input').value = '';
    } else {
      $('cancel-master-key-input').value = '';
      $('cancel-disposition-rows').innerHTML = '';
    }
    // Disposition section only for CFR on a DELIVERED order
    var order = appState.cancelTargetOrder;
    var isDelivered = order && order.status === 'DELIVERED';
    $('cancel-disposition-section').style.display = (isCfr && isDelivered) ? '' : 'none';
    if (isCfr && isDelivered && order) window.renderCfrDispositions(order);
    // Update submit button label and lock state
    $('cancel-submit-btn').textContent = isCfr ? 'Confirm — Cancel for Replacement' : 'Cancel Order';
    $('cancel-submit-btn').className = isCfr ? 'btn btn-warning' : 'btn btn-danger';
    if (isCfr) {
      $('cancel-submit-btn').disabled = true;
      window.onCfrDispositionChange();   // initial evaluation (master key is empty so stays locked)
    } else {
      $('cancel-submit-btn').disabled = false;
    }
  };

  // Build per-item disposition rows for cancel-for-replacement on a DELIVERED order
  window.renderCfrDispositions = function (order) {
    var activeItems = (order.items || []).filter(function(it) {
      return (it.quantity - (it.voidedQuantity || 0)) > 0;
    });
    if (!activeItems.length) {
      $('cancel-disposition-rows').innerHTML =
        '<div style="font-size:12px;color:var(--text-muted);padding:8px 0;">No active items to disposition.</div>';
      return;
    }
    var html = activeItems.map(function(it) {
      var effQty = it.quantity - (it.voidedQuantity || 0);
      return '<div style="display:flex;align-items:center;gap:8px;padding:7px 0;border-bottom:1px solid var(--border);" data-item-id="' + it.id + '">'
        + '<div style="flex:1;font-size:12px;">' + escapeHtml(it.productName || '') + ' <span style="color:var(--text-muted);">×' + effQty + '</span></div>'
        + '<div style="display:flex;gap:4px;">'
        + '<button type="button" class="btn btn-sm cfr-disp-btn" data-item="' + it.id + '" data-val="SELLABLE"'
        + ' onclick="onCfrDispositionChange(this)" style="font-size:11px;padding:2px 10px;">Sellable</button>'
        + '<button type="button" class="btn btn-sm cfr-disp-btn" data-item="' + it.id + '" data-val="REJECTED"'
        + ' onclick="onCfrDispositionChange(this)" style="font-size:11px;padding:2px 10px;">Rejected</button>'
        + '</div></div>';
    }).join('');
    $('cancel-disposition-rows').innerHTML = html;
  };

  // Called from disposition buttons (onclick) and from the master key input (oninput)
  window.onCfrDispositionChange = function (btn) {
    if (btn) {
      // Apply selected / deselected visual state to the two buttons for this item
      var itemId = btn.getAttribute('data-item');
      $('cancel-disposition-rows').querySelectorAll('.cfr-disp-btn[data-item="' + itemId + '"]').forEach(function(b) {
        if (b === btn) {
          b.style.background = 'var(--accent)';
          b.style.color = '#fff';
          b.dataset.selected = 'true';
        } else {
          b.style.background = '';
          b.style.color = '';
          delete b.dataset.selected;
        }
      });
    }
    // Decide whether the submit button should be enabled
    var masterKey = ($('cancel-master-key-input').value || '').trim();
    var order = appState.cancelTargetOrder;
    var isDelivered = order && order.status === 'DELIVERED';
    var allDisposed = true;
    if (isDelivered) {
      $('cancel-disposition-rows').querySelectorAll('[data-item-id]').forEach(function(row) {
        if (!row.querySelector('.cfr-disp-btn[data-selected="true"]')) allDisposed = false;
      });
    }
    $('cancel-submit-btn').disabled = !(masterKey && (!isDelivered || allDisposed));
  };

  // Unified cancel submit — routes to standard cancel or cancel-for-replacement
  window.confirmCancel = async function () {
    var type   = appState.cancelType || 'standard';
    var reason = ($('cancel-reason-input').value || '').trim();
    if (!reason) { showToast('Cancellation reason is required', 'error'); return; }

    if (type === 'standard') {
      var key = ($('cancel-key-input').value || '').trim();
      if (!key) { showToast('Admin security key is required', 'error'); return; }
      try {
        var res = await fetch('' + API_BASE + '/api/orders/' + appState.cancelTargetId + '/cancel', {
          method: 'POST', headers: authHeaders(),
          body: JSON.stringify({ securityKey: key, reason: reason })
        });
        if (res.ok) {
          closeModal('modal-cancel');
          showToast('Order ' + appState.cancelTargetId + ' cancelled', 'success');
          renderOrderList(); renderOrders();
        } else {
          var err = await res.json();
          showToast('Error: ' + (err.message || 'Failed to cancel'), 'error');
        }
      } catch (e) { showToast('Connection error', 'error'); }

    } else {
      // Cancel for replacement
      var masterKey = ($('cancel-master-key-input').value || '').trim();
      if (!masterKey) { showToast('Master key is required', 'error'); return; }
      var items = [];
      var order = appState.cancelTargetOrder;
      if (order && order.status === 'DELIVERED') {
        var valid = true;
        $('cancel-disposition-rows').querySelectorAll('[data-item-id]').forEach(function(row) {
          var selBtn = row.querySelector('.cfr-disp-btn[data-selected="true"]');
          if (!selBtn) { valid = false; return; }
          items.push({ orderItemId: Number(row.getAttribute('data-item-id')), disposition: selBtn.getAttribute('data-val') });
        });
        if (!valid) { showToast('Please select a disposition for every item', 'error'); return; }
      }
      try {
        var res2 = await fetch('' + API_BASE + '/api/orders/' + appState.cancelTargetId + '/cancel-for-replacement', {
          method: 'POST', headers: authHeaders(),
          body: JSON.stringify({ masterKey: masterKey, reason: reason, items: items })
        });
        if (res2.ok) {
          closeModal('modal-cancel');
          showToast('Order ' + appState.cancelTargetId + ' cancelled for replacement', 'success');
          renderOrderList(); renderOrders();
        } else {
          var err2 = await res2.json();
          showToast('Error: ' + (err2.message || 'Failed to cancel for replacement'), 'error');
        }
      } catch (e2) { showToast('Connection error', 'error'); }
    }
  };

  // ================================================================
  // Login
  // ================================================================
  window.doLogin = async function () {
    const identifier = ($('login-email').value || '').trim(), password = $('login-password').value;
    if (!identifier || !password) { showToast('Please enter your email/username and password', 'error'); return; }
    try {
      const res = await fetch('' + API_BASE + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: identifier, password }) });
      if (!res.ok) { const err = await res.json(); showToast(err.message || 'Invalid credentials', 'error'); return; }
      const { token, user } = await res.json();
      localStorage.setItem('rrbm_token', token);
      localStorage.setItem('rrbm_user', JSON.stringify(user));

      const av = $('sidebar-avatar'), un = $('sidebar-name'), ur = $('sidebar-role');
      if (av && user.fullName) av.textContent = user.fullName.split(' ').map(function (n) { return n[0]; }).join('').slice(0, 2).toUpperCase();
      if (un) un.textContent = user.fullName;
      if (ur && user.role) ur.textContent = user.role.replace(/_/g, ' ');

      // Apply role-based restrictions + page access nav hiding
      applyRoleRestrictions(user.role);
      applyPageAccessToNav();

      $('login-screen').style.display = 'none';
      showToast('Welcome, ' + user.fullName + '!', 'success');

      // Clear previous user's cached data before loading fresh data
      _clearSessionState();

      // Always land on the dashboard after login
      navigateTo('dash');

      // Load live dashboard data after DOM has settled (charts need visible containers)
      setTimeout(function() {
        renderDashboard();
        renderTopProductsToday();
        updateCollectionsBadge();
        loadProductAnalytics();
      }, 150);

      // If admin set a temporary password, force the user to change it now
      if (user.mustChangePassword) {
        _forcedPasswordChange = true;
        openChangePasswordModal(true);
      }
    } catch (err) {
      showToast('Connection error. Is the backend running?', 'error');
    }
  };

  // ================================================================
  // Toast Notifications
  // ================================================================
  window.showToast = function (msg, type) {
    type = type || ''; const id = 'toast-' + (appState.toastId++);
    const el = document.createElement('div'); el.className = 'rrbm-toast ' + type; el.id = id;
    const icon = type === 'success' ? 'check' : type === 'error' ? 'alert-circle' : 'info-circle';
    el.innerHTML = '<i class="ti ti-' + icon + '"></i><span>' + msg + '</span>';
    const c = $('rrbm-toast-container'); if (c) c.appendChild(el);
    setTimeout(function () { const t = $(id); if (t) t.remove(); }, 3500);
  };

  // ================================================================
  // Live Clock
  // ================================================================
  function updateClock() {
    const d = new Date(); const el = $('clock');
    if (el) el.textContent = pad(d.getHours(), 2) + ':' + pad(d.getMinutes(), 2) + ':' + pad(d.getSeconds(), 2);
  }

  // ================================================================
  // Charts — Dashboard (initialise empty; renderDashboard fills them)
  // ================================================================
  function initDashboardCharts() {
    if (typeof Chart === 'undefined') { setTimeout(initDashboardCharts, 100); return; }

    const isDark    = document.body.dataset.theme === 'dark';
    const HONEY     = '#D4860A';
    const gridColor = isDark ? 'rgba(212,134,10,0.08)' : 'rgba(74,44,23,0.07)';
    const tickColor = isDark ? '#6B5740'               : '#9C8B70';
    const tooltipBg = isDark ? '#2A1A0A'               : '#FFFFFF';
    const tipText   = isDark ? '#F5ECD8'               : '#1A1208';
    const tipBorder = isDark ? 'rgba(212,134,10,0.25)' : 'rgba(74,44,23,0.15)';
    const cardBg    = isDark ? '#1E1208'               : '#FFFFFF';

    const sharedTooltip = {
      backgroundColor: tooltipBg,
      titleColor:      tipText,
      bodyColor:       tickColor,
      borderColor:     tipBorder,
      borderWidth:     1,
      cornerRadius:    8,
      padding:         10,
      displayColors:   false
    };

    // Destroy existing instances so re-theming always works
    if (appState.chartSales)   { appState.chartSales.destroy();   appState.chartSales   = null; }
    if (appState.chartPayment) { appState.chartPayment.destroy(); appState.chartPayment = null; }

    // ── CHART 1: Sales Trend (line with gradient fill) ─────────────
    const c1 = $('chart-sales');
    if (c1) {
      appState.chartSales = new Chart(c1, {
        type: 'line',
        data: {
          labels: ['','','','','','',''],
          datasets: [{
            label:                'Sales',
            data:                 [0,0,0,0,0,0,0],
            borderColor:          HONEY,
            borderWidth:          2.5,
            pointBackgroundColor: HONEY,
            pointBorderColor:     cardBg,
            pointBorderWidth:     2,
            pointRadius:          5,
            pointHoverRadius:     7,
            tension:              0.4,
            fill:                 true,
            backgroundColor: function(ctx) {
              var g = ctx.chart.ctx.createLinearGradient(0, 0, 0, 220);
              g.addColorStop(0, isDark ? 'rgba(212,134,10,0.20)' : 'rgba(212,134,10,0.13)');
              g.addColorStop(1, 'rgba(212,134,10,0.00)');
              return g;
            }
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: { display: false },
            tooltip: Object.assign({}, sharedTooltip, {
              callbacks: { label: function(ctx) { return '₱' + ctx.parsed.y.toLocaleString('en-PH'); } }
            })
          },
          scales: {
            x: {
              grid:   { display: false },
              border: { display: false },
              ticks:  { color: tickColor, font: { size: 11 } }
            },
            y: {
              grid:   { color: gridColor, drawBorder: false },
              border: { display: false },
              ticks:  {
                color: tickColor,
                font:  { size: 11 },
                callback: function(v) { return '₱' + (v / 1000).toFixed(0) + 'k'; }
              }
            }
          }
        }
      });
    }

    // ── CHART 2: Payment Breakdown (donut, brand colors) ───────────
    const c2 = $('chart-ecommerce');
    if (c2) {
      appState.chartPayment = new Chart(c2, {
        type: 'doughnut',
        data: {
          labels: [],
          datasets: [{
            data:            [],
            backgroundColor: [],
            borderColor:     cardBg,
            borderWidth:     3,
            hoverOffset:     6
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          cutout: '68%',
          plugins: {
            legend: {
              display:  true,
              position: 'bottom',
              labels:   { color: tickColor, font: { size: 11 }, boxWidth: 10, padding: 12 }
            },
            tooltip: Object.assign({}, sharedTooltip, {
              displayColors: true,
              callbacks: {
                label: function(ctx) { return ' ' + ctx.label + ': ₱' + Number(ctx.raw).toLocaleString('en-PH'); }
              }
            })
          }
        }
      });
    }
  }

  // ================================================================
  // Dashboard — tab switching
  // ================================================================
  appState.dashPeriod = 'daily';

  window.switchDashTab = function (period) {
    appState.dashPeriod = period;
    ['daily', 'weekly', 'monthly'].forEach(function (p) {
      const btn = $('dash-tab-' + p);
      if (btn) btn.classList.toggle('active', p === period);
    });

    // Greeting / header swap
    var greetTimeEl = $('dash-greeting-time');
    var greetNameEl = $('dash-greeting-name');
    if (period === 'daily') {
      if (greetTimeEl) greetTimeEl.style.display = '';
      if (greetNameEl && _origGreetingName) {
        greetNameEl.textContent = _origGreetingName;
        greetNameEl.style.fontSize = '20px';
        greetNameEl.style.fontWeight = '700';
        greetNameEl.style.letterSpacing = '-.3px';
      }
    } else if (period === 'weekly') {
      if (greetTimeEl) greetTimeEl.style.display = 'none';
      if (greetNameEl) {
        greetNameEl.textContent = getWeeklyHeaderText();
        greetNameEl.style.fontSize = '16px';
        greetNameEl.style.fontWeight = '600';
        greetNameEl.style.letterSpacing = '-.2px';
      }
    } else if (period === 'monthly') {
      if (greetTimeEl) greetTimeEl.style.display = 'none';
      if (greetNameEl) {
        greetNameEl.textContent = getMonthlyHeaderText();
        greetNameEl.style.fontSize = '18px';
        greetNameEl.style.fontWeight = '700';
        greetNameEl.style.letterSpacing = '-.3px';
      }
    }

    // Hide low-stock panel for non-daily tabs; renderDashboard controls visibility for daily
    var lowStockPanel = $('low-stock-panel');
    if (lowStockPanel && period !== 'daily') lowStockPanel.style.display = 'none';

    // Row 9 (top-products analytics table) duplicates Row 2 Right on weekly/monthly — hide it there.
    // Rows 7–8 (pizza KPI + category charts) are period-aware and stay visible on all tabs.
    var topRow = $('dash-pa-top-row');
    if (topRow) topRow.style.display = (period === 'daily') ? '' : 'none';

    renderDashboard(period);
    renderTopProductsToday();
    loadProductAnalytics(period);
  };

  function getWeeklyHeaderText() {
    var now = new Date();
    var jan4 = new Date(now.getFullYear(), 0, 4);
    var startOfWeek1 = new Date(jan4);
    startOfWeek1.setDate(jan4.getDate() - ((jan4.getDay() + 6) % 7));
    var monday = new Date(now);
    monday.setDate(now.getDate() - ((now.getDay() + 6) % 7));
    var weekNum = Math.round((monday - startOfWeek1) / (7 * 86400000)) + 1;
    var sunday = new Date(monday);
    sunday.setDate(monday.getDate() + 6);
    var fmt = function(d) { return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }); };
    return 'Week ' + weekNum + ', ' + fmt(monday) + ' – ' + fmt(sunday) + ', ' + sunday.getFullYear();
  }

  function getMonthlyHeaderText() {
    var now = new Date();
    return now.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
  }

  window.openCategoryBreakdownModal = function() {
    openModal('modal-category-breakdown');
  };

  // ================================================================
  // Dashboard — fetch live stats and update all cards + charts
  // ================================================================
  async function renderDashboard(period) {
    const token = localStorage.getItem('rrbm_token');
    if (!token) return;
    period = period || appState.dashPeriod || 'daily';

    try {
      const res = await fetch(API_BASE + '/api/dashboard/stats?period=' + period,
        { headers: { 'Authorization': 'Bearer ' + token } });
      if (!res.ok) return;
      const s = await res.json();

      // ── Greeting (daily tab only) ────────────────────────────────
      if (period === 'daily') {
        const greetEl = $('dash-greeting-time');
        const nameEl  = $('dash-greeting-name');
        if (greetEl) {
          const h = new Date().getHours();
          greetEl.textContent = h < 12 ? 'Good morning,' : h < 17 ? 'Good afternoon,' : 'Good evening,';
          greetEl.style.display = '';
        }
        if (nameEl) {
          const rrbmUser = JSON.parse(localStorage.getItem('rrbm_user') || '{}');
          const uname = rrbmUser.fullName || '';
          nameEl.textContent = uname ? 'Welcome back, ' + uname + '!' : 'Welcome back!';
          nameEl.style.fontSize = '20px';
          nameEl.style.fontWeight = '700';
          nameEl.style.letterSpacing = '-.3px';
          _origGreetingName = nameEl.textContent;
        }
      }

      // ── Stat card labels (update for period) ─────────────────────
      const periodLabel = period === 'weekly' ? 'This Week' : period === 'monthly' ? 'This Month' : 'Today';
      const fmt = function(n) { return '₱' + Number(n).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 }); };

      setText('stat-sales-label',    'Total Sales ' + periodLabel);
      setText('stat-cancelled-label','Cancelled ' + periodLabel);
      setText('stat-expenses-label', 'Expenses ' + periodLabel);

      if ($('stat-total-sales'))    $('stat-total-sales').textContent    = fmt(s.totalSales || 0);
      if ($('stat-order-count'))    $('stat-order-count').textContent    = (s.orderCount || 0) + ' orders ' + periodLabel.toLowerCase();
      if ($('stat-active-orders'))  $('stat-active-orders').textContent  = s.activeOrders || 0;
      if ($('stat-pending-orders')) $('stat-pending-orders').textContent = (s.pendingOrders || 0) + ' pending';
      if ($('stat-cancelled'))      $('stat-cancelled').textContent      = s.cancelledOrders || 0;
      if ($('stat-low-stock'))      $('stat-low-stock').textContent      = s.lowStockCount || 0;

      // ── Pizza Box Quota Tracker (two-tone: direct=amber, ecom=purple) ──
      const PIZZA_QUOTA    = 5000;
      const pizzaQty       = s.pizzaBoxQtyToday || 0;
      const directPizzaQty = s.directPizzaQty   || 0;
      const ecomPizzaQty   = s.ecomPizzaQty     || 0;
      const pizzaCard      = $('pizza-quota-card');
      const qtyEl          = $('pizza-qty-display');
      const statusEl       = $('pizza-status-text');

      // Quantity display — daily: live today total; weekly/monthly: overwritten by loadProductAnalytics
      if (period === 'daily' && qtyEl) qtyEl.textContent = pizzaQty.toLocaleString();

      // Two-tone bar: daily = proportion of 5,000 quota; weekly/monthly = direct vs ecom share of total
      var directBarEl = $('pizza-bar-direct');
      var ecomBarEl   = $('pizza-bar-ecom');
      var directLbl   = $('pizza-direct-qty-label');
      var ecomLbl     = $('pizza-ecom-qty-label');
      var barBase  = period === 'daily' ? PIZZA_QUOTA : Math.max(pizzaQty, 1);
      var dPct = Math.min((directPizzaQty / barBase) * 100, 100);
      var ePct = Math.min((ecomPizzaQty   / barBase) * 100, 100 - dPct);
      if (directBarEl) directBarEl.style.width = dPct + '%';
      if (ecomBarEl)   ecomBarEl.style.width   = ePct + '%';
      if (directLbl)   directLbl.textContent   = directPizzaQty.toLocaleString();
      if (ecomLbl)     ecomLbl.textContent      = ecomPizzaQty.toLocaleString();

      // Pct badge, status text, color — daily tab only (uses 5,000 daily quota)
      if (period === 'daily') {
        const pizzaPct = Math.round((pizzaQty / PIZZA_QUOTA) * 100);
        const pctBadge = $('pizza-pct-badge');
        if (pctBadge) pctBadge.textContent = pizzaPct + '%';
        var statusColor;
        if (pizzaPct >= 100) {
          statusColor = '#10B981';
          if (qtyEl) { qtyEl.style.color = '#10B981'; qtyEl.style.webkitTextFillColor = ''; }
          if (pizzaCard) pizzaCard.classList.add('pizza-quota-met');
          if (statusEl) statusEl.textContent = pizzaPct + '% — Quota reached! Great work!';
        } else if (pizzaPct >= 50) {
          statusColor = '#F59E0B';
          if (qtyEl) { qtyEl.style.color = '#F59E0B'; qtyEl.style.webkitTextFillColor = '#F59E0B'; }
          if (pizzaCard) pizzaCard.classList.remove('pizza-quota-met');
          if (statusEl) statusEl.textContent = pizzaPct + '% of quota — Keep going!';
        } else {
          statusColor = '#EF4444';
          if (qtyEl) { qtyEl.style.color = '#EF4444'; qtyEl.style.webkitTextFillColor = '#EF4444'; }
          if (pizzaCard) pizzaCard.classList.remove('pizza-quota-met');
          if (statusEl) statusEl.textContent = pizzaPct + '% of quota — Needs attention';
        }
        if (statusEl) statusEl.style.color = statusColor;
      }

      // ── Low stock detail panel (daily tab only) ───────────────────
      const panel = $('low-stock-panel');
      const lsTb  = $('low-stock-tbody');
      const lsCnt = $('low-stock-panel-count');
      if (panel && lsTb) {
        const items = s.lowStockItems || [];
        if (items.length > 0 && period === 'daily') {
          panel.style.display = '';
          if (lsCnt) lsCnt.textContent = items.length + ' product' + (items.length > 1 ? 's' : '') + ' at or below low threshold';

          // Store all items globally for the modal
          window._allLowStockItems = items;

          // Build row HTML (reused for both panel and modal)
          function buildLowStockRow(p) {
            const shortfall = (p.threshold || 0) - (p.stock || 0);
            const tagCls = p.tag === 'HOT' ? 'badge-hot' : p.tag === 'SELLING' ? 'badge-selling' : 'badge-slow';
            return '<tr>'
              + '<td><strong>' + escapeHtml(p.name) + '</strong></td>'
              + '<td><span class="badge ' + tagCls + '">' + (p.tag || '—') + '</span></td>'
              + '<td style="color:#EF4444;font-weight:600;">' + (p.stock || 0).toLocaleString() + ' pcs</td>'
              + '<td>' + (p.threshold || 0).toLocaleString() + ' pcs</td>'
              + '<td style="color:#EF4444;">−' + shortfall.toLocaleString() + ' pcs</td>'
              + '</tr>';
          }

          // Show only first 5 rows in the panel
          const MAX_VISIBLE = 5;
          lsTb.innerHTML = items.slice(0, MAX_VISIBLE).map(buildLowStockRow).join('');

          // Show/hide "View more" link
          const viewMoreEl = $('low-stock-viewmore');
          if (viewMoreEl) {
            if (items.length > MAX_VISIBLE) {
              viewMoreEl.style.display = 'block';
              viewMoreEl.innerHTML = '<i class="ti ti-chevron-down" style="margin-right:4px;"></i>View all '
                + items.length + ' low stock items';
            } else {
              viewMoreEl.style.display = 'none';
            }
          }
        } else {
          panel.style.display = 'none';
          window._allLowStockItems = [];
          const viewMoreEl = $('low-stock-viewmore');
          if (viewMoreEl) viewMoreEl.style.display = 'none';
        }
      }

      // ── Payment breakdown cards ───────────────────────────────────
      const pd = s.paymentBreakdown || {};
      const cashTotal    = (pd['CASH'] || 0);
      const ewalletTotal = (pd['GCASH'] || 0) + (pd['PAYMAYA'] || 0) + (pd['ONLINE'] || 0);
      const codTotal     = (pd['COD'] || 0);
      const bankTotal    = (pd['BANK_TRANSFER'] || 0) + (pd['BANK_DEPOSIT'] || 0);
      if ($('stat-cash'))           $('stat-cash').textContent           = fmt(cashTotal);
      if ($('stat-ewallet'))        $('stat-ewallet').textContent        = fmt(ewalletTotal);
      if ($('stat-cod'))            $('stat-cod').textContent            = fmt(codTotal);
      if ($('stat-bank'))           $('stat-bank').textContent           = fmt(bankTotal);
      if ($('stat-expenses-today')) $('stat-expenses-today').textContent = fmt(s.totalExpensesToday || 0);

      // ── Sales trend chart ─────────────────────────────────────────
      if (appState.chartSales && s.salesTrend) {
        appState.chartSales.data.labels           = s.salesTrend.map(function(d) { return d.label; });
        appState.chartSales.data.datasets[0].data = s.salesTrend.map(function(d) { return Number(d.total) || 0; });
        appState.chartSales.update();
      }

      // ── Payment donut chart ───────────────────────────────────────
      if (appState.chartPayment) {
        const payColors = {
          CASH:          '#C25A0A',
          GCASH:         '#D4860A',
          PAYMAYA:       '#F0A830',
          BANK_TRANSFER: '#7A4A05',
          BANK_DEPOSIT:  '#5C3604',
          ONLINE:        '#FAD16A',
          COD:           '#E8972E'
        };
        const isDark  = document.body.dataset.theme === 'dark';
        const cardBg  = isDark ? '#1E1208' : '#FFFFFF';
        const entries = Object.entries(pd).filter(function(e) { return Number(e[1]) > 0; });
        appState.chartPayment.data.labels                            = entries.map(function(e) { return e[0]; });
        appState.chartPayment.data.datasets[0].data                  = entries.map(function(e) { return Number(e[1]); });
        appState.chartPayment.data.datasets[0].backgroundColor       = entries.map(function(e) { return payColors[e[0]] || '#9C8B70'; });
        appState.chartPayment.data.datasets[0].borderColor           = cardBg;
        appState.chartPayment.update();
      }

      // ── Channel summary (fire-and-forget — updates channel cards async) ─
      loadChannelSummary(period);

    } catch (err) {
      // Silently ignore — dashboard will show last values
    }
  }

  // ================================================================
  // Dashboard — Channel Summary (Direct vs Ecommerce, Platforms, Payables)
  // ================================================================
  function loadChannelSummary(period) {
    period = period || appState.dashPeriod || 'daily';
    const fmt = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
    fetch(API_BASE + '/api/dashboard/channel-summary?period=' + period, { headers: authHeaders() })
      .then(function(res){ return res.json(); })
      .then(function(d){
        // Direct channel
        var dOrdEl = $('dash-direct-orders');
        var dRevEl = $('dash-direct-revenue');
        var dPizEl = $('dash-direct-pizza');
        if (dOrdEl) dOrdEl.textContent = (d.directOrders || 0) + ' orders';
        if (dRevEl) dRevEl.textContent = fmt(d.directRevenue);
        if (dPizEl) dPizEl.textContent = (d.directPizzaQty || 0).toLocaleString() + ' pizza boxes';

        // Ecommerce channel
        var eOrdEl = $('dash-ecom-orders');
        var eRevEl = $('dash-ecom-revenue');
        var ePizEl = $('dash-ecom-pizza');
        if (eOrdEl) eOrdEl.textContent = (d.ecomOrders || 0) + ' orders';
        if (eRevEl) eRevEl.textContent = fmt(d.ecomRevenue);
        if (ePizEl) ePizEl.textContent = (d.ecomPizzaQty || 0).toLocaleString() + ' pizza boxes';

        // Per-platform (Shopee / TikTok / Lazada)
        var platMap = {};
        (d.ecomPlatforms || []).forEach(function(p){ platMap[p.platform] = p; });
        ['SHOPEE', 'TIKTOK', 'LAZADA'].forEach(function(plat){
          var lc = plat.toLowerCase();
          var p  = platMap[plat] || {};
          var ordEl = $('dash-' + lc + '-orders');
          var revEl = $('dash-' + lc + '-revenue');
          var pizEl = $('dash-' + lc + '-pizza');
          if (ordEl) ordEl.textContent = (p.orderCount || 0);
          if (revEl) revEl.textContent = fmt(p.revenue   || 0);
          if (pizEl) pizEl.textContent = (p.pizzaQty    || 0).toLocaleString() + ' pizza boxes';
        });

        // Payables
        var payEl = $('dash-payables-outstanding');
        var cntEl = $('dash-payables-count');
        var codEl = $('dash-cod-pending');
        if (payEl) payEl.textContent = fmt(d.payablesOutstanding);
        if (cntEl) {
          var pc = d.payablesPendingCount || 0;
          cntEl.textContent = pc + ' pending payable' + (pc === 1 ? '' : 's');
        }
        if (codEl) codEl.textContent = (d.codPending || 0);
      })
      .catch(function(err){ console.warn('channel-summary failed:', err); });
  }

  // ================================================================
  // Dashboard — Product Analytics (Session 53)
  // ================================================================
  var _chartPaTrend = null, _chartPaCategory = null, _chartPaPerformance = null;
  var _origGreetingName = null;

  function loadProductAnalytics(period) {
    period = period || appState.dashPeriod || 'daily';
    var token = localStorage.getItem('rrbm_token');
    if (!token) return;

    fetch(API_BASE + '/api/dashboard/product-analytics?period=' + period, { headers: authHeaders() })
      .then(function(res){ return res.json(); })
      .then(function(d){
        var periodLabel = period === 'weekly' ? 'This Week' : period === 'monthly' ? 'This Month' : 'Today';

        // Pizza Quota KPI
        var q = d.pizzaQuota || {};
        var actual = q.actual || 0;
        var target = q.target;
        var pct = q.pct;
        setText('dash-pa-quota-actual', actual.toLocaleString());
        setText('dash-pa-quota-target', target ? target.toLocaleString() : 'N/A');
        setText('dash-pa-quota-pct', pct != null ? pct + '%' : '—');
        setText('dash-pa-quota-label', target ? 'Target: ' + target.toLocaleString() + ' pcs' : 'No previous month data');
        setText('dash-pa-quota-period', periodLabel);
        var bar = $('dash-pa-quota-bar');
        var kpiCard = $('dash-pa-quota-kpi-card');
        if (bar) {
          var w = Math.min(pct || 0, 100);
          bar.style.width = w + '%';
          bar.style.background = w >= 100 ? '#10B981' : w >= 50 ? '#D4860A' : '#EF4444';
          // Quota-met animation on the KPI card
          if (kpiCard) {
            if (w >= 100) {
              kpiCard.classList.add('pizza-quota-met');
            } else {
              kpiCard.classList.remove('pizza-quota-met');
            }
          }
        }

        // Non-pizza total — all units sold minus pizza box units
        var nonPizzaEl = $('dash-pa-nonpizza-qty');
        if (nonPizzaEl) {
          var nonPizzaQty = Math.max(0, (d.totalQtySold || 0) - actual);
          nonPizzaEl.textContent = nonPizzaQty.toLocaleString() + ' pcs';
        }

        // Category Breakdown table (inline: first 5, modal: full list)
        var cats = d.categories || [];
        var MAX_INLINE_CATEGORIES = 5;

        function buildCatHtml(list) {
          if (list.length === 0) {
            return '<tr><td colspan="4" style="text-align:center;color:var(--text-muted);padding:18px;">No data for this period</td></tr>';
          }
          var h = '';
          list.forEach(function(c) {
            h += '<tr style="cursor:pointer;" onclick="this.nextElementSibling.style.display=this.nextElementSibling.style.display===\'none\'?\'\':\' none\'">'
              + '<td><strong>' + escapeHtml(c.name) + '</strong></td>'
              + '<td style="text-align:right;">' + (c.qty || 0).toLocaleString() + '</td>'
              + '<td style="text-align:right;">' + (c.pct || 0) + '%</td>'
              + '<td style="text-align:center;"><span style="background:var(--accent);color:#fff;border-radius:50%;width:22px;height:22px;display:inline-flex;align-items:center;justify-content:center;font-size:11px;font-weight:700;">' + c.rank + '</span></td>'
              + '</tr>';
            var subs = c.subcategories || [];
            h += '<tr style="display:none;"><td colspan="4" style="padding:0 0 0 24px;">';
            h += '<table class="table" style="margin:0;font-size:12px;">';
            subs.forEach(function(s) {
              h += '<tr><td style="color:var(--text-muted);">' + escapeHtml(s.name) + '</td><td style="text-align:right;">' + (s.qty || 0).toLocaleString() + '</td></tr>';
            });
            h += '</table></td></tr>';
          });
          return h;
        }

        var catTbody = $('dash-pa-category-tbody');
        if (catTbody) catTbody.innerHTML = buildCatHtml(cats.slice(0, MAX_INLINE_CATEGORIES));

        var modalTbody = $('dash-category-modal-tbody');
        if (modalTbody) modalTbody.innerHTML = buildCatHtml(cats);

        var viewMore = $('dash-category-viewmore');
        if (viewMore) viewMore.style.display = cats.length > MAX_INLINE_CATEGORIES ? 'block' : 'none';

        // Top 5 Products
        setText('dash-pa-top-period', periodLabel);
        var topTbody = $('dash-pa-top-tbody');
        if (topTbody) {
          var tops = d.topProducts || [];
          if (tops.length === 0) {
            topTbody.innerHTML = '<tr><td colspan="3" style="text-align:center;color:var(--text-muted);padding:18px;">No data</td></tr>';
          } else {
            var medals = ['🥇','🥈','🥉','4','5'];
            var thtml = '';
            tops.forEach(function(t, i){
              thtml += '<tr><td style="text-align:center;">' + medals[i] + '</td><td>' + escapeHtml(t.name) + '</td><td style="text-align:right;font-weight:600;">' + (t.qty || 0).toLocaleString() + '</td></tr>';
            });
            topTbody.innerHTML = thtml;
          }
        }

        // ── Row 2 updates for weekly / monthly tabs ────────────────
        if (period !== 'daily') {
          var paQ      = d.pizzaQuota || {};
          var paActual = paQ.actual || 0;
          var paTarget = paQ.target;        // 30000 (weekly) or prev-month total (monthly) or null
          var paPct    = paQ.pct;
          var isWeekly = period === 'weekly';
          var perLabel = isWeekly ? 'This Week' : 'This Month';

          // Card title
          var titleEl2 = $('pizza-quota-title');
          if (titleEl2) titleEl2.innerHTML =
            '<i class="ti ti-box" style="margin-right:6px;"></i>Pizza Boxes ' + perLabel;

          // Qty display + color
          var qtyEl2 = $('pizza-qty-display');
          if (qtyEl2) {
            qtyEl2.textContent = paActual.toLocaleString();
            qtyEl2.style.color = '';
            qtyEl2.style.webkitTextFillColor = '';
          }

          // Sub-text (quota or comparison baseline)
          var subEl = $('pizza-quota-sub');
          var compEl = $('pizza-quota-comparison');
          var pctDispEl = $('pizza-pct-display');
          var badgeEl2 = $('pizza-pct-badge');
          var statusEl2 = $('pizza-status-text');

          if (paTarget != null) {
            var targetFmt = Number(paTarget).toLocaleString();
            if (subEl) subEl.textContent =
              (isWeekly ? 'Weekly quota: ' : 'Prev. month: ') + targetFmt + ' pcs';
            if (pctDispEl) pctDispEl.textContent = '/ ' + targetFmt + ' pcs';
            if (badgeEl2) badgeEl2.textContent = (paPct != null ? paPct : '—') + '%';

            // Monthly: show vs last month comparison row
            if (!isWeekly && compEl) {
              var diff = paActual - paTarget;
              var sign = diff >= 0 ? '+' : '';
              compEl.textContent = sign + diff.toLocaleString() + ' pcs vs last month';
              compEl.style.color = diff >= 0 ? '#10B981' : '#EF4444';
              compEl.style.display = '';
            } else if (compEl) {
              compEl.style.display = 'none';
            }

            var pctNum = paPct || 0;
            if (statusEl2) {
              statusEl2.textContent = pctNum >= 100
                ? pctNum + '% — Target reached!'
                : pctNum >= 50
                  ? pctNum + '% — On track'
                  : pctNum + '% — Needs attention';
              statusEl2.style.color = pctNum >= 100 ? '#10B981' : pctNum >= 50 ? '#F59E0B' : '#EF4444';
            }
          } else {
            // No target data (e.g. no previous-month closed reports)
            if (subEl) subEl.textContent =
              isWeekly ? 'Weekly quota: 30,000 pcs' : 'No previous month data';
            if (pctDispEl) pctDispEl.textContent = '';
            if (badgeEl2) badgeEl2.textContent = '—';
            if (compEl) compEl.style.display = 'none';
            if (statusEl2) { statusEl2.textContent = 'No comparison data available'; statusEl2.style.color = ''; }
          }

          // Row 2 right: render period top products (5 weekly, 10 monthly)
          var titleRight = $('top5-card-title');
          var bodyRight  = $('top5-inline-body');
          var tops = d.topProducts || [];
          var maxCount = period === 'monthly' ? 10 : 5;
          var rightLabel = period === 'monthly' ? 'Top 10 Products This Month' : 'Top 5 Products This Week';
          if (titleRight) titleRight.innerHTML =
            '<i class="ti ti-trophy" style="margin-right:6px;color:#D4860A;"></i>' + rightLabel;
          if (bodyRight) {
            if (tops.length === 0) {
              bodyRight.innerHTML = '<div style="text-align:center;color:var(--text-muted);padding:20px;font-size:13px;">No data for this period</div>';
            } else {
              var maxQtyR = tops[0].qty || 1;
              var rhtml = '<div style="padding:0 2px;">';
              tops.slice(0, maxCount).forEach(function(t, i) {
                var pctW = Math.round((t.qty / maxQtyR) * 100);
                rhtml += '<div style="display:flex;align-items:center;gap:10px;padding:6px 0;border-bottom:1px solid var(--border);">'
                  + '<div style="width:18px;font-size:12px;font-weight:700;color:'
                  + (i === 0 ? '#D4860A' : 'var(--text-muted)') + ';flex-shrink:0;text-align:center;">' + (i + 1) + '</div>'
                  + '<div style="flex:1;min-width:0;">'
                  + '<div style="font-size:12px;font-weight:500;color:var(--text-primary);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' + escapeHtml(t.name) + '</div>'
                  + '<div style="height:3px;border-radius:4px;background:var(--border);margin-top:4px;overflow:hidden;">'
                  + '<div style="height:100%;border-radius:4px;background:#D4860A;width:' + pctW + '%;"></div>'
                  + '</div></div>'
                  + '<div style="font-size:11px;font-weight:600;color:'
                  + (i === 0 ? '#D4860A' : 'var(--text-secondary)') + ';flex-shrink:0;white-space:nowrap;">' + (t.qty || 0).toLocaleString() + '</div>'
                  + '</div>';
              });
              rhtml += '</div>';
              bodyRight.innerHTML = rhtml;
            }
          }
        } else {
          // Daily tab: restore card titles and hide comparison div
          var titleEl3 = $('pizza-quota-title');
          if (titleEl3) titleEl3.innerHTML = '<i class="ti ti-box" style="margin-right:6px;"></i>Pizza Boxes Sold Today';
          var subEl3 = $('pizza-quota-sub');
          if (subEl3) subEl3.textContent = 'Daily quota: 5,000 pcs';
          var pctDispEl3 = $('pizza-pct-display');
          if (pctDispEl3) pctDispEl3.textContent = '/ 5,000 pcs';
          var compEl3 = $('pizza-quota-comparison');
          if (compEl3) compEl3.style.display = 'none';
        }

        // Charts
        _renderPaTrendChart(d.trend || [], period);
        _renderPaCategoryChart(d.categories || []);
        _renderPaPerformanceChart(d.categories || []);
      })
      .catch(function(err){ console.warn('product-analytics failed:', err); });
  }

  function _renderPaTrendChart(trend, period) {
    var canvas = $('chart-pa-trend');
    if (!canvas) return;
    if (_chartPaTrend) { _chartPaTrend.destroy(); _chartPaTrend = null; }
    if (!trend.length) return;

    var labels = trend.map(function(t){ return t.label; });
    var data = trend.map(function(t){ return t.qty; });
    var isDark = document.body.dataset.theme === 'dark';
    var gridColor = isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)';
    var tickColor = isDark ? '#aaa' : '#666';

    _chartPaTrend = new Chart(canvas, {
      type: 'line',
      data: {
        labels: labels,
        datasets: [{
          label: 'Qty Sold',
          data: data,
          borderColor: '#D4860A',
          backgroundColor: 'rgba(212,134,10,0.1)',
          fill: true,
          tension: 0.3,
          pointRadius: 3,
          pointBackgroundColor: '#D4860A'
        }]
      },
      options: {
        responsive: true, maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: {
          x: { grid: { display: false }, ticks: { color: tickColor, font: { size: 10 } } },
          y: { grid: { color: gridColor }, ticks: { color: tickColor, font: { size: 10 } }, beginAtZero: true }
        }
      }
    });
  }

  function _renderPaCategoryChart(categories) {
    var canvas = $('chart-pa-category');
    if (!canvas) return;
    if (_chartPaCategory) { _chartPaCategory.destroy(); _chartPaCategory = null; }
    if (!categories.length) return;

    var labels = categories.map(function(c){ return c.name; });
    var data = categories.map(function(c){ return c.qty; });
    var colors = ['#D4860A','#7C3AED','#059669','#2563EB','#DC2626','#F59E0B'];
    var isDark = document.body.dataset.theme === 'dark';

    _chartPaCategory = new Chart(canvas, {
      type: 'doughnut',
      data: {
        labels: labels,
        datasets: [{ data: data, backgroundColor: colors.slice(0, data.length), borderWidth: 2, borderColor: isDark ? '#1A0E04' : '#fff' }]
      },
      options: {
        responsive: true, maintainAspectRatio: false,
        plugins: { legend: { display: true, position: 'bottom', labels: { color: isDark ? '#ccc' : '#444', font: { size: 11 }, boxWidth: 10, padding: 10 } } }
      }
    });
  }

  function _renderPaPerformanceChart(categories) {
    var canvas = $('chart-pa-performance');
    if (!canvas) return;
    if (_chartPaPerformance) { _chartPaPerformance.destroy(); _chartPaPerformance = null; }
    if (!categories.length) return;

    var labels = categories.map(function(c){ return c.name; });
    var isDark = document.body.dataset.theme === 'dark';
    var gridColor = isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)';
    var tickColor = isDark ? '#aaa' : '#666';

    // Build datasets from subcategories (stacked bar)
    var subNames = [];
    categories.forEach(function(c){ (c.subcategories || []).forEach(function(s){ if (subNames.indexOf(s.name) < 0) subNames.push(s.name); }); });
    var subColors = ['#D4860A','#7C3AED','#059669','#2563EB','#DC2626','#F59E0B','#EC4899','#14B8A6','#8B5CF6','#F97316'];
    var datasets = subNames.map(function(sn, idx){
      return {
        label: sn,
        data: categories.map(function(c){
          var found = (c.subcategories || []).find(function(s){ return s.name === sn; });
          return found ? found.qty : 0;
        }),
        backgroundColor: subColors[idx % subColors.length]
      };
    });

    _chartPaPerformance = new Chart(canvas, {
      type: 'bar',
      data: { labels: labels, datasets: datasets },
      options: {
        responsive: true, maintainAspectRatio: false,
        plugins: { legend: { display: true, position: 'bottom', labels: { color: tickColor, font: { size: 10 }, boxWidth: 10, padding: 8 } } },
        scales: {
          x: { stacked: true, grid: { display: false }, ticks: { color: tickColor, font: { size: 10 } } },
          y: { stacked: true, grid: { color: gridColor }, ticks: { color: tickColor, font: { size: 10 } }, beginAtZero: true }
        }
      }
    });
  }

  // ================================================================
  // Dashboard — Top 5 Products Today (FEATURE 5)
  // ================================================================
  window.renderTopProductsToday = async function () {
    const token = localStorage.getItem('rrbm_token');
    if (!token) return;

    // Only run for daily tab — weekly/monthly show product-analytics data instead
    if (appState.dashPeriod && appState.dashPeriod !== 'daily') return;

    // Restore daily card title (may have been changed by a prior weekly/monthly render)
    var titleEl = $('top5-card-title');
    if (titleEl) titleEl.innerHTML = '<i class="ti ti-trophy" style="margin-right:6px;color:#D4860A;"></i>Top 5 Products Today';

    // New inline-bar container (new dashboard layout); fall back to old table tbody
    const container = $('top5-inline-body');
    const oldTbody  = $('top-products-today-tbody');
    if (!container && !oldTbody) return;

    const fmt = function(n) { return '₱' + Number(n).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 }); };

    try {
      const res = await fetch(API_BASE + '/api/dashboard/top-products-today',
        { headers: { 'Authorization': 'Bearer ' + token } });

      if (!res.ok) {
        const errHtml = '<div style="text-align:center;color:var(--text-muted);padding:16px;font-size:13px;">Error loading data</div>';
        if (container) container.innerHTML = errHtml;
        if (oldTbody)  oldTbody.innerHTML  = '<tr><td colspan="4" style="text-align:center;color:var(--text-muted);padding:16px;">Error loading data</td></tr>';
        return;
      }

      const products = await res.json();

      if (!products || products.length === 0) {
        const emptyHtml = '<div style="text-align:center;color:var(--text-muted);padding:16px;font-size:13px;">No sales data yet today.</div>';
        if (container) container.innerHTML = emptyHtml;
        if (oldTbody)  oldTbody.innerHTML  = '<tr><td colspan="4" style="text-align:center;color:var(--text-muted);padding:20px;">No data</td></tr>';
        return;
      }

      // API returns { name, qty, revenue } — support both naming conventions
      const maxQty = products[0].qty || products[0].qtySold || 1;

      const rows = products.slice(0, 5).map(function(p, i) {
        const qty  = p.qty || p.qtySold || 0;
        const name = p.name || p.productName || '—';
        const pct  = Math.round((qty / maxQty) * 100);
        return '<div style="display:flex;align-items:center;gap:10px;padding:6px 0;border-bottom:1px solid var(--border);">'
          + '<div style="width:18px;font-size:12px;font-weight:700;color:' + (i === 0 ? '#D4860A' : 'var(--text-muted)') + ';flex-shrink:0;text-align:center;">' + (i + 1) + '</div>'
          + '<div style="flex:1;min-width:0;">'
          +   '<div style="font-size:12px;font-weight:500;color:var(--text-primary);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' + escapeHtml(name) + '</div>'
          +   '<div style="height:3px;border-radius:4px;background:var(--border);margin-top:4px;overflow:hidden;">'
          +     '<div style="height:100%;border-radius:4px;background:#D4860A;width:' + pct + '%;"></div>'
          +   '</div>'
          + '</div>'
          + '<div style="font-size:11px;font-weight:600;color:' + (i === 0 ? '#D4860A' : 'var(--text-secondary)') + ';flex-shrink:0;white-space:nowrap;">' + qty.toLocaleString() + '</div>'
          + '</div>';
      }).join('');

      if (container) {
        container.innerHTML = '<div style="padding:0 2px;">' + rows + '</div>';
      }

      // Also update old tbody if it still exists somewhere (e.g. on another view)
      if (oldTbody) {
        oldTbody.innerHTML = products.slice(0, 5).map(function(p, i) {
          const qty  = p.qty || p.qtySold || 0;
          const name = p.name || p.productName || '—';
          return '<tr><td>' + (i + 1) + '</td><td>' + escapeHtml(name) + '</td>'
            + '<td style="text-align:right;">' + qty.toLocaleString() + ' pcs</td>'
            + '<td style="text-align:right;">' + fmt(p.revenue || 0) + '</td></tr>';
        }).join('');
      }

    } catch (err) {
      const errHtml = '<div style="text-align:center;color:var(--text-muted);padding:16px;font-size:13px;">—</div>';
      if (container) container.innerHTML = errHtml;
      if (oldTbody)  oldTbody.innerHTML  = '<tr><td colspan="4" style="text-align:center;color:var(--text-muted);padding:16px;">—</td></tr>';
    }
  };

  // ================================================================
  // Settings — load and save company info
  // ================================================================
  async function loadSettings() {
    const token = localStorage.getItem('rrbm_token');
    if (!token) return;
    try {
      const res = await fetch('' + API_BASE + '/api/settings',
        { headers: { 'Authorization': 'Bearer ' + token } });
      if (!res.ok) return;
      const data = await res.json();
      if ($('set-company-name'))    $('set-company-name').value    = data['company_name']    || '';
      if ($('set-company-address')) $('set-company-address').value = data['company_address'] || '';
      if ($('set-company-contact')) $('set-company-contact').value = data['company_contact'] || '';
    } catch (err) { /* ignore */ }
  }

  window.saveCompanySettings = async function () {
    const name    = (($('set-company-name')    || {}).value || '').trim();
    const address = (($('set-company-address') || {}).value || '').trim();
    const contact = (($('set-company-contact') || {}).value || '').trim();
    if (!name) { showToast('Company name is required', 'error'); return; }
    try {
      const res = await fetch('' + API_BASE + '/api/settings', {
        method: 'POST', headers: authHeaders(),
        body: JSON.stringify({ company_name: name, company_address: address, company_contact: contact })
      });
      if (res.ok) {
        showToast('Company info saved', 'success');
      } else {
        const d = await res.json();
        showToast('Error: ' + (d.message || 'Failed to save'), 'error');
      }
    } catch (err) { showToast('Connection error', 'error'); }
  };

  // ================================================================
  // Reports — Insights Summary (real data)
  // ================================================================
  function initReportsView() {
    // Set month picker to current month on first open
    const picker = $('rep-month-picker');
    if (picker && !picker.value) {
      const now = new Date();
      picker.value = now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0');
    }
    // Default to Statistics tab
    switchRepTab('stats');
    loadAllReports();
  }

  /** Switch the monthly report tabs — stats / accounting / ecommerce */
  window.switchRepTab = function (tab) {
    // Toggle tab content sections
    document.querySelectorAll('.rep-tab-content').forEach(function (el) {
      el.style.display = el.getAttribute('data-tab') === tab ? '' : 'none';
    });
    // Toggle tab button active state — match dashboard period tab design
    document.querySelectorAll('.rep-tab-btn').forEach(function (btn) {
      if (btn.getAttribute('data-tab') === tab) {
        btn.classList.add('active');
        btn.style.background = '#D4860A';
        btn.style.color = '#fff';
        btn.style.border = '1px solid #D4860A';
      } else {
        btn.classList.remove('active');
        btn.style.background = 'var(--bg-card, #fff)';
        btn.style.color = 'var(--text-primary, #333)';
        btn.style.border = '1px solid var(--border, #ddd)';
      }
    });
  };

  window.toggleAccountingSummary = function () {
    var grid    = $('acc-summary-grid');
    var chevron = $('acc-summary-chevron');
    if (!grid) return;
    var isHidden = grid.style.display === 'none';
    grid.style.display = isHidden ? '' : 'none';
    if (chevron) chevron.style.transform = isHidden ? '' : 'rotate(-90deg)';
  };

  // ================================================================
  // REJECTED ITEMS PAGE
  // ================================================================
  function initRejectedItemsView() {
    // Pre-fill date range to current month if empty
    var startEl = $('rejected-start');
    var endEl   = $('rejected-end');
    if (startEl && !startEl.value) {
      var now = new Date();
      startEl.value = now.getFullYear() + '-' + pad(now.getMonth() + 1, 2) + '-01';
    }
    if (endEl && !endEl.value) {
      var today = new Date();
      endEl.value = today.getFullYear() + '-' + pad(today.getMonth() + 1, 2) + '-' + pad(today.getDate(), 2);
    }
    loadRejectedItems();
  }

  window.loadRejectedItems = function () {
    var start = ($('rejected-start') || {}).value || '';
    var end   = ($('rejected-end')   || {}).value || '';
    var tb    = $('rejected-items-tbody');
    var tf    = $('rejected-items-tfoot');
    var pdfBtn = $('btn-rejected-pdf');
    if (tb) tb.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);padding:24px;">Loading…</td></tr>';
    if (tf) tf.innerHTML = '';
    if (pdfBtn) pdfBtn.style.display = 'none';

    var url = API_BASE + '/api/reports/rejected-items';
    if (start || end) url += '?start=' + (start || '') + '&end=' + (end || '');

    fetch(url, { headers: authHeaders() })
      .then(function(r) { return r.json(); })
      .then(function(data) {
        window._rejectedData  = data.items  || [];
        window._rejectedStart = data.start  || start;
        window._rejectedEnd   = data.end    || end;
        renderRejectedItemsList(data);
      })
      .catch(function() {
        var tb2 = $('rejected-items-tbody');
        if (tb2) tb2.innerHTML = '<tr><td colspan="6" style="text-align:center;color:#EF4444;padding:24px;">Failed to load rejected items</td></tr>';
      });
  };

  function renderRejectedItemsList(data) {
    var tb     = $('rejected-items-tbody');
    var tf     = $('rejected-items-tfoot');
    var pdfBtn = $('btn-rejected-pdf');
    if (!tb) return;

    var items = data.items || [];

    var sourceBadge = function(src) {
      var map = {
        'DELIVERY': '<span class="badge badge-ok">Delivery</span>',
        'VOID':     '<span class="badge badge-crit">Void</span>',
        'CANCEL':   '<span class="badge badge-low">Cancel</span>',
        'RETURN':   '<span class="badge badge-low">Return</span>',
      };
      return map[src] || '<span class="badge">' + escapeHtml(src) + '</span>';
    };

    var truncReason = function(s) {
      if (!s) return '—';
      var safe = escapeHtml(s);
      if (s.length <= 60) return safe;
      return '<span title="' + safe + '">' + escapeHtml(s.substring(0, 60)) + '…</span>';
    };

    if (!items.length) {
      tb.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);padding:24px;">No rejected items found for this period.</td></tr>';
      if (tf) tf.innerHTML = '';
      if (pdfBtn) pdfBtn.style.display = 'none';
      return;
    }

    var rows = '';
    items.forEach(function(r, i) {
      var isDelivery = r.source === 'DELIVERY';
      var chevron = isDelivery
        ? '<button onclick="toggleRiDetail(' + i + ')" id="ri-btn-' + i + '" style="background:none;border:none;cursor:pointer;padding:0 4px;color:var(--text-muted);" title="Show delivery detail"><i class="ti ti-chevron-down" id="ri-icon-' + i + '"></i></button>'
        : '';
      rows += '<tr>' +
        '<td style="font-size:12px;white-space:nowrap;">' + escapeHtml(r.date || '—') + chevron + '</td>' +
        '<td>' + sourceBadge(r.source) + '</td>' +
        '<td><span style="font-family:monospace;font-size:11px;background:var(--bg-secondary);padding:1px 5px;border-radius:3px;">' + escapeHtml(r.reference || '—') + '</span></td>' +
        '<td style="font-size:13px;font-weight:600;">' + escapeHtml(r.productName || '—') + '</td>' +
        '<td style="text-align:right;font-weight:700;color:#EF4444;">' + (r.rejectedQty || 0) + '</td>' +
        '<td style="font-size:12px;color:var(--text-secondary);">' + truncReason(r.reason) + '</td>' +
        '</tr>';
      if (isDelivery) {
        rows += '<tr id="ri-detail-' + i + '" style="display:none;background:var(--bg-secondary);">' +
          '<td colspan="6" style="padding:8px 16px;">' +
          '<span style="font-size:11px;color:var(--text-muted);margin-right:20px;"><strong>Supplier:</strong> ' + escapeHtml(r.supplierName || '—') + '</span>' +
          '<span style="font-size:11px;color:var(--text-muted);margin-right:20px;"><strong>PO #:</strong> ' + escapeHtml(r.poNumber || '—') + '</span>' +
          '<span style="font-size:11px;color:var(--text-muted);"><strong>Received By:</strong> ' + escapeHtml(r.receivedBy || '—') + '</span>' +
          '</td></tr>';
      }
    });
    tb.innerHTML = rows;

    if (tf) tf.innerHTML = '';
    if (pdfBtn) pdfBtn.style.display = '';
  }

  window.toggleRiDetail = function(i) {
    var row  = $('ri-detail-' + i);
    var icon = $('ri-icon-' + i);
    if (!row) return;
    if (row.style.display !== 'table-row') {
      row.style.display = 'table-row';
      if (icon) icon.style.transform = 'rotate(180deg)';
    } else {
      row.style.display = 'none';
      if (icon) icon.style.transform = '';
    }
  };

  window.downloadRejectedPDF = function () {
    var items = window._rejectedData || [];
    var start = window._rejectedStart || '';
    var end   = window._rejectedEnd   || '';
    if (!items.length) { showToast('No rejected items to export', 'error'); return; }

    var sourceLabel = function(src) {
      return { 'DELIVERY': 'Delivery', 'VOID': 'Void', 'CANCEL': 'Cancel', 'RETURN': 'Return' }[src] || src || '—';
    };

    var rows = items.map(function(r) {
      var isDelivery = r.source === 'DELIVERY';
      var detailRow = isDelivery
        ? '<tr style="background:#FFF9E6;">' +
            '<td colspan="6" style="font-size:10px;color:#666;padding:3px 10px 6px;">' +
            'Supplier: ' + escapeHtml(r.supplierName || '—') +
            ' &nbsp;|&nbsp; PO #: ' + escapeHtml(r.poNumber || '—') +
            ' &nbsp;|&nbsp; Received By: ' + escapeHtml(r.receivedBy || '—') +
            '</td></tr>'
        : '';
      return '<tr>' +
        '<td>' + escapeHtml(r.date || '—') + '</td>' +
        '<td>' + escapeHtml(sourceLabel(r.source)) + '</td>' +
        '<td style="font-family:monospace;font-size:11px;">' + escapeHtml(r.reference || '—') + '</td>' +
        '<td>' + escapeHtml(r.productName || '—') + '</td>' +
        '<td style="text-align:right;font-weight:700;color:#CC2222;">' + (r.rejectedQty || 0) + '</td>' +
        '<td style="font-size:11px;color:#555;">' + escapeHtml(r.reason || '—') + '</td>' +
        '</tr>' + detailRow;
    }).join('');

    var w = window.open('', '_blank', 'width=960,height=900');
    if (!w) { showToast('Pop-up blocked — allow pop-ups and try again', 'error'); return; }
    w.document.write('<!DOCTYPE html><html><head><title>Rejected Items ' + start + ' to ' + end + '</title>'
      + '<style>'
      + 'body{font-family:Arial,sans-serif;font-size:12px;padding:24px;color:#1A1208;max-width:920px;margin:0 auto;}'
      + '.header{display:flex;align-items:center;gap:14px;border-bottom:3px solid #FAD16A;padding-bottom:12px;margin-bottom:16px;}'
      + '.logo-sq{width:44px;height:44px;background:#FAD16A;border-radius:6px;display:flex;align-items:center;justify-content:center;font-weight:900;font-size:18px;color:#2C1A0E;flex-shrink:0;}'
      + '.co-name{font-size:15px;font-weight:700;color:#2C1A0E;}'
      + '.co-sub{font-size:11px;color:#666;}'
      + 'table{width:100%;border-collapse:collapse;}'
      + 'th{background:#FAD16A;padding:7px 10px;text-align:left;font-size:10px;text-transform:uppercase;letter-spacing:0.3px;}'
      + 'td{padding:6px 10px;border-bottom:1px solid #eee;vertical-align:top;}'
      + '.footer{margin-top:32px;font-size:10px;color:#999;text-align:center;border-top:1px solid #eee;padding-top:8px;}'
      + '@media print{body{padding:10px;}}'
      + '</style></head><body>'
      + '<div class="header">'
      + '<div class="logo-sq">R</div>'
      + '<div><div class="co-name">RRBM Packaging Supplies and Trading</div>'
      + '<div class="co-sub">Rejected Items Report &nbsp;|&nbsp; Period: ' + escapeHtml(start) + ' to ' + escapeHtml(end) + '</div></div>'
      + '</div>'
      + '<table>'
      + '<thead><tr>'
      + '<th>Date</th><th>Source</th><th>Reference</th><th>Product</th>'
      + '<th style="text-align:right;">Rejected Qty</th><th>Reason</th>'
      + '</tr></thead>'
      + '<tbody>' + rows + '</tbody>'
      + '</table>'
      + '<div class="footer">RRBM Management System &middot; Confidential &middot; Internal use only</div>'
      + '<script>window.onload=function(){window.print();}<\/script>'
      + '</body></html>');
    w.document.close();
  };

  // ================================================================
  // E-COMMERCE CSV IMPORT
  // ================================================================

  window.openImportModal = function() {
    // Reset state
    _importParsed = [];
    _lastCsvText  = '';
    var fileEl = document.getElementById('import-csv-file');
    if (fileEl) fileEl.value = '';
    var groupCb = document.getElementById('import-group-by-customer');
    if (groupCb) groupCb.checked = false;
    var wrap = document.getElementById('import-preview-wrap');
    if (wrap) wrap.style.display = 'none';
    var empty = document.getElementById('import-empty-state');
    if (empty) empty.style.display = '';
    var btn = document.getElementById('import-submit-btn');
    if (btn) btn.disabled = true;
    var summary = document.getElementById('import-summary-line');
    if (summary) summary.style.display = 'none';

    // Populate shared product datalist for search inputs
    var dl = document.getElementById('import-product-datalist');
    if (dl) {
      var _c = appState.cachedProducts, _i = appState.inventoryAllProducts;
      var prods = (_c && _c.length) ? _c : (_i && _i.length) ? _i : [];
      dl.innerHTML = prods
        .filter(function(p){ return p.active !== false; })
        .map(function(p){ return '<option value="' + escapeHtml(p.name) + '">'; })
        .join('');
    }

    $('modal-import-ecom').classList.add('open');
  };

  window.onImportFileChange = function(input) {
    var file = input.files && input.files[0];
    if (!file) return;
    var reader = new FileReader();
    reader.onload = function(e) {
      try {
        _lastCsvText  = e.target.result;
        var parsed = parseCsvOrders(_lastCsvText);
        _importParsed = parsed;
        renderImportPreview(parsed);
      } catch (err) {
        showToast('Could not parse CSV: ' + err.message, 'error');
      }
    };
    reader.readAsText(file);
  };

  // Parse CSV text → array of order objects grouped by Order No.
  // Template format (1 header row only):
  //   [0] Order Number  [1] Shipping  [2] Tracking Number  [3] Product Name
  //   [4] Quantity      [5] Total     [6] Customer Name    [7] SKU (optional)
  function parseCsvOrders(text) {
    var lines = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n');
    var dataLines = [];
    var headerSkipped = 0;
    for (var i = 0; i < lines.length; i++) {
      var trimmed = lines[i].trim();
      if (!trimmed) continue;
      if (headerSkipped < 1) { headerSkipped++; continue; }   // skip 1 header row
      dataLines.push(trimmed);
    }
    if (!dataLines.length) throw new Error('No data rows found after skipping the header row');

    // Parse each row — handle quoted fields containing commas
    var rows = dataLines.map(parseCsvRow).filter(function(r){ return r.length >= 5 && r[0]; });
    if (!rows.length) throw new Error('No valid rows found — ensure the file matches the RRBM template format');

    // Group by Order Number (col 0)
    var orderMap = {};
    var orderSeq = [];
    rows.forEach(function(cols) {
      var orderNo  = (cols[0] || '').trim();
      var shipping = (cols[1] || '').trim();
      var tracking = (cols[2] || '').trim();
      var prodName = (cols[3] || '').trim();
      var qty      = Math.max(1, parseInt(cols[4], 10) || 1);
      var total    = parseFloat((cols[5] || '0').replace(/[^0-9.]/g, '')) || 0;
      var customer = (cols[6] || '').trim();
      var csvSku   = (cols[7] || '').trim();

      if (!orderMap[orderNo]) {
        orderMap[orderNo] = {
          orderNo:     orderNo,
          shipping:    shipping,
          tracking:    tracking,
          platform:    detectPlatform(orderNo, shipping),
          customer:    customer,
          allOrderNos: [orderNo],
          items:       [],
          orderTotal:  0
        };
        orderSeq.push(orderNo);
      }
      // Carry customer name from first non-blank occurrence
      if (customer && !orderMap[orderNo].customer) orderMap[orderNo].customer = customer;

      // Unit price derived from CSV line total — preserves actual ecommerce pricing
      var unitPrice = total > 0 ? Math.round((total / qty) * 100) / 100 : 0;

      // Match: SKU exact first, then name fuzzy
      var match = matchCsvProduct(prodName, csvSku);
      orderMap[orderNo].items.push({
        csvName:     prodName,
        csvSku:      csvSku,
        qty:         qty,
        lineTotal:   total,
        productId:   match ? match.product.id   : null,
        productName: match ? match.product.name : prodName,
        unitPrice:   unitPrice,
        warehouse:   match ? bestWarehouse(match.product) : 'wh1',
        confidence:  match ? match.confidence : null
      });
      orderMap[orderNo].orderTotal += total;
    });

    var parsed = orderSeq.map(function(no){ return orderMap[no]; });

    // Optionally merge rows that share the same customer name
    var groupCb = document.getElementById('import-group-by-customer');
    if (groupCb && groupCb.checked) return groupImportByCustomer(parsed);
    return parsed;
  }

  // Merge orders that share the same customer name into a single RRBM order
  function groupImportByCustomer(orders) {
    var customerMap = {};
    var custSeq     = [];
    orders.forEach(function(order) {
      var key = (order.customer || '').trim().toLowerCase() || ('__anon_' + order.orderNo);
      if (!customerMap[key]) {
        customerMap[key] = {
          orderNo:     order.orderNo,
          shipping:    order.shipping,
          tracking:    order.tracking,
          platform:    order.platform,
          customer:    order.customer,
          allOrderNos: [order.orderNo],
          items:       order.items.slice(),
          orderTotal:  order.orderTotal
        };
        custSeq.push(key);
      } else {
        var target = customerMap[key];
        order.items.forEach(function(item){ target.items.push(item); });
        target.orderTotal += order.orderTotal;
        if (target.allOrderNos.indexOf(order.orderNo) < 0) target.allOrderNos.push(order.orderNo);
      }
    });
    return custSeq.map(function(k){ return customerMap[k]; });
  }

  // Parse a single CSV row, respecting quoted fields
  function parseCsvRow(line) {
    var cols = [], cur = '', inQuote = false;
    for (var i = 0; i < line.length; i++) {
      var ch = line[i];
      if (ch === '"') { inQuote = !inQuote; continue; }
      if (ch === ',' && !inQuote) { cols.push(cur.trim()); cur = ''; continue; }
      cur += ch;
    }
    cols.push(cur.trim());
    return cols;
  }

  // Re-parse when the "Group by customer" checkbox is toggled
  window.onImportGroupToggle = function() {
    if (!_lastCsvText) return;
    try {
      _importParsed = parseCsvOrders(_lastCsvText);
      renderImportPreview(_importParsed);
    } catch(err) {
      showToast('Could not regroup orders: ' + err.message, 'error');
    }
  };

  // Download the RRBM CSV import template
  window.downloadImportTemplate = function() {
    var header  = 'Order Number,Shipping,Tracking Number,Product Name,Quantity,Total,Customer Name,SKU';
    var example = '240530ABC001,SPX,TH1234567890PH,Pizza Box 10in White,10,1500.00,Maria Santos,PB10W';
    var csv     = header + '\n' + example + '\n';
    var blob    = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    var url     = URL.createObjectURL(blob);
    var a       = document.createElement('a');
    a.href     = url;
    a.download = 'rrbm-import-template.csv';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  // Detect platform from courier string and order number format
  function detectPlatform(orderNo, shipping) {
    var s = (shipping || '').toLowerCase();
    var o = (orderNo  || '');
    if (s.indexOf('tiktok') >= 0)        return 'TIKTOK';
    if (s.indexOf('lzd') >= 0)           return 'LAZADA';
    if (s.indexOf('spx') >= 0)           return 'SHOPEE';
    // Fallback: all-digit order number
    if (/^\d+$/.test(o)) {
      return o.length >= 17 ? 'TIKTOK' : 'LAZADA';
    }
    return 'SHOPEE'; // alphanumeric = Shopee
  }

  // Return the warehouse with the most available stock
  function bestWarehouse(product) {
    if (!product) return 'wh1';
    var w1 = product.stockWh1 || 0, w2 = product.stockWh2 || 0, w3 = product.stockWh3 || 0;
    if (w1 >= w2 && w1 >= w3) return 'wh1';
    if (w2 >= w3)             return 'wh2';
    return 'wh3';
  }

  // Match a CSV product to the inventory.
  // Step 1: SKU exact match (if csvSku provided) → immediate high-confidence result.
  // Step 2: Improved name fuzzy match with dimension-aware token scoring.
  function matchCsvProduct(csvName, csvSku) {
    var _c = appState.cachedProducts, _i = appState.inventoryAllProducts;
    var products = ((_c && _c.length) ? _c : (_i && _i.length) ? _i : [])
      .filter(function(p){ return p.active !== false; });
    if (!products.length) return null;

    // — Step 1: SKU exact match —
    if (csvSku) {
      var skuNorm = csvSku.toLowerCase().trim();
      var skuMatch = null;
      for (var si = 0; si < products.length; si++) {
        var p = products[si];
        if ((p.sku         && p.sku.toLowerCase()          === skuNorm) ||
            (p.productCode && p.productCode.toLowerCase()  === skuNorm) ||
            (p.itemCode    && p.itemCode.toLowerCase()     === skuNorm)) {
          skuMatch = p; break;
        }
      }
      if (skuMatch) return { product: skuMatch, confidence: 'high' };
    }

    // — Step 2: Name fuzzy match —
    // Normalise: lowercase; convert inch/in suffixes to bare number; keep 'x' for "10x10"
    function norm(s) {
      return (s || '').toLowerCase()
        .replace(/(\d+)\s*(?:inches?|in\b|")/g, '$1')   // "10 inches" → "10"
        .replace(/[^a-z0-9\sx]/g, ' ')
        .replace(/\s+/g, ' ').trim();
    }

    // Token classifications
    var DIMEN   = /^\d+(?:x\d+)?$/;   // lone number or NxM (most discriminating for box sizes)
    var VARIANT = /^(black|brown|kraft|white|cg2|gen4|pso|round|square|rect|rectangle|plain|printed|custom)$/;
    var STOP    = /^(pcs|pc|pack|packs|bundle|bundles|box|boxes|pizza|grease|proof|wax|paper|clay|coated|corrugated|fresh|tasty|hot|time|super|delicious|care|slice|and|for|the|per|set|color|colour|food|grade|inches|inch|gen|pso|with|savers|saver|stand|supplies|tripod|a|an|of|to|by|via|our|new)$/;

    function tokens(s) {
      return norm(s).split(' ').filter(function(t){
        // Keep numeric dimension tokens (single-digit sizes like "6", "8") regardless of length
        return (t.length >= 2 || DIMEN.test(t)) && !STOP.test(t);
      });
    }

    var csvTokens = tokens(csvName);
    if (!csvTokens.length) return null;

    var best = null, bestScore = 0;
    products.forEach(function(prod) {
      var pTokens = tokens(prod.name || '');
      if (!pTokens.length) return;
      var score = 0;
      csvTokens.forEach(function(t) {
        if (pTokens.indexOf(t) >= 0) {
          // Exact token match — weight by token type
          if (DIMEN.test(t))       score += 4;   // size dimension: highest weight
          else if (VARIANT.test(t)) score += 3;  // colour / variant
          else                      score += 3;  // other exact keyword
        } else {
          // Partial substring match — require ≥ 4 chars overlap to reduce false positives
          var partial = pTokens.some(function(pt){
            var minLen = Math.min(pt.length, t.length);
            if (minLen < 4) return false;
            return pt.indexOf(t) >= 0 || t.indexOf(pt) >= 0;
          });
          if (partial) score += 1;
        }
      });
      if (score > bestScore) { bestScore = score; best = prod; }
    });

    if (!best || bestScore < 2) return null;    // raised minimum threshold
    // High: ≥ 5 points; Low: 2–4
    return { product: best, confidence: bestScore >= 5 ? 'high' : 'low' };
  }

  // Build platform badge HTML
  function platformBadge(platform) {
    var colors = { SHOPEE: '#EE4D2D', TIKTOK: '#010101', LAZADA: '#0F146D' };
    var labels = { SHOPEE: 'Shopee', TIKTOK: 'TikTok', LAZADA: 'Lazada' };
    var c = colors[platform] || '#6B7280';
    var l = labels[platform] || platform;
    return '<span style="background:' + c + ';color:#fff;padding:2px 7px;border-radius:4px;font-size:10px;font-weight:700;">' + l + '</span>';
  }

  // Render the preview table from _importParsed
  function renderImportPreview(orders) {
    var wrap  = document.getElementById('import-preview-wrap');
    var empty = document.getElementById('import-empty-state');
    var tbody = document.getElementById('import-preview-tbody');
    if (!tbody) return;

    if (!orders.length) {
      if (wrap)  wrap.style.display  = 'none';
      if (empty) empty.style.display = '';
      return;
    }

    if (empty) empty.style.display = 'none';
    if (wrap)  wrap.style.display  = '';

    // Always refresh the shared datalist here — products are loaded by the time
    // the file has been parsed, even if they weren't ready when the modal first opened.
    var dl = document.getElementById('import-product-datalist');
    if (dl) {
      var _c = appState.cachedProducts, _i = appState.inventoryAllProducts;
      var _prods = (_c && _c.length) ? _c : (_i && _i.length) ? _i : [];
      dl.innerHTML = _prods
        .filter(function(p){ return p.active !== false; })
        .map(function(p){ return '<option value="' + escapeHtml(p.name) + '">'; })
        .join('');
    }

    var fmt = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };

    var html = '';
    orders.forEach(function(order, oi) {
      // Compute match status
      var allHigh   = order.items.every(function(i){ return i.confidence === 'high'; });
      var anyNull   = order.items.some(function(i){  return i.confidence === null; });
      var statusChip, statusClass;
      if (anyNull) {
        statusChip  = '<span style="background:#EF4444;color:#fff;padding:2px 7px;border-radius:4px;font-size:10px;font-weight:700;">Fix needed</span>';
        statusClass = 'fix';
      } else if (allHigh) {
        statusChip  = '<span style="background:#10B981;color:#fff;padding:2px 7px;border-radius:4px;font-size:10px;font-weight:700;">Ready</span>';
        statusClass = 'ready';
      } else {
        statusChip  = '<span style="background:#F59E0B;color:#fff;padding:2px 7px;border-radius:4px;font-size:10px;font-weight:700;">Review</span>';
        statusClass = 'review';
      }

      // Header row — chip gets an id so we can update it live as user corrects items
      // For grouped orders show all order numbers (truncated if too long)
      var displayNo = (order.allOrderNos && order.allOrderNos.length > 1)
        ? order.allOrderNos.join(' + ')
        : order.orderNo;
      var displayNoShort = displayNo.length > 26 ? displayNo.substring(0, 24) + '…' : displayNo;
      html += '<tr style="cursor:pointer;" onclick="toggleImportRow(' + oi + ')">'
        + '<td style="text-align:center;color:var(--text-muted);"><i class="ti ti-chevron-right" id="import-chev-' + oi + '" style="transition:transform 0.2s;font-size:11px;"></i></td>'
        + '<td><span style="font-family:monospace;font-size:11px;" title="' + escapeHtml(displayNo) + '">' + escapeHtml(displayNoShort) + '</span></td>'
        + '<td>' + platformBadge(order.platform) + '</td>'
        + '<td style="font-size:11px;">' + escapeHtml(order.shipping) + '</td>'
        + '<td>' + escapeHtml(order.customer || '—') + '</td>'
        + '<td style="text-align:right;">' + order.items.length + '</td>'
        + '<td style="text-align:right;font-weight:600;">' + fmt(order.orderTotal) + '</td>'
        + '<td style="text-align:center;" id="import-chip-' + oi + '">' + statusChip + '</td>'
        + '</tr>';

      // Expanded item rows (hidden by default)
      // ALL items are editable: product (searchable typeahead), qty, unit price
      html += '<tr id="import-detail-' + oi + '" style="display:none;">'
        + '<td colspan="8" style="padding:0;background:var(--bg-secondary);">'
        + '<div style="padding:8px 16px;">'
        + '<table class="table" style="margin:0;font-size:11px;">'
        + '<thead><tr>'
        + '<th style="min-width:180px;">CSV Product Name</th>'
        + '<th style="min-width:200px;">Matched Product <span style="font-weight:400;color:var(--text-muted);">(type to search)</span></th>'
        + '<th style="text-align:right;width:70px;">Qty</th>'
        + '<th style="text-align:right;width:90px;">Unit Price</th>'
        + '<th style="text-align:right;width:90px;">Line Total</th>'
        + '</tr></thead>'
        + '<tbody>';

      order.items.forEach(function(item, ii) {
        // Border colour indicates confidence: green=high, amber=low, red=unmatched
        var borderColor = item.confidence === 'high' ? '#10B981'
                        : item.confidence === 'low'  ? '#F59E0B'
                        : '#EF4444';
        var lineTotal = (item.qty || 0) * (item.unitPrice || 0);
        var inputBase = 'border-radius:4px;background:var(--bg);color:var(--fg);font-size:11px;padding:3px 5px;';

        html += '<tr>'
          // CSV name (read-only, truncated with tooltip)
          + '<td style="color:var(--text-muted);max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="' + escapeHtml(item.csvName) + '">'
          +   escapeHtml(item.csvName)
          + '</td>'
          // Searchable product input — shared datalist provides typeahead suggestions
          + '<td><input type="text" id="ipi-' + oi + '-' + ii + '" list="import-product-datalist"'
          +   ' value="' + escapeHtml(item.productName || '') + '"'
          +   ' placeholder="Type to search inventory…"'
          +   ' oninput="onImportProdInput(this,' + oi + ',' + ii + ')"'
          +   ' style="width:100%;min-width:180px;' + inputBase + 'border:1.5px solid ' + borderColor + ';"></td>'
          // Editable qty
          + '<td style="text-align:right;"><input type="number" id="ipq-' + oi + '-' + ii + '"'
          +   ' value="' + (item.qty || 1) + '" min="1"'
          +   ' oninput="onImportQtyInput(this,' + oi + ',' + ii + ')"'
          +   ' style="width:58px;text-align:right;' + inputBase + 'border:1px solid var(--border);"></td>'
          // Editable unit price
          + '<td style="text-align:right;"><input type="number" id="ipp-' + oi + '-' + ii + '"'
          +   ' value="' + Number(item.unitPrice || 0).toFixed(2) + '" min="0" step="0.01"'
          +   ' oninput="onImportPriceInput(this,' + oi + ',' + ii + ')"'
          +   ' style="width:78px;text-align:right;' + inputBase + 'border:1px solid var(--border);"></td>'
          // Line total (read-only, auto-updated)
          + '<td style="text-align:right;" id="iplt-' + oi + '-' + ii + '">' + fmt(lineTotal) + '</td>'
          + '</tr>';
      });

      html += '</tbody></table></div></td></tr>';
    });

    tbody.innerHTML = html;
    updateImportSummary();
  }

  /**
   * Called as user types in the product search input for a CSV item.
   * Matches against inventory by exact name (datalist selection) or partial.
   * Does NOT auto-fill the price — CSV-derived pricing is preserved.
   * Updates the border colour and status chip.
   */
  window.onImportProdInput = function(input, oi, ii) {
    if (!_importParsed[oi] || !_importParsed[oi].items[ii]) return;
    var item  = _importParsed[oi].items[ii];
    var typed = (input.value || '').trim();
    var lower = typed.toLowerCase();

    var _c = appState.cachedProducts, _i = appState.inventoryAllProducts;
    var prods = (_c && _c.length) ? _c : (_i && _i.length) ? _i : [];

    // Exact name match (what datalist selection produces)
    var match = prods.find(function(p){ return p.active !== false && p.name.toLowerCase() === lower; });

    if (match) {
      item.productId   = match.id;
      item.productName = match.name;
      item.warehouse   = bestWarehouse(match);
      item.confidence  = 'high';
      // Price stays as-is (CSV-derived) — user can override it in the price input
      // Green border — confirmed match
      input.style.borderColor = '#10B981';
    } else if (typed) {
      item.productId   = null;
      item.productName = typed;
      item.confidence  = null;
      input.style.borderColor = '#EF4444'; // red — no match yet
    } else {
      item.productId   = null;
      item.productName = '';
      item.confidence  = null;
      input.style.borderColor = '#EF4444';
    }

    _importUpdateLineTotal(oi, ii);
    _importRefreshChip(oi);
    updateImportSummary();
  };

  /** Qty field edited — update state and line total. */
  window.onImportQtyInput = function(input, oi, ii) {
    if (!_importParsed[oi] || !_importParsed[oi].items[ii]) return;
    _importParsed[oi].items[ii].qty = Math.max(1, parseInt(input.value, 10) || 1);
    _importUpdateLineTotal(oi, ii);
  };

  /** Unit price field edited — update state and line total. */
  window.onImportPriceInput = function(input, oi, ii) {
    if (!_importParsed[oi] || !_importParsed[oi].items[ii]) return;
    _importParsed[oi].items[ii].unitPrice = parseFloat(input.value) || 0;
    _importUpdateLineTotal(oi, ii);
  };

  /** Recompute and display the line total for one item. */
  function _importUpdateLineTotal(oi, ii) {
    var item = _importParsed[oi] && _importParsed[oi].items[ii];
    if (!item) return;
    var lt = (item.qty || 0) * (item.unitPrice || 0);
    var cell = document.getElementById('iplt-' + oi + '-' + ii);
    if (cell) cell.textContent = '₱' + lt.toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2});
  }

  /** Update the status chip on the order's header row based on current item states. */
  function _importRefreshChip(oi) {
    var order = _importParsed[oi];
    if (!order) return;
    var allHigh = order.items.every(function(i){ return i.confidence === 'high'; });
    var anyNull = order.items.some(function(i){  return i.confidence === null; });
    var chip;
    if (anyNull) {
      chip = '<span style="background:#EF4444;color:#fff;padding:2px 7px;border-radius:4px;font-size:10px;font-weight:700;">Fix needed</span>';
    } else if (allHigh) {
      chip = '<span style="background:#10B981;color:#fff;padding:2px 7px;border-radius:4px;font-size:10px;font-weight:700;">Ready</span>';
    } else {
      chip = '<span style="background:#F59E0B;color:#fff;padding:2px 7px;border-radius:4px;font-size:10px;font-weight:700;">Review</span>';
    }
    var chipCell = document.getElementById('import-chip-' + oi);
    if (chipCell) chipCell.innerHTML = chip;
  }

  window.toggleImportRow = function(oi) {
    var detail = document.getElementById('import-detail-' + oi);
    var chev   = document.getElementById('import-chev-'   + oi);
    if (!detail) return;
    var open = detail.style.display !== 'none';
    detail.style.display = open ? 'none' : '';
    if (chev) chev.style.transform = open ? '' : 'rotate(90deg)';
  };

  function updateImportSummary() {
    var ready = 0, review = 0, fix = 0;
    _importParsed.forEach(function(order) {
      var anyNull  = order.items.some(function(i){ return i.confidence === null; });
      var allHigh  = order.items.every(function(i){ return i.confidence === 'high'; });
      if (anyNull)       fix++;
      else if (allHigh)  ready++;
      else               review++;
    });
    var btn     = document.getElementById('import-submit-btn');
    var summary = document.getElementById('import-summary-line');
    var importable = ready + review;
    if (btn) btn.disabled = importable === 0;
    if (btn) btn.innerHTML = importable > 0
      ? '<i class="ti ti-cloud-upload"></i>  Import ' + importable + ' Order' + (importable > 1 ? 's' : '')
      : '  No orders ready';
    if (summary) {
      summary.style.display = _importParsed.length ? '' : 'none';
      summary.innerHTML = '<span style="color:#10B981;font-weight:600;">' + ready + ' ready</span>'
        + (review ? ' &nbsp;·&nbsp; <span style="color:#F59E0B;font-weight:600;">' + review + ' review</span>' : '')
        + (fix    ? ' &nbsp;·&nbsp; <span style="color:#EF4444;font-weight:600;">' + fix + ' need fixing</span>' : '');
    }
  }

  window.submitCsvImport = async function() {
    var importable = _importParsed.filter(function(order){
      return order.items.every(function(i){ return i.confidence !== null; });
    });
    if (!importable.length) { showToast('No orders ready to import', 'error'); return; }

    var paymentMode = (document.getElementById('import-payment-mode') || {}).value || 'COD';
    var orderStatus = (document.getElementById('import-order-status') || {}).value || 'ACTIVE';

    var payload = importable.map(function(order) {
      // Notes: list all order numbers (grouped orders have multiple)
      var notesOrderRef = (order.allOrderNos && order.allOrderNos.length > 1)
        ? 'Orders: ' + order.allOrderNos.join(', ')
        : 'Order No: ' + order.orderNo;
      var notesTracking = order.tracking ? ' | ' + order.tracking + ' via ' + order.shipping : '';
      return {
        customerName:      order.customer || 'E-commerce Customer',
        source:            'ECOMMERCE',
        ecommercePlatform: order.platform,
        paymentMode:       paymentMode,
        status:            orderStatus,
        orderType:         'STANDARD',
        notes:             notesOrderRef + notesTracking,
        discount:          0,
        deliveryFee:       0,
        items: order.items.map(function(item) {
          return {
            productId:   item.productId,
            productName: item.productName,
            quantity:    item.qty,
            unitPrice:   item.unitPrice || 0,
            warehouse:   item.warehouse || 'wh1'
          };
        })
      };
    });

    var btn = document.getElementById('import-submit-btn');
    if (btn) { btn.disabled = true; btn.innerHTML = '<i class="ti ti-loader"></i>  Importing…'; }

    try {
      const token = localStorage.getItem('rrbm_token');
      const res = await fetch(API_BASE + '/api/orders/batch', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token },
        body: JSON.stringify(payload)
      });
      if (!res.ok) {
        const d = await res.json().catch(function(){ return {}; });
        showToast(d.message || 'Import failed', 'error');
        if (btn) { btn.disabled = false; btn.innerHTML = '<i class="ti ti-cloud-upload"></i>  Import Orders'; }
        return;
      }
      const result = await res.json();
      var imported = result.imported || 0;
      var failed   = result.failed   || 0;
      var skippedList = result.skipped || [];
      if (imported === 0 && failed > 0 && skippedList.length === 0) {
        // Nothing went through — leave modal open so user can retry
        showToast('All ' + failed + ' order' + (failed !== 1 ? 's' : '') + ' failed to import', 'error');
        if (btn) { btn.disabled = false; btn.innerHTML = '<i class="ti ti-cloud-upload"></i>  Import Orders'; }
        return;
      }
      // At least some orders were imported (or skipped as dupes) — close main modal and reload
      closeModal('modal-import-ecom');
      var msg = imported + ' order' + (imported !== 1 ? 's' : '') + ' imported';
      if (failed) msg += ' · ' + failed + ' failed';
      if (skippedList.length) msg += ' · ' + skippedList.length + ' duplicate' + (skippedList.length !== 1 ? 's' : '') + ' skipped';
      showToast(msg, (failed || skippedList.length) ? 'warning' : 'success');
      loadOrders();
      // Show skipped-orders modal if any duplicates were found
      if (skippedList.length > 0) {
        var skippedTbody = $('csv-skipped-tbody');
        if (skippedTbody) {
          skippedTbody.innerHTML = skippedList.map(function(s) {
            return '<tr><td>' + escapeHtml(s.ref || '—') + '</td><td>' + escapeHtml(s.customer || '—') + '</td></tr>';
          }).join('');
        }
        setText('csv-skipped-count', skippedList.length + ' order' + (skippedList.length !== 1 ? 's' : '') + ' already exist in the system and were not re-imported.');
        openModal('modal-csv-skipped');
      }
    } catch (err) {
      // Network timeout or parse error — the backend may have already committed some/all orders.
      // Reload the order list so the user can see what was actually saved.
      console.warn('CSV import fetch error (orders may still have been saved):', err);
      loadOrders();
      showToast('Connection timed out — check the order list to see what was saved', 'warning');
      if (btn) { btn.disabled = false; btn.innerHTML = '<i class="ti ti-cloud-upload"></i>  Import Orders'; }
    }
  };

  // ================================================================
  // PURCHASE ORDERS PAGE
  // ================================================================
  var _allPoData        = [];    // raw list from backend
  var _poItemCodeMap      = {};   // itemCode → {name, unitCost, productId} from products
  var _poDescMap          = {};   // product name.toLowerCase() → itemCode (reverse lookup)
  var _poSuppliersCache   = null; // session-level cache of active suppliers for PO dropdown (null = not loaded)
  var _poSupplierMappings = {};   // productId → {supplierItemCode, supplierDescription, unitCost}
  var _deliveryPoCache  = {};    // poNumber → full PO object (for receive stocks auto-populate)
  var _deliveryRecords  = [];    // cached delivery report rows (for detail modal)
  var _importParsed     = [];    // parsed CSV orders waiting for import confirmation
  var _lastCsvText      = '';    // raw CSV text from last file read (for re-grouping)

  function loadPurchaseOrders() {
    var tbody = $('po-list-tbody');
    if (tbody) tbody.innerHTML = '<tr><td colspan="8" style="text-align:center;color:var(--text-muted);padding:24px;">Loading…</td></tr>';
    fetch(API_BASE + '/api/purchase-orders', { headers: authHeaders() })
      .then(function(res){ return res.json(); })
      .then(function(data){
        _allPoData = Array.isArray(data) ? data : [];
        filterPoList();
      })
      .catch(function(err){
        console.warn('PO load failed:', err);
        var tb = $('po-list-tbody');
        if (tb) tb.innerHTML = '<tr><td colspan="8" style="text-align:center;color:#EF4444;padding:24px;">Failed to load purchase orders</td></tr>';
      });
  }

  window.filterPoList = function() {
    var hideComplete = $('po-hide-complete') && $('po-hide-complete').checked;
    var visible = hideComplete
      ? _allPoData.filter(function(po){ return po.status !== 'COMPLETE'; })
      : _allPoData;
    renderPoList(visible);
  };

  function renderPoList(data) {
    var tbody = $('po-list-tbody');
    if (!tbody) return;
    var fmt = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
    if (!data.length) {
      tbody.innerHTML = '<tr><td colspan="9" style="text-align:center;color:var(--text-muted);padding:24px;">No purchase orders</td></tr>';
      return;
    }
    tbody.innerHTML = data.map(function(po){
      var isComplete  = po.status === 'COMPLETE';
      var isPartial   = po.status === 'PARTIALLY_RECEIVED';
      var statusBadge = isComplete
        ? '<span style="background:#10B981;color:#fff;padding:2px 8px;border-radius:4px;font-size:11px;font-weight:700;">COMPLETE</span>'
        : isPartial
        ? '<span style="background:#D4860A;color:#fff;padding:2px 8px;border-radius:4px;font-size:11px;font-weight:700;">PARTIAL</span>'
        : '<span style="background:#F59E0B;color:#fff;padding:2px 8px;border-radius:4px;font-size:11px;font-weight:700;">INCOMPLETE</span>';
      var fulfilled   = po.fulfilledCount || 0;
      var total       = po.totalItems     || 0;
      var dateStr     = po.createdAt ? formatDate(po.createdAt.substring(0,10)) : '—';
      // Collect unique DR numbers from fulfilled items
      var drNums = [];
      (po.items || []).forEach(function(i){
        if (i.isFulfilled && i.drNumber && drNums.indexOf(i.drNumber) < 0) drNums.push(i.drNumber);
      });
      var drCell = drNums.length
        ? drNums.map(function(n){ return '<span style="font-family:monospace;font-size:11px;background:var(--bg-secondary);padding:1px 5px;border-radius:3px;">' + escapeHtml(n) + '</span>'; }).join(' ')
        : '<span style="color:var(--text-muted);">—</span>';
      return '<tr id="po-row-' + po.id + '" style="cursor:pointer;" onclick="togglePoRow(' + po.id + ')">' +
        '<td style="text-align:center;color:var(--text-muted);"><i class="ti ti-chevron-right" id="po-chevron-' + po.id + '" style="transition:transform 0.2s;"></i></td>' +
        '<td style="font-family:monospace;font-weight:700;">' + escapeHtml(po.poNumber) + '</td>' +
        '<td>' + dateStr + '</td>' +
        '<td>' + escapeHtml(po.vendorName) +
          (po.vendorReference ? '<br><span style="font-size:10px;color:var(--text-muted);font-family:monospace;">' + escapeHtml(po.vendorReference) + '</span>' : '') +
        '</td>' +
        '<td style="text-align:right;">' + fulfilled + ' / ' + total + '</td>' +
        '<td style="text-align:right;font-weight:600;color:var(--accent);">' + fmt(po.totalAmount) + '</td>' +
        '<td>' + statusBadge + '</td>' +
        '<td>' + drCell + '</td>' +
        '<td style="text-align:right;" onclick="event.stopPropagation();">' +
          '<button class="btn btn-secondary btn-sm" onclick="printPoDocument(' + po.id + ')" title="Print PO"><i class="ti ti-printer"></i></button>' +
        '</td>' +
        '</tr>' +
        '<tr id="po-detail-' + po.id + '" style="display:none;">' +
          '<td colspan="9" style="padding:0;background:var(--bg-secondary);">' + buildPoDetailHtml(po) + '</td>' +
        '</tr>';
    }).join('');
  }

  function buildPoDetailHtml(po) {
    var fmt = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
    var items = po.items || [];
    var rows = items.map(function(item){
      var fulfilled = item.isFulfilled;
      var rowBg     = fulfilled ? 'background:#D1FAE5;' : '';
      return '<tr style="' + rowBg + '">' +
        '<td style="font-family:monospace;font-size:12px;color:var(--text-muted);">' + escapeHtml(item.itemCode || '—') + '</td>' +
        '<td>' + escapeHtml(item.itemDescription) + '</td>' +
        '<td style="font-family:monospace;font-size:11px;color:var(--text-muted);">' + escapeHtml(item.supplierItemCode || '—') + '</td>' +
        '<td style="font-size:11px;color:var(--text-muted);">' + escapeHtml(item.supplierDescription || '—') + '</td>' +
        '<td style="text-align:right;">' + (item.quantityOrdered || 0) + '</td>' +
        '<td style="text-align:right;">' + fmt(item.unitPrice) + '</td>' +
        '<td style="text-align:right;font-weight:600;">' + fmt(item.lineTotal) + '</td>' +
        '<td style="text-align:right;">' + (item.fulfilledQty || 0) + '</td>' +
        '<td style="font-family:monospace;font-size:12px;">' + escapeHtml(item.drNumber || '—') + '</td>' +
        '<td>' + (fulfilled
          ? '<span style="background:#10B981;color:#fff;padding:1px 6px;border-radius:4px;font-size:10px;font-weight:700;">Fulfilled</span>'
          : '<span style="background:#9CA3AF;color:#fff;padding:1px 6px;border-radius:4px;font-size:10px;">Pending</span>') + '</td>' +
        '</tr>';
    }).join('');
    return '<div style="padding:10px 16px;">' +
      '<div style="font-size:11px;color:var(--text-muted);margin-bottom:6px;">' +
        escapeHtml(po.vendorName || '') +
        (po.vendorContact ? ' · ' + escapeHtml(po.vendorContact) : '') +
        (po.shippingArrangement ? ' &nbsp;|&nbsp; Shipping: ' + escapeHtml(po.shippingArrangement) : '') +
        (po.vatType ? ' &nbsp;|&nbsp; VAT: ' + escapeHtml(po.vatType) : '') +
        (po.vendorReference ? ' &nbsp;|&nbsp; Ref: <span style="font-family:monospace;">' + escapeHtml(po.vendorReference) + '</span>' : '') +
      '</div>' +
      '<table class="table" style="margin:0;font-size:12px;">' +
        '<thead><tr>' +
          '<th>Item Code</th><th>Description</th>' +
          '<th style="max-width:90px;">Supplier Code</th><th>Supplier Desc</th>' +
          '<th style="text-align:right;">Qty</th><th style="text-align:right;">Unit Cost</th>' +
          '<th style="text-align:right;">Line Total</th><th style="text-align:right;">Fulfilled</th>' +
          '<th>DR #</th><th>Status</th>' +
        '</tr></thead>' +
        '<tbody>' + (rows || '<tr><td colspan="10" style="text-align:center;color:var(--text-muted);">No items</td></tr>') + '</tbody>' +
      '</table>' +
      (po.notes ? '<div style="font-size:11px;color:var(--text-muted);margin-top:6px;">Notes: ' + escapeHtml(po.notes) + '</div>' : '') +
    '</div>';
  }

  window.togglePoRow = function(id) {
    var detailRow  = $('po-detail-' + id);
    var chevron    = $('po-chevron-' + id);
    if (!detailRow) return;
    var expanded = detailRow.style.display !== 'none';
    detailRow.style.display = expanded ? 'none' : '';
    if (chevron) chevron.style.transform = expanded ? '' : 'rotate(90deg)';
  };

  window.togglePoStatus = function(id, newStatus) {
    fetch(API_BASE + '/api/purchase-orders/' + id + '/status', {
      method: 'PATCH',
      headers: Object.assign({'Content-Type': 'application/json'}, authHeaders()),
      body: JSON.stringify({ status: newStatus })
    })
    .then(function(res){ return res.json(); })
    .then(function(updated){
      // Update in-memory list
      var idx = _allPoData.findIndex(function(p){ return p.id === id; });
      if (idx >= 0) _allPoData[idx] = updated;
      filterPoList();
      showToast('Status updated to ' + newStatus, 'success');
    })
    .catch(function(err){ showToast('Failed to update status', 'error'); });
  };

  // ── New PO modal ────────────────────────────────────────────────────────
  // Populate the supplier dropdown in the New PO modal from cache (or API if not yet loaded).
  function _populatePoSupplierDropdown() {
    var sel = $('po-supplier-id');
    if (!sel) return;
    sel.innerHTML = '<option value="">— No supplier —</option>';
    (_poSuppliersCache || []).forEach(function(s) {
      var opt = document.createElement('option');
      opt.value = s.id;
      opt.textContent = s.name;
      sel.appendChild(opt);
    });
  }

  window.openNewPoModal = function() {
    // Reset form
    ['po-number','po-vendor-name','po-vendor-contact','po-vendor-address',
     'po-shipping','po-notes','po-vendor-reference'].forEach(function(id){ var el = $(id); if (el) el.value = ''; });
    var supSel = $('po-supplier-id'); if (supSel) supSel.value = '';
    var shipName    = $('po-ship-name');    if (shipName)    shipName.value    = 'RRBM Packaging Supplies and Trading';
    var shipContact = $('po-ship-contact'); if (shipContact) shipContact.value = '+63 966 846 9993';
    var shipAddr    = $('po-ship-address'); if (shipAddr)    shipAddr.value    = '116 Santan St., Fortune, Marikina City';
    var vatSel      = $('po-vat-type');     if (vatSel)      vatSel.value      = 'EXCLUSIVE';
    var totEl       = $('po-total-display'); if (totEl)      totEl.textContent = '₱0.00';
    _poSupplierMappings = {};

    // Start with 3 empty item rows
    var itb = $('po-items-tbody');
    if (itb) { itb.innerHTML = ''; addPoItemRow(); addPoItemRow(); addPoItemRow(); }

    // Populate supplier dropdown (session cache — load once)
    if (_poSuppliersCache !== null) {
      _populatePoSupplierDropdown();
    } else {
      fetch(API_BASE + '/api/suppliers?includeInactive=false', { headers: authHeaders() })
        .then(function(r){ return r.json(); })
        .then(function(data){
          _poSuppliersCache = Array.isArray(data) ? data : [];
          _populatePoSupplierDropdown();
        })
        .catch(function(err){ console.warn('Failed to load suppliers for PO dropdown:', err); });
    }

    // Pre-load inventory for autocomplete — item codes & product names
    fetch(API_BASE + '/api/products', { headers: authHeaders() })
      .then(function(r){ return r.json(); })
      .then(function(products){
        _poItemCodeMap = {};
        _poDescMap     = {};
        var codeList = $('po-datalist-codes');
        var descList = $('po-datalist-descs');
        if (codeList) codeList.innerHTML = '';
        if (descList) descList.innerHTML = '';

        products.filter(function(p){ return p.active !== false; }).forEach(function(p){
          if (!p.itemCode) return; // skip products without an item code
          var name = p.name || '';
          // productId stored alongside so lookupPoItemCode can match supplier mappings
          _poItemCodeMap[p.itemCode.toUpperCase()] = { name: name, unitCost: p.unitCost || 0, productId: p.id };
          _poDescMap[name.toLowerCase()] = p.itemCode.toUpperCase();

          if (codeList) {
            var opt = document.createElement('option');
            opt.value = p.itemCode.toUpperCase();
            opt.label = p.itemCode.toUpperCase() + ' — ' + name;
            codeList.appendChild(opt);
          }
          if (descList) {
            var opt2 = document.createElement('option');
            opt2.value = name;
            descList.appendChild(opt2);
          }
        });
      })
      .catch(function(){});

    $('modal-new-po').classList.add('open');
  };

  // Called when the Supplier dropdown in the New PO modal changes.
  // Auto-fills vendor fields from supplier data; loads supplier's mappings for hint display.
  window.onPoSupplierChange = function() {
    var supplierId = parseInt(($('po-supplier-id') || {}).value || '', 10) || null;
    var tbody = $('po-items-tbody');

    // Always clear all supplier code hints before applying new ones
    if (tbody) Array.from(tbody.rows).forEach(function(tr) {
      var hint = tr.querySelector('.po-supplier-hint');
      if (hint) { hint.textContent = ''; hint.style.display = 'none'; }
    });
    _poSupplierMappings = {};

    if (!supplierId) {
      // No supplier selected — blank vendor fields
      ['po-vendor-name','po-vendor-contact','po-vendor-address'].forEach(function(id) {
        var el = $(id); if (el) el.value = '';
      });
      return;
    }

    // Auto-fill vendor fields from cached supplier list
    var supplier = (_poSuppliersCache || []).find(function(s) { return s.id === supplierId; });
    if (supplier) {
      var vnEl = $('po-vendor-name');    if (vnEl) vnEl.value = supplier.name          || '';
      var vcEl = $('po-vendor-contact'); if (vcEl) vcEl.value = supplier.contactNumber || '';
      var vaEl = $('po-vendor-address'); if (vaEl) vaEl.value = supplier.address       || '';
    }

    // Load this supplier's product mappings, then re-run lookup on any filled rows
    fetch(API_BASE + '/api/suppliers/' + supplierId + '/mappings', { headers: authHeaders() })
      .then(function(r){ return r.json(); })
      .then(function(mappings){
        _poSupplierMappings = {};
        if (Array.isArray(mappings)) {
          mappings.forEach(function(m) {
            _poSupplierMappings[m.productId] = {
              supplierItemCode:    m.supplierItemCode,
              supplierDescription: m.supplierDescription,
              unitCost:            m.unitCost != null ? m.unitCost : null
            };
          });
        }
        // Re-run code lookup on all rows that already have a code — applies hints + price overrides
        if (tbody) Array.from(tbody.rows).forEach(function(tr) {
          var codeEl = tr.querySelector('.po-item-code');
          if (codeEl && (codeEl.value || '').trim()) lookupPoItemCode(codeEl);
        });
      })
      .catch(function(err){ console.warn('Failed to load PO supplier mappings:', err); });
  };

  window.addPoItemRow = function() {
    var tbody = $('po-items-tbody');
    if (!tbody) return;
    var idx = tbody.rows.length + 1;
    var tr  = document.createElement('tr');
    tr.innerHTML =
      '<td style="text-align:center;color:var(--text-muted);font-size:12px;">' + idx + '</td>' +
      // Item Code — required, linked to datalist, triggers auto-fill on change/blur
      // Hint div below shows supplier code when a mapping exists for the selected supplier
      '<td><input type="text" class="form-control form-control-sm po-item-code" ' +
           'list="po-datalist-codes" placeholder="Item code *" ' +
           'style="font-family:monospace;text-transform:uppercase;" ' +
           'oninput="this.value=this.value.toUpperCase()" ' +
           'onchange="lookupPoItemCode(this)" onblur="lookupPoItemCode(this)" />' +
           '<div class="po-supplier-hint" style="font-size:10px;color:var(--text-muted);display:none;margin-top:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;"></div></td>' +
      // Description — linked to datalist, triggers reverse auto-fill on change/blur
      '<td><input type="text" class="form-control form-control-sm po-item-desc" ' +
           'list="po-datalist-descs" placeholder="Select or type product name" ' +
           'onchange="lookupPoItemDesc(this)" onblur="lookupPoItemDesc(this)" /></td>' +
      '<td><input type="number" class="form-control form-control-sm po-item-qty" value="1" min="1" ' +
           'style="text-align:right;min-width:90px;width:100%;" oninput="calculatePoTotal()" /></td>' +
      '<td><input type="number" class="form-control form-control-sm po-item-price" value="0" min="0" step="0.01" ' +
           'style="text-align:right;" oninput="calculatePoTotal()" /></td>' +
      '<td style="text-align:right;font-weight:600;" class="po-item-lt">₱0.00</td>' +
      '<td><button class="icon-btn" onclick="removePoItemRow(this)" title="Remove"><i class="ti ti-trash" style="color:#EF4444;"></i></button></td>';
    tbody.appendChild(tr);
  };

  window.removePoItemRow = function(btn) {
    var tr = btn.closest('tr');
    if (tr) tr.remove();
    calculatePoTotal();
    // Re-number rows
    var tbody = $('po-items-tbody');
    if (tbody) Array.from(tbody.rows).forEach(function(r, i){
      var firstCell = r.cells[0];
      if (firstCell) firstCell.textContent = i + 1;
    });
  };

  // Called when the Item Code field changes or loses focus.
  // Validates the code against inventory, auto-fills description + unit price.
  window.lookupPoItemCode = function(input) {
    var code = (input.value || '').trim().toUpperCase();
    input.value = code; // normalize case in-place
    if (!code) return;
    var info = _poItemCodeMap[code];
    if (!info) {
      // Not in inventory — highlight red, show toast if it looks intentional (non-empty)
      input.style.outline = '2px solid #EF4444';
      showToast('Item code "' + code + '" is not in the inventory. Select a valid product.', 'error');
      return;
    }
    input.style.outline = ''; // reset
    var row   = input.closest('tr');
    if (!row) return;
    var descEl  = row.querySelector('.po-item-desc');
    var priceEl = row.querySelector('.po-item-price');
    var hintEl  = row.querySelector('.po-supplier-hint');
    if (descEl) { descEl.value = info.name; descEl.style.outline = ''; }
    // Check if the selected supplier has a mapping for this product
    var sm = info.productId ? _poSupplierMappings[info.productId] : null;
    if (sm && sm.supplierItemCode) {
      // Mapping found — show supplier code hint and prefer mapping unit cost
      if (hintEl) { hintEl.textContent = 'Supplier code: ' + sm.supplierItemCode; hintEl.style.display = ''; }
      if (priceEl) priceEl.value = sm.unitCost != null ? sm.unitCost : (info.unitCost || 0);
    } else {
      // No mapping (or no supplier selected) — hide hint, use inventory cost
      if (hintEl) { hintEl.textContent = ''; hintEl.style.display = 'none'; }
      if (priceEl) priceEl.value = info.unitCost || 0;
    }
    calculatePoTotal();
  };

  // Called when the Item Description field changes or loses focus.
  // Reverse-looks up the item code by product name, auto-fills code + unit price.
  window.lookupPoItemDesc = function(input) {
    var name = (input.value || '').trim();
    if (!name) return;
    var code = _poDescMap[name.toLowerCase()];
    if (!code) {
      // Typed a name that doesn't match any inventory product
      input.style.outline = '2px solid #EF4444';
      showToast('Product "' + name + '" was not found in inventory. Please select from the list.', 'error');
      return;
    }
    input.style.outline = '';
    var row = input.closest('tr');
    if (!row) return;
    var codeEl = row.querySelector('.po-item-code');
    if (codeEl) {
      codeEl.value = code;
      codeEl.style.outline = '';
      // Delegate to lookupPoItemCode — sets description, price, and supplier hint in one place
      lookupPoItemCode(codeEl);
    }
  };

  window.calculatePoTotal = function() {
    var tbody = $('po-items-tbody');
    var totEl = $('po-total-display');
    if (!tbody) return;
    var total = 0;
    Array.from(tbody.rows).forEach(function(tr){
      var qty   = parseFloat((tr.querySelector('.po-item-qty')   || {}).value  || 0) || 0;
      var price = parseFloat((tr.querySelector('.po-item-price') || {}).value  || 0) || 0;
      var lt    = qty * price;
      total    += lt;
      var ltCell = tr.querySelector('.po-item-lt');
      if (ltCell) ltCell.textContent = '₱' + lt.toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2});
    });
    if (totEl) totEl.textContent = '₱' + total.toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2});
  };

  window.submitNewPO = function() {
    var poNumber  = ($('po-number')        || {}).value || '';
    var vendorName = ($('po-vendor-name')  || {}).value || '';
    if (!poNumber.trim()) { showToast('PO number is required', 'error'); return; }
    if (!vendorName.trim()) { showToast('Vendor name is required', 'error'); return; }

    var tbody = $('po-items-tbody');
    var items = [];
    var hasError = false;
    if (tbody) Array.from(tbody.rows).forEach(function(tr){
      var codeEl = tr.querySelector('.po-item-code');
      var descEl = tr.querySelector('.po-item-desc');
      var code   = ((codeEl || {}).value || '').trim().toUpperCase();
      var desc   = ((descEl || {}).value || '').trim();
      // Skip blank rows
      if (!code && !desc) return;
      // Item code is required and must exist in inventory
      if (!code) {
        if (codeEl) codeEl.style.outline = '2px solid #EF4444';
        showToast('Item code is required for every line. Select a product from inventory.', 'error');
        hasError = true; return;
      }
      if (!_poItemCodeMap[code]) {
        if (codeEl) codeEl.style.outline = '2px solid #EF4444';
        showToast('Item code "' + code + '" is not in the inventory. Remove or correct it.', 'error');
        hasError = true; return;
      }
      var info = _poItemCodeMap[code];
      items.push({
        itemCode:        code,
        productId:       info.productId || null,
        itemDescription: desc || info.name,
        quantityOrdered: parseInt((tr.querySelector('.po-item-qty')   || {}).value || 1, 10),
        unitPrice:       parseFloat((tr.querySelector('.po-item-price') || {}).value || 0)
      });
    });
    if (hasError) return;
    if (!items.length) { showToast('Add at least one item', 'error'); return; }

    var supplierIdRaw = parseInt(($('po-supplier-id') || {}).value || '', 10);
    var payload = {
      poNumber:            poNumber.trim(),
      vendorName:          vendorName.trim(),
      vendorContact:       ($('po-vendor-contact')    || {}).value || null,
      vendorAddress:       ($('po-vendor-address')    || {}).value || null,
      shipToName:          ($('po-ship-name')         || {}).value || null,
      shipToContact:       ($('po-ship-contact')      || {}).value || null,
      shipToAddress:       ($('po-ship-address')      || {}).value || null,
      vatType:             ($('po-vat-type')           || {}).value || 'EXCLUSIVE',
      shippingArrangement: ($('po-shipping')           || {}).value || null,
      notes:               ($('po-notes')              || {}).value || null,
      supplierId:          supplierIdRaw || null,
      vendorReference:     ($('po-vendor-reference')  || {}).value || null,
      createdBy:           currentUserName(),
      items:               items
    };

    fetch(API_BASE + '/api/purchase-orders', {
      method: 'POST',
      headers: Object.assign({'Content-Type': 'application/json'}, authHeaders()),
      body: JSON.stringify(payload)
    })
    .then(function(res){ return res.json().then(function(d){ return {ok: res.ok, data: d}; }); })
    .then(function(r){
      if (!r.ok) { showToast(r.data.message || 'Failed to save PO', 'error'); return; }
      _allPoData.unshift(r.data);
      filterPoList();
      closeModal('modal-new-po');
      showToast('Purchase Order ' + r.data.poNumber + ' saved', 'success');
    })
    .catch(function(err){ showToast('Error saving PO: ' + (err.message || err), 'error'); });
  };

  // ── Print PO Document (Letter size, logo only, Unit Cost, PNG export) ─────
  window.printPoDocument = function(id) {
    var po = _allPoData.find(function(p){ return p.id === id; });
    if (!po) { showToast('PO not found', 'error'); return; }

    var fmt  = function(n){ return '&#8369;' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
    var poDate = po.createdAt
      ? new Date(po.createdAt).toLocaleDateString('en-PH', {year:'numeric',month:'long',day:'numeric'})
      : new Date().toLocaleDateString('en-PH', {year:'numeric',month:'long',day:'numeric'});
    var genTs = new Date().toLocaleString('en-PH', {year:'numeric',month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'});

    var items = (po.items || []);
    var itemRows = items.map(function(item, i){
      var supCode = item.supplierItemCode || item.itemCode || '';
      var supDesc = item.supplierDescription || item.itemDescription || '';
      return '<tr>' +
        '<td style="text-align:center;">' + (i+1) + '</td>' +
        '<td style="font-family:monospace;font-size:11px;">' + escapeHtml(item.itemCode || '') + '</td>' +
        '<td>' + escapeHtml(item.itemDescription || '') + '</td>' +
        '<td style="font-family:monospace;font-size:11px;">' + escapeHtml(supCode) + '</td>' +
        '<td style="font-size:11px;">' + escapeHtml(supDesc) + '</td>' +
        '<td style="text-align:center;">' + (item.quantityOrdered || 0) + '</td>' +
        '<td style="text-align:right;">' + fmt(item.unitPrice) + '</td>' +
        '<td style="text-align:right;font-weight:700;">' + fmt(item.lineTotal) + '</td>' +
        '</tr>';
    }).join('');

    var vatNote = po.vatType === 'INCLUSIVE' ? 'VAT Inclusive — grand total includes 12% VAT.'
                : po.vatType === 'EXEMPT'    ? 'VAT Exempt transaction.'
                :                              'VAT Exclusive — prices do not include VAT.';
    var subtotal   = Number(po.totalAmount || 0);
    var vatAmount  = po.vatType === 'INCLUSIVE' ? subtotal * 0.12 : 0;
    var grandTotal = subtotal + vatAmount;
    var tfootRows  = po.vatType === 'INCLUSIVE'
      ? '<tr style="background:#fff9f5;">' +
          '<td colspan="7" style="text-align:right;padding-right:12px;font-weight:600;">SUBTOTAL</td>' +
          '<td style="text-align:right;font-weight:600;">' + fmt(subtotal) + '</td>' +
        '</tr>' +
        '<tr style="background:#fff9f5;">' +
          '<td colspan="7" style="text-align:right;padding-right:12px;font-weight:600;">VAT (12%)</td>' +
          '<td style="text-align:right;font-weight:600;">' + fmt(vatAmount) + '</td>' +
        '</tr>' +
        '<tr class="total-row">' +
          '<td colspan="7" style="text-align:right;padding-right:12px;">GRAND TOTAL</td>' +
          '<td style="text-align:right;">' + fmt(grandTotal) + '</td>' +
        '</tr>'
      : '<tr class="total-row">' +
          '<td colspan="7" style="text-align:right;padding-right:12px;">ORDER TOTAL</td>' +
          '<td style="text-align:right;">' + fmt(subtotal) + '</td>' +
        '</tr>';

    // Resolve absolute logo URL from the current page's origin
    var logoUrl = window.location.origin + (window.location.pathname.replace(/[^/]*$/, '')) + 'assets/logo-two.png';

    var html = '<!DOCTYPE html><html><head><meta charset="UTF-8">' +
      '<title>PO-' + escapeHtml(po.poNumber) + '</title>' +
      '<script src="https://cdnjs.cloudflare.com/ajax/libs/html2canvas/1.4.1/html2canvas.min.js"' +
        ' onload="var b=document.getElementById(\'po-save-png\');if(b){b.disabled=false;b.title=\'Save as PNG\';}"><\/script>' +
      '<style>' +
        '@page{size:letter;margin:15mm;}' +
        'body{font-family:Arial,sans-serif;font-size:12px;margin:0;padding:18px;color:#111;}' +
        '#po-actions{position:fixed;top:10px;right:10px;z-index:9999;display:flex;gap:8px;}' +
        '#po-actions button{padding:6px 14px;border-radius:6px;border:1px solid #ccc;cursor:pointer;font-size:12px;background:#fff;}' +
        '#po-actions .btn-pdf{background:#C25A0A;color:#fff;border-color:#C25A0A;}' +
        '.hdr{display:flex;justify-content:space-between;align-items:flex-start;border-bottom:2px solid #C25A0A;padding-bottom:10px;margin-bottom:14px;}' +
        '.po-title-blk{text-align:right;}' +
        '.po-title{font-size:22px;font-weight:700;color:#C25A0A;}' +
        '.po-num{font-size:13px;font-family:monospace;color:#555;margin-top:2px;}' +
        '.po-date{font-size:11px;color:#888;margin-top:2px;}' +
        '.meta-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin:0 0 14px 0;border:1px solid #e5e7eb;border-radius:6px;padding:12px;}' +
        '.meta-sect h4{font-size:10px;text-transform:uppercase;letter-spacing:1px;color:#888;margin:0 0 5px 0;}' +
        '.meta-sect p{margin:2px 0;font-size:12px;}' +
        'table{width:100%;border-collapse:collapse;margin:0 0 10px 0;}' +
        'thead th{background:#C25A0A;color:#fff;padding:6px 8px;font-size:11px;text-align:left;}' +
        'td{padding:5px 8px;border-bottom:1px solid #f0f0f0;font-size:11px;vertical-align:top;}' +
        'tr:nth-child(even) td{background:#fafafa;}' +
        '.total-row td{font-weight:700;border-top:2px solid #C25A0A;font-size:13px;background:#fff9f5;}' +
        '.notes-blk{font-size:11px;color:#555;margin:6px 0;font-style:italic;}' +
        '.sig-block{margin-top:36px;display:grid;grid-template-columns:1fr 1fr;gap:60px;}' +
        '.sig-line{border-top:1px solid #555;padding-top:4px;font-size:11px;color:#555;margin-top:30px;}' +
        '.footer{margin-top:20px;border-top:1px solid #e5e7eb;padding-top:8px;font-size:10px;color:#aaa;display:flex;justify-content:space-between;}' +
        '@media print{#po-actions{display:none!important;}}' +
      '</style></head><body>' +
      '<div id="po-actions">' +
        '<button class="btn-pdf" onclick="window.print()"><i>&#128438;</i> Print / Save PDF</button>' +
        '<button id="po-save-png" onclick="savePNG()" disabled title="Loading export library…" style="opacity:0.5;cursor:not-allowed;">&#128247; Save as PNG</button>' +
      '</div>' +
      '<div class="hdr">' +
        '<img src="' + logoUrl + '" style="height:64px;object-fit:contain;" onerror="this.style.display=\'none\'" />' +
        '<div class="po-title-blk">' +
          '<div class="po-title">PURCHASE ORDER</div>' +
          '<div class="po-num">P.O. # ' + escapeHtml(po.poNumber) + '</div>' +
          '<div class="po-date">Date: ' + poDate + '</div>' +
        '</div>' +
      '</div>' +
      '<div class="meta-grid">' +
        '<div class="meta-sect"><h4>Vendor</h4>' +
          '<p><strong>' + escapeHtml(po.vendorName || '') + '</strong></p>' +
          (po.vendorContact ? '<p>' + escapeHtml(po.vendorContact) + '</p>' : '') +
          (po.vendorAddress ? '<p>' + escapeHtml(po.vendorAddress) + '</p>' : '') +
          (po.vendorReference ? '<p style="font-family:monospace;font-size:11px;color:#555;">Ref: ' + escapeHtml(po.vendorReference) + '</p>' : '') +
        '</div>' +
        '<div class="meta-sect"><h4>Ship To</h4>' +
          '<p><strong>' + escapeHtml(po.shipToName || 'RRBM Packaging Supplies') + '</strong></p>' +
          (po.shipToContact ? '<p>' + escapeHtml(po.shipToContact) + '</p>' : '') +
          (po.shipToAddress ? '<p>' + escapeHtml(po.shipToAddress) + '</p>' : '') +
        '</div>' +
      '</div>' +
      '<table>' +
        '<thead><tr>' +
          '<th style="width:28px;text-align:center;">#</th>' +
          '<th style="width:80px;">Item Code</th>' +
          '<th>Description</th>' +
          '<th style="width:80px;">Supplier Code</th>' +
          '<th>Supplier Desc</th>' +
          '<th style="width:40px;text-align:center;">Qty</th>' +
          '<th style="width:80px;text-align:right;">Unit Cost</th>' +
          '<th style="width:90px;text-align:right;">Line Total</th>' +
        '</tr></thead>' +
        '<tbody>' + itemRows + '</tbody>' +
        '<tfoot>' + tfootRows + '</tfoot>' +
      '</table>' +
      '<p style="font-size:11px;color:#888;margin:4px 0;">' + vatNote + '</p>' +
      (po.shippingArrangement ? '<p class="notes-blk">Shipping Arrangement: ' + escapeHtml(po.shippingArrangement) + '</p>' : '') +
      (po.notes ? '<p class="notes-blk">Notes / Remarks: ' + escapeHtml(po.notes) + '</p>' : '') +
      '<div class="sig-block">' +
        '<div><div class="sig-line">Prepared by</div></div>' +
        '<div><div class="sig-line">Approved by</div></div>' +
      '</div>' +
      '<div class="footer">' +
        '<span>Generated: ' + genTs + '</span>' +
        '<span>INTERNAL DOCUMENT &mdash; CONFIDENTIAL &mdash; FOR RRBM USE ONLY</span>' +
      '</div>' +
      '<script>' +
        'function savePNG(){' +
          'if(!window.html2canvas){alert("Export library not available. Use Print / Save PDF instead.");return;}' +
          'var actions=document.getElementById("po-actions");' +
          'if(actions)actions.style.display="none";' +
          'window.html2canvas(document.body,{scale:2,useCORS:true,allowTaint:true}).then(function(canvas){' +
            'var a=document.createElement("a");' +
            'a.download="PO-' + escapeHtml(po.poNumber) + '.png";' +
            'a.href=canvas.toDataURL("image/png");' +
            'document.body.appendChild(a);' +
            'a.click();' +
            'document.body.removeChild(a);' +
            'if(actions)actions.style.display="flex";' +
          '}).catch(function(){' +
            'if(actions)actions.style.display="flex";' +
            'alert("PNG export failed. Try Print / Save PDF instead.");' +
          '});' +
        '}' +
      '<\/script>' +
      '</body></html>';

    var w = window.open('', '_blank', 'width=960,height=780');
    if (w) { w.document.write(html); w.document.close(); w.focus(); }
  };

  // ================================================================
  // SUPPLIERS PAGE
  // ================================================================
  var _allSuppliers = [];

  window.loadSuppliers = function() {
    var tbody = $('supplier-tbody');
    if (tbody) tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);padding:24px;">Loading…</td></tr>';
    fetch(API_BASE + '/api/suppliers?includeInactive=true', { headers: authHeaders() })
      .then(function(res) { return res.json(); })
      .then(function(data) {
        _allSuppliers = Array.isArray(data) ? data : [];
        filterSupplierList();
      })
      .catch(function(err) {
        console.warn('Supplier load failed:', err);
        var tb = $('supplier-tbody');
        if (tb) tb.innerHTML = '<tr><td colspan="6" style="text-align:center;color:#EF4444;padding:24px;">Failed to load suppliers</td></tr>';
      });
  };

  window.filterSupplierList = function() {
    var showInactive = $('sup-show-inactive') && $('sup-show-inactive').checked;
    var visible = showInactive
      ? _allSuppliers
      : _allSuppliers.filter(function(s) { return s.isActive !== false; });
    renderSupplierList(visible);
  };

  function renderSupplierList(list) {
    var tbody = $('supplier-tbody');
    if (!tbody) return;
    if (!list.length) {
      tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);padding:24px;">No suppliers found</td></tr>';
      return;
    }
    tbody.innerHTML = list.map(function(s) {
      var isActive  = s.isActive !== false;
      var rowStyle  = isActive ? '' : 'opacity:0.6;';
      var badge     = isActive
        ? '<span class="badge badge-ok">Active</span>'
        : '<span class="badge badge-low">Inactive</span>';
      var actionBtn = isActive
        ? '<button class="btn btn-sm" style="background:#EF4444;color:#fff;border:none;" onclick="deactivateSupplier(' + s.id + ')" title="Deactivate"><i class="ti ti-ban"></i></button>'
        : '<button class="btn btn-sm btn-secondary" onclick="reactivateSupplier(' + s.id + ')" title="Reactivate"><i class="ti ti-circle-check"></i></button>';
      return '<tr style="' + rowStyle + '">' +
        '<td style="font-weight:600;">' + escapeHtml(s.name) + '</td>' +
        '<td>' + escapeHtml(s.contactPerson || '—') + '</td>' +
        '<td>' + escapeHtml(s.contactNumber || '—') + '</td>' +
        '<td>' + escapeHtml(s.paymentTerms || '—') + '</td>' +
        '<td>' + badge + '</td>' +
        '<td style="text-align:right;white-space:nowrap;">' +
          '<button class="btn btn-secondary btn-sm" onclick="openSupplierMappingsModal(' + s.id + ')" style="margin-right:4px;" title="Mappings"><i class="ti ti-link"></i></button>' +
          '<button class="btn btn-secondary btn-sm" onclick="openEditSupplierModal(' + s.id + ')" style="margin-right:4px;" title="Edit"><i class="ti ti-pencil"></i></button>' +
          actionBtn +
        '</td>' +
        '</tr>';
    }).join('');
  }

  window.openAddSupplierModal = function() {
    ['add-sup-name','add-sup-contact-person','add-sup-contact-number',
     'add-sup-address','add-sup-payment-terms','add-sup-notes'].forEach(function(id) {
      var el = $(id); if (el) el.value = '';
    });
    $('modal-add-supplier').classList.add('open');
  };

  window.submitAddSupplier = function() {
    var name = (($('add-sup-name') || {}).value || '').trim();
    if (!name) { showToast('Supplier name is required', 'error'); return; }
    var payload = {
      name:          name,
      contactPerson: (($('add-sup-contact-person') || {}).value || '').trim() || null,
      contactNumber: (($('add-sup-contact-number') || {}).value || '').trim() || null,
      address:       (($('add-sup-address')        || {}).value || '').trim() || null,
      paymentTerms:  (($('add-sup-payment-terms')  || {}).value || '').trim() || null,
      notes:         (($('add-sup-notes')          || {}).value || '').trim() || null,
    };
    fetch(API_BASE + '/api/suppliers', {
      method: 'POST',
      headers: Object.assign({'Content-Type': 'application/json'}, authHeaders()),
      body: JSON.stringify(payload)
    })
    .then(function(res) { return res.json().then(function(d) { return {ok: res.ok, data: d}; }); })
    .then(function(r) {
      if (!r.ok) { showToast(r.data.message || 'Failed to add supplier', 'error'); return; }
      _allSuppliers.unshift(r.data);
      filterSupplierList();
      closeModal('modal-add-supplier');
      showToast('Supplier "' + escapeHtml(r.data.name) + '" added', 'success');
    })
    .catch(function(err) { showToast('Error adding supplier: ' + (err.message || err), 'error'); });
  };

  window.openEditSupplierModal = function(id) {
    var s = _allSuppliers.find(function(x) { return x.id === id; });
    if (!s) { showToast('Supplier not found', 'error'); return; }
    var setVal = function(elId, val) { var el = $(elId); if (el) el.value = val || ''; };
    setVal('edit-sup-id',             s.id);
    setVal('edit-sup-name',           s.name);
    setVal('edit-sup-contact-person', s.contactPerson);
    setVal('edit-sup-contact-number', s.contactNumber);
    setVal('edit-sup-address',        s.address);
    setVal('edit-sup-payment-terms',  s.paymentTerms);
    setVal('edit-sup-notes',          s.notes);
    $('modal-edit-supplier').classList.add('open');
  };

  window.submitEditSupplier = function() {
    var id   = parseInt(($('edit-sup-id') || {}).value || 0, 10);
    var name = (($('edit-sup-name') || {}).value || '').trim();
    if (!id)   { showToast('Missing supplier ID', 'error'); return; }
    if (!name) { showToast('Supplier name is required', 'error'); return; }
    var payload = {
      name:          name,
      contactPerson: (($('edit-sup-contact-person') || {}).value || '').trim() || null,
      contactNumber: (($('edit-sup-contact-number') || {}).value || '').trim() || null,
      address:       (($('edit-sup-address')        || {}).value || '').trim() || null,
      paymentTerms:  (($('edit-sup-payment-terms')  || {}).value || '').trim() || null,
      notes:         (($('edit-sup-notes')          || {}).value || '').trim() || null,
    };
    fetch(API_BASE + '/api/suppliers/' + id, {
      method: 'PATCH',
      headers: Object.assign({'Content-Type': 'application/json'}, authHeaders()),
      body: JSON.stringify(payload)
    })
    .then(function(res) { return res.json().then(function(d) { return {ok: res.ok, data: d}; }); })
    .then(function(r) {
      if (!r.ok) { showToast(r.data.message || 'Failed to update supplier', 'error'); return; }
      var idx = _allSuppliers.findIndex(function(x) { return x.id === id; });
      if (idx >= 0) _allSuppliers[idx] = r.data;
      filterSupplierList();
      closeModal('modal-edit-supplier');
      showToast('Supplier updated', 'success');
    })
    .catch(function(err) { showToast('Error updating supplier: ' + (err.message || err), 'error'); });
  };

  window.deactivateSupplier = function(id) {
    var s    = _allSuppliers.find(function(x) { return x.id === id; });
    var name = s ? s.name : 'this supplier';
    // Use a toast-level confirmation via a small inline modal approach rather than native confirm()
    _pendingDeactivateSupplierId = id;
    _pendingDeactivateSupplierName = name;
    var overlay = $('modal-deactivate-supplier-confirm');
    if (overlay) {
      var lbl = document.getElementById('deactivate-sup-name-label');
      if (lbl) lbl.textContent = '"' + name + '"';
      overlay.classList.add('open');
    }
  };

  var _pendingDeactivateSupplierId   = null;
  var _pendingDeactivateSupplierName = '';

  window.confirmDeactivateSupplier = function() {
    var id = _pendingDeactivateSupplierId;
    if (!id) return;
    closeModal('modal-deactivate-supplier-confirm');
    fetch(API_BASE + '/api/suppliers/' + id, {
      method: 'DELETE',
      headers: authHeaders()
    })
    .then(function(res) {
      if (!res.ok) return res.json().then(function(d) { throw new Error(d.message || 'Failed'); });
      var idx = _allSuppliers.findIndex(function(x) { return x.id === id; });
      if (idx >= 0) _allSuppliers[idx].isActive = false;
      filterSupplierList();
      showToast('Supplier deactivated', 'success');
    })
    .catch(function(err) { showToast('Error: ' + (err.message || err), 'error'); });
  };

  window.reactivateSupplier = function(id) {
    var s = _allSuppliers.find(function(x) { return x.id === id; });
    if (!s) { showToast('Supplier not found', 'error'); return; }
    fetch(API_BASE + '/api/suppliers/' + id, {
      method: 'PATCH',
      headers: Object.assign({'Content-Type': 'application/json'}, authHeaders()),
      body: JSON.stringify({ name: s.name, isActive: true })
    })
    .then(function(res) { return res.json().then(function(d) { return {ok: res.ok, data: d}; }); })
    .then(function(r) {
      if (!r.ok) { showToast(r.data.message || 'Failed to reactivate', 'error'); return; }
      var idx = _allSuppliers.findIndex(function(x) { return x.id === id; });
      if (idx >= 0) _allSuppliers[idx] = r.data;
      filterSupplierList();
      showToast('Supplier reactivated', 'success');
    })
    .catch(function(err) { showToast('Error: ' + (err.message || err), 'error'); });
  };

  // ================================================================
  // SUPPLIER MAPPINGS
  // ================================================================
  var _currentMappingsSupplierId = null;
  var _currentMappings           = [];
  var _editingMappingId          = null;
  var _pendingDeleteMappingId    = null;
  var _allProductsCache          = null;   // session-level lazy cache

  window.openSupplierMappingsModal = function(supplierId) {
    var supplier = _allSuppliers.find(function(x) { return x.id === supplierId; });
    if (!supplier) { showToast('Supplier not found', 'error'); return; }

    _currentMappingsSupplierId = supplierId;
    _editingMappingId          = null;

    var nameEl = document.getElementById('mapping-modal-supplier-name');
    if (nameEl) nameEl.textContent = supplier.name;

    var overlay = $('modal-supplier-mappings');
    if (overlay) overlay.classList.add('open');

    // Lazy-load products then load mappings
    _loadProductsForMappingDropdown(function() {
      _loadMappingsForSupplier(supplierId);
    });
  };

  function _loadProductsForMappingDropdown(callback) {
    if (_allProductsCache !== null) {
      _populateMappingProductDropdown();
      if (callback) callback();
      return;
    }
    fetch(API_BASE + '/api/products', { headers: authHeaders() })
      .then(function(r) { return r.json(); })
      .then(function(data) {
        _allProductsCache = Array.isArray(data)
          ? data.filter(function(p) { return p.active !== false; })
          : [];
        _populateMappingProductDropdown();
        if (callback) callback();
      })
      .catch(function(err) {
        console.warn('Failed to load products for mapping dropdown:', err);
        if (callback) callback();
      });
  }

  function _populateMappingProductDropdown() {
    var sel = $('new-map-product-id');
    if (!sel) return;
    var opts = (_allProductsCache || []).map(function(p) {
      var label = escapeHtml(p.name) + (p.itemCode ? ' (' + escapeHtml(p.itemCode) + ')' : '');
      return '<option value="' + p.id + '">' + label + '</option>';
    });
    sel.innerHTML = '<option value="">— select product —</option>' + opts.join('');
  }

  function _loadMappingsForSupplier(supplierId) {
    var tbody = $('mapping-tbody');
    if (tbody) tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);padding:24px;">Loading…</td></tr>';
    fetch(API_BASE + '/api/suppliers/' + supplierId + '/mappings', { headers: authHeaders() })
      .then(function(r) { return r.json(); })
      .then(function(data) {
        _currentMappings = Array.isArray(data) ? data : [];
        renderMappingList();
      })
      .catch(function(err) {
        console.warn('Failed to load mappings:', err);
        var tb = $('mapping-tbody');
        if (tb) tb.innerHTML = '<tr><td colspan="6" style="text-align:center;color:#EF4444;padding:24px;">Failed to load mappings</td></tr>';
      });
  }

  function renderMappingList() {
    var tbody = $('mapping-tbody');
    if (!tbody) return;
    if (!_currentMappings.length) {
      tbody.innerHTML =
        '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);padding:36px;">' +
        '<i class="ti ti-mood-empty" style="font-size:24px;display:block;margin-bottom:8px;"></i>' +
        'No mappings yet. Use the form above to add one.</td></tr>';
      return;
    }
    tbody.innerHTML = _currentMappings.map(function(m) {
      var isEditing   = (_editingMappingId === m.id);
      var isPreferred = (m.isPreferred === true);
      var costFmt     = m.unitCost != null
        ? '&#8369;' + Number(m.unitCost).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
        : '&mdash;';

      if (isEditing) {
        return '<tr id="map-row-' + m.id + '" style="background:var(--surface-alt,#fafafa);">' +
          '<td style="font-size:12px;color:var(--text-muted);font-weight:500;">' + escapeHtml(m.productName || 'Unknown') + '</td>' +
          '<td><input type="text" class="form-control form-control-sm" id="edit-map-code-' + m.id + '" ' +
               'value="' + escapeHtml(m.supplierItemCode || '') + '" ' +
               'style="font-family:monospace;font-size:12px;min-width:90px;" /></td>' +
          '<td><input type="text" class="form-control form-control-sm" id="edit-map-desc-' + m.id + '" ' +
               'value="' + escapeHtml(m.supplierDescription || '') + '" ' +
               'style="min-width:130px;" /></td>' +
          '<td><input type="number" class="form-control form-control-sm" id="edit-map-cost-' + m.id + '" ' +
               'value="' + (m.unitCost != null ? m.unitCost : '') + '" ' +
               'step="0.01" min="0" style="text-align:right;min-width:90px;" /></td>' +
          '<td style="text-align:center;">' +
            '<input type="checkbox" id="edit-map-pref-' + m.id + '"' + (isPreferred ? ' checked' : '') +
            ' style="width:14px;height:14px;" /></td>' +
          '<td style="text-align:right;white-space:nowrap;">' +
            '<button class="btn btn-primary btn-sm" onclick="saveEditMapping(' + m.id + ')" ' +
            'style="margin-right:4px;" title="Save"><i class="ti ti-device-floppy"></i></button>' +
            '<button class="btn btn-secondary btn-sm" onclick="cancelEditMapping()" title="Cancel"><i class="ti ti-x"></i></button>' +
          '</td></tr>';
      }

      var preferredCell = isPreferred
        ? '<span class="badge badge-ok" style="font-size:10px;"><i class="ti ti-star-filled" style="margin-right:3px;font-size:10px;"></i>Preferred</span>'
        : '<button class="btn btn-sm btn-secondary" onclick="togglePreferredMapping(' + m.id + ')" ' +
          'title="Set as preferred" style="font-size:11px;padding:2px 8px;">Set</button>';

      return '<tr id="map-row-' + m.id + '">' +
        '<td style="font-weight:500;">'                                            + escapeHtml(m.productName || 'Unknown') + '</td>' +
        '<td style="font-family:monospace;font-size:12px;">'                       + escapeHtml(m.supplierItemCode || '—') + '</td>' +
        '<td style="font-size:13px;">'                                             + escapeHtml(m.supplierDescription || '—') + '</td>' +
        '<td style="text-align:right;font-weight:600;">'                           + costFmt + '</td>' +
        '<td style="text-align:center;">'                                          + preferredCell + '</td>' +
        '<td style="text-align:right;white-space:nowrap;">' +
          '<button class="btn btn-secondary btn-sm" onclick="startEditMapping(' + m.id + ')" ' +
          'style="margin-right:4px;" title="Edit"><i class="ti ti-pencil"></i></button>' +
          '<button class="btn btn-sm" style="background:#EF4444;color:#fff;border:none;" ' +
          'onclick="deleteMappingConfirm(' + m.id + ')" title="Delete"><i class="ti ti-trash"></i></button>' +
        '</td></tr>';
    }).join('');
  }

  window.startEditMapping = function(id) {
    _editingMappingId = id;
    renderMappingList();
  };

  window.cancelEditMapping = function() {
    _editingMappingId = null;
    renderMappingList();
  };

  window.saveEditMapping = function(id) {
    var m = _currentMappings.find(function(x) { return x.id === id; });
    if (!m) return;

    var codeEl = document.getElementById('edit-map-code-' + id);
    var descEl = document.getElementById('edit-map-desc-' + id);
    var costEl = document.getElementById('edit-map-cost-' + id);
    var prefEl = document.getElementById('edit-map-pref-' + id);

    var costRaw  = costEl ? costEl.value : '';
    var payload  = {
      supplierItemCode:    codeEl ? (codeEl.value.trim() || null) : null,
      supplierDescription: descEl ? (descEl.value.trim() || null) : null,
      unitCost:            (costRaw !== '' && costRaw != null) ? parseFloat(costRaw) : null,
      isPreferred:         prefEl ? prefEl.checked : false,
    };

    fetch(API_BASE + '/api/suppliers/' + _currentMappingsSupplierId + '/mappings/' + id, {
      method:  'PATCH',
      headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
      body:    JSON.stringify(payload)
    })
    .then(function(res) { return res.json().then(function(d) { return { ok: res.ok, data: d }; }); })
    .then(function(r) {
      if (!r.ok) { showToast(r.data.message || 'Failed to update mapping', 'error'); return; }
      // Option A: if now preferred, clear preferred on other mappings for same product
      if (r.data.isPreferred) {
        _currentMappings.forEach(function(x) {
          if (x.productId === r.data.productId && x.id !== id) x.isPreferred = false;
        });
      }
      var idx = _currentMappings.findIndex(function(x) { return x.id === id; });
      if (idx >= 0) _currentMappings[idx] = r.data;
      _editingMappingId = null;
      renderMappingList();
      showToast('Mapping updated', 'success');
    })
    .catch(function(err) { showToast('Error: ' + (err.message || err), 'error'); });
  };

  window.togglePreferredMapping = function(id) {
    var m = _currentMappings.find(function(x) { return x.id === id; });
    if (!m) return;

    fetch(API_BASE + '/api/suppliers/' + _currentMappingsSupplierId + '/mappings/' + id, {
      method:  'PATCH',
      headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
      body:    JSON.stringify({ isPreferred: true })
    })
    .then(function(res) { return res.json().then(function(d) { return { ok: res.ok, data: d }; }); })
    .then(function(r) {
      if (!r.ok) { showToast(r.data.message || 'Failed to set preferred', 'error'); return; }
      // Option A: immediately mark all other mappings for this product as non-preferred
      _currentMappings.forEach(function(x) {
        if (x.productId === r.data.productId) x.isPreferred = (x.id === id);
      });
      var idx = _currentMappings.findIndex(function(x) { return x.id === id; });
      if (idx >= 0) _currentMappings[idx] = r.data;
      renderMappingList();
      showToast('Mapping set as preferred', 'success');
    })
    .catch(function(err) { showToast('Error: ' + (err.message || err), 'error'); });
  };

  window.deleteMappingConfirm = function(id) {
    var m = _currentMappings.find(function(x) { return x.id === id; });
    if (!m) return;
    _pendingDeleteMappingId = id;
    var lbl = document.getElementById('delete-map-product-label');
    if (lbl) lbl.textContent = '"' + (m.productName || 'this product') + '"';
    var overlay = $('modal-delete-mapping-confirm');
    if (overlay) overlay.classList.add('open');
  };

  window.confirmDeleteMapping = function() {
    var id = _pendingDeleteMappingId;
    if (!id) return;
    closeModal('modal-delete-mapping-confirm');
    fetch(API_BASE + '/api/suppliers/' + _currentMappingsSupplierId + '/mappings/' + id, {
      method:  'DELETE',
      headers: authHeaders()
    })
    .then(function(res) {
      if (!res.ok) return res.json().then(function(d) { throw new Error(d.message || 'Failed'); });
      _currentMappings = _currentMappings.filter(function(x) { return x.id !== id; });
      renderMappingList();
      showToast('Mapping deleted', 'success');
    })
    .catch(function(err) { showToast('Error: ' + (err.message || err), 'error'); });
  };

  window.submitAddMapping = function() {
    var productId = parseInt((($('new-map-product-id') || {}).value || '0'), 10);
    if (!productId) { showToast('Select a product', 'error'); return; }

    var itemCode  = (($('new-map-item-code')   || {}).value || '').trim();
    var desc      = (($('new-map-description') || {}).value || '').trim();
    var costRaw   = ($('new-map-unit-cost')    || {}).value;
    var preferred = !!($('new-map-preferred')  && $('new-map-preferred').checked);

    var payload = {
      productId:           productId,
      supplierItemCode:    itemCode || null,
      supplierDescription: desc    || null,
      unitCost:            (costRaw !== '' && costRaw != null) ? parseFloat(costRaw) : null,
      isPreferred:         preferred,
    };

    fetch(API_BASE + '/api/suppliers/' + _currentMappingsSupplierId + '/mappings', {
      method:  'POST',
      headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
      body:    JSON.stringify(payload)
    })
    .then(function(res) { return res.json().then(function(d) { return { ok: res.ok, data: d }; }); })
    .then(function(r) {
      if (!r.ok) { showToast(r.data.message || 'Failed to add mapping', 'error'); return; }
      // Option A: if new mapping is preferred, clear others for the same product
      if (r.data.isPreferred) {
        _currentMappings.forEach(function(x) {
          if (x.productId === r.data.productId) x.isPreferred = false;
        });
      }
      _currentMappings.push(r.data);
      renderMappingList();
      // Reset add-form
      var sel = $('new-map-product-id'); if (sel) sel.value = '';
      ['new-map-item-code', 'new-map-description', 'new-map-unit-cost'].forEach(function(fid) {
        var el = $(fid); if (el) el.value = '';
      });
      var pref = $('new-map-preferred'); if (pref) pref.checked = false;
      showToast('Mapping added', 'success');
    })
    .catch(function(err) { showToast('Error: ' + (err.message || err), 'error'); });
  };

  // ================================================================
  // DAILY REPORTS PAGE
  // ================================================================
  function loadDailyReports() {
    var tb = $('daily-reports-tbody');
    if (tb) tb.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);padding:24px;">Loading…</td></tr>';
    fetch(API_BASE + '/api/reports/daily-reports-list', { headers: authHeaders() })
      .then(function(r) { return r.json(); })
      .then(function(data) { renderDailyReportsList(data.reports || []); })
      .catch(function() {
        var tb2 = $('daily-reports-tbody');
        if (tb2) tb2.innerHTML = '<tr><td colspan="6" style="text-align:center;color:#EF4444;padding:24px;">Failed to load daily reports</td></tr>';
      });
  }

  function renderDailyReportsList(reports) {
    var tb = $('daily-reports-tbody');
    if (!tb) return;
    if (!reports || !reports.length) {
      tb.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);padding:24px;">No daily reports found. Close a daily sales session to create one.</td></tr>';
      return;
    }
    var fmt = function(v) {
      return '₱' + Number(v || 0).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    };
    tb.innerHTML = reports.map(function(r) {
      var closedAt = r.closedAt ? (formatDate(r.closedAt) + ' ' + formatTime(r.closedAt)) : '—';
      var netVal   = r.netSales != null ? r.netSales : r.totalRevenue;
      var safeDate = (r.reportDate || '').replace(/'/g, "\\'");
      return '<tr>' +
        '<td><strong style="font-size:13px;">' + (r.reportDate || '—') + '</strong></td>' +
        '<td style="font-size:12px;color:var(--text-muted);">' + closedAt + '</td>' +
        '<td style="font-size:13px;">' + (r.closedByName || '—') + '</td>' +
        '<td style="text-align:center;font-weight:600;">' + (r.totalOrders || 0) + '</td>' +
        '<td style="font-weight:700;">' + fmt(netVal) + '</td>' +
        '<td><button class="btn btn-primary btn-sm" onclick="openDailyReportDetail(\'' + safeDate + '\')"><i class="ti ti-eye" style="margin-right:4px;"></i>View</button></td>' +
        '</tr>';
    }).join('');
  }

  var _drepCurrentDate = null;  // track the date for the detail modal download button

  /** Build the HTML content used for both the detail modal and the PDF */
  function _buildDailyReportHTML(rep, orders, activityLogs, opts) {
    var fmt = function(v) { return '₱' + Number(v||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
    var netVal   = rep.netSales != null ? rep.netSales : rep.totalRevenue;
    var grossVal = rep.grossSales != null ? rep.grossSales : rep.totalRevenue;
    var closedAt = rep.closedAt ? (formatDate(rep.closedAt) + ' at ' + formatTime(rep.closedAt)) : '—';
    var isPdf    = opts && opts.pdf;
    var cardSt   = isPdf ? 'border:1px solid #e5e7eb;border-radius:6px;padding:10px 12px;text-align:center;' : 'padding:10px 14px;';
    var sectHead = function(t) { return '<div style="font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:.05em;color:#888;margin:16px 0 8px;border-bottom:1px solid #eee;padding-bottom:4px;">' + t + '</div>'; };

    // Separate orders into direct and ecommerce
    var directOrders = (orders||[]).filter(function(o){ return o.source !== 'ECOMMERCE'; });
    var ecomOrders   = (orders||[]).filter(function(o){ return o.source === 'ECOMMERCE'; });
    var directRev    = directOrders.reduce(function(s,o){ return s + Number(o.total||0); }, 0);
    var ecomRev      = ecomOrders.reduce(function(s,o){ return s + Number(o.total||0); }, 0);

    var h = '';

    // ─── SUMMARY ───
    h += '<div style="display:grid;grid-template-columns:repeat(5,1fr);gap:8px;margin-bottom:12px;">';
    h += '<div style="' + cardSt + '"><div style="font-size:10px;color:#888;">Total Orders</div><div style="font-size:20px;font-weight:700;">' + (rep.totalOrders||0) + '</div></div>';
    h += '<div style="' + cardSt + '"><div style="font-size:10px;color:#888;">Net Revenue</div><div style="font-size:16px;font-weight:700;color:#10B981;">' + fmt(netVal) + '</div></div>';
    h += '<div style="' + cardSt + '"><div style="font-size:10px;color:#888;">Items Sold</div><div style="font-size:20px;font-weight:700;">' + (rep.totalItemsSold||0) + '</div></div>';
    h += '<div style="' + cardSt + '"><div style="font-size:10px;color:#888;">Cancelled</div><div style="font-size:20px;font-weight:700;color:#EF4444;">' + (rep.totalCancelled||0) + '</div></div>';
    h += '<div style="' + cardSt + '"><div style="font-size:10px;color:#888;">Expenses</div><div style="font-size:16px;font-weight:700;color:#EF4444;">' + fmt(rep.totalExpenses) + '</div><div style="font-size:9px;color:#888;">' + (rep.expensesCount||0) + ' entries</div></div>';
    h += '</div>';

    // ─── REVENUE BREAKDOWN ───
    h += sectHead('Revenue Breakdown');
    h += '<table style="width:100%;border-collapse:collapse;font-size:12px;margin-bottom:8px;">';
    h += '<tr><td style="padding:4px 0;">Direct Transactions</td><td style="text-align:right;font-weight:600;">' + fmt(directRev) + '</td></tr>';
    h += '<tr><td style="padding:4px 0;">E-commerce Revenue</td><td style="text-align:right;font-weight:600;">' + fmt(ecomRev) + '</td></tr>';
    h += '<tr style="border-top:2px solid #C25A0A;"><td style="padding:6px 0;font-weight:700;">Grand Total</td><td style="text-align:right;font-weight:700;font-size:14px;">' + fmt(grossVal) + '</td></tr>';
    h += '</table>';

    // ─── ACCOUNTING ───
    h += sectHead('Accounting');
    h += '<table style="width:100%;border-collapse:collapse;font-size:12px;margin-bottom:8px;">';
    h += '<tr><td style="padding:4px 0;">Gross Sales</td><td style="text-align:right;font-weight:600;">' + fmt(grossVal) + '</td></tr>';
    h += '<tr><td style="padding:4px 0;">Refunds / Voids</td><td style="text-align:right;color:#EF4444;font-weight:600;">' + fmt(rep.refundsTotal) + '</td></tr>';
    h += '<tr><td style="padding:4px 0;">Adjustments</td><td style="text-align:right;color:#F59E0B;font-weight:600;">' + fmt(rep.adjustmentsTotal) + '</td></tr>';
    h += '<tr><td style="padding:4px 0;">Total Expenses</td><td style="text-align:right;color:#EF4444;font-weight:600;">' + fmt(rep.totalExpenses) + '</td></tr>';
    h += '<tr style="border-top:2px solid var(--border,#ddd);"><td style="font-weight:700;padding:6px 0;">Net Sales</td><td style="text-align:right;font-weight:700;font-size:14px;">' + fmt(netVal) + '</td></tr>';
    h += '</table>';

    // ─── SOURCE BREAKDOWN ───
    h += sectHead('Source Breakdown');
    h += '<div style="display:grid;grid-template-columns:repeat(4,1fr);gap:6px;margin-bottom:10px;">';
    ['Walk-in:' + (rep.walkInCount||0), 'Agent:' + (rep.agentCount||0),
     'E-commerce:' + (rep.ecommerceCount||0), 'Facebook:' + (rep.fbPageCount||0)].forEach(function(s){
      var parts = s.split(':');
      h += '<div style="text-align:center;background:#f8f9fa;border-radius:6px;padding:8px 4px;">' +
        '<div style="font-size:10px;color:#888;">' + parts[0] + '</div>' +
        '<div style="font-weight:700;font-size:16px;">' + parts[1] + '</div></div>';
    });
    h += '</div>';

    // ─── TOP PRODUCT ───
    if (rep.topProduct) {
      h += sectHead('Top Product');
      h += '<div style="font-size:14px;font-weight:700;margin-bottom:10px;">' + escapeHtml(rep.topProduct) +
        ' <span style="color:#888;font-size:12px;font-weight:400;">× ' + (rep.topProductQty||0) + '</span></div>';
    }

    // ─── DIRECT TRANSACTIONS TABLE ───
    if (directOrders.length > 0) {
      h += sectHead('Direct Transactions (' + directOrders.length + ')');
      h += '<table style="width:100%;border-collapse:collapse;font-size:11px;margin-bottom:10px;">';
      h += '<thead><tr style="background:#f0f0f0;"><th style="padding:5px;text-align:left;">Order ID</th><th>Customer</th><th>Payment</th><th style="text-align:right;">Total</th></tr></thead><tbody>';
      directOrders.forEach(function(o) {
        h += '<tr><td style="padding:3px 5px;border-bottom:1px solid #f0f0f0;font-family:monospace;font-size:10px;">' + (o.id||'').substring(0,16) + '</td>';
        h += '<td style="padding:3px 5px;border-bottom:1px solid #f0f0f0;">' + escapeHtml(o.customerName||'') + '</td>';
        h += '<td style="padding:3px 5px;border-bottom:1px solid #f0f0f0;">' + formatPaymentMode(o.paymentMode) + '</td>';
        h += '<td style="padding:3px 5px;border-bottom:1px solid #f0f0f0;text-align:right;font-weight:600;">' + fmt(o.total) + '</td></tr>';
      });
      h += '</tbody></table>';
    }

    // ─── ECOMMERCE TRANSACTIONS TABLE ───
    if (ecomOrders.length > 0) {
      h += sectHead('E-commerce Transactions (' + ecomOrders.length + ')');
      h += '<table style="width:100%;border-collapse:collapse;font-size:11px;margin-bottom:10px;">';
      h += '<thead><tr style="background:#f0f0f0;"><th style="padding:5px;text-align:left;">Order Ref</th><th>Platform</th><th>Customer</th><th style="text-align:right;">Total</th></tr></thead><tbody>';
      ecomOrders.forEach(function(o) {
        var ref = ecomOrderRef(o) || (o.id||'').substring(0,16);
        var platform = (o.ecommercePlatform || '').replace(/_/g,' ');
        h += '<tr><td style="padding:3px 5px;border-bottom:1px solid #f0f0f0;font-family:monospace;font-size:10px;">' + escapeHtml(ref) + '</td>';
        h += '<td style="padding:3px 5px;border-bottom:1px solid #f0f0f0;">' + escapeHtml(platform) + '</td>';
        h += '<td style="padding:3px 5px;border-bottom:1px solid #f0f0f0;">' + escapeHtml(o.customerName||'') + '</td>';
        h += '<td style="padding:3px 5px;border-bottom:1px solid #f0f0f0;text-align:right;font-weight:600;">' + fmt(o.total) + '</td></tr>';
      });
      h += '</tbody></table>';
    }

    // ─── ACTIVITY LOG ───
    if (activityLogs && activityLogs.length > 0) {
      h += sectHead('Activity Log (' + activityLogs.length + ')');
      h += '<table style="width:100%;border-collapse:collapse;font-size:10px;margin-bottom:10px;">';
      h += '<thead><tr style="background:#f0f0f0;"><th style="padding:4px;">Time</th><th>User</th><th>Action</th><th>Details</th></tr></thead><tbody>';
      activityLogs.forEach(function(log) {
        var t = log.createdAt ? formatTime(log.createdAt) : '';
        h += '<tr><td style="padding:3px 5px;border-bottom:1px solid #f0f0f0;white-space:nowrap;">' + t + '</td>';
        h += '<td style="padding:3px 5px;border-bottom:1px solid #f0f0f0;">' + escapeHtml(log.userName||'') + '</td>';
        h += '<td style="padding:3px 5px;border-bottom:1px solid #f0f0f0;font-weight:600;">' + escapeHtml(log.action||'') + '</td>';
        h += '<td style="padding:3px 5px;border-bottom:1px solid #f0f0f0;font-size:10px;color:#666;">' + escapeHtml((log.description||'').substring(0,80)) + '</td></tr>';
      });
      h += '</tbody></table>';
    }

    // ─── FOOTER ───
    h += '<div style="font-size:11px;color:#888;border-top:1px solid #ddd;padding-top:8px;margin-top:6px;">' +
      'Closed: ' + closedAt +
      (rep.notes ? ' &nbsp;|&nbsp; Notes: ' + escapeHtml(rep.notes) : '') +
      '</div>';

    return h;
  }

  /** Fetch all data needed for a daily report and return {rep, orders, logs} */
  async function _fetchDailyReportData(date) {
    var hdrs = authHeaders();
    var results = await Promise.all([
      fetch(API_BASE + '/api/reports/daily/' + date, {headers: hdrs}).then(function(r){return r.json();}),
      fetch(API_BASE + '/api/orders/today', {headers: hdrs}).then(function(r){return r.ok ? r.json() : [];}).catch(function(){return [];}),
      fetch(API_BASE + '/api/reports/activity-log/today', {headers: hdrs}).then(function(r){return r.ok ? r.json() : [];}).catch(function(){return [];})
    ]);
    return { rep: results[0], orders: results[1], logs: results[2] };
  }

  window.openDailyReportDetail = function(date) {
    _drepCurrentDate = date;
    var titleEl = $('drep-modal-title');
    var bodyEl  = $('drep-modal-body');
    if (titleEl) titleEl.textContent = 'Daily Report — ' + date;
    if (bodyEl)  bodyEl.innerHTML    = '<div style="text-align:center;color:var(--text-muted);padding:20px;">Loading…</div>';
    var overlay = $('modal-daily-report-detail');
    if (overlay) overlay.classList.add('open');

    _fetchDailyReportData(date)
      .then(function(d) {
        if (!d.rep || d.rep.message) {
          bodyEl.innerHTML = '<p style="color:var(--text-muted);text-align:center;padding:20px;">No data found for this date.</p>';
          return;
        }
        bodyEl.innerHTML = _buildDailyReportHTML(d.rep, d.orders, d.logs);
      })
      .catch(function() {
        if (bodyEl) bodyEl.innerHTML = '<p style="color:#EF4444;text-align:center;padding:20px;">Error loading report detail.</p>';
      });
  };

  /** Generate and open a printable daily report PDF for the given date (defaults to today) */
  window.downloadDailyReportPdf = function(dateStr) {
    var date = dateStr || new Date().toISOString().slice(0,10);
    showToast('Generating daily report…', 'success');

    _fetchDailyReportData(date)
      .then(function(d) {
        if (!d.rep || d.rep.message) { showToast('No report data for ' + date, 'error'); return; }
        var content = _buildDailyReportHTML(d.rep, d.orders, d.logs, {pdf:true});
        var dateLabel = new Date(date + 'T00:00:00').toLocaleDateString('en-PH',{year:'numeric',month:'long',day:'numeric'});
        var genTs = new Date().toLocaleString('en-PH',{year:'numeric',month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'});
        var logoUrl = window.location.origin + (window.location.pathname.replace(/[^/]*$/, '')) + 'assets/logo-two.png';

        var html = '<!DOCTYPE html><html><head><meta charset="UTF-8">' +
          '<title>Daily Report ' + date + '</title>' +
          '<script src="https://cdnjs.cloudflare.com/ajax/libs/html2canvas/1.4.1/html2canvas.min.js"' +
            ' onload="var b=document.getElementById(\'dr-save-png\');if(b){b.disabled=false;b.title=\'Save as PNG\';}"><\/script>' +
          '<style>' +
            '@page{size:letter;margin:12mm 15mm;}' +
            'body{font-family:Arial,sans-serif;font-size:12px;margin:0;padding:16px;color:#111;}' +
            '#dr-actions{position:fixed;top:10px;right:10px;z-index:9999;display:flex;gap:8px;}' +
            '#dr-actions button{padding:6px 14px;border-radius:6px;border:1px solid #ccc;cursor:pointer;font-size:12px;background:#fff;}' +
            '#dr-actions .btn-pdf{background:#C25A0A;color:#fff;border-color:#C25A0A;}' +
            'table{width:100%;border-collapse:collapse;}' +
            '.hdr{display:flex;justify-content:space-between;align-items:flex-start;border-bottom:2px solid #C25A0A;padding-bottom:8px;margin-bottom:12px;}' +
            '@media print{#dr-actions{display:none!important;}}' +
          '</style></head><body>' +
          '<div id="dr-actions">' +
            '<button class="btn-pdf" onclick="window.print()">&#128438; Print / Save PDF</button>' +
            '<button id="dr-save-png" onclick="savePNG()" disabled title="Loading export library…" style="opacity:0.5;cursor:not-allowed;">&#128247; Save as PNG</button>' +
          '</div>' +
          '<div class="hdr">' +
            '<img src="' + logoUrl + '" style="height:50px;" onerror="this.style.display=\'none\'" />' +
            '<div style="text-align:right;">' +
              '<div style="font-size:20px;font-weight:700;color:#C25A0A;">Daily Sales Report</div>' +
              '<div style="font-size:12px;color:#555;">' + dateLabel + '</div>' +
            '</div>' +
          '</div>' +
          content +
          '<div style="margin-top:12px;border-top:1px solid #ddd;padding-top:6px;font-size:10px;color:#aaa;display:flex;justify-content:space-between;">' +
            '<span>Generated: ' + genTs + '</span>' +
            '<span>INTERNAL DOCUMENT &mdash; CONFIDENTIAL</span>' +
          '</div>' +
          '<script>' +
            'function savePNG(){' +
              'if(!window.html2canvas){alert("Export library not available. Use Print / Save PDF instead.");return;}' +
              'var a=document.getElementById("dr-actions");if(a)a.style.display="none";' +
              'window.html2canvas(document.body,{scale:2,useCORS:true,allowTaint:true}).then(function(c){' +
                'var l=document.createElement("a");l.download="daily-report-' + date + '.png";' +
                'l.href=c.toDataURL("image/png");' +
                'document.body.appendChild(l);l.click();document.body.removeChild(l);' +
                'if(a)a.style.display="flex";' +
              '}).catch(function(){if(a)a.style.display="flex";alert("PNG export failed. Try Print / Save PDF instead.");});' +
            '}' +
          '<\/script>' +
          '</body></html>';

        var w = window.open('', '_blank', 'width=900,height=780');
        if (w) { w.document.write(html); w.document.close(); w.focus(); }
      })
      .catch(function(err) { showToast('Error generating report: ' + (err.message||err), 'error'); });
  };

  // Called by the Refresh button — loads all report sections with the same month
  window.loadAllReports = function () {
    loadInsightsSummary();
    loadAccountingSummary();
    loadSourceBreakdown();
    loadEcommerceBreakdown();
    loadTopAgents();
    loadTopDates();
    loadPizzaSummary();
    loadNonPizzaSummary();
    loadDailyOrderSummary();
    loadHotSelling();
    loadDeliveryFees();
    loadExpenseBreakdown();
    loadRepPayables();
    loadRepCollections();
  };

  function loadRepPayables() {
    var tb      = $('rep-payables-tbody');
    var summary = $('rep-payables-summary');
    if (!tb) return;
    tb.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-muted);padding:16px;">Loading…</td></tr>';
    var fmt = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
    fetch(API_BASE + '/api/payables', { headers: authHeaders() })
      .then(function(res){ return res.json(); })
      .then(function(records){
        var pending = (records || []).filter(function(r){ return r.status === 'PENDING'; });
        if (!pending.length) {
          tb.innerHTML = '<tr><td colspan="5" style="text-align:center;color:#10B981;padding:16px;">No outstanding payables</td></tr>';
          if (summary) summary.textContent = 'No outstanding payables';
          return;
        }
        var total = pending.reduce(function(s,r){ return s + Number(r.totalAmount||0); }, 0);
        if (summary) summary.textContent = pending.length + ' pending · ' + fmt(total) + ' total';
        tb.innerHTML = pending.map(function(r){
          var created = r.createdAt ? new Date(r.createdAt) : null;
          var days = created ? Math.floor((Date.now() - created.getTime()) / 86400000) : '—';
          return '<tr>'
            + '<td><span class="product-code" style="font-size:11px;">' + escapeHtml(r.receiptNumber||'—') + '</span></td>'
            + '<td>' + escapeHtml(r.supplierName||'—') + '</td>'
            + '<td style="text-align:right;font-weight:600;">' + fmt(r.totalAmount) + '</td>'
            + '<td style="font-size:11px;color:var(--text-muted);">' + (created ? created.toLocaleDateString('en-PH') : '—') + '</td>'
            + '<td style="text-align:center;font-weight:600;color:' + (typeof days==='number'&&days>=7?'#EF4444':'#F59E0B') + ';">'
            + (typeof days==='number' ? days + 'd' : '—') + '</td>'
            + '</tr>';
        }).join('');
      })
      .catch(function(){ tb.innerHTML = '<tr><td colspan="5" style="text-align:center;color:#EF4444;padding:16px;">Failed to load payables</td></tr>'; });
  }

  function loadRepCollections() {
    var tb      = $('rep-collections-tbody');
    var summary = $('rep-collections-summary');
    if (!tb) return;
    tb.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);padding:16px;">Loading…</td></tr>';
    var fmt = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
    fetch(API_BASE + '/api/orders/collections', { headers: authHeaders() })
      .then(function(res){ return res.json(); })
      .then(function(orders){
        if (!orders || !orders.length) {
          tb.innerHTML = '<tr><td colspan="6" style="text-align:center;color:#10B981;padding:16px;">No pending collections</td></tr>';
          if (summary) summary.textContent = 'No pending collections';
          return;
        }
        var total = orders.reduce(function(s,o){ return s + Number(o.total||0); }, 0);
        if (summary) summary.textContent = orders.length + ' pending · ' + fmt(total) + ' total';
        var payMap = { CASH:'Cash', COD:'COD', GCASH:'GCash', PAYMAYA:'PayMaya',
                       BANK_TRANSFER:'Bank Transfer', BANK_DEPOSIT:'Bank Deposit', ONLINE:'Online' };
        tb.innerHTML = orders.map(function(o){
          var created = o.createdAt ? new Date(o.createdAt) : null;
          var dateStr = created ? created.toLocaleDateString('en-PH',{year:'numeric',month:'short',day:'numeric'}) : '—';
          var days = created ? Math.floor((Date.now() - created.getTime()) / 86400000) : '—';
          return '<tr>'
            + '<td style="font-size:12px;">' + dateStr + '</td>'
            + '<td><span class="product-code" style="font-size:11px;">' + escapeHtml(ecomOrderRef(o)||o.id||'—') + '</span></td>'
            + '<td>' + escapeHtml(o.customerName||'—') + '</td>'
            + '<td>' + (payMap[o.paymentMode] || o.paymentMode || '—') + '</td>'
            + '<td style="text-align:right;font-weight:600;">' + fmt(o.total) + '</td>'
            + '<td style="text-align:center;font-weight:600;color:' + (typeof days==='number'&&days>=3?'#EF4444':'#F59E0B') + ';">'
            + (typeof days==='number' ? days + 'd' : '—') + '</td>'
            + '</tr>';
        }).join('');
      })
      .catch(function(){ tb.innerHTML = '<tr><td colspan="6" style="text-align:center;color:#EF4444;padding:16px;">Failed to load collections</td></tr>'; });
  }

  function loadInsightsSummary() {
    const picker = $('rep-month-picker');
    const month  = picker ? picker.value : '';
    const loading = $('rep-loading');
    if (loading) loading.style.display = 'inline';

    fetch(API_BASE + '/api/reports/insights-summary' + (month ? '?month=' + month : ''), { headers: authHeaders() })
      .then(function (res) { return res.json(); })
      .then(function (data) {
        if (loading) loading.style.display = 'none';
        renderInsightsSummary(data);
      })
      .catch(function (err) {
        if (loading) loading.style.display = 'none';
        showToast('Failed to load insights: ' + (err.message || err), 'error');
      });
  }

  function renderInsightsSummary(data) {
    // Helper: format MoM comparison arrow + text
    function momText(curr, prev, label) {
      const c = Number(curr) || 0, p = Number(prev) || 0;
      if (p === 0) return label;
      const diff = c - p;
      const pct  = ((diff / p) * 100).toFixed(1);
      const arrow = diff >= 0 ? '▲' : '▼';
      const color = diff >= 0 ? '#10B981' : '#EF4444';
      return '<span style="color:' + color + ';">' + arrow + ' ' + Math.abs(pct) + '% vs prev month</span>';
    }

    // Summary cards
    setText('rep-total-orders',   data.totalOrders  || 0);
    setText('rep-total-revenue',  '₱' + Number(data.totalRevenue || 0).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 }));
    setText('rep-items-sold',     (data.totalItemsSold || 0).toLocaleString());
    setText('rep-total-expenses', '₱' + Number(data.totalExpenses || 0).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 }));

    // MoM comparison sub-texts
    const ordersSub   = $('rep-orders-sub');
    const revenueSub  = $('rep-revenue-sub');
    const expensesSub = $('rep-expenses-sub');
    if (ordersSub)   ordersSub.innerHTML  = momText(data.totalOrders,  data.prevMonthOrders,  'Non-cancelled orders');
    if (revenueSub)  revenueSub.innerHTML = momText(data.totalRevenue, data.prevMonthRevenue, 'All payment modes');
    if (expensesSub) expensesSub.textContent = 'Recorded expense entries';

    // Daily breakdown chart
    const daily  = data.dailyBreakdown || [];
    const labels = daily.map(function (d) { return formatDate(d.date); });
    const values = daily.map(function (d) { return parseFloat(d.revenue) || 0; });
    renderDailyRevenueChart(labels, values);

    // Daily breakdown table is populated by loadDailyOrderSummary()

    // Top 5 products table
    const top = data.topProducts || [];
    const ptbody = $('rep-top-products-tbody');
    if (ptbody) {
      if (top.length === 0) {
        ptbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-muted);padding:20px;">No sales data this month</td></tr>';
      } else {
        const medals = ['🥇','🥈','🥉','4','5','6','7','8','9','10'];
        ptbody.innerHTML = top.map(function (p, i) {
          return '<tr>' +
            '<td style="font-size:16px;text-align:center;">' + (medals[i] || p.rank) + '</td>' +
            '<td style="font-weight:600;">' + escapeHtml(p.name) + '</td>' +
            '<td style="text-align:right;">' + (p.qty || 0).toLocaleString() + '</td>' +
            '<td style="text-align:right;">' + '₱' + Number(p.revenue || 0).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + '</td>' +
            '<td style="text-align:right;color:var(--accent);">' + (p.pct || 0) + '%</td>' +
            '</tr>';
        }).join('');
      }
    }

    // Sales vs Expenses dual-line chart
    const salesDaily    = data.dailyBreakdown || [];
    const expensesDaily = data.dailyExpenses   || [];

    // Build a unified date list
    const allDates = Array.from(new Set(
      salesDaily.map(function(d){ return d.date; })
        .concat(expensesDaily.map(function(d){ return d.date; }))
    )).sort();

    const expMap = {};
    expensesDaily.forEach(function(e){ expMap[e.date] = parseFloat(e.amount) || 0; });

    const chartLabels    = allDates.map(function(d){ return formatDate(d); });
    const salesValues    = allDates.map(function(d){
      const found = salesDaily.find(function(s){ return s.date === d; });
      return found ? (parseFloat(found.revenue) || 0) : 0;
    });
    const expenseValues  = allDates.map(function(d){ return expMap[d] || 0; });

    renderSalesVsExpChart(chartLabels, salesValues, expenseValues);

    // Month-over-month comparison cards
    var fmtCurr = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
    setText('rep-mom-curr-orders', Number(data.totalOrders||0).toLocaleString());
    setText('rep-mom-curr-rev',    fmtCurr(data.totalRevenue));
    if (data.prevMonth) {
      var momPrevOrders = $('rep-mom-prev-orders');
      var momPrevRev    = $('rep-mom-prev-rev');
      if (momPrevOrders) momPrevOrders.textContent = 'Prev (' + data.prevMonth + '): ' + Number(data.prevMonthOrders||0).toLocaleString();
      if (momPrevRev)    momPrevRev.textContent    = 'Prev: ' + fmtCurr(data.prevMonthRevenue);
      var change    = Number(data.totalRevenue||0) - Number(data.prevMonthRevenue||0);
      var changePct = data.prevMonthRevenue > 0
        ? ((change / data.prevMonthRevenue) * 100).toFixed(1) : null;
      var changeEl = $('rep-mom-change');
      if (changeEl) {
        changeEl.textContent = (change >= 0 ? '+' : '') + fmtCurr(change);
        changeEl.style.color = change >= 0 ? '#10B981' : '#EF4444';
      }
      var pctEl = $('rep-mom-change-pct');
      if (pctEl && changePct != null) {
        pctEl.textContent = (change >= 0 ? '▲' : '▼') + ' ' + Math.abs(changePct) + '% vs ' + data.prevMonth;
        pctEl.style.color = change >= 0 ? '#10B981' : '#EF4444';
      }
    }
  }

  function renderSalesVsExpChart(labels, salesData, expData) {
    if (typeof Chart === 'undefined') {
      setTimeout(function(){ renderSalesVsExpChart(labels, salesData, expData); }, 100);
      return;
    }
    const canvas = $('chart-sales-vs-exp');
    if (!canvas) return;
    if (appState.chartSalesVsExp) { appState.chartSalesVsExp.destroy(); }

    const isDark    = document.body.dataset.theme === 'dark';
    const tickColor = isDark ? '#6B5740' : '#9C8B70';
    const gridColor = isDark ? 'rgba(212,134,10,0.08)' : 'rgba(74,44,23,0.07)';
    const tooltipBg = isDark ? '#2A1A0A' : '#FFFFFF';
    const tipText   = isDark ? '#F5ECD8' : '#1A1208';
    const tipBorder = isDark ? 'rgba(212,134,10,0.25)' : 'rgba(74,44,23,0.15)';

    appState.chartSalesVsExp = new Chart(canvas, {
      type: 'line',
      data: {
        labels: labels,
        datasets: [
          {
            label: 'Sales Revenue',
            data: salesData,
            borderColor: '#D4860A',
            backgroundColor: 'rgba(212,134,10,0.08)',
            borderWidth: 2,
            pointRadius: 3,
            tension: 0.3,
            fill: true
          },
          {
            label: 'Expenses',
            data: expData,
            borderColor: '#EF4444',
            backgroundColor: 'rgba(239,68,68,0.08)',
            borderWidth: 2,
            pointRadius: 3,
            tension: 0.3,
            fill: true
          }
        ]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { labels: { color: tickColor, font: { size: 11 } } },
          tooltip: {
            backgroundColor: tooltipBg,
            titleColor: tipText,
            bodyColor: tickColor,
            borderColor: tipBorder,
            borderWidth: 1,
            cornerRadius: 8,
            padding: 10,
            callbacks: {
              label: function(ctx) {
                return ctx.dataset.label + ': ₱' + Number(ctx.raw).toLocaleString('en-PH', { minimumFractionDigits: 2 });
              }
            }
          }
        },
        scales: {
          x: {
            grid: { display: false },
            border: { display: false },
            ticks: { color: tickColor, font: { size: 10 }, maxRotation: 0, autoSkip: true, maxTicksLimit: 12 }
          },
          y: {
            grid: { color: gridColor, drawBorder: false },
            border: { display: false },
            ticks: {
              color: tickColor,
              font: { size: 10 },
              callback: function(v) { return '₱' + (v >= 1000 ? (v / 1000).toFixed(0) + 'k' : v); }
            }
          }
        }
      }
    });
  }

  function renderDailyRevenueChart(labels, values) {
    if (typeof Chart === 'undefined') {
      setTimeout(function () { renderDailyRevenueChart(labels, values); }, 100);
      return;
    }
    const canvas = $('chart-daily-revenue');
    if (!canvas) return;
    if (appState.chartDailyRevenue) { appState.chartDailyRevenue.destroy(); }

    const isDark    = document.body.dataset.theme === 'dark';
    const gridColor = isDark ? 'rgba(212,134,10,0.08)' : 'rgba(74,44,23,0.07)';
    const tickColor = isDark ? '#6B5740'               : '#9C8B70';
    const tooltipBg = isDark ? '#2A1A0A'               : '#FFFFFF';
    const tipText   = isDark ? '#F5ECD8'               : '#1A1208';
    const tipBorder = isDark ? 'rgba(212,134,10,0.25)' : 'rgba(74,44,23,0.15)';

    const barColors = values.map(function(v, i) {
      return i === values.length - 1
        ? '#D4860A'
        : (isDark ? 'rgba(212,134,10,0.28)' : 'rgba(212,134,10,0.20)');
    });
    const hoverColors = values.map(function(v, i) {
      return i === values.length - 1
        ? '#F0A830'
        : (isDark ? 'rgba(212,134,10,0.48)' : 'rgba(212,134,10,0.38)');
    });

    appState.chartDailyRevenue = new Chart(canvas, {
      type: 'bar',
      data: {
        labels: labels,
        datasets: [{
          label:                  'Revenue',
          data:                   values,
          backgroundColor:        barColors,
          hoverBackgroundColor:   hoverColors,
          borderRadius:           5,
          borderSkipped:          false
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: tooltipBg,
            titleColor:      tipText,
            bodyColor:       tickColor,
            borderColor:     tipBorder,
            borderWidth:     1,
            cornerRadius:    8,
            padding:         10,
            displayColors:   false,
            callbacks: {
              label: function(ctx) {
                return '₱' + Number(ctx.raw).toLocaleString('en-PH', { minimumFractionDigits: 2 });
              }
            }
          }
        },
        scales: {
          x: {
            grid:   { display: false },
            border: { display: false },
            ticks:  { color: tickColor, font: { size: 10 }, maxRotation: 0, autoSkip: true, maxTicksLimit: 12 }
          },
          y: {
            grid:   { color: gridColor, drawBorder: false },
            border: { display: false },
            ticks:  {
              color: tickColor,
              font:  { size: 10 },
              callback: function(v) { return '₱' + (v >= 1000 ? (v / 1000).toFixed(0) + 'k' : v); }
            }
          }
        }
      }
    });
  }

  function setText(id, val) {
    const el = $(id);
    if (el) el.textContent = val;
  }

  // ================================================================
  // Accounting Summary (Transaction Ledger)
  // ================================================================
  function loadAccountingSummary() {
    const picker = $('rep-month-picker');
    const month  = picker ? picker.value : '';

    fetch(API_BASE + '/api/reports/accounting-summary' + (month ? '?month=' + month : ''), { headers: authHeaders() })
      .then(function (res) { return res.json(); })
      .then(function (data) { renderAccountingSummary(data); })
      .catch(function (err) {
        showToast('Failed to load accounting summary: ' + (err.message || err), 'error');
      });
  }

  function renderAccountingSummary(data) {
    const fmt = function (n) {
      const v = parseFloat(n) || 0;
      return (v < 0 ? '-₱' : '₱') + Math.abs(v).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    };
    setText('acc-gross-sales',       fmt(data.grossSales));
    setText('acc-refunds-total',     fmt(data.refundsTotal));
    setText('acc-adjustments-total', fmt(data.adjustmentsTotal));
    setText('acc-net-sales',         fmt(data.netSales));

    const daily  = data.dailyBreakdown || [];
    const tbody  = $('acc-daily-tbody');
    if (!tbody) return;
    if (daily.length === 0) {
      tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-muted);padding:20px;">No transactions this month</td></tr>';
      return;
    }

    // Running net total row at the bottom
    let runNet = 0;
    tbody.innerHTML = daily.map(function (row) {
      const gross = parseFloat(row.grossSales)  || 0;
      const refs  = parseFloat(row.refunds)     || 0;
      const adj   = parseFloat(row.adjustments) || 0;
      const net   = parseFloat(row.netSales)    || 0;
      runNet += net;
      const netColor = net >= 0 ? '#10B981' : '#EF4444';
      return '<tr>'
        + '<td>' + escapeHtml(formatDate(row.date)) + '</td>'
        + '<td style="text-align:right;color:#10B981;">₱' + gross.toLocaleString('en-PH', { minimumFractionDigits: 2 }) + '</td>'
        + '<td style="text-align:right;color:#EF4444;">' + fmt(refs) + '</td>'
        + '<td style="text-align:right;color:#F59E0B;">' + fmt(adj)  + '</td>'
        + '<td style="text-align:right;font-weight:600;color:' + netColor + ';">' + fmt(net) + '</td>'
        + '</tr>';
    }).join('');
  }

  // ================================================================
  // Reports — Source Breakdown
  // ================================================================
  function loadSourceBreakdown() {
    const month = ($('rep-month-picker') || {}).value || '';
    fetch(API_BASE + '/api/reports/source-breakdown' + (month ? '?month=' + month : ''), { headers: authHeaders() })
      .then(function(res){ return res.json(); })
      .then(function(data){ renderSourceBreakdown(data); })
      .catch(function(err){ console.warn('source-breakdown failed:', err); });
  }

  function renderSourceBreakdown(data) {
    const list   = data.breakdown || [];
    const tbody  = $('rep-source-tbody');
    const fmt    = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
    if (tbody) {
      if (!list.length) {
        tbody.innerHTML = '<tr><td colspan="4" style="text-align:center;color:var(--text-muted);padding:20px;">No data</td></tr>';
      } else {
        tbody.innerHTML = list.map(function(r){
          return '<tr>' +
            '<td style="font-weight:600;">' + escapeHtml(formatSource(r.source) || '—') + '</td>' +
            '<td style="text-align:right;">' + (r.orderCount || 0) + '</td>' +
            '<td style="text-align:right;">' + fmt(r.revenue) + '</td>' +
            '<td style="text-align:right;color:var(--accent);">' + (r.pct || 0) + '%</td>' +
            '</tr>';
        }).join('');
      }
    }
    // Donut chart
    const sourceColors = ['#D4860A','#10B981','#3B82F6','#7C3AED','#F59E0B','#EF4444','#EC4899'];
    const labels = list.map(function(r){ return formatSource(r.source) || 'Unknown'; });
    const values = list.map(function(r){ return parseFloat(r.revenue) || 0; });
    renderSourceDonutChart(labels, values, sourceColors);
  }

  function renderSourceDonutChart(labels, values, colors) {
    if (typeof Chart === 'undefined') { setTimeout(function(){ renderSourceDonutChart(labels, values, colors); }, 100); return; }
    const canvas = $('chart-source-donut');
    if (!canvas) return;
    if (appState.chartSourceDonut) { appState.chartSourceDonut.destroy(); }
    const isDark = document.body.dataset.theme === 'dark';
    const tickColor = isDark ? '#6B5740' : '#9C8B70';
    const tooltipBg = isDark ? '#2A1A0A' : '#FFFFFF';
    const tipText   = isDark ? '#F5ECD8' : '#1A1208';
    appState.chartSourceDonut = new Chart(canvas, {
      type: 'doughnut',
      data: { labels: labels, datasets: [{ data: values, backgroundColor: colors.slice(0, values.length), borderWidth: 2 }] },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: tooltipBg, titleColor: tipText, bodyColor: tickColor,
            callbacks: {
              label: function(ctx) {
                return ctx.label + ': ₱' + Number(ctx.raw).toLocaleString('en-PH', { minimumFractionDigits: 2 });
              }
            }
          }
        }
      }
    });
  }

  // ================================================================
  // Reports — Top Agents
  // ================================================================
  function loadTopAgents() {
    const month = ($('rep-month-picker') || {}).value || '';
    fetch(API_BASE + '/api/reports/top-agents' + (month ? '?month=' + month : ''), { headers: authHeaders() })
      .then(function(res){ return res.json(); })
      .then(function(data){
        const list  = data.agents || [];
        const tbody = $('rep-top-agents-tbody');
        const fmt   = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
        if (!tbody) return;
        if (!list.length) {
          tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-muted);padding:20px;">No agent/reseller orders this month</td></tr>';
        } else {
          const medals = ['🥇','🥈','🥉'];
          tbody.innerHTML = list.map(function(r, i){
            return '<tr>' +
              '<td style="text-align:center;font-size:' + (i < 3 ? '16' : '13') + 'px;">' + (medals[i] || r.rank) + '</td>' +
              '<td style="font-weight:600;">' + escapeHtml(r.agentName || '—') + '</td>' +
              '<td style="font-size:11px;"><span style="background:#FAD16A;color:#2C1A0E;padding:2px 6px;border-radius:4px;">' + escapeHtml(r.source || '') + '</span></td>' +
              '<td style="text-align:right;">' + (r.orderCount || 0) + '</td>' +
              '<td style="text-align:right;font-weight:600;color:var(--accent);">' + fmt(r.revenue) + '</td>' +
              '</tr>';
          }).join('');
        }
      })
      .catch(function(err){ console.warn('top-agents failed:', err); });
  }

  // ================================================================
  // Reports — Top Dates
  // ================================================================
  function loadTopDates() {
    const month = ($('rep-month-picker') || {}).value || '';
    fetch(API_BASE + '/api/reports/top-dates' + (month ? '?month=' + month : ''), { headers: authHeaders() })
      .then(function(res){ return res.json(); })
      .then(function(data){
        const list  = data.dates || [];
        const tbody = $('rep-top-dates-tbody');
        const fmt   = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
        if (!tbody) return;
        if (!list.length) {
          tbody.innerHTML = '<tr><td colspan="4" style="text-align:center;color:var(--text-muted);padding:20px;">No data</td></tr>';
        } else {
          const medals = ['🥇','🥈','🥉'];
          tbody.innerHTML = list.map(function(r, i){
            return '<tr>' +
              '<td style="text-align:center;font-size:16px;">' + (medals[i] || r.rank) + '</td>' +
              '<td style="font-weight:600;">' + escapeHtml(formatDate(r.date)) + '</td>' +
              '<td style="text-align:right;">' + (r.orderCount || 0) + '</td>' +
              '<td style="text-align:right;font-weight:600;color:var(--accent);">' + fmt(r.revenue) + '</td>' +
              '</tr>';
          }).join('');
        }
      })
      .catch(function(err){ console.warn('top-dates failed:', err); });
  }

  // ================================================================
  // Reports — Pizza Box Summary
  // ================================================================
  function loadPizzaSummary() {
    const month = ($('rep-month-picker') || {}).value || '';
    fetch(API_BASE + '/api/reports/pizza-summary' + (month ? '?month=' + month : ''), { headers: authHeaders() })
      .then(function(res){ return res.json(); })
      .then(function(data){
        const fmt = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };

        // Channel grid cells
        var dQtyEl = $('rep-pizza-direct-qty');
        var dRevEl = $('rep-pizza-direct-rev');
        var eQtyEl = $('rep-pizza-ecom-qty');
        var eRevEl = $('rep-pizza-ecom-rev');
        var tQtyEl = $('rep-pizza-total-qty');
        var tRevEl = $('rep-pizza-total-rev');
        if (dQtyEl) dQtyEl.textContent = (data.directQty || 0).toLocaleString() + ' pcs';
        if (dRevEl) dRevEl.textContent = fmt(data.directRevenue);
        if (eQtyEl) eQtyEl.textContent = (data.ecomQty || 0).toLocaleString() + ' pcs';
        if (eRevEl) eRevEl.textContent = fmt(data.ecomRevenue);
        if (tQtyEl) tQtyEl.textContent = (data.totalQty || 0).toLocaleString() + ' pcs';
        if (tRevEl) tRevEl.textContent = fmt(data.totalRevenue);

        // SKU breakdown table
        const list  = data.top5 || [];
        const tbody = $('rep-pizza-tbody');
        if (!tbody) return;
        if (!list.length) {
          tbody.innerHTML = '<tr><td colspan="3" style="text-align:center;color:var(--text-muted);padding:20px;">No pizza box orders this month</td></tr>';
        } else {
          tbody.innerHTML = list.map(function(r){
            return '<tr>' +
              '<td>' + escapeHtml(r.productName || '—') + '</td>' +
              '<td style="text-align:right;font-weight:600;">' + (r.qty || 0).toLocaleString() + '</td>' +
              '<td style="text-align:right;">' + fmt(r.revenue) + '</td>' +
              '</tr>';
          }).join('');
        }
      })
      .catch(function(err){ console.warn('pizza-summary failed:', err); });
  }

  // ================================================================
  // Reports — Non-Pizza Items Summary
  // ================================================================
  // Cached data for the modal (populated by loadNonPizzaSummary)
  var _nonPizzaProducts = [];

  function loadNonPizzaSummary() {
    const month = ($('rep-month-picker') || {}).value || '';
    fetch(API_BASE + '/api/reports/non-pizza-summary' + (month ? '?month=' + month : ''), { headers: authHeaders() })
      .then(function(res){ return res.json(); })
      .then(function(data){
        const fmt = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };

        // Cache for modal
        _nonPizzaProducts = data.topProducts || [];

        // Channel grid cells
        var dQtyEl = $('rep-nonpizza-direct-qty');
        var dRevEl = $('rep-nonpizza-direct-rev');
        var eQtyEl = $('rep-nonpizza-ecom-qty');
        var eRevEl = $('rep-nonpizza-ecom-rev');
        var tQtyEl = $('rep-nonpizza-total-qty');
        var tRevEl = $('rep-nonpizza-total-rev');
        if (dQtyEl) dQtyEl.textContent = (data.directQty || 0).toLocaleString() + ' pcs';
        if (dRevEl) dRevEl.textContent = fmt(data.directRevenue);
        if (eQtyEl) eQtyEl.textContent = (data.ecomQty || 0).toLocaleString() + ' pcs';
        if (eRevEl) eRevEl.textContent = fmt(data.ecomRevenue);
        if (tQtyEl) tQtyEl.textContent = (data.totalQty || 0).toLocaleString() + ' pcs';
        if (tRevEl) tRevEl.textContent = fmt(data.totalRevenue);
      })
      .catch(function(err){ console.warn('non-pizza-summary failed:', err); });
  }

  window.openNonPizzaModal = function() {
    const tbody = $('nonpizza-modal-tbody');
    const fmt   = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
    if (tbody) {
      if (!_nonPizzaProducts.length) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);padding:20px;">No data — load the monthly report first</td></tr>';
      } else {
        tbody.innerHTML = _nonPizzaProducts.map(function(r, i){
          return '<tr>' +
            '<td style="text-align:center;color:var(--text-muted);font-weight:700;">' + (i + 1) + '</td>' +
            '<td style="font-weight:600;">' + escapeHtml(r.productName || '—') + '</td>' +
            '<td style="text-align:right;color:#F59E0B;">' + (r.directQty || 0).toLocaleString() + '</td>' +
            '<td style="text-align:right;color:#8B5CF6;">' + (r.ecomQty || 0).toLocaleString() + '</td>' +
            '<td style="text-align:right;font-weight:600;">' + (r.totalQty || 0).toLocaleString() + '</td>' +
            '<td style="text-align:right;">' + fmt(r.revenue) + '</td>' +
            '</tr>';
        }).join('');
      }
    }
    $('modal-nonpizza-detail').classList.add('open');
  };

  // ================================================================
  // Reports — Daily Order Summary (6-column table)
  // ================================================================
  function loadDailyOrderSummary() {
    const month = ($('rep-month-picker') || {}).value || '';
    fetch(API_BASE + '/api/reports/daily-order-summary' + (month ? '?month=' + month : ''), { headers: authHeaders() })
      .then(function(res){ return res.json(); })
      .then(function(data){
        const days  = data.days || [];
        const tbody = $('rep-daily-tbody');
        const fmt   = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
        if (!tbody) return;
        if (!days.length) {
          tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);padding:20px;">No orders this month</td></tr>';
          return;
        }
        tbody.innerHTML = days.map(function(row){
          return '<tr>' +
            '<td style="font-weight:600;">' + escapeHtml(formatDate(row.date)) + '</td>' +
            '<td style="text-align:right;color:#F59E0B;">' + (row.directOrders || 0) + '</td>' +
            '<td style="text-align:right;color:#8B5CF6;">' + (row.ecomOrders || 0) + '</td>' +
            '<td style="text-align:right;font-weight:600;">' + (row.totalOrders || 0) + '</td>' +
            '<td style="text-align:right;">' + (row.pizzaBoxQty || 0).toLocaleString() + '</td>' +
            '<td style="text-align:right;color:var(--accent);font-weight:600;">' + fmt(row.revenue) + '</td>' +
            '</tr>';
        }).join('');
      })
      .catch(function(err){ console.warn('daily-order-summary failed:', err); });
  }

  // ================================================================
  // Reports — Hot & Selling Items
  // ================================================================
  function loadHotSelling() {
    const month = ($('rep-month-picker') || {}).value || '';
    fetch(API_BASE + '/api/reports/hot-selling' + (month ? '?month=' + month : ''), { headers: authHeaders() })
      .then(function(res){ return res.json(); })
      .then(function(data){
        const list  = data.items || [];
        const tbody = $('rep-hot-selling-tbody');
        const fmt   = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
        if (!tbody) return;
        if (!list.length) {
          tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-muted);padding:20px;">No HOT/SELLING tagged items sold this month</td></tr>';
        } else {
          tbody.innerHTML = list.map(function(r){
            const tagColor = r.sellingTag === 'HOT' ? '#EF4444' : '#10B981';
            return '<tr>' +
              '<td style="text-align:center;font-weight:700;color:var(--text-muted);">' + (r.rank || '') + '</td>' +
              '<td style="font-weight:600;">' + escapeHtml(r.productName || '—') + '</td>' +
              '<td><span style="background:' + tagColor + ';color:#fff;padding:2px 7px;border-radius:4px;font-size:11px;font-weight:700;">' + escapeHtml(r.sellingTag || '') + '</span></td>' +
              '<td style="text-align:right;font-weight:600;">' + (r.qty || 0).toLocaleString() + '</td>' +
              '<td style="text-align:right;">' + fmt(r.revenue) + '</td>' +
              '</tr>';
          }).join('');
        }
      })
      .catch(function(err){ console.warn('hot-selling failed:', err); });
  }

  // ================================================================
  // Reports — Delivery Fees
  // ================================================================
  function loadDeliveryFees() {
    const month = ($('rep-month-picker') || {}).value || '';
    fetch(API_BASE + '/api/reports/delivery-fees' + (month ? '?month=' + month : ''), { headers: authHeaders() })
      .then(function(res){ return res.json(); })
      .then(function(data){
        const totalEl = $('rep-delivery-fees-total');
        const fmt     = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
        if (totalEl) totalEl.textContent = 'Total collected: ' + fmt(data.totalFees) + ' (' + (data.orderCount || 0) + ' orders)';
        const list  = data.orders || [];
        const tbody = $('rep-delivery-fees-tbody');
        if (!tbody) return;
        if (!list.length) {
          tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);padding:20px;">No delivery fee orders this month</td></tr>';
        } else {
          tbody.innerHTML = list.map(function(r){
            return '<tr>' +
              '<td style="font-family:monospace;font-size:12px;">' + escapeHtml(r.orderId || '') + '</td>' +
              '<td>' + escapeHtml(r.customerName || '—') + '</td>' +
              '<td>' + escapeHtml(r.source || '') + '</td>' +
              '<td>' + escapeHtml(formatDate(r.date)) + '</td>' +
              '<td style="text-align:right;color:var(--accent);font-weight:600;">' + fmt(r.deliveryFee) + '</td>' +
              '<td style="text-align:right;">' + fmt(r.orderTotal) + '</td>' +
              '</tr>';
          }).join('');
        }
      })
      .catch(function(err){ console.warn('delivery-fees failed:', err); });
  }

  // ================================================================
  // Reports — Expense Breakdown
  // ================================================================
  function loadExpenseBreakdown() {
    const month = ($('rep-month-picker') || {}).value || '';
    fetch(API_BASE + '/api/reports/expense-breakdown' + (month ? '?month=' + month : ''), { headers: authHeaders() })
      .then(function(res){ return res.json(); })
      .then(function(data){
        const grandEl = $('rep-expense-grand-total');
        const fmt     = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
        if (grandEl) grandEl.textContent = 'Total: ' + fmt(data.grandTotal);
        const list  = data.breakdown || [];
        const tbody = $('rep-expense-tbody');
        if (!tbody) return;
        if (!list.length) {
          tbody.innerHTML = '<tr><td colspan="4" style="text-align:center;color:var(--text-muted);padding:20px;">No expenses recorded this month</td></tr>';
        } else {
          tbody.innerHTML = list.map(function(r){
            return '<tr>' +
              '<td style="font-weight:600;">' + escapeHtml(r.description || '—') + '</td>' +
              '<td style="text-align:right;color:#EF4444;font-weight:600;">' + fmt(r.totalAmount) + '</td>' +
              '<td style="text-align:right;color:var(--text-muted);">' + (r.count || 0) + '</td>' +
              '<td style="text-align:right;color:var(--accent);">' + (r.pct || 0) + '%</td>' +
              '</tr>';
          }).join('');
        }
      })
      .catch(function(err){ console.warn('expense-breakdown failed:', err); });
  }

  // ================================================================
  // Reports — E-commerce Breakdown
  // ================================================================
  function loadEcommerceBreakdown() {
    const month = ($('rep-month-picker') || {}).value || '';
    fetch(API_BASE + '/api/reports/ecommerce-breakdown' + (month ? '?month=' + month : ''), { headers: authHeaders() })
      .then(function(res){ return res.json(); })
      .then(function(data){ renderEcommerceBreakdown(data); })
      .catch(function(err){ console.warn('ecommerce-breakdown failed:', err); });
  }

  function renderEcommerceBreakdown(data) {
    const fmt = function(n){ return '₱' + Number(n||0).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2}); };
    const platforms = data.platforms || [];

    setText('rep-ecom-orders',   Number(data.totalOrders||0).toLocaleString());
    setText('rep-ecom-revenue',  fmt(data.totalRevenue));
    setText('rep-ecom-avg',      fmt(data.avgOrder));
    setText('rep-ecom-platforms', String(platforms.length));
    setText('rep-ecom-total-badge', 'Total: ' + fmt(data.totalRevenue) + ' · ' + platforms.length + ' platforms');

    // Platform comparison bars
    const barsEl = $('rep-ecom-platform-bars');
    if (barsEl) {
      const platColors = { SHOPEE: '#FF6A13', TIKTOK: '#2D2D2D', LAZADA: '#0F3FAB' };
      barsEl.innerHTML = platforms.map(function(p) {
        const col = platColors[p.platform] || '#D4860A';
        const pct = Number(p.percentage || 0).toFixed(1);
        return '<div style="margin-bottom:10px;">'
          + '<div style="display:flex;justify-content:space-between;font-size:12px;margin-bottom:3px;">'
          + '<span style="display:flex;align-items:center;gap:6px;">'
          + '<span style="width:10px;height:10px;border-radius:2px;background:' + col + ';display:inline-block;"></span>'
          + '<strong>' + escapeHtml(p.platform) + '</strong></span>'
          + '<span style="color:var(--text-muted);">' + (p.orderCount||0) + ' orders · <strong>' + fmt(p.revenue) + '</strong> · ' + pct + '%</span>'
          + '</div>'
          + '<div style="background:var(--bg-secondary);border-radius:20px;height:6px;overflow:hidden;">'
          + '<div style="height:100%;border-radius:20px;background:' + col + ';width:' + p.percentage + '%;"></div>'
          + '</div></div>';
      }).join('');
    }

    // Per-platform detail with top products
    const detailEl = $('rep-ecom-platforms-detail');
    if (detailEl) {
      const platEmoji    = { SHOPEE: '🛍️', TIKTOK: '🎵', LAZADA: '🛒' };
      const platBadgeCls = {
        SHOPEE: 'background:#FFF0E6;color:#B55A00;',
        TIKTOK: 'background:#F0F0F0;color:#333;',
        LAZADA: 'background:#EAF0FF;color:#1A4A9E;'
      };
      detailEl.innerHTML = platforms.map(function(p) {
        const emoji      = platEmoji[p.platform] || '🛒';
        const badgeStyle = platBadgeCls[p.platform] || 'background:var(--bg-secondary);color:var(--text-primary);';
        const topRows    = (p.topProducts || []).map(function(t, i) {
          return '<tr><td>' + (i + 1) + '</td>'
            + '<td style="font-weight:500;">' + escapeHtml(t.productName || '—') + '</td>'
            + '<td style="text-align:right;">' + (t.qtySold || 0).toLocaleString() + ' pcs</td>'
            + '<td style="text-align:right;">' + fmt(t.revenue) + '</td></tr>';
        }).join('');
        return '<div style="border-top:0.5px solid var(--border);">'
          + '<div style="display:flex;align-items:center;gap:10px;padding:11px 16px;">'
          + '<span style="font-size:18px;">' + emoji + '</span>'
          + '<div style="flex:1;">'
          + '<div style="font-size:13px;font-weight:600;">' + escapeHtml(p.platform) + '</div>'
          + '<div style="font-size:11px;color:var(--text-muted);">' + (p.orderCount||0) + ' orders this month</div>'
          + '</div>'
          + '<span style="font-size:12px;font-weight:600;padding:3px 10px;border-radius:20px;' + badgeStyle + '">' + fmt(p.revenue) + '</span>'
          + '</div>'
          + '<div style="display:grid;grid-template-columns:repeat(3,1fr);gap:8px;padding:0 14px 10px;">'
          + '<div style="background:var(--bg-secondary);border-radius:8px;padding:9px 12px;">'
          + '<div style="font-size:11px;color:var(--text-muted);">Revenue</div>'
          + '<div style="font-size:14px;font-weight:600;">' + fmt(p.revenue) + '</div></div>'
          + '<div style="background:var(--bg-secondary);border-radius:8px;padding:9px 12px;">'
          + '<div style="font-size:11px;color:var(--text-muted);">Avg. order</div>'
          + '<div style="font-size:14px;font-weight:600;">' + fmt(p.avgOrder) + '</div></div>'
          + '<div style="background:var(--bg-secondary);border-radius:8px;padding:9px 12px;">'
          + '<div style="font-size:11px;color:var(--text-muted);">% of e-com</div>'
          + '<div style="font-size:14px;font-weight:600;">' + Number(p.percentage||0).toFixed(1) + '%</div></div>'
          + '</div>'
          + (topRows
            ? '<div style="padding:8px 16px 4px;font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.06em;color:var(--text-muted);border-top:0.5px solid var(--border);">Top products this month</div>'
              + '<table class="table" style="margin:0;font-size:12px;">'
              + '<thead><tr><th>#</th><th>Product</th><th style="text-align:right;">Qty</th><th style="text-align:right;">Revenue</th></tr></thead>'
              + '<tbody>' + topRows + '</tbody></table>'
            : '')
          + '</div>';
      }).join('');
    }
  }

  // ================================================================
  // Order Ledger — view transaction history for one order
  // ================================================================
  window.viewOrderLedger = function (orderId) {
    $('ledger-order-id-label').textContent = orderId;
    $('ledger-tbody').innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-muted);padding:20px;">Loading…</td></tr>';
    $('ledger-net').textContent = '—';
    $('modal-order-ledger').classList.add('open');

    fetch(API_BASE + '/api/transactions/order/' + encodeURIComponent(orderId), { headers: authHeaders() })
      .then(function (res) { return res.json(); })
      .then(function (txns) {
        if (!txns.length) {
          $('ledger-tbody').innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-muted);padding:20px;">No transactions recorded for this order</td></tr>';
          $('ledger-net').textContent = '₱0.00';
          return;
        }
        const typeColor = { SALE:'#10B981', REFUND:'#EF4444', VOID:'#EF4444', RETURN:'#EF4444', ADJUSTMENT:'#F59E0B', DISCOUNT:'#F59E0B' };
        let net = 0;
        $('ledger-tbody').innerHTML = txns.map(function (t) {
          const amt = parseFloat(t.amount) || 0;
          net += amt;
          const color = typeColor[t.transactionType] || 'inherit';
          const amtText = (amt < 0 ? '-₱' : '₱') + Math.abs(amt).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
          return '<tr>'
            + '<td><code style="font-size:11px;">' + escapeHtml(t.transactionCode) + '</code></td>'
            + '<td><span style="color:' + color + ';font-weight:600;font-size:12px;">' + escapeHtml(t.transactionType) + '</span></td>'
            + '<td style="text-align:right;color:' + color + ';font-weight:600;">' + amtText + '</td>'
            + '<td>' + (t.effectiveDate ? formatDate(t.effectiveDate) : '—') + '</td>'
            + '<td style="font-size:12px;color:var(--text-muted);">' + escapeHtml(t.notes || '—') + '</td>'
            + '</tr>';
        }).join('');
        const netColor = net >= 0 ? '#10B981' : '#EF4444';
        const netText  = (net < 0 ? '-₱' : '₱') + Math.abs(net).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        $('ledger-net').textContent  = netText;
        $('ledger-net').style.color  = netColor;
      })
      .catch(function () {
        $('ledger-tbody').innerHTML = '<tr><td colspan="5" style="text-align:center;color:#EF4444;">Failed to load ledger</td></tr>';
      });
  };

  // ================================================================
  // Order Detail View (Step 11)
  // GET /api/orders/{id}  — cache-first, live fetch on miss
  // ================================================================

  window.openOrderDetail = async function (orderId) {
    // Cache-first: check today's list then history; fall back to live API
    var order = (appState.allOrders || []).find(function(o) { return o.id === orderId; })
             || (appState.orderHistoryAll || []).find(function(o) { return o.id === orderId; });
    if (!order) {
      try {
        var r = await fetch(API_BASE + '/api/orders/' + orderId, { headers: authHeaders() });
        if (r.ok) order = await r.json();
      } catch (e) {}
    }
    if (!order) { showToast('Could not load order details', 'error'); return; }

    $('order-detail-id-label').textContent = orderId;

    // --- Two-way link banners ---
    var bannerHtml = '';
    if (order.cancellationType === 'REPLACEMENT') {
      if (order.replacementOrderId) {
        bannerHtml = '<div style="background:#FEF3C7;border:1px solid #F59E0B;border-radius:6px;padding:10px 14px;margin-bottom:14px;font-size:13px;">'
          + '<i class="ti ti-replace" style="color:#D97706;margin-right:6px;"></i>'
          + '<strong>Cancelled &mdash; replaced by another order.</strong>'
          + '<div style="margin-top:4px;font-size:12px;">Replacement order: '
          + '<a href="#" onclick="event.preventDefault();closeModal(\'modal-order-detail\');jumpToOrder(\'' + order.replacementOrderId + '\')" '
          + 'style="color:#D97706;font-weight:600;">' + escapeHtml(order.replacementOrderId) + '</a></div>'
          + '</div>';
      } else {
        bannerHtml = '<div style="background:#FEF3C7;border:1px solid #F59E0B;border-radius:6px;padding:10px 14px;margin-bottom:14px;font-size:13px;">'
          + '<i class="ti ti-replace" style="color:#D97706;margin-right:6px;"></i>'
          + '<strong>Cancelled for replacement.</strong>'
          + '<div style="margin-top:4px;font-size:12px;color:#9CA3AF;">Replacement order not yet created.</div>'
          + '</div>';
      }
    } else if (order.originalOrderId) {
      bannerHtml = '<div style="background:#EDE9FE;border:1px solid #7C3AED;border-radius:6px;padding:10px 14px;margin-bottom:14px;font-size:13px;">'
        + '<i class="ti ti-replace" style="color:#7C3AED;margin-right:6px;"></i>'
        + '<strong>This is a replacement order.</strong>'
        + '<div style="margin-top:4px;font-size:12px;">Original order: '
        + '<a href="#" onclick="event.preventDefault();closeModal(\'modal-order-detail\');jumpToOrder(\'' + order.originalOrderId + '\')" '
        + 'style="color:#7C3AED;font-weight:600;">' + escapeHtml(order.originalOrderId) + '</a></div>'
        + '</div>';
    }

    // --- Order info grid ---
    var infoHtml = '<div style="display:grid;grid-template-columns:1fr 1fr;gap:6px 16px;font-size:13px;margin-bottom:14px;">'
      + '<div><div style="color:var(--text-muted);font-size:11px;margin-bottom:2px;">Customer</div><div style="font-weight:600;">' + escapeHtml(order.customerName || '—') + '</div></div>'
      + '<div><div style="color:var(--text-muted);font-size:11px;margin-bottom:2px;">Status</div><div>' + orderStatusCell(order) + '</div></div>'
      + '<div><div style="color:var(--text-muted);font-size:11px;margin-bottom:2px;">Source</div><div>' + formatSource(order.source || '—') + '</div></div>'
      + '<div><div style="color:var(--text-muted);font-size:11px;margin-bottom:2px;">Payment</div><div>' + formatPaymentMode(order.paymentMode || '—') + '</div></div>'
      + '<div><div style="color:var(--text-muted);font-size:11px;margin-bottom:2px;">Order Type</div><div>' + escapeHtml(order.orderType || '—') + '</div></div>'
      + '<div><div style="color:var(--text-muted);font-size:11px;margin-bottom:2px;">Created by</div><div>' + escapeHtml(order.createdByName || '—') + '</div></div>';
    if (order.address) {
      infoHtml += '<div style="grid-column:1/-1;"><div style="color:var(--text-muted);font-size:11px;margin-bottom:2px;">Address</div><div>' + escapeHtml(order.address) + '</div></div>';
    }
    if (order.notes) {
      infoHtml += '<div style="grid-column:1/-1;"><div style="color:var(--text-muted);font-size:11px;margin-bottom:2px;">Notes</div><div style="color:var(--text-muted);">' + escapeHtml(order.notes) + '</div></div>';
    }
    infoHtml += '</div>';

    // --- Items table ---
    var items = order.items || [];
    var hasVoids = items.some(function(it) { return (it.voidedQuantity || 0) > 0; });
    var itemsHtml = '<table class="table" style="margin:0 0 14px;">'
      + '<thead><tr>'
      + '<th>Product</th>'
      + (hasVoids
          ? '<th style="text-align:center;color:var(--text-muted);">Ordered</th>'
            + '<th style="text-align:center;color:#EF4444;">Voided</th>'
            + '<th style="text-align:center;color:#10B981;">Active</th>'
          : '<th style="text-align:center;">Qty</th>')
      + '<th style="text-align:right;">Unit Price</th>'
      + '<th style="text-align:right;">Subtotal</th>'
      + '</tr></thead><tbody>'
      + items.map(function(it) {
          var voided = it.voidedQuantity || 0;
          var active = it.quantity - voided;
          var fullyVoided = active <= 0;
          var nameHtml = fullyVoided
            ? '<span style="text-decoration:line-through;color:var(--text-muted);">' + escapeHtml(it.productName || '') + '</span>'
            : escapeHtml(it.productName || '');
          var qtyHtml;
          if (hasVoids) {
            var voidedCell = voided > 0
              ? '<td style="text-align:center;color:#EF4444;font-weight:600;">&#8722;' + voided + '</td>'
              : '<td style="text-align:center;color:var(--text-muted);">&#8212;</td>';
            var activeCell = '<td style="text-align:center;color:' + (fullyVoided ? '#EF4444' : '#10B981') + ';font-weight:600;">' + active + '</td>';
            qtyHtml = '<td style="text-align:center;color:var(--text-muted);">' + it.quantity + '</td>' + voidedCell + activeCell;
          } else {
            qtyHtml = '<td style="text-align:center;">' + it.quantity + '</td>';
          }
          var effSubtotal = Number(it.unitPrice || 0) * active;
          var subtotalHtml = fullyVoided
            ? '<span style="text-decoration:line-through;color:var(--text-muted);">&#8369;' + Number(it.subtotal || 0).toLocaleString('en-PH', {minimumFractionDigits:2,maximumFractionDigits:2}) + '</span>'
            : '&#8369;' + effSubtotal.toLocaleString('en-PH', {minimumFractionDigits:2,maximumFractionDigits:2});
          return '<tr>'
            + '<td style="font-size:12px;">' + nameHtml + '</td>'
            + qtyHtml
            + '<td style="text-align:right;font-size:12px;">&#8369;' + Number(it.unitPrice || 0).toLocaleString('en-PH', {minimumFractionDigits:2,maximumFractionDigits:2}) + '</td>'
            + '<td style="text-align:right;font-size:12px;">' + subtotalHtml + '</td>'
            + '</tr>';
        }).join('')
      + '</tbody></table>';

    // --- Totals ---
    var vAmt = Number(order.voidedAmount || 0);
    var effTotal = Number(order.total || 0) - vAmt;
    var totalsHtml = '<div style="text-align:right;font-size:13px;border-top:1px solid var(--border-light);padding-top:8px;">';
    if (Number(order.discount || 0) > 0) {
      totalsHtml += '<div style="color:var(--text-muted);">Discount: <span style="color:#10B981;">&#8722;&#8369;' + Number(order.discount).toLocaleString('en-PH', {minimumFractionDigits:2,maximumFractionDigits:2}) + '</span></div>';
    }
    if (Number(order.deliveryFee || 0) > 0) {
      totalsHtml += '<div style="color:var(--text-muted);">Delivery fee: &#8369;' + Number(order.deliveryFee).toLocaleString('en-PH', {minimumFractionDigits:2,maximumFractionDigits:2}) + '</div>';
    }
    if (vAmt > 0) {
      totalsHtml += '<div style="color:var(--text-muted);">Voided: <span style="color:#EF4444;">&#8722;&#8369;' + vAmt.toLocaleString('en-PH', {minimumFractionDigits:2,maximumFractionDigits:2}) + '</span></div>';
      totalsHtml += '<div style="font-weight:700;font-size:15px;">Effective Total: &#8369;' + effTotal.toLocaleString('en-PH', {minimumFractionDigits:2,maximumFractionDigits:2}) + '</div>';
    } else {
      totalsHtml += '<div style="font-weight:700;font-size:15px;">Total: &#8369;' + Number(order.total || 0).toLocaleString('en-PH', {minimumFractionDigits:2,maximumFractionDigits:2}) + '</div>';
    }
    totalsHtml += '</div>';

    $('order-detail-body').innerHTML = bannerHtml + infoHtml + itemsHtml + totalsHtml;
    $('modal-order-detail').classList.add('open');
  };

  // ================================================================
  // Return and Adjustment
  // POST /api/orders/{id}/return
  // ================================================================

  window.openReturnModal = async function (orderId) {
    // Look up full order from either cache; fall back to API if not found
    var order = (appState.allOrders || []).find(function(o) { return o.id === orderId; })
             || (appState.orderHistoryAll || []).find(function(o) { return o.id === orderId; });
    if (!order) {
      try {
        var r = await fetch(API_BASE + '/api/orders/' + orderId, { headers: authHeaders() });
        if (r.ok) order = await r.json();
      } catch (e) {}
    }
    if (!order) { showToast('Could not load order details', 'error'); return; }

    appState.returnTargetId    = orderId;
    appState.returnTargetOrder = order;

    $('rtn-order-id').textContent     = orderId;
    $('rtn-reason').value             = '';
    $('rtn-security-key').value       = '';
    $('rtn-refund-toggle').checked    = false;
    $('rtn-refund-section').style.display   = 'none';
    $('rtn-refund-amount').value      = '';
    $('rtn-replacement-toggle').checked     = false;
    $('rtn-replacement-note').style.display = 'none';
    $('rtn-submit-btn').disabled      = true;

    window.renderReturnItems(order);
    $('modal-return').classList.add('open');
  };

  window.renderReturnItems = function (order) {
    var items = order.items || [];
    if (!items.length) {
      $('rtn-items-container').innerHTML =
        '<div style="font-size:12px;color:var(--text-muted);padding:8px 0;">No items found on this order.</div>';
      return;
    }
    var html = items.map(function(it) {
      var effQty = it.quantity - (it.voidedQuantity || 0);
      return '<div class="rtn-item-row" data-item-id="' + it.id + '" data-orig-qty="' + effQty + '"'
        + ' style="padding:8px 0;border-bottom:1px solid var(--border);">'
        + '<div style="font-size:12px;font-weight:600;margin-bottom:6px;">'
        + escapeHtml(it.productName || '')
        + ' <span style="color:var(--text-muted);font-weight:400;">× ' + effQty + ' ordered</span>'
        + '</div>'
        + '<div style="display:flex;gap:8px;align-items:center;">'
        +   '<div style="flex:1;">'
        +     '<div style="font-size:10px;color:var(--text-muted);margin-bottom:2px;">Total returned</div>'
        +     '<input type="number" class="form-control rtn-total" min="0" max="' + effQty + '" step="1" value="0"'
        +     ' style="font-size:12px;padding:4px 8px;" oninput="onReturnQtyChange()" />'
        +   '</div>'
        +   '<div style="flex:1;">'
        +     '<div style="font-size:10px;color:var(--text-muted);margin-bottom:2px;">Sellable</div>'
        +     '<input type="number" class="form-control rtn-sellable" min="0" max="' + effQty + '" step="1" value="0"'
        +     ' style="font-size:12px;padding:4px 8px;" oninput="onReturnQtyChange()" />'
        +   '</div>'
        +   '<div style="flex:1;">'
        +     '<div style="font-size:10px;color:var(--text-muted);margin-bottom:2px;">Rejected</div>'
        +     '<input type="number" class="form-control rtn-rejected" min="0" max="' + effQty + '" step="1" value="0"'
        +     ' style="font-size:12px;padding:4px 8px;" oninput="onReturnQtyChange()" />'
        +   '</div>'
        +   '<div class="rtn-validity" style="font-size:10px;width:80px;text-align:center;"></div>'
        + '</div>'
        + '<div class="rtn-wh-row" style="display:none;margin-top:6px;">'
        +   '<div style="font-size:10px;color:var(--text-muted);margin-bottom:2px;">Restock to warehouse</div>'
        +   '<select class="form-select rtn-warehouse" style="font-size:12px;padding:4px 8px;"'
        +   ' onchange="onReturnQtyChange()">'
        +   '<option value="">-- select --</option>'
        +   '<option value="wh1">WH1</option>'
        +   '<option value="wh2">WH2</option>'
        +   '<option value="wh3">Balagtas</option>'
        +   '</select>'
        + '</div>'
        + '</div>';
    }).join('');
    $('rtn-items-container').innerHTML = html;
  };

  // Re-evaluates row validation indicators and the submit lock after any input change
  window.onReturnQtyChange = function () {
    var anyReturning = false;
    var allValid     = true;

    document.querySelectorAll('.rtn-item-row').forEach(function(row) {
      var total    = parseInt(row.querySelector('.rtn-total').value)    || 0;
      var sellable = parseInt(row.querySelector('.rtn-sellable').value)  || 0;
      var rejected = parseInt(row.querySelector('.rtn-rejected').value)  || 0;
      var indicator = row.querySelector('.rtn-validity');

      // Show warehouse select only when sellable > 0
      var whRow = row.querySelector('.rtn-wh-row');
      if (whRow) whRow.style.display = (sellable > 0) ? '' : 'none';

      if (total <= 0) { indicator.textContent = ''; return; }  // row not participating

      anyReturning = true;
      if (sellable + rejected === total) {
        indicator.textContent = '✓';
        indicator.style.color = '#10B981';
      } else {
        indicator.textContent = 'Must equal ' + total;
        indicator.style.color = '#EF4444';
        allValid = false;
      }
    });

    var reason   = ($('rtn-reason').value      || '').trim();
    var secKey   = ($('rtn-security-key').value || '').trim();
    var refundOn = $('rtn-refund-toggle').checked;
    var refundAmt = refundOn ? (parseFloat($('rtn-refund-amount').value) || 0) : 1; // bypass when toggle off

    var warehouseOk = true;
    document.querySelectorAll('.rtn-item-row').forEach(function(r) {
      var sel = parseInt(r.querySelector('.rtn-sellable').value) || 0;
      if (sel > 0) {
        var wh = r.querySelector('.rtn-warehouse');
        if (!wh || !wh.value) warehouseOk = false;
      }
    });

    $('rtn-submit-btn').disabled = !(anyReturning && allValid && reason && secKey && (!refundOn || refundAmt > 0) && warehouseOk);
  };

  // Handles refund-section and replacement-note visibility on toggle change
  window.onRefundToggleChange = function () {
    var refundOn = $('rtn-refund-toggle').checked;
    $('rtn-refund-section').style.display = refundOn ? '' : 'none';
    if (!refundOn) $('rtn-refund-amount').value = '';
    $('rtn-replacement-note').style.display = $('rtn-replacement-toggle').checked ? '' : 'none';
    window.onReturnQtyChange();  // re-evaluate submit gate (refund amount requirement may have changed)
  };

  window.confirmReturn = async function () {
    var orderId = appState.returnTargetId;
    var reason  = ($('rtn-reason').value       || '').trim();
    var secKey  = ($('rtn-security-key').value  || '').trim();
    if (!reason) { showToast('Reason is required', 'error'); return; }
    if (!secKey) { showToast('Admin security key is required', 'error'); return; }

    // Build items array — only rows with totalReturned > 0 are included
    var items = [];
    var valid = true;
    document.querySelectorAll('.rtn-item-row').forEach(function(row) {
      var total    = parseInt(row.querySelector('.rtn-total').value)    || 0;
      var sellable = parseInt(row.querySelector('.rtn-sellable').value)  || 0;
      var rejected = parseInt(row.querySelector('.rtn-rejected').value)  || 0;
      if (total <= 0) return;
      if (sellable + rejected !== total) { valid = false; return; }
      var whEl = row.querySelector('.rtn-warehouse');
      var whVal = whEl ? whEl.value : '';
      var entry = {
        orderItemId:   Number(row.getAttribute('data-item-id')),
        totalReturned: total,
        sellableQty:   sellable,
        rejectedQty:   rejected
      };
      if (sellable > 0) entry.restockWarehouse = whVal;
      items.push(entry);
    });
    if (!valid)        { showToast('Fix item quantities before submitting', 'error'); return; }
    if (!items.length) { showToast('Enter quantities for at least one item', 'error'); return; }

    var refundOn  = $('rtn-refund-toggle').checked;
    var refundAmt = refundOn ? parseFloat($('rtn-refund-amount').value) : null;
    if (refundOn && (!refundAmt || refundAmt <= 0)) { showToast('Enter a valid refund amount', 'error'); return; }

    var payload = { securityKey: secKey, reason: reason, items: items };
    if (refundAmt) payload.refundAmount = refundAmt;

    var wantsReplacement = $('rtn-replacement-toggle').checked;

    try {
      var res = await fetch(API_BASE + '/api/orders/' + orderId + '/return', {
        method: 'POST', headers: authHeaders(),
        body: JSON.stringify(payload)
      });
      var data = await res.json();
      if (!res.ok) { showToast('Return failed: ' + (data.message || res.status), 'error'); return; }

      closeModal('modal-return');
      var toastMsg = refundAmt
        ? 'Return and ₱' + Number(refundAmt).toLocaleString('en-PH', { minimumFractionDigits: 2 }) + ' refund recorded for order ' + orderId
        : 'Return recorded for order ' + orderId;
      if (wantsReplacement) toastMsg += ' — create the replacement order from the order detail view';
      showToast(toastMsg, 'success');
      renderOrderHistory();
    } catch (err) {
      showToast('Error: ' + err.message, 'error');
    }
  };

  // ================================================================
  // Initialization
  // ================================================================
  document.addEventListener('DOMContentLoaded', function () {
    setInterval(updateClock, 1000);
    updateClock();
    setTimeout(initDashboardCharts, 100); // create empty chart containers at load time
    const today = new Date().toISOString().split('T')[0];
    if ($('delivery-rep-date'))  $('delivery-rep-date').value  = today;
    if ($('activity-log-date'))  $('activity-log-date').value  = today;

    // Hide role-restricted items until login
    applyRoleRestrictions(null);

    // ----------------------------------------------------------------
    // Global Enter key handler — modals + login screen
    // ----------------------------------------------------------------
    document.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter') return;
      // Skip if the target is a textarea or button to avoid double-fire
      if (e.target.tagName === 'TEXTAREA' || e.target.tagName === 'BUTTON') return;

      // Per-modal action map — null means "click first primary/danger/warning btn"
      var actionMap = {
        'modal-addprod-key':    function () { verifyAddProductKey(); },
        'modal-close-daily':    null,
        'modal-cancel':         null,
        'modal-cod-resume':     null,
        'modal-return':         null,
        'modal-item-void':      null,
        'modal-payable-delete': null,
        'modal-payable-paid':     function () { confirmPayableStatusChange(); },
        'modal-delete-employee':  function () { confirmDeleteEmployee(); },
        'modal-change-password':  function () { submitChangePassword(); },
        'modal-editprod-form':    null,
        'modal-add-employee':     null,
        'modal-edit-employee':    null,
        'modal-logout':           function () { _doLogout(); }
      };

      var modalIds = Object.keys(actionMap);
      for (var i = 0; i < modalIds.length; i++) {
        var modal = $(modalIds[i]);
        if (modal && modal.classList.contains('open')) {
          e.preventDefault();
          if (typeof actionMap[modalIds[i]] === 'function') {
            actionMap[modalIds[i]]();
          } else {
            var btn = modal.querySelector('.btn-primary, .btn-danger, .btn-warning');
            if (btn) btn.click();
          }
          return;
        }
      }

      // Login screen — no modal open, login screen visible
      var loginScreen = $('login-screen');
      if (loginScreen && loginScreen.style.display !== 'none') {
        e.preventDefault();
        doLogin();
      }
    });
  });

  // ================================================================
  // Agent Registry — list and performance modal
  // ================================================================

  window.loadAgents = async function (queryParams) {
    var grid = $('agents-grid');
    if (!grid) return;
    grid.innerHTML = '<div style="text-align:center;color:var(--text-muted);padding:24px;grid-column:1/-1;">Loading…</div>';
    try {
      var url = API_BASE + '/api/agents' + (queryParams || '');
      var res = await fetch(url, { headers: authHeaders() });
      if (!res.ok) { grid.innerHTML = '<div style="text-align:center;color:var(--text-muted);padding:24px;grid-column:1/-1;">Failed to load agents.</div>'; return; }
      var agents = await res.json();
      if (!Array.isArray(agents) || agents.length === 0) {
        grid.innerHTML = '<div style="text-align:center;color:var(--text-muted);padding:24px;grid-column:1/-1;">No agents found.</div>';
        return;
      }
      grid.innerHTML = agents.map(function (a) {
        var statusBg = a.status === 'ACTIVE' ? '#D1FAE5' : '#F3F4F6';
        var statusFg = a.status === 'ACTIVE' ? '#065F46' : '#6B7280';
        var pending  = '₱' + Number(a.pendingCommission  || 0).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        var lifetime = '₱' + Number(a.lifetimeNetCommission || 0).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        return '<div class="agent-card" onclick="openAgentPanel(' + a.id + ')">' +
          '<div class="agent-card-top">' +
            '<span class="agent-card-code">' + escapeHtml(a.agentCode || '') + '</span>' +
            '<span class="agent-card-status" style="background:' + statusBg + ';color:' + statusFg + ';">' + escapeHtml(a.status || '') + '</span>' +
          '</div>' +
          '<div class="agent-card-name">' + escapeHtml(a.fullName || '') + '</div>' +
          '<div class="agent-card-territory">' + escapeHtml(a.territory || '') + '</div>' +
          '<div class="agent-card-stats">' +
            '<div class="agent-card-stat"><div class="agent-card-stat-value">' + (a.totalOrders || 0) + '</div><div class="agent-card-stat-label">Orders</div></div>' +
            '<div class="agent-card-stat"><div class="agent-card-stat-value">' + pending + '</div><div class="agent-card-stat-label">Pending</div></div>' +
            '<div class="agent-card-stat"><div class="agent-card-stat-value">' + lifetime + '</div><div class="agent-card-stat-label">Lifetime</div></div>' +
          '</div>' +
        '</div>';
      }).join('');
    } catch (err) {
      grid.innerHTML = '<div style="text-align:center;color:red;padding:24px;grid-column:1/-1;">Error loading agents.</div>';
    }
  };

  window.toggleAgentStatus = async function (agentId, currentStatus) {
    var newStatus = currentStatus === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
    var confirmMsg = newStatus === 'INACTIVE'
      ? 'Deactivate this agent? They will not appear in the order form agent dropdown.'
      : 'Reactivate this agent?';
    if (!confirm(confirmMsg)) return;
    try {
      var res = await fetch(API_BASE + '/api/agents/' + agentId + '/status', {
        method: 'PATCH',
        headers: Object.assign({}, authHeaders(), { 'Content-Type': 'application/json' }),
        body: JSON.stringify({ status: newStatus })
      });
      if (!res.ok) { showToast('Failed to update status (' + res.status + ')', 'error'); return; }
      showToast('Agent ' + (newStatus === 'ACTIVE' ? 'activated' : 'deactivated'), 'success');
      loadAgents();
    } catch (err) {
      console.error('toggleAgentStatus', err);
      showToast('Error updating status', 'error');
    }
  };

  window.applyAgentFilters = function () {
    var status    = ($('agent-filter-status')         || {}).value || 'ALL';
    var territory = ($('agent-filter-territory')      || {}).value || '';
    var minC      = ($('agent-filter-min-commission') || {}).value || '';
    var maxC      = ($('agent-filter-max-commission') || {}).value || '';
    var regFrom   = ($('agent-filter-reg-from')       || {}).value || '';
    var regTo     = ($('agent-filter-reg-to')         || {}).value || '';

    var params = '?status=' + encodeURIComponent(status);
    if (territory) params += '&territory='      + encodeURIComponent(territory);
    if (minC)      params += '&minCommission='  + encodeURIComponent(minC);
    if (maxC)      params += '&maxCommission='  + encodeURIComponent(maxC);
    if (regFrom)   params += '&registeredFrom=' + encodeURIComponent(regFrom);
    if (regTo)     params += '&registeredTo='   + encodeURIComponent(regTo);

    loadAgents(params);
  };

  window.clearAgentFilters = function () {
    var statusEl  = $('agent-filter-status');
    var terrEl    = $('agent-filter-territory');
    var minCEl    = $('agent-filter-min-commission');
    var maxCEl    = $('agent-filter-max-commission');
    var regFromEl = $('agent-filter-reg-from');
    var regToEl   = $('agent-filter-reg-to');
    if (statusEl)  statusEl.value  = 'ALL';
    if (terrEl)    terrEl.value    = '';
    if (minCEl)    minCEl.value    = '';
    if (maxCEl)    maxCEl.value    = '';
    if (regFromEl) regFromEl.value = '';
    if (regToEl)   regToEl.value   = '';
    loadAgents();
  };

  // ================================================================
  // Agent Slide-out Panel
  // ================================================================

  var _currentAgentId = null;
  var _currentAgentData = null;
  var _currentAgentPeriods = [];
  var _currentAgentExportFormat = 'pdf';

  window.openAgentPanel = async function (agentId) {
    var panelBody = $('agent-panel-body');
    var panelTitle = $('agent-panel-title');
    if (!panelBody) return;

    _currentAgentId = agentId;
    panelBody.innerHTML = '<div style="text-align:center;color:var(--text-muted);padding:24px;">Loading…</div>';
    if (panelTitle) panelTitle.textContent = 'Agent Details';

    // Open overlay + panel
    var overlay = $('agent-panel-overlay');
    var panel = $('agent-slide-panel');
    if (overlay) overlay.classList.add('open');
    if (panel) panel.classList.add('open');

    try {
      var res = await fetch(API_BASE + '/api/agents/' + agentId, { headers: authHeaders() });
      if (!res.ok) { panelBody.innerHTML = '<div style="color:red;padding:16px;">Failed to load agent.</div>'; return; }
      var a = await res.json();
      _currentAgentData = a;

      if (panelTitle) panelTitle.textContent = a.fullName || 'Agent Details';

      // Fetch periods for the dropdown
      try {
        var perfRes = await fetch(API_BASE + '/api/agents/' + agentId + '/performance', { headers: authHeaders() });
        if (perfRes.ok) {
          var perfData = await perfRes.json();
          _currentAgentPeriods = perfData.commissionSummary || [];
        } else {
          _currentAgentPeriods = [];
        }
      } catch (e) {
        _currentAgentPeriods = [];
      }

      var statusBg = a.status === 'ACTIVE' ? '#D1FAE5' : '#F3F4F6';
      var statusFg = a.status === 'ACTIVE' ? '#065F46' : '#6B7280';
      var pending  = '₱' + Number(a.pendingCommission  || 0).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      var lifetime = '₱' + Number(a.lifetimeNetCommission || 0).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

      var html =
        '<div class="slide-panel-info">' +
          '<div class="slide-panel-info-item"><span class="slide-panel-info-label">Agent Code</span><span class="slide-panel-info-value" style="font-family:monospace;">' + escapeHtml(a.agentCode || '') + '</span></div>' +
          '<div class="slide-panel-info-item"><span class="slide-panel-info-label">Contact</span><span class="slide-panel-info-value">' + escapeHtml(a.contactNumber || '') + '</span></div>' +
          '<div class="slide-panel-info-item"><span class="slide-panel-info-label">Territory</span><span class="slide-panel-info-value">' + escapeHtml(a.territory || '') + '</span></div>' +
          '<div class="slide-panel-info-item"><span class="slide-panel-info-label">Status</span><span class="slide-panel-info-value"><span style="padding:2px 8px;border-radius:10px;font-size:11px;font-weight:600;background:' + statusBg + ';color:' + statusFg + ';">' + escapeHtml(a.status || '') + '</span></span></div>' +
        '</div>' +
        '<div class="slide-panel-stats">' +
          '<div class="slide-panel-stat"><div class="slide-panel-stat-value">' + (a.totalOrders || 0) + '</div><div class="slide-panel-stat-label">Orders</div></div>' +
          '<div class="slide-panel-stat"><div class="slide-panel-stat-value">' + pending + '</div><div class="slide-panel-stat-label">Pending Commission</div></div>' +
          '<div class="slide-panel-stat"><div class="slide-panel-stat-value" style="color:#10B981;">' + lifetime + '</div><div class="slide-panel-stat-label">Lifetime Commission</div></div>' +
        '</div>' +
        '<div class="slide-panel-tabs">' +
          '<button class="slide-panel-tab active" onclick="switchAgentTab(\'orders\')">Orders</button>' +
          '<button class="slide-panel-tab" onclick="switchAgentTab(\'commission\')">Commission</button>' +
        '</div>' +
        '<div id="agent-tab-content">' +
          '<div style="text-align:center;color:var(--text-muted);padding:16px;">Loading orders…</div>' +
        '</div>';

      panelBody.innerHTML = html;

      // Load orders tab by default
      loadAgentOrders(agentId, null);

    } catch (err) {
      panelBody.innerHTML = '<div style="color:red;padding:16px;">Error loading agent.</div>';
      console.error('openAgentPanel', err);
    }
  };

  window.closeAgentPanel = function () {
    var overlay = $('agent-panel-overlay');
    var panel = $('agent-slide-panel');
    if (overlay) overlay.classList.remove('open');
    if (panel) panel.classList.remove('open');
    _currentAgentId = null;
    _currentAgentData = null;
    _currentAgentPeriods = [];
  };

  window.switchAgentTab = function (tab) {
    // Update tab buttons
    var tabs = document.querySelectorAll('.slide-panel-tab');
    tabs.forEach(function (t) { t.classList.remove('active'); });
    if (tab === 'orders') {
      if (tabs[0]) tabs[0].classList.add('active');
    } else {
      if (tabs[1]) tabs[1].classList.add('active');
    }

    var content = $('agent-tab-content');
    if (!content || !_currentAgentId) return;

    if (tab === 'orders') {
      loadAgentOrders(_currentAgentId, null);
    } else {
      loadAgentCommission(_currentAgentId, null);
    }
  };

  window.editCurrentAgent = function () {
    if (_currentAgentId) openEditAgentModal(_currentAgentId);
  };

  window.toggleCurrentAgentStatus = async function () {
    if (!_currentAgentId || !_currentAgentData) return;
    var currentStatus = _currentAgentData.status;
    var newStatus = currentStatus === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
    var confirmMsg = newStatus === 'INACTIVE'
      ? 'Deactivate this agent? They will not appear in the order form agent dropdown.'
      : 'Reactivate this agent?';
    if (!confirm(confirmMsg)) return;
    try {
      var res = await fetch(API_BASE + '/api/agents/' + _currentAgentId + '/status', {
        method: 'PATCH',
        headers: Object.assign({}, authHeaders(), { 'Content-Type': 'application/json' }),
        body: JSON.stringify({ status: newStatus })
      });
      if (!res.ok) { showToast('Failed to update status (' + res.status + ')', 'error'); return; }
      showToast('Agent ' + (newStatus === 'ACTIVE' ? 'activated' : 'deactivated'), 'success');
      // Refresh panel and agent list
      openAgentPanel(_currentAgentId);
      loadAgents();
    } catch (err) {
      console.error('toggleCurrentAgentStatus', err);
      showToast('Error updating status', 'error');
    }
  };

  window.loadAgentOrders = async function (agentId, periodId) {
    var content = $('agent-tab-content');
    if (!content) return;

    // Build period dropdown grouped by year
    var periods = _currentAgentPeriods || [];
    var periodDropdown = '<div style="margin-bottom:12px;">' +
      '<label style="font-size:11px;color:var(--text-muted);display:block;margin-bottom:3px;">Period</label>' +
      '<select id="agent-panel-period-select" class="form-control" style="width:auto;min-width:220px;padding:5px 8px;font-size:12px;" onchange="loadAgentOrders(' + agentId + ', this.value || null)">' +
      '<option value="">All Periods</option>';

    if (periods.length > 0) {
      // Group by year
      var grouped = {};
      periods.forEach(function (p) {
        var year = p.startDate ? p.startDate.substring(0, 4) : 'Unknown';
        if (!grouped[year]) grouped[year] = [];
        grouped[year].push(p);
      });

      // Sort years descending
      var years = Object.keys(grouped).sort(function (a, b) { return b - a; });
      years.forEach(function (year) {
        periodDropdown += '<optgroup label="' + year + '">';
        grouped[year].forEach(function (p) {
          var selected = (periodId && periodId == p.periodId) ? ' selected' : '';
          periodDropdown += '<option value="' + p.periodId + '"' + selected + '>' + escapeHtml(p.periodCode || '') + ' (' + (p.startDate || '') + ' — ' + (p.endDate || '') + ')</option>';
        });
        periodDropdown += '</optgroup>';
      });
    }
    periodDropdown += '</select></div>';

    content.innerHTML = periodDropdown + '<div style="text-align:center;color:var(--text-muted);padding:16px;">Loading orders…</div>';
    try {
      var url = API_BASE + '/api/agents/' + agentId + '/orders' + (periodId ? '?periodId=' + periodId : '');
      var res = await fetch(url, { headers: authHeaders() });
      if (!res.ok) { content.innerHTML = '<div style="color:red;padding:16px;">Failed to load orders.</div>'; return; }
      var d = await res.json();
      var orders = d.orders || [];
      var summary = d.summary || {};

      if (orders.length === 0) {
        content.innerHTML = periodDropdown + '<div style="text-align:center;color:var(--text-muted);padding:16px;font-size:13px;">No orders found.</div>';
        return;
      }

      var html = periodDropdown + '<div style="font-size:12px;color:var(--text-muted);margin-bottom:10px;">' + summary.totalOrders + ' orders — ₱' + Number(summary.totalRevenue || 0).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' revenue</div>';
      html += orders.map(function (o) {
        var items = (o.items || []).map(function (it) {
          return '<tr>' +
            '<td>' + escapeHtml(it.productName || '') + '</td>' +
            '<td style="text-align:center;">' + (it.quantity || 0) + '</td>' +
            '<td style="text-align:right;">₱' + Number(it.unitPrice || 0).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + '</td>' +
            '<td style="text-align:right;">₱' + Number(it.opPerUnit || 0).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + '</td>' +
            '<td style="text-align:right;font-weight:600;">₱' + Number(it.opSubtotal || 0).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + '</td>' +
          '</tr>';
        }).join('');
        return '<div style="margin-bottom:12px;border:1px solid var(--border);border-radius:6px;overflow:hidden;">' +
          '<div style="padding:8px 12px;background:var(--bg-secondary);border-bottom:1px solid var(--border);font-size:12px;font-weight:600;display:flex;justify-content:space-between;">' +
            '<span><i class="ti ti-receipt" style="margin-right:4px;"></i>#' + escapeHtml(o.orderId || '') + '</span>' +
            '<span style="color:var(--text-muted);font-weight:400;">' + (o.date || '') + '</span>' +
          '</div>' +
          '<div style="overflow-x:auto;"><table class="table" style="margin:0;">' +
            '<thead><tr><th>Product</th><th style="text-align:center;">Qty</th><th style="text-align:right;">Price</th><th style="text-align:right;">OP/Unit</th><th style="text-align:right;">Total</th></tr></thead>' +
            '<tbody>' + items + '</tbody>' +
          '</table></div>' +
          '<div style="padding:6px 12px;border-top:1px solid var(--border);font-size:12px;display:flex;justify-content:flex-end;gap:16px;background:var(--bg-secondary);">' +
            '<span>Total OP: <strong>₱' + Number(o.totalOp || 0).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + '</strong></span>' +
          '</div>' +
        '</div>';
      }).join('');

      content.innerHTML = html;
    } catch (err) {
      content.innerHTML = periodDropdown + '<div style="color:red;padding:16px;">Error loading orders.</div>';
      console.error('loadAgentOrders', err);
    }
  };

  window.loadAgentCommission = async function (agentId, periodId) {
    var content = $('agent-tab-content');
    if (!content) return;

    // Build period dropdown grouped by year
    var periods = _currentAgentPeriods || [];
    var periodDropdown = '<div style="margin-bottom:12px;display:flex;gap:12px;align-items:flex-end;flex-wrap:wrap;">' +
      '<div>' +
      '<label style="font-size:11px;color:var(--text-muted);display:block;margin-bottom:3px;">Period</label>' +
      '<select id="agent-panel-commission-period-select" class="form-control" style="width:auto;min-width:220px;padding:5px 8px;font-size:12px;" onchange="loadAgentCommission(' + agentId + ', this.value || null)">' +
      '<option value="">All Periods</option>';

    if (periods.length > 0) {
      var grouped = {};
      periods.forEach(function (p) {
        var year = p.startDate ? p.startDate.substring(0, 4) : 'Unknown';
        if (!grouped[year]) grouped[year] = [];
        grouped[year].push(p);
      });
      var years = Object.keys(grouped).sort(function (a, b) { return b - a; });
      years.forEach(function (year) {
        periodDropdown += '<optgroup label="' + year + '">';
        grouped[year].forEach(function (p) {
          var selected = (periodId && periodId == p.periodId) ? ' selected' : '';
          periodDropdown += '<option value="' + p.periodId + '"' + selected + '>' + escapeHtml(p.periodCode || '') + ' (' + (p.startDate || '') + ' — ' + (p.endDate || '') + ')</option>';
        });
        periodDropdown += '</optgroup>';
      });
    }
    periodDropdown += '</select></div>';

    // Export format selector
    periodDropdown += '<div>' +
      '<label style="font-size:11px;color:var(--text-muted);display:block;margin-bottom:3px;">Export Format</label>' +
      '<select id="agent-panel-export-format" class="form-control" style="width:auto;min-width:100px;padding:5px 8px;font-size:12px;" onchange="_currentAgentExportFormat = this.value;">' +
      '<option value="pdf">PDF</option>' +
      '<option value="csv">CSV</option>' +
      '<option value="excel">Excel</option>' +
      '</select></div>' +
      '</div>';

    content.innerHTML = periodDropdown + '<div style="text-align:center;color:var(--text-muted);padding:16px;">Loading commission data…</div>';
    try {
      var res = await fetch(API_BASE + '/api/agents/' + agentId + '/performance', { headers: authHeaders() });
      if (!res.ok) { content.innerHTML = periodDropdown + '<div style="color:red;padding:16px;">Failed to load commission data.</div>'; return; }
      var d = await res.json();
      var summary = d.commissionSummary || [];

      if (summary.length === 0) {
        content.innerHTML = periodDropdown + '<div style="text-align:center;color:var(--text-muted);padding:16px;font-size:13px;">No commission periods found.</div>';
        return;
      }

      var fmt = function (n) { return '₱' + Number(n || 0).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 }); };
      var statusBadgeMini = function (s) {
        var m = { OPEN: 'background:#DBEAFE;color:#1E40AF;', CLOSED: 'background:#FEF3C7;color:#92400E;', RELEASED: 'background:#D1FAE5;color:#065F46;' };
        return '<span style="padding:1px 7px;border-radius:10px;font-size:10px;font-weight:600;' + (m[s] || 'background:#F3F4F6;color:#6B7280;') + '">' + (s || '—') + '</span>';
      };
      var filtered = periodId ? summary.filter(function (r) { return r.periodId == periodId; }) : summary;
      var rows = filtered.map(function (r) {
        var relAt = r.releasedAt ? r.releasedAt.replace('T', ' ').substring(0, 19) : '';
        var releaseBtn = r.status === 'CLOSED'
          ? '<button class="btn btn-sm btn-outline" style="font-size:10px;padding:2px 8px;margin-right:4px;" onclick="releaseFromAgentPanel(' + r.periodId + ',' + agentId + ')"><i class="ti ti-affiliate"></i> Release</button>'
          : '';
        return '<tr>' +
          '<td><code style="font-size:11px;">' + escapeHtml(r.periodCode || '') + '</code></td>' +
          '<td>' + (r.startDate || '') + '</td>' +
          '<td>' + (r.endDate || '') + '</td>' +
          '<td style="text-align:right;">' + fmt(r.totalOp) + '</td>' +
          '<td style="text-align:right;color:#10B981;">' + fmt(r.netCommission) + '</td>' +
          '<td>' + statusBadgeMini(r.status) + '</td>' +
          '<td style="font-size:11px;color:var(--text-muted);">' + relAt + '</td>' +
          '<td style="text-align:center;">' + releaseBtn + '<button class="btn btn-sm btn-outline" style="font-size:10px;padding:2px 8px;" onclick="downloadCommissionStatement(' + agentId + ',' + r.periodId + ')"><i class="ti ti-download"></i></button></td>' +
        '</tr>';
      }).join('');

      var html = '<div class="table-scroll"><table class="table">' +
        '<thead><tr><th>Period</th><th>Start</th><th>End</th><th style="text-align:right;">O.P.</th><th style="text-align:right;">Net Commission</th><th>Status</th><th>Released At</th><th style="text-align:center;">Actions</th></tr></thead>' +
        '<tbody>' + rows + '</tbody>' +
      '</table></div>';

      content.innerHTML = periodDropdown + html;
    } catch (err) {
      content.innerHTML = periodDropdown + '<div style="color:red;padding:16px;">Error loading commission data.</div>';
      console.error('loadAgentCommission', err);
    }
  };

  // ── Commission period management modal ───────────────────────────

  window.openCommissionPeriodModal = async function () {
    var modalBody = $('commission-period-modal-body');
    if (!modalBody) return;
    modalBody.innerHTML = '<div style="text-align:center;color:var(--text-muted);padding:24px;">Loading periods…</div>';
    openModal('modal-commission-periods');
    await renderPeriodList(modalBody);
  };

  async function renderPeriodList(container, focusNew) {
    try {
      var res = await fetch(API_BASE + '/api/commissions/periods', { headers: authHeaders() });
      if (!res.ok) { container.innerHTML = '<div style="color:red;padding:16px;">Failed to load periods.</div>'; return; }
      var periods = await res.json();
      if (!Array.isArray(periods)) periods = [];

      var statusBadge = function (s) {
        var map = { OPEN:'background:#DBEAFE;color:#1E40AF;', CLOSED:'background:#FEF3C7;color:#92400E;', RELEASED:'background:#D1FAE5;color:#065F46;' };
        return '<span style="padding:2px 8px;border-radius:10px;font-size:11px;font-weight:600;' + (map[s]||'background:#F3F4F6;color:#6B7280;') + '">' + (s||'—') + '</span>';
      };

      var rows = periods.length ? periods.map(function (p) {
        var closeBtn   = p.status === 'OPEN'
          ? '<button class="btn btn-sm btn-outline" style="font-size:10px;padding:3px 8px;" onclick="closePeriod(' + p.id + ')"><i class="ti ti-lock"></i> Close</button>' : '';
        var releaseBtn = p.status === 'CLOSED'
          ? '<button class="btn btn-sm btn-outline" style="font-size:10px;padding:3px 8px;" onclick="releasePeriod(' + p.id + ')"><i class="ti ti-affiliate"></i> Release</button>' : '';
        return '<tr>' +
          '<td><code style="font-size:11px;">' + escapeHtml(p.periodCode||'') + '</code></td>' +
          '<td style="font-size:11px;">' + (p.startDate||'—') + ' — ' + (p.endDate||'—') + '</td>' +
          '<td>' + statusBadge(p.status) + '</td>' +
          '<td style="display:flex;gap:4px;">' + closeBtn + releaseBtn + '</td>' +
          '</tr>';
      }).join('') : '<tr><td colspan="4" style="text-align:center;color:var(--text-muted);padding:16px;">No commission periods found.</td></tr>';

      var newForm = focusNew
        ? '<div id="period-new-form" style="margin-bottom:12px;padding:12px;border:1px solid var(--border);border-radius:6px;background:var(--card-bg);display:flex;flex-wrap:wrap;gap:8px;align-items:flex-end;">' +
            '<div><label style="font-size:11px;color:var(--text-muted);display:block;margin-bottom:2px;">Start Date</label><input type="date" id="period-start" class="form-control" style="width:150px;font-size:12px;"></div>' +
            '<div><label style="font-size:11px;color:var(--text-muted);display:block;margin-bottom:2px;">End Date</label><input type="date" id="period-end" class="form-control" style="width:150px;font-size:12px;"></div>' +
            '<div><label style="font-size:11px;color:var(--text-muted);display:block;margin-bottom:2px;">Notes (optional)</label><input type="text" id="period-notes" placeholder="e.g. 1st half June" class="form-control" style="width:180px;font-size:12px;"></div>' +
            '<div style="display:flex;gap:4px;">' +
              '<button class="btn btn-sm btn-primary" onclick="saveNewPeriod()" style="font-size:12px;">Save</button>' +
              '<button class="btn btn-sm btn-outline" onclick="cancelNewPeriod()" style="font-size:12px;">Cancel</button>' +
            '</div>' +
          '</div>'
        : '';

      container.innerHTML =
        '<div style="margin-bottom:10px;display:flex;gap:8px;align-items:center;">' +
          '<div style="font-size:12px;font-weight:600;">' + periods.length + ' period(s)</div>' +
          '<button class="btn btn-sm btn-primary" onclick="showNewPeriodForm()" style="font-size:12px;margin-left:auto;"><i class="ti ti-plus"></i> Open New Period</button>' +
        '</div>' +
        newForm +
        '<div class="table-scroll"><table class="table" style="font-size:12px;">' +
          '<thead><tr><th>Code</th><th>Dates</th><th>Status</th><th>Actions</th></tr></thead>' +
          '<tbody>' + rows + '</tbody>' +
        '</table></div>';
    } catch (err) {
      container.innerHTML = '<div style="color:red;padding:16px;">Error loading periods.</div>';
      console.error('renderPeriodList', err);
    }
  }

  window.showNewPeriodForm = function () {
    var container = $('commission-period-modal-body');
    if (container) renderPeriodList(container, true);
  };

  window.cancelNewPeriod = function () {
    var container = $('commission-period-modal-body');
    if (container) renderPeriodList(container, false);
  };

  window.saveNewPeriod = async function () {
    var start   = ($('period-start') || {}).value;
    var end     = ($('period-end')   || {}).value;
    var notes   = ($('period-notes') || {}).value || '';
    if (!start || !end) { showToast('Start date and end date are required.', 'error'); return; }
    try {
      var res = await fetch(API_BASE + '/api/commissions/periods', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
        body: JSON.stringify({ startDate: start, endDate: end, notes: notes })
      });
      var data = await res.json();
      if (!res.ok) { showToast(data.message || 'Failed to create period.', 'error'); return; }
      showToast('Period ' + (data.periodCode||'') + ' created.', 'success');
      if (data.backfill && data.backfill.ordersProcessed > 0) {
        showToast('Backfilled ' + data.backfill.ordersProcessed + ' order(s) from ' + data.backfill.agentsProcessed + ' agent(s) — ' + data.backfill.entriesCreated + ' commission entries created.', 'info');
      }
      var container = $('commission-period-modal-body');
      if (container) renderPeriodList(container, false);
    } catch (err) {
      showToast('Error creating period.', 'error');
      console.error('saveNewPeriod', err);
    }
  };

  window.closePeriod = async function (periodId) {
    if (!confirm('Close this commission period? Orders can still be added but no further changes allowed after closing.')) return;
    try {
      var res = await fetch(API_BASE + '/api/commissions/periods/' + periodId + '/close', {
        method: 'POST', headers: authHeaders()
      });
      if (!res.ok) { var d = await res.json(); showToast(d.message || 'Failed to close period.', 'error'); return; }
      showToast('Period closed.', 'success');
      var container = $('commission-period-modal-body');
      if (container) renderPeriodList(container, false);
    } catch (err) {
      showToast('Error closing period.', 'error');
      console.error('closePeriod', err);
    }
  };

  window.releasePeriod = async function (periodId) {
    var key = prompt('Enter your admin security key to release this period:');
    if (!key) return;
    try {
      var res = await fetch(API_BASE + '/api/commissions/periods/' + periodId + '/release', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
        body: JSON.stringify({ adminSecurityKey: key })
      });
      var data = await res.json();
      if (!res.ok) { showToast(data.message || 'Failed to release period.', 'error'); return; }
      showToast('Period released.', 'success');
      var container = $('commission-period-modal-body');
      if (container) renderPeriodList(container, false);
    } catch (err) {
      showToast('Error releasing period.', 'error');
      console.error('releasePeriod', err);
    }
  };

  window.releaseFromAgentPanel = async function (periodId, agentId) {
    var key = prompt('Enter your admin security key to release this period:');
    if (!key) return;
    try {
      var res = await fetch(API_BASE + '/api/commissions/periods/' + periodId + '/release', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
        body: JSON.stringify({ adminSecurityKey: key })
      });
      var data = await res.json();
      if (!res.ok) { showToast(data.message || 'Failed to release period.', 'error'); return; }
      showToast('Period released.', 'success');
      await loadAgentCommission(agentId, null);
    } catch (err) {
      showToast('Error releasing period.', 'error');
      console.error('releaseFromAgentPanel', err);
    }
  };

  window.downloadCommissionStatement = async function (agentId, periodId) {
    var fmt = _currentAgentExportFormat || 'pdf';
    var url = API_BASE + '/api/commissions/periods/' + periodId + '/agents/' + agentId +
              '/statement/export?format=' + encodeURIComponent(fmt);
    try {
      var res = await fetch(url, { headers: authHeaders() });
      if (!res.ok) { showToast('Statement export failed (' + res.status + ')', 'error'); return; }
      if (fmt === 'pdf') {
        var html = await res.text();
        var w = window.open('', '_blank', 'width=900,height=700');
        if (!w) { showToast('Pop-up blocked — allow pop-ups and try again', 'error'); return; }
        w.document.open();
        w.document.write(html);
        w.document.close();
      } else {
        var blob = await res.blob();
        var ext  = fmt === 'excel' ? 'xls' : 'csv';
        var burl = URL.createObjectURL(blob);
        var a    = document.createElement('a');
        a.href     = burl;
        a.download = 'statement-' + agentId + '-period-' + periodId + '.' + ext;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(burl);
      }
    } catch (err) {
      console.error('downloadCommissionStatement', err);
      showToast('Export failed', 'error');
    }
  };

  window.openEditAgentModal = async function (agentId) {
    var res;
    try {
      res = await fetch(API_BASE + '/api/agents/' + agentId, { headers: authHeaders() });
    } catch (e) { showToast('Connection error', 'error'); return; }
    if (!res.ok) { showToast('Agent not found', 'error'); return; }
    var a = await res.json();
    if ($('edit-agent-id'))        $('edit-agent-id').value        = a.id || '';
    if ($('edit-agent-name'))      $('edit-agent-name').value      = a.fullName || '';
    if ($('edit-agent-contact'))   $('edit-agent-contact').value   = a.contactNumber || '';
    if ($('edit-agent-territory')) $('edit-agent-territory').value = a.territory || '';
    if ($('edit-agent-email'))     $('edit-agent-email').value     = a.email || '';
    if ($('edit-agent-notes'))     $('edit-agent-notes').value     = a.notes || '';
    openModal('modal-edit-agent');
  };

  window.closeEditAgentModal = function () {
    closeModal('modal-edit-agent');
  };

  window.submitEditAgent = async function () {
    var id        = (($('edit-agent-id')        || {}).value || '').trim();
    var fullName  = (($('edit-agent-name')      || {}).value || '').trim();
    var contact   = (($('edit-agent-contact')   || {}).value || '').trim();
    var territory = (($('edit-agent-territory') || {}).value || '').trim();
    var email     = (($('edit-agent-email')     || {}).value || '').trim();
    var notes     = (($('edit-agent-notes')     || {}).value || '').trim();

    if (!fullName)  { showToast('Full name is required', 'error'); return; }
    if (!contact)   { showToast('Contact number is required', 'error'); return; }
    if (!territory) { showToast('Territory is required', 'error'); return; }

    try {
      var res = await fetch(API_BASE + '/api/agents/' + id, {
        method: 'PUT',
        headers: authHeaders(),
        body: JSON.stringify({ fullName: fullName, contactNumber: contact, territory: territory, email: email, notes: notes })
      });
      var data = await res.json();
      if (res.ok) {
        showToast('Agent updated', 'success');
        window.closeEditAgentModal();
        loadAgents();
      } else {
        showToast('Error: ' + (data.error || 'Update failed'), 'error');
      }
    } catch (err) {
      showToast('Connection error', 'error');
    }
  };

  // ================================================================
  // IMPORT — authorize → template → upload → preview → commit
  // ================================================================

  var _importSessionToken = null;
  var _importSecurityKey  = null;
  var _importHistoryData  = [];
  var _importUploadType   = 'sales'; // 'sales' | 'expenses'
  var _importReviewData   = null;     // upload response data for review modal

  window.loadImportHistory = async function () {
    var tb = $('import-history-tbody');
    if (!tb) return;
    tb.innerHTML = '<tr><td colspan="7" style="text-align:center;color:var(--text-muted);padding:24px;">Loading…</td></tr>';
    var token = localStorage.getItem('rrbm_token');
    if (!token) { tb.innerHTML = '<tr><td colspan="7" style="text-align:center;color:var(--text-muted);">Please login first</td></tr>'; return; }
    try {
      var res = await fetch(API_BASE + '/api/import/history', { headers: { Authorization: 'Bearer ' + token } });
      if (!res.ok) { tb.innerHTML = '<tr><td colspan="7" style="text-align:center;color:var(--text-muted);">Failed to load history</td></tr>'; return; }
      var rows = await res.json();
      _importHistoryData = rows;
      if (!rows.length) { tb.innerHTML = '<tr><td colspan="7" style="text-align:center;color:var(--text-muted);padding:24px;">No import history yet</td></tr>'; return; }
      tb.innerHTML = rows.map(function (r, idx) {
        return '<tr>'
          + '<td>' + (r.importDate || '—') + '</td>'
          + '<td>' + escapeHtml(r.importedBy || '—') + '</td>'
          + '<td style="text-align:right;">' + (r.ordersCount || 0) + '</td>'
          + '<td style="text-align:right;">' + (r.expensesCount || 0) + '</td>'
          + '<td style="text-align:right;">₱' + Number(r.totalOrderValue || 0).toLocaleString(undefined, {minimumFractionDigits:2,maximumFractionDigits:2}) + '</td>'
          + '<td style="text-align:right;">₱' + Number(r.totalExpenseAmount || 0).toLocaleString(undefined, {minimumFractionDigits:2,maximumFractionDigits:2}) + '</td>'
          + '<td style="text-align:center;"><button class="btn btn-secondary btn-sm" onclick="openImportDetailModal(' + idx + ')"><i class="ti ti-eye"></i> View</button></td>'
          + '</tr>';
      }).join('');
    } catch (e) {
      tb.innerHTML = '<tr><td colspan="7" style="text-align:center;color:var(--text-muted);">Connection error</td></tr>';
    }
  };

  window.openImportDetailModal = async function (idx) {
    var r = _importHistoryData[idx];
    if (!r) return;
    var bodyEl = $('import-detail-modal-body');
    if (!bodyEl) return;
    var fmt = function (n) { return '₱' + Number(n || 0).toLocaleString(undefined, {minimumFractionDigits:2, maximumFractionDigits:2}); };
    bodyEl.innerHTML =
      '<div style="margin-bottom:16px;">' +
        '<div style="font-size:11px;color:var(--text-muted);margin-bottom:4px;">Import Date</div>' +
        '<div style="font-size:15px;font-weight:600;">' + escapeHtml(r.importDate || '—') + '</div>' +
      '</div>' +
      '<div style="display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:20px;">' +
        '<div class="card" style="padding:12px;"><div style="font-size:11px;color:var(--text-muted);">Imported By</div><div style="font-weight:600;">' + escapeHtml(r.importedBy || '—') + '</div></div>' +
        '<div class="card" style="padding:12px;"><div style="font-size:11px;color:var(--text-muted);">Orders</div><div style="font-weight:600;">' + (r.ordersCount || 0) + '</div></div>' +
        '<div class="card" style="padding:12px;"><div style="font-size:11px;color:var(--text-muted);">Expenses</div><div style="font-weight:600;">' + (r.expensesCount || 0) + '</div></div>' +
        '<div class="card" style="padding:12px;"><div style="font-size:11px;color:var(--text-muted);">Total Order Value</div><div style="font-weight:600;">' + fmt(r.totalOrderValue) + '</div></div>' +
      '</div>' +
      '<div id="import-detail-lists"><div style="text-align:center;color:var(--text-muted);padding:16px;">Loading details…</div></div>';
    openModal('modal-import-detail');
    var token = localStorage.getItem('rrbm_token');
    if (!token || !r.importDate) { $('import-detail-lists').innerHTML = ''; return; }
    try {
      var res = await fetch(API_BASE + '/api/import/history/batch?date=' + encodeURIComponent(r.importDate),
        { headers: { Authorization: 'Bearer ' + token } });
      if (!res.ok) { $('import-detail-lists').innerHTML = '<div style="color:var(--text-muted);font-size:13px;">Could not load batch details.</div>'; return; }
      var data = await res.json();
      var ordersHtml = '';
      var expensesHtml = '';
      if (data.orders && data.orders.length) {
        ordersHtml = '<div style="font-weight:600;margin-bottom:8px;">Orders (' + data.orders.length + ')</div>' +
          '<div class="table-scroll"><table class="table"><thead><tr>' +
          '<th>Order ID</th><th>Customer</th><th>Source</th><th>Import Ref</th><th style="text-align:right;">Total</th>' +
          '</tr></thead><tbody>' +
          data.orders.map(function (o) {
            return '<tr>' +
              '<td><code style="font-size:11px;">' + escapeHtml(o.orderId || '') + '</code></td>' +
              '<td>' + escapeHtml(o.customer || '') + '</td>' +
              '<td>' + escapeHtml(o.source || '') + '</td>' +
              '<td style="font-size:11px;color:var(--text-muted);">' + escapeHtml(o.importRef || '') + '</td>' +
              '<td style="text-align:right;">' + fmt(o.total) + '</td>' +
              '</tr>';
          }).join('') +
          '</tbody></table></div>';
      } else {
        ordersHtml = '<div style="color:var(--text-muted);font-size:13px;margin-bottom:12px;">No orders in this batch.</div>';
      }
      if (data.expenses && data.expenses.length) {
        expensesHtml = '<div style="font-weight:600;margin:16px 0 8px;">Expenses (' + data.expenses.length + ')</div>' +
          '<div class="table-scroll"><table class="table"><thead><tr>' +
          '<th>Date</th><th>Payment Method</th><th>Reference</th><th style="text-align:right;">Amount</th><th></th>' +
          '</tr></thead><tbody>' +
          data.expenses.map(function (e) {
            var lateTag = e.lateImported
              ? '<span style="display:inline-block;padding:1px 6px;border-radius:10px;font-size:10px;font-weight:600;background:#FEF3C7;color:#92400E;">⚠ Late recorded</span>'
              : '';
            return '<tr>' +
              '<td>' + escapeHtml(e.date || '') + '</td>' +
              '<td>' + escapeHtml(e.paymentMethod || '') + '</td>' +
              '<td style="font-size:11px;color:var(--text-muted);">' + escapeHtml(e.referenceNumber || '') + '</td>' +
              '<td style="text-align:right;">' + fmt(e.totalAmount) + '</td>' +
              '<td style="font-size:11px;">' + lateTag + '</td>' +
              '</tr>';
          }).join('') +
          '</tbody></table></div>';
      } else if (data.expenses) {
        expensesHtml = '<div style="color:var(--text-muted);font-size:13px;">No expenses in this batch.</div>';
      }
      $('import-detail-lists').innerHTML = ordersHtml + expensesHtml;
    } catch (err) {
      $('import-detail-lists').innerHTML = '<div style="color:var(--text-muted);font-size:13px;">Could not load batch details.</div>';
    }
  };

  window.authorizeImport = async function () {
    var keyEl = $('import-security-key');
    var errEl = $('import-auth-error');
    if (!keyEl || !keyEl.value.trim()) { if (errEl) { errEl.textContent = 'Security key is required.'; errEl.style.display = 'block'; } return; }
    var token = localStorage.getItem('rrbm_token');
    if (!token) return;
    if (errEl) errEl.style.display = 'none';
    try {
      var res = await fetch(API_BASE + '/api/import/authorize', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
        body: JSON.stringify({ adminSecurityKey: keyEl.value.trim() })
      });
      if (res.ok) {
        _importSecurityKey = keyEl.value.trim();
        var stepsEl = $('import-steps-authorized');
        if (stepsEl) stepsEl.style.display = 'block';
        $('import-step-authorize').style.opacity = '0.5';
        $('import-step-authorize').style.pointerEvents = 'none';
      } else {
        var body = await res.json().catch(function () { return {}; });
        if (errEl) { errEl.textContent = body.error || body.message || 'Authorization failed.'; errEl.style.display = 'block'; }
      }
    } catch (e) {
      if (errEl) { errEl.textContent = 'Connection error.'; errEl.style.display = 'block'; }
    }
  };

  window.selectImportType = function (type) {
    _importUploadType = type;
    var salesBtn    = $('import-type-sales-btn');
    var expensesBtn = $('import-type-expenses-btn');
    if (salesBtn)    { salesBtn.className    = type === 'sales'    ? 'btn btn-primary btn-sm'   : 'btn btn-secondary btn-sm'; }
    if (expensesBtn) { expensesBtn.className = type === 'expenses' ? 'btn btn-primary btn-sm'   : 'btn btn-secondary btn-sm'; }
    // Reset preview when type changes
    var previewEl = $('import-preview-section');
    if (previewEl) previewEl.style.display = 'none';
    _importSessionToken = null;
  };

  window.downloadCombinedTemplate = function () {
    if (typeof XLSX === 'undefined') { showToast('Excel library not loaded yet. Please refresh the page.', 'error'); return; }
    var wb = XLSX.utils.book_new();

    // ═══════════════════════════════════════════════════════════════════════
    // Sheet 1: Instructions (opens first in Excel)
    // ═══════════════════════════════════════════════════════════════════════
    var instr = [
      ['RRBM DAILY IMPORT — COMBINED TEMPLATE'],
      [''],
      ['This file has two data sheets: Sales and Expenses. Fill in what you need, then upload this single file.'],
      ['The system detects both sheets automatically.'],
      [''],
      ['━━━ SALES COLUMN GUIDE ━━━'],
      ['Column','What to Enter','Rules & Tips'],
      ['Date','The actual order date (not today)','Any format works: 2026-06-07, 6/7/2026, June 7 2026. Must be a real date.'],
      ['Receipt#','Your manual receipt or order number','All rows with the same Receipt# become one order. Never leave blank.'],
      ['Time','Time of the order (optional)','Format: HH:MM. Leave blank if unknown.'],
      ['Customer','Customer full name (optional)','Leave blank for walk-in customers with no name on record.'],
      ['Source','Order channel','Valid: WALK_IN · AGENT · ECOMMERCE · FACEBOOK_PAGE · RESELLER · DISTRIBUTOR. Leave blank to auto-detect.'],
      ['Agent','Agent full registered name','Required when Source = AGENT. Must match exactly as registered.'],
      ['Payment Method','How the customer paid','Required. Valid: CASH · BANK_TRANSFER · GCASH · PAYMAYA · COD. For COD, also fill Payment Status.'],
      ['Platform','E-commerce platform','Required when Source = ECOMMERCE. Valid: SHOPEE · TIKTOK · LAZADA.'],
      ['Product Code','6-char system product code (e.g. PPB006)','Use the PRODUCT CODE, not the supplier/item code. Must exist in the product catalog.'],
      ['Qty','Number of units','Positive whole number only (e.g. 1, 2, 10). No decimals.'],
      ['Unit Price','Price per unit','Decimal number only (e.g. 150.00). No symbol, no commas.'],
      ['Base Price','Company price per unit (AGENT orders only)','For AGENT orders only. The business price before agent overprice. Leave blank for non-agent orders.'],
      ['OP per Unit','Over price per unit — agent commission','For AGENT orders only. Commission = OP per Unit × Qty. Leave blank for non-agent orders.'],
      ['Payment Status','PAID or UNPAID (COD only)','Leave blank for non-COD orders. PAID → active + commission. UNPAID → collections queue.'],
      [''],
      ['━━━ EXPENSES COLUMN GUIDE ━━━'],
      ['Column','What to Enter','Rules & Tips'],
      ['Date','The date the expense was incurred','Any format works. Must be a real date.'],
      ['Category','Category code or plain keyword','Use a code (e.g. UTILITY) or plain words like "gas", "water", "rent" — auto-detected.'],
      ['Sub-Category','Sub-category name (optional)','Must match exactly as listed in the system if filled.'],
      ['Amount','Expense amount','Numbers only — no symbol, no commas.'],
      ['Notes','Short description (optional)','Free text.'],
      ['Payment Method','How the expense was paid','Required. Valid: CASH · BANK_TRANSFER · GCASH · PAYMAYA · COD.'],
      ['Reference','OR / receipt number (optional)','Used for duplicate detection.'],
      [''],
      ['BEFORE YOU START'],
      ['✔ Keep the file as .xlsx — do not save as .csv or any other format.'],
      ['✔ Do NOT edit or remove the header rows in the Sales or Expenses sheets.'],
      ['✔ DELETE the example rows before uploading your real data.'],
      ['✘ Do not merge cells, add extra columns, or change column order.'],
      ['✘ Do not use formulas in cells — enter plain values only.'],
      ['✘ Do not use supplier item codes — only product codes from the system catalog.'],
    ];
    var instrWs = XLSX.utils.aoa_to_sheet(instr);
    instrWs['!ref'] = 'A1:C' + instr.length;
    XLSX.utils.book_append_sheet(wb, instrWs, 'Instructions');

    // ═══════════════════════════════════════════════════════════════════════
    // Sheet 2: Sales data
    // ═══════════════════════════════════════════════════════════════════════
    var sHeaders = ['Date','Receipt#','Time','Customer','Source','Agent','Payment Method','Platform','Product Code','Qty','Unit Price','Base Price','OP per Unit','Payment Status'];
    var sEx1a = ['2026-06-07','R001','09:00','Juan de la Cruz','WALK_IN','','CASH','','PPB006','2','150.00','','',''];
    var sEx1b = ['2026-06-07','R001','','','','','','','PPB007','1','200.00','','',''];
    var sEx2  = ['2026-06-07','R002','10:30','Maria Santos','AGENT','Agent Name Here','COD','','PPB006','3','150.00','100.00','50.00','UNPAID'];
    var sEx3  = ['2026-06-07','R003','11:00','','ECOMMERCE','','BANK_TRANSFER','SHOPEE','PPB007','1','200.00','','',''];
    var sEx4  = ['2026-06-07','R004','12:00','Pedro Reyes','WALK_IN','','CASH','','PPB006','2','150.00','','','PAID'];
    var sEx5  = ['2026-06-07','R005','12:30','Ana Cruz','AGENT','Another Agent','COD','','PPB007','4','200.00','','',''];
    var sData = [sHeaders, sEx1a, sEx1b, sEx2, sEx3, sEx4, sEx5];
    var salesWs = XLSX.utils.aoa_to_sheet(sData);
    salesWs['!ref'] = 'A1:N' + sData.length;
    XLSX.utils.book_append_sheet(wb, salesWs, 'Sales');

    // ═══════════════════════════════════════════════════════════════════════
    // Sheet 3: Expenses data
    // ═══════════════════════════════════════════════════════════════════════
    var eHeaders = ['Date','Category','Sub-Category','Amount','Notes','Payment Method','Reference'];
    var eEx1 = ['2026-06-07','UTILITY','Water Utility Bill','850.00','June water bill','CASH',''];
    var eEx2 = ['2026-06-07','gas','','500.00','Fuel for delivery van','CASH',''];
    var eEx3 = ['2026-06-07','OPERATIONS','Shipping Fee','1200.00','','BANK_TRANSFER','REF-2026-001'];
    var eData = [eHeaders, eEx1, eEx2, eEx3];
    var expWs = XLSX.utils.aoa_to_sheet(eData);
    expWs['!ref'] = 'A1:G' + eData.length;
    XLSX.utils.book_append_sheet(wb, expWs, 'Expenses');

    // ═══════════════════════════════════════════════════════════════════════
    // Sheet 4: Sales Reference
    // ═══════════════════════════════════════════════════════════════════════
    var sRef = [
      ['SALES — Column Reference'],
      ['Column','Valid Values','Notes'],
      ['Date','Any common date format','YYYY-MM-DD, M/D/YYYY, etc.'],
      ['Receipt#','Any text','Rows with same Receipt# grouped into one order'],
      ['Time','HH:MM or blank','Optional'],
      ['Customer','Any text','Optional'],
      ['Source','WALK_IN · AGENT · ECOMMERCE · FACEBOOK_PAGE · RESELLER · DISTRIBUTOR','Leave blank to auto-detect from Agent column'],
      ['Agent','Agent full name or blank','Required only when Source = AGENT'],
      ['Payment Method','CASH · BANK_TRANSFER · GCASH · PAYMAYA · COD','Required'],
      ['Platform','SHOPEE · TIKTOK · LAZADA','Required only when Source = ECOMMERCE'],
      ['Product Code','6-char code e.g. PPB006','Must exist in the product catalog'],
      ['Qty','Positive integer','e.g. 1, 2, 10'],
      ['Unit Price','Decimal number','e.g. 150.00 — what the customer pays'],
      ['Base Price','Decimal number','e.g. 100.00 — company price before agent overprice; AGENT orders only'],
      ['OP per Unit','Decimal or blank','e.g. 50.00 — agent commission per unit; AGENT orders only. Commission = OP per Unit × Qty'],
      ['Payment Status','PAID · UNPAID · blank','For COD only. PAID → active. UNPAID → collections.'],
      [''],
      ['EXPENSES — Column Reference'],
      ['Column','Valid Values','Notes'],
      ['Date','Any common date format','YYYY-MM-DD, M/D/YYYY, etc.'],
      ['Category','Code or plain text','UTILITY / OPERATIONS / FACILITY / INVENTORY / PERSONNEL / SUPPLY / SERVICES / MISC — or plain words like "gas", "water", "rent"'],
      ['Sub-Category','Sub-category name or blank','Must match exactly if filled'],
      ['Amount','Decimal number','e.g. 1200.00 — numbers only, no symbol'],
      ['Notes','Free text','Optional'],
      ['Payment Method','CASH · BANK_TRANSFER · GCASH · PAYMAYA · COD','Required'],
      ['Reference','OR / receipt number','Used for duplicate detection'],
      [''],
      ['EXPENSE — Natural Language Keywords'],
      ['Keyword','Maps To Category','Sub-category'],
      ['gas / fuel / petrol','OPERATIONS','Gas Allowance / Fuel Reimbursement'],
      ['water','UTILITY','Water Utility Bill'],
      ['electric / meralco','UTILITY','Electric Bill'],
      ['internet / wifi / pldt / converge','UTILITY','Internet Bill (ISP)'],
      ['rent / lease','FACILITY','Monthly Office Rent'],
      ['maintenance / repair','FACILITY','Building Maintenance'],
      ['salary / wage','PERSONNEL','Employee Salary'],
      ['food / meal','PERSONNEL','Daily Food Expense'],
      ['delivery','OPERATIONS','Delivery Budget'],
      ['packaging / tape','INVENTORY','Packaging Tapes'],
      ['box / carton','INVENTORY','Boxes/Cartons'],
      ['supplies','SUPPLY','Office Supplies'],
    ];
    var sRefWs = XLSX.utils.aoa_to_sheet(sRef);
    sRefWs['!ref'] = 'A1:C' + sRef.length;
    XLSX.utils.book_append_sheet(wb, sRefWs, 'Reference');

    XLSX.writeFile(wb, 'rrbm_import_template.xlsx');
  };

  window.uploadImportCsv = function () {
    var fileEl = $('import-file-input');
    var errEl  = $('import-upload-error');
    if (!fileEl || !fileEl.files.length) {
      if (errEl) { errEl.textContent = 'Please select an Excel file (.xlsx).'; errEl.style.display = 'block'; }
      return;
    }
    if (typeof XLSX === 'undefined') {
      if (errEl) { errEl.textContent = 'Excel library not loaded. Please refresh the page.'; errEl.style.display = 'block'; }
      return;
    }
    if (errEl) errEl.style.display = 'none';
    var token = localStorage.getItem('rrbm_token');
    if (!token) return;

    var file     = fileEl.files[0];
    var endpoint = API_BASE + '/api/import/upload/sales';

    var reader = new FileReader();
    reader.onerror = function () {
      if (errEl) { errEl.textContent = 'Failed to read file.'; errEl.style.display = 'block'; }
    };
    reader.onload = async function (e) {
      try {
        var wb = XLSX.read(new Uint8Array(e.target.result), { type: 'array', cellDates: true });

        // ── Detect dual-sheet (Sales + Expenses) vs single-sheet ──
        var sheetNames = wb.SheetNames.filter(function (n) {
          var lower = n.toLowerCase();
          return lower !== 'instructions' && lower !== 'reference';
        });

        var hasSalesSheet    = sheetNames.some(function (n) { return n.toLowerCase() === 'sales'; });
        var hasExpensesSheet = sheetNames.some(function (n) { return n.toLowerCase() === 'expenses'; });

        if (hasSalesSheet && hasExpensesSheet) {
          // ── Dual-sheet: send to /upload/combined ──
          var salesSheetName = sheetNames.find(function (n) { return n.toLowerCase() === 'sales'; });
          var expSheetName   = sheetNames.find(function (n) { return n.toLowerCase() === 'expenses'; });

          var salesRows    = rowsFromSheet(wb.Sheets[salesSheetName]);
          var expensesRows = rowsFromSheet(wb.Sheets[expSheetName]);

          if (salesRows.length < 2) {
            if (errEl) { errEl.textContent = 'Sales sheet appears empty — no data rows found after the header.'; errEl.style.display = 'block'; }
            return;
          }
          if (expensesRows.length < 2) {
            if (errEl) { errEl.textContent = 'Expenses sheet appears empty — no data rows found after the header.'; errEl.style.display = 'block'; }
            return;
          }

          var salesCsv    = rowsToCsv(salesRows);
          var expensesCsv = rowsToCsv(expensesRows);

          var fd = new FormData();
          fd.append('salesFile',    new Blob([salesCsv],    { type: 'text/csv;charset=utf-8' }), 'sales.csv');
          fd.append('expensesFile', new Blob([expensesCsv], { type: 'text/csv;charset=utf-8' }), 'expenses.csv');
          fd.append('adminSecurityKey', _importSecurityKey || '');

          var combinedEndpoint = API_BASE + '/api/import/upload/combined';
          var res = await fetch(combinedEndpoint, { method: 'POST', headers: { Authorization: 'Bearer ' + token }, body: fd });
          if (!res.ok) {
            var body = await res.json().catch(function () { return {}; });
            if (errEl) { errEl.textContent = body.error || body.message || 'Upload failed.'; errEl.style.display = 'block'; }
            return;
          }
          var data = await res.json();
          _importSessionToken = data.sessionToken;
          renderImportPreview(data, 'combined');
          var previewEl = $('import-preview-section');
          if (previewEl) previewEl.style.display = 'block';
          var issuesEl = $('import-issues-section');
          if (issuesEl) issuesEl.style.display = 'none';
          var commitBtn = document.getElementById('import-commit-btn');
          if (commitBtn) commitBtn.disabled = true;
          validateImport();
        } else {
          // ── Single-sheet: existing behavior based on upload type ──
          var ws = wb.Sheets[wb.SheetNames[0]];
          var rows = rowsFromSheet(ws);

          if (rows.length < 2) {
            if (errEl) { errEl.textContent = 'File appears empty — no data rows found after the header.'; errEl.style.display = 'block'; }
            return;
          }

          var csvStr = rowsToCsv(rows);
          endpoint = _importUploadType === 'expenses'
            ? API_BASE + '/api/import/upload/expenses'
            : API_BASE + '/api/import/upload/sales';

          var fd = new FormData();
          fd.append('file', new Blob([csvStr], { type: 'text/csv;charset=utf-8' }), 'upload.csv');
          fd.append('adminSecurityKey', _importSecurityKey || '');

          var res = await fetch(endpoint, { method: 'POST', headers: { Authorization: 'Bearer ' + token }, body: fd });
          if (!res.ok) {
            var body = await res.json().catch(function () { return {}; });
            if (errEl) { errEl.textContent = body.error || body.message || 'Upload failed.'; errEl.style.display = 'block'; }
            return;
          }
          var data = await res.json();
          _importSessionToken = data.sessionToken;
          renderImportPreview(data, _importUploadType);
          var previewEl = $('import-preview-section');
          if (previewEl) previewEl.style.display = 'block';
          var issuesEl = $('import-issues-section');
          if (issuesEl) issuesEl.style.display = 'none';
          var commitBtn = document.getElementById('import-commit-btn');
          if (commitBtn) commitBtn.disabled = true;
          validateImport();
        }
      } catch (err) {
        if (errEl) { errEl.textContent = 'Error reading Excel file: ' + err.message; errEl.style.display = 'block'; }
      }
    };
    reader.readAsArrayBuffer(file);
  };

  /** Extract rows from a SheetJS worksheet, dropping empty rows. */
  function rowsFromSheet(ws) {
    var rows = XLSX.utils.sheet_to_json(ws, { header: 1, raw: false, dateNF: 'yyyy-mm-dd' });
    return rows.filter(function (row) {
      return Array.isArray(row) && row.some(function (c) { return c != null && c.toString().trim() !== ''; });
    });
  }

  /** Convert rows array (array of arrays) to CSV string with proper quoting. */
  function rowsToCsv(rows) {
    return rows.map(function (row) {
      return row.map(function (cell) {
        var s = (cell == null) ? '' : cell.toString();
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
          s = '"' + s.replace(/"/g, '""') + '"';
        }
        return s;
      }).join(',');
    }).join('\r\n');
  }

  function renderImportPreview(data, uploadType) {
    var valid    = data.valid       || [];
    var needsFix = data.needsFix    || [];
    var dups     = data.duplicates  || [];
    var summary  = data.summary     || {};

    _importReviewData = data;
    _importReviewData._uploadType = uploadType;

    var orderCount   = 0;
    var expenseCount = 0;
    valid.forEach(function (r) {
      if (r.type === 'expense' || uploadType === 'expenses') expenseCount++;
      else orderCount++;
    });

    $('import-preview-order-count').textContent   = orderCount;
    $('import-preview-expense-count').textContent  = expenseCount;
    $('import-preview-fix-count').textContent      = summary.needsFixCount  !== undefined ? summary.needsFixCount  : needsFix.length;
    $('import-preview-dup-count').textContent      = summary.duplicateCount !== undefined ? summary.duplicateCount : dups.length;

    // ── Warn if any COD order is missing Payment Status ────────────────
    var codMissingPs = valid.filter(function (r) {
      return r.paymentMethod === 'COD' && (!r.paymentStatus || r.paymentStatus === '');
    });
    var warningEl = $('import-ps-warning');
    if (codMissingPs.length > 0) {
      var summaryHtml = codMissingPs.length === 1
        ? '1 COD order is missing a Payment Status.'
        : codMissingPs.length + ' COD orders are missing a Payment Status.';
      var rowsHtml = codMissingPs.map(function (r) {
        return '<div style="font-size:11px;padding:2px 0;">· ' + escapeHtml(r.receiptNum || '—') + ' — ' + escapeHtml(r.customer || 'unknown') + '</div>';
      }).join('');
      if (warningEl) {
        warningEl.innerHTML = '<i class="ti ti-alert-triangle"></i> ' + summaryHtml
          + ' These will default to <strong>PAID (ACTIVE)</strong> and will <strong>NOT</strong> appear in Collections.'
          + ' Set the Payment Status column to <strong>UNPAID</strong> for COD cash-on-delivery orders.'
          + rowsHtml;
        warningEl.style.display = 'block';
      }
    } else {
      if (warningEl) { warningEl.innerHTML = ''; warningEl.style.display = 'none'; }
    }

    // ── Needs Fix table ────────────────────────────────────────────────
    function issueRowHtml(r) {
      var ref = r.receiptNum || r.referenceNumber || '—';
      var issues = Array.isArray(r.errors) ? r.errors.join('; ') : (r.reason || '');
      return '<tr>'
        + '<td><code style="font-size:11px;">' + escapeHtml(ref) + '</code></td>'
        + '<td>' + escapeHtml(r.date || (r.rowIndex ? 'Row ' + r.rowIndex : '—')) + '</td>'
        + '<td colspan="2" style="font-size:11px;color:#EF4444;white-space:normal;">' + escapeHtml(issues) + '</td>'
        + '</tr>';
    }

    var ftb = $('import-fix-tbody');
    ftb.innerHTML = needsFix.length
      ? needsFix.map(issueRowHtml).join('')
      : '<tr><td colspan="4" style="color:#999;text-align:center;">None</td></tr>';

    // ── Duplicates table ───────────────────────────────────────────────
    var dtb = $('import-dup-tbody');
    dtb.innerHTML = dups.length
      ? dups.map(issueRowHtml).join('')
      : '<tr><td colspan="4" style="color:#999;text-align:center;">None</td></tr>';

    // ── Enable the review button ───────────────────────────────────────
    var reviewBtn = document.querySelector('#import-preview-section .btn-primary[onclick*="openReviewModal"]');
    if (reviewBtn) {
      reviewBtn.disabled = valid.length === 0;
    }
  }

  // ── Review Modal ────────────────────────────────────────────────────

  var _reviewPage  = 1;
  var _reviewPerPage = 10;
  var _reviewOverrides = { orders: {}, expenses: {} };
  var _reviewGreenItems = []; // [{ key, green }] flat list for green gate

  // ── Override helpers ──────────────────────────────────────────────────

  function _getOrderOv(receiptNum) {
    if (!_reviewOverrides.orders[receiptNum]) {
      _reviewOverrides.orders[receiptNum] = { include: true, items: {} };
    }
    return _reviewOverrides.orders[receiptNum];
  }

  function _getItemOv(receiptNum, rowIndex, createIfMissing) {
    var ov = _getOrderOv(receiptNum);
    if (!ov.items[rowIndex]) {
      if (createIfMissing !== false) ov.items[rowIndex] = {};
      else return undefined;
    }
    return ov.items[rowIndex];
  }

  function _getExpenseOv(refNum) {
    if (!_reviewOverrides.expenses[refNum]) {
      _reviewOverrides.expenses[refNum] = { include: true };
    }
    return _reviewOverrides.expenses[refNum];
  }

  function _setOrderField(receiptNum, field, value) {
    var ov = _getOrderOv(receiptNum);
    ov[field] = value;
  }

  function _setExpenseField(refNum, field, value) {
    var ov = _getExpenseOv(refNum);
    ov[field] = value;
  }

  // ── Green gate ───────────────────────────────────────────────────────

  function _recalcGreenGate() {
    var allGreen = true;
    var total = 0;
    var green = 0;
    _reviewGreenItems.forEach(function (ri) {
      if (!ri._include) return;
      total++;
      if (ri._green) green++;
      else allGreen = false;
    });
    if (total === 0) {
      allGreen = false;
    }
    _updateGreenGateUI(allGreen, green, total);
  }

  function _updateGreenGateUI(allGreen, green, total) {
    var bar = $('review-green-bar');
    var text = $('review-green-text');
    var commitBtn = $('review-commit-btn');
    if (!bar || !text || !commitBtn) return;
    if (total === 0) {
      bar.style.background = '#FEF3C7';
      bar.style.borderBottomColor = '#F59E0B';
      bar.style.color = '#92400E';
      text.textContent = 'All items excluded — nothing to commit';
      commitBtn.disabled = true;
      return;
    }
    if (allGreen) {
      bar.style.background = '#D1FAE5';
      bar.style.borderBottomColor = '#6EE7B7';
      bar.style.color = '#065F46';
      text.innerHTML = '<i class="ti ti-circle-check"></i> All items verified \u2014 ready to commit';
      commitBtn.disabled = false;
    } else {
      var needs = total - green;
      bar.style.background = '#FEF3C7';
      bar.style.borderBottomColor = '#F59E0B';
      bar.style.color = '#92400E';
      text.innerHTML = '<i class="ti ti-alert-triangle"></i> ' + needs + ' item(s) need product/category match';
      commitBtn.disabled = true;
    }
  }

  // ── Open review modal ────────────────────────────────────────────────

  window.openReviewModal = function () {
    var data = _importReviewData;
    if (!data) { showToast('No data to review. Please upload a file first.', 'error'); return; }
    var valid = data.valid || [];
    if (!valid.length) { showToast('No valid items to review.', 'error'); return; }

    _reviewPage = 1;
    _reviewOverrides = { orders: {}, expenses: {} };
    _reviewGreenItems = [];

    var isExpenseUpload = (data._uploadType === 'expenses');

    valid.forEach(function (item) {
      var isExpense = isExpenseUpload || item.type === 'expense';
      if (isExpense) {
        _reviewGreenItems.push({
          _key: item.referenceNumber || item.date + '_' + item.amount,
          _green: item.categoryId != null,
          _include: true,
          _receiptNum: null,
          _rowIndex: null
        });
      } else {
        (item.items || []).forEach(function (itm, ri) {
          _reviewGreenItems.push({
            _key: item.receiptNum + '_i' + ri,
            _green: itm.productId != null,
            _include: true,
            _receiptNum: item.receiptNum,
            _rowIndex: ri
          });
        });
      }
    });

    // Ensure expense categories are loaded for FUZZY dropdown rendering
    loadExpenseCategories().then(function () {
      _renderReviewPage();
      _setupReviewCardEvents();
      openModal('modal-import-review');
      _recalcGreenGate();
    });
  };

  // ── Render current page ──────────────────────────────────────────────

  function _renderReviewPage() {
    var data = _importReviewData;
    if (!data) return;
    var valid   = data.valid || [];
    var total   = valid.length;
    var pages   = Math.max(1, Math.ceil(total / _reviewPerPage));
    if (_reviewPage > pages) _reviewPage = pages;
    if (_reviewPage < 1)     _reviewPage = 1;

    var start = (_reviewPage - 1) * _reviewPerPage;
    var end   = Math.min(start + _reviewPerPage, total);
    var pageItems = valid.slice(start, end);

    var container = $('review-card-container');
    if (!container) return;

    var hintBar = $('review-hint-bar');
    if (hintBar) {
      hintBar.innerHTML = '<span>Showing ' + (start + 1) + '\u2013' + end + ' of ' + total + ' items</span>'
        + '<span style="font-size:10px;">Base Price and OP per Unit are editable for all items</span>';
    }

    var html = '';

    var isExpenseUpload = (data._uploadType === 'expenses');

    pageItems.forEach(function (item, idx) {
      var absIdx = start + idx;
      var isOrder = !isExpenseUpload && item.type !== 'expense';
      html += _renderReviewCard(item, absIdx, isOrder);
    });

    container.innerHTML = html;

    // Pagination controls
    var prevBtn = $('review-page-prev');
    var nextBtn = $('review-page-next');
    var infoEl  = $('review-page-info');
    if (prevBtn) prevBtn.disabled = _reviewPage <= 1;
    if (nextBtn) nextBtn.disabled = _reviewPage >= pages;
    if (!isFinite(pages)) pages = 1;
    if (!isFinite(_reviewPage)) _reviewPage = 1;
    if (infoEl)  infoEl.textContent = 'Page ' + _reviewPage + ' of ' + pages;

    // Recalculate green gate
    _recalcGreenGate();
  }

  // ── Render a single review card ──────────────────────────────────────

  function _renderReviewCard(item, absIdx, isOrder) {
    var fmt = function (n) {
      return n != null ? '\u20B1' + Number(n).toLocaleString('en-PH', {minimumFractionDigits: 2, maximumFractionDigits: 2}) : '\u2014';
    };
    var esc = escapeHtml;
    var sd = function (v) { return v == null || v === '' || (typeof v === 'number' && isNaN(v)) ? '\u2014' : esc(String(v)); };

    if (!isOrder) {
      // ═══════════════════════════════════════════════════════════════
      // EXPENSE REVIEW CARD
      // ═══════════════════════════════════════════════════════════════
      var expenseOv = _getExpenseOv(item.referenceNumber || item.date + '_' + item.amount);

      var matchBadge = item.matchConfidence === 'EXACT'
        ? '<span style="display:inline-flex;align-items:center;gap:3px;padding:1px 7px;border-radius:10px;font-size:10px;font-weight:600;background:#D1FAE5;color:#065F46;"><i class="ti ti-circle-check" style="font-size:9px;"></i> EXACT</span>'
        : item.matchConfidence === 'FUZZY'
          ? '<span style="display:inline-flex;align-items:center;gap:3px;padding:1px 7px;border-radius:10px;font-size:10px;font-weight:600;background:#FEF3C7;color:#92400E;"><i class="ti ti-alert-triangle" style="font-size:9px;"></i> FUZZY</span>'
          : '<span style="display:inline-flex;align-items:center;gap:3px;padding:1px 7px;border-radius:10px;font-size:10px;font-weight:600;background:#FEE2E2;color:#991B1B;"><i class="ti ti-circle-x" style="font-size:9px;"></i> NO MATCH</span>';

      var excluded = expenseOv.include === false;
      var expPm  = expenseOv.paymentMethod !== undefined ? expenseOv.paymentMethod : item.paymentMethod;
      var expAmt = expenseOv.amount !== undefined ? expenseOv.amount : item.amount;
      var expNotes = expenseOv.notes !== undefined ? expenseOv.notes : item.notes;

      // Category display: cascading dropdowns for FUZZY, static text for EXACT
      var catHtml;
      if (item.matchConfidence === 'FUZZY' && _expCatData) {
        var catPath = _findCatPath(item.categoryId);
        var key = esc(item.referenceNumber || item.date + '_' + item.amount);
        catHtml = '<div class="rc-cat-edit">'
          + '<select class="form-control form-select review-editor-input" data-field="exp-cat-primary" data-key="' + key + '">'
          + _expCatPrimaryOpts(catPath.primaryCode) + '</select>'
          + '<select class="form-control form-select review-editor-input" data-field="exp-cat-sub" data-key="' + key + '">'
          + _expCatSubOpts(catPath.primaryCode, catPath.subId) + '</select>'
          + '</div>';
      } else {
        catHtml = '<span>' + esc(item.category || 'Uncategorized') + (item.inferred ? ' <span style="font-size:10px;color:#D97706;font-style:italic;">inferred</span>' : '') + '</span>';
      }

      return '<div class="review-card' + (excluded ? ' excluded' : '') + '" data-abs-idx="' + absIdx + '">'
        // Title: Category + match badge
        + '<div class="rc-title" style="display:flex;align-items:center;justify-content:space-between;">'
        +   catHtml
        +   matchBadge
        + '</div>'
        // Description: date
        + '<div class="rc-desc">' + esc(item.date || '') + '</div>'
        // Metadata: amount, payment method, amount input, notes
        + '<div class="rc-meta">'
        +   '<div style="font-size:15px;font-weight:700;color:var(--text-primary);margin-bottom:8px;">' + fmt(expAmt) + '</div>'
        +   '<div style="display:grid;grid-template-columns:1fr 1fr;gap:6px;margin-bottom:6px;">'
        +     '<div><label style="font-size:10px;color:var(--text-muted);display:block;margin-bottom:1px;">Payment Method</label>'
        +       '<select class="form-control form-select" style="font-size:11px;padding:3px 5px;" data-field="exp-paymethod" data-key="' + esc(item.referenceNumber || item.date + '_' + item.amount) + '">'
        +         _paymentOpts(expPm)
        +       '</select></div>'
        +     '<div><label style="font-size:10px;color:var(--text-muted);display:block;margin-bottom:1px;">Amount</label>'
      +   '<input type="number" class="form-control" style="font-size:11px;padding:3px 5px;" value="' + sd(expAmt) + '" step="0.01" min="0" data-field="exp-amount" data-key="' + esc(item.referenceNumber || item.date + '_' + item.amount) + '">'
        +       '</div>'
        +   '</div>'
        +   '<div><label style="font-size:10px;color:var(--text-muted);display:block;margin-bottom:1px;">Notes</label>'
        +     '<input type="text" class="form-control" style="font-size:11px;padding:3px 5px;" value="' + esc(expNotes || '') + '" data-field="exp-notes" data-key="' + esc(item.referenceNumber || item.date + '_' + item.amount) + '">'
        +     '</div>'
        + '</div>'
        // Actions
        + '<div class="rc-actions">'
        +   '<label style="display:flex;align-items:center;gap:4px;font-size:11px;cursor:pointer;user-select:none;" title="Exclude from commit">'
        +     '<input type="checkbox" class="review-exclude-cb" data-type="expense" data-key="' + esc(item.referenceNumber || item.date + '_' + item.amount) + '" ' + (excluded ? 'checked' : '') + '> Exclude this expense'
        +   '</label>'
        + '</div>'
        + '</div>';
    }

    // ═══════════════════════════════════════════════════════════════════
    // ORDER REVIEW CARD
    // ═══════════════════════════════════════════════════════════════════
    var orderOv = _getOrderOv(item.receiptNum);
    var excluded = orderOv.include === false;

    var sourceColors = { AGENT: '#7C3AED', ECOMMERCE: '#8B5CF6', WALK_IN: '#10B981', RESELLER: '#F59E0B', DISTRIBUTOR: '#F59E0B', FACEBOOK_PAGE: '#3B82F6' };
    var sourceColor = sourceColors[item.source] || '#6B7280';

    var items = item.items || [];

    var itemsHtml = items.map(function (itm, ri) {
      var iov = _getItemOv(item.receiptNum, ri, false) || {};
      var overridePid = iov.productId;
      var effectivePid = overridePid !== undefined ? overridePid : itm.productId;
      var effectiveGreen = effectivePid != null;
      var effectiveName = iov.productName || itm.productName || itm.itemCode || 'Unknown';

      var matchIcon = effectiveGreen
        ? '<span style="display:inline-flex;align-items:center;gap:2px;padding:0 6px;border-radius:8px;font-size:9px;font-weight:600;background:#D1FAE5;color:#065F46;"><i class="ti ti-circle-check" style="font-size:8px;"></i></span>'
        : '<span style="display:inline-flex;align-items:center;gap:2px;padding:0 6px;border-radius:8px;font-size:9px;font-weight:600;background:#FEE2E2;color:#991B1B;"><i class="ti ti-circle-x" style="font-size:8px;"></i></span>';

      var prodDisplay = '<div class="product-autocomplete-wrapper" style="flex:1;">'
        + '<input type="text" class="form-control review-prod-input review-editor-input" style="font-size:11px;padding:3px 5px;width:100%;" placeholder="Search product\u2026" autocomplete="off"'
        +   ' data-rn="' + esc(item.receiptNum) + '" data-ri="' + ri + '" value="' + esc(effectiveName) + '"' + (excluded ? ' disabled' : '') + '>'
        + '<input type="hidden" class="review-prod-hidden" data-rn="' + esc(item.receiptNum) + '" data-ri="' + ri + '" value="' + (effectivePid || '') + '">'
        + '<div class="product-dropdown" style="position:absolute;z-index:1000;"></div>'
        + '</div>';

      var qty = iov.qty !== undefined ? iov.qty : itm.qty;
      var unitPrice = iov.unitPrice !== undefined ? iov.unitPrice : itm.unitPrice;
      var basePrice = iov.basePrice !== undefined ? iov.basePrice : itm.basePrice;
      var opPerUnit = iov.opPerUnit !== undefined ? iov.opPerUnit : itm.opPerUnit;

      var itemExcluded = iov._include === false;

      return '<div class="rc-item-row' + (itemExcluded ? ' excluded' : '') + '">'
        // Line 1: exclude X button + number + product + match icon
        + '<div style="display:flex;align-items:center;gap:4px;width:100%;">'
        +   '<button type="button" class="rc-item-exclude-btn' + (itemExcluded ? ' active' : '') + '" data-rn="' + esc(item.receiptNum) + '" data-ri="' + ri + '" title="Exclude this item"><i class="ti ti-x"></i></button>'
        +   '<span style="color:var(--text-muted);flex-shrink:0;width:14px;text-align:center;font-size:10px;">' + (ri + 1) + '</span>'
        +   '<div style="min-width:0;flex:1;">' + prodDisplay + '</div>'
        +   matchIcon
        + '</div>'
        // Line 2: compact labelled fields
        + '<div class="rc-item-fields">'
        +   '<span style="font-size:9px;color:var(--text-muted);">Qty</span>'
      +   '<input type="number" class="form-control review-item-num review-editor-input" value="' + sd(qty) + '" min="0" step="1"'
      +     ' data-field="qty" data-rn="' + esc(item.receiptNum) + '" data-ri="' + ri + '"' + (excluded ? ' disabled' : '') + '>'
      +   '<span style="font-size:9px;color:var(--text-muted);">Prc</span>'
      +   '<input type="number" class="form-control review-item-num review-editor-input" value="' + sd(unitPrice) + '" min="0" step="0.01"'
      +     ' data-field="unitPrice" data-rn="' + esc(item.receiptNum) + '" data-ri="' + ri + '"' + (excluded ? ' disabled' : '') + '>'
      +   '<span style="font-size:9px;color:var(--text-muted);">Base</span>'
      +   '<input type="number" class="form-control review-item-num review-editor-input" value="' + sd(basePrice) + '" min="0" step="0.01" placeholder="0"'
      +     ' data-field="basePrice" data-rn="' + esc(item.receiptNum) + '" data-ri="' + ri + '"' + (excluded ? ' disabled' : '') + '>'
      +   '<span style="font-size:9px;color:var(--text-muted);">OP</span>'
      +   '<input type="number" class="form-control review-item-num review-editor-input" value="' + sd(opPerUnit) + '" min="0" step="0.01" placeholder="0"'
      +     ' data-field="opPerUnit" data-rn="' + esc(item.receiptNum) + '" data-ri="' + ri + '"' + (excluded ? ' disabled' : '') + '>'
        + '</div>'
        + '</div>';
    }).join('');

      var ovPm   = orderOv.paymentMethod;
      var ovPs   = orderOv.paymentStatus;
      var ovEp   = orderOv.ecommercePlatform;
      var ovAgentId = orderOv.agentId;
      var ovAgentName = orderOv.agentName;
      var effectivePm   = ovPm   !== undefined ? ovPm   : item.paymentMethod;
      var effectivePs   = ovPs   !== undefined ? ovPs   : item.paymentStatus;
      var effectiveEp   = ovEp   !== undefined ? ovEp   : item.ecommercePlatform;
      var effectiveAgent = ovAgentName || item.agent || '';

    return '<div class="review-card' + (excluded ? ' excluded' : '') + '" data-abs-idx="' + absIdx + '">'
      // Title: receipt number + source badge + total
      + '<div class="rc-title" style="display:flex;align-items:center;justify-content:space-between;">'
      +   '<div style="display:flex;align-items:center;gap:6px;">'
      +     '<code style="font-size:12px;">' + esc(item.receiptNum || '') + '</code>'
      +     '<span style="display:inline-block;padding:0 6px;border-radius:8px;font-size:9px;font-weight:600;background:' + sourceColor + '22;color:' + sourceColor + ';">' + esc(item.source || '—') + '</span>'
      +   '</div>'
      +   '<span style="font-size:14px;font-weight:700;">' + fmt(item.computedTotal) + '</span>'
      + '</div>'
      // Description: customer + date
      + '<div class="rc-desc" style="display:flex;justify-content:space-between;">'
      +   '<span>' + sd(item.customer) + '</span>'
      +   '<span>' + sd(item.date) + '</span>'
      + '</div>'
      // Metadata: editors + items
      + '<div class="rc-meta">'
      // Order-level editors (2x2 grid via CSS class)
      +   '<div class="rc-editors">'
      +     '<div><label style="font-size:9px;color:var(--text-muted);display:block;margin-bottom:1px;">Agent</label>'
      +       '<div class="product-autocomplete-wrapper">'
      +         '<input type="text" class="form-control review-agent-input review-editor-input" style="font-size:10px;padding:3px 5px;" placeholder="Search agent\u2026" autocomplete="off"'
      +           ' data-rn="' + esc(item.receiptNum) + '" value="' + esc(effectiveAgent) + '"' + (excluded ? ' disabled' : '') + '>'
      +         '<input type="hidden" class="review-agent-hidden" data-rn="' + esc(item.receiptNum) + '" value="' + (ovAgentId || '') + '">'
      +         '<div class="product-dropdown" style="position:absolute;z-index:1000;"></div>'
      +       '</div></div>'
      +     '<div><label style="font-size:9px;color:var(--text-muted);display:block;margin-bottom:1px;">Payment</label>'
      +       '<select class="form-control form-select review-editor-input" style="font-size:10px;padding:3px 5px;" data-field="paymentMethod" data-rn="' + esc(item.receiptNum) + '"' + (excluded ? ' disabled' : '') + '>'
      +         _paymentOpts(effectivePm)
      +       '</select></div>'
      +     '<div><label style="font-size:9px;color:var(--text-muted);display:block;margin-bottom:1px;">Status</label>'
      +       '<select class="form-control form-select review-editor-input" style="font-size:10px;padding:3px 5px;" data-field="paymentStatus" data-rn="' + esc(item.receiptNum) + '"' + (excluded ? ' disabled' : '') + '>'
      +         '<option value="">—</option>'
      +         '<option value="PAID" ' + (effectivePs === 'PAID' ? 'selected' : '') + '>PAID</option>'
      +         '<option value="UNPAID" ' + (effectivePs === 'UNPAID' ? 'selected' : '') + '>UNPAID</option>'
      +       '</select></div>'
      +     '<div><label style="font-size:9px;color:var(--text-muted);display:block;margin-bottom:1px;">Platform</label>'
      +       '<select class="form-control form-select review-editor-input" style="font-size:10px;padding:3px 5px;" data-field="ecommercePlatform" data-rn="' + esc(item.receiptNum) + '"' + (excluded ? ' disabled' : '') + '>'
      +         '<option value="">—</option>'
      +         '<option value="SHOPEE" ' + (effectiveEp === 'SHOPEE' ? 'selected' : '') + '>SHOPEE</option>'
      +         '<option value="TIKTOK" ' + (effectiveEp === 'TIKTOK' ? 'selected' : '') + '>TIKTOK</option>'
      +         '<option value="LAZADA" ' + (effectiveEp === 'LAZADA' ? 'selected' : '') + '>LAZADA</option>'
      +       '</select></div>'
      +   '</div>'
      // Items header (only if there are items)
      +   ((items.length
        ? '<div style="font-size:9px;color:var(--text-muted);font-weight:600;text-transform:uppercase;letter-spacing:.05em;padding:2px 0;margin-top:4px;">Items</div>'
        : '') + '<div class="rc-items-scroll">' + itemsHtml + '</div>')
      + '</div>'
      // Actions
      + '<div class="rc-actions">'
      +   '<label style="display:flex;align-items:center;gap:4px;font-size:11px;cursor:pointer;user-select:none;" title="Exclude entire order from commit">'
      +     '<input type="checkbox" class="review-exclude-cb" data-type="order" data-key="' + esc(item.receiptNum) + '" ' + (excluded ? 'checked' : '') + '> Exclude this order'
      +   '</label>'
      + '</div>'
      + '</div>';
  }

  // ── Payment method dropdown options ──────────────────────────────────

  function _paymentOpts(selected) {
    var methods = ['CASH','BANK_TRANSFER','GCASH','PAYMAYA','COD'];
    return '<option value="">—</option>'
      + methods.map(function (m) {
        return '<option value="' + m + '" ' + (selected === m ? 'selected' : '') + '>' + m + '</option>';
      }).join('');
  }

  // ── Expense category dropdown helpers for review modal ──────────────

  function _expCatPrimaryOpts(selectedCode) {
    if (!_expCatData) return '<option value="">—</option>';
    var opts = '<option value="">Select category…</option>';
    (_expCatData.primaries || []).forEach(function (p) {
      opts += '<option value="' + p.code + '" ' + (selectedCode === p.code ? 'selected' : '') + '>' + p.name + '</option>';
    });
    return opts;
  }

  function _expCatSubOpts(primaryCode, selectedSubId) {
    if (!_expCatData || !primaryCode) return '<option value="">Select sub-category…</option>';
    var prim = (_expCatData.primaries || []).find(function (p) { return p.code === primaryCode; });
    var subs = prim ? (prim.subcategories || []) : [];
    var opts = '<option value="">Select sub-category…</option>';
    subs.forEach(function (s) {
      opts += '<option value="' + s.id + '" ' + (selectedSubId != null && String(selectedSubId) === String(s.id) ? 'selected' : '') + '>' + s.name + '</option>';
    });
    return opts;
  }

  function _findCatPath(categoryId) {
    if (!_expCatData || categoryId == null) return { primaryCode: '', subId: null };
    var primaries = _expCatData.primaries || [];
    for (var i = 0; i < primaries.length; i++) {
      var subs = primaries[i].subcategories || [];
      for (var j = 0; j < subs.length; j++) {
        if (String(subs[j].id) === String(categoryId)) {
          return { primaryCode: primaries[i].code, subId: subs[j].id };
        }
      }
    }
    return { primaryCode: '', subId: null };
  }

  // ── Event delegation for review card inputs ─────────────────────────

  function _setupReviewCardEvents() {
    var container = $('review-card-container');
    if (!container) return;

    // Exclude toggle
    container.querySelectorAll('.review-exclude-cb').forEach(function (cb) {
      cb.addEventListener('change', function () {
        var type = this.getAttribute('data-type');
        var key = this.getAttribute('data-key');
        var excluded = this.checked;
        if (type === 'order') {
          _getOrderOv(key).include = !excluded;
          // Toggle card opacity
          _reviewGreenItems.forEach(function (ri) {
            if (ri._receiptNum === key) ri._include = !excluded;
          });
        } else {
          _getExpenseOv(key).include = !excluded;
          _reviewGreenItems.forEach(function (ri) {
            if (ri._key === key) ri._include = !excluded;
          });
        }
        // Dim/undim the card and toggle editor disabled state
        var card = this.closest('.review-card');
        if (card) {
          card.classList.toggle('excluded', excluded);
          card.querySelectorAll('.review-editor-input').forEach(function (inp) {
            inp.disabled = excluded;
          });
        }
        _recalcGreenGate();
      });
    });

    // Per-item exclude toggle
    container.querySelectorAll('.rc-item-exclude-btn').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var rn = this.getAttribute('data-rn');
        var ri = parseInt(this.getAttribute('data-ri'));
        var iov = _getItemOv(rn, ri);
        var isCurrentlyExcluded = iov._include === false;
        var newExcluded = !isCurrentlyExcluded;
        iov._include = !newExcluded;
        _reviewGreenItems.forEach(function (gi) {
          if (gi._receiptNum === rn && gi._rowIndex === ri) gi._include = !newExcluded;
        });
        this.classList.toggle('active', newExcluded);
        var row = this.closest('.rc-item-row');
        if (row) row.classList.toggle('excluded', newExcluded);
        _recalcGreenGate();
      });
    });

    // Order-level dropdowns
    container.querySelectorAll('select[data-field]:not([data-field="exp-paymethod"])').forEach(function (sel) {
      sel.addEventListener('change', function () {
        var rn = this.getAttribute('data-rn');
        var field = this.getAttribute('data-field');
        _setOrderField(rn, field, this.value || null);
      });
    });

    // Expense payment method
    container.querySelectorAll('select[data-field="exp-paymethod"]').forEach(function (sel) {
      sel.addEventListener('change', function () {
        var key = this.getAttribute('data-key');
        _setExpenseField(key, 'paymentMethod', this.value || null);
      });
    });

    // Expense amount
    container.querySelectorAll('input[data-field="exp-amount"]').forEach(function (inp) {
      inp.addEventListener('change', function () {
        var key = this.getAttribute('data-key');
        _setExpenseField(key, 'amount', parseFloat(this.value) || null);
      });
    });

    // Expense notes
    container.querySelectorAll('input[data-field="exp-notes"]').forEach(function (inp) {
      inp.addEventListener('change', function () {
        var key = this.getAttribute('data-key');
        _setExpenseField(key, 'notes', this.value || null);
      });
    });

    // Expense category — primary dropdown → populate sub-category
    container.querySelectorAll('select[data-field="exp-cat-primary"]').forEach(function (sel) {
      sel.addEventListener('change', function () {
        var key = this.getAttribute('data-key');
        var subSel = this.parentElement.querySelector('select[data-field="exp-cat-sub"]');
        if (subSel) {
          subSel.innerHTML = _expCatSubOpts(this.value, '');
        }
        _setExpenseField(key, 'categoryId', null);
        _reviewGreenItems.forEach(function (gi) {
          if (gi._key === key) gi._green = false;
        });
        _recalcGreenGate();
      });
    });

    // Expense category — sub-category dropdown → store categoryId override
    container.querySelectorAll('select[data-field="exp-cat-sub"]').forEach(function (sel) {
      sel.addEventListener('change', function () {
        var key = this.getAttribute('data-key');
        var subId = this.value ? parseInt(this.value) : null;
        _setExpenseField(key, 'categoryId', subId);
        _reviewGreenItems.forEach(function (gi) {
          if (gi._key === key) gi._green = subId != null;
        });
        _recalcGreenGate();
      });
    });

    // Item numeric fields (qty, unitPrice, basePrice, opPerUnit)
    container.querySelectorAll('.review-item-num').forEach(function (inp) {
      inp.addEventListener('change', function () {
        var rn = this.getAttribute('data-rn');
        var ri = parseInt(this.getAttribute('data-ri'));
        var field = this.getAttribute('data-field');
        var val = this.value !== '' ? parseFloat(this.value) : null;
        _getItemOv(rn, ri)[field] = val;
      });
    });

    // Item product autocomplete (setup for each item)
    container.querySelectorAll('.review-prod-input').forEach(function (input) {
      _setupReviewProductAutocomplete(input);
    });

    // Agent autocomplete (setup for each order card)
    container.querySelectorAll('.review-agent-input').forEach(function (input) {
      _setupReviewAgentAutocomplete(input);
    });
  }

  // ── Product autocomplete for review card items ─────────────────────

  function _setupReviewProductAutocomplete(input) {
    var wrapper = input.closest('.product-autocomplete-wrapper');
    if (!wrapper) return;
    var dropdown = wrapper.querySelector('.product-dropdown');
    var hidden = wrapper.querySelector('.review-prod-hidden');
    var rn = input.getAttribute('data-rn');
    var ri = parseInt(input.getAttribute('data-ri'));

    if (!dropdown || !hidden) return;

    // Lazy-load product cache if empty
    if (!appState.cachedProducts || !appState.cachedProducts.length) {
      loadProducts();
    }

    var showDropdown = function (filter) {
      var prods = appState.cachedProducts || [];
      var t = (filter || '').toLowerCase().trim();
      var filtered = t.length === 0 ? prods : prods.filter(function (p) {
        return p.name.toLowerCase().includes(t) || (p.productCode && p.productCode.toLowerCase().includes(t));
      });
      if (filtered.length === 0) {
        dropdown.innerHTML = '<div class="product-dropdown-item" style="color:#999;cursor:default;">No products found</div>';
      } else {
        dropdown.innerHTML = filtered.map(function (p) {
          var total = (p.stockWh1 || 0) + (p.stockWh2 || 0) + (p.stockWh3 || 0);
          return '<div class="product-dropdown-item" data-id="' + p.id + '" data-name="' + escapeHtml(p.name) + '" data-price="' + p.unitPrice + '">'
            + '<strong>' + escapeHtml(p.name) + '</strong>'
            + ' <span style="font-size:10px;color:var(--text-muted);">[' + escapeHtml(p.productCode || '') + '] \u00b7 ' + total + ' pcs</span>'
            + ' <span style="font-size:10px;font-weight:600;color:#10B981;">\u20B1' + Number(p.unitPrice).toFixed(2) + '</span>'
            + '</div>';
        }).join('');
      }
      dropdown.classList.add('show');
    };

    input.addEventListener('input', function () {
      showDropdown(this.value);
      // Clear hidden when user types
      hidden.value = '';
      // Update green status
      _updateItemGreen(rn, ri, false);
    });

    input.addEventListener('focus', function () {
      showDropdown(this.value);
    });

    dropdown.addEventListener('click', function (e) {
      var item = e.target.closest('.product-dropdown-item[data-id]');
      if (!item) return;
      var pid = item.getAttribute('data-id');
      var pname = item.getAttribute('data-name');
      input.value = pname;
      hidden.value = pid;
      dropdown.classList.remove('show');
      // Persist override
      _getItemOv(rn, ri).productId = parseInt(pid);
      _getItemOv(rn, ri).productName = pname;
      // Update green status
      _updateItemGreen(rn, ri, true);
    });

    document.addEventListener('click', function (e) {
      if (!input.contains(e.target) && !dropdown.contains(e.target)) {
        dropdown.classList.remove('show');
      }
    });
  }

  // ── Agent autocomplete for review card order-level ─────────────────

  function _setupReviewAgentAutocomplete(input) {
    var wrapper = input.closest('.product-autocomplete-wrapper');
    if (!wrapper) return;
    var dropdown = wrapper.querySelector('.product-dropdown');
    var hidden = wrapper.querySelector('.review-agent-hidden');
    var rn = input.getAttribute('data-rn');

    if (!dropdown || !hidden) return;

    var showDropdown = function (filter) {
      var agents = _cachedAgents || [];
      var t = (filter || '').toLowerCase().trim();
      var filtered = t.length === 0 ? agents : agents.filter(function (a) {
        return a.fullName.toLowerCase().includes(t) || a.agentCode.toLowerCase().includes(t);
      });
      if (filtered.length === 0) {
        dropdown.innerHTML = '<div class="product-dropdown-item" style="color:#999;cursor:default;">No agents found</div>';
      } else {
        dropdown.innerHTML = filtered.map(function (a) {
          return '<div class="product-dropdown-item" data-id="' + a.id + '" data-name="' + escapeHtml(a.fullName) + '">'
            + '<strong>' + escapeHtml(a.fullName) + '</strong>'
            + ' <span style="font-size:10px;color:var(--text-muted);">(' + escapeHtml(a.agentCode || '') + ') ' + escapeHtml(a.territory || '') + '</span>'
            + '</div>';
        }).join('');
      }
      dropdown.classList.add('show');
    };

    input.addEventListener('input', function () {
      showDropdown(this.value);
      hidden.value = '';
      _setOrderField(rn, 'agentId', null);
    });

    input.addEventListener('focus', function () {
      showDropdown(this.value);
    });

    dropdown.addEventListener('click', function (e) {
      var item = e.target.closest('.product-dropdown-item[data-id]');
      if (!item) return;
      var aid = item.getAttribute('data-id');
      var aname = item.getAttribute('data-name');
      input.value = aname;
      hidden.value = aid;
      dropdown.classList.remove('show');
      _setOrderField(rn, 'agentId', parseInt(aid));
      _setOrderField(rn, 'agentName', aname);
    });

    document.addEventListener('click', function (e) {
      if (!input.contains(e.target) && !dropdown.contains(e.target)) {
        dropdown.classList.remove('show');
      }
    });
  }

  // ── Update a single item's green status ────────────────────────────

  function _updateItemGreen(receiptNum, rowIndex, green) {
    _reviewGreenItems.forEach(function (ri) {
      if (ri._receiptNum === receiptNum && ri._rowIndex === rowIndex) {
        ri._green = green;
      }
    });
    _recalcGreenGate();
  }

  // ── Pagination ──────────────────────────────────────────────────────

  window.reviewPagePrev = function () {
    if (_reviewPage > 1) { _reviewPage--; _renderReviewPage(); _setupReviewCardEvents(); }
  };

  window.reviewPageNext = function () {
    var data = _importReviewData;
    if (!data) return;
    var total = (data.valid || []).length;
    var pages = Math.max(1, Math.ceil(total / _reviewPerPage));
    if (_reviewPage < pages) { _reviewPage++; _renderReviewPage(); _setupReviewCardEvents(); }
  };

  function renderImportIssues(data) {
    var issues       = data.stockIssues       || [];
    var closedDates  = data.reportClosedDates || [];
    var section = $('import-issues-section');
    var tbody   = $('import-issues-tbody');
    var count   = $('import-issues-count');
    var commitBtn = document.getElementById('import-commit-btn');
    if (!section || !tbody) return;

    var allIssues = [];
    issues.forEach(function (i) {
      allIssues.push({
        receiptNum: i.receiptNum || '—',
        product:    i.productName || i.productCode || '—',
        message:    i.message || ''
      });
    });
    closedDates.forEach(function (d) {
      allIssues.push({
        receiptNum: '—',
        product:    '—',
        message: 'Date ' + d + ' already has a daily report — orders will be marked as late recorded'
      });
    });

    if (allIssues.length) {
      section.style.display = 'block';
      count.textContent = allIssues.length;
      tbody.innerHTML = allIssues.map(function (i) {
        return '<tr>'
          + '<td><code style="font-size:11px;">' + escapeHtml(i.receiptNum) + '</code></td>'
          + '<td>' + escapeHtml(i.product) + '</td>'
          + '<td style="font-size:11px;color:#DC2626;white-space:normal;">' + escapeHtml(i.message) + '</td>'
          + '</tr>';
      }).join('');
    } else {
      section.style.display = 'none';
    }
    if (commitBtn) commitBtn.disabled = !data.allClear;
  }

  window.validateImport = async function () {
    if (!_importSessionToken) { showToast('No active upload session. Please upload a file first.', 'error'); return; }
    var token = localStorage.getItem('rrbm_token');
    if (!token) return;
    try {
      var res = await fetch(API_BASE + '/api/import/validate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
        body: JSON.stringify({ sessionToken: _importSessionToken })
      });
      var data = await res.json().catch(function () { return {}; });
      if (res.ok) {
        renderImportIssues(data);
        if (data.allClear) {
          showToast('Validation passed — all clear!', 'success');
        } else {
          showToast(data.stockIssues.length + ' stock issue(s) found. Fix the file and re-upload.', 'warning');
        }
      } else {
        showToast(data.error || data.message || 'Validation failed.', 'error');
      }
    } catch (e) { showToast('Connection error.', 'error'); }
  };

  window.commitImport = async function () {
    if (!_importSessionToken) { showToast('No active upload session. Please upload a file first.', 'error'); return; }
    var token = localStorage.getItem('rrbm_token');
    if (!token) return;

    // Build overrides payload from review state
    var ordersArr = [];
    Object.keys(_reviewOverrides.orders).forEach(function (rn) {
      var ov = _reviewOverrides.orders[rn];
      if (ov.include !== false && !Object.keys(ov.items).length && !ov.paymentMethod && !ov.paymentStatus && !ov.ecommercePlatform && !ov.agentId) return;
      var orderOverride = {
        receiptNum: rn,
        include: ov.include !== false,
        paymentMethod: ov.paymentMethod || undefined,
        paymentStatus: ov.paymentStatus || undefined,
        ecommercePlatform: ov.ecommercePlatform || undefined,
        agentId: ov.agentId || undefined,
        items: []
      };
      Object.keys(ov.items).forEach(function (ri) {
        var iov = ov.items[ri];
        var itemOverride = { rowIndex: parseInt(ri) };
        if (iov.qty != null) itemOverride.qty = iov.qty;
        if (iov.unitPrice != null) itemOverride.unitPrice = iov.unitPrice;
        if (iov.basePrice != null) itemOverride.basePrice = iov.basePrice;
        if (iov.opPerUnit != null) itemOverride.opPerUnit = iov.opPerUnit;
        if (iov.productId != null) itemOverride.productId = iov.productId;
        orderOverride.items.push(itemOverride);
      });
      ordersArr.push(orderOverride);
    });

    var expensesArr = [];
    Object.keys(_reviewOverrides.expenses).forEach(function (refNum) {
      var ov = _reviewOverrides.expenses[refNum];
      if (ov.include !== false && !ov.amount && !ov.notes && !ov.paymentMethod && !ov.categoryId) return;
      var expenseOverride = {
        referenceNumber: refNum,
        include: ov.include !== false
      };
      if (ov.paymentMethod !== undefined) expenseOverride.paymentMethod = ov.paymentMethod;
      if (ov.amount !== undefined) expenseOverride.amount = ov.amount;
      if (ov.notes !== undefined) expenseOverride.notes = ov.notes;
      if (ov.categoryId !== undefined) expenseOverride.categoryId = ov.categoryId;
      expensesArr.push(expenseOverride);
    });

    var payload = {
      sessionToken: _importSessionToken,
      adminSecurityKey: _importSecurityKey || ''
    };
    if (ordersArr.length) payload.overrides = { orders: ordersArr };
    if (expensesArr.length) {
      if (!payload.overrides) payload.overrides = {};
      payload.overrides.expenses = expensesArr;
    }

    try {
      showToast('Committing import\u2026', 'info');
      var res = await fetch(API_BASE + '/api/import/commit', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
        body: JSON.stringify(payload)
      });
      var data = await res.json().catch(function () { return {}; });
      if (res.ok) {
        _importSessionToken = null;
        _importReviewData = null;
        closeModal('modal-import-review');
        // Collect unique dates from committed orders for manual close
        var orderDates = (data.committedOrders || []).map(function (o) { return o.date; }).filter(Boolean);
        var uniqueDates = [...new Set(orderDates)];
        data._closeDates = uniqueDates;
        showImportResultModal(data);
        loadImportHistory();
      } else {
        showToast(data.error || data.message || 'Commit failed.', 'error');
      }
    } catch (e) { showToast('Connection error.', 'error'); }
  };

  window.closeImportReports = async function () {
    var datesEl = $('import-close-dates');
    if (!datesEl) return;
    var dates = datesEl.getAttribute('data-dates');
    if (!dates) return;
    var token = localStorage.getItem('rrbm_token');
    if (!token) return;
    try {
      var res = await fetch(API_BASE + '/api/import/close', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
        body: JSON.stringify({ dates: JSON.parse(dates), adminSecurityKey: _importSecurityKey || '' })
      });
      var result = await res.json().catch(function () { return {}; });
      if (res.ok) {
        var reports = result.closedReports || [];
        var ok = reports.filter(function (r) { return r.status === 'created'; }).length;
        var fail = reports.filter(function (r) { return r.status === 'failed'; }).length;
        showToast('Reports closed: ' + ok + ' created' + (fail ? ', ' + fail + ' failed' : ''), fail > 0 ? 'error' : 'success');
        loadImportHistory();
      } else {
        showToast(result.error || 'Close failed.', 'error');
      }
    } catch (e) { showToast('Connection error.', 'error'); }
  };

  function showImportResultModal(data) {
    var orders   = data.committedOrders   || [];
    var expenses = data.committedExpenses || [];
    var errors   = data.errors            || [];
    var skipped  = data.skipped           || 0;
    var fmt = function (n) { return n != null ? '₱' + Number(n).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '—'; };

    // Build committed orders rows
    var orderRows = orders.length ? orders.map(function (o) {
      var lateTag = o.lateImported
        ? ' <span style="display:inline-block;padding:1px 6px;border-radius:10px;font-size:10px;font-weight:600;background:#FEF3C7;color:#92400E;">⚠ Late recorded</span>'
        : '';
      return '<tr>'
        + '<td><code style="font-size:11px;">' + escapeHtml(o.receiptNum || '—') + '</code></td>'
        + '<td><code style="font-size:11px;">' + escapeHtml(o.orderId    || '—') + '</code></td>'
        + '<td>' + escapeHtml(o.date     || '—') + '</td>'
        + '<td>' + escapeHtml(o.customer || '—') + '</td>'
        + '<td style="text-align:right;">' + fmt(o.total) + '</td>'
        + '<td>' + lateTag + '</td>'
        + '</tr>';
    }).join('') : '<tr><td colspan="6" style="color:#999;text-align:center;padding:10px;">None</td></tr>';

    // Build committed expenses rows
    var expenseRows = expenses.length ? expenses.map(function (e) {
      var lateTag = e.lateImported
        ? ' <span style="display:inline-block;padding:1px 6px;border-radius:10px;font-size:10px;font-weight:600;background:#FEF3C7;color:#92400E;">⚠ Late recorded</span>'
        : '';
      return '<tr>'
        + '<td>' + escapeHtml(e.date || '—') + '</td>'
        + '<td style="text-align:right;">' + fmt(e.amount) + '</td>'
        + '<td>' + lateTag + '</td>'
        + '</tr>';
    }).join('') : '<tr><td colspan="3" style="color:#999;text-align:center;padding:10px;">None</td></tr>';

    // Build error rows (only shown if errors exist)
    var errorSection = '';
    if (errors.length) {
      var errorRows = errors.map(function (e) {
        var ref = e.receiptNum || e.date || '—';
        return '<tr>'
          + '<td style="color:#EF4444;font-weight:600;">' + escapeHtml(e.section || '—') + '</td>'
          + '<td><code style="font-size:11px;">' + escapeHtml(ref) + '</code></td>'
          + '<td style="font-size:11px;color:#EF4444;white-space:normal;">' + escapeHtml(e.error || 'Unknown error') + '</td>'
          + '</tr>';
      }).join('');
      errorSection = '<div style="margin-top:16px;">'
        + '<div style="font-weight:600;color:#EF4444;margin-bottom:6px;"><i class="ti ti-alert-triangle"></i> Failed (' + errors.length + ')</div>'
        + '<div style="max-height:200px;overflow-y:auto;border:1px solid #FCA5A5;border-radius:6px;">'
        + '<table style="width:100%;border-collapse:collapse;font-size:12px;">'
        + '<thead><tr style="background:#FEF2F2;">'
        + '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #FCA5A5;">Section</th>'
        + '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #FCA5A5;">Receipt / Date</th>'
        + '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #FCA5A5;">Reason</th>'
        + '</tr></thead>'
        + '<tbody>' + errorRows + '</tbody>'
        + '</table></div></div>';
    }

    var skippedNote = skipped > 0 ? '<span style="color:#6B7280;font-size:12px;"> &nbsp;·&nbsp; ' + skipped + ' skipped</span>' : '';

    var modalHtml = '<div id="import-result-overlay" style="position:fixed;inset:0;background:rgba(0,0,0,0.5);z-index:9999;display:flex;align-items:center;justify-content:center;padding:16px;">'
      + '<div style="background:#fff;border-radius:12px;box-shadow:0 20px 60px rgba(0,0,0,0.25);width:100%;max-width:720px;max-height:90vh;display:flex;flex-direction:column;">'
      // Header
      + '<div style="padding:16px 20px;border-bottom:1px solid #E5E7EB;display:flex;align-items:center;justify-content:space-between;">'
      + '<div style="font-weight:700;font-size:16px;color:#111827;"><i class="ti ti-circle-check" style="color:#10B981;margin-right:6px;"></i>Import Complete</div>'
      + '<button onclick="document.getElementById(\'import-result-overlay\').remove();" style="background:none;border:none;cursor:pointer;font-size:20px;color:#6B7280;line-height:1;">✕</button>'
      + '</div>'
      // Body
      + '<div style="padding:20px;overflow-y:auto;flex:1;">'
      // Committed orders
      + '<div style="font-weight:600;color:#059669;margin-bottom:6px;"><i class="ti ti-check"></i> Committed — Orders (' + orders.length + ')' + skippedNote + '</div>'
      + '<div style="max-height:200px;overflow-y:auto;border:1px solid #D1FAE5;border-radius:6px;margin-bottom:12px;">'
      + '<table style="width:100%;border-collapse:collapse;font-size:12px;">'
      + '<thead><tr style="background:#ECFDF5;">'
      + '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #A7F3D0;">Receipt#</th>'
      + '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #A7F3D0;">Order ID</th>'
      + '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #A7F3D0;">Date</th>'
      + '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #A7F3D0;">Customer</th>'
      + '<th style="padding:6px 8px;text-align:right;border-bottom:1px solid #A7F3D0;">Total</th>'
      + '<th style="padding:6px 8px;border-bottom:1px solid #A7F3D0;">Note</th>'
      + '</tr></thead>'
      + '<tbody>' + orderRows + '</tbody>'
      + '</table></div>'
      // Committed expenses
      + '<div style="font-weight:600;color:#059669;margin-bottom:6px;"><i class="ti ti-check"></i> Committed — Expenses (' + expenses.length + ')</div>'
      + '<div style="max-height:120px;overflow-y:auto;border:1px solid #D1FAE5;border-radius:6px;">'
      + '<table style="width:100%;border-collapse:collapse;font-size:12px;">'
      + '<thead><tr style="background:#ECFDF5;">'
      + '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #A7F3D0;">Date</th>'
      + '<th style="padding:6px 8px;text-align:right;border-bottom:1px solid #A7F3D0;">Amount</th>'
      + '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #A7F3D0;">Notes</th>'
      + '</tr></thead>'
      + '<tbody>' + expenseRows + '</tbody>'
      + '</table></div>'
      + errorSection
      + (data._closeDates && data._closeDates.length
          ? '<div style="margin-top:12px;padding:10px 14px;background:#EFF6FF;border:1px solid #BFDBFE;border-radius:8px;font-size:12px;color:#1E40AF;">'
            + '<i class="ti ti-info-circle"></i> '
            + 'Daily reports for ' + data._closeDates.length + ' date(s) need to be closed. '
            + 'Click <strong>"Close Daily Reports"</strong> below to generate them.'
          + '</div>'
          : '')
      + '</div>'
      // Footer
      + '<div style="padding:12px 20px;border-top:1px solid #E5E7EB;display:flex;justify-content:space-between;align-items:center;">'
      + '<div style="font-size:12px;color:#6B7280;">'
      + '  <button id="import-close-btn" class="btn btn-secondary btn-sm" onclick="closeImportReports()" style="display:none;">'
      + '    <i class="ti ti-report-analytics"></i> Close Daily Reports'
      + '  </button>'
      + '</div>'
      + '<button onclick="document.getElementById(\'import-result-overlay\').remove();" class="btn btn-primary" style="min-width:80px;">Close</button>'
      + '</div>'
      + '</div></div>';

    var overlay = document.createElement('div');
    overlay.innerHTML = modalHtml;
    document.body.appendChild(overlay.firstChild);

    // Close on backdrop click
    document.getElementById('import-result-overlay').addEventListener('click', function (ev) {
      if (ev.target === this) this.remove();
    });

    // ── Show Close Reports button if there are dates to close ────────
    var closeDates = data._closeDates;
    if (closeDates && closeDates.length) {
      var closeBtn = document.getElementById('import-close-btn');
      if (closeBtn) {
        closeBtn.style.display = 'inline-flex';
        // Also store dates in a hidden element for the close handler
        var hiddenDates = document.createElement('input');
        hiddenDates.type = 'hidden';
        hiddenDates.id = 'import-close-dates';
        hiddenDates.setAttribute('data-dates', JSON.stringify(closeDates));
        document.getElementById('import-result-overlay').appendChild(hiddenDates);
      }
    }
  }

  // ================================================================
  // Transaction Ledger
  // ================================================================

  window.loadTransactions = async function () {
    var today = new Date().toISOString().split('T')[0];
    var startEl = $('ledger-start');
    var endEl   = $('ledger-end');
    var typeEl  = $('ledger-type');
    if (startEl && !startEl.value) startEl.value = today;
    if (endEl   && !endEl.value)   endEl.value   = today;
    var start = startEl ? startEl.value : today;
    var end   = endEl   ? endEl.value   : today;
    var type  = typeEl  ? typeEl.value  : '';

    var tbody = $('ledger-tbody');
    if (!tbody) return;
    tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);padding:24px;">Loading…</td></tr>';

    var params = '?start=' + start + '&end=' + end + (type ? '&type=' + encodeURIComponent(type) : '');
    try {
      var listRes   = fetch(API_BASE + '/api/transactions/ledger'        + params, { headers: authHeaders() });
      var reportRes = fetch(API_BASE + '/api/transactions/ledger/report?start=' + start + '&end=' + end, { headers: authHeaders() });
      var results   = await Promise.all([listRes, reportRes]);
      var lRes = results[0];
      var rRes = results[1];

      if (rRes.ok) {
        var rpt = await rRes.json();
        var summaryCard = $('ledger-summary-card');
        var summaryBody = $('ledger-summary-body');
        if (summaryCard && summaryBody) {
          summaryCard.style.display = '';
          var fmt = function (n) { return '₱' + Number(n || 0).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 }); };
          var netColor = Number(rpt.netSales || 0) >= 0 ? '#10B981' : '#EF4444';
          var voidRet = Number(rpt.voidTotal || 0) + Number(rpt.returnTotal || 0);
          summaryBody.innerHTML =
            '<div style="display:flex;gap:24px;flex-wrap:wrap;">' +
              '<div><div style="font-size:11px;color:var(--text-muted);margin-bottom:2px;">Gross Sales</div>' +
                   '<div style="font-weight:700;color:#10B981;">' + fmt(rpt.grossSales) + '</div></div>' +
              '<div><div style="font-size:11px;color:var(--text-muted);margin-bottom:2px;">Voids / Returns</div>' +
                   '<div style="font-weight:700;color:#EF4444;">' + fmt(voidRet) + '</div></div>' +
              '<div><div style="font-size:11px;color:var(--text-muted);margin-bottom:2px;">Adjustments</div>' +
                   '<div style="font-weight:700;">' + fmt(rpt.adjustmentsTotal) + '</div></div>' +
              '<div><div style="font-size:11px;color:var(--text-muted);margin-bottom:2px;">Net Sales</div>' +
                   '<div style="font-weight:700;font-size:18px;color:' + netColor + ';">' + fmt(rpt.netSales) + '</div></div>' +
              '<div><div style="font-size:11px;color:var(--text-muted);margin-bottom:2px;">Total Entries</div>' +
                   '<div style="font-weight:700;">' + (rpt.totalCount || 0) + '</div></div>' +
            '</div>';
        }
      }

      if (!lRes.ok) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:red;padding:24px;">Failed to load ledger.</td></tr>';
        return;
      }
      var txns = await lRes.json();
      if (!Array.isArray(txns) || txns.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-muted);padding:24px;">No transactions in this range.</td></tr>';
        return;
      }
      var typeColors = { SALE: '#10B981', VOID: '#EF4444', RETURN: '#F59E0B', REFUND: '#F59E0B', ADJUSTMENT: '#6366F1', DISCOUNT: '#F59E0B' };
      tbody.innerHTML = txns.map(function (t) {
        var col      = typeColors[t.transactionType] || '#6B7280';
        var amtStyle = Number(t.amount || 0) >= 0 ? 'color:#10B981;' : 'color:#EF4444;';
        var ref      = (t.referenceType || '') + (t.referenceId ? ' ' + t.referenceId : '');
        return '<tr>' +
          '<td><code style="font-size:11px;">' + escapeHtml(t.transactionCode || '') + '</code></td>' +
          '<td style="white-space:nowrap;">' + escapeHtml(t.effectiveDate || '') + '</td>' +
          '<td><span style="padding:2px 8px;border-radius:10px;font-size:11px;font-weight:600;' +
               'background:' + col + '22;color:' + col + ';">' + escapeHtml(t.transactionType || '') + '</span></td>' +
          '<td style="text-align:right;font-weight:600;' + amtStyle + '">₱' +
               Number(t.amount || 0).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + '</td>' +
          '<td style="font-size:12px;">' + escapeHtml(ref) + '</td>' +
          '<td style="font-size:12px;color:var(--text-muted);max-width:200px;overflow:hidden;text-overflow:ellipsis;' +
               'white-space:nowrap;" title="' + escapeHtml(t.notes || '') + '">' + escapeHtml(t.notes || '') + '</td>' +
          '</tr>';
      }).join('');
    } catch (err) {
      tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:red;padding:24px;">Error loading ledger.</td></tr>';
    }
  };

  window.toggleLedgerAdjForm = function () {
    var form = $('ledger-adj-form');
    if (form) form.style.display = form.style.display === 'none' ? '' : 'none';
  };

  window.submitManualAdjustment = async function () {
    var amount  = ($('adj-amount')   && $('adj-amount').value.trim())   || '';
    var reason  = ($('adj-reason')   && $('adj-reason').value.trim())   || '';
    var orderId = ($('adj-order-id') && $('adj-order-id').value.trim()) || '';
    if (!amount || !reason) { showToast('Amount and reason are required.', 'error'); return; }
    try {
      var body = { amount: amount, reason: reason };
      if (orderId) body.orderId = orderId;
      var res  = await fetch(API_BASE + '/api/transactions/adjustment', {
        method: 'POST',
        headers: Object.assign({}, authHeaders(), { 'Content-Type': 'application/json' }),
        body: JSON.stringify(body)
      });
      var data = await res.json();
      if (!res.ok) { showToast(data.message || 'Failed to post adjustment.', 'error'); return; }
      showToast('Adjustment posted.', 'success');
      if ($('adj-amount'))   $('adj-amount').value   = '';
      if ($('adj-reason'))   $('adj-reason').value   = '';
      if ($('adj-order-id')) $('adj-order-id').value = '';
      loadTransactions();
    } catch (err) {
      showToast('Error posting adjustment.', 'error');
    }
  };

})();
