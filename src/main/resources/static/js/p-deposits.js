'use strict';
/* Deposits (advances) and account closing. */

route('deposits', 'Deposits (Advances)', function (main) {
  const area = main.appendChild(h('div'));
  showList();

  async function showList() {
    clear(area);
    const status = select([{ value: 'OPEN', label: 'Running' }, { value: 'CLOSED', label: 'Closed' }, { value: 'ALL', label: 'All' }], 'OPEN');
    const areaSel = areaSelect(true);
    const from = h('input', { type: 'date' });
    const to = h('input', { type: 'date' });
    const results = h('div');
    const load = async function () {
      const rows = await GET('/api/deposits', { status: status.value, area: areaSel.value, from: from.value, to: to.value });
      clear(results).appendChild(h('div', { class: 'total-line' }, rows.length + ' account(s)'));
      results.appendChild(dataTable([
        { key: 'accountNo', label: 'A/C No', align: 'c' },
        { key: 'debtorName', label: 'Debtor Name' },
        { key: 'debtorCode', label: 'Code' },
        { key: 'street', label: 'Area' },
        { key: 'amountOfAdvance', label: 'Amount of Advance', align: 'r', fmt: fmt.money, total: true },
        { key: 'paid', label: 'Paid', align: 'r', fmt: fmt.money, total: true },
        { key: 'due', label: 'Due', align: 'r', fmt: fmt.money, total: true },
        { key: 'dateOfAdvance', label: 'Date of Advance', fmt: fmt.date },
        { key: 'closingDate', label: 'Closing Date', fmt: fmt.date },
        { key: 'accountStatus', label: 'Status', fmt: function (v) { return v === 'OPEN' ? 'Running' : 'Closed'; } },
        { key: 'x', label: '', render: function (r) {
          return h('span', null,
            h('button', { class: 'btn small secondary', onclick: function () { showForm(r.depositId); } }, 'Edit'), ' ',
            h('button', { class: 'btn small danger', onclick: function () { remove(r); } }, 'Delete'));
        } }
      ].map(function (c) {
        if (c.key === 'due') c.render = function (r) { return fmt.money(r.amountOfAdvance - r.paid); };
        return c;
      }), rows.map(function (r) { r.due = r.amountOfAdvance - r.paid; return r; })));
    };
    [status, areaSel, from, to].forEach(function (el) { el.addEventListener('change', function () { load().catch(fail); }); });
    area.appendChild(h('div', { class: 'card' },
      h('div', { class: 'row' },
        field('Show', status), field('Area', areaSel), field('Advance from', from), field('Advance to', to),
        h('button', { class: 'btn', onclick: function () { showForm(null); } }, '+ Add Deposit'))));
    area.appendChild(results);
    await load();
  }

  async function remove(r) {
    const reason = await askReason('Delete deposit', 'Delete account ' + r.accountNo + ' of ' + r.debtorName + '? Enter the reason.');
    if (!reason) return;
    try { await DEL('/api/deposits/' + r.depositId, { reason: reason }); ok('Deposit deleted'); showList(); }
    catch (e) { fail(e); }
  }

  async function showForm(id) {
    clear(area);
    const editing = id !== null;
    const d = editing ? await GET('/api/deposits/' + id) : {};
    let debtors = [];
    let running = {};
    let accNo;
    if (editing) {
      accNo = d.accountNo;
    } else {
      const res = await Promise.all([GET('/api/debtors'), GET('/api/deposits', { status: 'OPEN' }), GET('/api/deposits/next-account-no')]);
      debtors = res[0];
      res[1].forEach(function (r) { running[r.debtorCode] = true; });
      accNo = res[2].accountNo;
    }
    const code = h('input', { type: 'text', readonly: true, value: d.debtorCode || '' });
    const debtorSel = editing
      ? h('input', { type: 'text', readonly: true, value: d.debtorName })
      : select([{ value: '', label: '- Select debtor -' }].concat(debtors.map(function (x) {
        return { value: x.debtorCode, label: x.debtorName + ' (' + x.debtorCode + ')' + (running[x.debtorCode] ? ' - running account' : '') };
      })), '');
    if (!editing) {
      Array.prototype.forEach.call(debtorSel.options, function (o) { if (running[o.value]) o.disabled = true; });
      debtorSel.addEventListener('change', function () { code.value = debtorSel.value; });
    }
    const amount = h('input', { type: 'number', min: 1, step: 1, value: d.amountOfAdvance || '' });
    const days = h('input', { type: 'number', readonly: true, value: d.noOfDays || 100 });
    const daily = h('input', { type: 'number', min: 1, step: 1, value: d.dayOfPayment || '' });
    const adv = h('input', { type: 'date', value: d.dateOfAdvance || fmt.today() });
    const closing = h('input', { type: 'text', readonly: true });
    const note = select(App.lookups.notes.map(function (n) { return { value: n.noteId, label: n.note }; }), d.note || '');
    let dailyTouched = editing;
    daily.addEventListener('input', function () { dailyTouched = true; });
    const recalc = function () {
      if (!dailyTouched && amount.value) daily.value = Math.floor(Number(amount.value) / Number(days.value));
      closing.value = adv.value ? fmt.date(fmt.addDays(adv.value, Number(days.value))) : '';
    };
    amount.addEventListener('input', recalc);
    adv.addEventListener('change', recalc);
    recalc();

    const save = h('button', { class: 'btn', type: 'button' }, editing ? 'Modify' : 'Save Deposit');
    save.addEventListener('click', function () {
      busy(save, async function () {
        const body = {
          debtorCode: code.value, amountOfAdvance: amount.value, noOfDays: days.value,
          dayOfPayment: daily.value, dateOfAdvance: adv.value, note: note.value
        };
        if (editing) { await PUT('/api/deposits/' + id, body); ok('Your record has been updated successfully'); showList(); }
        else {
          if (!body.debtorCode) throw new Error('Please select a debtor');
          const saved = await POST('/api/deposits', body);
          ok('Deposit saved. Account number ' + saved.accountNo);
          const more = await confirmBox('Deposit added', 'Your record has been added successfully. Do you want to add more deposits?', 'Yes, add another');
          if (more) showForm(null); else showList();
        }
      });
    });
    area.appendChild(h('div', { class: 'card' },
      h('h2', { style: 'margin-top:0' }, editing ? 'Update deposit (A/C ' + accNo + ')' : 'Add deposit'),
      h('div', { class: 'grid' },
        field('Debtor name', debtorSel, { required: true }),
        field('Debtor code', code),
        field('A/C No', h('input', { type: 'text', readonly: true, value: accNo })),
        field('Amount of advance', amount, { required: true }),
        field('No. of days', days),
        field('Day of payment (daily amount)', daily, { required: true }),
        field('Date of advance', adv, { required: true }),
        field('Closing date', closing),
        field('Note', note, { required: true })),
      h('div', { class: 'actions' }, save,
        h('button', { class: 'btn secondary', type: 'button', onclick: showList }, 'Cancel'))));
  }
});

/* ---------------------------------------------------------------- account closing */
route('closing', 'Account Closing', function (main) {
  const out = h('div');
  main.appendChild(h('p', { style: 'color:#667585' }, 'Accounts that have been paid in full and can now be closed.'));
  main.appendChild(out);
  load();

  async function load() {
    const rows = await GET('/api/accounts/closable');
    clear(out).appendChild(dataTable([
      { key: 'debtorCode', label: 'Debtor Code' },
      { key: 'debtorName', label: 'Debtor Name' },
      { key: 'accountNo', label: 'Account Number', align: 'c' },
      { key: 'amountOfAdvance', label: 'Amount of Advance', align: 'r', fmt: fmt.money, total: true },
      { key: 'paid', label: 'Paid Amount', align: 'r', fmt: fmt.money, total: true },
      { key: 'due', label: 'Due Amount', align: 'r', fmt: fmt.money, total: true },
      { key: 'x', label: 'Close', render: function (r) {
        return h('button', { class: 'btn small', onclick: function () { close(r); } }, 'Close');
      } }
    ], rows, { empty: 'No accounts are ready to be closed' }));
  }
  async function close(r) {
    if (!(await confirmBox('Close account', 'Close account ' + r.accountNo + ' of ' + r.debtorName + '?', 'Close account'))) return;
    try { await POST('/api/accounts/close', { debtorCode: r.debtorCode, accountNo: r.accountNo }); ok('Account closed'); load(); }
    catch (e) { fail(e); }
  }
});
