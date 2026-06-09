  window.openCommissionPeriodModal = async function () {
    var modalBody = $('commission-period-modal-body');
    if (!modalBody) return;
    modalBody.innerHTML = '<div style="text-align:center;color:var(--text-muted);padding:24px;">Loading periodsâ€¦</div>';
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
        return '<span style="padding:2px 8px;border-radius:10px;font-size:11px;font-weight:600;' + (map[s]||'background:#F3F4F6;color:#6B7280;') + '">' + (s||'â€”') + '</span>';
      };

      var rows = periods.length ? periods.map(function (p) {
        var closeBtn   = p.status === 'OPEN'
          ? '<button class="btn btn-sm btn-outline" style="font-size:10px;padding:3px 8px;" onclick="closePeriod(' + p.id + ')"><i class="ti ti-lock"></i> Close</button>' : '';
        var releaseBtn = p.status === 'CLOSED'
          ? '<button class="btn btn-sm btn-outline" style="font-size:10px;padding:3px 8px;" onclick="releasePeriod(' + p.id + ')"><i class="ti ti-affiliate"></i> Release</button>' : '';
        return '<tr>' +
          '<td><code style="font-size:11px;">' + escapeHtml(p.periodCode||'') + '</code></td>' +
          '<td style="font-size:11px;">' + (p.startDate||'â€”') + ' â€” ' + (p.endDate||'â€”') + '</td>' +
          '<td>' + statusBadge(p.status) + '</td>' +
          '<td style="display:flex;gap:4px;">' + closeBtn + releaseBtn + '</td>' +
          '</tr>';
      }).join('') : '<tr><td colspan="4" style="text-align:center;color:#999;padding:16px;">No commission periods found.</td></tr>';

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

  window.downloadStatement = async function (agentId, periodId) {
    var fmt = (document.getElementById('stmt-export-format') || {}).value || 'pdf';
    var url = API_BASE + '/commissions/periods/' + periodId + '/agents/' + agentId +
