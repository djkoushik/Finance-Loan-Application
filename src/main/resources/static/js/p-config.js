'use strict';
/* Config Center (administrators): names shown in the application and the values behind dropdown lists. */

route('config', 'Config Center', function (main) {
  main.appendChild(tabs([
    { label: 'Names', render: renderNames },
    { label: 'Dropdown values', render: function (pane) { renderDropdowns(pane).catch(fail); } }
  ]));
});

/* ---------------------------------------------------------------- names */
function renderNames(pane) {
  const c = App.config;
  const appName = h('input', { type: 'text', maxlength: 60, value: c.appName });
  const profile = h('input', { type: 'text', maxlength: 60, value: c.profileName || '', placeholder: 'Blank = the logged-in user\'s own name' });
  const roleDisp = select([
    { value: 'admin', label: 'Only for administrators' },
    { value: 'all', label: 'For everyone' },
    { value: 'none', label: 'Never' }], c.roleDisplay);

  const pageInputs = {};
  const rows = NAV.filter(function (n) { return n.path; }).map(function (n) {
    const inp = h('input', {
      type: 'text', maxlength: 50, placeholder: n.label,
      value: (c.pages && c.pages[n.path]) || ''
    });
    pageInputs[n.path] = inp;
    return h('tr', null, h('td', null, n.label), h('td', null, h('code', null, '#/' + n.path)), h('td', null, inp));
  });

  const save = h('button', { class: 'btn', type: 'button' }, 'Save names');
  save.addEventListener('click', function () {
    busy(save, async function () {
      const pages = {};
      Object.keys(pageInputs).forEach(function (p) { pages[p] = pageInputs[p].value.trim(); });
      App.config = await PUT('/api/config', {
        appName: appName.value.trim(), profileName: profile.value.trim(), roleDisplay: roleDisp.value, pages: pages
      });
      App.applyBranding();
      buildNav();
      render();
      ok('Names saved');
    });
  });

  pane.appendChild(h('div', { class: 'card' },
    h('h2', { style: 'margin-top:0' }, 'Header and profile'),
    h('div', { class: 'grid' },
      field('Application name (top left, login screen, PDF reports)', appName, { required: true }),
      field('Profile name (top right)', profile),
      field('Show role next to the profile name', roleDisp))));

  const preview = h('img', { class: 'brand-logo', alt: '', style: 'width:56px;height:56px;object-fit:contain',
    src: '/api/auth/icon' + (c.iconVersion ? '?v=' + c.iconVersion : '') });
  const file = h('input', { type: 'file', accept: 'image/png,image/jpeg,image/gif' });
  const upload = h('button', { class: 'btn', type: 'button' }, 'Upload icon');
  const reset = h('button', { class: 'btn secondary', type: 'button' }, 'Use original icon');
  upload.addEventListener('click', function () {
    busy(upload, async function () {
      if (!file.files[0]) throw new Error('Please choose an image first');
      const fd = new FormData();
      fd.append('file', file.files[0]);
      const r = await fetch('/api/config/icon', { method: 'PUT', body: fd, credentials: 'same-origin' });
      let data = null;
      try { data = await r.json(); } catch (e) { /* not json */ }
      if (!r.ok) throw new Error((data && data.error) || 'Upload failed');
      App.config = data; App.applyBranding(); preview.src = '/api/auth/icon?v=' + data.iconVersion; file.value = '';
      ok('Icon updated');
    });
  });
  reset.addEventListener('click', function () {
    busy(reset, async function () {
      App.config = await DEL('/api/config/icon'); App.applyBranding(); preview.src = '/api/auth/icon';
      ok('Original icon restored');
    });
  });
  pane.appendChild(h('div', { class: 'card' },
    h('h2', { style: 'margin-top:0' }, 'App icon'),
    h('p', null, 'Shown next to the application name, on the login screen and as the browser tab icon. PNG, JPG or GIF, up to 1 MB; a square image works best.'),
    h('div', { class: 'row' }, preview, field('Image', file), upload, reset)));

  pane.appendChild(h('div', { class: 'card' },
    h('h2', { style: 'margin-top:0' }, 'Page names'),
    h('p', null, 'Shown in the menu, as the page heading and in the browser title. Leave a box empty to use the original name.'),
    h('div', { class: 'table-wrap' }, h('table', { class: 'data' },
      h('thead', null, h('tr', null, h('th', null, 'Original name'), h('th', null, 'Page'), h('th', null, 'Name to show'))),
      h('tbody', null, rows)))));

  pane.appendChild(h('div', { class: 'actions' }, save));
}

/* ---------------------------------------------------------------- dropdown values */
async function renderDropdowns(pane) {
  const groups = await GET('/api/config/dropdowns');
  pane.appendChild(h('p', { class: 'page-hint' },
    'Internal value is what is stored in the records and never changes; display value is the text people see. ' +
    'A value that is already used by records cannot be deleted - switch it off instead.'));
  groups.forEach(function (g) { pane.appendChild(dropdownCard(g)); });
}

function dropdownCard(g) {
  const card = h('div', { class: 'card' });
  const reload = async function () {
    App.refreshLookups();
    const fresh = (await GET('/api/config/dropdowns')).filter(function (x) { return x.key === g.key; })[0];
    card.replaceWith(dropdownCard(fresh));
  };
  const call = function (btn, fn, msg) {
    busy(btn, async function () { await fn(); ok(msg); await reload(); });
  };

  const body = h('tbody');
  g.items.forEach(function (it) {
    const display = g.nameOnly ? h('span', null, it.display) : h('input', { type: 'text', maxlength: 100, value: it.display });
    const active = g.supportsActive ? h('input', { type: 'checkbox', checked: it.active, disabled: it.locked }) : null;
    const save = h('button', { class: 'btn', type: 'button' }, 'Save');
    save.addEventListener('click', function () {
      call(save, function () {
        return PUT('/api/config/dropdowns/' + g.key, {
          internal: it.internal, display: g.nameOnly ? it.display : display.value.trim(), active: active ? active.checked : true
        });
      }, 'Saved');
    });
    const del = h('button', { class: 'btn danger', type: 'button', disabled: it.locked || it.inUse > 0 }, 'Delete');
    del.addEventListener('click', async function () {
      if (!await confirmBox('Delete value', 'Delete "' + it.display + '" from this list?', 'Delete', true)) return;
      call(del, function () { return DEL('/api/config/dropdowns/' + g.key + '/' + encodeURIComponent(it.internal)); }, 'Deleted');
    });
    body.appendChild(h('tr', null,
      g.nameOnly ? h('td') : h('td', null, h('code', null, it.internal)), h('td', null, display),
      g.supportsActive ? h('td', { class: 'c' }, active) : null,
      h('td', { class: 'r' }, it.inUse), h('td', null, h('div', { class: 'actions' }, save, del))));
  });

  const newInternal = g.nameOnly ? h('span') : h('input', { type: 'text', maxlength: 30, placeholder: g.numeric ? 'Auto' : 'Code (1 letter)' });
  const newDisplay = h('input', { type: 'text', maxlength: 100, placeholder: g.nameOnly ? 'New area name' : 'Display value' });
  const add = h('button', { class: 'btn', type: 'button' }, 'Add');
  add.addEventListener('click', function () {
    call(add, function () {
      return PUT('/api/config/dropdowns/' + g.key, { internal: g.nameOnly ? '' : newInternal.value.trim(), display: newDisplay.value.trim(), active: true });
    }, 'Added');
  });
  body.appendChild(h('tr', null, h('td', null, newInternal), h('td', null, newDisplay),
    g.supportsActive ? h('td') : null, h('td'), h('td', null, add)));

  card.appendChild(h('h2', { style: 'margin-top:0' }, g.label));
  card.appendChild(h('div', { class: 'table-wrap' }, h('table', { class: 'data' },
    h('thead', null, h('tr', null,
      g.nameOnly ? h('th', null, '') : h('th', null, 'Internal value'), h('th', null, g.nameOnly ? 'Area name' : 'Display value'),
      g.supportsActive ? h('th', { class: 'c' }, 'Active') : null,
      h('th', { class: 'r' }, 'Used by'), h('th', null, ''))),
    body)));
  return card;
}
