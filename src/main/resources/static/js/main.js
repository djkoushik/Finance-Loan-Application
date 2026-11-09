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
  { path: 'r-loans', label: 'Loans Statements' },
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


const ICONS = {
  home: 'M3 11l9-8 9 8M5 10v10h5v-6h4v6h5V10',
  debtors: 'M16 11a4 4 0 10-8 0 4 4 0 008 0zM4 21c0-4 3.6-6 8-6s8 2 8 6',
  'area-seq': 'M12 21s7-6.2 7-11a7 7 0 10-14 0c0 4.8 7 11 7 11zM12 12a2.5 2.5 0 100-5 2.5 2.5 0 000 5z',
  deposits: 'M4 7h16v12H4zM4 7l2-3h12l2 3M12 11v4M10 13h4',
  payments: 'M3 7h18v10H3zM12 14a2 2 0 100-4 2 2 0 000 4zM6 10v.01M18 14v.01',
  closing: 'M5 12l4 4 10-10M4 4h16v16H4z',
  report: 'M5 3h10l4 4v14H5zM14 3v5h5M8 13h8M8 17h6',
  password: 'M6 11V8a6 6 0 1112 0v3M5 11h14v10H5zM12 15v2',
  backup: 'M12 3v12m0 0l-4-4m4 4l4-4M4 17v3h16v-3',
  trash: 'M5 7h14M10 7V4h4v3M7 7l1 13h8l1-13'
};
function navIcon(path) {
  const d = ICONS[path] || (path === 'delete-closed' ? ICONS.trash : path.indexOf('r-') === 0 ? ICONS.report : ICONS.home);
  return h('span', { class: 'ic', html: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="' + d + '"/></svg>' });
}
const HINTS = {
  home: 'Your business at a glance. Click a tile to see the names behind it.',
  debtors: 'Search, add and update debtors, guarantors and photos.',
  'area-seq': 'The order in which debtors are visited in an area.',
  deposits: 'Give a new advance to a debtor and manage running accounts.',
  payments: 'Enter the amounts collected today, or correct earlier payments.',
  closing: 'Accounts that have been paid in full and can now be closed.',
  'r-paid-day': 'Payments received on one date, debtor by debtor.',
  'r-pay-rec': 'Money lent out compared with money collected, day by day.',
  'r-total-due': 'What every running account has paid and still owes.',
  'r-ledger': 'The full payment history of a single account.',
  'r-days': 'Running accounts and how many days have passed since the advance.',
  'r-month-due': 'Collections within a period against what was outstanding.',
  'r-closed': 'Accounts that were fully repaid within the period.',
  'delete-closed': 'Clear out old closed accounts to keep the lists short.',
  password: 'Keep your account safe with a new password.',
  backup: 'Download a copy of all data, or restore from one.'
};

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
    nav.appendChild(h('a', { href: '#/' + n.path, 'data-path': n.path }, navIcon(n.path), h('span', null, n.label)));
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
  if (HINTS[path]) main.appendChild(h('p', { class: 'page-hint' }, HINTS[path]));
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
