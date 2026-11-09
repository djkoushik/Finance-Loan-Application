'use strict';
/* Daily payments: entry sheet and edit/delete list. */

const ORDER_MODES = [
  { value: 'area', label: 'Area wise' },
  { value: 'name', label: 'Order by name' },
  { value: 'account', label: 'Order by account number' }
];

/** Shared filter bar: date, ordering and area. Returns {node, params(), onLoad}. */
function paymentFilters(loadFn) {
  const date = h('input', { type: 'date', value: fmt.today() });
  const areaSel = areaSelect(false);
  const areaField = field('Area', areaSel, { required: true });
  let mode = 'area';
  const radios = radioGroup('mode' + Math.random(), ORDER_MODES, 'area', function (v) {
    mode = v;
    areaField.classList.toggle('hidden', v !== 'area');
  });
  const btn = h('button', { class: 'btn', type: 'button' }, 'Load');
  btn.addEventListener('click', function () { busy(btn, loadFn); });
  const auto = function () {
    if (!date.value || (mode === 'area' && !areaSel.value)) return;
    busy(btn, loadFn);
  };
  [date, areaSel].forEach(function (el) { el.addEventListener('change', auto); });
  radios.addEventListener('change', function () { setTimeout(auto, 0); });
  const node = h('div', { class: 'card no-print' },
    h('div', { class: 'row' }, field('Date', date, { required: true }), field('Order', radios), areaField, btn));
  return {
    node: node,
    params: function () {
      if (!date.value) throw new Error('Please choose a date');
      if (mode === 'area' && !areaSel.value) throw new Error('Please select an area');
      return { date: date.value, mode: mode, area: mode === 'area' ? areaSel.value : '' };
    },
    date: function () { return date.value; }
  };
}

route('payments', 'Daily Payments', function (main) {
  main.appendChild(tabs([
    { label: 'Payment Entry', render: renderEntry },
    { label: 'Edit / Delete Payments', render: renderEdit }
  ]));
});

function renderEntry(pane) {
  const result = h('div');
  const filters = paymentFilters(load);
  pane.appendChild(filters.node);
  pane.appendChild(result);

  async function load() {
    const p = filters.params();
    const res = await GET('/api/payments/entry', p);
    const rows = res.rows;
    clear(result);
    if (!rows.length) { result.appendChild(h('div', { class: 'empty' }, 'No running accounts for the selected date')); return; }

    const inputs = rows.map(function (r) {
      const closedAcc = Number(r.due) <= 0;
      const inp = h('input', {
        type: 'number', class: 'amt', min: 0, max: r.due, step: 1, disabled: closedAcc,
        placeholder: closedAcc ? '' : String(Math.min(r.dayOfPayment, r.due)),
        value: r.todayAmount === null ? '' : r.todayAmount
      });
      return inp;
    });
    const totalEl = h('span', null, '0');
    const recompute = function () {
      totalEl.textContent = fmt.money(inputs.reduce(function (a, i) { return a + (Number(i.value) || 0); }, 0));
    };
    inputs.forEach(function (inp, i) {
      inp.addEventListener('input', recompute);
      inp.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
          e.preventDefault();
          for (let j = i + 1; j < inputs.length; j++) { if (!inputs[j].disabled) { inputs[j].focus(); inputs[j].select(); break; } }
        }
      });
    });
    recompute();

    const d = p.date;
    const cols = [
      { key: '#', label: 'S.No', align: 'c' },
      { key: 'debtorCode', label: 'Debtor Code' },
      { key: 'debtorName', label: 'Debtor Name' },
      { key: 'phone', label: 'Phone' },
      { key: 'dateOfAdvance', label: 'Date of Advance', fmt: fmt.date },
      { key: 'days', label: 'Days', align: 'c' },
      { key: 'amountOfAdvance', label: 'Advance', align: 'r', fmt: fmt.money },
      { key: 'before3', label: fmt.shortDate(fmt.addDays(d, -3)), align: 'r', fmt: fmt.money },
      { key: 'before2', label: fmt.shortDate(fmt.addDays(d, -2)), align: 'r', fmt: fmt.money },
      { key: 'before1', label: fmt.shortDate(fmt.addDays(d, -1)), align: 'r', fmt: fmt.money },
      { key: 'due', label: 'Due', align: 'r', fmt: fmt.money },
      { key: 'amt', label: 'Amount', noExport: true, render: function (r, i) { return inputs[i]; } }
    ];
    result.appendChild(h('div', { class: 'legend' },
      h('span', { class: 'red' }, 'Red: fully paid, or amount equals due (ready to close)'), h('span', { class: 'green' }, 'Green: advance given on this date')));
    // red = nothing left to pay: already fully paid, or the amount typed equals the due (Amount - Due = 0)
    const rowColor = function (r, i) {
      const typed = inputs[i].value;
      const settled = Number(r.due) <= 0 || (typed !== '' && Number(typed) === Number(r.due));
      return settled ? 'row-red' : (String(r.dateOfAdvance).slice(0, 10) === d ? 'row-green' : '');
    };
    const grid = dataTable(cols, rows, { rowClass: function (r) { return rowColor(r, rows.indexOf(r)); } });
    result.appendChild(grid);
    const trs = grid.querySelectorAll('tbody tr');
    const recolor = function (i) { if (trs[i]) trs[i].className = rowColor(rows[i], i); };
    inputs.forEach(function (inp, i) { inp.addEventListener('input', function () { recolor(i); }); });
    const recolorAll = function () { rows.forEach(function (r, i) { recolor(i); }); };

    const fill = h('button', { class: 'btn secondary', type: 'button', onclick: function () {
      inputs.forEach(function (inp, i) {
        if (!inp.disabled && inp.value === '' && String(rows[i].dateOfAdvance).slice(0, 10) !== d) inp.value = Math.min(rows[i].dayOfPayment, rows[i].due);
      });
      recompute();
      recolorAll();
    } }, 'Fill daily amounts');
    const clearBtn = h('button', { class: 'btn secondary', type: 'button', onclick: function () { inputs.forEach(function (i) { i.value = ''; }); recompute(); recolorAll(); } }, 'Clear');
    const save = h('button', { class: 'btn', type: 'button' }, 'Save');
    save.addEventListener('click', function () {
      busy(save, async function () {
        const entries = [];
        inputs.forEach(function (inp, i) {
          if (!inp.disabled && inp.value !== '' && Number(inp.value) !== 0) entries.push({ debtorCode: rows[i].debtorCode, accountNo: rows[i].accountNo, amount: inp.value });
        });
        if (!entries.length) throw new Error('Enter at least one amount');
        const total = entries.reduce(function (a, e) { return a + Number(e.amount); }, 0);
        if (!(await confirmBox('Save payments', 'Save ' + entries.length + ' payment(s) totalling ' + fmt.money(total) + ' for ' + fmt.date(d) + '?', 'Save'))) return;
        const res = await POST('/api/payments/batch', { date: d, entries: entries });
        ok('Your record has been saved. ' + res.saved + ' payment(s), total ' + fmt.money(res.total));
        await load();
      });
    });
    result.appendChild(h('div', { class: 'total-line' }, 'Total: ', totalEl));
    result.appendChild(h('div', { class: 'actions no-print' }, save, fill, clearBtn));
  }
}

function renderEdit(pane) {
  const result = h('div');
  const filters = paymentFilters(load);
  pane.appendChild(filters.node);
  pane.appendChild(result);

  async function load() {
    const p = filters.params();
    const rows = (await GET('/api/payments', p)).rows;
    clear(result);
    if (!rows.length) { result.appendChild(h('div', { class: 'empty' }, 'No payments found for the selected date')); return; }
    const inputs = rows.map(function (r) { return h('input', { type: 'number', class: 'amt', min: 0, step: 1, value: r.amount }); });
    const totalEl = h('span');
    const recompute = function () { totalEl.textContent = fmt.money(inputs.reduce(function (a, i) { return a + (Number(i.value) || 0); }, 0)); };
    inputs.forEach(function (i) { i.addEventListener('input', recompute); });
    recompute();
    result.appendChild(dataTable([
      { key: '#', label: 'S.No', align: 'c' },
      { key: 'debtorCode', label: 'Debtor Code' },
      { key: 'debtorName', label: 'Debtor Name' },
      { key: 'amt', label: 'Amount', render: function (r, i) { return inputs[i]; } },
      { key: 'x', label: '', render: function (r, i) {
        const edit = h('button', { class: 'btn small', type: 'button' }, 'Edit');
        edit.addEventListener('click', function () {
          busy(edit, async function () {
            await PUT('/api/payments/' + r.paymentId, { amount: inputs[i].value });
            ok('Your record has been updated successfully');
            await load();
          });
        });
        const del = h('button', { class: 'btn small danger', type: 'button' }, 'Delete');
        del.addEventListener('click', async function () {
          const reason = await askReason('Delete payment', 'Delete the payment of ' + r.debtorName + ' (A/C ' + r.accountNo + ')? Enter the reason.');
          if (!reason) return;
          busy(del, async function () { await DEL('/api/payments/' + r.paymentId, { reason: reason }); ok('Payment deleted'); await load(); });
        });
        return h('span', null, edit, ' ', del);
      } }
    ], rows));
    result.appendChild(h('div', { class: 'total-line' }, 'Total: ', totalEl));
  }
}
