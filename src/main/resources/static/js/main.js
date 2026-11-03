'use strict';
/* Application shell: login, navigation, routing. */

const NAV = [
  { path: 'home', label: 'Home' },
  { group: 'Masters' },
  { path: 'debtors', label: 'Add/Update Debtor' },
  { path: 'area-seq', label: 'Area Visiting Order' },
  { group: 'Transactions' },
  { path: 'deposits', label: 'Deposits (Advances)' },
  { path: 'payments', label: 'Daily Payments' },
  { path: 'closing', label: 'Account Closing' },
  { group: 'Reports' },
  { path: 'r-advance-day', label: 'Advances on a Date' },
  { path: 'r-advance-between', label: 'Advances Between Dates' },
  { path: 'r-paid-day', label: 'Paid Amount on a Date' },
  { path: 'r-pay-rec', label: 'Payments & Receipts' },
  { path: 'r-total-due', label: 'Total Due Amount' },
  { path: 'r-ledger', label: 'Daily Payment Ledger' },
  { path: 'r-days', label: 'No. of Days of Accounts' },
  { path: 'r-month-due', label: 'Month-wise Due List' },
  { path: 'r-closed', label: 'Closed Accounts' },
  { path: 'delete-closed', label: 'Delete Closed Accounts' },
  { group: 'Administration' },
  { path: 'password', label: 'Change Password' },
  { path: 'backup', label: 'Backup & Restore', admin: true }
];

App.refreshLookups = async function () {
  App.lookups = await GET('/api/lookups');
};

App.showLogin = async function () {
  $('#app').classList.add('hidden');
  $('#login').classList.remove('hidden');
  try {
    const roles = await GET('/api/auth/roles');
    const sel = $('#lgRole');
    clear(sel);
    roles.slice().sort(function (a, b) { return String(a.roleDesc).localeCompare(String(b.roleDesc), undefined, { sensitivity: 'base' }); }).forEach(function (r) { sel.appendChild(h('option', { value: r.roleId }, r.roleDesc)); });
  } catch (e) { $('#lgErr').textContent = e.message; }
};

async function enterApp(user) {
  App.user = user;
  await App.refreshLookups();
  $('#login').classList.add('hidden');
  $('#app').classList.remove('hidden');
  $('#whoAmI').textContent = user.name + (user.admin ? ' (Admin)' : '');
  buildNav();
  render();
}

function buildNav() {
  const nav = clear($('#nav'));
  NAV.forEach(function (n) {
    if (n.admin && !App.user.admin) return;
    if (n.group) { nav.appendChild(h('div', { class: 'group' }, n.group)); return; }
    nav.appendChild(h('a', { href: '#/' + n.path, 'data-path': n.path }, n.label));
  });
}

async function render() {
  if (!App.user) return;
  const path = (location.hash.replace(/^#\/?/, '') || 'home').split('?')[0];
  $('#nav').classList.remove('open');
  document.querySelectorAll('#nav a').forEach(function (a) {
    a.classList.toggle('active', a.getAttribute('data-path') === path);
  });
  const r = App.routes[path] || App.routes.home;
  const main = clear($('#main'));
  main.appendChild(h('h1', null, r.title));
  document.title = r.title + ' - Sri Raja Rajeshwari';
  try { await r.render(main); } catch (e) { fail(e); }
  window.scrollTo(0, 0);
}

window.addEventListener('hashchange', render);

$('#menuBtn').addEventListener('click', function () { $('#nav').classList.toggle('open'); });

$('#logoutBtn').addEventListener('click', async function () {
  try { await POST('/api/auth/logout'); } catch (e) { /* ignore */ }
  App.user = null;
  location.hash = '';
  App.showLogin();
});

$('#loginForm').addEventListener('submit', async function (ev) {
  ev.preventDefault();
  $('#lgErr').textContent = '';
  const btn = $('#loginForm button');
  btn.disabled = true;
  try {
    const user = await POST('/api/auth/login', {
      username: $('#lgUser').value.trim(), password: $('#lgPass').value, roleId: $('#lgRole').value
    });
    $('#lgPass').value = '';
    await enterApp(user);
  } catch (e) { $('#lgErr').textContent = e.message; }
  finally { btn.disabled = false; }
});

(async function boot() {
  try {
    const me = await GET('/api/auth/me');
    if (me.authenticated) await enterApp(me.user); else App.showLogin();
  } catch (e) { App.showLogin(); }
})();
