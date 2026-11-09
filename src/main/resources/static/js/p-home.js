'use strict';
/* Home dashboard and change password. */

route('home', 'Home', async function (main) {
  const d = await GET('/api/reports/dashboard');
  const PAGE = 10;
  const tiles = {};
  const panel = h('div', { class: 'card widget-list', style: 'display:none' });
  let activeKey = null;

  const showList = async function (key, title, showAmount) {
    if (activeKey === key) { activeKey = null; panel.style.display = 'none'; refreshTiles(); return; }
    activeKey = key;
    refreshTiles();
    panel.style.display = '';
    clear(panel).appendChild(h('div', { class: 'empty' }, 'Loading...'));
    let rows;
    try { rows = await GET('/api/reports/dashboard/list?type=' + key); }
    catch (e) { clear(panel).appendChild(h('div', { class: 'empty' }, 'Could not load: ' + e.message)); return; }
    let page = 0;
    const pages = Math.max(1, Math.ceil(rows.length / PAGE));
    const render = function () {
      clear(panel);
      panel.appendChild(h('div', { class: 'widget-head' }, h('h2', { style: 'margin:0' }, title + ' (' + rows.length + ')'),
        h('button', { class: 'btn secondary', type: 'button', onclick: function () { activeKey = null; panel.style.display = 'none'; refreshTiles(); } }, 'Close')));
      if (!rows.length) { panel.appendChild(h('div', { class: 'empty' }, 'No records')); return; }
      const slice = rows.slice(page * PAGE, page * PAGE + PAGE);
      const head = h('tr', null, h('th', null, '#'), h('th', null, 'Name'), h('th', null, 'Area'),
        key === 'debtors' ? null : h('th', null, 'Account'), showAmount ? h('th', { style: 'text-align:right' }, 'Amount') : null);
      const body = slice.map(function (r, i) {
        return h('tr', null, h('td', null, page * PAGE + i + 1), h('td', null, r.debtorName), h('td', null, r.street || ''),
          key === 'debtors' ? null : h('td', null, r.accountNo),
          showAmount ? h('td', { style: 'text-align:right' }, fmt.money(r.amount)) : null);
      });
      panel.appendChild(h('div', { class: 'widget-scroll' }, h('table', null, h('thead', null, head), h('tbody', null, body))));
      panel.appendChild(h('div', { class: 'widget-pager' },
        h('button', { class: 'btn secondary', type: 'button', disabled: page === 0, onclick: function () { page--; render(); } }, 'Prev'),
        h('span', null, 'Page ' + (page + 1) + ' of ' + pages),
        h('button', { class: 'btn secondary', type: 'button', disabled: page >= pages - 1, onclick: function () { page++; render(); } }, 'Next')));
    };
    render();
  };
  const refreshTiles = function () {
    Object.keys(tiles).forEach(function (k) { tiles[k].classList.toggle('active', k === activeKey); });
  };
  const stat = function (v, l, key, title, showAmount) {
    const el = h('div', { class: 'stat' + (key ? ' clickable' : ''), title: key ? 'Click to see the list' : null,
      tabindex: key ? '0' : null, role: key ? 'button' : null },
      h('div', { class: 'v' }, v), h('div', { class: 'l' }, l));
    if (key) {
      tiles[key] = el;
      const go = function () { showList(key, title, showAmount); };
      el.addEventListener('click', go);
      el.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go(); } });
    }
    return el;
  };
  main.appendChild(h('div', { class: 'stats' },
    stat(fmt.money(d.debtors), 'Active debtors', 'debtors', 'Active debtors', false),
    stat(fmt.money(d.openAccounts), 'Running accounts', 'open', 'Running accounts', true),
    stat(fmt.money(d.todayCollection), 'Collected today', 'collected', 'Collected today', true),
    stat(fmt.money(d.todayAdvance), 'Advanced today', 'advanced', 'Advanced today', true),
    stat(fmt.money(d.outstanding), 'Total outstanding')));
  main.appendChild(panel);

  // bar chart: outstanding debt of the running accounts, one bar per area
  const chart = h('div', { class: 'card' }, h('h2', { style: 'margin-top:0' }, 'Debt by area (share of total outstanding debt)'));
  main.appendChild(chart);
  GET('/api/reports/dashboard/areas').then(function (areas) {
    if (!areas.length) { chart.appendChild(h('div', { class: 'empty' }, 'No outstanding debt')); return; }
    // Treemap heat map: tile AREA = area's share of the total outstanding debt, colour deepens with the share.
    const W = 1000, H = 420;
    const total = areas.reduce(function (t, a) { return t + Number(a.due); }, 0);
    const items = areas.map(function (a) { return { a: a, v: Number(a.due) / total * W * H }; })
      .sort(function (x, y) { return y.v - x.v; });
    const worst = function (row, side) {
      const sum = row.reduce(function (t, i) { return t + i.v; }, 0);
      const mx = Math.max.apply(null, row.map(function (i) { return i.v; }));
      const mn = Math.min.apply(null, row.map(function (i) { return i.v; }));
      return Math.max(side * side * mx / (sum * sum), sum * sum / (side * side * mn));
    };
    const rects = [];
    const layoutRow = function (row, box) {
      const sum = row.reduce(function (t, i) { return t + i.v; }, 0);
      if (box.w >= box.h) {            // column on the left
        const cw = sum / box.h; let y = box.y;
        row.forEach(function (i) { const ch = i.v / cw; rects.push({ i: i, x: box.x, y: y, w: cw, h: ch }); y += ch; });
        return { x: box.x + cw, y: box.y, w: box.w - cw, h: box.h };
      }
      const rh = sum / box.w; let x = box.x;   // row on top
      row.forEach(function (i) { const rw = i.v / rh; rects.push({ i: i, x: x, y: box.y, w: rw, h: rh }); x += rw; });
      return { x: box.x, y: box.y + rh, w: box.w, h: box.h - rh };
    };
    let box = { x: 0, y: 0, w: W, h: H }, row = [], k = 0;
    while (k < items.length) {
      const side = Math.min(box.w, box.h), it = items[k];
      if (!row.length || worst(row.concat([it]), side) <= worst(row, side)) { row.push(it); k++; }
      else { box = layoutRow(row, box); row = []; }
    }
    if (row.length) layoutRow(row, box);

    const top = items.length ? items[0].v : 1;
    const cells = rects.map(function (r) {
      const a = r.i.a, share = Number(a.due) / total, t = Math.min(1, r.i.v / top);
      const hue = Math.round(50 - 50 * t), light = Math.round(80 - 38 * t);
      const pct = (share * 100).toFixed(1) + '%';
      const big = r.w > 90 && r.h > 55;
      return h('div', { class: 'heat-cell', style: 'left:' + (r.x / W * 100) + '%;top:' + (r.y / H * 100) + '%;width:' + (r.w / W * 100) +
          '%;height:' + (r.h / H * 100) + '%;background:hsl(' + hue + ',80%,' + light + '%);color:' + (light < 58 ? '#fff' : '#222'),
          title: a.area + ' - ' + pct + ' of total debt. ' + a.accounts + ' running account(s), advanced ' + fmt.money(a.advanced) +
            ', collected ' + fmt.money(a.paid) + ', outstanding ' + fmt.money(a.due) },
        h('div', { class: 'heat-in' },
          h('div', { class: 'heat-area' }, a.area),
          r.w > 50 && r.h > 34 ? h('div', { class: 'heat-due' }, pct) : null,
          big ? h('div', { class: 'heat-sub' }, fmt.money(a.due) + ' | ' + a.accounts + ' acc') : null));
    });
    chart.appendChild(h('div', { class: 'heat-map' }, cells));
    chart.appendChild(h('div', { class: 'heat-legend' }, 'Tile size = share of total outstanding debt (' + fmt.money(total) + '). Hover a tile for details.'));
  }).catch(function (e) { chart.appendChild(h('div', { class: 'empty' }, 'Could not load the chart: ' + e.message)); });

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

  const file = h('input', { type: 'file', accept: '.zip,.bak,application/zip' });
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
      'Anything entered after the backup was taken is lost. A safety copy of the current data is saved automatically first. ' +
      'Accepted: a backup zip made by this application, or a SQL Server backup (.bak) from the old system (also inside a zip).'),
    h('div', { class: 'row' }, field('Backup file (.zip or SQL Server .bak)', file), field('Confirmation', word), btn)));
  main.appendChild(result);
});
