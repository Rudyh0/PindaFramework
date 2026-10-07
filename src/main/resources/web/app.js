/* PindaFramework - webpaneel. Geen externe bibliotheken; alles wat de server stuurt wordt ge-escaped. */
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
    players: '<circle cx="9" cy="8" r="4"/><path d="M2 21c0-3.9 3.1-7 7-7s7 3.1 7 7"/><path d="M16 4.1a4 4 0 0 1 0 7.8M22 21c0-3.2-2-6-5-6.7"/>',
    coin: '<circle cx="12" cy="12" r="9"/><path d="M14.8 9.2A3 3 0 0 0 12 7.6c-1.7 0-3 .9-3 2.2 0 3 6 1.6 6 4.6 0 1.3-1.3 2.2-3 2.2a3 3 0 0 1-2.8-1.6M12 6v1.6M12 16.6V18"/>',
    shop: '<path d="M3 9l1.5-5h15L21 9"/><path d="M3 9h18v1a3 3 0 0 1-6 0 3 3 0 0 1-6 0 3 3 0 0 1-6 0z"/><path d="M5 12.5V21h14v-8.5M10 21v-5h4v5"/>',
    shield: '<path d="M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6z"/><path d="M12 8v4M12 15.5h.01"/>',
    server: '<rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="13" width="18" height="7" rx="2"/><path d="M7 7.5h.01M7 16.5h.01M11 7.5h6M11 16.5h6"/>',
    terminal: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 9l3 3-3 3M13 15h4"/>',
    log: '<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><path d="M14 3v6h6M8 13h8M8 17h5"/>',
    logout: '<path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 17l5-5-5-5M15 12H4"/>',
    menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
    back: '<path d="M15 18l-6-6 6-6"/>',
    refresh: '<path d="M21 12a9 9 0 1 1-2.6-6.4L21 8"/><path d="M21 3v5h-5"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
    sunrise: '<path d="M17 18a5 5 0 0 0-10 0M12 2v7M4.2 10.2l1.4 1.4M1 18h2M21 18h2M18.4 11.6l1.4-1.4M23 22H1M8 6l4-4 4 4"/>',
    sunset: '<path d="M17 18a5 5 0 0 0-10 0M12 9V2M4.2 10.2l1.4 1.4M1 18h2M21 18h2M18.4 11.6l1.4-1.4M23 22H1M16 5l-4 4-4-4"/>',
    moon: '<path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/>',
    stars: '<path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/><path d="M17 3v3M15.5 4.5h3"/>',
    rain: '<path d="M7 15a4 4 0 0 1-.6-8 5.5 5.5 0 0 1 10.6 1.5A3.5 3.5 0 0 1 17 15"/><path d="M8 18l-1 2.5M12 18l-1 2.5M16 18l-1 2.5"/>',
    bolt: '<path d="M7 15a4 4 0 0 1-.6-8 5.5 5.5 0 0 1 10.6 1.5A3.5 3.5 0 0 1 17 15"/><path d="M12.5 13l-2 4h3l-2 4"/>',
    clear: '<circle cx="12" cy="12" r="4"/><path d="M12 3v1.5M12 19.5V21M3 12h1.5M19.5 12H21"/>',
    megaphone: '<path d="M3 11v3a1 1 0 0 0 1 1h2l5 4V6L6 10H4a1 1 0 0 0-1 1z"/><path d="M15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13"/>',
    save: '<path d="M5 3h11l3 3v13a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"/><path d="M7 3v5h8V3M7 21v-7h10v7"/>',
    power: '<path d="M12 3v9"/><path d="M6.3 6.3a8 8 0 1 0 11.4 0"/>',
    list: '<path d="M9 6h11M9 12h11M9 18h11M4 6h.01M4 12h.01M4 18h.01"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    gauge: '<path d="M12 14l4-4"/><path d="M3.3 17a9 9 0 1 1 17.4 0"/>',
    memory: '<rect x="3" y="6" width="18" height="12" rx="2"/><path d="M7 10v4M11 10v4M15 10v4"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/>',
    online: '<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4.4 3.6-8 8-8s8 3.6 8 8"/>',
    userPlus: '<circle cx="9" cy="8" r="4"/><path d="M2 21c0-3.9 3.1-7 7-7 1.4 0 2.7.4 3.8 1.1M19 14v6M16 17h6"/>',
    ban: '<circle cx="12" cy="12" r="9"/><path d="M5.6 5.6l12.8 12.8"/>',
    mute: '<path d="M11 5L6 9H3v6h3l5 4z"/><path d="M22 9l-6 6M16 9l6 6"/>',
    kick: '<path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 17l5-5-5-5M15 12H4"/>',
    warn: '<path d="M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4M12 17h.01"/>',
    undo: '<path d="M9 14L4 9l5-5"/><path d="M4 9h11a5 5 0 0 1 0 10h-3"/>',
    crown: '<path d="M3 7l4 4 5-7 5 7 4-4-2 12H5z"/>',
    bank: '<path d="M3 10l9-6 9 6M5 10v8M9 10v8M15 10v8M19 10v8M3 21h18"/>',
    sign: '<rect x="3" y="4" width="18" height="11" rx="1.5"/><path d="M12 15v6M8 21h8"/>',
    lock: '<rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
    x: '<path d="M18 6L6 18M6 6l12 12"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    send: '<path d="M22 2L11 13M22 2l-7 20-4-9-9-4z"/>',
    check: '<path d="M20 6L9 17l-5-5"/>',
    reload: '<path d="M3 12a9 9 0 0 1 15.4-6.4L21 8M21 3v5h-5M21 12a9 9 0 0 1-15.4 6.4L3 16M3 21v-5h5"/>',
    box: '<path d="M21 8l-9-5-9 5v8l9 5 9-5z"/><path d="M3 8l9 5 9-5M12 13v8"/>',
    trending: '<path d="M3 17l6-6 4 4 8-8M15 7h6v6"/>',
    award: '<circle cx="12" cy="8" r="6"/><path d="M8.5 13.5L7 22l5-3 5 3-1.5-8.5"/>',
    zap: '<path d="M13 2L4 14h7l-1 8 9-12h-7z"/>'
  };
  const icon = (name, cls = '') => raw(`<svg class="i ${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name] || ''}</svg>`);

  // =============================================================== opmaak

  const numberFormat = new Intl.NumberFormat('nl-NL');
  const num = n => numberFormat.format(n || 0);
  const fixed = (n, digits = 1) => (n || 0).toLocaleString('nl-NL', { minimumFractionDigits: digits, maximumFractionDigits: digits });
  const dateTimeFormat = new Intl.DateTimeFormat('nl-NL', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  const dateFormat = new Intl.DateTimeFormat('nl-NL', { day: 'numeric', month: 'short', year: 'numeric' });
  const timeFormat = new Intl.DateTimeFormat('nl-NL', { hour: '2-digit', minute: '2-digit' });
  const dateTime = ms => ms ? dateTimeFormat.format(new Date(ms)) : '—';
  const date = ms => ms ? dateFormat.format(new Date(ms)) : '—';
  const clock = ms => timeFormat.format(new Date(ms));

  function ago(ms) {
    if (!ms) return '—';
    const seconds = Math.round((Date.now() - ms) / 1000);
    if (seconds < 45) return 'zojuist';
    const minutes = Math.round(seconds / 60);
    if (minutes < 60) return `${minutes} min geleden`;
    const hours = Math.round(minutes / 60);
    if (hours < 24) return `${hours} uur geleden`;
    const days = Math.round(hours / 24);
    if (days === 1) return 'gisteren';
    if (days < 30) return `${days} dagen geleden`;
    return date(ms);
  }

  /** Een duur in woorden: "2 dagen 3 uur". */
  function human(ms) {
    const days = Math.floor(ms / 86400000);
    const hours = Math.floor((ms % 86400000) / 3600000);
    const minutes = Math.floor((ms % 3600000) / 60000);
    const parts = [];
    if (days) parts.push(days === 1 ? '1 dag' : `${days} dagen`);
    if (hours) parts.push(`${hours} uur`);
    if (minutes && !days) parts.push(`${minutes} min`);
    return parts.join(' ') || 'minder dan een minuut';
  }

  function uptime(ms) {
    let seconds = Math.floor(ms / 1000);
    const days = Math.floor(seconds / 86400); seconds %= 86400;
    const hours = Math.floor(seconds / 3600); seconds %= 3600;
    const minutes = Math.floor(seconds / 60);
    return [days ? `${days}d` : '', days || hours ? `${hours}u` : '', `${minutes}m`].filter(Boolean).join(' ');
  }

  function bytes(n) {
    const gb = n / 1024 ** 3;
    return gb >= 1 ? `${fixed(gb, 1)} GB` : `${num(Math.round(n / 1024 ** 2))} MB`;
  }

  /** Minecraft-tijd (ticks) als klok: 0 ticks = 06:00. */
  function mcClock(ticks) {
    const total = (ticks + 6000) % 24000;
    const h = Math.floor(total / 1000);
    const m = Math.floor((total % 1000) * 60 / 1000);
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
  }
  const isNight = ticks => ticks >= 12500 && ticks < 23500;
  const tpsClass = tps => tps >= 18.5 ? 'success' : tps >= 15 ? 'warning' : 'error';

  const GAMEMODES = { survival: 'Survival', creative: 'Creative', adventure: 'Adventure', spectator: 'Toeschouwer' };
  const ENVIRONMENTS = { normal: 'Overworld', nether: 'Nether', the_end: 'The End', custom: 'Aangepast' };
  const TYPES = { ban: ['Ban', 'error', 'ban'], mute: ['Mute', 'warning', 'mute'], kick: ['Kick', 'warning', 'kick'], warn: ['Waarschuwing', 'info', 'warn'] };
  const STATUS = { active: ['Actief', 'error'], expired: ['Verlopen', 'plain'], lifted: ['Opgeheven', 'success'], done: ['Afgehandeld', 'plain'] };
  const ECO_TYPES = {
    start: 'Startbedrag', 'admin-give': 'Gegeven door staff', 'admin-take': 'Afgenomen door staff', 'admin-set': 'Ingesteld door staff',
    death: 'Verloren bij dood', deposit: 'Gestort op bank', withdraw: 'Opgenomen van bank', pay: 'Betaald aan speler',
    'pay-received': 'Ontvangen van speler', pickup: 'Geld opgeraapt', playtime: 'Online-bonus', refund: 'Terugbetaling',
    'shop-buy': 'Aankoop in shop', 'shop-fee': 'Marketplace fee', 'shop-sale': 'Verkoop in shop', spend: 'Uitgegeven', income: 'Inkomsten',
    'skill-level': 'Skill-beloning'
  };

  const PLACEHOLDER = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 8 8"><rect width="8" height="8" fill="#2a251f"/><rect x="2" y="2" width="4" height="4" fill="#3d352c"/></svg>');
  const head = name => `https://mc-heads.net/avatar/${encodeURIComponent(name || 'MHF_Steve')}/64`;
  const avatar = (name, size = '') => html`<img class="avatar ${size}" src="${head(name)}" alt="" loading="lazy" referrerpolicy="no-referrer">`;
  const rankBadge = rank => rank ? html`<span class="badge rank" style="--c:${rank.color}">${rank.name}</span>` : '';
  const who = (name, uuid, rank) => html`<div class="who">${avatar(name)}<span class="who-name">${uuid && can(P.players) ? html`<a href="#/spelers/${uuid}">${name}</a>` : name}</span>${rank ? rankBadge(rank) : ''}</div>`;
  const typeBadge = type => { const t = TYPES[type] || [type, 'plain']; return html`<span class="badge ${t[1]}">${t[0]}</span>`; };
  const statusBadge = status => { const s = STATUS[status] || [status, 'plain']; return html`<span class="badge ${s[1]}">${s[0]}</span>`; };
  const amountClass = cents => cents > 0 ? 'amount-plus' : cents < 0 ? 'amount-min' : '';
  /** Alleen het getal van een bedrag ("1.250 PindaCredits" -> "1.250"), voor plekken waar de valuta al in het label staat. */
  const amount = money => { const text = money && money.text !== undefined ? money.text : String(money || ''); const space = text.indexOf(' '); return space < 0 ? text : text.slice(0, space); };
  const currency = () => (state.me && state.me.currency) || 'PindaCredits';
  const label = (text, hint) => html`<span class="field-label">${text}${hint ? html` <span class="hint">${hint}</span>` : ''}</span>`;

  function expiresText(p) {
    if (p.type !== 'ban' && p.type !== 'mute') return '—';
    if (p.expires === null) return 'Permanent';
    const left = p.expires - Date.now();
    return left > 0 ? `${dateTime(p.expires)} (nog ${human(left)})` : dateTime(p.expires);
  }

  // =============================================================== status en API

  const P = {
    use: 'pinda.panel.use', players: 'pinda.panel.players', moderate: 'pinda.panel.moderate',
    ecoView: 'pinda.panel.economy.view', ecoEdit: 'pinda.panel.economy.edit', ranks: 'pinda.panel.ranks',
    shops: 'pinda.panel.shops', shopsManage: 'pinda.panel.shops.manage', server: 'pinda.panel.server',
    stop: 'pinda.panel.stop', console: 'pinda.panel.console', log: 'pinda.panel.log', banPermanent: 'pinda.mod.ban.permanent',
    skills: 'pinda.panel.skills', skillsEdit: 'pinda.panel.skills.edit'
  };

  const state = { me: null, info: null, timers: [], renderId: 0, history: null, playerQuery: '' };
  const can = permission => !!state.me && state.me.permissions.includes(permission);
  const feature = name => !!state.me && !!state.me.features[name];
  const serverName = () => (state.me && state.me.server) || (state.info && state.info.server) || 'PindaCraft';

  class ApiError extends Error {
    constructor(message, status) { super(message); this.status = status; }
  }

  async function api(path, body) {
    const options = { credentials: 'same-origin', headers: {} };
    if (body !== undefined) {
      options.method = 'POST';
      options.headers['Content-Type'] = 'application/json';
      options.headers['X-Pinda'] = '1';
      options.body = JSON.stringify(body);
    }
    let response;
    try {
      response = await fetch('/api' + path, options);
    } catch {
      throw new ApiError('Geen verbinding met de server. Staat hij nog aan?', 0);
    }
    let data = null;
    try { data = await response.json(); } catch { /* geen JSON */ }
    if (!response.ok) {
      const error = new ApiError(data && data.error ? data.error : `Er ging iets mis (${response.status}).`, response.status);
      if (response.status === 401 && state.me) {
        state.me = null;
        clearTimers();
        closeModal();
        renderLogin(error.message);
      }
      throw error;
    }
    return data;
  }
  const post = (path, body = {}) => api(path, body);

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
  function openModal(view) {
    modal.returnValue = '';
    render(modal, view);
    if (!modal.open) modal.showModal();
    const focus = $('[autofocus]', modal) || $('input:not([type=hidden]), select, textarea', modal);
    if (focus) focus.focus();
  }
  function closeModal() {
    if (modal.open) modal.close();
  }
  modal.addEventListener('click', event => { if (event.target === modal) closeModal(); });

  function confirmDialog({ title, text, confirm = 'Bevestigen', danger = false }) {
    openModal(html`<form method="dialog">
      <div class="modal-head"><h3>${title}</h3></div>
      <div class="modal-body"><p class="muted">${text}</p></div>
      <div class="modal-foot">
        <button type="button" class="btn ghost" data-action="close">Annuleren</button>
        <button class="btn ${danger ? 'solid-danger' : 'primary'}" value="ok" autofocus>${confirm}</button>
      </div></form>`);
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

  // =============================================================== inloggen

  function renderLogin(message, pending = false, kind = 'error') {
    document.body.classList.remove('nav-open');
    const name = serverName();
    render($('#app'), html`<div class="login"><div class="login-card">
      <img class="login-logo" src="/favicon.svg" alt="">
      <h1>${name} <span>paneel</span></h1>
      ${pending
        ? html`<p class="lead"><span class="spinner sm"></span>&nbsp; Bezig met inloggen…</p>`
        : html`<p class="lead">Het beheerpaneel van ${name}, alleen voor staff.</p>
          <ol class="login-steps">
            <li><b>1</b><span>Ga op ${name} en typ <span class="kbd">/panel</span> in de chat.</span></li>
            <li><b>2</b><span>Klik op de link die je krijgt. Die werkt één keer en is maar kort geldig.</span></li>
            <li><b>3</b><span>Je bent ingelogd. Na een tijd zonder activiteit log je vanzelf uit.</span></li>
          </ol>`}
      ${message ? html`<div class="alert ${kind}">${message}</div>` : ''}
      <p class="login-foot">PindaFramework${state.info ? ` v${state.info.version}` : ''}</p>
    </div></div>`);
  }

  // =============================================================== opbouw en navigatie

  const ROUTES = [
    { path: 'dashboard', title: 'Dashboard', icon: 'dashboard', perm: P.use, page: pageDashboard },
    { path: 'spelers', title: 'Spelers', icon: 'players', perm: P.players, page: pagePlayers },
    { path: 'economie', title: 'Economie', icon: 'coin', perm: P.ecoView, feature: 'economy', page: pageEconomy },
    { path: 'shops', title: 'Shops', icon: 'shop', perm: P.shops, feature: 'shop', page: pageShops },
    { path: 'skills', title: 'Skills', icon: 'award', perm: P.skills, feature: 'skills', page: pageSkills },
    { path: 'straffen', title: 'Straffen', icon: 'shield', perm: P.players, feature: 'moderation', page: pagePunishments },
    { path: 'server', title: 'Server', icon: 'server', perm: P.server, page: pageServer, group: 'Beheer' },
    { path: 'console', title: 'Console', icon: 'terminal', perm: P.console, page: pageConsole, group: 'Beheer' },
    { path: 'logboek', title: 'Logboek', icon: 'log', perm: P.log, page: pageLog, group: 'Beheer' }
  ];
  const visibleRoutes = () => ROUTES.filter(route => can(route.perm) && (!route.feature || feature(route.feature)));

  function renderShell() {
    const me = state.me;
    let lastGroup = null;
    const links = visibleRoutes().map(route => {
      const label = route.group && route.group !== lastGroup ? html`<div class="nav-label">${route.group}</div>` : '';
      lastGroup = route.group || lastGroup;
      return html`${label}<a href="#/${route.path}" data-route="${route.path}">${icon(route.icon)}<span>${route.title}</span></a>`;
    });
    render($('#app'), html`<div class="layout">
      <aside class="sidebar">
        <div class="brand"><img src="/favicon.svg" alt=""><div><div class="brand-name">${me.server}</div><div class="brand-sub">Beheerpaneel</div></div></div>
        <nav class="nav">${links}</nav>
        <div class="sidebar-foot">
          <div class="me">${avatar(me.name, 'md')}<div class="me-text"><div class="me-name">${me.name}</div>${rankBadge(me.rank)}</div>
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
    document.title = `${me.server} · Paneel`;
  }

  async function navigate() {
    if (!state.me || !$('#main')) return;
    clearTimers();
    closeModal();
    document.body.classList.remove('nav-open');
    const [path = '', param] = location.hash.replace(/^#\/?/, '').split('/').map(decodeURIComponent);
    const routes = visibleRoutes();
    const route = routes.find(r => r.path === path) || routes[0];
    if (!route) return;
    if (route.path !== path) {
      location.replace(`#/${route.path}`);
      return;
    }
    $$('.nav a').forEach(link => link.classList.toggle('active', link.dataset.route === route.path));
    $('#topbar-title').textContent = route.title;
    const main = $('#main');
    const id = ++state.renderId;
    const alive = () => id === state.renderId && !!state.me;
    render(main, html`<div class="page-loading"><div class="spinner"></div></div>`);
    window.scrollTo(0, 0);
    try {
      await route.page(main, param, alive);
    } catch (error) {
      if (!alive() || error.status === 401) return;
      render(main, html`<div class="empty-page">${icon('warn')}<h2>Dit lukte niet</h2><p>${error.message}</p>
        <button class="btn" data-action="reload">${icon('refresh')} Opnieuw proberen</button></div>`);
    }
  }

  function pageHead(title, subtitle, actions = '') {
    return html`<div class="page-head"><div><h1>${title}</h1>${subtitle ? html`<p>${subtitle}</p>` : ''}</div>
      ${actions ? html`<div class="page-actions">${actions}</div>` : ''}</div>`;
  }

  const card = (title, iconName, body, extra = '') => html`<section class="card">
    <div class="card-head"><h2>${iconName ? icon(iconName) : ''}${title}</h2>${extra}</div>${body}</section>`;
  const emptyRow = (columns, text) => html`<tr><td colspan="${columns}"><div class="empty">${text}</div></td></tr>`;

  // =============================================================== dashboard

  async function pageDashboard(main, _, alive) {
    const load = async () => {
      const data = await api('/dashboard');
      if (!alive()) return;
      state.history = data.history;
      render(main, viewDashboard(data));
      drawChart($('#chart', main), data.history);
    };
    await load();
    every(15000, () => load().catch(() => {}));
  }

  function stat(label, value, sub, iconName, cls = '', extra = '') {
    return html`<div class="card stat"><div class="stat-label">${label}</div>
      <div class="stat-icon">${icon(iconName)}</div>
      <div class="stat-value ${cls}">${value}</div>${sub ? html`<div class="stat-sub">${sub}</div>` : ''}${extra}</div>`;
  }

  function viewDashboard(d) {
    const s = d.server;
    const c = d.counts || {};
    const memory = s.memoryUsed / s.memoryMax;
    const stats = [
      stat('Online', html`${num(s.online)}<small> / ${num(s.maxPlayers)}</small>`, `${num(c.seenToday)} vandaag gezien · ${num(c.newToday)} nieuw`, 'online'),
      stat('TPS', fixed(s.tps[0], 1), `5m ${fixed(s.tps[1])} · 15m ${fixed(s.tps[2])} · ${fixed(s.mspt)} ms/tick`, 'gauge', tpsClass(s.tps[0])),
      stat('Geheugen', bytes(s.memoryUsed), `van ${bytes(s.memoryMax)}`, 'memory', '',
        html`<div class="meter ${memory > .9 ? 'error' : memory > .75 ? 'warning' : ''}"><i style="width:${Math.min(100, Math.round(memory * 100))}%"></i></div>`),
      stat('Uptime', uptime(s.uptime), `sinds ${dateTime(Date.now() - s.uptime)}`, 'clock'),
      stat('Spelers', num(c.players), `${num(c.newWeek)} nieuw deze week`, 'players')
    ];
    if (c.totalText) stats.push(stat('Geld in omloop', amount(c.totalText), `${currency()} · bank ${amount(c.bankText)} · contant ${amount(c.cashText)}`, 'coin'));
    if (d.shops) stats.push(stat('Shops open', html`${num(d.shops.open)}<small> / ${num(d.shops.total)}</small>`, 'open voor kopers', 'shop'));
    if (c.bans !== undefined) stats.push(stat('Actieve bans', num(c.bans), c.bans === 1 ? 'speler verbannen' : 'spelers verbannen', 'ban'));

    const players = d.players.length ? d.players.map(p => html`<tr ${can(P.players) ? raw(`data-href="#/spelers/${esc(p.uuid)}" tabindex="0"`) : ''}>
        <td><div class="who">${avatar(p.name)}<span class="who-name">${p.name}</span>${rankBadge(p.rank)}
          ${p.afk ? html`<span class="badge warning">AFK</span>` : ''}${p.vanished ? html`<span class="badge info">Vanish</span>` : ''}</div></td>
        <td class="hide-sm">${p.world}</td>
        <td class="num">${num(p.ping)} ms</td>
      </tr>`) : emptyRow(3, 'Er is nu niemand online.');

    const weather = w => w.thundering ? 'onweer' : w.storm ? 'regen' : 'helder';
    const worlds = d.worlds.map(w => html`<tr>
        <td><b>${w.name}</b><div class="muted">${ENVIRONMENTS[w.environment] || w.environment}</div></td>
        <td class="num">${num(w.players)}</td>
        <td class="num hide-sm">${num(w.entities)}</td>
        <td class="num hide-sm">${num(w.chunks)}</td>
        <td class="nowrap">${w.environment === 'normal' ? html`<span class="status">${icon(isNight(w.time) ? 'moon' : 'sun')} ${mcClock(w.time)} · ${weather(w)}</span>` : html`<span class="muted">—</span>`}</td>
      </tr>`);

    return html`
      ${pageHead('Dashboard', `${s.software} ${s.minecraft} · PindaFramework ${s.plugin} · bijgewerkt ${clock(Date.now())}`,
        html`<button class="btn" data-action="reload">${icon('refresh')} Verversen</button>`)}
      <div class="grid stats">${stats}</div>
      ${card('Laatste 24 uur', 'trending', html`<div class="card-body"><div class="chart" id="chart"></div></div>`,
        html`<div class="legend"><span><i style="background:var(--primary)"></i>Spelers online</span><span><i style="background:var(--info)"></i>TPS</span></div>`)}
      <div class="grid two">
        ${card('Online spelers', 'online', html`<div class="table-wrap"><table>
          <thead><tr><th>Speler</th><th class="hide-sm">Wereld</th><th class="num">Ping</th></tr></thead>
          <tbody>${players}</tbody></table></div>`, html`<span class="count">${num(d.players.length)}</span>`)}
        ${card('Werelden', 'globe', html`<div class="table-wrap"><table>
          <thead><tr><th>Wereld</th><th class="num">Spelers</th><th class="num hide-sm">Entities</th><th class="num hide-sm">Chunks</th><th>Tijd en weer</th></tr></thead>
          <tbody>${worlds}</tbody></table></div>`)}
      </div>`;
  }

  /** Lijngrafiek van spelers online (links) en TPS (rechts, 0-20). */
  function drawChart(element, history) {
    if (!element) return;
    if (!history || history.length < 2) {
      render(element, html`<div class="empty">${icon('trending')}Nog te weinig gegevens. Elke minuut komt er een meetpunt bij.</div>`);
      return;
    }
    const width = element.clientWidth || 600;
    const height = element.clientHeight || 230;
    const pad = { left: 32, right: 30, top: 10, bottom: 24 };
    const first = history[0].time;
    const last = history[history.length - 1].time;
    const peak = Math.max(...history.map(h => h.online));
    // Vier nette stappen: 1, 2, 5, 10, 20, 25, 50, ...
    const raw4 = Math.max(1, (peak + 1) / 4);
    const magnitude = 10 ** Math.floor(Math.log10(raw4));
    const step = [1, 2, 2.5, 5, 10].map(f => f * magnitude).find(s => s >= raw4 && Number.isInteger(s)) || 10 * magnitude;
    const max = step * 4;
    const x = t => pad.left + (t - first) / Math.max(1, last - first) * (width - pad.left - pad.right);
    const y = v => pad.top + (1 - v / max) * (height - pad.top - pad.bottom);
    const yt = v => pad.top + (1 - Math.min(20, v) / 20) * (height - pad.top - pad.bottom);

    const online = history.map((h, i) => `${i ? 'L' : 'M'}${x(h.time).toFixed(1)},${y(h.online).toFixed(1)}`).join('');
    const area = `${online}L${x(last).toFixed(1)},${y(0).toFixed(1)}L${x(first).toFixed(1)},${y(0).toFixed(1)}Z`;
    const tps = history.map((h, i) => `${i ? 'L' : 'M'}${x(h.time).toFixed(1)},${yt(h.tps).toFixed(1)}`).join('');

    let grid = '';
    for (let i = 0; i <= 4; i++) {
      const value = max * i / 4;
      const gy = y(value).toFixed(1);
      grid += `<line class="grid-line" x1="${pad.left}" x2="${width - pad.right}" y1="${gy}" y2="${gy}"/>`;
      grid += `<text class="axis" x="${pad.left - 8}" y="${gy}" text-anchor="end" dominant-baseline="middle">${Math.round(value)}</text>`;
      grid += `<text class="axis" x="${width - pad.right + 8}" y="${gy}" dominant-baseline="middle">${Math.round(20 * i / 4)}</text>`;
    }
    const labels = [first, first + (last - first) / 2, last].map((t, i) =>
      `<text class="axis" x="${x(t).toFixed(1)}" y="${height - 4}" text-anchor="${['start', 'middle', 'end'][i]}">${clock(t)}</text>`).join('');

    element.innerHTML = `<svg viewBox="0 0 ${width} ${height}" role="img" aria-label="Spelers online en TPS van de laatste 24 uur">
      <defs><linearGradient id="area" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffc857" stop-opacity=".28"/><stop offset="1" stop-color="#ffc857" stop-opacity="0"/></linearGradient></defs>
      ${grid}${labels}
      <path d="${area}" fill="url(#area)"/>
      <path class="line-tps" d="${tps}"/>
      <path class="line-online" d="${online}"/>
      <line class="hover-line hidden" y1="${pad.top}" y2="${height - pad.bottom}"/>
      <circle class="hover-dot hidden" r="4" fill="#ffc857" stroke="#191612" stroke-width="2"/>
    </svg><div class="chart-tip hidden"></div>`;

    const line = $('.hover-line', element);
    const dot = $('.hover-dot', element);
    const tip = $('.chart-tip', element);
    const svg = $('svg', element);
    const hide = () => [line, dot, tip].forEach(e => e.classList.add('hidden'));
    svg.addEventListener('pointermove', event => {
      const box = svg.getBoundingClientRect();
      const px = (event.clientX - box.left) * (width / box.width);
      const t = first + (px - pad.left) / (width - pad.left - pad.right) * (last - first);
      let best = 0;
      history.forEach((h, i) => { if (Math.abs(h.time - t) < Math.abs(history[best].time - t)) best = i; });
      const h = history[best];
      const hx = x(h.time);
      line.setAttribute('x1', hx); line.setAttribute('x2', hx);
      dot.setAttribute('cx', hx); dot.setAttribute('cy', y(h.online));
      tip.innerHTML = `${clock(h.time)} · <b>${num(h.online)} online</b> · <span class="t">TPS ${fixed(h.tps)}</span>`;
      tip.style.left = `${hx / width * 100}%`;
      tip.style.top = `${y(h.online) / height * 100}%`;
      [line, dot, tip].forEach(e => e.classList.remove('hidden'));
    });
    svg.addEventListener('pointerleave', hide);
  }

  let resizeTimer;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => { const chart = $('#chart'); if (chart && state.history) drawChart(chart, state.history); }, 150);
  });

  // =============================================================== spelers

  async function pagePlayers(main, uuid, alive) {
    if (uuid) return pagePlayer(main, uuid, alive);
    let offset = 0;
    render(main, html`${pageHead('Spelers', `Iedereen die ooit op ${serverName()} is geweest.`)}
      <section class="card">
        <div class="card-head"><label class="search">${icon('search')}<input class="input" id="player-search" type="search" placeholder="Zoek op naam…" autocomplete="off" value="${state.playerQuery}"></label><span class="count" id="player-count"></span></div>
        <div id="player-list"><div class="page-loading"><div class="spinner"></div></div></div>
      </section>`);

    const load = async () => {
      const query = state.playerQuery;
      const data = await api(`/players?q=${encodeURIComponent(query)}&limit=50&offset=${offset}`);
      if (!alive() || query !== state.playerQuery) return;
      $('#player-count', main).textContent = `${num(data.total)} ${data.total === 1 ? 'speler' : 'spelers'}`;
      const rows = data.players.length ? data.players.map(p => html`<tr data-href="#/spelers/${p.uuid}" tabindex="0">
          <td>${who(p.name, null, null)}</td>
          <td>${rankBadge(p.rank)}</td>
          <td>${p.online ? html`<span class="status"><span class="dot on"></span>Online</span>` : html`<span class="status"><span class="dot"></span>${ago(p.lastSeen)}</span>`}</td>
          <td class="hide-sm muted">${date(p.firstJoin)}</td>
        </tr>`) : emptyRow(4, query ? `Geen spelers gevonden met "${query}".` : 'Nog geen spelers.');
      const end = Math.min(data.total, offset + data.players.length);
      render($('#player-list', main), html`<div class="table-wrap"><table>
          <thead><tr><th>Speler</th><th>Rang</th><th>Status</th><th class="hide-sm">Eerste keer</th></tr></thead>
          <tbody>${rows}</tbody></table></div>
        ${data.total > data.limit ? html`<div class="pager"><span>${num(data.players.length ? offset + 1 : 0)}–${num(end)} van ${num(data.total)}</span>
          <div class="btn-row"><button class="btn sm" data-page="-1" ${offset === 0 ? 'disabled' : ''}>Vorige</button>
          <button class="btn sm" data-page="1" ${end >= data.total ? 'disabled' : ''}>Volgende</button></div></div>` : ''}`);
      $$('[data-page]', main).forEach(button => button.addEventListener('click', () => {
        offset = Math.max(0, offset + Number(button.dataset.page) * data.limit);
        load().catch(error => toast(error.message, 'error'));
      }));
    };

    let timer;
    $('#player-search', main).addEventListener('input', event => {
      clearTimeout(timer);
      timer = setTimeout(() => {
        state.playerQuery = event.target.value.trim();
        offset = 0;
        load().catch(error => toast(error.message, 'error'));
      }, 220);
    });
    await load();
  }

  async function pagePlayer(main, uuid, alive) {
    const p = await api(`/players/${encodeURIComponent(uuid)}`);
    if (!alive()) return;
    const me = state.me;
    const live = p.live;
    const mod = p.moderation;
    const canPunish = mod && mod.canPunish;

    const badges = [
      live ? html`<span class="badge success"><span class="dot on"></span>Online</span>` : html`<span class="badge plain">Offline</span>`,
      live && live.afk ? html`<span class="badge warning">AFK</span>` : '',
      live && live.vanished ? html`<span class="badge info">Vanish</span>` : '',
      mod && mod.ban ? html`<span class="badge error">Verbannen</span>` : '',
      mod && mod.mute ? html`<span class="badge warning">Gemute</span>` : ''
    ];

    const punishBar = canPunish ? html`<div class="punish-bar">
        ${live ? html`<button class="btn warn" data-action="punish" data-type="kick">${icon('kick')} Kicken</button>` : ''}
        <button class="btn" data-action="punish" data-type="warn">${icon('warn')} Waarschuwen</button>
        ${mod.mute ? html`<button class="btn" data-action="revoke" data-type="mute">${icon('undo')} Unmute</button>`
          : html`<button class="btn warn" data-action="punish" data-type="mute">${icon('mute')} Muten</button>`}
        ${mod.ban ? html`<button class="btn" data-action="revoke" data-type="ban">${icon('undo')} Unban</button>`
          : html`<button class="btn danger" data-action="punish" data-type="ban">${icon('ban')} Bannen</button>`}
      </div>` : '';

    const sections = [];
    if (live) {
      sections.push(card('Nu online', 'online', html`<div class="card-body"><dl class="kv">
        <dt>Wereld</dt><dd>${live.world}</dd>
        <dt>Positie</dt><dd class="mono">${live.x}, ${live.y}, ${live.z}</dd>
        <dt>Spelmodus</dt><dd>${GAMEMODES[live.gamemode] || live.gamemode}${live.flying ? ' · vliegt' : ''}</dd>
        <dt>Gezondheid</dt><dd>${fixed(live.health / 2, 1)} ♥</dd>
        <dt>Honger</dt><dd>${num(live.food)} / 20</dd>
        <dt>Level</dt><dd>${num(live.level)}</dd>
        <dt>Ping</dt><dd>${num(live.ping)} ms</dd></dl></div>`));
    }
    if (p.rank) {
      const options = me.ranks.filter(r => me.rank && (me.rank.operator || r.weight < me.rank.weight));
      sections.push(card('Rang', 'crown', html`<div class="card-body">
        <p>Huidige rang: ${rankBadge(p.rank)}</p>
        ${p.canSetRank && options.length ? html`<form data-form="rank" class="row">
            <label class="field">Nieuwe rang<select name="rank">${options.map(r => html`<option value="${r.id}" ${r.id === p.rank.id ? 'selected' : ''}>${r.name}</option>`)}</select></label>
            <button class="btn primary">Opslaan</button></form>`
          : html`<p class="muted">${can(P.ranks) ? (p.uuid === me.uuid ? 'Je eigen rang pas je in-game aan met /rank.' : 'Deze speler heeft een gelijke of hogere rang dan jij.') : 'Je mag geen rangen aanpassen.'}</p>`}
      </div>`));
    }
    if (p.economy) {
      const e = p.economy;
      sections.push(card('Saldo', 'coin', html`<div class="card-body">
        <div class="balance"><div><span>Bank</span><b>${amount(e.bank)}</b></div><div><span>Contant</span><b>${amount(e.cash)}</b></div><div><span>Totaal</span><b>${amount(e.total)}</b></div></div>
        ${can(P.ecoEdit) ? html`<form data-form="economy">
          <div class="row">
            <div class="seg" role="radiogroup" aria-label="Actie">
              <label><input type="radio" name="action" value="give" checked><span>Geven</span></label>
              <label><input type="radio" name="action" value="take"><span>Afnemen</span></label>
              <label><input type="radio" name="action" value="set"><span>Instellen</span></label>
            </div>
            <div class="seg" role="radiogroup" aria-label="Rekening">
              <label><input type="radio" name="account" value="bank" checked><span>Bank</span></label>
              <label><input type="radio" name="account" value="cash"><span>Contant</span></label>
            </div>
          </div>
          <div class="row">
            <label class="field">${label('Bedrag')}<input class="input" name="amount" inputmode="decimal" placeholder="bijv. 250 of 1,5k" required></label>
            <label class="field">${label('Notitie', '(optioneel)')}<input class="input" name="note" maxlength="100" placeholder="Waarom?"></label>
            <button class="btn primary">Uitvoeren</button>
          </div></form>` : ''}
      </div>`, html`<span class="count">in ${currency()}</span>`));
    }
    if (p.skills) {
      const sk = p.skills;
      sections.push(card('Skills', 'award', html`<div class="card-body">
        <div class="skills-list">${sk.skills.map(x => html`<div class="skill-row">
          <div class="skill-top"><b>${x.name}</b><span><span class="muted">level</span> <b>${num(x.level)}</b></span></div>
          <div class="meter"><i style="width:${Math.round(x.progress * 100)}%"></i></div>
          <div class="skill-sub muted">${x.max ? `${num(x.xp)} XP · maximaal level` : `${num(x.xp)} / ${num(x.next)} XP`}</div></div>`)}</div>
        ${sk.canEdit ? html`<form data-form="skills" class="row" style="margin-top:16px">
          <label class="field">${label('Skill')}<select name="skill"><option value="">Alle skills (alleen resetten)</option>${sk.skills.map(x => html`<option value="${x.id}">${x.name}</option>`)}</select></label>
          <label class="field">${label('Actie')}<select name="action"><option value="level">Level instellen</option><option value="xp">XP geven</option><option value="reset">Resetten</option></select></label>
          <label class="field">${label('Waarde')}<input class="input" name="value" inputmode="numeric" placeholder="bijv. 50"></label>
          <button class="btn primary">Uitvoeren</button></form>` : ''}
      </div>`, html`<span class="count">totaal level ${num(sk.total)} / ${num(sk.maxTotal)}</span>`));
    }
    if (p.shop) {
      sections.push(card('Shop', 'shop', html`<div class="card-body"><dl class="kv">
        <dt>Naam</dt><dd>${p.shop.name}</dd>
        <dt>Status</dt><dd>${shopStatus(p.shop)}</dd>
        <dt>Te koop</dt><dd>${num(p.shop.forSale)} ${p.shop.forSale === 1 ? 'item' : 'items'}</dd>
        <dt>Verkopen</dt><dd>${num(p.shop.sales)}</dd>
        <dt>Verdiend</dt><dd>${p.shop.earned.text}</dd></dl>
        <p style="margin-top:14px"><a class="btn sm" href="#/shops/${p.uuid}">Shop bekijken</a></p></div>`));
    }

    let punishments = '';
    if (mod) {
      const active = [mod.ban, mod.mute].filter(Boolean);
      const rows = mod.history.length ? mod.history.map(x => html`<tr>
          <td>${typeBadge(x.type)}</td><td class="reason">${x.reason}</td><td class="nowrap">${x.actor}</td>
          <td class="nowrap muted">${dateTime(x.created)}</td><td class="nowrap hide-sm">${expiresText(x)}</td><td>${statusBadge(x.status)}</td></tr>`)
        : emptyRow(6, 'Geen straffen. Netjes!');
      punishments = card('Straffen', 'shield', html`
        ${active.length ? html`<div class="card-body">${active.map(a => html`<div class="alert ${a.type === 'ban' ? 'error' : 'warning'}" style="margin-top:0;margin-bottom:8px">
          <b>${a.type === 'ban' ? 'Verbannen' : 'Gemute'}</b> door ${a.actor} · ${a.reason} · ${a.expires === null ? 'permanent' : `tot ${expiresText(a)}`}</div>`)}</div>` : ''}
        <div class="table-wrap"><table><thead><tr><th>Type</th><th>Reden</th><th>Door</th><th>Datum</th><th class="hide-sm">Tot</th><th>Status</th></tr></thead>
        <tbody>${rows}</tbody></table></div>`, html`<span class="count">${num(mod.history.length)}</span>`);
    }

    let transactions = '';
    if (p.economy) {
      const rows = p.economy.log.length ? p.economy.log.map(t => html`<tr>
          <td class="nowrap muted">${dateTime(t.time)}</td><td>${ECO_TYPES[t.type] || t.type}</td>
          <td class="num ${amountClass(t.amount.cents)}">${t.amount.cents > 0 ? '+' : ''}${t.amount.text}</td>
          <td class="muted hide-sm">${t.note || ''}</td></tr>`) : emptyRow(4, 'Nog geen transacties.');
      transactions = card('Laatste transacties', 'list', html`<div class="table-wrap"><table>
        <thead><tr><th>Wanneer</th><th>Wat</th><th class="num">Bedrag</th><th class="hide-sm">Notitie</th></tr></thead><tbody>${rows}</tbody></table></div>`);
    }

    render(main, html`<a class="back" href="#/spelers">${icon('back')} Spelers</a>
      <div class="page-head"><div class="profile">${avatar(p.name, 'lg')}
        <div class="profile-main"><h1>${p.name} ${rankBadge(p.rank)}</h1>
          <div class="profile-meta">${badges}<span>Eerste keer ${date(p.firstJoin)}</span><span>${live ? 'Nu online' : `Laatst gezien ${ago(p.lastSeen)}`}</span>
          <button class="uuid" data-action="copy" data-text="${p.uuid}" title="Kopiëren">${p.uuid}</button></div></div></div>
        ${punishBar}</div>
      <div class="grid two">${sections}</div>
      ${punishments}
      ${transactions}`);
    main.dataset.uuid = p.uuid;
    main.dataset.name = p.name;
  }

  // =============================================================== straffen geven

  function openPunish(type) {
    const main = $('#main');
    const uuid = main.dataset.uuid;
    const name = main.dataset.name;
    const permanentAllowed = type === 'mute' || can(P.banPermanent);
    const max = state.me.maxTempBan;
    const titles = { ban: `${name} bannen`, mute: `${name} muten`, kick: `${name} kicken`, warn: `${name} waarschuwen` };
    const texts = {
      ban: 'De speler wordt meteen van de server gehaald en kan niet meer inloggen.',
      mute: 'De speler kan niet meer chatten of privéberichten sturen.',
      kick: 'De speler wordt van de server gehaald, maar kan direct terugkomen.',
      warn: 'De speler krijgt een waarschuwing in beeld. Die komt in zijn geschiedenis.'
    };
    const presets = [['30m', '30 minuten'], ['1u', '1 uur'], ['6u', '6 uur'], ['1d', '1 dag'], ['3d', '3 dagen'], ['7d', '7 dagen'], ['30d', '30 dagen']];
    const durationField = type === 'ban' || type === 'mute' ? html`<div class="row">
        <label class="field">${label('Duur')}<select name="preset">
          ${permanentAllowed ? html`<option value="permanent">Permanent</option>` : ''}
          ${presets.map(([value, label], i) => html`<option value="${value}" ${!permanentAllowed && i === 3 ? 'selected' : ''}>${label}</option>`)}
          <option value="custom">Anders…</option></select></label>
        <label class="field hidden" id="custom-duration">${label('Eigen duur')}<input class="input" name="custom" placeholder="bijv. 2d12u"></label></div>
        ${type === 'ban' && !permanentAllowed && max ? html`<p class="hint muted" style="margin-top:8px">Je mag maximaal ${human(max)} bannen.</p>` : ''}` : '';
    openModal(html`<form data-form="punish" data-type="${type}" data-uuid="${uuid}" data-name="${name}">
      <div class="modal-head"><h3>${titles[type]}</h3><p>${texts[type]}</p></div>
      <div class="modal-body">
        <label class="field">${label('Reden', type === 'warn' ? '' : '(optioneel)')}
          <textarea name="reason" maxlength="200" placeholder="Wat is er gebeurd?" ${type === 'warn' ? 'required' : ''} autofocus></textarea></label>
        ${durationField}
      </div>
      <div class="modal-foot"><button type="button" class="btn ghost" data-action="close">Annuleren</button>
        <button class="btn ${type === 'ban' ? 'solid-danger' : 'primary'}">${{ ban: 'Bannen', mute: 'Muten', kick: 'Kicken', warn: 'Waarschuwen' }[type]}</button></div>
    </form>`);
    const preset = $('select[name=preset]', modal);
    if (preset) preset.addEventListener('change', () => $('#custom-duration', modal).classList.toggle('hidden', preset.value !== 'custom'));
  }

  // =============================================================== economie

  async function pageEconomy(main, _, alive) {
    const data = await api('/economy?limit=60');
    if (!alive()) return;
    const t = data.totals;
    const top = data.top.length ? data.top.map((r, i) => html`<tr data-href="#/spelers/${r.uuid}" tabindex="0">
        <td class="muted num" style="width:1%">${i + 1}</td><td>${who(r.name, null, null)}</td>
        <td class="num hide-sm">${r.bank.text}</td><td class="num hide-sm">${r.cash.text}</td><td class="num"><b>${r.total.text}</b></td></tr>`)
      : emptyRow(5, 'Nog geen rekeningen.');
    const log = data.log.length ? data.log.map(r => html`<tr>
        <td class="nowrap muted">${dateTime(r.time)}</td><td>${who(r.name, r.uuid, null)}</td><td>${ECO_TYPES[r.type] || r.type}</td>
        <td class="num ${amountClass(r.amount.cents)}">${r.amount.cents > 0 ? '+' : ''}${r.amount.text}</td><td class="muted hide-sm">${r.note || ''}</td></tr>`)
      : emptyRow(5, 'Nog geen transacties.');
    render(main, html`${pageHead('Economie', 'PindaCredits op de server: wie heeft wat, en waar gaat het heen.')}
      <div class="grid stats">
        ${stat('In omloop', amount(t.total), `${currency()}, bank en contant samen`, 'coin')}
        ${stat('Op de bank', amount(t.bank), `${currency()}, veilig opgeslagen`, 'bank')}
        ${stat('Contant', amount(t.cash), `${currency()}, kwijt bij doodgaan`, 'coin')}
        ${stat('Rekeningen', num(t.accounts), 'spelers met saldo', 'players')}
      </div>
      ${card('Rijkste spelers', 'crown', html`<div class="table-wrap"><table>
        <thead><tr><th></th><th>Speler</th><th class="num hide-sm">Bank</th><th class="num hide-sm">Contant</th><th class="num">Totaal</th></tr></thead>
        <tbody>${top}</tbody></table></div>`)}
      ${card('Laatste transacties', 'list', html`<div class="table-wrap"><table>
        <thead><tr><th>Wanneer</th><th>Speler</th><th>Wat</th><th class="num">Bedrag</th><th class="hide-sm">Notitie</th></tr></thead>
        <tbody>${log}</tbody></table></div>`)}`);
  }

  // =============================================================== shops

  function shopStatus(shop) {
    if (shop.open) return html`<span class="badge success">Open</span>`;
    if (!shop.wantsOpen) return html`<span class="badge plain">Gesloten</span>`;
    if (!shop.ownerOnline) return html`<span class="badge plain">Eigenaar offline</span>`;
    if (!shop.forSale) return html`<span class="badge warning">Uitverkocht</span>`;
    if (!shop.feePaidToday) return html`<span class="badge warning">Fee niet betaald</span>`;
    return html`<span class="badge plain">Dicht</span>`;
  }

  async function pageShops(main, owner, alive) {
    if (owner) return pageShop(main, owner, alive);
    const data = await api('/shops');
    if (!alive()) return;
    const cards = data.shops.map(s => html`<article class="card shop-card" data-href="#/shops/${s.owner}" tabindex="0">
        <div class="shop-card-top"><div><h3>${s.name}</h3><div class="who" style="margin-top:6px">${avatar(s.ownerName)}<span class="muted">${s.ownerName}</span></div></div>${shopStatus(s)}</div>
        <div class="shop-stats"><div>Te koop<b>${num(s.forSale)}</b></div><div>Verkopen<b>${num(s.sales)}</b></div><div>Verdiend<b>${amount(s.earned)}</b></div></div>
      </article>`);
    render(main, html`${pageHead('Shops', `${num(data.shops.length)} ${data.shops.length === 1 ? 'shop' : 'shops'} · marketplace fee ${data.dailyFee.text} per dag · ${fixed(data.tax, 1)}% belasting per verkoop`)}
      ${data.shops.length ? html`<div class="shop-cards">${cards}</div>` : html`<div class="card"><div class="empty">${icon('shop')}Nog niemand heeft een shop. Spelers maken er een met /shop.</div></div>`}`);
  }

  async function pageShop(main, owner, alive) {
    const s = await api(`/shops/${encodeURIComponent(owner)}`);
    if (!alive()) return;
    const items = s.items.length ? html`<div class="items">${s.items.map(item => html`<div class="item ${item.forSale ? '' : 'off'}">
        <div class="item-name ${item.enchanted ? 'ench' : ''}" title="${item.name}">${item.name}</div>
        <div class="item-mat">${item.material}</div>
        <div class="item-price">${item.price ? html`${amount(item.price)} <small>per stuk</small>` : html`<small>nog geen prijs</small>`}</div>
        <div class="item-stock">${num(item.stock)} op voorraad</div></div>`)}</div>`
      : html`<div class="empty">${icon('box')}Deze shop is leeg.</div>`;
    render(main, html`<a class="back" href="#/shops">${icon('back')} Shops</a>
      ${pageHead(html`${s.name} ${shopStatus(s)}`, html`Van ${can(P.players) ? html`<a href="#/spelers/${s.owner}">${s.ownerName}</a>` : s.ownerName} · sinds ${date(s.created)}`,
        can(P.shopsManage) && s.wantsOpen ? html`<button class="btn danger" data-action="close-shop" data-owner="${s.owner}">${icon('lock')} Shop sluiten</button>` : '')}
      <div class="grid stats">
        ${stat('Te koop', num(s.forSale), `${num(s.slots)} van ${num(s.maxSlots)} vakken gebruikt`, 'box')}
        ${stat('Verkopen', num(s.sales), 'sinds de start', 'trending')}
        ${stat('Verdiend', amount(s.earned), `${currency()}, na belasting`, 'coin')}
        ${stat('Shopbord', s.sign ? 'Geplaatst' : 'Geen', s.sign ? `${s.sign.world} · ${s.sign.x}, ${s.sign.y}, ${s.sign.z}` : 'nog niet geplaatst', 'sign')}
      </div>
      ${card('Aanbod', 'shop', html`<div class="card-body">${items}</div>`, html`<span class="count">${num(s.items.length)}</span>`)}`);
  }

  // =============================================================== skills

  async function pageSkills(main, _, alive) {
    const data = await api('/skills');
    if (!alive()) return;
    const tabs = [{ id: 'total', name: 'Totaal level' }, ...data.skills];
    const boost = data.boost;
    const boostCard = boost
      ? html`<div class="card-body"><div class="alert warning" style="margin-top:0"><b>${fixed(boost.multiplier, 1)}x XP</b> voor iedereen, nog ${human(boost.until - Date.now())} <span class="muted">(gestart door ${boost.by})</span></div>
          ${can(P.skillsEdit) ? html`<p style="margin-top:12px"><button class="btn danger sm" data-action="boost-stop">${icon('x')} Boost stoppen</button></p>` : ''}</div>`
      : html`<div class="card-body"><p class="muted">Er loopt nu geen XP-boost. Met een boost krijgt iedereen tijdelijk meer XP, handig voor een event.</p>
          ${can(P.skillsEdit) ? html`<form data-form="boost" class="row">
            <label class="field">${label('Vermenigvuldiger')}<select name="multiplier"><option value="1.5">1,5x</option><option value="2" selected>2x</option><option value="3">3x</option><option value="5">5x</option></select></label>
            <label class="field">${label('Duur', '(bijv. 30m, 2u, 1d)')}<input class="input" name="duration" value="1u" required></label>
            <button class="btn primary">${icon('zap')} Boost starten</button></form>` : ''}</div>`;
    render(main, html`${pageHead('Skills', `Level 0 tot ${data.maxLevel} in ${data.skills.length} skills. Bij elke level-up krijgen spelers contant geld.`)}
      <div class="grid two">
        ${card('XP-boost', 'zap', boostCard)}
        ${card('Wat kost een level?', 'trending', html`<div class="table-wrap"><table><thead><tr><th>Level</th><th class="num">Totale XP</th></tr></thead>
          <tbody>${data.curve.map(c => html`<tr><td>Level ${c.level}</td><td class="num">${num(c.xp)}</td></tr>`)}</tbody></table></div>`)}
      </div>
      <section class="card">
        <div class="card-head"><h2>${icon('crown')}Ranglijst</h2>
          <div class="seg" role="tablist">${tabs.map((t, i) => html`<label><input type="radio" name="skill-tab" value="${t.id}" ${i === 0 ? 'checked' : ''}><span>${t.name}</span></label>`)}</div></div>
        <div id="skill-top"></div>
      </section>`);
    const show = id => {
      const rows = data.top[id] || [];
      render($('#skill-top', main), html`<div class="table-wrap"><table>
        <thead><tr><th style="width:1%">#</th><th>Speler</th><th class="num">${id === 'total' ? 'Totaal level' : 'Level'}</th><th class="num">XP</th></tr></thead>
        <tbody>${rows.length ? rows.map(r => html`<tr ${can(P.players) ? raw(`data-href="#/spelers/${esc(r.uuid)}" tabindex="0"`) : ''}>
          <td class="muted num">${r.position}</td><td>${who(r.name, null, null)}</td><td class="num"><b>${num(r.level)}</b></td><td class="num muted">${num(r.xp)}</td></tr>`)
          : emptyRow(4, 'Nog niemand op deze ranglijst.')}</tbody></table></div>`);
    };
    $$('input[name=skill-tab]', main).forEach(input => input.addEventListener('change', () => show(input.value)));
    show('total');
  }

  // =============================================================== straffen

  async function pagePunishments(main, _, alive) {
    const data = await api('/punishments?limit=100');
    if (!alive()) return;
    const canRevoke = can(P.moderate);
    const active = data.active.length ? data.active.map(x => html`<tr>
        <td>${who(x.name, x.uuid, null)}</td><td>${typeBadge(x.type)}</td><td class="reason">${x.reason}</td><td class="nowrap">${x.actor}</td>
        <td class="nowrap hide-sm muted">${dateTime(x.created)}</td><td class="nowrap">${expiresText(x)}</td>
        <td class="actions">${canRevoke ? html`<button class="btn sm" data-action="revoke" data-type="${x.type}" data-uuid="${x.uuid}" data-name="${x.name}">${x.type === 'ban' ? 'Unban' : 'Unmute'}</button>` : ''}</td></tr>`)
      : emptyRow(7, 'Er zijn geen actieve bans of mutes.');
    const recent = data.recent.length ? data.recent.map(x => html`<tr>
        <td>${who(x.name, x.uuid, null)}</td><td>${typeBadge(x.type)}</td><td class="reason">${x.reason}</td><td class="nowrap">${x.actor}</td>
        <td class="nowrap muted">${dateTime(x.created)}</td><td>${statusBadge(x.status)}</td></tr>`)
      : emptyRow(6, 'Nog geen straffen uitgedeeld.');
    render(main, html`${pageHead('Straffen', 'Bans, mutes, kicks en waarschuwingen. Straffen geven doe je op het profiel van een speler.')}
      ${card('Nu actief', 'shield', html`<div class="table-wrap"><table>
        <thead><tr><th>Speler</th><th>Type</th><th>Reden</th><th>Door</th><th class="hide-sm">Sinds</th><th>Tot</th><th></th></tr></thead>
        <tbody>${active}</tbody></table></div>`, html`<span class="count">${num(data.active.length)}</span>`)}
      ${card('Recent', 'list', html`<div class="table-wrap"><table>
        <thead><tr><th>Speler</th><th>Type</th><th>Reden</th><th>Door</th><th>Datum</th><th>Status</th></tr></thead>
        <tbody>${recent}</tbody></table></div>`)}`);
  }

  // =============================================================== server

  async function pageServer(main, _, alive) {
    const data = await api('/server');
    if (!alive()) return;
    const worldOptions = html`<option value="">Alle normale werelden</option>${data.worlds.map(w => html`<option value="${w.name}">${w.name} (${ENVIRONMENTS[w.environment] || w.environment})</option>`)}`;
    const times = [['sunrise', 'Zonsopkomst', 'sunrise'], ['day', 'Dag', 'sun'], ['noon', 'Middag', 'sun'], ['sunset', 'Zonsondergang', 'sunset'], ['night', 'Nacht', 'moon'], ['midnight', 'Middernacht', 'stars']];
    const weathers = [['clear', 'Helder', 'clear'], ['rain', 'Regen', 'rain'], ['thunder', 'Onweer', 'bolt']];
    const wl = data.whitelist;
    render(main, html`${pageHead('Server', 'Tijd, weer, mededelingen, whitelist en onderhoud.')}
      <div class="grid two">
        ${card('Tijd', 'clock', html`<div class="card-body">
          <label class="field">Wereld<select id="time-world">${worldOptions}</select></label>
          <div class="btn-row" style="margin-top:14px">${times.map(([value, label, i]) => html`<button class="btn" data-action="time" data-value="${value}">${icon(i)} ${label}</button>`)}</div>
          <p class="muted" style="margin-top:12px">Nu: ${data.worlds.filter(w => w.environment === 'normal').map(w => `${w.name} ${mcClock(w.time)}`).join(' · ') || '—'}</p></div>`)}
        ${card('Weer', 'rain', html`<div class="card-body">
          <label class="field">Wereld<select id="weather-world">${worldOptions}</select></label>
          <div class="btn-row" style="margin-top:14px">${weathers.map(([value, label, i]) => html`<button class="btn" data-action="weather" data-value="${value}">${icon(i)} ${label}</button>`)}</div>
          <p class="muted" style="margin-top:12px">Nu: ${data.worlds.filter(w => w.environment === 'normal').map(w => `${w.name} ${w.thundering ? 'onweer' : w.storm ? 'regen' : 'helder'}`).join(' · ') || '—'}</p></div>`)}
        ${card('Mededeling', 'megaphone', html`<form class="card-body" data-form="broadcast">
          <label class="field">Bericht aan alle spelers<textarea name="message" maxlength="256" required placeholder="Bijvoorbeeld: over 10 minuten herstart de server!"></textarea></label>
          <div class="row" style="justify-content:space-between;align-items:center">
            <label class="check"><input type="checkbox" name="title"> Ook groot in beeld (titel)</label>
            <button class="btn primary">${icon('send')} Versturen</button></div></form>`)}
        ${card('Whitelist', 'list', html`<div class="card-body">
          <div class="row" style="justify-content:space-between;align-items:center;margin-bottom:14px">
            <span>Whitelist staat ${wl.enabled ? html`<span class="badge success">aan</span>` : html`<span class="badge plain">uit</span>`}</span>
            <button class="btn sm" data-action="whitelist-toggle" data-enabled="${wl.enabled ? 1 : 0}">${wl.enabled ? 'Uitzetten' : 'Aanzetten'}</button></div>
          <form class="row" data-form="whitelist-add"><label class="field">${label('Speler toevoegen')}<input class="input" name="name" placeholder="Minecraft-naam" pattern="[A-Za-z0-9_]{1,16}" required></label>
            <button class="btn">${icon('plus')} Toevoegen</button></form>
          <div class="chips" style="margin-top:14px">${wl.players.length ? wl.players.map(n => html`<span class="badge plain">${n}
            <button class="btn ghost sm icon-only" style="padding:0" data-action="whitelist-remove" data-name="${n}" aria-label="${n} verwijderen">${icon('x')}</button></span>`)
              : html`<span class="muted">Er staat nog niemand op de whitelist.</span>`}</div></div>`)}
        ${card('Onderhoud', 'save', html`<div class="card-body">
          <div class="btn-row">
            <button class="btn" data-action="save">${icon('save')} Alles opslaan</button>
            <button class="btn" data-action="plugin-reload">${icon('reload')} PindaFramework herladen</button>
          </div>
          <p class="muted" style="margin-top:12px">Opslaan schrijft werelden en spelers weg. Herladen leest alle configs en taalbestanden opnieuw in (zoals /pinda reload).</p>
          ${can(P.stop) ? html`<div class="alert error" style="margin-top:16px"><b>Server stoppen</b><br>Iedereen wordt eraf gehaald. Draait de server als service met automatisch herstarten, dan is dit een herstart.
            <div style="margin-top:10px"><button class="btn solid-danger sm" data-action="stop">${icon('power')} Server stoppen</button></div></div>` : ''}
        </div>`)}
        ${card('Instellingen', 'server', html`<div class="card-body"><dl class="kv">
          <dt>Maximaal aantal spelers</dt><dd>${num(data.maxPlayers)}</dd>
          <dt>View distance</dt><dd>${num(data.viewDistance)} chunks</dd>
          <dt>Simulation distance</dt><dd>${num(data.simulationDistance)} chunks</dd>
          <dt>Werelden</dt><dd>${num(data.worlds.length)}</dd></dl>
          <p class="muted" style="margin-top:12px">Deze waarden pas je aan in server.properties.</p></div>`)}
      </div>`);
  }

  // =============================================================== console

  async function pageConsole(main, _, alive) {
    render(main, html`${pageHead('Console', 'Live meelezen met de server. Commando’s worden uitgevoerd als de console.')}
      <div class="console" id="console" role="log" aria-live="off"></div>
      <form class="console-form" data-form="console"><div class="prompt"><input class="input" name="command" autocomplete="off" spellcheck="false" placeholder="bijv. say Hallo allemaal of time set day"></div>
        <button class="btn primary">${icon('send')} Uitvoeren</button></form>`);
    const box = $('#console', main);
    let offset = -1;
    const load = async () => {
      const data = await api(`/console?from=${offset}`);
      if (!alive()) return;
      const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 60;
      const lines = data.lines.map(line => {
        const cls = /\b(ERROR|SEVERE)\b/.test(line) ? 'err' : /\bWARN(ING)?\b/.test(line) ? 'warn' : /issued server command|\[Paneel\]/.test(line) ? 'cmd' : '';
        return `<div${cls ? ` class="${cls}"` : ''}>${esc(line) || ' '}</div>`;
      }).join('');
      if (data.reset) box.innerHTML = lines; else if (lines) box.insertAdjacentHTML('beforeend', lines);
      while (box.childElementCount > 1500) box.firstElementChild.remove();
      offset = data.offset;
      if (data.reset || nearBottom) box.scrollTop = box.scrollHeight;
    };
    state.consoleHistory = state.consoleHistory || [];
    let index = -1;
    const input = $('input[name=command]', main);
    input.addEventListener('keydown', event => {
      const history = state.consoleHistory;
      if (event.key === 'ArrowUp' && history.length) {
        index = Math.min(history.length - 1, index + 1);
        input.value = history[history.length - 1 - index];
        event.preventDefault();
      } else if (event.key === 'ArrowDown') {
        index = Math.max(-1, index - 1);
        input.value = index < 0 ? '' : history[history.length - 1 - index];
        event.preventDefault();
      }
    });
    state.consoleReload = () => { index = -1; return load(); };
    await load();
    input.focus();
    every(2000, () => load().catch(() => {}));
  }

  // =============================================================== logboek

  async function pageLog(main, _, alive) {
    let offset = 0;
    const data = await api('/log?limit=100');
    if (!alive()) return;
    const sessions = data.sessions.map(s => html`<tr>
        <td>${who(s.name, s.uuid, null)}</td><td class="mono">${s.ip}</td><td class="nowrap muted">${dateTime(s.created)}</td>
        <td class="nowrap">${ago(s.lastSeen)}</td><td>${s.current ? html`<span class="badge success">Dit apparaat</span>` : ''}</td></tr>`);
    const entryRows = entries => entries.map(e => html`<tr>
        <td class="nowrap muted">${dateTime(e.time)}</td><td>${who(e.name, e.uuid, null)}</td><td><b>${e.action}</b></td>
        <td>${e.target || ''}</td><td class="reason muted">${e.details || ''}</td><td class="mono muted hide-sm">${e.ip || ''}</td></tr>`);
    render(main, html`${pageHead('Logboek', 'Alles wat er via het paneel is gedaan. Acties staan ook in de serverconsole.')}
      ${card('Ingelogd op het paneel', 'lock', html`<div class="table-wrap"><table>
        <thead><tr><th>Wie</th><th>IP</th><th>Ingelogd</th><th>Laatst actief</th><th></th></tr></thead>
        <tbody>${sessions}</tbody></table></div>`, html`<span class="count">${num(data.sessions.length)}</span>`)}
      ${card('Acties', 'log', html`<div class="table-wrap"><table>
        <thead><tr><th>Wanneer</th><th>Wie</th><th>Actie</th><th>Doel</th><th>Details</th><th class="hide-sm">IP</th></tr></thead>
        <tbody id="log-rows">${data.entries.length ? entryRows(data.entries) : emptyRow(6, 'Nog geen acties.')}</tbody></table></div>
        ${data.entries.length === 100 ? html`<div class="pager"><span></span><button class="btn sm" id="log-more">Meer laden</button></div>` : ''}`)}`);
    const more = $('#log-more', main);
    if (more) {
      more.addEventListener('click', () => busy(more, async () => {
        offset += 100;
        const next = await api(`/log?limit=100&offset=${offset}`);
        $('#log-rows', main).insertAdjacentHTML('beforeend', part(entryRows(next.entries)));
        if (next.entries.length < 100) more.parentElement.remove();
      }));
    }
  }

  // =============================================================== acties (knoppen)

  const actions = {
    async 'boost-stop'(button) {
      const result = await busy(button, () => post('/skills/boost', { stop: true }));
      if (result) { toast('De XP-boost is gestopt.'); navigate(); }
    },

    reload: () => navigate(),
    close: () => closeModal(),
    'open-nav': () => document.body.classList.add('nav-open'),
    'close-nav': () => document.body.classList.remove('nav-open'),

    async logout(button) {
      await busy(button, () => post('/logout'));
      state.me = null;
      clearTimers();
      renderLogin('Je bent uitgelogd.', false, 'info');
    },

    copy(button) {
      const text = button.dataset.text;
      (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject()).then(
        () => toast('Gekopieerd.'), () => toast('Kopiëren lukte niet.', 'error'));
    },

    punish(button) {
      openPunish(button.dataset.type);
    },

    async revoke(button) {
      const main = $('#main');
      const uuid = button.dataset.uuid || main.dataset.uuid;
      const name = button.dataset.name || main.dataset.name;
      const ban = button.dataset.type === 'ban';
      const ok = await confirmDialog({
        title: ban ? `Ban van ${name} opheffen?` : `Mute van ${name} opheffen?`,
        text: ban ? `${name} kan daarna meteen weer inloggen.` : `${name} kan daarna meteen weer chatten.`,
        confirm: ban ? 'Unban' : 'Unmute'
      });
      if (!ok) return;
      const result = await busy(button, () => post(`/players/${uuid}/revoke`, { type: button.dataset.type }));
      if (result) { toast(ban ? `De ban van ${name} is opgeheven.` : `De mute van ${name} is opgeheven.`); navigate(); }
    },

    async 'close-shop'(button) {
      const ok = await confirmDialog({ title: 'Shop sluiten?', text: 'De eigenaar krijgt een melding en kan de shop zelf weer openen.', confirm: 'Sluiten', danger: true });
      if (!ok) return;
      const result = await busy(button, () => post(`/shops/${button.dataset.owner}/close`));
      if (result) { toast('De shop is gesloten.'); navigate(); }
    },

    async time(button) {
      const result = await busy(button, () => post('/server/time', { value: button.dataset.value, world: $('#time-world').value || null }));
      if (result) toast(`Tijd aangepast in ${result.worlds || 'geen werelden'}.`);
    },

    async weather(button) {
      const result = await busy(button, () => post('/server/weather', { value: button.dataset.value, world: $('#weather-world').value || null }));
      if (result) toast(`Weer aangepast in ${result.worlds || 'geen werelden'}.`);
    },

    async 'whitelist-toggle'(button) {
      const enable = button.dataset.enabled !== '1';
      const result = await busy(button, () => post('/server/whitelist', { action: enable ? 'enable' : 'disable' }));
      if (result) { toast(enable ? 'De whitelist staat aan.' : 'De whitelist staat uit.'); navigate(); }
    },

    async 'whitelist-remove'(button) {
      const result = await busy(button, () => post('/server/whitelist', { action: 'remove', name: button.dataset.name }));
      if (result) { toast(`${button.dataset.name} staat niet meer op de whitelist.`); navigate(); }
    },

    async save(button) {
      const result = await busy(button, () => post('/server/save'));
      if (result) toast('Werelden en spelers zijn opgeslagen.');
    },

    async 'plugin-reload'(button) {
      const result = await busy(button, () => post('/server/reload'));
      if (result) toast(`PindaFramework is herladen in ${num(result.time)} ms.`);
    },

    async stop(button) {
      const ok = await confirmDialog({
        title: 'Server stoppen?',
        text: 'Alle spelers worden eraf gehaald en de server gaat uit. Zonder automatische herstart moet je hem zelf weer starten op de VPS of laptop.',
        confirm: 'Ja, stoppen', danger: true
      });
      if (!ok) return;
      const result = await busy(button, () => post('/server/stop'));
      if (result) toast('De server stopt over een paar seconden.');
    }
  };

  // =============================================================== formulieren

  const forms = {
    async skills(form, data, button) {
      const uuid = $('#main').dataset.uuid;
      const action = data.get('action');
      const skill = data.get('skill');
      if (action !== 'reset' && !skill) { toast('Kies een skill.', 'error'); return; }
      if (action === 'reset') {
        const ok = await confirmDialog({ title: 'Skills resetten?', text: skill ? 'De XP van deze skill gaat terug naar 0.' : 'Alle skills van deze speler gaan terug naar 0.', confirm: 'Resetten', danger: true });
        if (!ok) return;
      }
      const result = await busy(button, () => post(`/players/${uuid}/skills`, { action, skill: skill || null, value: data.get('value') || '0' }));
      if (result) { toast('Skills bijgewerkt.'); navigate(); }
    },

    async boost(form, data, button) {
      const result = await busy(button, () => post('/skills/boost', { multiplier: data.get('multiplier'), duration: data.get('duration') }));
      if (result) { toast('De XP-boost is gestart.'); navigate(); }
    },

    async punish(form, data, button) {
      const type = form.dataset.type;
      const preset = data.get('preset');
      const duration = preset === 'custom' ? (data.get('custom') || '').trim() : preset;
      if (preset === 'custom' && !duration) { toast('Vul een duur in, bijvoorbeeld 2d12u.', 'error'); return; }
      const result = await busy(button, () => post(`/players/${form.dataset.uuid}/punish`, {
        type, reason: (data.get('reason') || '').trim() || null, duration: duration || null
      }));
      if (!result) return;
      const name = form.dataset.name;
      toast({ ban: `${name} is verbannen.`, mute: `${name} is gemute.`, kick: `${name} is gekickt.`, warn: `${name} is gewaarschuwd.` }[type]);
      closeModal();
      navigate();
    },

    async rank(form, data, button) {
      const uuid = $('#main').dataset.uuid;
      const result = await busy(button, () => post(`/players/${uuid}/rank`, { rank: data.get('rank') }));
      if (result) { toast(`${$('#main').dataset.name} is nu ${result.rank.name}.`); navigate(); }
    },

    async economy(form, data, button) {
      const uuid = $('#main').dataset.uuid;
      const result = await busy(button, () => post(`/players/${uuid}/economy`, {
        action: data.get('action'), account: data.get('account'), amount: data.get('amount'), note: data.get('note') || null
      }));
      if (result) { toast(`Saldo bijgewerkt. Totaal nu ${result.total.text}.`); navigate(); }
    },

    async broadcast(form, data, button) {
      const result = await busy(button, () => post('/server/broadcast', { message: data.get('message'), title: data.get('title') === 'on' }));
      if (result) { toast('Mededeling verstuurd.'); form.reset(); }
    },

    async 'whitelist-add'(form, data, button) {
      const name = (data.get('name') || '').trim();
      const result = await busy(button, () => post('/server/whitelist', { action: 'add', name }));
      if (result) { toast(`${name} staat op de whitelist.`); navigate(); }
    },

    async console(form, data, button) {
      const command = (data.get('command') || '').trim();
      if (!command) return;
      const result = await busy(button, () => post('/console', { command }));
      if (!result) return;
      const history = state.consoleHistory;
      if (history[history.length - 1] !== command) history.push(command);
      if (history.length > 50) history.shift();
      form.reset();
      if (!result.known) toast('Dat commando kent de server niet.', 'error');
      setTimeout(() => state.consoleReload && state.consoleReload().catch(() => {}), 300);
    }
  };

  // =============================================================== events

  document.addEventListener('click', event => {
    const trigger = event.target.closest('[data-action]');
    if (trigger) {
      const action = actions[trigger.dataset.action];
      if (action) {
        event.preventDefault();
        action(trigger, event);
      }
      return;
    }
    const link = event.target.closest('[data-href]');
    if (link && !event.target.closest('a, button, input, select, textarea, label')) {
      location.hash = link.dataset.href;
    }
  });

  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') document.body.classList.remove('nav-open');
    if (event.key === 'Enter' && event.target.matches && event.target.matches('[data-href]')) location.hash = event.target.dataset.href;
  });

  document.addEventListener('submit', event => {
    const form = event.target.closest('form[data-form]');
    if (!form) return;
    event.preventDefault();
    const handler = forms[form.dataset.form];
    if (handler) handler(form, new FormData(form), event.submitter || $('button:not([type=button])', form));
  });

  // Plaatje van een speler niet te laden (geen internet)? Dan een grijs vlakje.
  document.addEventListener('error', event => {
    const image = event.target;
    if (image && image.tagName === 'IMG' && image.classList.contains('avatar') && image.src !== PLACEHOLDER) image.src = PLACEHOLDER;
  }, true);

  // =============================================================== starten

  /** Inloggen met de code uit de link (#login=...). Werkt ook als het paneel al open stond. */
  async function loginFromLink() {
    const match = location.hash.match(/login=([A-Za-z0-9_-]+)/);
    if (!match) return false;
    // De link meteen uit de adresbalk en geschiedenis halen.
    history.replaceState(null, '', `${location.pathname}#/dashboard`);
    clearTimers();
    closeModal();
    state.me = null;
    renderLogin(null, true);
    try {
      state.me = await post('/login', { token: match[1] });
    } catch (error) {
      renderLogin(error.message);
      return true;
    }
    renderShell();
    navigate();
    return true;
  }

  window.addEventListener('hashchange', async () => {
    if (!(await loginFromLink())) navigate();
  });

  async function boot() {
    try {
      const response = await fetch('/api/info', { credentials: 'same-origin' });
      if (response.ok) state.info = await response.json();
    } catch { /* offline */ }
    if (state.info) document.title = `${state.info.server} · Paneel`;

    if (await loginFromLink()) return;
    try {
      state.me = await api('/me');
    } catch (error) {
      renderLogin(error.status === 401 ? null : error.message);
      return;
    }
    renderShell();
    navigate();
  }

  boot();
})();
