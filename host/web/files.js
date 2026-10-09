/* PindaHost - bestanden: de bestandsbrowser en editor voor de server en de website. */
'use strict';

(() => {
  const { $, $$, html, render, icon, api, post, toast, openModal, closeModal, confirmDialog, busy,
    pageHead, card, watchJob, actions, forms, state, bytes, num, ago, dateTime } = window.PH;

  // Bestanden die de editor opent als je erop klikt.
  const TEXT = /\.(ya?ml|json|json5|properties|txt|log|cfg|conf|config|toml|ini|md|sh|bat|cmd|html?|css|js|mjs|ts|xml|svg|csv|sk|lang|mcmeta|env|sql|php|py|gitignore|htaccess|lock|secret|key)$/i;
  const NO_EXT = /^[^.]+$/;
  const ARCHIVE = /\.(zip|tar|tar\.gz|tgz)$/i;
  const IMAGE = /\.(png|jpe?g|gif|webp|ico|bmp)$/i;

  const fm = { root: '', route: '', path: '.', entries: [], selected: new Set(), opts: null };

  const encPath = path => path.split('/').map(encodeURIComponent).join('/');
  const joinPath = (dir, name) => (dir === '.' || !dir) ? name : `${dir}/${name}`;
  const parentOf = path => { const i = path.lastIndexOf('/'); return i < 0 ? '.' : path.slice(0, i); };
  const baseName = path => path.slice(path.lastIndexOf('/') + 1);
  const listHash = (route, path) => `#/${route}` + (path && path !== '.' ? '/' + encPath(path) : '');
  const editHash = (route, path) => `#/${route}/${encPath(path)}?bewerken`;

  function parseRest(rest) {
    const [raw, query] = (rest || '').split('?');
    let path = raw.split('/').filter(Boolean).map(part => { try { return decodeURIComponent(part); } catch { return part; } }).join('/');
    if (!path || path.split('/').some(part => part === '..' || part === '.')) path = '.';
    return { path, edit: query === 'bewerken' };
  }

  function fileIcon(entry) {
    if (entry.type === 'dir') return 'folder';
    if (entry.type === 'link') return 'link';
    if (ARCHIVE.test(entry.name)) return 'archive';
    if (IMAGE.test(entry.name)) return 'image';
    if (TEXT.test(entry.name)) return 'code';
    return 'file';
  }
  const editable = entry => entry.type === 'file' && (TEXT.test(entry.name) || (NO_EXT.test(entry.name) && entry.size < 1024 * 1024));

  // =============================================================== de lijst

  /**
   * Een bestandsbrowser. opts: { root: 'server' | 'website', route: 'bestanden', title, intro, rootLabel, extra(data) }
   */
  async function fileBrowser(main, alive, rest, opts) {
    const { path, edit } = parseRest(rest);
    fm.root = opts.root;
    fm.route = opts.route;
    fm.opts = opts;
    if (edit) return openEditor(main, alive, path);
    const data = await api(`/files/${opts.root}/list?path=${encodeURIComponent(path)}`);
    if (!alive()) return;
    fm.path = data.path;
    fm.entries = data.entries;
    fm.free = data.free;
    fm.selected.clear();
    const extra = opts.extra ? await opts.extra(data.path) : '';
    if (!alive()) return;
    renderList(main, data, extra);
  }

  function crumbs(path, editing) {
    const parts = path === '.' ? [] : path.split('/');
    const links = [html`<a href="${listHash(fm.route, '.')}">${icon(fm.root === 'website' ? 'web' : 'folder')} ${fm.opts.rootLabel}</a>`];
    parts.forEach((part, i) => {
      const sub = parts.slice(0, i + 1).join('/');
      const last = i === parts.length - 1;
      links.push(html`<span class="crumb-sep">/</span>`);
      links.push(last && editing ? html`<b>${part}</b>` : html`<a href="${listHash(fm.route, sub)}" class="${last ? 'current' : ''}">${part}</a>`);
    });
    return html`<nav class="crumbs" aria-label="Map">${links}</nav>`;
  }

  function renderList(main, data, extra) {
    const dirs = data.entries.filter(e => e.type === 'dir').length;
    const files = data.entries.length - dirs;
    const rows = data.entries.map(entry => {
      const target = joinPath(fm.path, entry.name);
      const href = entry.type === 'dir' ? listHash(fm.route, target)
        : editable(entry) ? editHash(fm.route, target)
          : entry.type === 'file' ? `/api/files/${fm.root}/download?path=${encodeURIComponent(target)}` : '';
      return html`<tr data-name="${entry.name}" class="fm-row">
        <td class="fm-check"><input type="checkbox" data-fm-select value="${entry.name}" aria-label="${entry.name} selecteren"></td>
        <td class="fm-name-cell">${href
          ? html`<a class="fm-name" href="${href}" ${entry.type === 'file' && !editable(entry) ? 'download' : ''}>${icon(fileIcon(entry), entry.type === 'dir' ? 'fm-dir' : '')}<span>${entry.name}</span></a>`
          : html`<span class="fm-name">${icon(fileIcon(entry))}<span>${entry.name}</span></span>`}
          ${entry.type === 'link' ? html`<span class="badge plain">symlink</span>` : ''}</td>
        <td class="num hide-sm muted">${entry.type === 'file' ? bytes(entry.size) : ''}</td>
        <td class="hide-sm muted nowrap" title="${dateTime(entry.modified)}">${ago(entry.modified)}</td>
        <td class="actions"><button class="btn sm ghost icon-only" data-action="fm-menu" data-name="${entry.name}" title="Meer" aria-label="Acties voor ${entry.name}">${icon('more')}</button></td>
      </tr>`;
    });
    const up = fm.path !== '.' ? html`<tr class="fm-row fm-up"><td class="fm-check"></td><td colspan="4"><a class="fm-name" href="${listHash(fm.route, parentOf(fm.path))}">${icon('folder', 'fm-dir')}<span>..</span></a></td></tr>` : '';
    const empty = !data.entries.length ? html`<tr><td colspan="5"><div class="empty">Deze map is leeg. Sleep bestanden hierheen of gebruik Uploaden.</div></td></tr>` : '';
    render(main, html`${pageHead(fm.opts.title, fm.opts.intro)}
      ${extra}
      <section class="card fm" id="fm">
        <div class="fm-bar">
          ${crumbs(fm.path)}
          <div class="fm-actions">
            <button class="btn sm primary" data-action="fm-upload">${icon('upload')} Uploaden</button>
            <button class="btn sm" data-action="fm-new" data-kind="dir">${icon('folderPlus')}<span class="hide-sm">Nieuwe map</span></button>
            <button class="btn sm" data-action="fm-new" data-kind="file">${icon('filePlus')}<span class="hide-sm">Nieuw bestand</span></button>
            <button class="btn sm ghost icon-only" data-action="reload" title="Vernieuwen" aria-label="Vernieuwen">${icon('refresh')}</button>
          </div>
        </div>
        <div class="fm-selbar hidden" id="fm-selbar">
          <span id="fm-selcount"></span>
          <div class="btn-row">
            <button class="btn sm" data-action="fm-move-selected">${icon('move')} Verplaatsen</button>
            <button class="btn sm" data-action="fm-archive-selected">${icon('archive')} Inpakken</button>
            <button class="btn sm danger" data-action="fm-delete-selected">${icon('trash')} Verwijderen</button>
          </div>
        </div>
        <div class="table-wrap fm-drop" id="fm-drop">
          <table class="fm-table">
            <thead><tr><th class="fm-check"><input type="checkbox" data-fm-all aria-label="Alles selecteren"></th><th>Naam</th>
              <th class="num hide-sm">Grootte</th><th class="hide-sm">Gewijzigd</th><th></th></tr></thead>
            <tbody>${up}${rows}${empty}</tbody>
          </table>
          <div class="fm-dropzone">${icon('upload')} Laat los om te uploaden naar ${fm.path === '.' ? fm.opts.rootLabel : fm.path}</div>
        </div>
        <div class="fm-foot">${num(dirs)} ${dirs === 1 ? 'map' : 'mappen'}, ${num(files)} ${files === 1 ? 'bestand' : 'bestanden'}${data.truncated ? ' (niet alles getoond)' : ''}
          ${data.free >= 0 ? html` · nog ${bytes(data.free)} vrij` : ''}</div>
        <input type="file" id="fm-input" multiple hidden>
      </section>`);
    bindList(main);
  }

  function bindList(main) {
    main.onchange = event => {
      const box = event.target;
      if (box.matches('[data-fm-all]')) {
        $$('[data-fm-select]', main).forEach(cb => { cb.checked = box.checked; });
      }
      if (box.matches('[data-fm-all], [data-fm-select]')) updateSelection();
      if (box.id === 'fm-input' && box.files.length) {
        queueUploads(Array.from(box.files).map(file => ({ file, dir: fm.path })));
        box.value = '';
      }
    };
    const drop = $('#fm-drop', main);
    let depth = 0;
    drop.addEventListener('dragenter', event => { if (hasFiles(event)) { depth++; drop.classList.add('over'); event.preventDefault(); } });
    drop.addEventListener('dragover', event => { if (hasFiles(event)) event.preventDefault(); });
    drop.addEventListener('dragleave', () => { depth = Math.max(0, depth - 1); if (!depth) drop.classList.remove('over'); });
    drop.addEventListener('drop', async event => {
      if (!hasFiles(event)) return;
      event.preventDefault();
      depth = 0;
      drop.classList.remove('over');
      const items = await droppedFiles(event.dataTransfer, fm.path);
      if (items.length) queueUploads(items);
    });
  }

  const hasFiles = event => Array.from(event.dataTransfer?.types || []).includes('Files');

  function updateSelection() {
    fm.selected = new Set($$('[data-fm-select]:checked').map(cb => cb.value));
    const bar = $('#fm-selbar');
    if (!bar) return;
    bar.classList.toggle('hidden', fm.selected.size === 0);
    $('#fm-selcount').textContent = `${num(fm.selected.size)} geselecteerd`;
    $$('.fm-row').forEach(row => row.classList.toggle('selected', fm.selected.has(row.dataset.name)));
  }

  const reloadList = () => window.PH.reloadPage();

  // =============================================================== menu per bestand

  let openMenu = null;
  function closeMenu() {
    if (openMenu) { openMenu.remove(); openMenu = null; }
  }
  document.addEventListener('click', event => { if (openMenu && !event.target.closest('.menu, [data-action=fm-menu]')) closeMenu(); });
  document.addEventListener('keydown', event => { if (event.key === 'Escape') closeMenu(); });
  window.addEventListener('scroll', closeMenu, true);
  window.addEventListener('hashchange', closeMenu);

  actions['fm-menu'] = button => {
    const name = button.dataset.name;
    if (openMenu && openMenu.dataset.name === name) { closeMenu(); return; }
    closeMenu();
    const entry = fm.entries.find(e => e.name === name);
    if (!entry) return;
    const target = joinPath(fm.path, name);
    const items = [];
    if (entry.type === 'dir') items.push(html`<a href="${listHash(fm.route, target)}">${icon('folder')} Openen</a>`);
    if (entry.type === 'file') items.push(html`<a href="${editHash(fm.route, target)}">${icon('edit')} Bewerken</a>`);
    if (entry.type === 'file' || entry.type === 'dir') {
      items.push(html`<a href="/api/files/${fm.root}/download?path=${encodeURIComponent(target)}" download>${icon('download')} Downloaden${entry.type === 'dir' ? ' (zip)' : ''}</a>`);
    }
    items.push(html`<button data-action="fm-rename" data-name="${name}">${icon('edit')} Hernoemen</button>`);
    items.push(html`<button data-action="fm-move" data-name="${name}">${icon('move')} Verplaatsen</button>`);
    if (entry.type === 'file' && ARCHIVE.test(name)) items.push(html`<button data-action="fm-extract" data-name="${name}">${icon('archive')} Uitpakken</button>`);
    if (entry.type !== 'link') items.push(html`<button data-action="fm-archive" data-name="${name}">${icon('archive')} Inpakken (zip)</button>`);
    items.push(html`<button class="danger" data-action="fm-delete" data-name="${name}">${icon('trash')} Verwijderen</button>`);
    const menu = document.createElement('div');
    menu.className = 'menu';
    menu.dataset.name = name;
    render(menu, items);
    document.body.append(menu);
    const rect = button.getBoundingClientRect();
    const width = menu.offsetWidth;
    const height = menu.offsetHeight;
    const top = rect.bottom + 4 + height > window.innerHeight ? Math.max(8, rect.top - height - 4) : rect.bottom + 4;
    menu.style.top = `${top}px`;
    menu.style.left = `${Math.max(8, Math.min(window.innerWidth - width - 8, rect.right - width))}px`;
    openMenu = menu;
    menu.addEventListener('click', event => { if (event.target.closest('a, button')) setTimeout(closeMenu, 0); });
  };

  // =============================================================== acties

  function nameDialog({ title, text = '', label, value = '', confirm, form, data = {} }) {
    openModal(html`<form data-form="${form}" ${Object.entries(data).map(([k, v]) => html` data-${k}="${v}"`)}>
      <div class="modal-head"><h3>${title}</h3>${text ? html`<p>${text}</p>` : ''}</div>
      <div class="modal-body"><label class="field">${label}<input class="input mono" name="value" value="${value}" required autocomplete="off" autofocus></label></div>
      <div class="modal-foot"><button type="button" class="btn ghost" data-action="close">Annuleren</button><button class="btn primary">${confirm}</button></div></form>`);
    const input = $('#modal input[name=value]');
    if (input && value) {
      const dot = value.lastIndexOf('.');
      input.setSelectionRange(0, dot > 0 && !value.endsWith('/') ? dot : value.length);
    }
  }

  actions['fm-new'] = button => {
    const dir = button.dataset.kind === 'dir';
    nameDialog({ title: dir ? 'Nieuwe map' : 'Nieuw bestand', label: 'Naam', confirm: 'Aanmaken', form: 'fm-new', data: { kind: button.dataset.kind },
      text: `In ${fm.path === '.' ? fm.opts.rootLabel : fm.path}.` });
  };
  forms['fm-new'] = async (form, data, button) => {
    const name = data.get('value').trim();
    const dir = form.dataset.kind === 'dir';
    const target = joinPath(fm.path, name);
    const result = await busy(button, () => post(`/files/${fm.root}/create`, { path: target, dir }));
    if (!result) return;
    closeModal();
    if (dir) { toast(`Map ${name} aangemaakt.`); reloadList(); } else location.hash = editHash(fm.route, target);
  };

  actions['fm-rename'] = button => {
    const name = button.dataset.name;
    nameDialog({ title: `${name} hernoemen`, label: 'Nieuwe naam', value: name, confirm: 'Hernoemen', form: 'fm-rename', data: { name } });
  };
  forms['fm-rename'] = async (form, data, button) => {
    const name = form.dataset.name;
    const next = data.get('value').trim();
    if (!next || next === name) { closeModal(); return; }
    if (next.includes('/')) { toast('Gebruik Verplaatsen om naar een andere map te gaan.', 'error'); return; }
    const result = await busy(button, () => post(`/files/${fm.root}/rename`, { from: joinPath(fm.path, name), to: joinPath(fm.path, next) }));
    if (!result) return;
    closeModal();
    toast('Hernoemd.');
    reloadList();
  };

  function moveDialog(names) {
    nameDialog({ title: names.length === 1 ? `${names[0]} verplaatsen` : `${num(names.length)} items verplaatsen`,
      text: `Naar welke map? Vanaf de hoofdmap, bijv. plugins/oud. Leeg = de hoofdmap.`, label: 'Map', value: fm.path === '.' ? '' : fm.path,
      confirm: 'Verplaatsen', form: 'fm-move', data: { names: JSON.stringify(names) } });
    const input = $('#modal input[name=value]');
    if (input) input.required = false;
  }
  actions['fm-move'] = button => moveDialog([button.dataset.name]);
  actions['fm-move-selected'] = () => moveDialog(Array.from(fm.selected));
  forms['fm-move'] = async (form, data, button) => {
    const names = JSON.parse(form.dataset.names);
    const dest = data.get('value').trim().replace(/^\/+|\/+$/g, '') || '.';
    const done = await busy(button, async () => {
      for (const name of names) await post(`/files/${fm.root}/rename`, { from: joinPath(fm.path, name), to: joinPath(dest, name) });
      return true;
    });
    if (!done) return;
    closeModal();
    toast(`Verplaatst naar ${dest === '.' ? fm.opts.rootLabel : dest}.`);
    reloadList();
  };

  async function deleteItems(names) {
    const dirs = names.filter(name => (fm.entries.find(e => e.name === name) || {}).type === 'dir');
    const ok = await confirmDialog({
      title: names.length === 1 ? `${names[0]} verwijderen?` : `${num(names.length)} items verwijderen?`,
      text: dirs.length ? 'Mappen worden met alles erin verwijderd. Dit kan niet ongedaan worden gemaakt.' : 'Dit kan niet ongedaan worden gemaakt.',
      confirm: 'Verwijderen', danger: true, typeToConfirm: dirs.length && fm.root === 'server' && names.length === 1 ? names[0] : ''
    });
    if (!ok) return;
    const result = await busy(null, () => post(`/files/${fm.root}/delete`, { paths: names.map(name => joinPath(fm.path, name)) }));
    if (!result) return;
    toast(`Verwijderd (${num(result.removed)} ${result.removed === 1 ? 'item' : 'items'}).`);
    reloadList();
  }
  actions['fm-delete'] = button => deleteItems([button.dataset.name]);
  actions['fm-delete-selected'] = () => deleteItems(Array.from(fm.selected));

  function archiveDialog(names) {
    const stamp = new Date().toISOString().slice(0, 10);
    const base = names.length === 1 ? names[0].replace(/\.[^.]+$/, '') : 'archief';
    nameDialog({ title: 'Inpakken als zip', text: `${num(names.length)} ${names.length === 1 ? 'item' : 'items'} in één zip-bestand, in deze map.`,
      label: 'Naam', value: `${base}-${stamp}.zip`, confirm: 'Inpakken', form: 'fm-archive', data: { names: JSON.stringify(names) } });
  }
  actions['fm-archive'] = button => archiveDialog([button.dataset.name]);
  actions['fm-archive-selected'] = () => archiveDialog(Array.from(fm.selected));
  forms['fm-archive'] = async (form, data, button) => {
    const job = await busy(button, () => post(`/files/${fm.root}/archive`, { dir: fm.path, names: JSON.parse(form.dataset.names), name: data.get('value').trim() }));
    if (job) watchJob(job, () => reloadList());
  };

  actions['fm-extract'] = async button => {
    const name = button.dataset.name;
    const ok = await confirmDialog({ title: `${name} uitpakken?`, text: `De inhoud komt in ${fm.path === '.' ? fm.opts.rootLabel : fm.path}. Bestanden met dezelfde naam worden overschreven.`, confirm: 'Uitpakken' });
    if (!ok) return;
    const job = await busy(null, () => post(`/files/${fm.root}/extract`, { path: joinPath(fm.path, name), dest: fm.path }));
    if (job) watchJob(job, () => reloadList());
  };

  actions['fm-upload'] = () => $('#fm-input').click();

  // =============================================================== uploaden (in stukken, ook mappen)

  const uploads = { queue: [], running: false, panel: null };

  /** Bestanden (en mappen, via slepen) met hun map erbij. */
  async function droppedFiles(transfer, base) {
    const items = Array.from(transfer.items || []).map(item => item.webkitGetAsEntry && item.webkitGetAsEntry()).filter(Boolean);
    if (!items.length) return Array.from(transfer.files || []).map(file => ({ file, dir: base }));
    const out = [];
    const readAll = reader => new Promise(resolve => {
      const all = [];
      const next = () => reader.readEntries(batch => { if (!batch.length) resolve(all); else { all.push(...batch); next(); } }, () => resolve(all));
      next();
    });
    const walk = async (entry, dir) => {
      if (entry.isFile) {
        const file = await new Promise(resolve => entry.file(resolve, () => resolve(null)));
        if (file) out.push({ file, dir });
      } else if (entry.isDirectory) {
        const sub = joinPath(dir, entry.name);
        out.push({ mkdir: sub });
        for (const child of await readAll(entry.createReader())) await walk(child, sub);
      }
    };
    for (const entry of items) await walk(entry, base);
    return out;
  }

  /** Uploads in de rij zetten. onDone komt als ze klaar zijn (voor andere pagina's, zoals backups). */
  function queueUploads(items, root = fm.root, onDone = null) {
    for (const item of items) {
      item.root = root;
      item.onDone = onDone;
      item.loaded = 0;
      item.status = 'wachten';
      uploads.queue.push(item);
    }
    renderUploads();
    if (!uploads.running) runUploads();
  }

  function renderUploads() {
    const visible = uploads.queue.filter(item => item.file);
    if (!visible.length) { if (uploads.panel) { uploads.panel.remove(); uploads.panel = null; } return; }
    if (!uploads.panel) {
      uploads.panel = document.createElement('div');
      uploads.panel.className = 'uploads card';
      document.body.append(uploads.panel);
    }
    const doneCount = visible.filter(item => item.status === 'klaar').length;
    const all = visible.every(item => ['klaar', 'mislukt', 'overgeslagen', 'gestopt'].includes(item.status));
    render(uploads.panel, html`<div class="uploads-head"><b>${all ? 'Uploads klaar' : 'Uploaden'}</b><span class="muted small">${num(doneCount)} / ${num(visible.length)}</span>
        ${all ? html`<button class="btn sm ghost icon-only" data-action="uploads-close" aria-label="Sluiten">${icon('x')}</button>` : ''}</div>
      <div class="uploads-list">${visible.slice(-30).map(item => {
        const pctDone = item.file.size ? Math.round(item.loaded * 100 / item.file.size) : 100;
        return html`<div class="upload-item ${item.status === 'mislukt' ? 'failed' : ''}">
          <div class="upload-top"><span class="upload-name" title="${joinPath(item.dir, item.file.name)}">${item.file.name}</span>
            <span class="muted small">${item.status === 'bezig' ? `${pctDone}%` : item.status}</span></div>
          <div class="meter ${item.status === 'mislukt' ? 'error' : ''}"><i style="width:${item.status === 'klaar' ? 100 : pctDone}%"></i></div>
          ${item.error ? html`<div class="small error-text">${item.error}</div>` : ''}</div>`;
      })}</div>
      ${!all ? html`<div class="uploads-foot"><button class="btn sm ghost" data-action="uploads-stop">Stoppen</button></div>` : ''}`);
  }

  actions['uploads-close'] = () => { uploads.queue = []; renderUploads(); };
  actions['uploads-stop'] = () => {
    for (const item of uploads.queue) {
      if (item.status === 'wachten') item.status = 'gestopt';
      if (item.status === 'bezig') { item.stop = true; if (item.xhr) item.xhr.abort(); }
    }
    renderUploads();
  };

  function sendChunk(item, url, blob) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      item.xhr = xhr;
      xhr.open('POST', '/api' + url);
      xhr.setRequestHeader('X-Pinda-Host', '1');
      xhr.setRequestHeader('Content-Type', 'application/octet-stream');
      const base = item.loaded;
      xhr.upload.onprogress = event => { item.loaded = base + event.loaded; renderUploadsSoon(); };
      xhr.onload = () => {
        let body = null;
        try { body = JSON.parse(xhr.responseText); } catch { /* geen JSON */ }
        if (xhr.status >= 200 && xhr.status < 300) resolve(body);
        else reject(new Error(body && body.error ? body.error : `Upload mislukt (${xhr.status})`));
      };
      xhr.onerror = () => reject(new Error('Geen verbinding'));
      xhr.onabort = () => reject(new Error('Gestopt'));
      xhr.send(blob);
    });
  }

  let renderTimer = null;
  function renderUploadsSoon() {
    if (renderTimer) return;
    renderTimer = setTimeout(() => { renderTimer = null; renderUploads(); }, 150);
  }

  async function uploadOne(item) {
    const { file, dir, root } = item;
    let start = await post(`/files/${root}/upload`, { dir, name: file.name, size: file.size, overwrite: !!item.overwrite });
    if (start.exists) {
      const ok = await confirmDialog({ title: `${file.name} bestaat al`, text: `In ${dir === '.' ? 'de hoofdmap' : dir}. Overschrijven?`, confirm: 'Overschrijven' });
      if (!ok) { item.status = 'overgeslagen'; return; }
      start = await post(`/files/${root}/upload`, { dir, name: file.name, size: file.size, overwrite: true });
    }
    const chunk = start.chunk || 16 * 1024 * 1024;
    item.loaded = 0;
    try {
      for (let offset = 0; offset < file.size; offset += chunk) {
        if (item.stop) throw new Error('Gestopt');
        let attempt = 0;
        for (;;) {
          try {
            await sendChunk(item, `/files/${root}/upload/${start.id}?offset=${offset}`, file.slice(offset, offset + chunk));
            break;
          } catch (error) {
            // Een haperende verbinding: nog twee keer proberen.
            if (item.stop || ++attempt > 2 || !/verbinding/i.test(error.message)) throw error;
            item.loaded = offset;
            await new Promise(resolve => setTimeout(resolve, 1500 * attempt));
          }
        }
        item.loaded = Math.min(file.size, offset + chunk);
      }
      await post(`/files/${root}/upload/${start.id}/finish`);
    } catch (error) {
      post(`/files/${root}/upload/${start.id}/cancel`).catch(() => {});
      throw error;
    }
  }

  async function runUploads() {
    uploads.running = true;
    let changed = false;
    for (;;) {
      const item = uploads.queue.find(i => i.status === 'wachten');
      if (!item) break;
      item.status = 'bezig';
      renderUploads();
      try {
        if (item.mkdir) {
          await post(`/files/${item.root}/create`, { path: item.mkdir, dir: true }).catch(error => { if (error.status !== 409) throw error; });
          item.status = 'klaar';
        } else {
          await uploadOne(item);
          if (item.status === 'bezig') item.status = 'klaar';
        }
        changed = true;
      } catch (error) {
        item.status = item.stop ? 'gestopt' : 'mislukt';
        item.error = item.stop ? '' : error.message;
      }
      renderUploads();
    }
    uploads.running = false;
    const callbacks = new Set(uploads.queue.map(item => item.onDone).filter(Boolean));
    uploads.queue.forEach(item => { item.onDone = null; });
    callbacks.forEach(callback => callback());
    if (changed && fm.root && location.hash.startsWith(`#/${fm.route}`) && !location.hash.includes('?bewerken')) reloadList();
  }

  // =============================================================== editor

  async function openEditor(main, alive, path) {
    const file = await api(`/files/${fm.root}/read?path=${encodeURIComponent(path)}`);
    if (!alive()) return;
    const dir = parentOf(path);
    const download = `/api/files/${fm.root}/download?path=${encodeURIComponent(path)}`;
    const head = html`<div class="editor-head">
      ${crumbs(path, true)}
      <div class="btn-row">
        <a class="btn sm" href="${download}" download>${icon('download')}<span class="hide-sm">Downloaden</span></a>
        <a class="btn sm" href="${listHash(fm.route, dir)}">${icon('x')} Sluiten</a>
        ${!file.binary && !file.tooLarge ? html`<button class="btn sm primary" data-action="editor-save" id="editor-save" disabled>${icon('save')} Opslaan</button>` : ''}
      </div></div>`;
    if (file.binary || file.tooLarge) {
      render(main, html`${head}<div class="card"><div class="empty-page">
        ${icon('file')}<h2>${file.binary ? 'Dit is geen tekstbestand' : 'Dit bestand is te groot voor de editor'}</h2>
        <p class="muted">${bytes(file.size)} · ${dateTime(file.modified)}. Je kunt het wel downloaden${file.binary ? '' : ' en bewerken op je eigen computer'}.</p>
        <a class="btn primary" href="${download}" download>${icon('download')} Downloaden</a></div></div>`);
      return;
    }
    render(main, html`${head}
      <div class="editor card" id="editor"></div>
      <div class="editor-status"><span id="editor-pos">Regel 1, kolom 1</span><span>${languageName(path)}</span><span>${bytes(file.size)}</span>
        <span class="hide-sm">Ctrl+S = opslaan · Tab = inspringen</span></div>`);
    const editor = codeEditor($('#editor', main), file.content, languageOf(path));
    const doc = { path, modified: file.modified, saved: file.content, editor };
    state.editorDoc = doc;
    const setDirty = () => {
      const dirty = editor.value() !== doc.saved;
      state.dirty = dirty;
      const button = $('#editor-save');
      if (button) button.disabled = !dirty;
      document.title = `${dirty ? '● ' : ''}${baseName(path)} · Dev-paneel`;
    };
    editor.onChange = setDirty;
    editor.onSave = () => saveEditor();
    editor.onCursor = (line, column) => { const pos = $('#editor-pos'); if (pos) pos.textContent = `Regel ${line}, kolom ${column}`; };
    state.leaveGuard = async () => {
      if (!state.dirty) return true;
      const ok = await confirmDialog({ title: 'Niet opgeslagen', text: `${baseName(path)} heeft wijzigingen die nog niet zijn opgeslagen. Weggooien?`, confirm: 'Weggooien', danger: true });
      if (ok) state.dirty = false;
      return ok;
    };
    editor.focus();
  }

  async function saveEditor(force = false) {
    const doc = state.editorDoc;
    if (!doc || state.saving) return;
    const content = doc.editor.value();
    state.saving = true;
    const button = $('#editor-save');
    try {
      if (button) button.classList.add('busy');
      const result = await post(`/files/${fm.root}/write`, { path: doc.path, content, modified: force ? 0 : doc.modified });
      doc.modified = result.modified;
      doc.saved = content;
      doc.editor.onChange();
      toast('Opgeslagen.');
    } catch (error) {
      if (error.status === 409) {
        const ok = await confirmDialog({ title: 'Het bestand is intussen veranderd', text: `${error.message} Toch opslaan? Dan gaan de andere wijzigingen verloren.`, confirm: 'Toch opslaan', danger: true });
        state.saving = false;
        if (button) button.classList.remove('busy');
        if (ok) return saveEditor(true);
        return;
      }
      if (error.status !== 401) toast(error.message, 'error');
    } finally {
      state.saving = false;
      if (button) button.classList.remove('busy');
    }
  }
  actions['editor-save'] = () => saveEditor();

  // =============================================================== code-editor met kleuren

  function languageOf(path) {
    const name = baseName(path).toLowerCase();
    if (/\.ya?ml$/.test(name)) return 'yaml';
    if (/\.(properties|env|ini|cfg|conf|toml|lang)$/.test(name) || name === 'eula.txt') return 'properties';
    if (/\.(json|json5|mcmeta)$/.test(name)) return 'json';
    if (/\.html?$/.test(name) || name.endsWith('.svg') || name.endsWith('.xml')) return 'html';
    if (name.endsWith('.css')) return 'css';
    if (/\.(m?js|ts)$/.test(name)) return 'js';
    if (/\.(log|txt)$/.test(name)) return 'log';
    if (/\.(sh|bat|cmd)$/.test(name)) return 'shell';
    return 'text';
  }
  const LANGUAGE_NAMES = { yaml: 'YAML', properties: 'Properties', json: 'JSON', html: 'HTML', css: 'CSS', js: 'JavaScript', log: 'Tekst', shell: 'Script', text: 'Tekst' };
  const languageName = path => LANGUAGE_NAMES[languageOf(path)];

  const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;' };
  const esc = text => text.replace(/[&<>]/g, c => ESC[c]);
  const span = (cls, text) => text ? `<span class="t-${cls}">${esc(text)}</span>` : '';

  /** Zoekt commentaar dat niet in een string staat. */
  function splitComment(line, marks) {
    let quote = null;
    for (let i = 0; i < line.length; i++) {
      const c = line[i];
      if (quote) { if (c === '\\') i++; else if (c === quote) quote = null; continue; }
      if (c === '"' || c === "'") { quote = c; continue; }
      for (const mark of marks) {
        if (line.startsWith(mark, i) && (i === 0 || /\s/.test(line[i - 1]))) return [line.slice(0, i), line.slice(i)];
      }
    }
    return [line, ''];
  }

  function valueTokens(text) {
    return text.replace(/("(?:[^"\\]|\\.)*"?|'(?:[^']|'')*'?)|(\b(?:true|false|yes|no|on|off|null|~)\b)|(-?\b\d+(?:\.\d+)?\b)|([^"']+?)/gi, (match, str, bool, number) => {
      if (str) return span('str', str);
      if (bool) return span('bool', bool);
      if (number) return span('num', number);
      return esc(match);
    });
  }

  const HIGHLIGHT = {
    yaml(line) {
      const [code, comment] = splitComment(line, ['#']);
      const m = code.match(/^(\s*)(-\s+)?((?:"[^"]*"|'[^']*'|[^\s:#"'][^:#]*?))(\s*:)(?=\s|$)(.*)$/);
      let out;
      if (m) out = esc(m[1]) + span('pun', m[2] || '') + span('key', m[3]) + span('pun', m[4]) + valueTokens(m[5]);
      else {
        const d = code.match(/^(\s*)(-\s+)(.*)$/);
        out = d ? esc(d[1]) + span('pun', d[2]) + valueTokens(d[3]) : valueTokens(code);
      }
      return out + span('com', comment);
    },
    properties(line) {
      if (/^\s*[#!;]/.test(line) || /^\s*\[.*\]\s*$/.test(line)) return /^\s*\[/.test(line) ? span('key', line) : span('com', line);
      const m = line.match(/^(\s*[^=:\s]+)(\s*[=:]\s*)(.*)$/);
      return m ? span('key', m[1]) + span('pun', m[2]) + valueTokens(m[3]) : esc(line);
    },
    json(line) {
      return line.replace(/("(?:[^"\\]|\\.)*")(\s*:)?|(\b(?:true|false|null)\b)|(-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b)|([^"]+?)/g, (match, str, colon, bool, number) => {
        if (str) return colon ? span('key', str) + span('pun', colon) : span('str', str);
        if (bool) return span('bool', bool);
        if (number) return span('num', number);
        return esc(match);
      });
    },
    html(line) {
      return line.replace(/(<!--.*?-->)|(<\/?[\w:-]+)|(\s[\w:-]+)(=)("[^"]*"|'[^']*')|(\/?>)|([^<>\s=]+|\s+|.)/g, (match, comment, tag, attr, eq, value, close) => {
        if (comment) return span('com', comment);
        if (tag) return span('key', tag);
        if (attr) return span('attr', attr) + span('pun', eq) + span('str', value);
        if (close) return span('key', close);
        return esc(match);
      });
    },
    css(line) {
      const [code, comment] = splitComment(line, ['/*', '//']);
      return code.replace(/([\w-]+)(\s*:)(?!\/)|("(?:[^"\\]|\\.)*"|'[^']*')|(#[0-9a-fA-F]{3,8}\b|-?\b\d+(?:\.\d+)?(?:px|em|rem|%|vh|vw|s|ms)?\b)|([^"'#\w-]+|.)/g, (match, prop, colon, str, number) => {
        if (prop) return span('key', prop) + span('pun', colon);
        if (str) return span('str', str);
        if (number) return span('num', number);
        return esc(match);
      }) + span('com', comment);
    },
    js(line) {
      const [code, comment] = splitComment(line, ['//']);
      return code.replace(/("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|`[^`]*`)|(\b(?:const|let|var|function|return|if|else|for|while|class|new|import|export|from|async|await|try|catch|throw|true|false|null|undefined|this)\b)|(\b\d+(?:\.\d+)?\b)|([^"'`\w]+|\w+|.)/g, (match, str, keyword, number) => {
        if (str) return span('str', str);
        if (keyword) return span('kw', keyword);
        if (number) return span('num', number);
        return esc(match);
      }) + span('com', comment);
    },
    shell(line) {
      const [code, comment] = splitComment(line, ['#']);
      return valueTokens(code) + span('com', comment);
    },
    log(line) {
      if (/\b(ERROR|SEVERE|FATAL|Exception)\b/.test(line)) return span('err', line);
      if (/\bWARN(ING)?\b/.test(line)) return span('warn', line);
      return esc(line);
    },
    text: line => esc(line)
  };

  /** Een simpele code-editor: een textarea met daaronder een gekleurde kopie. */
  function codeEditor(container, value, language) {
    render(container, html`<div class="code">
      <div class="code-gutter" aria-hidden="true"><pre class="code-lines"></pre></div>
      <div class="code-area">
        <pre class="code-hl" aria-hidden="true"><code></code></pre>
        <textarea class="code-input" spellcheck="false" autocapitalize="off" autocomplete="off" autocorrect="off" wrap="off" aria-label="Inhoud van het bestand"></textarea>
      </div></div>`);
    const input = $('.code-input', container);
    const hl = $('.code-hl code', container);
    const pre = $('.code-hl', container);
    const lines = $('.code-lines', container);
    const gutter = $('.code-gutter', container);
    input.value = value;
    const highlighter = HIGHLIGHT[language] || HIGHLIGHT.text;
    let lineCount = 0;
    let frame = null;
    const api = { onChange: () => {}, onSave: () => {}, onCursor: () => {}, value: () => input.value, focus: () => input.focus() };

    const paint = () => {
      frame = null;
      const text = input.value;
      const big = text.length > 400000;
      hl.innerHTML = (big ? esc(text) : text.split('\n').map(highlighter).join('\n')) + '\n';
      const count = text.split('\n').length;
      if (count !== lineCount) {
        lineCount = count;
        lines.textContent = Array.from({ length: count }, (_, i) => i + 1).join('\n') + '\n';
      }
      sync();
    };
    const schedule = () => { if (!frame) frame = requestAnimationFrame(paint); };
    const sync = () => {
      pre.scrollTop = input.scrollTop;
      pre.scrollLeft = input.scrollLeft;
      gutter.scrollTop = input.scrollTop;
    };
    const cursor = () => {
      const before = input.value.slice(0, input.selectionStart);
      const line = before.split('\n').length;
      api.onCursor(line, input.selectionStart - before.lastIndexOf('\n'));
    };
    const insert = text => {
      // execCommand houdt ongedaan maken (Ctrl+Z) intact.
      if (!document.execCommand('insertText', false, text)) input.setRangeText(text, input.selectionStart, input.selectionEnd, 'end');
    };

    input.addEventListener('input', () => { schedule(); api.onChange(); });
    input.addEventListener('scroll', sync);
    input.addEventListener('keyup', cursor);
    input.addEventListener('click', cursor);
    input.addEventListener('keydown', event => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault();
        api.onSave();
        return;
      }
      if (event.key === 'Tab' && !event.ctrlKey && !event.altKey && !event.metaKey) {
        event.preventDefault();
        const { selectionStart: start, selectionEnd: end, value: text } = input;
        const lineStart = text.lastIndexOf('\n', start - 1) + 1;
        if (start === end && !event.shiftKey) { insert('  '); return; }
        // Meerdere regels (of shift+tab): hele regels in- of uitspringen.
        const lineEnd = text.indexOf('\n', end - (end > start && text[end - 1] === '\n' ? 1 : 0));
        const blockEnd = lineEnd < 0 ? text.length : lineEnd;
        const block = text.slice(lineStart, blockEnd);
        const changed = block.split('\n').map(l => event.shiftKey ? l.replace(/^ {1,2}|^\t/, '') : '  ' + l).join('\n');
        input.setSelectionRange(lineStart, blockEnd);
        insert(changed);
        input.setSelectionRange(lineStart, lineStart + changed.length);
        return;
      }
      if (event.key === 'Enter' && !event.ctrlKey && !event.metaKey && !event.altKey) {
        const { selectionStart: start, value: text } = input;
        const lineStart = text.lastIndexOf('\n', start - 1) + 1;
        const current = text.slice(lineStart, start);
        let indent = current.match(/^\s*/)[0];
        if (language === 'yaml' && /:\s*$/.test(current)) indent += '  ';
        if (language === 'yaml' && /^\s*-\s/.test(current) && !/:\s*$/.test(current)) indent = current.match(/^\s*/)[0] + '- ';
        if (indent) { event.preventDefault(); insert('\n' + indent); }
      }
    });
    paint();
    return api;
  }

  // =============================================================== de pagina's

  window.PH.pages.files = (main, alive, rest) => fileBrowser(main, alive, rest, {
    root: 'server', route: 'bestanden', rootLabel: 'server', title: 'Bestanden',
    intro: 'Alle bestanden van de Minecraft-server: configuratie, plugins, werelden en logs.'
  });

  // =============================================================== website

  async function websiteStatus(path) {
    if (path !== '.') return '';
    let site;
    try { site = await api('/website'); } catch (error) { return html`<div class="alert error">${error.message}</div>`; }
    const domain = site.domain;
    const ip = (site.addresses || [])[0];
    const url = domain ? `https://${domain}` : ip ? `http://${ip}` : '';
    const https = {
      cloudflare: html`<span class="badge success">${icon('shield')} Via Cloudflare</span>`,
      letsencrypt: html`<span class="badge success">${icon('lock')} Let's Encrypt</span>`,
      none: domain ? html`<span class="badge warning">Nog geen certificaat</span>` : html`<span class="badge plain">Geen domein</span>`
    }[site.https];
    const records = (site.dns || []).map(r => html`<tr><td class="mono">${r.type}</td><td class="mono">${r.name}</td><td class="mono">${r.content}</td>
      <td>${r.proxy ? html`<span class="badge ${r.proxy === 'aan' ? 'warning' : 'plain'}">${r.proxy === 'aan' ? 'Proxy aan' : 'DNS only'}</span>` : ''}</td></tr>`);
    const certButton = site.https !== 'cloudflare' && domain && window.PH.state.user.admin
      ? html`<button class="btn sm" data-action="website-cert">${icon('lock')} ${site.https === 'letsencrypt' ? 'Certificaat vernieuwen' : 'HTTPS-certificaat aanvragen'}</button>` : '';
    return html`<div class="grid two website-grid">
      ${card('Online', 'globe', html`<div class="card-body"><dl class="kv">
          <dt>Adres</dt><dd>${url ? html`<a href="${url}" target="_blank" rel="noopener">${url.replace(/^https?:\/\//, '')} ${icon('external')}</a>` : '—'}</dd>
          <dt>HTTPS</dt><dd>${https}</dd>
          ${site.certificate ? html`<dt>Geldig tot</dt><dd>${dateTime(site.certificate.expires)} <span class="muted small">(wordt vanzelf verlengd)</span></dd>` : ''}
          <dt>Webserver</dt><dd>${site.nginx.active ? html`<span class="badge success">nginx draait</span>` : html`<span class="badge error">nginx staat uit</span>`}</dd>
        </dl>
        ${site.https === 'none' && domain && !site.cloudflare ? html`<p class="muted small" style="margin-top:12px">Zodra ${domain} naar deze server wijst, kun je een gratis certificaat van Let's Encrypt aanvragen.</p>` : ''}
        ${certButton ? html`<div class="btn-row" style="margin-top:12px">${certButton}</div>` : ''}</div>`)}
      ${card('DNS', 'web', domain ? html`<div class="table-wrap"><table><thead><tr><th>Type</th><th>Naam</th><th>Inhoud</th><th></th></tr></thead>
          <tbody>${records}</tbody></table></div>
          <p class="card-body muted small">${site.cloudflare ? 'Zet deze regels in Cloudflare met de proxy aan (oranje wolk), bij SSL/TLS modus Full.' : 'Zet deze regels bij je domeinnaam.'}</p>`
        : html`<div class="card-body muted">Er is nog geen domein voor de website. ${ip ? html`Zonder domein staat hij op <a href="http://${ip}" target="_blank" rel="noopener">http://${ip}</a>.` : ''}</div>`)}
    </div>`;
  }

  actions['website-cert'] = async button => {
    const job = await busy(button, () => post('/website/certificate'));
    if (job) watchJob(job, () => window.PH.reloadPage());
  };

  window.PH.pages.website = (main, alive, rest) => fileBrowser(main, alive, rest, {
    root: 'website', route: 'website', rootLabel: 'website', title: 'Website',
    intro: 'Alles in deze map staat online op het domein van de website. Een index.html is de voorpagina.',
    extra: websiteStatus
  });

  window.PH.fileBrowser = fileBrowser;
  window.PH.queueUploads = queueUploads;
  window.PH.listHash = listHash;
  window.PH.editHash = editHash;
})();
