'use strict';
/* Core helpers: DOM builder, API client, formatting, dialogs, tables, tabs, exports. */

const App = { user: null, lookups: null, config: null, routes: {}, current: null };

/* ---------------------------------------------------------------- DOM */
function h(tag, attrs) {
  const el = document.createElement(tag);
  if (attrs) {
    Object.keys(attrs).forEach(function (k) {
      const v = attrs[k];
      if (v === null || v === undefined || v === false) return;
      if (k.indexOf('on') === 0 && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else if (k === 'class') el.className = v;
      else if (k === 'value') el.value = v;
      else if (k === 'html') el.innerHTML = v;
      else if (v === true) el.setAttribute(k, '');
      else el.setAttribute(k, v);
    });
  }
  for (let i = 2; i < arguments.length; i++) append(el, arguments[i]);
  return el;
}
function append(el, kid) {
  if (kid === null || kid === undefined || kid === false) return;
  if (Array.isArray(kid)) kid.forEach(function (k) { append(el, k); });
  else if (kid instanceof Node) el.appendChild(kid);
  else el.appendChild(document.createTextNode(String(kid)));
}
function $(sel, root) { return (root || document).querySelector(sel); }
function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }

/* ---------------------------------------------------------------- formatting */
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const fmt = {
  money: function (n) { return n === null || n === undefined || n === '' ? '' : Number(n).toLocaleString('en-IN'); },
  date: function (s) {
    if (!s) return '';
    const p = String(s).slice(0, 10).split('-');
    return p.length === 3 ? p[2] + '-' + MONTHS[+p[1] - 1] + '-' + p[0] : s;
  },
  today: function () { return new Date().toLocaleDateString('en-CA'); },
  addDays: function (iso, n) {
    const d = new Date(iso + 'T00:00:00'); d.setDate(d.getDate() + n);
    return d.toLocaleDateString('en-CA');
  },
  shortDate: function (s) { const p = String(s).slice(0, 10).split('-'); return p[2] + '-' + MONTHS[+p[1] - 1]; },
  gender: function (g) {
    const names = App.lookups && App.lookups.genderNames;
    return names && names[g] ? names[g] : g;
  }
};

/** Page name as set in the Config Center; `dflt` is the built-in name used until one is set. */
function pageName(path, dflt) {
  const p = App.config && App.config.pages;
  return p && p[path] ? p[path] : dflt;
}

/* ---------------------------------------------------------------- API */
async function api(method, url, body) {
  const opt = { method: method, credentials: 'same-origin', headers: {} };
  if (body !== undefined) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
  let r;
  try { r = await fetch(url, opt); }
  catch (e) { throw new Error('Cannot reach the application. Is it still running?'); }
  const text = await r.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { /* not JSON */ }
  if (r.status === 401 && url.indexOf('/api/auth/') !== 0) {
    App.user = null;
    if (App.showLogin) App.showLogin();
    throw new Error('Please log in again');
  }
  if (!r.ok) throw new Error((data && data.error) || ('Request failed (' + r.status + ')'));
  return data;
}
const GET = function (url, params) { return api('GET', url + qs(params)); };
const POST = function (url, body) { return api('POST', url, body || {}); };
const PUT = function (url, body) { return api('PUT', url, body || {}); };
const DEL = function (url, body) { return api('DELETE', url, body || {}); };
function qs(params) {
  if (!params) return '';
  const parts = [];
  Object.keys(params).forEach(function (k) {
    const v = params[k];
    if (v !== null && v !== undefined && v !== '') parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
  });
  return parts.length ? '?' + parts.join('&') : '';
}

/* ---------------------------------------------------------------- feedback */
function toast(msg, kind) {
  const t = h('div', { class: 'toast ' + (kind || '') }, msg);
  $('#toasts').appendChild(t);
  setTimeout(function () { t.remove(); }, kind === 'err' ? 7000 : 3500);
}
const ok = function (m) { toast(m, 'ok'); };
const fail = function (e) { toast(e && e.message ? e.message : String(e), 'err'); };

/** Runs an async action while disabling the button; shows errors as toasts. */
async function busy(btn, fn) {
  if (btn) btn.disabled = true;
  try { return await fn(); }
  catch (e) { fail(e); }
  finally { if (btn) btn.disabled = false; }
}

function modal(title, bodyNodes, buttons) {
  return new Promise(function (resolve) {
    const overlay = h('div', { class: 'overlay' });
    const box = h('div', { class: 'modal', role: 'dialog' }, h('h3', null, title), bodyNodes);
    const bar = h('div', { class: 'actions' });
    buttons.forEach(function (b) {
      bar.appendChild(h('button', {
        class: 'btn ' + (b.cls || ''), type: 'button',
        onclick: function () { overlay.remove(); resolve(b.value); }
      }, b.label));
    });
    box.appendChild(bar);
    overlay.appendChild(box);
    document.body.appendChild(overlay);
    const first = box.querySelector('input,textarea,select,button.btn');
    if (first) first.focus();
  });
}
async function confirmBox(title, text, okLabel, danger) {
  const r = await modal(title, h('p', null, text), [
    { label: 'Cancel', cls: 'secondary', value: false },
    { label: okLabel || 'OK', cls: danger ? 'danger' : '', value: true }]);
  return r === true;
}
/** Asks for a mandatory reason; resolves to the text or null when cancelled. */
function askReason(title, text) {
  return new Promise(function (resolve) {
    const ta = h('textarea', { rows: 3, maxlength: 500, placeholder: 'Reason (required)' });
    const msg = h('div', { style: 'color:#c0392b;font-size:12px;min-height:16px' });
    const overlay = h('div', { class: 'overlay' });
    const finish = function (v) { overlay.remove(); resolve(v); };
    const box = h('div', { class: 'modal' }, h('h3', null, title), h('p', null, text), ta, msg,
      h('div', { class: 'actions' },
        h('button', { class: 'btn secondary', type: 'button', onclick: function () { finish(null); } }, 'Cancel'),
        h('button', {
          class: 'btn danger', type: 'button', onclick: function () {
            if (!ta.value.trim()) { msg.textContent = 'Please enter a reason'; return; }
            finish(ta.value.trim());
          }
        }, 'Delete')));
    overlay.appendChild(box);
    document.body.appendChild(overlay);
    ta.focus();
  });
}

/* ---------------------------------------------------------------- forms */
function field(label, input, opts) {
  opts = opts || {};
  return h('div', { class: 'field' + (opts.cls ? ' ' + opts.cls : '') },
    h('label', null, label, opts.required ? h('span', { class: 'req' }, ' *') : null), input);
}
function textInput(name, opts) {
  opts = opts || {};
  return h('input', Object.assign({ type: 'text', name: name, autocomplete: 'off' }, opts));
}
function select(options, value, attrs) {
  const s = h('select', attrs || {});
  // placeholder entries (empty value) stay on top; everything else is sorted by displayed name
  const placeholders = options.filter(function (o) { return o.value === ''; });
  const rest = options.filter(function (o) { return o.value !== ''; }).sort(function (a, b) {
    return String(a.label).localeCompare(String(b.label), undefined, { sensitivity: 'base', numeric: true });
  });
  placeholders.concat(rest).forEach(function (o) {
    const opt = h('option', { value: o.value }, o.label);
    if (String(o.value) === String(value)) opt.selected = true;
    s.appendChild(opt);
  });
  return s;
}
function areaSelect(includeAll, value) {
  const opts = (includeAll ? [{ value: '', label: 'All areas' }] : [{ value: '', label: '- Select area -' }])
    .concat((App.lookups.areas || []).map(function (a) { return { value: a, label: a }; }));
  return select(opts, value || '');
}
function radioGroup(name, items, value, onchange) {
  const wrap = h('div', { class: 'radios' });
  items.forEach(function (it) {
    const r = h('input', { type: 'radio', name: name, value: it.value });
    if (it.value === value) r.checked = true;
    r.addEventListener('change', function () { if (r.checked && onchange) onchange(it.value); });
    wrap.appendChild(h('label', null, r, it.label));
  });
  wrap.value = function () { const c = wrap.querySelector('input:checked'); return c ? c.value : null; };
  return wrap;
}

/* ---------------------------------------------------------------- tables */
/**
 * columns: [{key,label,align:'r'|'c',fmt:fn(v,row),render:fn(row,idx)->Node,total:true}]
 * opts: {rowClass:fn(row), footerLabelCols, empty}
 */
function dataTable(columns, rows, opts) {
  opts = opts || {};
  if (!rows || !rows.length) return h('div', { class: 'empty' }, opts.empty || 'No records found');
  const head = h('tr', null, columns.map(function (c) { return h('th', { class: c.align || '' }, c.label); }));
  const body = h('tbody');
  rows.forEach(function (row, i) {
    const tr = h('tr', { class: opts.rowClass ? opts.rowClass(row) : '' });
    columns.forEach(function (c) {
      let content;
      if (c.render) content = c.render(row, i);
      else if (c.key === '#') content = i + 1;
      else content = c.fmt ? c.fmt(row[c.key], row) : (row[c.key] === null || row[c.key] === undefined ? '' : row[c.key]);
      tr.appendChild(h('td', { class: c.align || '' }, content));
    });
    body.appendChild(tr);
  });
  const table = h('table', { class: 'data' }, h('thead', null, head), body);
  if (columns.some(function (c) { return c.total; })) {
    const tr = h('tr');
    let labelled = false;
    columns.forEach(function (c) {
      if (c.total) {
        const sum = rows.reduce(function (a, r) { return a + (Number(r[c.key]) || 0); }, 0);
        tr.appendChild(h('td', { class: c.align || 'r' }, c.fmt ? c.fmt(sum) : fmt.money(sum)));
      } else {
        tr.appendChild(h('td', null, labelled ? '' : 'Total'));
        labelled = true;
      }
    });
    table.appendChild(h('tfoot', null, tr));
  }
  return h('div', { class: 'table-wrap' }, table);
}

/** Plain-text version of a table, used for PDF / Excel export. */
function tableSpec(columns, rows) {
  const cols = columns.filter(function (c) { return c.key && !c.noExport; });
  const spec = {
    headers: cols.map(function (c) { return c.label; }),
    align: cols.map(function (c) { return c.align === 'r' ? 'R' : c.align === 'c' ? 'C' : 'L'; }),
    rows: rows.map(function (r, i) {
      return cols.map(function (c) {
        if (c.key === '#') return i + 1;
        const v = r[c.key];
        if (c.exportFmt) return c.exportFmt(v, r);
        if (c.fmt) return c.fmt(v, r);
        return v === null || v === undefined ? '' : v;
      });
    })
  };
  // Excel gets raw numbers (no thousands separators) so they stay numeric
  const rawNum = function (c, v) { return c.fmt === fmt.money && v !== null && v !== undefined && v !== '' ? Number(v) : null; };
  spec.csvRows = rows.map(function (r, i) {
    return cols.map(function (c, ci) {
      const n = c.key === '#' ? null : rawNum(c, r[c.key]);
      return n !== null ? n : spec.rows[i][ci];
    });
  });
  if (cols.some(function (c) { return c.total; })) {
    let labelled = false;
    spec.csvFooter = cols.map(function (c) {
      if (c.total) return rows.reduce(function (a, r) { return a + (Number(r[c.key]) || 0); }, 0);
      if (!labelled) { labelled = true; return 'Total'; }
      return '';
    });
    labelled = false;
    spec.footer = cols.map(function (c) {
      if (c.total) {
        const sum = rows.reduce(function (a, r) { return a + (Number(r[c.key]) || 0); }, 0);
        return c.fmt ? c.fmt(sum) : fmt.money(sum);
      }
      if (!labelled) { labelled = true; return 'Total'; }
      return '';
    });
  }
  return spec;
}

async function downloadExport(kind, spec) {
  const r = await fetch('/api/export/' + kind, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(spec)
  });
  if (!r.ok) {
    let msg = 'Export failed';
    try { msg = (await r.json()).error || msg; } catch (e) { /* ignore */ }
    throw new Error(msg);
  }
  const blob = await r.blob();
  const name = (r.headers.get('Content-Disposition') || '').replace(/.*filename="?([^"]+)"?.*/, '$1') || ('report.' + kind);
  const url = URL.createObjectURL(blob);
  const a = h('a', { href: url, download: name });
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(function () { URL.revokeObjectURL(url); }, 10000);
}

/* ---------------------------------------------------------------- tabs */
function tabs(items) {
  const bar = h('div', { class: 'tabs no-print' });
  const pane = h('div');
  const buttons = [];
  function show(i) {
    buttons.forEach(function (b, j) { b.classList.toggle('active', i === j); });
    clear(pane);
    items[i].render(pane);
  }
  items.forEach(function (it, i) {
    const b = h('button', { type: 'button', onclick: function () { show(i); } }, it.label);
    buttons.push(b); bar.appendChild(b);
  });
  show(0);
  return h('div', null, bar, pane);
}

/* ---------------------------------------------------------------- routing */
function route(path, title, render) { App.routes[path] = { title: title, render: render }; }
function go(path) { location.hash = '#/' + path; }
