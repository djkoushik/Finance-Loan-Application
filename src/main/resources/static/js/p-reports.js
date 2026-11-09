'use strict';
/* Reports. Most are described by a small config and share one page builder. */

const money = { align: 'r', fmt: fmt.money };
const COL = {
  no: { key: '#', label: 'S.No', align: 'c' },
  code: { key: 'debtorCode', label: 'Debtor Code' },
  name: { key: 'debtorName', label: 'Debtor Name' },
  area: { key: 'street', label: 'Area' },
  advDate: { key: 'dateOfAdvance', label: 'Date of Advance', fmt: fmt.date }
};
function mcol(key, label, total) { return { key: key, label: label, align: 'r', fmt: fmt.money, total: !!total }; }

function reportPage(cfg) {
  route(cfg.path, cfg.title, function (main) {
    const f = {};
    const row = h('div', { class: 'row' });
    if (cfg.date) { f.date = h('input', { type: 'date', value: fmt.today() }); row.appendChild(field('Date', f.date, { required: true })); }
    if (cfg.range) {
      const t = fmt.today();
      f.from = h('input', { type: 'date', value: t.slice(0, 8) + '01' });
      f.to = h('input', { type: 'date', value: t });
      row.appendChild(field('From date', f.from, { required: true }));
      row.appendChild(field('To date', f.to, { required: true }));
    }
    if (cfg.area) { f.area = areaSelect(true); row.appendChild(field('Area', f.area)); }
    if (cfg.order) {
      f.order = radioGroup('ord', [{ value: 'name', label: 'Order by name' }, { value: 'account', label: 'Order by account number' }], 'name');
      row.appendChild(field('Order', f.order));
    }
    const gen = h('button', { class: 'btn', type: 'button' }, 'Generate Report');
    const pdf = h('button', { class: 'btn secondary', type: 'button' }, 'Export to PDF');
    const xls = h('button', { class: 'btn secondary', type: 'button' }, 'Export to Excel');
    const prt = h('button', { class: 'btn secondary', type: 'button', onclick: function () { window.print(); } }, 'Print');
    row.appendChild(gen);
    const out = h('div');
    const tools = h('div', { class: 'actions no-print hidden' }, pdf, xls, prt);
    main.appendChild(h('div', { class: 'card no-print' }, row));
    main.appendChild(out);
    main.appendChild(tools);

    let rows = null;
    let params = null;
    const read = function () {
      const p = {};
      Object.keys(f).forEach(function (k) { p[k] = f[k].value === undefined || typeof f[k].value === 'function' ? f[k].value() : f[k].value; });
      if (cfg.date && !p.date) throw new Error('Please choose a date');
      if (cfg.range) {
        if (!p.from || !p.to) throw new Error('Please choose the from and to dates');
        if (p.to < p.from) throw new Error('To date cannot be before From date');
      }
      return p;
    };
    const subtitle = function (p) {
      const parts = [];
      if (p.date) parts.push('Date: ' + fmt.date(p.date));
      if (p.from) parts.push('From ' + fmt.date(p.from) + ' to ' + fmt.date(p.to));
      if (cfg.area) parts.push('Area: ' + (p.area || 'All'));
      return parts.join('   ');
    };
    gen.addEventListener('click', function () {
      busy(gen, async function () {
        params = read();
        rows = await GET(cfg.endpoint, params);
        clear(out);
        out.appendChild(h('h2', { class: 'no-print', style: 'margin-top:0' }, cfg.title + (subtitle(params) ? ' - ' + subtitle(params) : '')));
        out.appendChild(dataTable(cfg.columns, rows, { empty: cfg.empty || 'No records found for the selected criteria' }));
        tools.classList.toggle('hidden', !rows.length);
      });
    });
    const exporter = function (btn, kind) {
      btn.addEventListener('click', function () {
        busy(btn, async function () {
          if (!rows) throw new Error('Generate the report first');
          const spec = tableSpec(cfg.columns, rows);
          spec.title = cfg.title;
          spec.subtitle = subtitle(params);
          await downloadExport(kind, spec);
        });
      });
    };
    exporter(pdf, 'pdf');
    exporter(xls, 'csv');
    if (!cfg.manual) gen.click();
  });
}

/* ---------------------------------------------------------------- loans statements */
/* Per-date summary (10 dates at a time) with an expandable debtor panel inside each row. */
route('r-loans', 'Loans Statements', function (main) {
  const PAGE = 10;
  const t = fmt.today();
  const from = h('input', { type: 'date', value: t.slice(0, 8) + '01' });
  const to = h('input', { type: 'date', value: t });
  const area = areaSelect(true);
  const gen = h('button', { class: 'btn ls-go', type: 'button' }, 'Generate Report');
  const CHEVRON = '<svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><path d="M7 4l6 6-6 6" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  const rupee = function (n) { return '₹ ' + fmt.money(n || 0); };
  const initials = function (name) {
    const p = String(name || '?').trim().split(/\s+/);
    return ((p[0] || '?')[0] + (p.length > 1 ? p[1][0] : '')).toUpperCase();
  };
  const tile = function (cls, label) {
    const v = h('div', { class: 'ls-tile-v' }, '-');
    return { v: v, el: h('div', { class: 'ls-tile ' + cls }, h('div', { class: 'ls-tile-l' }, label), v) };
  };

  main.appendChild(h('p', { class: 'ls-lead no-print' }, 'Advances given day by day. Open a date to see which debtors received a loan.'));
  main.appendChild(h('div', { class: 'ls-filters no-print' },
    field('From Date', from, { required: true }), field('To Date', to, { required: true }), field('Area', area), gen));

  const tDays = tile('t-navy', 'Dates with advances');
  const tAcc = tile('t-teal', 'Loans given');
  const tTotal = tile('t-gold', 'Total advanced');
  const tMax = tile('t-plum', 'Highest single day');
  const tiles = h('div', { class: 'ls-tiles hidden' }, tDays.el, tAcc.el, tTotal.el, tMax.el);
  main.appendChild(tiles);
  const out = h('div', { class: 'ls-out' });
  main.appendChild(out);

  let q = null;       // criteria of the last Generate, so Next keeps the same filter
  let page = 0;       // zero-based page currently shown
  let totalDays = 0;
  let maxDay = 1;
  let tbody = null;
  let nextBtn = null;
  let prevBtn = null;
  let status = null;
  let bar = null;

  function setProgress(shown) {
    const first = page * PAGE + 1;
    const pages = Math.max(1, Math.ceil(totalDays / PAGE));
    status.textContent = 'Showing ' + first + '–' + (first + shown - 1) + ' of ' + totalDays + ' dates · Page ' + (page + 1) + ' of ' + pages;
    bar.style.width = Math.round((page * PAGE + shown) * 100 / totalDays) + '%';
    prevBtn.disabled = page === 0;
    nextBtn.disabled = (page + 1) * PAGE >= totalDays;
  }

  function detailPanel(date, rows) {
    const sum = rows.reduce(function (a, r) { return a + (Number(r.amountOfAdvance) || 0); }, 0);
    if (!rows.length) return h('div', { class: 'ls-panel' }, h('div', { class: 'ls-empty-sm' }, 'No debtors found for this date'));
    const body = h('tbody');
    rows.forEach(function (r) {
      body.appendChild(h('tr', null,
        h('td', null, h('div', { class: 'ls-person' },
          h('span', { class: 'ls-avatar' }, initials(r.debtorName)),
          h('span', null, h('span', { class: 'ls-pname' }, r.debtorName), h('span', { class: 'ls-pcode' }, r.debtorCode)))),
        h('td', null, r.street ? h('span', { class: 'ls-chip' }, r.street) : ''),
        h('td', { class: 'r ls-amt' }, rupee(r.amountOfAdvance)),
        h('td', { class: 'r' }, fmt.money(r.dayOfPayment)),
        h('td', null, fmt.date(r.closingDate)),
        h('td', { class: 'ls-note' }, r.noteText || '')));
    });
    return h('div', { class: 'ls-panel' },
      h('div', { class: 'ls-panel-head' },
        h('div', null, h('div', { class: 'ls-panel-title' }, 'Debtors on ' + fmt.date(date)),
          h('div', { class: 'ls-panel-sub' }, rows.length + (rows.length === 1 ? ' loan' : ' loans') + ' · ' + rupee(sum))),
        h('span', { class: 'ls-badge' }, rows.length)),
      h('div', { class: 'ls-scroll' }, h('table', { class: 'ls-sub' },
        h('thead', null, h('tr', null, h('th', null, 'Debtor'), h('th', null, 'Area'), h('th', { class: 'r' }, 'Advance'),
          h('th', { class: 'r' }, 'Day of Payment'), h('th', null, 'Closing Date'), h('th', null, 'Note'))),
        body)));
  }

  function addRows(rows) {
    rows.forEach(function (r, i) {
      const date = String(r.dateOfAdvance).slice(0, 10);
      const btn = h('button', { class: 'ls-toggle', type: 'button', 'aria-expanded': 'false', 'aria-label': 'Show debtors on ' + fmt.date(date), title: 'Show debtors', html: CHEVRON });
      const pct = Math.max(3, Math.round((Number(r.advanceAmount) || 0) * 100 / maxDay));
      const tr = h('tr', { class: 'ls-row' },
        h('td', { class: 'ls-c-toggle' }, btn),
        h('td', { class: 'c ls-muted' }, page * PAGE + i + 1),
        h('td', { class: 'ls-date' }, fmt.date(date)),
        h('td', { class: 'c' }, h('span', { class: 'ls-count' }, fmt.money(r.accounts))),
        h('td', { class: 'r ls-money' }, h('div', { class: 'ls-money-in' }, h('span', null, rupee(r.advanceAmount)),
          h('span', { class: 'ls-meter' }, h('span', { style: 'width:' + pct + '%' })))));
      let detail = null;
      const setOpen = function (open) {
        tr.classList.toggle('open', open);
        btn.setAttribute('aria-expanded', open ? 'true' : 'false');
        btn.title = open ? 'Hide debtors' : 'Show debtors';
        if (detail) detail.classList.toggle('hidden', !open);
      };
      btn.addEventListener('click', function () {
        if (detail) { setOpen(detail.classList.contains('hidden')); return; }
        busy(btn, async function () {
          btn.classList.add('loading');
          try {
            const rows2 = await GET('/api/reports/advance-on-date', { date: date, area: q.area });
            detail = h('tr', { class: 'ls-detail' }, h('td', { colspan: 5 }, detailPanel(date, rows2)));
            tr.after(detail);
            setOpen(true);
          } finally { btn.classList.remove('loading'); }
        });
      });
      tbody.appendChild(tr);
    });
  }

  /** Shows exactly one page of dates (replacing the previous one). */
  async function loadPage(n) {
    const rows = await GET('/api/reports/advance-between', { from: q.from, to: q.to, area: q.area, limit: PAGE, offset: n * PAGE });
    page = n;
    clear(tbody);
    addRows(rows);
    setProgress(rows.length);
    const top = tbody.closest('.ls-card');
    if (top && top.getBoundingClientRect().top < 0) top.scrollIntoView({ block: 'start', behavior: 'smooth' });
  }

  gen.addEventListener('click', function () {
    busy(gen, async function () {
      if (!from.value || !to.value) throw new Error('Please choose the from and to dates');
      if (to.value < from.value) throw new Error('To date cannot be before From date');
      q = { from: from.value, to: to.value, area: area.value };
      page = 0;
      clear(out);
      const s = await GET('/api/reports/advance-summary', q);
      totalDays = Number(s.days) || 0;
      maxDay = Number(s.maxDay) || 1;
      tiles.classList.remove('hidden');
      tDays.v.textContent = fmt.money(totalDays);
      tAcc.v.textContent = fmt.money(s.accounts || 0);
      tTotal.v.textContent = rupee(s.total);
      tMax.v.textContent = rupee(s.maxDay);
      if (!totalDays) {
        out.appendChild(h('div', { class: 'ls-empty' }, h('div', { class: 'ls-empty-ic' }, '∅'),
          h('div', { class: 'ls-empty-t' }, 'No loans in this period'), h('div', null, 'Try a wider date range or choose All areas.')));
        return;
      }
      tbody = h('tbody');
      status = h('span', { class: 'ls-status' });
      bar = h('span', null);
      prevBtn = h('button', { class: 'btn secondary ls-prev', type: 'button' }, h('span', { class: 'ls-flip', html: CHEVRON }), 'Previous');
      nextBtn = h('button', { class: 'btn ls-next', type: 'button' }, 'Next', h('span', { html: CHEVRON }));
      prevBtn.addEventListener('click', function () { busy(prevBtn, function () { return loadPage(page - 1); }); });
      nextBtn.addEventListener('click', function () { busy(nextBtn, function () { return loadPage(page + 1); }); });
      out.appendChild(h('div', { class: 'ls-card' },
        h('div', { class: 'ls-card-head' }, h('div', { class: 'ls-card-title' }, 'Advances by date'),
          h('div', { class: 'ls-card-sub' }, fmt.date(q.from) + ' – ' + fmt.date(q.to) + ' · ' + (q.area || 'All areas'))),
        h('div', { class: 'ls-scroll' }, h('table', { class: 'ls-main' },
          h('thead', null, h('tr', null, h('th', { class: 'ls-c-toggle' }, ''), h('th', { class: 'c' }, 'S.No'), h('th', null, 'Date'),
            h('th', { class: 'c' }, 'Loans'), h('th', { class: 'r' }, 'Advance Amount'))),
          tbody)),
        h('div', { class: 'ls-foot no-print' }, h('div', { class: 'ls-prog' }, status, h('span', { class: 'ls-track' }, bar)), h('div', { class: 'ls-pager' }, prevBtn, nextBtn))));
      await loadPage(0);
    });
  });
  gen.click();
});

reportPage({
  path: 'r-paid-day', title: 'Paid Amount on a Date', endpoint: '/api/reports/paid-on-date', date: true, area: true,
  columns: [COL.no, COL.code, COL.name, COL.area, { key: 'dayDate', label: 'Date', fmt: fmt.date }, mcol('amount', 'Paid Amount', true)]
});
reportPage({
  path: 'r-pay-rec', title: 'Payments & Receipts', endpoint: '/api/reports/payments-receipts', range: true, area: true,
  columns: [COL.no, { key: 'day', label: 'Date', fmt: fmt.date }, mcol('advanceAmount', 'Payments (advances given)', true),
    mcol('receivedAmount', 'Receipts (collected)', true)]
});
reportPage({
  path: 'r-total-due', title: 'Total Due Amount', endpoint: '/api/reports/total-due', date: true, area: true,
  columns: [COL.no, COL.code, COL.name, COL.area, COL.advDate, mcol('amountOfAdvance', 'Amount of Advance', true),
    mcol('paid', 'Paid', true), mcol('due', 'Due', true)]
});
reportPage({
  path: 'r-days', title: 'No. of Days of Accounts', endpoint: '/api/reports/days', date: true, order: true,
  columns: [COL.no, COL.code, COL.name, COL.advDate, mcol('amountOfAdvance', 'Amount', true), mcol('paid', 'Paid Amount', true),
    mcol('due', 'Due Amount', true), { key: 'noOfDays', label: 'No. of Days', align: 'c' }]
});
reportPage({
  path: 'r-month-due', title: 'Month-wise Due List', endpoint: '/api/reports/month-due', range: true, order: true,
  columns: [COL.no, COL.code, COL.name, COL.advDate, mcol('amountOfAdvance', 'Amount of Advance', true),
    mcol('paidBefore', 'Paid before period', true), mcol('paidInPeriod', 'Paid in period', true), mcol('paidTotal', 'Paid total', true), mcol('due', 'Due', true)]
});
reportPage({
  path: 'r-closed', title: 'Closed Accounts', endpoint: '/api/reports/closed-between', range: true, area: true,
  columns: [COL.no, COL.code, COL.name, COL.area, COL.advDate, mcol('amountOfAdvance', 'Amount', true),
    { key: 'closedDate', label: 'Closed Date', fmt: fmt.date }, { key: 'noOfDays', label: 'No. of Days', align: 'c' }]
});

/* ---------------------------------------------------------------- ledger */
route('r-ledger', 'Daily Payment Ledger', async function (main) {
  const debtors = await GET('/api/debtors');
  const debtorSel = select([{ value: '', label: '- Select debtor -' }].concat(debtors.map(function (d) {
    return { value: d.debtorCode, label: d.debtorName + ' (' + d.debtorCode + ')' };
  })), '');
  const accSel = select([{ value: '', label: '- Select account -' }], '');
  let status = 'ALL';
  const radios = radioGroup('lstatus', [{ value: 'ALL', label: 'All accounts' }, { value: 'OPEN', label: 'Running account' }, { value: 'CLOSED', label: 'Closed accounts' }],
    'ALL', function (v) { status = v; loadAccounts().catch(fail); });
  const out = h('div');
  const tools = h('div', { class: 'actions no-print hidden' });
  let current = null;

  async function loadAccounts() {
    clear(accSel);
    accSel.appendChild(h('option', { value: '' }, '- Select account -'));
    if (!debtorSel.value) return;
    const accs = await GET('/api/debtors/' + encodeURIComponent(debtorSel.value) + '/accounts', { status: status });
    accs.forEach(function (a) {
      accSel.appendChild(h('option', { value: a.accountNo }, 'A/C ' + a.accountNo + ' - ' + fmt.money(a.amountOfAdvance) + ' (' + fmt.date(a.dateOfAdvance) + ', ' + (a.accountStatus === 'OPEN' ? 'running' : 'closed') + ')'));
    });
    if (accs.length === 1) { accSel.value = accs[0].accountNo; show().catch(fail); }
  }
  async function show() {
    clear(out);
    tools.classList.add('hidden');
    if (!debtorSel.value || !accSel.value) return;
    const res = await GET('/api/reports/ledger', { debtorCode: debtorSel.value, accountNo: accSel.value });
    const a = res.account;
    current = res;
    const kv = function (k, v) { return h('div', null, h('span', { style: 'color:#667585' }, k + ': '), h('strong', null, v)); };
    out.appendChild(h('div', { class: 'card' }, h('div', { class: 'grid' },
      kv('Debtor', a.debtorName + ' (' + a.debtorCode + ')'), kv('Cell', a.phone), kv('Guarantor', a.guarantorName || '-'),
      kv('A/C No', a.accountNo), kv('Amount of advance', fmt.money(a.amountOfAdvance)), kv('Day of payment', fmt.money(a.dayOfPayment)),
      kv('Date of advance', fmt.date(a.dateOfAdvance)), kv('Closing date', fmt.date(a.closingDate)),
      kv('Status', a.accountStatus === 'OPEN' ? 'Running' : 'Closed'))));
    out.appendChild(dataTable(ledgerCols, res.rows, { empty: 'No payments recorded for this account' }));
    tools.classList.toggle('hidden', !res.rows.length);
  }
  const ledgerCols = [COL.no, { key: 'dayDate', label: 'Date', fmt: fmt.date }, mcol('amount', 'Paid Amount', true),
    mcol('paidTotal', 'Total Paid'), mcol('due', 'Balance Due')];
  const exportTo = function (kind) {
    return function () {
      busy(null, async function () {
        const spec = tableSpec(ledgerCols, current.rows);
        const a = current.account;
        spec.title = 'Daily Payment Ledger';
        spec.subtitle = a.debtorName + ' (' + a.debtorCode + ')  A/C ' + a.accountNo + '  Advance ' + fmt.money(a.amountOfAdvance) + ' on ' + fmt.date(a.dateOfAdvance);
        await downloadExport(kind, spec);
      });
    };
  };
  tools.appendChild(h('button', { class: 'btn secondary', onclick: exportTo('pdf') }, 'Export to PDF'));
  tools.appendChild(h('button', { class: 'btn secondary', onclick: exportTo('csv') }, 'Export to Excel'));
  tools.appendChild(h('button', { class: 'btn secondary', onclick: function () { window.print(); } }, 'Print'));
  debtorSel.addEventListener('change', function () { clear(out); tools.classList.add('hidden'); loadAccounts().catch(fail); });
  accSel.addEventListener('change', function () { show().catch(fail); });
  main.appendChild(h('div', { class: 'card no-print' }, h('div', { class: 'row' },
    field('Debtor name', debtorSel, { required: true }), field('Accounts', radios), field('Account', accSel, { required: true }))));
  main.appendChild(out);
  main.appendChild(tools);
});

/* ---------------------------------------------------------------- delete closed accounts */
route('delete-closed', 'Delete Closed Accounts', function (main) {
  const t = fmt.today();
  const from = h('input', { type: 'date', value: t.slice(0, 4) + '-01-01' });
  const to = h('input', { type: 'date', value: t });
  const areaSel = areaSelect(true);
  const out = h('div');
  const gen = h('button', { class: 'btn', type: 'button' }, 'Generate Report');
  const del = h('button', { class: 'btn danger hidden', type: 'button' }, 'Delete Accounts');
  main.appendChild(h('div', { class: 'card' }, h('p', { style: 'margin-top:0;color:#667585' },
    'Lists accounts that are already closed, by date of advance. Deleting hides them and their payments from all lists and reports.'),
    h('div', { class: 'row' }, field('From date', from, { required: true }), field('To date', to, { required: true }), field('Area', areaSel), gen)));
  main.appendChild(out);
  main.appendChild(h('div', { class: 'actions' }, del));
  let count = 0;

  gen.addEventListener('click', function () {
    busy(gen, async function () {
      if (!from.value || !to.value) throw new Error('Please choose the from and to dates');
      const rows = await GET('/api/reports/closed-delete', { from: from.value, to: to.value, area: areaSel.value });
      count = rows.length;
      clear(out).appendChild(dataTable([COL.no, COL.code, COL.name, COL.area, COL.advDate, mcol('amountOfAdvance', 'Amount', true),
        { key: 'closingDate', label: 'Closing Date', fmt: fmt.date }], rows, { empty: 'No closed accounts in this period' }));
      del.classList.toggle('hidden', !rows.length);
    });
  });
  del.addEventListener('click', function () {
    busy(del, async function () {
      if (!(await confirmBox('Delete closed accounts', 'Delete ' + count + ' closed account(s) and all their payments? This cannot be undone from the screen.', 'Delete', true))) return;
      const res = await POST('/api/reports/closed-delete', { from: from.value, to: to.value, area: areaSel.value });
      ok(res.deleted + ' account(s) deleted');
      gen.click();
    });
  });
});
