'use strict';
/* Home dashboard and change password. */

route('home', 'Home', async function (main) {
  const d = await GET('/api/reports/dashboard');
  const stat = function (v, l) { return h('div', { class: 'stat' }, h('div', { class: 'v' }, v), h('div', { class: 'l' }, l)); };
  main.appendChild(h('div', { class: 'stats' },
    stat(fmt.money(d.debtors), 'Active debtors'),
    stat(fmt.money(d.openAccounts), 'Running accounts'),
    stat(fmt.money(d.todayCollection), 'Collected today'),
    stat(fmt.money(d.todayAdvance), 'Advanced today'),
    stat(fmt.money(d.outstanding), 'Total outstanding')));

  const link = function (path, label, text) {
    return h('div', { class: 'card' }, h('h2', { style: 'margin-top:0' }, h('a', { href: '#/' + path }, label)), h('div', null, text));
  };
  main.appendChild(h('div', { class: 'grid' },
    link('payments', 'Daily Payments', 'Enter the amounts collected today, area by area.'),
    link('deposits', 'Deposits', 'Give a new advance to a debtor and view running accounts.'),
    link('closing', 'Account Closing', 'Close accounts that have been fully repaid.'),
    link('debtors', 'Add/Update Debtor', 'Add or update debtors, guarantors and photos.')));
});

route('password', 'Change Password', function (main) {
  const oldP = h('input', { type: 'password', autocomplete: 'current-password' });
  const newP = h('input', { type: 'password', autocomplete: 'new-password' });
  const rep = h('input', { type: 'password', autocomplete: 'new-password' });
  const btn = h('button', {
    class: 'btn', type: 'button', onclick: function () {
      busy(btn, async function () {
        if (newP.value !== rep.value) throw new Error('New password and confirmation do not match');
        await POST('/api/auth/change-password', { oldPassword: oldP.value, newPassword: newP.value });
        oldP.value = newP.value = rep.value = '';
        ok('Password changed');
      });
    }
  }, 'Change Password');
  main.appendChild(h('div', { class: 'card', style: 'max-width:420px' },
    h('div', { class: 'grid', style: 'grid-template-columns:1fr' },
      field('User', h('input', { type: 'text', value: App.user.username, readonly: true })),
      field('Current password', oldP, { required: true }),
      field('New password', newP, { required: true }),
      field('Confirm new password', rep, { required: true })),
    h('div', { class: 'actions' }, btn)));
});

/* ---------------------------------------------------------------- backup & restore (admin) */
route('backup', 'Backup & Restore', function (main) {
  const dl = h('a', { class: 'btn', href: '/api/admin/backup', style: 'display:inline-block;text-decoration:none' }, 'Download backup now');
  main.appendChild(h('div', { class: 'card' },
    h('h2', { style: 'margin-top:0' }, 'Backup'),
    h('p', null, 'Downloads a zip file with all debtors, deposits, payments, users and photos as they are right now. ' +
      'Keep a copy on a pen drive or another computer.'),
    h('div', { class: 'actions' }, dl)));

  const file = h('input', { type: 'file', accept: '.zip,application/zip' });
  const word = h('input', { type: 'text', placeholder: 'Type RESTORE to confirm', autocomplete: 'off' });
  const btn = h('button', { class: 'btn danger', type: 'button', disabled: true }, 'Restore from backup');
  const result = h('div');
  const check = function () { btn.disabled = !(file.files[0] && word.value.trim() === 'RESTORE'); };
  file.addEventListener('change', check);
  word.addEventListener('input', check);
  btn.addEventListener('click', function () {
    busy(btn, async function () {
      const fd = new FormData();
      fd.append('file', file.files[0]);
      const r = await fetch('/api/admin/restore', { method: 'POST', body: fd, credentials: 'same-origin' });
      let data = null;
      try { data = await r.json(); } catch (e) { /* not json */ }
      if (!r.ok) throw new Error((data && data.error) || 'Restore failed');
      clear(result).appendChild(h('div', { class: 'card', style: 'border-color:#1e7e4a' },
        h('h2', { style: 'margin-top:0;color:#1e7e4a' }, 'Restore complete'),
        h('p', null, Object.keys(data.rows).map(function (t) { return t + ': ' + fmt.money(data.rows[t]); }).join('   |   ')),
        h('p', null, data.photos + ' photo(s) restored. The data that was replaced was saved first as: ' + data.safetyBackup),
        h('p', null, 'You will now be asked to log in again.')));
      setTimeout(function () { App.user = null; location.hash = ''; App.showLogin(); }, 4000);
    });
  });
  main.appendChild(h('div', { class: 'card' },
    h('h2', { style: 'margin-top:0' }, 'Restore'),
    h('p', { style: 'color:#c0392b;font-weight:600' }, 'Restoring REPLACES all current data with the contents of the backup file. ' +
      'Anything entered after the backup was taken is lost. A safety copy of the current data is saved automatically first.'),
    h('div', { class: 'row' }, field('Backup file (.zip)', file), field('Confirmation', word), btn)));
  main.appendChild(result);
});
