/* PindaHost - het dev-paneel. Geen externe bibliotheken; alles wat de server stuurt wordt ge-escaped. */
'use strict';

(() => {
  // =============================================================== helpers

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

  const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  const esc = value => String(value).replace(/[&<>"']/g, char => ESCAPES[char]);

  class Raw {
    constructor(s) { this.s = s; }
    toString() { return this.s; }
  }
  const raw = s => new Raw(s);
  const part = value => value instanceof Raw ? value.s
    : Array.isArray(value) ? value.map(part).join('')
      : value === null || value === undefined || value === false ? '' : esc(value);
  /** Template-tag: alles wat je invult wordt ge-escaped, behalve andere html`` of raw(). */
  const html = (strings, ...values) => raw(strings.reduce((out, str, i) => out + str + (i < values.length ? part(values[i]) : ''), ''));
  const render = (element, view) => { element.innerHTML = part(view); };

  const ICONS = {
    dashboard: '<rect x="3" y="3" width="7" height="9" rx="1.5"/><rect x="14" y="3" width="7" height="5" rx="1.5"/><rect x="14" y="12" width="7" height="9" rx="1.5"/><rect x="3" y="16" width="7" height="5" rx="1.5"/>',
    database: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v6c0 1.7 3.6 3 8 3s8-1.3 8-3V5"/><path d="M4 11v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6"/>',
    users: '<circle cx="9" cy="8" r="4"/><path d="M2 21c0-3.9 3.1-7 7-7s7 3.1 7 7"/><path d="M16 4.1a4 4 0 0 1 0 7.8M22 21c0-3.2-2-6-5-6.7"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4.4 3.6-8 8-8s8 3.6 8 8"/>',
    userPlus: '<circle cx="9" cy="8" r="4"/><path d="M2 21c0-3.9 3.1-7 7-7 1.4 0 2.7.4 3.8 1.1M19 14v6M16 17h6"/>',
    server: '<rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="13" width="18" height="7" rx="2"/><path d="M7 7.5h.01M7 16.5h.01M11 7.5h6M11 16.5h6"/>',
    log: '<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><path d="M14 3v6h6M8 13h8M8 17h5"/>',
    logout: '<path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 17l5-5-5-5M15 12H4"/>',
    menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
    refresh: '<path d="M21 12a9 9 0 1 1-2.6-6.4L21 8"/><path d="M21 3v5h-5"/>',
    shield: '<path d="M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6z"/><path d="M9 12l2 2 4-4"/>',
    lock: '<rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
    key: '<circle cx="8" cy="15" r="4"/><path d="M10.8 12.2L21 2M17 6l3 3M14 9l2 2"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/>',
    gauge: '<path d="M12 14l4-4"/><path d="M3.3 17a9 9 0 1 1 17.4 0"/>',
    memory: '<rect x="3" y="6" width="18" height="12" rx="2"/><path d="M7 10v4M11 10v4M15 10v4"/>',
    disk: '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="12" cy="12" r="3.5"/><path d="M17 7h.01"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    cube: '<path d="M21 8l-9-5-9 5v8l9 5 9-5z"/><path d="M3 8l9 5 9-5M12 13v8"/>',
    flame: '<path d="M12 3c1 3.5 5 5.5 5 10a5 5 0 0 1-10 0c0-2.4 1.3-3.8 2.5-5 .3 1.6 1 2.5 2 3 0-3-.5-5.5.5-8z"/>',
    web: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 9h18M7 6.5h.01M10 6.5h.01"/>',
    panel: '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M9 3v18M13 8h4M13 12h4"/>',
    download: '<path d="M12 3v12M7 10l5 5 5-5M5 21h14"/>',
    upload: '<path d="M12 21V9M7 14l5-5 5 5M5 3h14"/>',
    trash: '<path d="M4 7h16M10 11v6M14 11v6M5 7l1 13a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2l1-13M9 7V4h6v3"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    check: '<path d="M20 6L9 17l-5-5"/>',
    x: '<path d="M18 6L6 18M6 6l12 12"/>',
    copy: '<rect x="8" y="8" width="13" height="13" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/>',
    warn: '<path d="M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4M12 17h.01"/>',
    arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
    swap: '<path d="M7 4L3 8l4 4M3 8h14M17 20l4-4-4-4M21 16H7"/>'
  };
  const icon = (name, cls = '') => raw(`<svg class="i ${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name] || ''}</svg>`);

  // =============================================================== opmaak

  const numberFormat = new Intl.NumberFormat('nl-NL');
  const num = n => numberFormat.format(n || 0);
  const fixed = (n, digits = 1) => (n || 0).toLocaleString('nl-NL', { minimumFractionDigits: digits, maximumFractionDigits: digits });
  const dateTimeFormat = new Intl.DateTimeFormat('nl-NL', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  const dateTime = ms => ms ? dateTimeFormat.format(new Date(ms)) : '—';

  function ago(ms) {
    if (!ms) return '—';
    const seconds = Math.round((Date.now() - ms) / 1000);
    if (seconds < 45) return 'zojuist';
    const minutes = Math.round(seconds / 60);
    if (minutes < 60) return `${minutes} min geleden`;
    const hours = Math.round(minutes / 60);
    if (hours < 24) return `${hours} uur geleden`;
    const days = Math.round(hours / 24);
    return days === 1 ? 'gisteren' : `${days} dagen geleden`;
  }

  function uptime(ms) {
    let seconds = Math.floor(ms / 1000);
    const days = Math.floor(seconds / 86400); seconds %= 86400;
    const hours = Math.floor(seconds / 3600); seconds %= 3600;
    const minutes = Math.floor(seconds / 60);
    return [days ? `${days}d` : '', days || hours ? `${hours}u` : '', `${minutes}m`].filter(Boolean).join(' ');
  }

  function bytes(n) {
    if (n >= 1024 ** 3) return `${fixed(n / 1024 ** 3, 1)} GB`;
    if (n >= 1024 ** 2) return `${fixed(n / 1024 ** 2, 1)} MB`;
    if (n >= 1024) return `${num(Math.round(n / 1024))} kB`;
    return `${num(n)} bytes`;
  }

  const pct = (used, total) => total ? Math.round(used * 100 / total) : 0;

  // =============================================================== verbinding met het paneel

  const state = { info: null, user: null, timers: [], renderId: 0 };

  class ApiError extends Error {
    constructor(message, status) { super(message); this.status = status; }
  }

  async function request(path, options = {}) {
    const init = { credentials: 'same-origin', headers: {}, ...options };
    if (init.method && init.method !== 'GET') init.headers['X-Pinda-Host'] = '1';
    let response;
    try {
      response = await fetch('/api' + path, init);
    } catch {
      throw new ApiError('Geen verbinding met het dev-paneel.', 0);
    }
    let data = null;
    try { data = await response.json(); } catch { /* geen JSON */ }
    if (!response.ok) {
      const error = new ApiError(data && data.error ? data.error : `Er ging iets mis (${response.status}).`, response.status);
      if (response.status === 401 && state.user) {
        state.user = null;
        clearTimers();
        closeModal();
        renderLogin(error.message);
      }
      throw error;
    }
    return data;
  }
  const api = path => request(path);
  const post = (path, body = {}) => request(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const upload = (path, file) => request(path, { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: file });

  function every(ms, task) {
    const id = setInterval(() => { if (!document.hidden) task(); }, ms);
    state.timers.push(id);
  }
  function clearTimers() {
    state.timers.forEach(clearInterval);
    state.timers = [];
  }

  // =============================================================== meldingen en dialogen

  function toast(message, type = 'success') {
    const element = document.createElement('div');
    element.className = `toast ${type}`;
    element.textContent = message;
    $('#toasts').append(element);
    setTimeout(() => {
      element.classList.add('out');
      setTimeout(() => element.remove(), 260);
    }, type === 'error' ? 6000 : 3500);
  }

  const modal = $('#modal');
  function openModal(view, cls = '') {
    modal.returnValue = '';
    modal.className = cls;
    render(modal, view);
    if (!modal.open) modal.showModal();
    const focus = $('[autofocus]', modal) || $('input:not([type=hidden]), select, textarea', modal);
    if (focus) focus.focus();
  }
  function closeModal() {
    if (modal.open) modal.close();
  }
  modal.addEventListener('click', event => { if (event.target === modal) closeModal(); });

  /** Vraagt om bevestiging. Met checkbox komt er een vinkje bij; dat staat daarna in state.confirmChecked. */
  function confirmDialog({ title, text, confirm = 'Bevestigen', danger = false, typeToConfirm = '', checkbox = null }) {
    openModal(html`<form method="dialog" class="confirm-form">
      <div class="modal-head"><h3>${title}</h3></div>
      <div class="modal-body"><p class="muted">${text}</p>
        ${checkbox ? html`<label class="check"><input type="checkbox" name="extra" ${checkbox.checked ? 'checked' : ''}> ${checkbox.label}</label>` : ''}
        ${typeToConfirm ? html`<label class="field">Typ <b class="mono">${typeToConfirm}</b> om te bevestigen<input class="input mono" name="typed" autocomplete="off" autofocus></label>` : ''}</div>
      <div class="modal-foot">
        <button type="button" class="btn ghost" data-action="close">Annuleren</button>
        <button class="btn ${danger ? 'solid-danger' : 'primary'}" value="ok" ${typeToConfirm ? 'disabled' : 'autofocus'}>${confirm}</button>
      </div></form>`);
    if (typeToConfirm) {
      const input = $('input[name=typed]', modal);
      const button = $('button[value=ok]', modal);
      input.addEventListener('input', () => { button.disabled = input.value !== typeToConfirm; });
    }
    const extra = $('input[name=extra]', modal);
    state.confirmChecked = false;
    if (extra) extra.addEventListener('change', () => { state.confirmChecked = extra.checked; });
    if (extra) state.confirmChecked = extra.checked;
    return new Promise(resolve => modal.addEventListener('close', () => resolve(modal.returnValue === 'ok'), { once: true }));
  }

  async function busy(button, task) {
    if (button) { button.disabled = true; button.classList.add('busy'); }
    try {
      return await task();
    } catch (error) {
      if (error.status !== 401) toast(error.message, 'error');
      return undefined;
    } finally {
      if (button) { button.disabled = false; button.classList.remove('busy'); }
    }
  }

  /** Een wachtwoord dat maar één keer te zien is. */
  function showSecret({ title, intro, rows }) {
    openModal(html`<div class="modal-head"><h3>${title}</h3>${intro ? html`<p>${intro}</p>` : ''}</div>
      <div class="modal-body"><dl class="kv secret-list">${rows.map(([key, value, secret]) => html`<dt>${key}</dt>
        <dd><span class="mono ${secret ? 'secret-value' : ''}">${value}</span>
          <button class="btn sm ghost icon-only" data-action="copy" data-text="${value}" title="Kopiëren" aria-label="${key} kopiëren">${icon('copy')}</button></dd>`)}</dl>
        <div class="alert warning">${icon('warn')} Dit wachtwoord zie je maar één keer. Bewaar het nu (bijvoorbeeld in een wachtwoordmanager).</div></div>
      <div class="modal-foot"><button class="btn primary" data-action="close" autofocus>Ik heb het bewaard</button></div>`);
  }

  // =============================================================== starten

  async function boot() {
    try {
      state.info = await api('/state');
    } catch (error) {
      render($('#app'), html`<div class="login"><div class="login-card"><h1>Dev-paneel</h1><div class="alert error">${error.message}</div></div></div>`);
      return;
    }
    state.user = state.info.user;
    if (state.info.needsSetup) return renderSetupAccount();
    if (!state.user) return renderLogin();
    afterLogin();
  }

  function afterLogin() {
    if (!state.info.setupDone) return renderWizard();
    renderShell();
    navigate();
  }

  async function refreshInfo() {
    state.info = await api('/state');
    state.user = state.info.user;
  }

  // =============================================================== inloggen

  function loginCard(content) {
    render($('#app'), html`<div class="login"><div class="login-card">
      <img class="login-logo" src="/favicon.svg" alt="">
      ${content}
      <p class="login-foot">PindaHost ${state.info ? state.info.version : ''}</p>
    </div></div>`);
    const focus = $('#app [autofocus]') || $('#app input');
    if (focus) focus.focus();
  }

  function renderLogin(message = '', kind = 'error') {
    const name = state.info && state.info.serverName;
    loginCard(html`<h1>${name || 'PindaHost'} <span>dev-paneel</span></h1>
      <p class="lead">Voor developers en beheerders van de server. Staff gebruikt het webpaneel in de game (/panel).</p>
      <form data-form="login" class="stack">
        <label class="field">Gebruikersnaam<input class="input" name="username" autocomplete="username" required autofocus></label>
        <label class="field">Wachtwoord<input class="input" type="password" name="password" autocomplete="current-password" required></label>
        <button class="btn primary">${icon('lock')} Inloggen</button>
      </form>
      ${message ? html`<div class="alert ${kind}">${message}</div>` : ''}`);
  }

  /** De 2FA-stap: de eerste keer met QR-code, daarna alleen de code. */
  function renderCode(challenge, message = '', setup = false) {
    state.challenge = challenge;
    const first = challenge.step === 'setup';
    loginCard(html`<h1>${first ? 'Beveilig je account' : 'Tweestapsverificatie'}</h1>
      ${first ? html`<p class="lead">Koppel een authenticator-app, zoals Google Authenticator, Microsoft Authenticator, Authy of Bitwarden.</p>
        <div class="qr-wrap"><img class="qr" src="${challenge.qr}" alt="QR-code voor je authenticator-app" width="208" height="208"></div>
        <ol class="login-steps">
          <li><b>1</b><span>Open je app en kies <em>account toevoegen</em> of <em>QR-code scannen</em>.</span></li>
          <li><b>2</b><span>Scan de code. Je ziet daarna <span class="kbd">${challenge.issuer}</span> met 6 cijfers.</span></li>
          <li><b>3</b><span>Vul die 6 cijfers hieronder in.</span></li>
        </ol>
        <details class="manual"><summary>Scannen lukt niet?</summary><p class="muted">Vul deze sleutel handmatig in (op tijd gebaseerd):</p><p class="secret mono">${challenge.secret}</p></details>`
        : html`<p class="lead">Vul de code van 6 cijfers uit je authenticator-app in.</p>`}
      <form data-form="${setup ? 'setup-verify' : 'login-verify'}" class="code-form">
        <input class="input code-input" name="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9 ]*" maxlength="7" required autofocus aria-label="Code van 6 cijfers">
        <button class="btn primary">${first ? 'Koppelen' : 'Inloggen'}</button>
      </form>
      ${message ? html`<div class="alert error">${message}</div>` : ''}
      <p class="login-foot"><a href="#" data-action="restart-login">Opnieuw beginnen</a></p>`);
    const input = $('.code-input');
    input.addEventListener('input', () => {
      if (input.value.replace(/\D/g, '').length === 6 && !state.verifying) input.form.requestSubmit();
    });
  }

  function renderNewPassword(message = '') {
    loginCard(html`<h1>Kies een eigen wachtwoord</h1>
      <p class="lead">Je bent ingelogd met een tijdelijk wachtwoord. Kies nu je eigen wachtwoord (minimaal 10 tekens).</p>
      <form data-form="login-password" class="stack">
        <label class="field">Nieuw wachtwoord<input class="input" type="password" name="password" autocomplete="new-password" minlength="10" required autofocus></label>
        <label class="field">Nog een keer<input class="input" type="password" name="again" autocomplete="new-password" minlength="10" required></label>
        <button class="btn primary">${icon('check')} Opslaan en inloggen</button>
      </form>
      ${message ? html`<div class="alert error">${message}</div>` : ''}`);
  }

  // =============================================================== de allereerste beheerder

  function renderSetupAccount(message = '') {
    loginCard(html`<h1>Welkom bij <span>PindaHost</span></h1>
      <p class="lead">Maak de eerste beheerder van dit dev-paneel. De setupcode staat aan het eind van de installer, of typ op de server: <span class="kbd">sudo pinda-host setup-code</span></p>
      <form data-form="setup-account" class="stack">
        <label class="field">Setupcode<input class="input mono" name="code" autocomplete="off" placeholder="XXXX-XXXX-XXXX" required autofocus></label>
        <label class="field">Gebruikersnaam<input class="input" name="username" autocomplete="username" minlength="3" maxlength="32" required></label>
        <label class="field">Wachtwoord <span class="hint">minimaal 10 tekens</span><input class="input" type="password" name="password" autocomplete="new-password" minlength="10" required></label>
        <label class="field">Nog een keer<input class="input" type="password" name="again" autocomplete="new-password" minlength="10" required></label>
        <button class="btn primary">${icon('arrow')} Verder</button>
      </form>
      ${message ? html`<div class="alert error">${message}</div>` : ''}`);
  }

  // =============================================================== setup: server, database, DNS

  const WIZARD_STEPS = ['Server', 'Database', 'DNS', 'Klaar'];

  async function renderWizard(step = 0) {
    state.wizardStep = step;
    let info;
    try {
      info = await api('/setup');
    } catch (error) {
      render($('#app'), html`<div class="wizard"><div class="alert error">${error.message}</div></div>`);
      return;
    }
    state.setup = info;
    const steps = html`<ol class="wizard-steps">${WIZARD_STEPS.map((name, i) => html`<li class="${i === step ? 'current' : i < step ? 'done' : ''}"><b>${i < step ? icon('check') : i + 1}</b><span>${name}</span></li>`)}</ol>`;
    const isAdmin = state.user && state.user.admin;
    let body;
    if (!isAdmin) {
      body = html`<div class="alert info">Een beheerder maakt de setup af. Daarna kun je hier verder.</div>
        <div class="btn-row"><button class="btn" data-action="logout">${icon('logout')} Uitloggen</button></div>`;
    } else if (step === 0) {
      const domain = info.siteDomain || (info.mode === 'domain' && info.domain ? info.domain.split('.').slice(-2).join('.') : '');
      body = html`<form data-form="wizard-server" class="stack">
        <h2>Je server</h2>
        <p class="muted">Deze gegevens komen in het paneel, in de uitleg voor de DNS en later in de website.</p>
        <label class="field">Naam van de server<input class="input" name="serverName" maxlength="32" value="${info.serverName || ''}" placeholder="PindaCraft" required autofocus></label>
        <label class="field">Website <span class="hint">het domein van de website, bijv. pindacraft.nl (mag leeg)</span>
          <input class="input" name="siteDomain" value="${domain}" placeholder="pindacraft.nl"></label>
        <div class="row">
          <label class="field grow">Adres voor spelers <span class="hint">wat spelers in Minecraft typen</span>
            <input class="input" name="gameAddress" value="${info.gameAddress || (domain ? 'play.' + domain : '')}" placeholder="play.pindacraft.nl" required></label>
          <label class="field port">Poort<input class="input" type="number" name="gamePort" min="1024" max="65535" value="${info.gamePort || 25565}" required></label>
        </div>
        <div class="wizard-foot"><span></span><button class="btn primary">Verder ${icon('arrow')}</button></div>
      </form>`;
    } else if (step === 1) {
      const chosen = info.database;
      const db = info.mariadb;
      body = html`<div class="stack">
        <h2>Database</h2>
        <p class="muted">Waar PindaFramework zijn gegevens bewaart: spelers, geld, homes, shops, skills, … Later omzetten van SQLite naar MySQL kan altijd, vanuit dit paneel.</p>
        <div class="choice-grid">
          <button class="choice ${chosen === 'sqlite' ? 'chosen' : ''}" data-action="wizard-db" data-type="sqlite">
            <span class="choice-icon">${icon('disk')}</span>
            <b>SQLite</b><span class="muted">Eén bestand in de pluginmap. Niets te beheren; prima voor de meeste servers.</span></button>
          <button class="choice ${chosen === 'mysql' ? 'chosen' : ''}" data-action="wizard-db" data-type="mysql" ${db.installed ? '' : 'disabled'}>
            <span class="choice-icon">${icon('database')}</span>
            <b>MySQL (MariaDB)</b><span class="muted">Een echte databaseserver op deze VPS. Handig voor grote servers, backups per database en andere plugins.</span>
            ${db.installed ? (db.running ? html`<span class="badge success">MariaDB ${db.version} draait</span>` : html`<span class="badge warning">MariaDB staat uit</span>`) : html`<span class="badge plain">Niet geïnstalleerd</span>`}</button>
        </div>
        ${chosen === 'mysql' ? html`<div class="alert success">${icon('check')} Database <b class="mono">pindacraft</b> en gebruiker <b class="mono">pindacraft</b> zijn aangemaakt, met een sterk wachtwoord. De plugin gebruikt ze vanaf de eerste start.</div>` : ''}
        ${chosen === 'sqlite' ? html`<div class="alert success">${icon('check')} De plugin gebruikt SQLite.</div>` : ''}
        <div class="wizard-foot"><button class="btn ghost" data-action="wizard-step" data-step="0">Terug</button>
          <button class="btn primary" data-action="wizard-step" data-step="2" ${chosen ? '' : 'disabled'}>Verder ${icon('arrow')}</button></div>
      </div>`;
    } else if (step === 2) {
      body = html`<div class="stack">
        <h2>DNS instellen</h2>
        <p class="muted">Maak deze records aan bij je domein${info.cloudflare ? ' in Cloudflare' : ''}. Het kan een paar minuten duren voordat ze werken.</p>
        <div class="table-wrap card flat"><table>
          <thead><tr><th>Type</th><th>Naam</th><th>Waarde</th>${info.cloudflare ? html`<th>Proxy</th>` : ''}</tr></thead>
          <tbody>${info.dns.map(r => html`<tr><td><span class="badge plain">${r.type}</span></td><td class="mono">${r.name}</td><td class="mono">${r.content}</td>
            ${info.cloudflare ? html`<td class="nowrap">${r.proxy === 'aan' ? html`<span class="badge warning">Aan</span>` : r.proxy === 'uit' ? html`<span class="badge plain">Uit</span>` : '—'}</td>` : ''}</tr>
            ${r.note ? html`<tr class="note-row"><td></td><td colspan="${info.cloudflare ? 3 : 2}" class="muted small">${r.note}</td></tr>` : ''}`)}</tbody></table></div>
        <div class="alert info">${icon('globe')} <div><b>SRV-record</b>: hiermee vinden spelers je server op <b class="mono">${info.gameAddress}</b>, ook als de poort niet 25565 is.
          ${info.cloudflare ? html` In Cloudflare: <em>Add record › SRV</em>, naam <span class="mono">_minecraft._tcp.${info.gameAddress.split('.')[0]}</span>, prioriteit 0, gewicht 5, poort ${info.gamePort}, doel ${info.gameAddress}.` : ''}</div></div>
        ${info.cloudflare ? html`<div class="alert warning">${icon('warn')} <div><b>Cloudflare</b>: zet bij <em>SSL/TLS</em> de modus op <b>Full</b>. Het adres voor spelers moet op <b>DNS only</b> (grijze wolk): Minecraft-verkeer kan niet door de proxy.</div></div>` : ''}
        ${info.mode === 'ip' && info.fingerprint ? html`<div class="alert plain">${icon('lock')} <div>Dit paneel gebruikt een eigen certificaat. Controleer in je browser de vingerafdruk:<br><span class="mono small wrap">${info.fingerprint}</span></div></div>` : ''}
        <div class="wizard-foot"><button class="btn ghost" data-action="wizard-step" data-step="1">Terug</button>
          <button class="btn primary" data-action="wizard-step" data-step="3">Verder ${icon('arrow')}</button></div>
      </div>`;
    } else {
      body = html`<div class="stack">
        <h2>Klaar om te beginnen</h2>
        <dl class="kv"><dt>Server</dt><dd>${info.serverName}</dd><dt>Adres voor spelers</dt><dd class="mono">${info.gameAddress}${info.gamePort !== 25565 ? `:${info.gamePort}` : ''}</dd>
          <dt>Website</dt><dd class="mono">${info.siteDomain || '—'}</dd><dt>Database</dt><dd>${info.database === 'mysql' ? 'MySQL (MariaDB)' : 'SQLite'}</dd></dl>
        <p class="muted">Op het dashboard zie je wat er draait. De Minecraft-server installeren, bestanden, backups en de website komen in een volgende versie van het dev-paneel.</p>
        <div class="wizard-foot"><button class="btn ghost" data-action="wizard-step" data-step="2">Terug</button>
          <button class="btn primary" data-action="wizard-finish">${icon('check')} Naar het dashboard</button></div>
      </div>`;
    }
    render($('#app'), html`<div class="wizard">
      <div class="wizard-brand"><img src="/favicon.svg" alt=""><div><b>PindaHost</b><span>Setup</span></div></div>
      ${steps}<section class="card wizard-card"><div class="card-body">${body}</div></section></div>`);
    const focus = $('#app [autofocus]');
    if (focus) focus.focus();
  }

  // =============================================================== opbouw en navigatie

  const ROUTES = [
    { path: 'dashboard', title: 'Dashboard', icon: 'dashboard', page: pageDashboard },
    { path: 'databases', title: 'Databases', icon: 'database', page: pageDatabases },
    { path: 'gebruikers', title: 'Gebruikers', icon: 'users', page: pageUsers, admin: true, group: 'Beheer' },
    { path: 'logboek', title: 'Logboek', icon: 'log', page: pageAudit, admin: true, group: 'Beheer' },
    { path: 'account', title: 'Mijn account', icon: 'user', page: pageAccount, group: 'Beheer' }
  ];
  const visibleRoutes = () => ROUTES.filter(route => !route.admin || (state.user && state.user.admin));

  function renderShell() {
    let lastGroup = null;
    const links = visibleRoutes().map(route => {
      const label = route.group && route.group !== lastGroup ? html`<div class="nav-label">${route.group}</div>` : '';
      lastGroup = route.group || lastGroup;
      return html`${label}<a href="#/${route.path}" data-route="${route.path}">${icon(route.icon)}<span>${route.title}</span></a>`;
    });
    render($('#app'), html`<div class="layout">
      <aside class="sidebar">
        <div class="brand"><img src="/favicon.svg" alt=""><div><div class="brand-name">${state.info.serverName || 'PindaHost'}</div><div class="brand-sub">Dev-paneel</div></div></div>
        <nav class="nav">${links}</nav>
        <div class="sidebar-foot">
          <div class="me"><span class="me-icon">${icon('user')}</span><div class="me-text"><div class="me-name">${state.user.name}</div>
            <span class="badge ${state.user.admin ? 'warning' : 'plain'}">${state.user.admin ? 'Beheerder' : 'Developer'}</span></div>
            <button class="btn ghost icon-only" data-action="logout" title="Uitloggen" aria-label="Uitloggen">${icon('logout')}</button></div>
        </div>
      </aside>
      <div class="scrim" data-action="close-nav"></div>
      <div class="content">
        <header class="topbar">
          <button class="btn ghost icon-only" data-action="open-nav" aria-label="Menu">${icon('menu')}</button>
          <img src="/favicon.svg" alt=""><span class="topbar-title" id="topbar-title"></span>
        </header>
        <main class="main" id="main" tabindex="-1"></main>
      </div>
    </div>`);
    document.title = `${state.info.serverName || 'PindaHost'} · Dev-paneel`;
  }

  async function navigate() {
    if (!state.user || !$('#main')) return;
    clearTimers();
    closeModal();
    document.body.classList.remove('nav-open');
    const path = location.hash.replace(/^#\/?/, '').split('/')[0];
    const routes = visibleRoutes();
    const route = routes.find(r => r.path === path) || routes[0];
    if (route.path !== path) {
      location.replace(`#/${route.path}`);
      return;
    }
    $$('.nav a').forEach(link => link.classList.toggle('active', link.dataset.route === route.path));
    $('#topbar-title').textContent = route.title;
    const main = $('#main');
    main.onclick = null;
    main.onchange = null;
    const id = ++state.renderId;
    const alive = () => id === state.renderId && !!state.user;
    render(main, html`<div class="page-loading"><div class="spinner"></div></div>`);
    window.scrollTo(0, 0);
    try {
      await route.page(main, alive);
    } catch (error) {
      if (!alive() || error.status === 401) return;
      render(main, html`<div class="alert error">${error.message}</div>`);
    }
  }
  window.addEventListener('hashchange', navigate);

  function pageHead(title, subtitle, actions = '') {
    return html`<div class="page-head"><div><h1>${title}</h1>${subtitle ? html`<p>${subtitle}</p>` : ''}</div>
      ${actions ? html`<div class="page-actions">${actions}</div>` : ''}</div>`;
  }
  const card = (title, iconName, body, extra = '') => html`<section class="card">
    <div class="card-head"><h2>${iconName ? icon(iconName) : ''}${title}</h2>${extra}</div>${body}</section>`;
  const emptyRow = (columns, text) => html`<tr><td colspan="${columns}"><div class="empty">${text}</div></td></tr>`;

  // =============================================================== dashboard

  const SERVICE_ICONS = { game: 'cube', database: 'database', web: 'web', firewall: 'flame', panel: 'panel' };
  const STATES = { active: ['Draait', 'success'], inactive: ['Staat uit', 'warning'], failed: ['Gecrasht', 'error'], activating: ['Start op', 'info'], deactivating: ['Stopt', 'info'], unknown: ['Onbekend', 'plain'] };

  async function pageDashboard(main, alive) {
    const load = async () => {
      const data = await api('/dashboard');
      if (!alive()) return;
      renderDashboard(main, data);
    };
    await load();
    every(15000, () => load().catch(() => {}));
  }

  function renderDashboard(main, d) {
    const s = d.system;
    const setup = d.setup;
    const services = d.services.map(service => {
      const [label, cls] = !service.installed && service.id !== 'panel' ? ['Niet geïnstalleerd', 'plain'] : (STATES[service.state] || [service.state, 'plain']);
      return html`<div class="card service ${service.active ? 'on' : ''}">
        <div class="service-top"><span class="service-icon">${icon(SERVICE_ICONS[service.id] || 'server')}</span><span class="badge ${cls}"><span class="dot ${service.active ? 'on' : ''}"></span>${label}</span></div>
        <div class="service-name">${service.name}</div>
        <div class="service-detail muted">${service.detail || service.unit}</div></div>`;
    });
    const meter = (title, iconName, used, total, sub) => {
      const p = Math.min(100, pct(used, total));
      return html`<div class="card stat"><div class="stat-label">${title}</div>
        <div class="stat-icon">${icon(iconName)}</div><div class="stat-value">${p}%</div>
        <div class="meter ${p > 90 ? 'error' : p > 75 ? 'warning' : ''}"><i style="width:${p}%"></i></div>
        <div class="stat-sub">${sub}</div></div>`;
    };
    const load = s.load && s.load.length ? s.load : [0, 0, 0];
    const plugin = d.plugin;
    render(main, html`${pageHead(setup.serverName || 'Dashboard', 'Wat er draait op deze VPS. Het dev-paneel blijft bereikbaar, ook als de Minecraft-server crasht.',
        html`<button class="btn" data-action="reload">${icon('refresh')} Verversen</button>`)}
      <div class="grid services">${services}</div>
      <div class="grid stats" style="margin-top:16px">
        <div class="card stat"><div class="stat-label">Processor</div><div class="stat-icon">${icon('gauge')}</div>
          <div class="stat-value">${fixed(load[0], 2)}</div><div class="stat-sub">belasting (1 min) · ${num(s.cpus)} ${s.cpus === 1 ? 'kern' : 'kernen'} · 15m ${fixed(load[2], 2)}</div></div>
        ${meter('Geheugen', 'memory', s.memUsed, s.memTotal, `${bytes(s.memUsed)} van ${bytes(s.memTotal)}`)}
        ${meter('Schijf', 'disk', s.diskUsed, s.diskTotal, `${bytes(s.diskUsed)} van ${bytes(s.diskTotal)}`)}
        <div class="card stat"><div class="stat-label">Aan sinds</div><div class="stat-icon">${icon('clock')}</div>
          <div class="stat-value">${uptime(s.uptime)}</div><div class="stat-sub">${s.os || ''}</div></div>
      </div>
      <div class="grid two" style="margin-top:16px">
        ${card('Server', 'globe', html`<div class="card-body"><dl class="kv">
          <dt>Adres voor spelers</dt><dd class="mono">${setup.gameAddress || '—'}${setup.gamePort && setup.gamePort !== 25565 ? `:${setup.gamePort}` : ''}</dd>
          <dt>Website</dt><dd class="mono">${setup.siteDomain || '—'}</dd>
          <dt>Dev-paneel</dt><dd class="mono">${setup.mode === 'ip' ? location.host : setup.domain}</dd>
          <dt>IP-adres</dt><dd class="mono">${(s.addresses || []).join(', ') || '—'}</dd>
          <dt>Hostnaam</dt><dd class="mono">${s.hostname}</dd></dl></div>`)}
        ${card('Database van PindaFramework', 'database', html`<div class="card-body">${pluginSummary(plugin)}
          <div class="btn-row" style="margin-top:14px"><a class="btn" href="#/databases">${icon('database')} Naar databases</a></div></div>`)}
      </div>`);
  }

  function pluginStatusBadge(plugin) {
    const status = plugin.status;
    if (plugin.convert) return html`<span class="badge info">Wordt omgezet bij de volgende start</span>`;
    if (!status) return html`<span class="badge plain">Server nog niet gestart</span>`;
    if (!status.ok) return html`<span class="badge error">Geen verbinding</span>`;
    if (status.warning) return html`<span class="badge warning">Let op</span>`;
    return html`<span class="badge success">Verbonden</span>`;
  }

  function pluginSummary(plugin) {
    const status = plugin.status;
    return html`<dl class="kv">
      <dt>Soort</dt><dd>${plugin.type === 'mysql' ? 'MySQL (MariaDB)' : 'SQLite'}</dd>
      <dt>Waar</dt><dd class="mono">${plugin.type === 'mysql' ? `${plugin.user}@${plugin.host}:${plugin.port}/${plugin.database}` : plugin.sqliteFile}</dd>
      <dt>Status</dt><dd>${pluginStatusBadge(plugin)}</dd>
      ${status && status.updated ? html`<dt>Laatste start</dt><dd>${ago(status.updated)}${status.plugin ? ` · v${status.plugin}` : ''}</dd>` : ''}
    </dl>
    ${status && !status.ok && status.error ? html`<div class="alert error small">${status.error}</div>` : ''}
    ${status && status.warning ? html`<div class="alert warning small">${status.warning}</div>` : ''}`;
  }

  // =============================================================== databases

  async function pageDatabases(main, alive) {
    const data = await api('/database');
    if (!alive()) return;
    renderDatabases(main, data);
  }

  function renderDatabases(main, d) {
    state.db = d;
    const server = d.server;
    const plugin = d.plugin;
    const pluginDb = plugin.type === 'mysql' ? plugin.database : '';
    const pluginUser = plugin.type === 'mysql' ? plugin.user : '';
    const conversion = plugin.conversion;
    // Developers mogen kijken, aanmaken en downloaden; weggooien, inladen en rechten zijn voor beheerders.
    const admin = !!(state.user && state.user.admin);
    const serverBadge = !server.installed ? html`<span class="badge plain">Niet geïnstalleerd</span>`
      : server.running ? html`<span class="badge success"><span class="dot on"></span>MariaDB ${server.version}</span>`
        : html`<span class="badge error">Draait niet</span>`;

    const pluginCard = card('PindaFramework', 'cube', html`<div class="card-body">
      ${pluginSummary(plugin)}
      ${conversion ? html`<div class="alert ${conversion.status === 'done' ? 'success' : 'error'} small">${conversion.status === 'done'
        ? html`${icon('check')} <div>Omgezet naar MySQL ${ago(conversion.at)}: ${num(conversion.rows)} rijen uit ${num(conversion.tables)} tabellen. Het oude bestand staat nog als <span class="mono">${conversion.backup}</span>.</div>`
        : html`${icon('warn')} <div>Omzetten mislukt ${ago(conversion.at)}: ${conversion.error}. De server draait op SQLite verder; bij de volgende start wordt het opnieuw geprobeerd.</div>`}</div>` : ''}
      ${plugin.type !== 'mysql' ? html`<p class="muted small" style="margin-top:12px">Met MySQL kun je de gegevens los van de server back-uppen en ook vanuit andere plugins of tools bij de gegevens. Je spelers merken er niets van.</p>
        ${admin ? html`<div class="btn-row"><button class="btn primary" data-action="plugin-mysql" ${server.running ? '' : 'disabled'}>${icon('swap')} Omzetten naar MySQL</button></div>`
          : html`<p class="muted small">Omzetten kan alleen een beheerder.</p>`}`
        : plugin.convert ? html`<p class="muted small" style="margin-top:12px">Bij de volgende start van de Minecraft-server worden alle gegevens uit <span class="mono">${plugin.sqliteFile}</span> overgezet.</p>` : ''}
      </div>`);

    const dbRows = d.databases.length ? d.databases.map(db => html`<tr>
        <td><b class="mono">${db.name}</b>${db.name === pluginDb ? html` <span class="badge warning">PindaFramework</span>` : ''}</td>
        <td class="num">${num(db.tables)}</td><td class="num">${bytes(db.size)}</td>
        <td class="actions">
          <a class="btn sm" href="/api/database/databases/${encodeURIComponent(db.name)}/export" download title="Downloaden als .sql">${icon('download')} .sql</a>
          ${admin ? html`<button class="btn sm" data-action="db-import" data-name="${db.name}" title="Een .sql-bestand inladen">${icon('upload')} Inladen</button>` : ''}
          ${!admin || db.name === pluginDb ? '' : html`<button class="btn sm danger icon-only" data-action="db-drop" data-name="${db.name}" title="Verwijderen" aria-label="${db.name} verwijderen">${icon('trash')}</button>`}
        </td></tr>`) : emptyRow(4, server.running ? 'Nog geen databases.' : 'MariaDB draait niet.');

    const userRows = d.users.length ? d.users.map(user => html`<tr>
        <td><b class="mono">${user.name}</b><span class="muted mono">@${user.host}</span>${user.name === pluginUser ? html` <span class="badge warning">PindaFramework</span>` : ''}</td>
        <td><div class="chips">${user.databases.length ? user.databases.map(db => html`<span class="badge plain mono">${db}
          ${!admin || (user.name === pluginUser && db === pluginDb) ? '' : html`<button class="chip-x" data-action="db-revoke" data-user="${user.name}" data-host="${user.host}" data-db="${db}" title="Toegang intrekken" aria-label="Toegang tot ${db} intrekken">×</button>`}</span>`)
          : html`<span class="muted">geen</span>`}</div></td>
        <td class="actions">${admin ? html`
          <button class="btn sm" data-action="db-grant" data-user="${user.name}" data-host="${user.host}">${icon('plus')} Toegang</button>
          <button class="btn sm" data-action="db-password" data-user="${user.name}" data-host="${user.host}" data-plugin="${user.name === pluginUser && user.host === 'localhost'}" title="Nieuw wachtwoord">${icon('key')}</button>
          ${user.name === pluginUser ? '' : html`<button class="btn sm danger icon-only" data-action="db-user-drop" data-user="${user.name}" data-host="${user.host}" title="Verwijderen" aria-label="${user.name} verwijderen">${icon('trash')}</button>`}` : ''}
        </td></tr>`) : emptyRow(3, server.running ? 'Nog geen gebruikers.' : 'MariaDB draait niet.');

    render(main, html`${pageHead('Databases', 'MariaDB op deze VPS: databases en gebruikers voor PindaFramework en andere plugins.',
        html`${serverBadge}<button class="btn" data-action="reload">${icon('refresh')} Verversen</button>`)}
      ${!server.installed ? html`<div class="alert warning">${icon('warn')} MariaDB is niet geïnstalleerd. Draai de installer opnieuw om het te installeren.</div>`
        : !server.running ? html`<div class="alert error">${icon('warn')} MariaDB draait niet${server.error ? `: ${server.error}` : ''}.</div>` : ''}
      <div class="grid two">
        ${pluginCard}
        ${card('Nieuwe database', 'plus', html`<form class="card-body stack" data-form="db-create">
          <label class="field">Naam <span class="hint">letters, cijfers en _</span><input class="input mono" name="name" pattern="[A-Za-z0-9_]{1,64}" maxlength="64" placeholder="dynmap" required ${server.running ? '' : 'disabled'}></label>
          <label class="check"><input type="checkbox" name="createUser" checked> Ook een gebruiker met dezelfde naam en een sterk wachtwoord</label>
          <div class="btn-row"><button class="btn primary" ${server.running ? '' : 'disabled'}>${icon('plus')} Aanmaken</button></div>
          <p class="muted small">Handig voor plugins als LuckPerms, CoreProtect of Dynmap. Gebruik host <span class="mono">127.0.0.1</span> en poort <span class="mono">3306</span>.</p>
        </form>`)}
      </div>
      <div style="margin-top:16px">${card('Databases', 'database', html`<div class="table-wrap"><table>
        <thead><tr><th>Naam</th><th class="num">Tabellen</th><th class="num">Grootte</th><th></th></tr></thead>
        <tbody>${dbRows}</tbody></table></div>`, html`<span class="count">${num(d.databases.length)}</span>`)}</div>
      <div style="margin-top:16px">${card('Gebruikers', 'key', html`<div class="table-wrap"><table>
        <thead><tr><th>Gebruiker</th><th>Toegang tot</th><th></th></tr></thead>
        <tbody>${userRows}</tbody></table></div>
        ${admin ? html`<form class="card-body inline-form" data-form="db-user-create">
          <input class="input mono" name="name" pattern="[A-Za-z0-9_]{1,32}" maxlength="32" placeholder="gebruikersnaam" aria-label="Gebruikersnaam" required ${server.running ? '' : 'disabled'}>
          <select name="database" aria-label="Toegang tot database" ${server.running ? '' : 'disabled'}><option value="">Nog geen toegang</option>${d.databases.map(db => html`<option value="${db.name}">${db.name}</option>`)}</select>
          <button class="btn" ${server.running ? '' : 'disabled'}>${icon('userPlus')} Gebruiker maken</button>
        </form>` : html`<p class="card-body muted small">Gebruikers en rechten beheren kan alleen een beheerder.</p>`}`, html`<span class="count">${num(d.users.length)}</span>`)}</div>
      <p class="muted small" style="margin-top:14px">${icon('shield')} Gebruikers kunnen alleen vanaf deze VPS inloggen (localhost); MariaDB is niet van buitenaf bereikbaar. Databases gaan straks ook mee in de backups.</p>`);
  }

  function afterDatabaseChange(result, message) {
    if (!result) return;
    if (message) toast(message);
    if (location.hash.startsWith('#/databases')) renderDatabases($('#main'), result);
    if (result.credentials) {
      const c = result.credentials;
      showSecret({
        title: 'Gegevens van de gebruiker',
        intro: c.plugin ? 'database.yml van PindaFramework is bijgewerkt. Herstart de Minecraft-server, dan gebruikt de plugin het nieuwe wachtwoord.'
          : 'Vul deze in bij de plugin die de database gebruikt.',
        rows: [['Host', '127.0.0.1'], ['Poort', '3306'], ...(c.database ? [['Database', c.database]] : []), ['Gebruiker', c.user], ['Wachtwoord', c.password, true]]
      });
    }
  }

  async function watchJob(job, onDone) {
    openModal(html`<div class="modal-head"><h3>${job.title}</h3></div>
      <div class="modal-body"><pre class="job-log" id="job-log">Bezig…</pre></div>
      <div class="modal-foot"><button class="btn" data-action="close" id="job-close" disabled>Sluiten</button></div>`);
    for (;;) {
      await new Promise(resolve => setTimeout(resolve, 1000));
      let current;
      try {
        current = await api(`/jobs/${job.id}`);
      } catch (error) {
        toast(error.message, 'error');
        break;
      }
      const log = $('#job-log');
      if (!log) return;
      log.textContent = current.lines.join('\n') + (current.status === 'failed' ? `\n\nMislukt: ${current.error}` : '');
      if (current.status !== 'running') {
        $('#job-close').disabled = false;
        toast(current.status === 'done' ? `${current.title}: klaar.` : `${current.title}: mislukt.`, current.status === 'done' ? 'success' : 'error');
        if (onDone) onDone(current);
        break;
      }
    }
  }

  // =============================================================== gebruikers en logboek

  async function pageUsers(main, alive) {
    const data = await api('/users');
    if (!alive()) return;
    renderUsers(main, data);
  }

  function renderUsers(main, d) {
    const rows = d.users.map(user => {
      const self = user.name === d.me;
      return html`<tr>
        <td><b>${user.name}</b>${self ? html` <span class="muted">(jij)</span>` : ''}</td>
        <td>${user.admin ? html`<span class="badge warning">Beheerder</span>` : html`<span class="badge plain">Developer</span>`}
          ${user.disabled ? html` <span class="badge error">Uit</span>` : ''}</td>
        <td>${user.twoFactor ? html`<span class="badge success">${icon('shield')} 2FA</span>` : html`<span class="badge plain">Nog niet ingesteld</span>`}
          ${user.mustChangePassword ? html` <span class="badge info">Tijdelijk wachtwoord</span>` : ''}</td>
        <td class="nowrap muted">${user.lastLogin ? ago(user.lastLogin) : 'nooit'}${user.sessions ? html` · ${num(user.sessions)} actief` : ''}</td>
        <td class="actions">${self ? '' : html`
          <button class="btn sm" data-action="user-act" data-name="${user.name}" data-act="reset-password" title="Tijdelijk wachtwoord">${icon('key')}</button>
          <button class="btn sm" data-action="user-act" data-name="${user.name}" data-act="reset-2fa" title="2FA resetten">${icon('shield')}</button>
          <button class="btn sm" data-action="user-act" data-name="${user.name}" data-act="admin" data-value="${!user.admin}">${user.admin ? 'Developer maken' : 'Beheerder maken'}</button>
          <button class="btn sm ${user.disabled ? '' : 'warn'}" data-action="user-act" data-name="${user.name}" data-act="disable" data-value="${!user.disabled}">${user.disabled ? 'Aanzetten' : 'Uitzetten'}</button>
          <button class="btn sm danger icon-only" data-action="user-act" data-name="${user.name}" data-act="delete" title="Verwijderen" aria-label="${user.name} verwijderen">${icon('trash')}</button>`}</td></tr>`;
    });
    render(main, html`${pageHead('Gebruikers', 'Wie op het dev-paneel mag. 2FA is voor iedereen verplicht. Developers kunnen geen gebruikers beheren en bij Databases niets weggooien, inladen of rechten aanpassen.',
        html`<button class="btn primary" data-action="user-new">${icon('userPlus')} Nieuwe gebruiker</button>`)}
      ${card('Gebruikers', 'users', html`<div class="table-wrap"><table>
        <thead><tr><th>Naam</th><th>Rol</th><th>Beveiliging</th><th>Laatst ingelogd</th><th></th></tr></thead>
        <tbody>${rows}</tbody></table></div>`, html`<span class="count">${num(d.users.length)}</span>`)}`);
  }

  const USER_ACTIONS = {
    'reset-password': ['Tijdelijk wachtwoord geven?', 'Het oude wachtwoord werkt niet meer en de gebruiker wordt overal uitgelogd.', 'Wachtwoord resetten', false],
    'reset-2fa': ['2FA resetten?', 'De gebruiker wordt overal uitgelogd en krijgt een tijdelijk wachtwoord. Bij de volgende keer inloggen koppelt hij opnieuw een authenticator-app en kiest hij een eigen wachtwoord. (Zo kan iemand met alleen het oude wachtwoord nooit 2FA omzeilen.)', '2FA resetten', false],
    delete: ['Gebruiker verwijderen?', 'Deze gebruiker kan niet meer inloggen op het dev-paneel.', 'Verwijderen', true]
  };

  async function pageAudit(main, alive) {
    const data = await api('/audit');
    if (!alive()) return;
    const rows = data.entries.length ? data.entries.map(entry => html`<tr>
        <td class="nowrap muted">${dateTime(entry.time)}</td><td>${entry.user || '—'}</td><td>${entry.action}</td>
        <td class="mono">${entry.target || ''}</td><td class="muted">${entry.details || ''}</td><td class="mono muted hide-sm">${entry.ip || ''}</td></tr>`)
      : emptyRow(6, 'Nog niets gebeurd.');
    render(main, html`${pageHead('Logboek', 'Alles wat er op het dev-paneel gebeurt: inloggen, gebruikers, databases, ….')}
      ${card('Laatste acties', 'log', html`<div class="table-wrap"><table>
        <thead><tr><th>Wanneer</th><th>Wie</th><th>Wat</th><th>Waarop</th><th>Details</th><th class="hide-sm">IP</th></tr></thead>
        <tbody>${rows}</tbody></table></div>`)}`);
  }

  async function pageAccount(main) {
    render(main, html`${pageHead('Mijn account', `Ingelogd als ${state.user.name}.`)}
      <div class="grid two">
        ${card('Wachtwoord wijzigen', 'key', html`<form class="card-body stack" data-form="account-password">
          <label class="field">Huidig wachtwoord<input class="input" type="password" name="current" autocomplete="current-password" required></label>
          <label class="field">Nieuw wachtwoord <span class="hint">minimaal 10 tekens</span><input class="input" type="password" name="password" autocomplete="new-password" minlength="10" required></label>
          <label class="field">Nog een keer<input class="input" type="password" name="again" autocomplete="new-password" minlength="10" required></label>
          <div class="btn-row"><button class="btn primary">${icon('check')} Wijzigen</button></div>
          <p class="muted small">Je wordt daarna overal uitgelogd, behalve hier.</p></form>`)}
        ${card('Sessies', 'lock', html`<div class="card-body">
          <p class="muted">Ergens ingelogd laten en vergeten? Log overal uit, behalve in deze browser.</p>
          <div class="btn-row"><button class="btn" data-action="logout-everywhere">${icon('logout')} Overal uitloggen</button></div>
          <p class="muted small" style="margin-top:12px">2FA kwijt? Een beheerder kan je 2FA resetten (je krijgt dan ook een tijdelijk wachtwoord), of op de server: <span class="kbd">sudo pinda-host reset-2fa ${state.user.name}</span></p></div>`)}
      </div>`);
  }

  // =============================================================== klikken

  const actions = {
    close: () => closeModal(),
    reload: () => navigate(),
    'open-nav': () => document.body.classList.add('nav-open'),
    'close-nav': () => document.body.classList.remove('nav-open'),

    copy(button) {
      navigator.clipboard.writeText(button.dataset.text).then(() => toast('Gekopieerd.'), () => toast('Kopiëren lukte niet; selecteer en kopieer zelf.', 'error'));
    },

    async logout(button) {
      await busy(button, () => post('/logout'));
      state.user = null;
      clearTimers();
      closeModal();
      await refreshInfo().catch(() => {});
      renderLogin('Je bent uitgelogd.', 'info');
    },

    async 'logout-everywhere'(button) {
      const result = await busy(button, () => post('/account/logout-everywhere'));
      if (result) toast(result.removed ? `${num(result.removed)} andere ${result.removed === 1 ? 'sessie' : 'sessies'} uitgelogd.` : 'Er waren geen andere sessies.');
    },

    'restart-login'() {
      state.challenge = null;
      boot();
    },

    async 'wizard-db'(button) {
      const type = button.dataset.type;
      if (type === 'mysql' && state.setup.database !== 'mysql') {
        const ok = await confirmDialog({ title: 'MySQL gebruiken?', text: 'Er wordt een database "pindacraft" aangemaakt met een eigen gebruiker en een sterk wachtwoord. De plugin gebruikt die vanaf de eerste start.', confirm: 'MySQL gebruiken' });
        if (!ok) return;
      }
      const result = await busy(button, () => post('/setup/database', { type }));
      if (result) renderWizard(1);
    },
    'wizard-step'(button) { renderWizard(Number(button.dataset.step)); },
    async 'wizard-finish'(button) {
      const result = await busy(button, () => post('/setup/finish'));
      if (!result) return;
      await refreshInfo();
      location.hash = '#/dashboard';
      afterLogin();
    },

    async 'plugin-mysql'(button) {
      const plugin = state.db.plugin;
      const ok = await confirmDialog({
        title: 'PindaFramework omzetten naar MySQL?',
        text: plugin.sqliteExists
          ? `Er komt een database "pindacraft". Bij de volgende start van de Minecraft-server worden alle gegevens uit ${plugin.sqliteFile} overgezet en gecontroleerd. Het oude bestand blijft als backup bewaard. Gaat er iets mis, dan draait de server gewoon op SQLite verder. Draait de server, dan wordt hij nu herstart.`
          : 'Er komt een database "pindacraft". De plugin gebruikt die vanaf de volgende start.',
        confirm: 'Omzetten'
      });
      if (!ok) return;
      const result = await busy(button, () => post('/database/plugin/mysql', { restart: true }));
      if (!result) return;
      afterDatabaseChange(result, result.restarted ? 'De server wordt herstart en zet de gegevens om.'
        : result.converting ? 'Klaar. De gegevens worden omgezet bij de volgende start van de server.' : 'De plugin gebruikt MySQL vanaf de volgende start.');
    },

    async 'db-drop'(button) {
      const name = button.dataset.name;
      // Een gebruiker met dezelfde naam die alleen bij deze database kan, hoort er meestal bij.
      const own = state.db.users.find(u => u.name === name && u.host === 'localhost' && u.databases.length === 1 && u.databases[0] === name);
      const ok = await confirmDialog({ title: `Database ${name} verwijderen?`, text: 'Alle tabellen en gegevens erin zijn dan weg. Dit kan niet ongedaan worden gemaakt. Download hem eerst als je twijfelt.', confirm: 'Verwijderen', danger: true, typeToConfirm: name,
        checkbox: own ? { label: `Ook gebruiker ${name} verwijderen`, checked: true } : null });
      if (!ok) return;
      afterDatabaseChange(await busy(button, () => post(`/database/databases/${encodeURIComponent(name)}/delete`, { confirm: name, dropUser: !!own && state.confirmChecked })), `Database ${name} verwijderd.`);
    },

    'db-import'(button) {
      const name = button.dataset.name;
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = '.sql,text/plain,application/sql';
      input.addEventListener('change', async () => {
        const file = input.files[0];
        if (!file) return;
        if (file.size > 100 * 1024 * 1024) {
          const go = await confirmDialog({ title: 'Groot bestand', text: `Dit bestand is ${bytes(file.size)}. Loopt het paneel via Cloudflare, dan is de grens 100 MB per upload. Toch proberen?`, confirm: 'Toch proberen' });
          if (!go) return;
        }
        const ok = await confirmDialog({ title: `${file.name} inladen in ${name}?`, text: 'Tabellen met dezelfde naam worden meestal eerst weggegooid en opnieuw gemaakt (zo werkt een .sql-export). Download eerst een backup als je twijfelt.', confirm: 'Inladen' });
        if (!ok) return;
        const job = await busy(button, () => upload(`/database/databases/${encodeURIComponent(name)}/import`, file));
        if (job) watchJob(job, () => { if (location.hash.startsWith('#/databases')) navigate(); });
      });
      input.click();
    },

    async 'db-grant'(button) {
      const { user, host } = button.dataset;
      const current = (state.db.users.find(u => u.name === user && u.host === host) || { databases: [] }).databases;
      const options = state.db.databases.filter(db => !current.includes(db.name));
      if (!options.length) { toast('Deze gebruiker kan al bij alle databases.', 'error'); return; }
      openModal(html`<form data-form="db-grant" data-user="${user}" data-host="${host}">
        <div class="modal-head"><h3>Toegang voor ${user}</h3><p>Alle rechten op de gekozen database.</p></div>
        <div class="modal-body"><label class="field">Database<select name="database">${options.map(db => html`<option value="${db.name}">${db.name}</option>`)}</select></label></div>
        <div class="modal-foot"><button type="button" class="btn ghost" data-action="close">Annuleren</button><button class="btn primary">${icon('check')} Toegang geven</button></div></form>`);
    },

    async 'db-revoke'(button) {
      const { user, host, db } = button.dataset;
      const ok = await confirmDialog({ title: `Toegang intrekken?`, text: `${user} kan daarna niet meer bij ${db}.`, confirm: 'Intrekken', danger: true });
      if (!ok) return;
      afterDatabaseChange(await busy(button, () => post(`/database/users/${encodeURIComponent(user)}/${encodeURIComponent(host)}/revoke`, { database: db })), 'Toegang ingetrokken.');
    },

    async 'db-password'(button) {
      const { user, host } = button.dataset;
      const text = button.dataset.plugin === 'true'
        ? 'Dit is de gebruiker van PindaFramework. Het nieuwe wachtwoord komt meteen in database.yml; na een herstart van de Minecraft-server gebruikt de plugin het. Handig als de plugin geen verbinding meer krijgt.'
        : 'Het oude wachtwoord werkt dan niet meer. Pas het daarna aan in de plugin die deze gebruiker gebruikt.';
      const ok = await confirmDialog({ title: `Nieuw wachtwoord voor ${user}?`, text, confirm: 'Nieuw wachtwoord' });
      if (!ok) return;
      afterDatabaseChange(await busy(button, () => post(`/database/users/${encodeURIComponent(user)}/${encodeURIComponent(host)}/password`)), null);
    },

    async 'db-user-drop'(button) {
      const { user, host } = button.dataset;
      const ok = await confirmDialog({ title: `Gebruiker ${user} verwijderen?`, text: 'Plugins die met deze gebruiker inloggen, kunnen dat daarna niet meer. De databases zelf blijven bestaan.', confirm: 'Verwijderen', danger: true });
      if (!ok) return;
      afterDatabaseChange(await busy(button, () => post(`/database/users/${encodeURIComponent(user)}/${encodeURIComponent(host)}/delete`)), `Gebruiker ${user} verwijderd.`);
    },

    'user-new'() {
      openModal(html`<form data-form="user-new">
        <div class="modal-head"><h3>Nieuwe gebruiker</h3><p>Je krijgt een tijdelijk wachtwoord. Bij de eerste keer inloggen stelt de gebruiker 2FA in en kiest een eigen wachtwoord.</p></div>
        <div class="modal-body stack">
          <label class="field">Gebruikersnaam<input class="input" name="name" minlength="3" maxlength="32" pattern="[A-Za-z0-9_.\\-]{3,32}" required autofocus></label>
          <label class="check"><input type="checkbox" name="admin"> Beheerder (mag ook gebruikers beheren)</label>
        </div>
        <div class="modal-foot"><button type="button" class="btn ghost" data-action="close">Annuleren</button><button class="btn primary">${icon('userPlus')} Aanmaken</button></div></form>`);
    },

    async 'user-act'(button) {
      const { name, act } = button.dataset;
      const value = button.dataset.value === 'true';
      const confirmText = USER_ACTIONS[act];
      if (confirmText) {
        const [title, text, confirm, danger] = confirmText;
        if (!await confirmDialog({ title: `${title.replace('?', '')} (${name})?`, text, confirm, danger })) return;
      }
      const result = await busy(button, () => post(`/users/${encodeURIComponent(name)}/${act}`, act === 'admin' || act === 'disable' ? { value } : {}));
      if (!result) return;
      renderUsers($('#main'), result);
      if (result.password) {
        showSecret({ title: `Tijdelijk wachtwoord voor ${name}`, intro: act === 'reset-2fa'
          ? 'Geef dit door aan de gebruiker. Bij het inloggen koppelt hij opnieuw 2FA en kiest hij een eigen wachtwoord.'
          : 'Geef dit door aan de gebruiker. Bij het inloggen kiest hij een eigen wachtwoord.', rows: [['Gebruiker', name], ['Wachtwoord', result.password, true]] });
      } else {
        toast('Opgeslagen.');
      }
    }
  };

  // =============================================================== formulieren

  const forms = {
    async login(form, data, button) {
      const result = await busy(button, () => post('/login', { username: data.get('username').trim(), password: data.get('password') }));
      if (result) renderCode(result);
    },

    async 'login-verify'(form, data, button) {
      await verifyCode(form, data, button, '/login/verify', false);
    },

    async 'setup-verify'(form, data, button) {
      await verifyCode(form, data, button, '/setup/account/verify', true);
    },

    async 'login-password'(form, data, button) {
      if (data.get('password') !== data.get('again')) { renderNewPassword('De wachtwoorden zijn niet hetzelfde.'); return; }
      try {
        button.disabled = true;
        const result = await post('/login/password', { password: data.get('password') });
        await finishLogin(result);
      } catch (error) {
        if (error.status === 401) renderLogin(error.message); else renderNewPassword(error.message);
      }
    },

    async 'setup-account'(form, data, button) {
      if (data.get('password') !== data.get('again')) { toast('De wachtwoorden zijn niet hetzelfde.', 'error'); return; }
      const result = await busy(button, () => post('/setup/account', { code: data.get('code'), username: data.get('username').trim(), password: data.get('password') }));
      if (result) renderCode(result, '', true);
    },

    async 'wizard-server'(form, data, button) {
      const result = await busy(button, () => post('/setup/server', {
        serverName: data.get('serverName').trim(), siteDomain: data.get('siteDomain').trim(),
        gameAddress: data.get('gameAddress').trim(), gamePort: Number(data.get('gamePort'))
      }));
      if (result) { await refreshInfo(); renderWizard(1); }
    },

    async 'db-create'(form, data, button) {
      const result = await busy(button, () => post('/database/databases', { name: data.get('name').trim(), createUser: form.createUser.checked }));
      if (result) { afterDatabaseChange(result, `Database ${data.get('name').trim()} aangemaakt.`); }
    },

    async 'db-user-create'(form, data, button) {
      const result = await busy(button, () => post('/database/users', { name: data.get('name').trim(), database: data.get('database') }));
      afterDatabaseChange(result, result ? `Gebruiker ${data.get('name').trim()} aangemaakt.` : null);
    },

    async 'db-grant'(form, data, button) {
      const { user, host } = form.dataset;
      const result = await busy(button, () => post(`/database/users/${encodeURIComponent(user)}/${encodeURIComponent(host)}/grant`, { database: data.get('database') }));
      if (result) { closeModal(); afterDatabaseChange(result, `${user} kan nu bij ${data.get('database')}.`); }
    },

    async 'user-new'(form, data, button) {
      const name = data.get('name').trim();
      const result = await busy(button, () => post('/users', { name, admin: form.admin.checked }));
      if (!result) return;
      renderUsers($('#main'), result);
      showSecret({ title: `${name} is aangemaakt`, intro: 'Geef deze gegevens door. Bij de eerste keer inloggen koppelt de gebruiker 2FA en kiest een eigen wachtwoord.', rows: [['Adres', location.origin], ['Gebruiker', name], ['Tijdelijk wachtwoord', result.password, true]] });
    },

    async 'account-password'(form, data, button) {
      if (data.get('password') !== data.get('again')) { toast('De nieuwe wachtwoorden zijn niet hetzelfde.', 'error'); return; }
      const result = await busy(button, () => post('/account/password', { current: data.get('current'), password: data.get('password') }));
      if (result) { form.reset(); toast('Je wachtwoord is gewijzigd.'); }
    }
  };

  async function verifyCode(form, data, button, path, setup) {
    if (state.verifying) return;
    state.verifying = true;
    button.disabled = true;
    try {
      const result = await post(path, { code: data.get('code').replace(/\s/g, '') });
      if (result.step === 'password') { renderNewPassword(); return; }
      await finishLogin(result);
    } catch (error) {
      if (error.status === 401 || error.status === 429) {
        if (setup) { await refreshInfo().catch(() => {}); if (state.info.needsSetup) renderSetupAccount(error.message); else renderLogin(error.message); }
        else renderLogin(error.message);
      } else {
        renderCode(state.challenge, error.message, setup);
      }
    } finally {
      state.verifying = false;
    }
  }

  async function finishLogin(result) {
    state.user = result.user;
    await refreshInfo();
    afterLogin();
  }

  document.addEventListener('click', event => {
    const target = event.target.closest('[data-action]');
    if (!target || target.disabled) return;
    const handler = actions[target.dataset.action];
    if (!handler) return;
    event.preventDefault();
    handler(target, event);
  });

  document.addEventListener('submit', event => {
    const form = event.target.closest('form[data-form]');
    if (!form) return;
    event.preventDefault();
    const handler = forms[form.dataset.form];
    if (!handler) return;
    const button = event.submitter || $('button:not([type=button])', form);
    handler(form, new FormData(form), button);
  });

  boot();
})();
