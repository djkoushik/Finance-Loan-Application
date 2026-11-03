'use strict';
/* Reports. Most are described by a small config and share one page builder. */

const money = { align: 'r', fmt: fmt.money };
const COL = {
  no: { key: '#', label: 'S.No', align: 'c' },
  code: { key: 'debtorCode', label: 'Debtor Code' },
  name: { key: 'debtorName', label: 'Debtor Name' },
  area: { key: 'street', label: 'Area' },
  acc: { key: 'accountNo', label: 'A/C No', align: 'c' },
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

reportPage({
  path: 'r-advance-day', title: 'Advances on a Date', endpoint: '/api/reports/advance-on-date', date: true, area: true,
  columns: [COL.no, COL.code, COL.name, COL.area, COL.acc, mcol('amountOfAdvance', 'Amount of Advance', true),
    mcol('dayOfPayment', 'Day of Payment'), { key: 'closingDate', label: 'Closing Date', fmt: fmt.date }, { key: 'noteText', label: 'Note' }]
});
reportPage({
  path: 'r-advance-between', title: 'Advances Between Dates', endpoint: '/api/reports/advance-between', range: true, area: true,
  columns: [COL.no, { key: 'dateOfAdvance', label: 'Date', fmt: fmt.date }, { key: 'accounts', label: 'Accounts', align: 'c', total: true, fmt: fmt.money },
    mcol('advanceAmount', 'Advance Amount', true)]
});
reportPage({
  path: 'r-paid-day', title: 'Paid Amount on a Date', endpoint: '/api/reports/paid-on-date', date: true, area: true,
  columns: [COL.no, COL.code, COL.name, COL.area, COL.acc, { key: 'dayDate', label: 'Date', fmt: fmt.date }, mcol('amount', 'Paid Amount', true)]
});
reportPage({
  path: 'r-pay-rec', title: 'Payments & Receipts', endpoint: '/api/reports/payments-receipts', range: true, area: true,
  columns: [COL.no, { key: 'day', label: 'Date', fmt: fmt.date }, mcol('advanceAmount', 'Payments (advances given)', true),
    mcol('receivedAmount', 'Receipts (collected)', true)]
});
reportPage({
  path: 'r-total-due', title: 'Total Due Amount', endpoint: '/api/reports/total-due', date: true, area: true,
  columns: [COL.no, COL.code, COL.name, COL.area, COL.acc, COL.advDate, mcol('amountOfAdvance', 'Amount of Advance', true),
    mcol('paid', 'Paid', true), mcol('due', 'Due', true)]
});
reportPage({
  path: 'r-days', title: 'No. of Days of Accounts', endpoint: '/api/reports/days', date: true, order: true,
  columns: [COL.no, COL.code, COL.name, COL.acc, COL.advDate, mcol('amountOfAdvance', 'Amount', true), mcol('paid', 'Paid Amount', true),
    mcol('due', 'Due Amount', true), { key: 'noOfDays', label: 'No. of Days', align: 'c' }]
});
reportPage({
  path: 'r-month-due', title: 'Month-wise Due List', endpoint: '/api/reports/month-due', range: true, order: true,
  columns: [COL.no, COL.code, COL.name, COL.acc, COL.advDate, mcol('amountOfAdvance', 'Amount of Advance', true),
    mcol('paidBefore', 'Paid before period', true), mcol('paidInPeriod', 'Paid in period', true), mcol('paidTotal', 'Paid total', true), mcol('due', 'Due', true)]
});
reportPage({
  path: 'r-closed', title: 'Closed Accounts', endpoint: '/api/reports/closed-between', range: true, area: true,
  columns: [COL.no, COL.code, COL.name, COL.area, COL.acc, COL.advDate, mcol('amountOfAdvance', 'Amount', true),
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
      clear(out).appendChild(dataTable([COL.no, COL.code, COL.name, COL.area, COL.acc, COL.advDate, mcol('amountOfAdvance', 'Amount', true),
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
