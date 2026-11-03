'use strict';
/* Debtors (list, add, edit, delete, photo) and area visiting order. */

function photoUrl(photo) {
  return !photo || photo === 'nophotoavailable.jpg' ? 'img/nophoto.svg' : '/photos/' + encodeURIComponent(photo);
}

route('debtors', 'Add/Update Debtor', function (main) {
  const area = main.appendChild(h('div'));
  showList();

  async function showList() {
    clear(area);
    const q = h('input', { type: 'text', placeholder: 'Search name, code or cell' });
    const areaSel = areaSelect(true);
    const results = h('div');
    const load = async function () {
      const rows = await GET('/api/debtors', { q: q.value, area: areaSel.value });
      clear(results).appendChild(dataTable([
        { key: 'debtorCode', label: 'Debtor Code', render: function (r) { return h('a', { href: '#', onclick: function (e) { e.preventDefault(); showForm(r.debtorCode); } }, r.debtorCode); } },
        { key: 'debtorName', label: 'Debtor Name' },
        { key: 'phone', label: 'Cell' },
        { key: 'street', label: 'Area' },
        { key: 'dateOfJoin', label: 'Date of Joining', fmt: fmt.date },
        { key: 'x', label: '', render: function (r) {
          return h('span', null,
            h('button', { class: 'btn small secondary', onclick: function () { showForm(r.debtorCode); } }, 'Edit'), ' ',
            h('button', { class: 'btn small danger', onclick: function () { remove(r); } }, 'Delete'));
        } }
      ], rows, { empty: 'No debtors found' }));
      results.insertBefore(h('div', { class: 'total-line' }, rows.length + ' debtor(s)'), results.firstChild);
    };
    let timer;
    q.addEventListener('input', function () { clearTimeout(timer); timer = setTimeout(function () { load().catch(fail); }, 250); });
    areaSel.addEventListener('change', function () { load().catch(fail); });
    area.appendChild(h('div', { class: 'card' },
      h('div', { class: 'row' },
        field('Search', q), field('Area', areaSel),
        h('button', { class: 'btn', onclick: function () { showForm(null); } }, '+ Add Debtor'))));
    area.appendChild(results);
    await load();
  }

  async function remove(r) {
    const reason = await askReason('Delete debtor', 'Delete ' + r.debtorName + ' (' + r.debtorCode + ')? Enter the reason.');
    if (!reason) return;
    try {
      await DEL('/api/debtors/' + encodeURIComponent(r.debtorCode), { reason: reason });
      ok('Debtor deleted');
      await App.refreshLookups();
      showList();
    } catch (e) { fail(e); }
  }

  async function showForm(code) {
    clear(area);
    const editing = code !== null;
    const d = editing ? await GET('/api/debtors/' + encodeURIComponent(code)) : {};
    const nextCode = editing ? null : (await GET('/api/debtors/next-code')).code;
    const f = {};
    const inp = function (name, opts) { f[name] = textInput(name, Object.assign({ value: d[name] === undefined || d[name] === null || d[name] === 0 && name.toLowerCase().indexOf('pin') >= 0 ? '' : d[name] }, opts || {})); return f[name]; };

    inp('debtorName', { maxlength: 50 });
    inp('dateOfJoin', { type: 'date' });
    if (!editing) f.dateOfJoin.value = fmt.today();
    f.gender = select([{ value: '', label: '- Select -' }].concat(App.lookups.genders.map(function (g) { return { value: g.code, label: g.name }; })), d.gender || '');
    inp('email', { type: 'email', maxlength: 100 });
    inp('phone', { type: 'tel', maxlength: 10, inputmode: 'numeric', placeholder: '10 digit mobile' });
    inp('street', { list: 'areaList', maxlength: 100 });
    inp('houseNo', { maxlength: 100 });
    inp('city', { maxlength: 100 });
    inp('state', { maxlength: 100 });
    inp('pin', { maxlength: 6, inputmode: 'numeric' });
    inp('guarantorName', { maxlength: 100 });
    inp('guarantorPhone', { type: 'tel', maxlength: 10, inputmode: 'numeric' });
    inp('guarantorHouseNo', { maxlength: 100 });
    inp('guarantorStreet', { maxlength: 100 });
    inp('guarantorCity', { maxlength: 100 });
    inp('guarantorState', { maxlength: 100 });
    inp('guarantorPin', { maxlength: 6, inputmode: 'numeric' });

    const img = h('img', { class: 'photo', src: photoUrl(d.photo), alt: 'Photo' });
    const file = h('input', { type: 'file', accept: 'image/png,image/jpeg,image/gif' });
    file.addEventListener('change', function () {
      if (file.files[0]) img.src = URL.createObjectURL(file.files[0]);
    });

    const datalist = h('datalist', { id: 'areaList' }, (App.lookups.areas || []).map(function (a) { return h('option', { value: a }); }));
    const save = h('button', { class: 'btn', type: 'button' }, editing ? 'Update Debtor' : 'Save Debtor');
    save.addEventListener('click', function () {
      busy(save, async function () {
        const body = {};
        Object.keys(f).forEach(function (k) { body[k] = f[k].value; });
        const saved = editing
          ? await PUT('/api/debtors/' + encodeURIComponent(code), body)
          : await POST('/api/debtors', body);
        if (file.files[0]) {
          const fd = new FormData();
          fd.append('file', file.files[0]);
          const r = await fetch('/api/debtors/' + encodeURIComponent(saved.debtorCode) + '/photo', { method: 'POST', body: fd, credentials: 'same-origin' });
          if (!r.ok) { let m = 'Photo upload failed'; try { m = (await r.json()).error || m; } catch (e) { /* ignore */ } toast('Debtor saved, but: ' + m, 'err'); }
        }
        await App.refreshLookups();
        if (editing) { ok('Your record has been updated successfully'); showList(); }
        else {
          ok('Debtor ' + saved.debtorCode + ' added successfully');
          const more = await confirmBox('Debtor added', 'Do you want to add more debtors?', 'Yes, add another');
          if (more) showForm(null); else showList();
        }
      });
    });

    area.appendChild(h('div', { class: 'card' },
      datalist,
      h('h2', { style: 'margin-top:0' }, editing ? 'Edit debtor ' + code : 'Add debtor'),
      h('div', { class: 'row', style: 'align-items:flex-start' },
        h('div', { style: 'flex:1;min-width:280px' },
          h('div', { class: 'grid' },
            field('Debtor code', h('input', { type: 'text', value: editing ? code : nextCode, readonly: true })),
            field('Debtor name', f.debtorName, { required: true }),
            field('Date of joining', f.dateOfJoin, { required: true }),
            field('Gender', f.gender, { required: true }),
            field('Email', f.email),
            field('Cell', f.phone, { required: true }))),
        h('div', null, img, h('div', { style: 'margin-top:8px;max-width:220px' }, file))),
      h('h2', null, 'Address and guarantor'),
      h('div', { class: 'row', style: 'align-items:flex-start;gap:30px' },
        h('div', { style: 'flex:1;min-width:260px' },
          h('div', { style: 'font-weight:700;margin-bottom:8px' }, 'Debtor'),
          h('div', { class: 'grid', style: 'grid-template-columns:1fr 1fr' },
            field('H.No', f.houseNo), field('Colony/Street (area)', f.street, { required: true }),
            field('City', f.city), field('State', f.state), field('Pin', f.pin))),
        h('div', { style: 'flex:1;min-width:260px' },
          h('div', { style: 'font-weight:700;margin-bottom:8px' }, 'Guarantor'),
          h('div', { class: 'grid', style: 'grid-template-columns:1fr 1fr' },
            field('Name', f.guarantorName), field('Cell', f.guarantorPhone),
            field('H.No', f.guarantorHouseNo), field('Colony/Street', f.guarantorStreet),
            field('City', f.guarantorCity), field('State', f.guarantorState), field('Pin', f.guarantorPin)))),
      h('div', { class: 'actions' }, save,
        h('button', { class: 'btn secondary', type: 'button', onclick: showList }, 'Cancel'))));
  }
});

/* ---------------------------------------------------------------- area visiting order */
route('area-seq', 'Area Visiting Order', function (main) {
  const areaSel = areaSelect(false);
  const out = h('div');
  const show = h('button', { class: 'btn' }, 'Show Details');
  main.appendChild(h('div', { class: 'card' }, h('div', { class: 'row' }, field('Area', areaSel, { required: true }), show)));
  main.appendChild(out);

  show.addEventListener('click', function () {
    busy(show, async function () {
      if (!areaSel.value) throw new Error('Please select an area');
      const rows = await GET('/api/areas/' + encodeURIComponent(areaSel.value) + '/debtors');
      clear(out);
      if (!rows.length) { out.appendChild(h('div', { class: 'empty' }, 'No debtors in this area')); return; }
      const inputs = rows.map(function (r, i) {
        return h('input', { type: 'number', min: 1, class: 'amt', value: r.seq === null ? i + 1 : r.seq });
      });
      const swap = function (i, j) {
        if (j < 0 || j >= rows.length) return;
        // the sequence numbers stay with their positions; the debtors change places
        const tmp = rows[i]; rows[i] = rows[j]; rows[j] = tmp;
        draw();
      };
      const holder = h('div');
      const draw = function () {
        clear(holder).appendChild(dataTable([
          { key: '#', label: 'S.No', align: 'c' },
          { key: 'debtorCode', label: 'Debtor Code' },
          { key: 'debtorName', label: 'Debtor Name' },
          { key: 'dateOfJoin', label: 'Date of Joining', fmt: fmt.date },
          { key: 'phone', label: 'Cell' },
          { key: 'seq', label: 'Current Seq', align: 'c' },
          { key: 'n', label: 'New Seq', render: function (r, i) { return inputs[i]; } },
          { key: 'mv', label: 'Move', render: function (r, i) {
            return h('span', null,
              h('button', { class: 'btn small secondary', onclick: function () { swap(i, i - 1); } }, '▲'), ' ',
              h('button', { class: 'btn small secondary', onclick: function () { swap(i, i + 1); } }, '▼'));
          } }
        ], rows));
      };
      draw();
      const save = h('button', { class: 'btn' }, 'Update Sequence');
      save.addEventListener('click', function () {
        busy(save, async function () {
          const items = rows.map(function (r, i) { return { debtorId: r.debtorId, seq: inputs[i].value }; });
          await PUT('/api/areas/' + encodeURIComponent(areaSel.value) + '/sequence', { items: items });
          ok('Your record has been updated successfully');
          show.click();
        });
      });
      out.appendChild(holder);
      out.appendChild(h('div', { class: 'actions' }, save));
    });
  });
});
