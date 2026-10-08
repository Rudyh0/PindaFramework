/* PindaHost - de Minecraft-server: installeren, starten/stoppen, console, plugins en instellingen. */
'use strict';

(() => {
  const { $, $$, html, render, icon, api, post, toast, openModal, closeModal, confirmDialog, busy,
    pageHead, card, emptyRow, every, watchJob, actions, forms, state, bytes, num, ago, uptime, dateTime, pct } = window.PH;

  const STATE_LABELS = {
    active: ['Online', 'success'], inactive: ['Offline', 'plain'], failed: ['Gecrasht', 'error'],
    activating: ['Start op', 'info'], deactivating: ['Stopt', 'warning'], unknown: ['Onbekend', 'plain']
  };
  const TABS = [['console', 'Console', 'terminal'], ['plugins', 'Plugins', 'plug'], ['instellingen', 'Instellingen', 'sliders']];
  const isAdmin = () => !!(state.user && state.user.admin);

  const srv = { data: null, tab: 'console', console: { offset: 0, inode: 0, lines: 0, history: [], historyIndex: -1 } };

  // =============================================================== pagina

  window.PH.pages.server = async (main, alive, rest) => {
    const tab = TABS.some(([key]) => key === rest) ? rest : 'console';
    srv.tab = tab;
    const data = await api('/server');
    if (!alive()) return;
    srv.data = data;
    if (!data.installed) return renderInstall(main, alive, data);
    renderServer(main, data);
    every(4000, () => refreshStatus(alive));
    if (tab === 'console') {
      srv.console = { ...srv.console, offset: 0, inode: 0, lines: 0 };
      await pollConsole(alive);
      every(1500, () => pollConsole(alive));
    } else if (tab === 'plugins') {
      await loadPlugins(alive);
    } else {
      await renderSettings(alive);
    }
  };

  function powerButtons(data) {
    const s = data.state;
    const running = s === 'active';
    const busyState = s === 'activating' || s === 'deactivating' || data.busy;
    return html`<button class="btn primary" data-action="server-power" data-power="start" ${running || busyState ? 'disabled' : ''}>${icon('play')} Starten</button>
      <button class="btn" data-action="server-power" data-power="restart" ${!running || busyState ? 'disabled' : ''}>${icon('restart')} Herstarten</button>
      ${s === 'deactivating'
        ? html`<button class="btn solid-danger" data-action="server-power" data-power="kill">${icon('bolt')} Afbreken</button>`
        : html`<button class="btn danger" data-action="server-power" data-power="stop" ${!running ? 'disabled' : ''}>${icon('stop')} Stoppen</button>`}`;
  }

  function statusBadge(data) {
    const [label, cls] = data.busy ? ['Bezig…', 'info'] : (STATE_LABELS[data.state] || [data.state, 'plain']);
    return html`<span class="badge ${cls}"><span class="dot ${data.state === 'active' ? 'on' : ''}"></span>${label}</span>`;
  }

  function stats(data) {
    const players = data.players;
    const memoryMax = data.memoryMB * 1024 * 1024;
    return html`
      <div class="card stat"><div class="stat-label">Status</div><div class="stat-icon">${icon('server')}</div>
        <div class="stat-value">${statusBadge(data)}</div>
        <div class="stat-sub">${data.state === 'active' && data.since ? `online sinds ${uptime(Date.now() - data.since)}` : data.state === 'failed' ? 'kijk in de console wat er misging' : '—'}</div></div>
      <div class="card stat"><div class="stat-label">Spelers</div><div class="stat-icon">${icon('players')}</div>
        <div class="stat-value">${players ? `${num(players.online)} / ${num(players.max)}` : '—'}</div>
        <div class="stat-sub wrap">${players && players.names.length ? players.names.join(', ') : data.state === 'active' ? 'niemand online' : 'server staat uit'}</div></div>
      <div class="card stat"><div class="stat-label">Geheugen</div><div class="stat-icon">${icon('memory')}</div>
        <div class="stat-value">${data.memoryUsed ? bytes(data.memoryUsed) : '—'}</div>
        <div class="meter ${pct(data.memoryUsed, data.memTotal) > 90 ? 'warning' : ''}"><i style="width:${Math.min(100, pct(data.memoryUsed, data.memTotal))}%"></i></div>
        <div class="stat-sub">${bytes(memoryMax)} toegewezen · VPS ${bytes(data.memTotal)}</div></div>
      <div class="card stat"><div class="stat-label">Versie</div><div class="stat-icon">${icon('cube')}</div>
        <div class="stat-value">${data.version}</div>
        <div class="stat-sub">Purpur build ${data.build}${data.java ? ` · Java ${data.java}` : ''}</div></div>`;
  }

  function renderServer(main, data) {
    const address = data.address ? `${data.address}${data.port !== 25565 ? `:${data.port}` : ''}` : `poort ${data.port}`;
    render(main, html`<div class="page-head server-head"><div><h1>${state.info.serverName || 'Minecraft-server'}</h1>
        <p><span class="mono">${address}</span> · <span id="server-badge">${statusBadge(data)}</span></p></div>
        <div class="page-actions" id="server-power">${powerButtons(data)}</div></div>
      ${!data.eula ? html`<div class="alert info">${icon('warn')} <div>De server is geïnstalleerd. Klik op <b>Starten</b>: je krijgt eerst de Minecraft EULA te zien, daarna worden PindaFramework en de standaardplugins neergezet en start de server.</div></div>` : ''}
      ${data.eula && !data.pluginsReady ? html`<div class="alert warning">${icon('warn')} <div>De standaardplugins zijn nog niet (helemaal) neergezet. Klik op <b>Starten</b> om het opnieuw te proberen.</div></div>` : ''}
      <div class="grid stats server-stats" id="server-stats">${stats(data)}</div>
      <nav class="tabs server-tabs">${TABS.map(([key, label, ic]) => html`<a href="#/server${key === 'console' ? '' : '/' + key}" class="${srv.tab === key ? 'active' : ''}">${icon(ic)} ${label}</a>`)}</nav>
      <div id="server-tab">${srv.tab === 'console' ? consoleView() : html`<div class="page-loading"><div class="spinner"></div></div>`}</div>`);
    if (srv.tab === 'console') bindConsole(main);
  }

  async function refreshStatus(alive) {
    let data;
    try { data = await api('/server'); } catch { return; }
    if (!alive() || !$('#server-power')) return;
    const changed = !srv.data || srv.data.state !== data.state || srv.data.busy !== data.busy;
    srv.data = data;
    render($('#server-stats'), stats(data));
    if (changed) {
      render($('#server-power'), powerButtons(data));
      render($('#server-badge'), statusBadge(data));
    }
  }

  // =============================================================== aan, uit, EULA

  actions['server-power'] = async button => {
    const action = button.dataset.power;
    if (action === 'stop' && !await confirmDialog({ title: 'Server stoppen?', text: 'Spelers worden van de server gehaald. De wereld wordt eerst bewaard.', confirm: 'Stoppen', danger: true })) return;
    if (action === 'kill' && !await confirmDialog({ title: 'Server afbreken?', text: 'Alleen als stoppen blijft hangen: de server wordt meteen uitgezet, zonder de wereld te bewaren.', confirm: 'Afbreken', danger: true })) return;
    if (action === 'restart' && !await confirmDialog({ title: 'Server herstarten?', text: 'Spelers worden even van de server gehaald.', confirm: 'Herstarten' })) return;
    const result = await busy(button, () => post('/server/power', { action }));
    if (!result) return;
    if (result.needsEula) return showEula(result.eulaUrl);
    if (result.job) return watchJob(result.job, () => window.PH.reloadPage());
    toast({ start: 'De server start.', restart: 'De server herstart.', stop: 'De server stopt.', kill: 'De server is afgebroken.' }[action]);
    setTimeout(() => refreshStatus(() => true), 600);
  };

  function showEula(url) {
    openModal(html`<form data-form="server-eula">
      <div class="modal-head"><h3>Minecraft EULA</h3><p>Voordat de server de eerste keer start.</p></div>
      <div class="modal-body stack">
        <p class="muted">Om een Minecraft-server te draaien moet je akkoord gaan met de End User License Agreement van Mojang. Daarin staat onder andere dat je geen geld mag vragen voor dingen die het spel oneerlijk maken.</p>
        <a class="btn" href="${url}" target="_blank" rel="noopener noreferrer">${icon('external')} De EULA lezen</a>
        <label class="check"><input type="checkbox" name="accept" required> Ik heb de EULA gelezen en ga ermee akkoord</label>
        <p class="muted small">Daarna zet het paneel PindaFramework, ViaVersion, PlaceholderAPI, CoreProtect en WorldEdit neer (de nieuwste versies voor deze Minecraft-versie) en start de server.</p>
      </div>
      <div class="modal-foot"><button type="button" class="btn ghost" data-action="close">Annuleren</button>
        <button class="btn primary">${icon('check')} Akkoord en starten</button></div></form>`);
  }

  forms['server-eula'] = async (form, data, button) => {
    if (!data.get('accept')) { toast('Vink aan dat je akkoord gaat.', 'error'); return; }
    const result = await busy(button, () => post('/server/eula', { accept: true }));
    if (!result) return;
    if (result.job) watchJob(result.job, () => window.PH.reloadPage());
  };

  // =============================================================== console

  function consoleView() {
    return html`<div class="console" id="console" role="log" aria-live="off"></div>
      <form data-form="server-command" class="console-form">
        <div class="prompt"><input class="input" name="command" id="console-input" autocomplete="off" spellcheck="false" placeholder="Opdracht, bijv. list of say Hallo allemaal" aria-label="Opdracht"></div>
        <button class="btn primary">${icon('arrow')}<span class="hide-sm">Versturen</span></button></form>
      <p class="muted small console-hint">Opdrachten gaan via RCON; het antwoord zie je hier in het geel. Pijltje omhoog = vorige opdracht.</p>`;
  }

  function lineClass(line) {
    if (/\b(ERROR|SEVERE|FATAL)\b|Exception|^\s+at /.test(line)) return 'err';
    if (/\bWARN(ING)?\b/.test(line)) return 'warn';
    if (/Done \(|For help, type/.test(line)) return 'ok';
    return '';
  }

  function appendConsole(text, reset, extraClass = '') {
    const box = $('#console');
    if (!box) return;
    const atEnd = box.scrollTop + box.clientHeight >= box.scrollHeight - 30;
    if (reset) { box.textContent = ''; srv.console.lines = 0; }
    const fragment = document.createDocumentFragment();
    for (const line of text.replace(/\n$/, '').split('\n')) {
      if (!text) break;
      const div = document.createElement('div');
      const cls = extraClass || lineClass(line);
      if (cls) div.className = cls;
      div.textContent = line;
      fragment.append(div);
      srv.console.lines++;
    }
    box.append(fragment);
    while (srv.console.lines > 2000 && box.firstChild) { box.firstChild.remove(); srv.console.lines--; }
    if (atEnd || reset) box.scrollTop = box.scrollHeight;
  }

  async function pollConsole(alive) {
    if (!$('#console')) return;
    let chunk;
    try {
      chunk = await api(`/server/console?offset=${srv.console.offset}&inode=${srv.console.inode}`);
    } catch { return; }
    if (!alive() || !$('#console')) return;
    if (chunk.source !== 'log') {
      // Nog geen logbestand (de server is nooit gestart, of start net): laat zien wat er is.
      if (chunk.text !== srv.console.fallback) {
        srv.console.fallback = chunk.text;
        appendConsole(chunk.text || 'Nog geen uitvoer. Start de server om hier mee te kijken.', true, chunk.text ? '' : 'muted');
      }
      srv.console.offset = 0;
      srv.console.inode = 0;
      return;
    }
    srv.console.fallback = null;
    if (chunk.reset || chunk.text) appendConsole(chunk.text, chunk.reset);
    srv.console.offset = chunk.offset;
    srv.console.inode = chunk.inode;
  }

  function bindConsole(main) {
    const input = $('#console-input', main);
    if (!input) return;
    input.addEventListener('keydown', event => {
      const history = srv.console.history;
      if (event.key === 'ArrowUp' && history.length) {
        event.preventDefault();
        srv.console.historyIndex = Math.min(history.length - 1, srv.console.historyIndex + 1);
        input.value = history[history.length - 1 - srv.console.historyIndex];
      } else if (event.key === 'ArrowDown') {
        event.preventDefault();
        srv.console.historyIndex = Math.max(-1, srv.console.historyIndex - 1);
        input.value = srv.console.historyIndex < 0 ? '' : history[history.length - 1 - srv.console.historyIndex];
      }
    });
  }

  forms['server-command'] = async (form, data, button) => {
    const command = data.get('command').trim();
    if (!command) return;
    const history = srv.console.history;
    if (history[history.length - 1] !== command) history.push(command);
    if (history.length > 100) history.shift();
    srv.console.historyIndex = -1;
    form.reset();
    appendConsole(`> ${command}`, false, 'cmd');
    try {
      const result = await post('/server/command', { command });
      appendConsole(result.response || '(geen antwoord)', false, 'resp');
    } catch (error) {
      appendConsole(error.message, false, 'err');
    }
    $('#console-input')?.focus();
  };

  // =============================================================== plugins

  async function loadPlugins(alive) {
    const data = await api('/server/plugins');
    if (!alive() || !$('#server-tab')) return;
    const managed = data.managed.map(plugin => {
      const files = data.plugins.filter(p => p.managed === plugin.key);
      const installed = data.installed && data.installed[plugin.key];
      const tooOld = plugin.minVersion && compareVersions(data.minecraft, plugin.minVersion) < 0;
      const status = tooOld ? html`<span class="badge plain">Pas vanaf ${plugin.minVersion}</span>`
        : files.length ? (files.some(f => f.enabled) ? html`<span class="badge success">v${files[0].version || '?'}</span>` : html`<span class="badge warning">Staat uit</span>`)
          : html`<span class="badge plain">Niet geïnstalleerd</span>`;
      return html`<div class="plugin-card">
        <div class="plugin-top"><b>${plugin.name}</b>${status}</div>
        <p class="muted small">${plugin.about}</p>
        <div class="plugin-foot">
          <span class="muted small">${installed ? `bijgewerkt ${ago(installed.at)}${installed.beta ? ' · bèta' : ''}` : plugin.source === 'github' ? 'van GitHub' : 'van Modrinth'}</span>
          ${tooOld ? '' : html`<button class="btn sm" data-action="plugins-update" data-keys="${plugin.key}" ${data.busy ? 'disabled' : ''}>${icon('download')} ${files.length ? 'Bijwerken' : 'Installeren'}</button>`}
        </div></div>`;
    });
    const rows = data.plugins.length ? data.plugins.map(plugin => html`<tr class="${plugin.enabled ? '' : 'off'}">
        <td><b>${plugin.name}</b>${plugin.managed ? html` <span class="badge info">standaard</span>` : ''}${plugin.error ? html` <span class="badge error" title="${plugin.error}">onleesbaar</span>` : ''}
          ${plugin.description ? html`<div class="muted small plugin-desc">${plugin.description}</div>` : ''}</td>
        <td class="mono hide-sm">${plugin.version || '—'}</td>
        <td class="hide-sm"><span class="mono small muted">${plugin.file}</span></td>
        <td>${plugin.enabled ? html`<span class="badge success">Aan</span>` : html`<span class="badge plain">Uit</span>`}</td>
        <td class="actions">
          <button class="btn sm" data-action="plugin-toggle" data-file="${plugin.file}">${plugin.enabled ? 'Uitzetten' : 'Aanzetten'}</button>
          <button class="btn sm danger icon-only" data-action="plugin-delete" data-file="${plugin.file}" data-name="${plugin.name}" title="Verwijderen" aria-label="${plugin.name} verwijderen">${icon('trash')}</button>
        </td></tr>`) : emptyRow(5, 'Nog geen plugins. Ze komen er bij de eerste start vanzelf in.');
    render($('#server-tab'), html`
      ${data.running ? html`<div class="alert plain small">${icon('warn')} <div>Nieuwe of bijgewerkte plugins werken pas na een herstart van de server.</div></div>` : ''}
      ${card('Standaardplugins', 'plug', html`<div class="card-body"><div class="plugin-grid">${managed}</div>
        <p class="muted small" style="margin-top:14px">De rangen van PindaFramework regelen de rechten: PindaAdmin is operator en mag alles (ook WorldEdit), PindaMod mag met CoreProtect inspecteren, opzoeken en teleporteren. Aanpassen kan in <a href="${window.PH.editHash('bestanden', 'plugins/PindaFramework/modules/ranks.yml')}">ranks.yml</a>.</p></div>`,
        html`<button class="btn sm" data-action="plugins-update" data-keys="${data.managed.map(p => p.key).join(',')}" ${data.busy ? 'disabled' : ''}>${icon('refresh')} Alles bijwerken</button>`)}
      <div style="margin-top:16px">${card('Alle plugins', 'cube', html`<div class="table-wrap"><table>
        <thead><tr><th>Plugin</th><th class="hide-sm">Versie</th><th class="hide-sm">Bestand</th><th>Status</th><th></th></tr></thead>
        <tbody>${rows}</tbody></table></div>
        <div class="card-body muted small">Een eigen plugin toevoegen: upload het .jar-bestand naar <a href="${window.PH.listHash('bestanden', 'plugins')}">plugins</a>.</div>`,
        html`<span class="count">${num(data.plugins.length)}</span>`)}</div>`);
  }

  function compareVersions(a, b) {
    const as = String(a || '').split('.').map(Number);
    const bs = String(b || '').split('.').map(Number);
    for (let i = 0; i < Math.max(as.length, bs.length); i++) {
      const x = as[i] || 0;
      const y = bs[i] || 0;
      if (x !== y) return x < y ? -1 : 1;
    }
    return 0;
  }

  actions['plugins-update'] = async button => {
    const keys = button.dataset.keys.split(',');
    const job = await busy(button, () => post('/server/plugins/install', { keys }));
    if (job) watchJob(job, () => window.PH.reloadPage());
  };

  actions['plugin-toggle'] = async button => {
    const result = await busy(button, () => post('/server/plugins/toggle', { file: button.dataset.file }));
    if (!result) return;
    toast(result.restartNeeded ? 'Opgeslagen. Herstart de server om dit te laten werken.' : 'Opgeslagen.');
    window.PH.navigate();
  };

  actions['plugin-delete'] = async button => {
    const name = button.dataset.name;
    const core = name === 'PindaFramework';
    const ok = await confirmDialog({ title: `${name} verwijderen?`, confirm: 'Verwijderen', danger: true, typeToConfirm: core ? name : '',
      text: core ? 'Zonder PindaFramework werken de rangen, economy, het webpaneel en de rest niet meer. De instellingen en gegevens blijven wel bewaard.'
        : 'Het .jar-bestand wordt weggehaald. De instellingen in de map van de plugin blijven staan.' });
    if (!ok) return;
    const result = await busy(button, () => post('/server/plugins/delete', { file: button.dataset.file }));
    if (!result) return;
    toast(result.restartNeeded ? 'Verwijderd. Herstart de server om het te laten werken.' : 'Verwijderd.');
    window.PH.navigate();
  };

  // =============================================================== installeren en versies

  async function versionForm(installed) {
    let versions;
    try {
      versions = await api('/server/versions');
    } catch (error) {
      return html`<div class="alert error">${icon('warn')} ${error.message}</div>`;
    }
    srv.versions = versions;
    const admin = isAdmin();
    const options = versions.versions.map(v => html`<option value="${v.version}" ${v.version === (installed || versions.current) ? 'selected' : ''}>
      ${v.version}${v.recommended ? ' — aanbevolen' : v.experimental ? ' — experimenteel' : ''}${!v.framework ? ' (zonder PindaFramework)' : ''}${v.version === installed ? ' · nu geïnstalleerd' : ''}</option>`);
    return html`<form data-form="server-install" class="stack install-form">
      <div class="row">
        <label class="field">Minecraft-versie<select name="version" id="install-version" ${admin ? '' : 'disabled'}>${options}</select></label>
        <label class="field">Build<select name="build" id="install-build" ${admin ? '' : 'disabled'}><option value="latest">Nieuwste build</option></select></label>
      </div>
      <div id="install-note"></div>
      ${admin ? html`<div class="btn-row"><button class="btn primary">${icon('download')} ${installed ? 'Deze versie installeren' : 'Installeren'}</button></div>`
        : html`<p class="muted small">Alleen een beheerder kan de server installeren of van versie wisselen.</p>`}
    </form>`;
  }

  function versionNote(version, installed) {
    const v = srv.versions && srv.versions.versions.find(x => x.version === version);
    if (!v) return '';
    const notes = [];
    if (v.recommended) notes.push(html`<div class="alert success small">${icon('check')} <div>De nieuwste stabiele versie van Purpur. Aanbevolen.</div></div>`);
    if (v.experimental) notes.push(html`<div class="alert warning small">${icon('warn')} <div>Nieuwer dan de stabiele versie: nog experimenteel. Plugins werken misschien nog niet.</div></div>`);
    if (!v.framework) notes.push(html`<div class="alert warning small">${icon('warn')} <div>PindaFramework werkt pas vanaf Minecraft ${srv.versions.frameworkMin}. Op deze versie draait de server zonder PindaFramework.</div></div>`);
    if (installed && compareVersions(version, installed) < 0) notes.push(html`<div class="alert error small">${icon('warn')} <div>Ouder dan de versie die nu draait (${installed}). Een wereld kan niet zomaar terug naar een oudere versie: maak eerst een backup.</div></div>`);
    notes.push(html`<p class="muted small">Heeft Java ${v.java} nodig.</p>`);
    return notes;
  }

  async function bindVersionForm(installed) {
    const select = $('#install-version');
    if (!select) return;
    const update = async () => {
      render($('#install-note'), versionNote(select.value, installed));
      const builds = $('#install-build');
      render(builds, html`<option value="latest">Nieuwste build</option>`);
      try {
        const data = await api(`/server/versions/${encodeURIComponent(select.value)}`);
        if ($('#install-version')?.value !== data.version) return;
        render(builds, html`<option value="latest">Nieuwste build (${data.builds[0] || '?'})</option>${data.builds.map(build => html`<option value="${build}">Build ${build}</option>`)}`);
      } catch { /* dan alleen "nieuwste" */ }
    };
    select.addEventListener('change', update);
    await update();
  }

  async function renderInstall(main, alive, data) {
    render(main, html`${pageHead('Minecraft-server', 'Er staat nog geen server op deze VPS. Kies een versie: het paneel zet Purpur neer (een snelle, uitgebreide versie van Paper).')}
      <div class="grid two install-grid">
        ${card('Server installeren', 'download', html`<div class="card-body" id="install-body"><div class="page-loading"><div class="spinner"></div></div></div>`)}
        ${card('Wat er gebeurt', 'cube', html`<div class="card-body"><ol class="steps">
          <li><b>Installeren</b><span class="muted">Purpur wordt gedownload en gecontroleerd (controlegetal), en klaargezet als dienst.</span></li>
          <li><b>Starten en de EULA</b><span class="muted">Bij de eerste start ga je akkoord met de Minecraft EULA.</span></li>
          <li><b>Plugins</b><span class="muted">PindaFramework, ViaVersion, PlaceholderAPI, CoreProtect en WorldEdit, de nieuwste versies voor jouw Minecraft-versie.</span></li>
          <li><b>Spelen</b><span class="muted">Spelers komen erin via ${data.address || 'het adres uit de setup'}${data.port !== 25565 ? `:${data.port}` : ''}.</span></li>
        </ol></div>`)}
      </div>`);
    const form = await versionForm('');
    if (!alive()) return;
    render($('#install-body'), form);
    await bindVersionForm('');
  }

  forms['server-install'] = async (form, data, button) => {
    const version = data.get('version');
    const build = data.get('build') || 'latest';
    const installed = srv.data && srv.data.installed ? srv.data.version : '';
    const v = srv.versions && srv.versions.versions.find(x => x.version === version);
    let confirm = false;
    if (installed && compareVersions(version, installed) < 0) {
      if (!await confirmDialog({ title: `Terug naar ${version}?`, text: `De server draait nu ${installed}. Een wereld van een nieuwere versie kan stuk gaan op een oudere. Maak eerst een backup.`, confirm: 'Toch installeren', danger: true, typeToConfirm: version })) return;
      confirm = true;
    } else if (installed) {
      if (!await confirmDialog({ title: `Purpur ${version} installeren?`, text: 'Alleen server.jar wordt vervangen; de wereld, plugins en instellingen blijven staan. De plugins werk je daarna bij in het tabblad Plugins.', confirm: 'Installeren' })) return;
    } else if (v && v.experimental) {
      if (!await confirmDialog({ title: `Experimentele versie ${version}?`, text: 'Deze versie is nog niet stabiel. Plugins werken misschien nog niet.', confirm: 'Toch installeren' })) return;
    }
    const job = await busy(button, () => post('/server/install', { version, build, confirm }));
    if (job) watchJob(job, () => window.PH.reloadPage());
  };

  // =============================================================== instellingen

  async function renderSettings(alive) {
    const data = srv.data;
    const step = 256;
    render($('#server-tab'), html`<div class="grid two">
      ${card('Geheugen', 'memory', html`<form data-form="server-memory" class="card-body stack">
        <p class="muted small">Hoeveel geheugen Minecraft mag gebruiken. Laat wat over voor de rest van de VPS (MariaDB, nginx, het paneel). Aanbevolen voor deze VPS: <b>${num(data.defaultMemoryMB)} MB</b>.</p>
        <div class="memory-row">
          <input type="range" name="range" min="1024" max="${data.maxMemoryMB}" step="${step}" value="${data.memoryMB}" id="memory-range" aria-label="Geheugen">
          <label class="pct-input"><input class="input" type="number" name="memoryMB" min="1024" max="${data.maxMemoryMB}" step="${step}" value="${data.memoryMB}" id="memory-input"><span>MB</span></label>
        </div>
        <div class="btn-row"><button class="btn primary">${icon('save')} Opslaan</button></div>
        <p class="muted small">Geldt na een herstart. De server start met de vlaggen van Aikar (de standaard voor Paper en Purpur).</p></form>`)}
      ${card('Over de server', 'cube', html`<div class="card-body"><dl class="kv">
          <dt>Software</dt><dd>Purpur ${data.version} (build ${data.build})</dd>
          <dt>Geïnstalleerd</dt><dd>${dateTime(data.installedAt)}</dd>
          <dt>Java</dt><dd>${data.java ? `Java ${data.java}` : '—'}</dd>
          <dt>Draait als</dt><dd>${data.mode === 'systemd' ? html`dienst <span class="mono">pinda-minecraft</span>` : 'proces van het paneel (geen systemd)'}</dd>
          <dt>Instellingen</dt><dd><a href="${window.PH.editHash('bestanden', 'server.properties')}">server.properties</a> · <a href="${window.PH.editHash('bestanden', 'purpur.yml')}">purpur.yml</a></dd>
        </dl></div>`)}
    </div>
    <div style="margin-top:16px">${card('Versie', 'download', html`<div class="card-body" id="version-body"><div class="page-loading"><div class="spinner"></div></div></div>`)}</div>`);
    const range = $('#memory-range');
    const input = $('#memory-input');
    range.addEventListener('input', () => { input.value = range.value; });
    input.addEventListener('input', () => { range.value = input.value; });
    const form = await versionForm(data.version);
    if (!alive() || !$('#version-body')) return;
    render($('#version-body'), html`<p class="muted small">Overstappen naar een andere versie (of een nieuwere build) vervangt alleen server.jar. Stop de server eerst.</p>${form}`);
    await bindVersionForm(data.version);
  }

  forms['server-memory'] = async (form, data, button) => {
    const memoryMB = Number(data.get('memoryMB'));
    const result = await busy(button, () => post('/server/settings', { memoryMB }));
    if (!result) return;
    srv.data.memoryMB = memoryMB;
    toast(result.restartNeeded ? 'Opgeslagen. Geldt na een herstart van de server.' : 'Opgeslagen.');
  };
})();
