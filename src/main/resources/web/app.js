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
    zap: '<path d="M13 2L4 14h7l-1 8 9-12h-7z"/>',
    cog: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
    type: '<path d="M4 7V4h16v3M9 20h6M12 4v16"/>',
    chat: '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>',
    trophy: '<path d="M8 21h8M12 17v4M7 4h10v5a5 5 0 0 1-10 0z"/><path d="M17 5h3v2a3 3 0 0 1-3 3M7 5H4v2a3 3 0 0 0 3 3"/>',
    sword: '<path d="M14.5 17.5L3 6V3h3l11.5 11.5M13 19l6-6M16 16l4 4M19 21l2-2"/>',
    ghost: '<path d="M9 10h.01M15 10h.01M12 2a8 8 0 0 0-8 8v12l3-3 2.5 2.5L12 19l2.5 2.5L17 19l3 3V10a8 8 0 0 0-8-8z"/>',
    tree: '<path d="M12 22v-6M9 22h6"/><path d="M12 2l5 7h-2.5l3.5 5h-3l3 4H6l3-4H6l3.5-5H7z"/>',
    skull: '<circle cx="9" cy="12" r="1"/><circle cx="15" cy="12" r="1"/><path d="M8 20v2h8v-2M12.5 17l-.5-1-.5 1z"/><path d="M16 20a2 2 0 0 0 1.56-3.25 8 8 0 1 0-11.12 0A2 2 0 0 0 8 20"/>'
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
    skills: 'pinda.panel.skills', skillsEdit: 'pinda.panel.skills.edit', config: 'pinda.panel.config',
    texts: 'pinda.panel.texts', ranksEdit: 'pinda.panel.ranks.edit', playersManage: 'pinda.panel.players.manage',
    security: 'pinda.panel.security', broadcasts: 'pinda.panel.broadcasts'
  };

  const state = { me: null, info: null, timers: [], renderId: 0, history: null, playerQuery: '' };
  const can = permission => Array.isArray(permission) ? permission.some(p => can(p)) : !!state.me && state.me.permissions.includes(permission);
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

  /** Stap 2 van het inloggen: de code uit de authenticator-app (de eerste keer met QR-code). */
  function renderTwoFactor(challenge, message) {
    document.body.classList.remove('nav-open');
    state.challenge = challenge;
    const name = serverName();
    const setup = challenge.step === 'setup';
    render($('#app'), html`<div class="login"><div class="login-card">
      <img class="login-logo" src="/favicon.svg" alt="">
      <h1>${setup ? 'Beveilig je account' : 'Tweestapsverificatie'}</h1>
      ${setup ? html`<p class="lead">Koppel eenmalig een authenticator-app, zoals Google Authenticator, Microsoft Authenticator, Authy of Bitwarden.</p>
        <div class="qr-wrap"><img class="qr" src="${challenge.qr}" alt="QR-code voor je authenticator-app" width="208" height="208"></div>
        <ol class="login-steps">
          <li><b>1</b><span>Open je authenticator-app en kies <em>account toevoegen</em> of <em>QR-code scannen</em>.</span></li>
          <li><b>2</b><span>Scan de QR-code. Je ziet daarna <span class="kbd">${challenge.issuer}</span> met een code van 6 cijfers.</span></li>
          <li><b>3</b><span>Vul die code hieronder in.</span></li>
        </ol>
        <details class="manual"><summary>Scannen lukt niet?</summary><p class="muted">Vul in je app deze sleutel handmatig in (type: op tijd gebaseerd):</p><p class="secret mono">${challenge.secret}</p></details>`
        : html`<p class="lead">Open je authenticator-app en vul de code van 6 cijfers voor <span class="kbd">${name}</span> in.</p>`}
      <form data-form="two-factor" class="code-form">
        <input class="input code-input" name="code" inputmode="numeric" autocomplete="one-time-code" maxlength="7" placeholder="000000" aria-label="Code van 6 cijfers" required>
        <button class="btn primary">${setup ? 'Koppelen en inloggen' : 'Inloggen'}</button>
      </form>
      ${message ? html`<div class="alert error">${message}</div>` : ''}
      <p class="login-foot">Telefoon kwijt? Vraag een admin om je 2FA te resetten (/panel 2fa reset).</p>
    </div></div>`);
    const input = $('.code-input');
    input.focus();
    input.addEventListener('input', () => {
      const digits = input.value.replace(/\D/g, '');
      if (digits.length === 6 && !state.verifying) input.form.requestSubmit();
    });
  }

  // =============================================================== opbouw en navigatie

  const ROUTES = [
    { path: 'dashboard', title: 'Dashboard', icon: 'dashboard', perm: P.use, page: pageDashboard },
    { path: 'spelers', title: 'Spelers', icon: 'players', perm: P.players, page: pagePlayers },
    { path: 'economie', title: 'Economie', icon: 'coin', perm: P.ecoView, feature: 'economy', page: pageEconomy },
    { path: 'shops', title: 'Shops', icon: 'shop', perm: P.shops, feature: 'shop', page: pageShops },
    { path: 'skills', title: 'Skills', icon: 'award', perm: P.skills, feature: 'skills', page: pageSkills },
    { path: 'toplijsten', title: 'Toplijsten', icon: 'trophy', perm: P.players, feature: 'leaderboards', page: pageLeaderboards },
    { path: 'straffen', title: 'Straffen', icon: 'shield', perm: P.players, feature: 'moderation', page: pagePunishments },
    { path: 'server', title: 'Server', icon: 'server', perm: [P.server, P.config], page: pageServer, group: 'Beheer' },
    { path: 'prestaties', title: 'Prestaties', icon: 'gauge', perm: P.server, feature: 'antilag', page: pagePerformance, group: 'Beheer' },
    { path: 'aankondigingen', title: 'Aankondigingen', icon: 'megaphone', perm: P.broadcasts, feature: 'broadcasts', page: pageBroadcasts, group: 'Beheer' },
    { path: 'instellingen', title: 'Instellingen', icon: 'cog', perm: P.config, page: pageSettings, group: 'Beheer' },
    { path: 'teksten', title: 'Teksten', icon: 'type', perm: P.texts, page: pageTexts, group: 'Beheer' },
    { path: 'rangen', title: 'Rangen', icon: 'crown', perm: P.ranksEdit, feature: 'ranks', page: pageRanks, group: 'Beheer' },
    { path: 'discord', title: 'Discord', icon: 'chat', perm: P.config, feature: 'discord', page: pageDiscord, group: 'Beheer' },
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
    const [path = '', ...rest] = location.hash.replace(/^#\/?/, '').split('/').map(decodeURIComponent);
    const param = rest.length ? rest.join('/') : undefined;
    if (state.themeBackup) { state.me.theme.colors = state.themeBackup; state.themeBackup = null; }
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
    main.onclick = null;
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

  // =============================================================== Minecraft-tekst: snel voorbeeld en editor

  const MC_COLORS = {
    black: '#000000', dark_blue: '#0000AA', dark_green: '#00AA00', dark_aqua: '#00AAAA', dark_red: '#AA0000',
    dark_purple: '#AA00AA', gold: '#FFAA00', gray: '#AAAAAA', dark_gray: '#555555', blue: '#5555FF',
    green: '#55FF55', aqua: '#55FFFF', red: '#FF5555', light_purple: '#FF55FF', yellow: '#FFFF55', white: '#FFFFFF'
  };
  const MC_ALIASES = { grey: 'gray', dark_grey: 'dark_gray' };
  const DECORATIONS = { bold: 'b', b: 'b', italic: 'i', i: 'i', em: 'i', underlined: 'u', u: 'u', strikethrough: 's', st: 's', obfuscated: 'o', obf: 'o' };
  const THEME_TAGS = ['primary', 'secondary', 'text', 'muted', 'highlight', 'success', 'error', 'warning'];
  const THEME_LABELS = { primary: 'Hoofdkleur', secondary: 'Tweede kleur', text: 'Tekst', muted: 'Gedempt', highlight: 'Opvallend', success: 'Gelukt', error: 'Fout', warning: 'Waarschuwing' };
  const STYLE_TAGS = ['click', 'hover', 'insertion', 'key', 'lang', 'font', 'shadow', 'transition', 'selector', 'score', 'nbt'];

  function resolveColor(name, depth = 0) {
    if (!name) return null;
    const value = name.trim();
    if (/^#[0-9a-f]{6}$/i.test(value)) return value;
    const key = MC_ALIASES[value.toLowerCase()] || value.toLowerCase();
    const theme = state.me && state.me.theme ? state.me.theme.colors : {};
    if (theme[key] && depth < 3) return resolveColor(theme[key], depth + 1);
    return MC_COLORS[key] || null;
  }

  function mix(colors, t) {
    if (colors.length === 1) return colors[0];
    const scaled = Math.min(0.9999, Math.max(0, t)) * (colors.length - 1);
    const index = Math.floor(scaled);
    const local = scaled - index;
    const a = parseInt(colors[index].slice(1), 16);
    const b = parseInt(colors[index + 1].slice(1), 16);
    const channel = shift => Math.round(((a >> shift) & 255) + (((b >> shift) & 255) - ((a >> shift) & 255)) * local);
    return '#' + [16, 8, 0].map(shift => channel(shift).toString(16).padStart(2, '0')).join('');
  }

  /** MiniMessage naar HTML, voor een snel voorbeeld in lijsten. Onbekende tags zijn placeholders. */
  function miniToHtml(input) {
    let source = String(input || '');
    if (source.startsWith('[actionbar]')) source = source.slice(11).trimStart();
    const theme = state.me && state.me.theme ? state.me.theme : { prefix: '', serverName: '' };
    source = source.replace(/<prefix>/g, theme.prefix || '').replace(/<server>/g, theme.serverName || '');
    const tokens = [];
    const stack = [];
    let text = '';
    const flush = () => {
      if (!text) return;
      let color = null;
      let gradient = null;
      const decorations = new Set();
      for (const entry of stack) {
        if (entry.color) { color = entry.color; gradient = null; }
        if (entry.gradient) { gradient = entry.gradient; color = null; }
        if (entry.decoration) decorations.add(entry.decoration);
      }
      const chars = Array.from(text);
      if (gradient) gradient.count += chars.length;
      tokens.push({ chars, color, gradient, decorations });
      text = '';
    };
    const handle = raw => {
      const closing = raw.startsWith('/');
      const body = closing ? raw.slice(1) : raw;
      const parts = body.split(':');
      const name = parts[0].toLowerCase();
      if (closing) {
        for (let i = stack.length - 1; i >= 0; i--) {
          if (!name || stack[i].tag === name) { stack.splice(i); break; }
        }
        return true;
      }
      if (name === 'newline' || name === 'br') { tokens.push({ br: true }); return true; }
      if (name === 'reset') { stack.length = 0; return true; }
      if (name === 'gradient' || name === 'rainbow') {
        const colors = name === 'rainbow' ? ['#FF5555', '#FFAA00', '#FFFF55', '#55FF55', '#55FFFF', '#5555FF', '#FF55FF']
          : parts.slice(1).map(c => resolveColor(c)).filter(Boolean);
        stack.push({ tag: name, gradient: { colors: colors.length ? colors : ['#FFFFFF', '#000000'], count: 0, index: 0 } });
        return true;
      }
      if (name === 'color' || name === 'colour' || name === 'c') { stack.push({ tag: name, color: resolveColor(parts[1]) }); return true; }
      const color = resolveColor(name);
      if (color) { stack.push({ tag: name, color }); return true; }
      if (DECORATIONS[name]) { stack.push({ tag: name, decoration: DECORATIONS[name] }); return true; }
      if (name.startsWith('!') && DECORATIONS[name.slice(1)]) return true;
      if (STYLE_TAGS.includes(name)) { stack.push({ tag: name }); return true; }
      return false;
    };
    let pos = 0;
    while (pos < source.length) {
      const char = source[pos];
      if (char === '\\' && source[pos + 1] === '<') { text += '<'; pos += 2; continue; }
      if (char === '<') {
        let end = pos + 1;
        let quote = null;
        let ok = true;
        while (end < source.length) {
          const c = source[end];
          if (quote) { if (c === quote) quote = null; } else if (c === "'" || c === '"') quote = c; else if (c === '>') break; else if (c === '<') { ok = false; break; }
          end++;
        }
        if (ok && end < source.length) {
          const raw = source.slice(pos + 1, end);
          flush();
          if (!handle(raw)) tokens.push({ placeholder: raw.split(':')[0] });
          pos = end + 1;
          continue;
        }
      }
      if (char === '\n') { flush(); tokens.push({ br: true }); pos++; continue; }
      text += char;
      pos++;
    }
    flush();
    return tokens.map(token => {
      if (token.br) return '<br>';
      if (token.placeholder) return `<span class="mc-ph">${esc(token.placeholder)}</span>`;
      const cls = Array.from(token.decorations).map(d => `mc-${d}`).join(' ');
      if (token.gradient) {
        return token.chars.map(c => {
          const g = token.gradient;
          const color = mix(g.colors, g.count > 1 ? g.index++ / (g.count - 1) : 0);
          return `<span class="${cls}" style="color:${color}">${esc(c)}</span>`;
        }).join('');
      }
      return `<span class="${cls}"${token.color ? ` style="color:${token.color}"` : ''}>${esc(token.chars.join(''))}</span>`;
    }).join('');
  }
  const mc = (value, cls = '') => raw(`<span class="mc ${cls}">${miniToHtml(Array.isArray(value) ? value.join('\n') : value)}</span>`);

  function wrapSelection(area, open, close) {
    const start = area.selectionStart;
    const end = area.selectionEnd;
    const value = area.value;
    area.value = value.slice(0, start) + open + value.slice(start, end) + close + value.slice(end);
    area.focus();
    area.selectionStart = start + open.length;
    area.selectionEnd = end + open.length;
    area.dispatchEvent(new Event('input'));
  }

  function insertAt(area, value) {
    const start = area.selectionStart;
    area.value = area.value.slice(0, start) + value + area.value.slice(area.selectionEnd);
    area.focus();
    area.selectionStart = area.selectionEnd = start + value.length;
    area.dispatchEvent(new Event('input'));
  }

  /**
   * De visuele teksteditor: knoppen voor kleuren, verloop, opmaak en placeholders, met een
   * voorbeeld van hoe het er in Minecraft uitziet (exact, door de server gemaakt).
   */
  function openTextEditor({ title, subtitle, value, placeholders = [], onSave, onReset, list = Array.isArray(value), saveLabel = 'Opslaan' }) {
    const text = Array.isArray(value) ? value.join('\n') : (value || '');
    const extras = ['prefix', 'server', ...placeholders.filter(p => p !== 'prefix' && p !== 'server')];
    state.textEditor = { onSave, onReset, list };
    openModal(html`<form data-form="text-editor" class="text-editor">
      <div class="modal-head"><h3>${title}</h3>${subtitle ? html`<p class="mono">${subtitle}</p>` : ''}</div>
      <div class="modal-body">
        <div class="te-toolbar">
          <div class="te-group" aria-label="Themakleuren">${THEME_TAGS.map(t => html`<button type="button" class="te-swatch" data-wrap="${t}" title="${THEME_LABELS[t]} (&lt;${t}&gt;)" style="--c:${resolveColor(t) || '#fff'}"></button>`)}</div>
          <div class="te-group" aria-label="Minecraft-kleuren">${Object.entries(MC_COLORS).map(([n, c]) => html`<button type="button" class="te-swatch sm" data-wrap="${n}" title="${n}" style="--c:${c}"></button>`)}</div>
          <div class="te-group">
            <button type="button" class="btn sm te-btn" data-wrap="bold" title="Vet"><b>B</b></button>
            <button type="button" class="btn sm te-btn" data-wrap="italic" title="Cursief"><i>I</i></button>
            <button type="button" class="btn sm te-btn" data-wrap="underlined" title="Onderstreept"><u>U</u></button>
            <button type="button" class="btn sm te-btn" data-wrap="strikethrough" title="Doorgestreept"><s>S</s></button>
            <span class="te-sep"></span>
            <input type="color" id="te-color" value="#ffc857" title="Eigen kleur kiezen"><button type="button" class="btn sm" data-te="color">Kleur</button>
            <span class="te-sep"></span>
            <input type="color" id="te-grad-a" value="#ffc857" title="Verloop: begin"><input type="color" id="te-grad-b" value="#e9724c" title="Verloop: eind"><button type="button" class="btn sm" data-te="gradient">Verloop</button>
            <span class="te-sep"></span>
            <button type="button" class="btn sm" data-insert="&lt;newline&gt;" title="Nieuwe regel">↵</button>
          </div>
          <div class="te-group te-placeholders"><span class="muted">Invoegen:</span>${extras.map(p => html`<button type="button" class="te-chip" data-insert="&lt;${p}&gt;">${p}</button>`)}</div>
        </div>
        <textarea class="input mono te-source" name="text" rows="${list ? 7 : 4}" spellcheck="false">${text}</textarea>
        <p class="te-hint muted">${list ? 'Elke regel in het vak is een losse regel. ' : ''}Selecteer tekst en klik op een kleur of opmaak, of typ zelf tags zoals &lt;primary&gt;…&lt;/primary&gt;.</p>
        <div class="te-preview-label"><span>Zo ziet het eruit in Minecraft</span><span class="badge info hidden" id="te-actionbar">boven de hotbar</span></div>
        <div class="mc-preview" id="te-preview"><span class="spinner sm"></span></div>
      </div>
      <div class="modal-foot">
        ${onReset ? html`<button type="button" class="btn ghost te-reset" data-te="reset">Standaardtekst terugzetten</button>` : ''}
        <button type="button" class="btn ghost" data-action="close">Annuleren</button>
        <button class="btn primary">${saveLabel}</button>
      </div></form>`, 'wide');

    const area = $('.te-source', modal);
    let timer;
    let sequence = 0;
    const refresh = () => {
      clearTimeout(timer);
      timer = setTimeout(async () => {
        const id = ++sequence;
        const value = list ? area.value.split('\n') : area.value;
        try {
          const result = await post('/texts/preview', { text: value });
          if (id !== sequence) return;
          $('#te-preview', modal).innerHTML = result.html || '<span class="muted">(leeg)</span>';
          $('#te-actionbar', modal).classList.toggle('hidden', !result.actionbar);
        } catch (error) {
          if (id === sequence) $('#te-preview', modal).innerHTML = miniToHtml(area.value);
        }
      }, 200);
    };
    area.addEventListener('input', refresh);
    $('.te-toolbar', modal).addEventListener('click', event => {
      const button = event.target.closest('button');
      if (!button) return;
      if (button.dataset.wrap) wrapSelection(area, `<${button.dataset.wrap}>`, `</${button.dataset.wrap}>`);
      else if (button.dataset.insert) insertAt(area, button.dataset.insert);
      else if (button.dataset.te === 'color') { const c = $('#te-color', modal).value; wrapSelection(area, `<${c}>`, `</${c}>`); }
      else if (button.dataset.te === 'gradient') wrapSelection(area, `<gradient:${$('#te-grad-a', modal).value}:${$('#te-grad-b', modal).value}>`, '</gradient>');
    });
    const reset = $('[data-te=reset]', modal);
    if (reset) {
      reset.addEventListener('click', async () => {
        const ok = await confirmDialog({ title: 'Standaardtekst terugzetten?', text: 'Je eigen versie van deze tekst gaat verloren.', confirm: 'Terugzetten', danger: true });
        if (ok && onReset) await busy(null, onReset);
      });
    }
    refresh();
    area.focus();
  }

  // =============================================================== instellingen (alle configbestanden)

  const FILE_NAMES = {
    'config.yml': 'Algemeen', 'teleport.yml': 'Teleports', 'modules/settings.yml': 'Eerste keer joinen',
    'modules/tips.yml': 'Tips', 'modules/homes.yml': 'Homes', 'modules/tpa.yml': 'TPA', 'modules/spawn.yml': 'Spawn',
    'modules/back.yml': 'Terug (/back)', 'modules/msg.yml': 'Privéberichten', 'modules/gamemode.yml': 'Spelmodus',
    'modules/afk.yml': 'AFK', 'modules/utility.yml': 'Handige commando’s', 'modules/staff.yml': 'Vanish en invsee',
    'modules/economy.yml': 'Economie', 'modules/shop.yml': 'Shops', 'modules/locks.yml': 'Sloten',
    'modules/moderation.yml': 'Moderatie', 'modules/skills.yml': 'Skills', 'modules/sleep.yml': 'Slapen',
    'modules/motd.yml': 'MOTD', 'modules/discord.yml': 'Discord', 'modules/panel.yml': 'Webpaneel',
    'modules/leaderboards.yml': 'Toplijsten', 'modules/scoreboard.yml': 'Scoreboard', 'modules/broadcasts.yml': 'Aankondigingen',
    'modules/antilag.yml': 'Antilag', 'modules/timber.yml': 'Bomen kappen'
  };
  const fileName = path => FILE_NAMES[path] || path.replace(/^modules\//, '').replace(/\.yml$/, '');
  const prettyKey = key => {
    if (/^[A-Z0-9_]+$/.test(key)) return key;
    const text = key.replace(/[-_.]/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();
    return text.charAt(0).toUpperCase() + text.slice(1);
  };

  async function pageSettings(main, file, alive) {
    const list = await api('/settings');
    if (!alive()) return;
    const files = list.files;
    const current = file && files.some(f => f.path === file) ? file : files[0].path;
    render(main, html`${pageHead('Instellingen', 'Alle instellingen van PindaFramework. Na opslaan worden ze meteen herladen.')}
      <div class="settings-layout">
        <nav class="card settings-files">
          <select class="input settings-select" aria-label="Kies een onderdeel">${files.map(f => html`<option value="${f.path}" ${f.path === current ? 'selected' : ''}>${fileName(f.path)}</option>`)}</select>
          ${files.map(f => html`<a href="#/instellingen/${f.path}" class="${f.path === current ? 'active' : ''}"><span>${fileName(f.path)}</span>${f.enabled ? '' : html`<span class="badge plain">uit</span>`}</a>`)}
        </nav>
        <section class="card settings-form" id="settings-form"><div class="page-loading"><div class="spinner"></div></div></section>
      </div>`);
    $('.settings-select', main).addEventListener('change', event => { location.hash = `#/instellingen/${event.target.value}`; });
    const data = await api(`/settings/file?path=${encodeURIComponent(current)}`);
    if (!alive()) return;
    renderSettingsForm($('#settings-form', main), data, files.find(f => f.path === current));
  }

  function renderSettingsForm(container, data, file) {
    const original = {};
    const collect = fields => fields.forEach(f => { if (f.type === 'section') collect(f.fields); else original[f.path] = f.value; });
    collect(data.fields);
    state.settings = { path: data.path, original };
    const field = f => {
      const label = html`<span class="cfg-label">${prettyKey(f.key)}</span>`;
      const help = f.help && f.help.length ? html`<span class="cfg-help">${f.help.filter(Boolean).join(' ')}</span>` : '';
      const note = f.note && f.note.length ? html`<span class="cfg-help">${f.note.join(' ')}</span>` : '';
      switch (f.type) {
        case 'section':
          return html`<fieldset class="cfg-section"><legend>${prettyKey(f.key)}</legend>${help}${f.fields.map(field)}</fieldset>`;
        case 'boolean':
          return html`<div class="cfg-row"><div class="cfg-text">${label}${help}${note}</div>
            <label class="switch"><input type="checkbox" data-path="${f.path}" data-type="boolean" ${f.value ? 'checked' : ''}><span></span></label></div>`;
        case 'integer':
        case 'number':
          return html`<div class="cfg-row"><div class="cfg-text">${label}${help}${note}</div>
            <input class="input cfg-number" type="number" step="${f.type === 'integer' ? '1' : 'any'}" data-path="${f.path}" data-type="${f.type}" value="${f.value}"></div>`;
        case 'numbers':
          return html`<div class="cfg-row"><div class="cfg-text">${label}${help}${note}<span class="cfg-help">Getallen gescheiden door komma's.</span></div>
            <input class="input cfg-wide" data-path="${f.path}" data-type="numbers" value="${f.value.join(', ')}"></div>`;
        case 'list':
          return html`<div class="cfg-row cfg-stack"><div class="cfg-text">${label}${help}${note}<span class="cfg-help">Eén per regel.</span></div>
            <textarea class="input mono" rows="${Math.min(10, Math.max(3, f.value.length + 1))}" data-path="${f.path}" data-type="list">${f.value.join('\n')}</textarea></div>`;
        case 'map':
          return html`<div class="cfg-row cfg-stack"><div class="cfg-text">${label}${help}${note}</div>
            <div class="cfg-map" data-path="${f.path}" data-type="map">
              ${Object.entries(f.value).map(([k, v]) => mapRow(k, v))}
              <button type="button" class="btn sm" data-map-add>${icon('plus')} Regel toevoegen</button>
            </div></div>`;
        default: {
          const color = /^#[0-9a-f]{6}$/i.test(f.value || '');
          const formatted = !color && /<[a-z#!/]/i.test(f.value || '');
          return html`<div class="cfg-row ${formatted ? 'cfg-stack' : ''}"><div class="cfg-text">${label}${help}${note}</div>
            <div class="cfg-string">
              ${color ? html`<input type="color" value="${f.value}" data-color-for="${f.path}">` : ''}
              <input class="input ${formatted ? 'mono' : ''}" data-path="${f.path}" data-type="string" value="${f.value}">
              ${formatted ? html`<button type="button" class="btn sm" data-edit-text="${f.path}">Visueel bewerken</button>` : ''}
            </div>
            ${formatted ? html`<div class="mc-preview sm" data-preview-for="${f.path}">${mc(f.value)}</div>` : ''}</div>`;
        }
      }
    };
    render(container, html`<div class="card-head"><h2>${icon('server')}${fileName(data.path)}</h2>
        <span class="count mono">${data.path}${file && !file.enabled ? ' · module staat uit' : ''}</span></div>
      <form class="card-body cfg-form" data-form="settings">
        ${data.description && data.description.length ? html`<div class="alert info cfg-intro">${data.description.filter(Boolean).map(line => html`${line}<br>`)}</div>` : ''}
        ${data.fields.map(field)}
        <div class="save-bar"><span class="muted" id="settings-dirty">Geen wijzigingen</span><button class="btn primary">${icon('save')} Opslaan en herladen</button></div>
      </form>`);
    const form = $('.cfg-form', container);
    const markDirty = () => { const count = Object.keys(settingsChanges(form)).length; $('#settings-dirty', container).textContent = count ? `${count} ${count === 1 ? 'wijziging' : 'wijzigingen'}` : 'Geen wijzigingen'; };
    form.addEventListener('input', event => {
      const target = event.target;
      if (target.dataset.colorFor) { const input = $(`[data-path="${CSS.escape(target.dataset.colorFor)}"]`, form); input.value = target.value.toUpperCase(); }
      const preview = target.dataset.path && $(`[data-preview-for="${CSS.escape(target.dataset.path)}"]`, form);
      if (preview) preview.innerHTML = miniToHtml(target.value);
      markDirty();
    });
    form.addEventListener('change', markDirty);
    form.addEventListener('click', event => {
      const add = event.target.closest('[data-map-add]');
      if (add) { add.insertAdjacentHTML('beforebegin', part(mapRow('', 0))); markDirty(); return; }
      const remove = event.target.closest('[data-map-remove]');
      if (remove) { remove.closest('.map-row').remove(); markDirty(); return; }
      const edit = event.target.closest('[data-edit-text]');
      if (edit) {
        const input = $(`[data-path="${CSS.escape(edit.dataset.editText)}"]`, form);
        openTextEditor({ title: prettyKey(edit.dataset.editText.split('.').pop()), subtitle: edit.dataset.editText, value: input.value, onSave: async value => {
          input.value = value;
          input.dispatchEvent(new Event('input', { bubbles: true }));
          return true;
        } });
      }
    });
  }

  const mapRow = (key, value) => html`<div class="map-row"><input class="input mono map-key" placeholder="NAAM" value="${key}"><input class="input map-value" type="number" step="any" value="${value}"><button type="button" class="btn ghost sm icon-only" data-map-remove aria-label="Verwijderen">${icon('x')}</button></div>`;

  function settingsChanges(form) {
    const changes = {};
    const original = state.settings ? state.settings.original : {};
    $$('[data-path]', form).forEach(element => {
      const path = element.dataset.path;
      let value;
      switch (element.dataset.type) {
        case 'boolean': value = element.checked; break;
        case 'integer': case 'number': value = element.value === '' ? null : Number(element.value); break;
        case 'numbers': value = element.value.split(',').map(s => s.trim()).filter(Boolean).map(Number); break;
        case 'list': value = element.value.split('\n').map(s => s.replace(/\s+$/, '')).filter(s => s.length); break;
        case 'map': {
          value = {};
          $$('.map-row', element).forEach(row => {
            const key = $('.map-key', row).value.trim().toUpperCase();
            if (key) value[key] = Number($('.map-value', row).value || 0);
          });
          break;
        }
        default: value = element.value;
      }
      if (element.dataset.type === 'list' || element.dataset.type === 'numbers') {
        const before = (original[path] || []).map(String);
        if (JSON.stringify(before) !== JSON.stringify(value.map(String))) changes[path] = value;
      } else if (element.dataset.type === 'map') {
        if (JSON.stringify(original[path]) !== JSON.stringify(value)) changes[path] = value;
      } else if (value !== null && String(value) !== String(original[path])) {
        changes[path] = value;
      }
    });
    return changes;
  }

  // =============================================================== teksten en uiterlijk

  const SECTION_NAMES = {
    general: 'Algemeen', settings: 'Instellingen-menu', tips: 'Tips', menu: 'Menu’s', teleport: 'Teleports', homes: 'Homes',
    tpa: 'TPA', spawn: 'Spawn', back: 'Terug', msg: 'Privéberichten', gamemode: 'Spelmodus', afk: 'AFK', utility: 'Handige commando’s',
    vanish: 'Vanish', invsee: 'Invsee', economy: 'Economie', shop: 'Shops', lock: 'Sloten', partner: 'Partners', rank: 'Rangen',
    moderation: 'Moderatie', skills: 'Skills', sleep: 'Slapen', discord: 'Discord', panel: 'Webpaneel', admin: 'Beheer',
    top: 'Toplijsten', scoreboard: 'Scoreboard', broadcasts: 'Aankondigingen', antilag: 'Antilag', timber: 'Bomen kappen'
  };

  async function pageTexts(main, tab, alive) {
    const tabs = html`<div class="tabs"><a href="#/teksten" class="${tab ? '' : 'active'}">Teksten</a><a href="#/teksten/uiterlijk" class="${tab === 'uiterlijk' ? 'active' : ''}">Uiterlijk</a></div>`;
    if (tab === 'uiterlijk') return pageAppearance(main, tabs, alive);
    const lang = state.textLang || (state.me && state.me.lang) || '';
    const data = await api(`/texts${lang ? `?lang=${encodeURIComponent(lang)}` : ''}`);
    if (!alive()) return;
    state.textLang = data.lang;
    const sections = [...new Set(data.entries.map(e => e.key.split('.')[0]))];
    const filter = state.textFilter || { section: '', query: '', changed: false };
    render(main, html`${pageHead('Teksten', 'Alle meldingen, menu’s en tips. Klik op een tekst om hem aan te passen met kleuren en opmaak.')}
      ${tabs}
      <section class="card">
        <div class="card-head text-filters">
          <select class="input" id="text-lang" aria-label="Taal">${data.languages.map(l => html`<option value="${l.code}" ${l.code === data.lang ? 'selected' : ''}>${l.name}</option>`)}</select>
          <select class="input" id="text-section" aria-label="Onderdeel"><option value="">Alle onderdelen</option>${sections.map(s => html`<option value="${s}" ${s === filter.section ? 'selected' : ''}>${SECTION_NAMES[s] || s}</option>`)}</select>
          <label class="search">${icon('search')}<input class="input" id="text-search" type="search" placeholder="Zoek in teksten…" value="${filter.query}"></label>
          <label class="check"><input type="checkbox" id="text-changed" ${filter.changed ? 'checked' : ''}> Alleen aangepast</label>
        </div>
        <div id="text-list"></div>
      </section>`);
    const show = () => {
      const query = filter.query.toLowerCase();
      const entries = data.entries.filter(e => (!filter.section || e.key.split('.')[0] === filter.section)
        && (!filter.changed || e.changed)
        && (!query || e.key.toLowerCase().includes(query) || JSON.stringify(e.value).toLowerCase().includes(query)));
      render($('#text-list', main), entries.length ? html`<div class="text-list">${entries.slice(0, 400).map(e => html`<button type="button" class="text-row" data-key="${e.key}">
          <span class="text-key mono">${e.key}${e.changed ? html` <span class="badge warning">aangepast</span>` : ''}</span>
          <span class="mc-preview sm">${mc(e.value)}</span></button>`)}</div>
          ${entries.length > 400 ? html`<div class="pager"><span>${num(entries.length - 400)} meer; zoek gerichter om ze te zien.</span></div>` : ''}`
        : html`<div class="empty">Geen teksten gevonden.</div>`);
    };
    const save = () => { state.textFilter = filter; show(); };
    $('#text-lang', main).addEventListener('change', event => { state.textLang = event.target.value; navigate(); });
    $('#text-section', main).addEventListener('change', event => { filter.section = event.target.value; save(); });
    $('#text-changed', main).addEventListener('change', event => { filter.changed = event.target.checked; save(); });
    let timer;
    $('#text-search', main).addEventListener('input', event => { clearTimeout(timer); timer = setTimeout(() => { filter.query = event.target.value.trim(); save(); }, 150); });
    $('#text-list', main).addEventListener('click', event => {
      const row = event.target.closest('.text-row');
      if (!row) return;
      const entry = data.entries.find(e => e.key === row.dataset.key);
      openTextEditor({
        title: SECTION_NAMES[entry.key.split('.')[0]] || 'Tekst', subtitle: entry.key, value: entry.value, placeholders: entry.placeholders,
        onSave: async value => {
          const result = await post('/texts', { lang: data.lang, key: entry.key, value });
          entry.value = result.value;
          entry.changed = entry.default !== null && JSON.stringify(entry.default) !== JSON.stringify(entry.value);
          toast('Tekst opgeslagen. Hij is meteen actief in-game.');
          show();
          return result;
        },
        onReset: entry.default === null ? null : async () => {
          const result = await post('/texts/reset', { lang: data.lang, key: entry.key });
          entry.value = result.value;
          entry.changed = false;
          toast('Standaardtekst teruggezet.');
          show();
          return result;
        }
      });
    });
    show();
  }

  async function pageAppearance(main, tabs, alive) {
    const data = await api('/appearance');
    if (!alive()) return;
    const original = { ...state.me.theme.colors };
    state.themeBackup = original;
    const sample = () => html`<div class="mc-preview">${mc(['<prefix><success>Je home <highlight>thuis</highlight> is opgeslagen.',
      '<prefix><error>Daar heb je geen toestemming voor.', '<prefix><text>Saldo: <primary>1.250 PindaCredits</primary> <muted>(bank)',
      '<secondary>✦ <bold>Tip</bold> <muted>» <text>Zet je geld op tijd op de <primary>/bank</primary>.'])}</div>`;
    render(main, html`${pageHead('Teksten', 'De servernaam, de prefix voor alle meldingen en de kleuren.')}
      ${tabs}
      <form data-form="appearance" class="grid two">
        ${card('Naam en prefix', 'crown', html`<div class="card-body">
          <div class="appearance-row"><div><b>Servernaam</b><div class="cfg-help">Overal waar &lt;server&gt; staat.</div></div>
            <div class="mc-preview sm" id="app-name">${mc(data.serverName)}</div><button type="button" class="btn sm" data-app-edit="serverName">Bewerken</button></div>
          <div class="appearance-row"><div><b>Prefix</b><div class="cfg-help">Voor bijna elke melding (&lt;prefix&gt;).</div></div>
            <div class="mc-preview sm" id="app-prefix">${mc(data.prefix)}</div><button type="button" class="btn sm" data-app-edit="prefix">Bewerken</button></div>
          <input type="hidden" name="serverName" value="${data.serverName}"><input type="hidden" name="prefix" value="${data.prefix}">
        </div>`)}
        ${card('Kleuren', 'sun', html`<div class="card-body"><div class="color-grid">${THEME_TAGS.map(t => html`<label class="color-field">
            <input type="color" name="color-${t}" value="${/^#/.test(data.colors[t]) ? data.colors[t] : (resolveColor(data.colors[t]) || '#ffffff')}">
            <span><b>${THEME_LABELS[t]}</b><span class="cfg-help mono">&lt;${t}&gt;</span></span></label>`)}</div></div>`)}
        <section class="card"><div class="card-head"><h2>${icon('megaphone')}Voorbeeld</h2></div><div class="card-body" id="app-sample">${sample()}</div></section>
        <div class="save-bar"><span class="muted">Opslaan herlaadt alle teksten met de nieuwe kleuren.</span><button class="btn primary">${icon('save')} Opslaan</button></div>
      </form>`);
    const form = $('form', main);
    form.addEventListener('input', event => {
      if (event.target.type === 'color') {
        state.me.theme.colors[event.target.name.slice(6)] = event.target.value.toUpperCase();
        render($('#app-sample', main), sample());
        $('#app-name', main).innerHTML = miniToHtml(form.serverName.value);
        $('#app-prefix', main).innerHTML = miniToHtml(form.prefix.value);
      }
    });
    form.addEventListener('click', event => {
      const button = event.target.closest('[data-app-edit]');
      if (!button) return;
      const key = button.dataset.appEdit;
      openTextEditor({ title: key === 'prefix' ? 'Prefix' : 'Servernaam', value: form[key].value, onSave: async value => {
        form[key].value = value;
        state.me.theme[key === 'prefix' ? 'prefix' : 'serverName'] = value;
        $(key === 'prefix' ? '#app-prefix' : '#app-name', main).innerHTML = miniToHtml(value);
        render($('#app-sample', main), sample());
        return true;
      } });
    });
  }

  // =============================================================== server: MOTD, server.properties en spelregels

  const PROPERTY_LABELS = {
    'max-players': 'Maximaal aantal spelers', difficulty: 'Moeilijkheid', gamemode: 'Standaard spelmodus',
    'force-gamemode': 'Spelmodus afdwingen bij joinen', pvp: 'PvP (spelers kunnen elkaar aanvallen)',
    'view-distance': 'View distance (chunks)', 'simulation-distance': 'Simulation distance (chunks)',
    'spawn-protection': 'Spawnbescherming (blokken rond de spawn)', 'player-idle-timeout': 'Kicken na inactief (minuten, 0 = nooit)',
    'allow-flight': 'Vliegen toestaan (anders kick bij vliegen)', 'allow-nether': 'Nether aan', 'enable-command-block': 'Command blocks aan',
    'hide-online-players': 'Spelersnamen verbergen in de serverlijst', 'entity-broadcast-range-percentage': 'Zichtafstand van mobs en items (%)',
    'white-list': 'Whitelist aan', 'enforce-whitelist': 'Whitelist afdwingen (kickt wie er niet op staat)',
    motd: 'MOTD uit server.properties (de MOTD-module gaat hier overheen)', 'resource-pack': 'Resourcepack (link)', 'require-resource-pack': 'Resourcepack verplicht'
  };
  const OPTION_LABELS = { peaceful: 'Vredig', easy: 'Makkelijk', normal: 'Normaal', hard: 'Moeilijk', survival: 'Survival', creative: 'Creative', adventure: 'Adventure', spectator: 'Toeschouwer' };
  const GAMERULE_LABELS = {
    keepinventory: 'Inventory houden bij doodgaan', dodaylightcycle: 'Dag en nacht wisselen', advancetime: 'Dag en nacht wisselen',
    doweathercycle: 'Het weer verandert', advanceweather: 'Het weer verandert', mobgriefing: 'Mobs veranderen blokken (creepers, endermen)',
    dofiretick: 'Vuur verspreidt zich', domobspawning: 'Mobs spawnen', spawnmobs: 'Mobs spawnen', announceadvancements: 'Advancements in de chat',
    showdeathmessages: 'Doodsberichten in de chat', naturalregeneration: 'Levens herstellen vanzelf', doinsomnia: 'Phantoms spawnen',
    spawnphantoms: 'Phantoms spawnen', doimmediaterespawn: 'Direct respawnen', randomtickspeed: 'Groeisnelheid (random tick speed)',
    spawnradius: 'Spawngebied (blokken)', playerssleepingpercentage: 'Slaappercentage (vanilla; de slaapmodule regelt dit)',
    dotraderspawning: 'Rondreizende handelaar spawnt', dopatrolspawning: 'Patrouilles spawnen', disableraids: 'Raids uitzetten',
    falldamage: 'Valschade', firedamage: 'Vuurschade', drowningdamage: 'Verdrinkingsschade', freezedamage: 'Bevriezingsschade',
    doentitydrops: 'Entities laten items vallen', dotiledrops: 'Blokken laten items vallen', maxentitycramming: 'Max mobs op één plek',
    commandblockoutput: 'Command blocks melden in de chat', sendcommandfeedback: 'Feedback na commando’s', logadmincommands: 'Admin-commando’s loggen',
    universalanger: 'Boze mobs vallen iedereen aan', forgivedeadplayers: 'Mobs vergeven dode spelers', dolimitedcrafting: 'Alleen ontgrendelde recepten',
    reduceddebuginfo: 'Minder info op F3', spectatorsgeneratechunks: 'Toeschouwers laden nieuwe chunks', pvp: 'PvP'
  };
  const ruleLabel = name => GAMERULE_LABELS[name.toLowerCase().replace(/_/g, '')] || prettyKey(name);

  function serverTabs(active) {
    const tabs = [];
    if (can(P.server)) tabs.push(['', 'Beheer']);
    if (can(P.config)) tabs.push(['motd', 'MOTD'], ['instellingen', 'server.properties'], ['spelregels', 'Spelregels']);
    return html`<div class="tabs">${tabs.map(([id, label]) => html`<a href="#/server${id ? `/${id}` : ''}" class="${active === id ? 'active' : ''}">${label}</a>`)}</div>`;
  }

  async function pageMotd(main, alive) {
    const data = await api('/motd');
    if (!alive()) return;
    const name = state.me.theme.serverName;
    const save = async body => {
      const result = await post('/motd', body);
      toast('MOTD opgeslagen.');
      navigate();
      return result;
    };
    render(main, html`${pageHead('Server', 'Het bericht in de serverlijst van Minecraft.')}
      ${serverTabs('motd')}
      ${data.moduleEnabled ? '' : html`<div class="alert warning">De MOTD-module staat uit in config.yml (modules.motd). Zet hem aan en herstart de server.</div>`}
      <section class="card"><div class="card-head"><h2>${icon('list')}MOTD’s</h2><span class="count">${data.motds.length > 1 ? 'elke keer een willekeurige' : ''}</span></div>
        <div class="card-body motd-list">${data.motds.map((motd, index) => html`<div class="motd-item">
          <div class="serverlist"><img class="server-icon" src="/api/server/icon" data-fallback="/favicon.svg" alt="" width="64" height="64">
            <div class="serverlist-text"><div class="serverlist-top"><span class="serverlist-name">${raw(miniToHtml(name))}</span><span class="serverlist-count">${num(data.online)}/${num(data.max)} ▮▮▮▮</span></div>
            <div class="serverlist-motd">${raw(data.previews[index] || '')}</div></div></div>
          <div class="btn-row"><button class="btn sm" data-motd-edit="${index}">Bewerken</button>${data.motds.length > 1 ? html`<button class="btn sm danger" data-motd-remove="${index}">Verwijderen</button>` : ''}</div>
        </div>`)}
        <button class="btn" data-motd-add>${icon('plus')} MOTD toevoegen</button></div></section>
      <form class="card" data-form="motd-settings"><div class="card-head"><h2>${icon('server')}Opties</h2></div><div class="card-body">
        <div class="cfg-row"><div class="cfg-text"><span class="cfg-label">Deze MOTD gebruiken</span><span class="cfg-help">Uit = de MOTD uit server.properties.</span></div><label class="switch"><input type="checkbox" name="enabled" ${data.enabled ? 'checked' : ''}><span></span></label></div>
        <div class="cfg-row"><div class="cfg-text"><span class="cfg-label">Spelersnamen verbergen</span><span class="cfg-help">Niemand ziet wie er online is als hij met de muis over het aantal gaat.</span></div><label class="switch"><input type="checkbox" name="hidePlayers" ${data.hidePlayers ? 'checked' : ''}><span></span></label></div>
        <div class="cfg-row"><div class="cfg-text"><span class="cfg-label">Getoond maximum</span><span class="cfg-help">-1 = het echte maximum.</span></div><input class="input cfg-number" type="number" name="shownMax" value="${data.shownMax}"></div>
        <div class="save-bar"><span></span><button class="btn primary">${icon('save')} Opslaan</button></div></div></form>`);
    main.onclick = event => {
      const edit = event.target.closest('[data-motd-edit]');
      const remove = event.target.closest('[data-motd-remove]');
      const add = event.target.closest('[data-motd-add]');
      if (edit) {
        const index = Number(edit.dataset.motdEdit);
        openTextEditor({ title: 'MOTD bewerken', value: data.motds[index], placeholders: ['online', 'max'], onSave: value => {
          const list = [...data.motds];
          list[index] = value;
          return save({ motds: list });
        } });
      } else if (remove) {
        confirmDialog({ title: 'MOTD verwijderen?', text: 'Deze variant verdwijnt uit de lijst.', confirm: 'Verwijderen', danger: true }).then(ok => {
          if (ok) busy(remove, () => save({ motds: data.motds.filter((_, i) => i !== Number(remove.dataset.motdRemove)) }));
        });
      } else if (add) {
        openTextEditor({ title: 'Nieuwe MOTD', value: '<primary><server></primary><newline><text>', placeholders: ['online', 'max'], onSave: value => save({ motds: [...data.motds, value] }) });
      }
    };
  }

  async function pageServerProperties(main, alive) {
    const data = await api('/server/settings');
    if (!alive()) return;
    state.properties = Object.fromEntries(data.properties.map(p => [p.key, p.value]));
    render(main, html`${pageHead('Server', 'Instellingen uit server.properties. Waar "direct" staat, werkt het meteen; de rest na een herstart.')}
      ${serverTabs('instellingen')}
      <form class="card" data-form="properties"><div class="card-body">
        ${data.properties.map(p => html`<div class="cfg-row"><div class="cfg-text"><span class="cfg-label">${PROPERTY_LABELS[p.key] || p.key}</span>
            <span class="cfg-help mono">${p.key} · ${p.live ? html`<span class="success-text">direct</span>` : 'na herstart'}</span></div>
          ${p.type === 'boolean' ? html`<label class="switch"><input type="checkbox" data-prop="${p.key}" data-type="boolean" ${p.value === 'true' ? 'checked' : ''}><span></span></label>`
            : p.type === 'select' ? html`<select class="input cfg-number" data-prop="${p.key}">${p.options.map(o => html`<option value="${o}" ${o === p.value ? 'selected' : ''}>${OPTION_LABELS[o] || o}</option>`)}</select>`
            : p.type === 'integer' ? html`<input class="input cfg-number" type="number" data-prop="${p.key}" value="${p.value}">`
            : html`<input class="input cfg-wide" data-prop="${p.key}" value="${p.value}">`}</div>`)}
        <div class="save-bar"><span class="muted">Alleen gewijzigde instellingen worden opgeslagen.</span><button class="btn primary">${icon('save')} Opslaan</button></div>
      </div></form>`);
  }

  async function pageGamerules(main, alive) {
    const data = await api('/server/settings');
    if (!alive()) return;
    const rules = data.gamerules.rules;
    const worlds = data.gamerules.worlds;
    let world = state.ruleWorld && worlds.includes(state.ruleWorld) ? state.ruleWorld : worlds[0];
    render(main, html`${pageHead('Server', 'Spelregels (gamerules) per wereld. Wijzigingen werken meteen.')}
      ${serverTabs('spelregels')}
      <section class="card"><div class="card-head"><h2>${icon('list')}Spelregels</h2>
        <select class="input rule-world" aria-label="Wereld">${worlds.map(w => html`<option value="${w}" ${w === world ? 'selected' : ''}>${w}</option>`)}</select></div>
        <div id="rule-list"></div></section>`);
    const show = () => render($('#rule-list', main), html`<div class="card-body">${rules.map(r => {
      const value = r.values[world];
      const changed = value !== r.default;
      return html`<div class="cfg-row"><div class="cfg-text"><span class="cfg-label">${ruleLabel(r.name)}${changed ? html` <span class="badge warning">aangepast</span>` : ''}</span>
          <span class="cfg-help mono">${r.name} · standaard ${String(r.default)}</span></div>
        ${r.type === 'boolean' ? html`<label class="switch"><input type="checkbox" data-rule="${r.name}" ${value ? 'checked' : ''}><span></span></label>`
          : html`<input class="input cfg-number" type="number" data-rule="${r.name}" value="${value}">`}</div>`;
    })}</div>`);
    $('.rule-world', main).addEventListener('change', event => { world = state.ruleWorld = event.target.value; show(); });
    $('#rule-list', main).addEventListener('change', async event => {
      const input = event.target.closest('[data-rule]');
      if (!input) return;
      const value = input.type === 'checkbox' ? input.checked : input.value;
      try {
        const result = await post('/server/gamerule', { rule: input.dataset.rule, world, value });
        const rule = rules.find(r => r.name === input.dataset.rule);
        rule.values[world] = result.value;
        toast(`${ruleLabel(rule.name)}: ${String(result.value)} in ${result.worlds}.`);
        show();
      } catch (error) {
        if (error.status !== 401) toast(error.message, 'error');
        show();
      }
    });
    show();
  }

  // =============================================================== rangen

  async function pageRanks(main, _, alive) {
    const data = await api('/ranks');
    if (!alive()) return;
    state.ranksData = data;
    const s = data.settings;
    const isOp = !!state.me.rank?.operator;
    const lock = isOp ? '' : 'disabled';
    const opOnly = isOp ? '' : ' Alleen een operator kan dit aanpassen.';
    const rankOptions = selected => data.ranks.map(r => html`<option value="${r.id}" ${r.id === selected ? 'selected' : ''}>${r.displayName}</option>`);
    render(main, html`${pageHead('Rangen', 'Maak rangen, kies hun kleuren, prefix en permissies. Wijzigingen werken meteen.',
        html`<button class="btn primary" data-action="rank-new">${icon('plus')} Nieuwe rang</button>`)}
      <div class="rank-cards">${data.ranks.map(r => html`<article class="card rank-card">
        <div class="rank-card-top"><div><div class="mc-preview sm">${mc(r.prefix || r.displayName)}</div>
          <h3 style="color:${resolveColor(r.color) || '#fff'}">${r.displayName}</h3></div>
          <div class="btn-row"><button class="btn sm" data-rank-edit="${r.id}">Bewerken</button>${r.isDefault ? '' : html`<button class="btn sm danger" data-rank-delete="${r.id}">Verwijderen</button>`}</div></div>
        <div class="shop-stats"><div>Spelers<b>${num(r.players)}</b></div><div>Gewicht<b>${num(r.weight)}</b></div><div>Permissies<b>${r.operator ? 'alles' : num(r.permissions.length)}</b></div></div>
        <div class="btn-row">${r.isDefault ? html`<span class="badge success">standaard</span>` : ''}${r.operator ? html`<span class="badge warning">operator</span>` : ''}${r.inherits ? html`<span class="badge plain">erft van ${r.inherits}</span>` : ''}</div>
      </article>`)}</div>
      <form class="card" data-form="rank-settings" style="margin-top:16px"><div class="card-head"><h2>${icon('server')}Algemeen</h2></div><div class="card-body">
        <div class="cfg-row"><div class="cfg-text"><span class="cfg-label">Standaardrang</span><span class="cfg-help">Wat nieuwe spelers krijgen.${opOnly}</span></div><select class="input cfg-number" name="defaultRank" ${lock}>${rankOptions(s.defaultRank)}</select></div>
        <div class="cfg-row"><div class="cfg-text"><span class="cfg-label">Rang voor operators</span><span class="cfg-help">Operators zonder rang krijgen deze bij hun eerste join.${opOnly}</span></div><select class="input cfg-number" name="operatorsGetRank" ${lock}>${rankOptions(s.operatorsGetRank)}</select></div>
        <div class="cfg-row"><div class="cfg-text"><span class="cfg-label">Operator gelijk houden aan de rang</span><span class="cfg-help">Wie geen operator-rang heeft, verliest zijn operator-status.${opOnly}</span></div><label class="switch"><input type="checkbox" name="syncOperator" ${s.syncOperator ? 'checked' : ''} ${lock}><span></span></label></div>
        <div class="cfg-row"><div class="cfg-text"><span class="cfg-label">Chatopmaak aan</span></div><label class="switch"><input type="checkbox" name="chatEnabled" ${s.chatEnabled ? 'checked' : ''}><span></span></label></div>
        <div class="cfg-row cfg-stack"><div class="cfg-text"><span class="cfg-label">Chatopmaak</span><span class="cfg-help">&lt;rank_prefix&gt; = prefix, &lt;name&gt; = naam in de rangkleur, &lt;message&gt; = het bericht.</span></div>
          <div class="cfg-string"><input class="input mono" name="chatFormat" value="${s.chatFormat}"><button type="button" class="btn sm" data-chat-edit>Visueel bewerken</button></div>
          <div class="mc-preview sm" id="chat-preview">${mc(chatSample(s.chatFormat))}</div></div>
        <div class="save-bar"><span></span><button class="btn primary">${icon('save')} Opslaan</button></div>
      </div></form>`);
    const form = $('[data-form=rank-settings]', main);
    form.chatFormat.addEventListener('input', () => { $('#chat-preview', main).innerHTML = miniToHtml(chatSample(form.chatFormat.value)); });
    main.onclick = event => {
      const edit = event.target.closest('[data-rank-edit]');
      const remove = event.target.closest('[data-rank-delete]');
      if (event.target.closest('[data-chat-edit]')) {
        openTextEditor({ title: 'Chatopmaak', value: form.chatFormat.value, placeholders: ['rank_prefix', 'name', 'message'], onSave: async value => {
          form.chatFormat.value = value;
          form.chatFormat.dispatchEvent(new Event('input'));
          return true;
        } });
      } else if (edit) {
        openRankEditor(data.ranks.find(r => r.id === edit.dataset.rankEdit));
      } else if (remove) {
        const rank = data.ranks.find(r => r.id === remove.dataset.rankDelete);
        confirmDialog({ title: `${rank.displayName} verwijderen?`, text: `${num(rank.players)} spelers met deze rang krijgen de standaardrang.`, confirm: 'Verwijderen', danger: true }).then(ok => {
          if (ok) busy(remove, async () => { await post('/ranks/delete', { id: rank.id }); toast('Rang verwijderd.'); navigate(); });
        });
      }
    };
  }

  const chatSample = format => (format || '').replace(/<rank_prefix>/g, '<#B17CFF>[Pinda]</#B17CFF> ').replace(/<name>/g, '<#B17CFF>Henk_Bouwt</#B17CFF>').replace(/<message>/g, '<#E0E0E0>Hallo allemaal!</#E0E0E0>');

  function openRankEditor(rank) {
    const data = state.ranksData;
    const isNew = !rank;
    const r = rank || { id: '', displayName: '', weight: 10, inherits: data.settings.defaultRank, operator: false, color: '#FFFFFF', chatColor: '#E0E0E0', prefix: '', permissions: [] };
    const inherited = [];
    let parent = r.inherits;
    const seen = new Set([r.id]);
    while (parent && !seen.has(parent)) {
      seen.add(parent);
      const p = data.ranks.find(x => x.id === parent);
      if (!p) break;
      p.permissions.forEach(node => inherited.push([node, p.displayName]));
      parent = p.inherits;
    }
    const hex = value => /^#[0-9a-f]{6}$/i.test(value) ? value : (resolveColor(value) || '#ffffff');
    state.rankEdit = { permissions: [...r.permissions], isNew };
    openModal(html`<form data-form="rank" class="rank-editor">
      <div class="modal-head"><h3>${isNew ? 'Nieuwe rang' : `${r.displayName} bewerken`}</h3></div>
      <div class="modal-body">
        <div class="row">
          <label class="field">${label('Id', '(kleine letters)')}<input class="input mono" name="id" value="${r.id}" ${isNew ? '' : 'readonly'} required pattern="[a-z0-9_-]{1,32}" placeholder="bijv. vip"></label>
          <label class="field">${label('Naam')}<input class="input" name="displayName" value="${r.displayName}" required placeholder="bijv. VIP"></label>
          <label class="field">${label('Gewicht', '(hoger = belangrijker)')}<input class="input" type="number" name="weight" value="${r.weight}" required></label>
        </div>
        <div class="row">
          <label class="field">${label('Erft van')}<select name="inherits"><option value="">Niets</option>${data.ranks.filter(x => x.id !== r.id).map(x => html`<option value="${x.id}" ${x.id === r.inherits ? 'selected' : ''}>${x.displayName}</option>`)}</select></label>
          <label class="field">${label('Kleur naam')}<input type="color" name="color" value="${hex(r.color)}"></label>
          <label class="field">${label('Kleur chat')}<input type="color" name="chatColor" value="${hex(r.chatColor)}"></label>
          <label class="check" style="align-self:center"><input type="checkbox" name="operator" ${r.operator ? 'checked' : ''}> Operator (mag alles)</label>
        </div>
        <div class="field" style="margin-top:14px">${label('Prefix')}
          <div class="cfg-string"><input class="input mono" name="prefix" value="${r.prefix}" placeholder="<gold>[VIP]</gold> "><button type="button" class="btn sm" data-prefix-edit>Visueel bewerken</button></div>
          <div class="mc-preview sm" id="rank-preview">${mc(chatSample('<rank_prefix><name> <dark_gray>»</dark_gray> <message>').replace('<#B17CFF>[Pinda]</#B17CFF> ', r.prefix))}</div></div>
        <div class="field" style="margin-top:14px">${label('Permissies', '(begin met - om iets juist uit te zetten, plugin.* voor alles van een plugin)')}
          <div class="perm-add"><input class="input mono" id="perm-input" list="perm-list" placeholder="Zoek of typ een permissie…"><button type="button" class="btn sm" data-perm-add>${icon('plus')} Toevoegen</button></div>
          <datalist id="perm-list">${data.permissions.map(p => html`<option value="${p.name}">${p.description || ''}</option>`)}</datalist>
          <div class="chips perm-chips" id="perm-chips"></div>
          ${inherited.length ? html`<details class="manual"><summary>${num(inherited.length)} geërfde permissies</summary><div class="chips">${inherited.map(([node, from]) => html`<span class="badge plain" title="van ${from}">${node}</span>`)}</div></details>` : ''}
        </div>
      </div>
      <div class="modal-foot"><button type="button" class="btn ghost" data-action="close">Annuleren</button><button class="btn primary">${isNew ? 'Rang maken' : 'Opslaan'}</button></div>
    </form>`, 'wide');
    const chips = () => render($('#perm-chips', modal), state.rankEdit.permissions.length ? state.rankEdit.permissions.map(node => html`<span class="badge ${node.startsWith('-') ? 'error' : 'info'}">${node}
        <button type="button" class="btn ghost sm icon-only chip-x" data-perm-remove="${node}" aria-label="${node} weghalen">${icon('x')}</button></span>`) : html`<span class="muted">Nog geen eigen permissies.</span>`);
    const addPermission = () => {
      const input = $('#perm-input', modal);
      const node = input.value.trim().toLowerCase();
      if (node && !state.rankEdit.permissions.includes(node)) state.rankEdit.permissions.push(node);
      input.value = '';
      chips();
    };
    const preview = () => { $('#rank-preview', modal).innerHTML = miniToHtml(chatSample('<rank_prefix><name> <dark_gray>»</dark_gray> <message>').replace('<#B17CFF>[Pinda]</#B17CFF> ', $('[name=prefix]', modal).value)); };
    $('[name=prefix]', modal).addEventListener('input', preview);
    $('#perm-input', modal).addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); addPermission(); } });
    $('.rank-editor', modal).addEventListener('click', event => {
      if (event.target.closest('[data-perm-add]')) addPermission();
      const remove = event.target.closest('[data-perm-remove]');
      if (remove) { state.rankEdit.permissions = state.rankEdit.permissions.filter(n => n !== remove.dataset.permRemove); chips(); }
      if (event.target.closest('[data-prefix-edit]')) {
        const form = $('.rank-editor', modal);
        const values = Object.fromEntries(new FormData(form));
        values.operator = form.operator.checked;
        state.rankDraft = values;
        openTextEditor({ title: 'Prefix', value: values.prefix, onSave: async value => {
          openRankEditor({ ...r, ...state.rankDraft, prefix: value, permissions: state.rankEdit.permissions });
          return undefined;
        } });
      }
    });
    chips();
  }

  // =============================================================== discord

  async function pageDiscord(main, _, alive) {
    const data = await api('/discord');
    if (!alive()) return;
    const events = { punishments: 'Bans, mutes, kicks en waarschuwingen', revokes: 'Unbans en unmutes', 'rank-changes': 'Iemand krijgt een andere rang',
      'panel-logins': 'Iemand logt in op het paneel', 'panel-actions': 'Alle andere acties in het paneel', 'server-start-stop': 'De server start of stopt',
      lag: 'De server laggt (lage TPS) en is weer normaal' };
    render(main, html`${pageHead('Discord', 'Koppel Discord-kanalen via webhooks: een live statusbericht en meldingen voor staff.')}
      ${data.moduleEnabled ? '' : html`<div class="alert warning">De Discord-module staat uit in config.yml (modules.discord). Zet hem aan en herstart de server.</div>`}
      <div class="alert info">Webhook maken: in Discord bij het kanaal op <b>Kanaal bewerken › Integraties › Webhooks › Nieuwe webhook</b>, daarna <b>Webhook-URL kopiëren</b> en hieronder plakken.</div>
      <form data-form="discord" class="grid two" style="margin-top:16px">
        ${card('Serverstatus', 'server', html`<div class="card-body">
          <p class="muted">Eén bericht dat zichzelf steeds bijwerkt: online of offline, aantal spelers, TPS en wie er online is.</p>
          <div class="cfg-row"><div class="cfg-text"><span class="cfg-label">Aan</span></div><label class="switch"><input type="checkbox" name="status-enabled" ${data.status.enabled ? 'checked' : ''}><span></span></label></div>
          <label class="field">${label('Webhook-URL')}<input class="input mono" name="status-webhook" value="${data.status.webhook}" placeholder="https://discord.com/api/webhooks/…"></label>
          <div class="row" style="margin-top:14px">
            <label class="field">${label('Bijwerken elke', '(seconden)')}<input class="input" type="number" min="30" name="status-interval" value="${data.status.interval}"></label>
            <label class="field">${label('Adres', '(optioneel)')}<input class="input" name="status-address" value="${data.status.address}" placeholder="play.pindacraft.nl"></label>
          </div>
          <div class="cfg-row"><div class="cfg-text"><span class="cfg-label">Namen van online spelers tonen</span></div><label class="switch"><input type="checkbox" name="status-players" ${data.status.showPlayers ? 'checked' : ''}><span></span></label></div>
          <button type="button" class="btn sm" data-discord-test="status">${icon('send')} Testbericht sturen</button></div>`)}
        ${card('Staffmeldingen', 'shield', html`<div class="card-body">
          <p class="muted">Meldingen in een besloten staffkanaal.</p>
          <div class="cfg-row"><div class="cfg-text"><span class="cfg-label">Aan</span></div><label class="switch"><input type="checkbox" name="staff-enabled" ${data.staff.enabled ? 'checked' : ''}><span></span></label></div>
          <label class="field">${label('Webhook-URL')}<input class="input mono" name="staff-webhook" value="${data.staff.webhook}" placeholder="https://discord.com/api/webhooks/…"></label>
          ${Object.entries(events).map(([key, text]) => html`<div class="cfg-row"><div class="cfg-text"><span class="cfg-label">${text}</span></div><label class="switch"><input type="checkbox" name="event-${key}" ${data.staff.events[key] ? 'checked' : ''}><span></span></label></div>`)}
          <button type="button" class="btn sm" data-discord-test="staff">${icon('send')} Testbericht sturen</button></div>`)}
        <div class="save-bar"><label class="field" style="flex:1;max-width:320px">${label('Naam in Discord', '(leeg = naam van de webhook)')}<input class="input" name="username" value="${data.username}"></label>
          <button class="btn primary">${icon('save')} Opslaan</button></div>
      </form>`);
    main.onclick = event => {
      const test = event.target.closest('[data-discord-test]');
      if (!test) return;
      const webhook = $(`[name=${test.dataset.discordTest}-webhook]`, main).value.trim();
      busy(test, async () => { await post('/discord/test', { webhook }); toast('Testbericht verstuurd. Kijk in Discord!'); });
    };
  }

  // =============================================================== speleracties (profiel)

  function playerActionCards(p) {
    const cards = [];
    if (can(P.playersManage)) {
      const online = !!p.live;
      cards.push(card('Acties', 'zap', online ? html`<div class="card-body">
        <div class="field">${label('Spelmodus')}<div class="btn-row">${['survival', 'creative', 'adventure', 'spectator'].map(m => html`<button class="btn sm ${p.live.gamemode === m ? 'primary' : ''}" data-action="player-action" data-kind="gamemode" data-value="${m}">${GAMEMODES[m]}</button>`)}</div></div>
        <div class="btn-row" style="margin-top:14px">
          <button class="btn sm" data-action="player-action" data-kind="heal">Healen</button>
          <button class="btn sm" data-action="player-action" data-kind="feed">Eten geven</button>
          <button class="btn sm" data-action="player-action" data-kind="fly">Vliegen ${p.live.flying ? 'uit' : 'aan/uit'}</button>
          <button class="btn sm" data-action="player-action" data-kind="spawn">Naar spawn</button>
          <button class="btn sm" data-action="inventory">Inventory bekijken</button>
          <button class="btn sm danger" data-action="player-action" data-kind="clear-inventory">Inventory leegmaken</button>
        </div>
        <form class="row" data-form="player-message" style="margin-top:14px"><label class="field">${label('Bericht sturen')}<input class="input" name="value" maxlength="256" required placeholder="Verschijnt als [Paneel] in de chat"></label><button class="btn">${icon('send')}</button></form>
        <form class="row" data-form="player-teleport"><label class="field">${label('Teleporteren naar speler')}<input class="input" name="value" required placeholder="Naam van een online speler"></label><button class="btn">Teleporteren</button></form>
        <form class="row" data-form="player-give"><label class="field">${label('Item geven')}<input class="input mono" name="value" list="material-list" required placeholder="bijv. diamond"></label>
          <label class="field" style="flex:0 1 90px">${label('Aantal')}<input class="input" type="number" name="amount" min="1" max="2304" value="1"></label><button class="btn">Geven</button></form>
        <datalist id="material-list"></datalist>
      </div>` : html`<div class="card-body"><p class="muted">${p.name} is offline. Acties zoals healen, teleporteren en items geven kunnen alleen als de speler online is.</p></div>`));
    }
    if (can(P.playersManage) && feature('homes')) {
      cards.push(card('Homes', 'list', html`<div id="homes-list"><div class="page-loading" style="min-height:80px"><div class="spinner"></div></div></div>`));
    }
    if (p.twoFactor) {
      cards.push(card('Tweestapsverificatie', 'lock', html`<div class="card-body">
        <p>${p.twoFactor.enabled ? html`<span class="badge success">gekoppeld</span> <span class="muted">sinds ${date(p.twoFactor.since)}</span>` : html`<span class="badge plain">niet gekoppeld</span>`}</p>
        ${p.twoFactor.enabled ? html`<p class="muted">Telefoon kwijt? Na een reset koppelt ${p.name} bij de volgende login opnieuw.</p><button class="btn sm danger" data-action="reset-2fa">2FA resetten</button>` : ''}
      </div>`));
    }
    return cards;
  }

  async function loadPlayerExtras(main, p, alive) {
    if (can(P.playersManage) && p.live) {
      if (!state.materials) {
        api('/materials').then(result => { state.materials = result.materials; fillMaterials(); }).catch(() => {});
      } else {
        fillMaterials();
      }
    }
    if (can(P.playersManage) && feature('homes') && $('#homes-list', main)) {
      try {
        const result = await api(`/players/${p.uuid}/homes`);
        if (!alive()) return;
        renderHomes(main, p, result.homes);
      } catch (error) {
        if (alive()) render($('#homes-list', main), html`<div class="empty">${error.message}</div>`);
      }
    }
  }

  function fillMaterials() {
    const list = $('#material-list');
    if (list && state.materials && !list.childElementCount) list.innerHTML = state.materials.map(m => `<option value="${esc(m)}">`).join('');
  }

  function renderHomes(main, p, homes) {
    render($('#homes-list', main), homes.length ? html`<div class="table-wrap"><table><thead><tr><th>Naam</th><th>Wereld</th><th>Positie</th><th></th></tr></thead><tbody>
      ${homes.map(h => html`<tr><td><b>${h.name}</b></td><td>${h.world}</td><td class="mono">${h.x}, ${h.y}, ${h.z}</td>
        <td class="actions"><button class="btn sm danger" data-action="home-delete" data-name="${h.name}">Verwijderen</button></td></tr>`)}</tbody></table></div>`
      : html`<div class="empty">${p.name} heeft nog geen homes.</div>`);
  }

  function openInventory(uuid, name, data) {
    const grid = (items, size, ender) => {
      const bySlot = Object.fromEntries(items.map(item => [item.slot, item]));
      return html`<div class="inv-grid">${Array.from({ length: size }, (_, slot) => {
        const item = bySlot[slot];
        return item ? html`<button type="button" class="inv-slot filled ${item.enchanted ? 'ench' : ''}" title="${item.name} (${item.material}) · klik om te verwijderen" data-inv-remove="${slot}" data-ender="${ender ? 1 : 0}">
            <span class="inv-name">${item.name}</span>${item.amount > 1 ? html`<span class="inv-amount">${item.amount}</span>` : ''}</button>`
          : html`<span class="inv-slot"></span>`;
      })}</div>`;
    };
    openModal(html`<div class="inventory-view">
      <div class="modal-head"><h3>Inventory van ${name}</h3><p>Klik op een item om het weg te halen.</p></div>
      <div class="modal-body">
        <h4 class="inv-title">Rugzak en hotbar</h4>${grid(data.inventory.filter(i => i.slot < 36), 36, false)}
        <h4 class="inv-title">Harnas en tweede hand</h4>${grid(data.inventory.filter(i => i.slot >= 36).map(i => ({ ...i, slot: i.slot - 36 })), 5, false)}
        <h4 class="inv-title">Enderkist</h4>${grid(data.enderchest, 27, true)}
      </div>
      <div class="modal-foot"><button type="button" class="btn" data-action="close">Sluiten</button></div></div>`, 'wide');
    $('.inventory-view', modal).addEventListener('click', async event => {
      const slot = event.target.closest('[data-inv-remove]');
      if (!slot) return;
      const ender = slot.dataset.ender === '1';
      const real = Number(slot.dataset.invRemove) + (!ender && slot.closest('.inv-grid') !== $$('.inv-grid', modal)[0] ? 36 : 0);
      const result = await busy(slot, () => post(`/players/${uuid}/inventory/remove`, { slot: String(real), enderchest: ender }));
      if (result) { toast('Item verwijderd.'); openInventory(uuid, name, result); }
    });
  }

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
          <label class="field">${label('Skill')}<select name="skill">${sk.skills.map(x => html`<option value="${x.id}">${x.name}</option>`)}<option value="">Alle skills (alleen resetten)</option></select></label>
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

    sections.push(...playerActionCards(p));

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
    loadPlayerExtras(main, p, alive);
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

  // =============================================================== toplijsten en scoreboard

  const BOARD_ICONS = { money: 'coin', skills: 'award', playtime: 'clock', kills: 'sword', mobkills: 'ghost', deaths: 'skull', trees: 'tree' };

  async function pageLeaderboards(main, _, alive) {
    const data = await api('/leaderboards');
    if (!alive()) return;
    renderLeaderboards(main, data);
  }

  function renderLeaderboards(main, data) {
    const sb = data.scoreboard;
    const boards = data.boards.length ? html`<div class="grid three">${data.boards.map(b => html`<section class="card board-card">
        <div class="card-head"><h2>${icon(BOARD_ICONS[b.id] || 'trophy')}${b.name}</h2><span class="count">${num(b.total)} ${b.total === 1 ? 'speler' : 'spelers'}</span></div>
        ${b.description ? html`<p class="board-desc">${b.description}${b.id === 'money' ? ` In ${currency()}.` : ''}</p>` : ''}
        <div class="table-wrap"><table><tbody>${b.entries.length ? b.entries.map(e => html`<tr ${can(P.players) ? raw(`data-href="#/spelers/${esc(e.uuid)}" tabindex="0"`) : ''}>
          <td class="rank-pos ${e.position <= 3 ? `medal-${e.position}` : ''}">${e.position}</td><td>${who(e.name, null, null)}</td><td class="num nowrap"><b>${b.id === 'money' ? amount(e.text) : e.text}</b></td></tr>`)
          : emptyRow(3, 'Nog niemand op deze lijst.')}</tbody></table></div></section>`)}</div>`
      : html`<div class="empty-page">${icon('trophy')}<h2>Geen toplijsten</h2><p>Zet toplijsten aan bij Instellingen › Toplijsten.</p></div>`;
    render(main, html`${pageHead('Toplijsten', `Spelers zien dit met /top en op het scoreboard. ${data.updated ? `Bijgewerkt ${ago(data.updated)}.` : 'Wordt nu berekend…'}`,
        html`<button class="btn" data-action="leaderboards-refresh">${icon('refresh')} Nu bijwerken</button>`)}
      ${boards}
      ${sb && sb.enabled ? scoreboardCard(data) : html`<div class="alert info">Het scoreboard staat uit in config.yml (modules.scoreboard).</div>`}`);
    if (sb && sb.enabled) {
      $$('input[name=sb-board]', main).forEach(input => input.addEventListener('change', () => {
        render($('#sb-preview', main), sidebarPreview(sb.previews[input.value]));
      }));
    }
  }

  function sidebarPreview(preview) {
    if (!preview) return html`<div class="empty">Nog geen voorbeeld.</div>`;
    return html`<div class="mc-sidebar"><div class="mc-sidebar-title">${raw(preview.title)}</div>
      ${preview.lines.map(l => html`<div class="mc-sidebar-row"><span>${raw(l.left || '&nbsp;')}</span>${l.right ? html`<span class="mc-sidebar-right">${raw(l.right)}</span>` : ''}</div>`)}</div>`;
  }

  function scoreboardCard(data) {
    const sb = data.scoreboard;
    const s = sb.settings;
    const ids = Object.keys(sb.previews);
    const current = sb.current && sb.previews[sb.current] ? sb.current : ids[0];
    const available = data.allBoards.filter(b => b.available);
    const ordered = [...s.boards.map(id => available.find(b => b.id === id)).filter(Boolean), ...available.filter(b => !s.boards.includes(b.id))];
    const names = Object.fromEntries(data.allBoards.map(b => [b.id, b.name]));
    const row = (name, title, help, input) => html`<div class="cfg-row"><div class="cfg-text"><span class="cfg-label">${title}</span>${help ? html`<span class="cfg-help">${help}</span>` : ''}</div>${input}</div>`;
    const toggle = (name, checked) => html`<label class="switch"><input type="checkbox" name="${name}" ${checked ? 'checked' : ''}><span></span></label>`;
    return html`<section class="card" style="margin-top:16px"><div class="card-head"><h2>${icon('list')}Scoreboard</h2><span class="count">rechts in beeld, wisselt elke ${num(s.switchSeconds)} seconden</span></div>
      <div class="card-body scoreboard-layout">
        <div class="scoreboard-preview">
          ${ids.length > 1 ? html`<div class="seg" role="tablist">${ids.map(id => html`<label><input type="radio" name="sb-board" value="${id}" ${id === current ? 'checked' : ''}><span>${names[id] || id}</span></label>`)}</div>` : ''}
          <div class="scoreboard-stage" id="sb-preview">${sidebarPreview(sb.previews[current])}</div>
          <p class="muted small">Zo ziet het eruit voor een speler die er nog niet op staat. Spelers zetten het zelf aan of uit in /instellingen.</p>
        </div>
        ${data.canEdit ? html`<form data-form="scoreboard" class="scoreboard-form">
          ${row('defaultEnabled', 'Standaard aan', 'Voor spelers die zelf nog niets gekozen hebben.', toggle('defaultEnabled', s.defaultEnabled))}
          ${row('showInSetup', 'In het welkomstmenu', 'De schakelaar tonen als iemand voor het eerst joint.', toggle('showInSetup', s.showInSetup))}
          ${row('switchSeconds', 'Wisselen na', 'Seconden per toplijst (minimaal 3).', html`<input class="input cfg-number" type="number" min="3" max="600" name="switchSeconds" value="${s.switchSeconds}">`)}
          ${row('places', 'Plekken per lijst', '1 tot 10.', html`<input class="input cfg-number" type="number" min="1" max="10" name="places" value="${s.places}">`)}
          ${row('showOwn', 'Eigen plek onderaan', 'Iedereen ziet zijn eigen plek, ook buiten de top.', toggle('showOwn', s.showOwn))}
          <div class="cfg-row cfg-stack"><div class="cfg-text"><span class="cfg-label">Toplijsten op het scoreboard</span><span class="cfg-help">In deze volgorde; vink uit wat je niet wilt zien.</span></div>
            <div class="check-chips">${ordered.map(b => html`<label class="check-chip"><input type="checkbox" name="sb-boards" value="${b.id}" ${s.boards.includes(b.id) ? 'checked' : ''}> ${b.name}</label>`)}</div></div>
          <div class="cfg-row cfg-stack"><div class="cfg-text"><span class="cfg-label">Werelden zonder scoreboard</span><span class="cfg-help">Namen gescheiden door komma's, bijv. minigames.</span></div>
            <input class="input" name="disabledWorlds" value="${s.disabledWorlds.join(', ')}" placeholder="geen"></div>
          <div class="save-bar"><button type="button" class="btn ghost" data-action="scoreboard-texts">${icon('type')} Teksten aanpassen</button><button class="btn primary">${icon('save')} Opslaan</button></div>
        </form>` : ''}
      </div></section>`;
  }

  // =============================================================== aankondigingen

  async function pageBroadcasts(main, _, alive) {
    const data = await api('/broadcasts');
    if (!alive()) return;
    renderBroadcasts(main, data);
  }

  const BROADCAST_PLACEHOLDERS = ['player', 'online', 'max'];

  async function saveBroadcasts(body, message = 'Aankondigingen opgeslagen.') {
    const result = await post('/broadcasts', body);
    toast(message);
    renderBroadcasts($('#main'), result);
    return result;
  }

  function renderBroadcasts(main, data) {
    const next = data.moduleEnabled && data.nextAt ? data.nextAt - Date.now() : 0;
    const count = data.messages.length;
    render(main, html`${pageHead('Aankondigingen', 'Berichten die vanzelf in de chat verschijnen, voor iedereen die online is.',
        html`<button class="btn primary" data-action="broadcast-once">${icon('send')} Eenmalig versturen</button>`)}
      ${data.moduleEnabled ? '' : html`<div class="alert warning">De module staat uit in config.yml (modules.broadcasts). Zet hem aan en herstart de server.</div>`}
      <section class="card"><div class="card-head"><h2>${icon('megaphone')}Berichten</h2><span class="count">${count ? `${num(count)} · ${data.random ? 'willekeurige volgorde' : 'op volgorde'}` : ''}</span></div>
        <div class="card-body bc-list">${count ? data.messages.map((m, i) => html`<div class="bc-item">
            <div class="mc-preview">${raw(data.previews[i] || '')}</div>
            <div class="btn-row">
              <button class="btn sm" data-bc-edit="${i}">Bewerken</button>
              <button class="btn sm" data-bc-send="${i}">${icon('send')} Nu versturen</button>
              <button class="btn sm ghost" data-bc-move="${i}" data-dir="-1" ${i === 0 ? 'disabled' : ''} aria-label="Omhoog">↑</button>
              <button class="btn sm ghost" data-bc-move="${i}" data-dir="1" ${i === count - 1 ? 'disabled' : ''} aria-label="Omlaag">↓</button>
              <button class="btn sm danger" data-bc-remove="${i}">Verwijderen</button>
            </div></div>`) : html`<div class="empty">${icon('megaphone')}Nog geen aankondigingen. Voeg er een toe!</div>`}
          <button class="btn" data-bc-add>${icon('plus')} Aankondiging toevoegen</button></div></section>
      <form class="card" data-form="broadcast-settings" style="margin-top:16px"><div class="card-head"><h2>${icon('clock')}Wanneer</h2>${next > 0 ? html`<span class="count">volgende over ${human(next)}</span>` : ''}</div><div class="card-body">
        <div class="cfg-row"><div class="cfg-text"><span class="cfg-label">Om de hoeveel minuten</span><span class="cfg-help">Elke keer één aankondiging.</span></div><input class="input cfg-number" type="number" min="1" max="1440" name="intervalMinutes" value="${data.intervalMinutes}"></div>
        <div class="cfg-row"><div class="cfg-text"><span class="cfg-label">Willekeurige volgorde</span><span class="cfg-help">Uit = de lijst van boven naar beneden.</span></div><label class="switch"><input type="checkbox" name="random" ${data.random ? 'checked' : ''}><span></span></label></div>
        <div class="cfg-row"><div class="cfg-text"><span class="cfg-label">Minimaal aantal spelers online</span><span class="cfg-help">Daaronder wordt er niets aangekondigd.</span></div><input class="input cfg-number" type="number" min="0" max="1000" name="minPlayers" value="${data.minPlayers}"></div>
        <div class="cfg-row"><div class="cfg-text"><span class="cfg-label">Geluidje</span></div><label class="switch"><input type="checkbox" name="sound" ${data.sound ? 'checked' : ''}><span></span></label></div>
        <div class="alert info">In een aankondiging kun je &lt;player&gt; (wie het leest), &lt;online&gt;, &lt;max&gt; en &lt;server&gt; gebruiken${data.placeholderApi ? ', en ook %placeholders% van PlaceholderAPI' : ''}. De opmaak eromheen pas je aan bij Teksten › Aankondigingen.</div>
        <div class="save-bar"><span></span><button class="btn primary">${icon('save')} Opslaan</button></div></div></form>`);
    main.onclick = event => {
      const edit = event.target.closest('[data-bc-edit]');
      const send = event.target.closest('[data-bc-send]');
      const move = event.target.closest('[data-bc-move]');
      const remove = event.target.closest('[data-bc-remove]');
      const add = event.target.closest('[data-bc-add]');
      if (edit) {
        const index = Number(edit.dataset.bcEdit);
        openTextEditor({ title: 'Aankondiging bewerken', value: data.messages[index], placeholders: BROADCAST_PLACEHOLDERS, onSave: value => {
          const list = [...data.messages];
          list[index] = value;
          return saveBroadcasts({ messages: list });
        } });
      } else if (send) {
        busy(send, () => post('/broadcasts/send', { index: Number(send.dataset.bcSend) })).then(result => {
          if (result) toast(`Verstuurd naar ${num(result.sent)} ${result.sent === 1 ? 'speler' : 'spelers'}.`);
        });
      } else if (move) {
        const index = Number(move.dataset.bcMove);
        const target = index + Number(move.dataset.dir);
        const list = [...data.messages];
        [list[index], list[target]] = [list[target], list[index]];
        busy(move, () => saveBroadcasts({ messages: list }, 'Volgorde opgeslagen.'));
      } else if (remove) {
        confirmDialog({ title: 'Aankondiging verwijderen?', text: 'Dit bericht komt niet meer in de chat.', confirm: 'Verwijderen', danger: true }).then(ok => {
          if (ok) busy(remove, () => saveBroadcasts({ messages: data.messages.filter((_, i) => i !== Number(remove.dataset.bcRemove)) }, 'Aankondiging verwijderd.'));
        });
      } else if (add) {
        openTextEditor({ title: 'Nieuwe aankondiging', value: '<text>', placeholders: BROADCAST_PLACEHOLDERS,
          onSave: value => saveBroadcasts({ messages: [...data.messages, value] }, 'Aankondiging toegevoegd.') });
      }
    };
  }

  // =============================================================== prestaties en antilag

  const ENTITY_NAMES = {
    item: 'item', experience_orb: 'XP-bol', arrow: 'pijl', cow: 'koe', chicken: 'kip', pig: 'varken', sheep: 'schaap',
    villager: 'villager', iron_golem: 'ijzergolem', zombie: 'zombie', skeleton: 'skelet', creeper: 'creeper', spider: 'spin',
    enderman: 'enderman', slime: 'slijm', squid: 'inktvis', glow_squid: 'gloei-inktvis', bat: 'vleermuis', horse: 'paard',
    rabbit: 'konijn', wolf: 'wolf', cat: 'kat', bee: 'bij', fox: 'vos', goat: 'geit', frog: 'kikker', cod: 'kabeljauw',
    salmon: 'zalm', tropical_fish: 'tropische vis', drowned: 'drowned', piglin: 'piglin', zombified_piglin: 'zombiepiglin',
    magma_cube: 'magmakubus', item_frame: 'itemframe', glow_item_frame: 'itemframe', armor_stand: 'harnasstandaard',
    minecart: 'mijnkar', hopper_minecart: 'hopperkar', chest_minecart: 'kistkar', falling_block: 'vallend blok',
    painting: 'schilderij', wandering_trader: 'handelaar', turtle: 'schildpad', axolotl: 'axolotl', camel: 'kameel'
  };
  const entityName = key => ENTITY_NAMES[key] || key.replace(/_/g, ' ');

  async function pagePerformance(main, _, alive) {
    const load = async full => {
      const data = await api('/performance');
      if (!alive()) return;
      renderPerformance(main, data, full);
    };
    await load(true);
    every(10000, () => load(false).catch(() => {}));
  }

  function renderPerformance(main, data, full) {
    if (full || !$('#perf-live', main)) {
      render(main, html`${pageHead('Prestaties', 'Hoe soepel draait de server? Antilag ruimt op en grijpt in als het nodig is.',
          html`<button class="btn" data-action="reload">${icon('refresh')} Verversen</button>`)}
        <div id="perf-live"></div>
        ${can(P.config) ? perfSettings(data.settings) : ''}`);
    }
    render($('#perf-live', main), perfLive(data));
  }

  function perfLive(d) {
    const sum = key => d.worlds.reduce((total, w) => total + w[key], 0);
    const clear = d.clear;
    const nextText = !clear.next ? 'staat uit'
      : clear.counting ? `aftelling loopt (nog ${Math.max(0, Math.round((clear.next - Date.now()) / 1000))} s)` : `over ${human(Math.max(0, clear.next - Date.now()))}`;
    const lastText = clear.lastCount >= 0 ? `${num(clear.lastCount)} items, ${ago(clear.last)}` : 'nog niet sinds de start';
    const stats = [
      stat('TPS', fixed(d.tps[0]), `1 min · 5m ${fixed(d.tps[1])} · 15m ${fixed(d.tps[2])}`, 'gauge', tpsClass(d.tps[0])),
      stat('Nu', fixed(d.recentTps), `${fixed(d.mspt)} ms per tick (onder 50 is goed)`, 'zap', tpsClass(d.recentTps)),
      stat('Mobs', num(sum('living')), `${num(sum('entities'))} entities in totaal`, 'players'),
      stat('Items op de grond', num(sum('items')), `opruimen ${nextText}`, 'box'),
      stat('Chunks geladen', num(sum('chunks')), `${num(d.worlds.length)} ${d.worlds.length === 1 ? 'wereld' : 'werelden'}`, 'globe')
    ];
    const chunks = d.chunks.length ? d.chunks.map(c => html`<tr><td>${c.world}</td><td class="mono nowrap">${c.x}, ${c.z}</td>
        <td class="num"><b>${num(c.entities)}</b></td><td class="num hide-sm">${num(c.items)}</td>
        <td class="muted">${Object.entries(c.types).map(([type, count]) => `${entityName(type)} ${num(count)}`).join(' · ')}</td></tr>`)
      : emptyRow(5, 'Er zijn geen entities geladen.');
    const worlds = d.worlds.map(w => html`<tr><td><b>${w.name}</b><div class="muted">${ENVIRONMENTS[w.environment] || w.environment}</div></td>
        <td class="num">${num(w.players)}</td><td class="num">${num(w.living)}</td><td class="num">${num(w.items)}</td>
        <td class="num hide-sm">${num(w.entities)}</td><td class="num hide-sm">${num(w.chunks)}</td></tr>`);
    return html`${d.lagging ? html`<div class="alert error" style="margin:0 0 16px">${icon('warn')} <b>De server laggt</b> sinds ${clock(d.laggingSince)} (TPS onder ${fixed(d.threshold)}). Staff met een melding weet ervan; losse items worden opgeruimd.</div>` : ''}
      <div class="grid stats">${stats}</div>
      <div class="grid two" style="margin-top:16px">
        ${card('Items opruimen', 'box', html`<div class="card-body">
          <dl class="kv"><dt>Volgende keer</dt><dd>${nextText}</dd><dt>Vorige keer</dt><dd>${lastText}</dd><dt>Nu op de grond</dt><dd>${num(sum('items'))} items</dd></dl>
          ${can(P.server) ? html`<div class="btn-row" style="margin-top:16px">
            <button class="btn" data-action="perf-clear" data-seconds="30" ${clear.counting ? 'disabled' : ''}>${icon('clock')} Over 30 seconden</button>
            <button class="btn danger" data-action="perf-clear-now">${icon('x')} Meteen opruimen</button></div>
            <p class="muted small" style="margin-top:10px">Met aftelling krijgen spelers eerst een waarschuwing. Waardevolle items, items met een naam en geld blijven altijd liggen.</p>` : ''}
        </div>`)}
        ${card('Drukste chunks', 'list', html`<div class="table-wrap"><table>
          <thead><tr><th>Wereld</th><th>Plek (x, z)</th><th class="num">Entities</th><th class="num hide-sm">Items</th><th>Meeste</th></tr></thead>
          <tbody>${chunks}</tbody></table></div>`, html`<span class="count">in-game: /lag chunks</span>`)}
      </div>
      <div style="margin-top:16px">${card('Werelden', 'globe', html`<div class="table-wrap"><table>
        <thead><tr><th>Wereld</th><th class="num">Spelers</th><th class="num">Mobs</th><th class="num">Items</th><th class="num hide-sm">Entities</th><th class="num hide-sm">Chunks</th></tr></thead>
        <tbody>${worlds}</tbody></table></div>`)}</div>`;
  }

  function perfSettings(s) {
    const toggle = (name, checked) => html`<label class="switch"><input type="checkbox" name="${name}" ${checked ? 'checked' : ''}><span></span></label>`;
    const row = (title, help, input) => html`<div class="cfg-row"><div class="cfg-text"><span class="cfg-label">${title}</span>${help ? html`<span class="cfg-help">${help}</span>` : ''}</div>${input}</div>`;
    return html`<form class="card" data-form="perf-settings" style="margin-top:16px"><div class="card-head"><h2>${icon('cog')}Antilag</h2>
        <a class="btn sm ghost" href="#/instellingen/modules/antilag.yml">Alle instellingen</a></div><div class="card-body">
      ${row('Items automatisch opruimen', 'Met een aftelling in de chat vooraf.', toggle('clearEnabled', s.clearEnabled))}
      ${row('Om de hoeveel minuten', null, html`<input class="input cfg-number" type="number" min="1" max="1440" name="intervalMinutes" value="${s.intervalMinutes}">`)}
      ${row('Maximum mobs per chunk', 'Bij fokken, spawners en eieren. Gewone mobs ’s nachts niet.', toggle('mobLimitEnabled', s.mobLimitEnabled))}
      ${row('Maximaal van dezelfde soort', '0 = geen maximum. Per soort (kippen, villagers…) stel je in bij Alle instellingen.', html`<input class="input cfg-number" type="number" min="0" max="10000" name="perType" value="${s.perType}">`)}
      ${row('Ingrijpen bij lag', 'Staff krijgt een melding (ook in Discord) en losse items worden opgeruimd.', toggle('guardEnabled', s.guardEnabled))}
      ${row('Lag als de TPS lager is dan', '20 is perfect. 15 is een goede grens.', html`<input class="input cfg-number" type="number" min="1" max="19.5" step="0.5" name="tpsBelow" value="${s.tpsBelow}">`)}
      <div class="save-bar"><span></span><button class="btn primary">${icon('save')} Opslaan</button></div>
    </div></form>`;
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

  async function pageServer(main, tab, alive) {
    if (tab === 'motd') return pageMotd(main, alive);
    if (tab === 'instellingen') return pageServerProperties(main, alive);
    if (tab === 'spelregels') return pageGamerules(main, alive);
    if (!can(P.server)) { location.replace('#/server/motd'); return; }
    const data = await api('/server');
    if (!alive()) return;
    const worldOptions = html`<option value="">Alle normale werelden</option>${data.worlds.map(w => html`<option value="${w.name}">${w.name} (${ENVIRONMENTS[w.environment] || w.environment})</option>`)}`;
    const times = [['sunrise', 'Zonsopkomst', 'sunrise'], ['day', 'Dag', 'sun'], ['noon', 'Middag', 'sun'], ['sunset', 'Zonsondergang', 'sunset'], ['night', 'Nacht', 'moon'], ['midnight', 'Middernacht', 'stars']];
    const weathers = [['clear', 'Helder', 'clear'], ['rain', 'Regen', 'rain'], ['thunder', 'Onweer', 'bolt']];
    const wl = data.whitelist;
    render(main, html`${pageHead('Server', 'Tijd, weer, mededelingen, whitelist en onderhoud.')}
      ${serverTabs('')}
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

  async function playerAction(button, kind, value, extra = {}) {
    const uuid = $('#main').dataset.uuid;
    const result = await busy(button, () => post(`/players/${uuid}/action`, { action: kind, value: value === undefined ? null : value, ...extra }));
    if (result) {
      toast(`Gelukt: ${result.result}`);
      if (kind === 'gamemode' || kind === 'fly') navigate();
    }
    return !!result;
  }

  const actions = {
    async 'player-action'(button) {
      const kind = button.dataset.kind;
      if (kind === 'clear-inventory') {
        const ok = await confirmDialog({ title: 'Inventory leegmaken?', text: `Alles in de inventory van ${$('#main').dataset.name} verdwijnt. Dit kan niet ongedaan worden.`, confirm: 'Leegmaken', danger: true });
        if (!ok) return;
      }
      playerAction(button, kind, button.dataset.value);
    },

    async inventory(button) {
      const main = $('#main');
      const result = await busy(button, () => api(`/players/${main.dataset.uuid}/inventory`));
      if (result) openInventory(main.dataset.uuid, main.dataset.name, result);
    },

    async 'home-delete'(button) {
      const ok = await confirmDialog({ title: `Home ${button.dataset.name} verwijderen?`, text: 'De speler kan er daarna niet meer naartoe.', confirm: 'Verwijderen', danger: true });
      if (!ok) return;
      const main = $('#main');
      const result = await busy(button, () => post(`/players/${main.dataset.uuid}/homes/delete`, { name: button.dataset.name }));
      if (result) { toast('Home verwijderd.'); renderHomes(main, { name: main.dataset.name }, result.homes); }
    },

    async 'reset-2fa'(button) {
      const main = $('#main');
      const ok = await confirmDialog({ title: '2FA resetten?', text: `${main.dataset.name} wordt overal uitgelogd en koppelt bij de volgende login opnieuw een authenticator-app.`, confirm: 'Resetten', danger: true });
      if (!ok) return;
      const result = await busy(button, () => post(`/players/${main.dataset.uuid}/2fa/reset`));
      if (result) { toast('2FA gereset.'); navigate(); }
    },

    'rank-new'() {
      openRankEditor(null);
    },

    async 'boost-stop'(button) {
      const result = await busy(button, () => post('/skills/boost', { stop: true }));
      if (result) { toast('De XP-boost is gestopt.'); navigate(); }
    },

    async 'leaderboards-refresh'(button) {
      const result = await busy(button, () => post('/leaderboards/refresh'));
      if (result) { toast('Toplijsten bijgewerkt.'); renderLeaderboards($('#main'), result); }
    },

    'scoreboard-texts'() {
      state.textFilter = { section: 'scoreboard', query: '', changed: false };
      location.hash = '#/teksten';
    },

    'broadcast-once'() {
      openTextEditor({ title: 'Eenmalige aankondiging', subtitle: 'Gaat meteen naar iedereen die online is', value: '<text>', placeholders: BROADCAST_PLACEHOLDERS,
        saveLabel: 'Versturen', onSave: async value => {
          const result = await post('/broadcasts/send', { message: value });
          toast(`Verstuurd naar ${num(result.sent)} ${result.sent === 1 ? 'speler' : 'spelers'}.`);
          return result;
        } });
    },

    async 'perf-clear'(button) {
      const result = await busy(button, () => post('/performance/clear', { seconds: Number(button.dataset.seconds || 30) }));
      if (result) { toast(`De items worden over ${result.seconds} seconden opgeruimd. Spelers krijgen een waarschuwing.`); navigate(); }
    },

    async 'perf-clear-now'(button) {
      const ok = await confirmDialog({ title: 'Meteen opruimen?', text: 'Alle losse items op de grond verdwijnen nu, zonder waarschuwing. Waardevolle items, items met een naam en geld blijven liggen.', confirm: 'Opruimen', danger: true });
      if (!ok) return;
      const result = await busy(button, () => post('/performance/clear', { now: true }));
      if (result) { toast(`${num(result.removed)} items opgeruimd.`); navigate(); }
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
    async 'two-factor'(form, data, button) {
      if (state.verifying) return;
      state.verifying = true;
      button.disabled = true;
      button.classList.add('busy');
      try {
        const result = await post('/login/verify', { code: (data.get('code') || '').trim() });
        if (result.step) { renderTwoFactor(result); return; }
        state.me = result;
        renderShell();
        navigate();
      } catch (error) {
        if (error.status === 401 || error.status === 429) renderLogin(error.message);
        else renderTwoFactor(state.challenge, error.message);
      } finally {
        state.verifying = false;
      }
    },

    async scoreboard(form, data, button) {
      const boards = $$('input[name=sb-boards]:checked', form).map(input => input.value);
      if (!boards.length) { toast('Kies minimaal één toplijst.', 'error'); return; }
      const result = await busy(button, () => post('/scoreboard', {
        defaultEnabled: form.defaultEnabled.checked, showInSetup: form.showInSetup.checked, showOwn: form.showOwn.checked,
        switchSeconds: Number(data.get('switchSeconds')), places: Number(data.get('places')), boards,
        disabledWorlds: String(data.get('disabledWorlds') || '').split(',').map(w => w.trim()).filter(Boolean)
      }));
      if (result) { toast('Scoreboard opgeslagen. Het is meteen bijgewerkt in-game.'); renderLeaderboards($('#main'), result); }
    },

    async 'broadcast-settings'(form, data, button) {
      await busy(button, () => saveBroadcasts({
        intervalMinutes: Number(data.get('intervalMinutes')), minPlayers: Number(data.get('minPlayers')),
        random: form.random.checked, sound: form.sound.checked
      }));
    },

    async 'perf-settings'(form, data, button) {
      const result = await busy(button, () => post('/performance/settings', {
        clearEnabled: form.clearEnabled.checked, intervalMinutes: Number(data.get('intervalMinutes')),
        mobLimitEnabled: form.mobLimitEnabled.checked, perType: Number(data.get('perType')),
        guardEnabled: form.guardEnabled.checked, tpsBelow: Number(data.get('tpsBelow'))
      }));
      if (result) { toast('Antilag-instellingen opgeslagen.'); renderPerformance($('#main'), result, true); }
    },

    async 'text-editor'(form, data, button) {
      const editor = state.textEditor;
      if (!editor) return;
      const text = data.get('text') || '';
      const value = editor.list ? text.split('\n') : text;
      const result = await busy(button, () => editor.onSave(value));
      if (result !== undefined) closeModal();
    },

    async settings(form, data, button) {
      const changes = settingsChanges(form);
      if (!Object.keys(changes).length) { toast('Er is niets veranderd.', 'error'); return; }
      const result = await busy(button, () => post('/settings/file', { path: state.settings.path, values: changes }));
      if (result) { toast('Opgeslagen en herladen.'); renderSettingsForm(form.closest('.settings-form'), result, null); }
    },

    async appearance(form, data, button) {
      const colors = {};
      THEME_TAGS.forEach(t => { colors[t] = String(data.get(`color-${t}`)).toUpperCase(); });
      const result = await busy(button, () => post('/appearance', { serverName: data.get('serverName'), prefix: data.get('prefix'), colors }));
      if (result) {
        state.me.theme = { colors: { ...result.colors }, prefix: result.prefix, serverName: result.serverName };
        state.themeBackup = null;
        toast('Uiterlijk opgeslagen.');
      }
    },

    async 'motd-settings'(form, data, button) {
      const result = await busy(button, () => post('/motd', { enabled: form.enabled.checked, hidePlayers: form.hidePlayers.checked, shownMax: Number(data.get('shownMax')) }));
      if (result) { toast('Opgeslagen.'); navigate(); }
    },

    async properties(form, data, button) {
      const values = {};
      $$('[data-prop]', form).forEach(element => {
        const value = element.type === 'checkbox' ? String(element.checked) : element.value.trim();
        if (value !== String(state.properties[element.dataset.prop])) values[element.dataset.prop] = value;
      });
      if (!Object.keys(values).length) { toast('Er is niets veranderd.', 'error'); return; }
      const result = await busy(button, () => post('/server/properties', { values }));
      if (result) { toast('Opgeslagen.'); navigate(); }
    },

    async 'rank-settings'(form, data, button) {
      const result = await busy(button, () => post('/ranks/settings', {
        defaultRank: data.get('defaultRank'), operatorsGetRank: data.get('operatorsGetRank'),
        syncOperator: form.syncOperator.disabled ? undefined : form.syncOperator.checked,
        chatEnabled: form.chatEnabled.checked, chatFormat: data.get('chatFormat')
      }));
      if (result) { toast('Opgeslagen.'); navigate(); }
    },

    async rank(form, data, button) {
      const result = await busy(button, () => post('/ranks/save', {
        create: state.rankEdit.isNew, id: data.get('id'), displayName: data.get('displayName'), weight: data.get('weight'),
        inherits: data.get('inherits') || null, operator: form.operator.checked, color: String(data.get('color')).toUpperCase(),
        chatColor: String(data.get('chatColor')).toUpperCase(), prefix: data.get('prefix'), permissions: state.rankEdit.permissions
      }));
      if (result) { toast(state.rankEdit.isNew ? 'Rang gemaakt.' : 'Rang opgeslagen.'); closeModal(); navigate(); }
    },

    async discord(form, data, button) {
      const events = {};
      Array.from(form.elements).filter(element => element.name && element.name.startsWith('event-')).forEach(element => { events[element.name.slice(6)] = element.checked; });
      const result = await busy(button, () => post('/discord', {
        username: data.get('username'),
        status: { enabled: form['status-enabled'].checked, webhook: data.get('status-webhook'), interval: Number(data.get('status-interval')),
          showPlayers: form['status-players'].checked, address: data.get('status-address') },
        staff: { enabled: form['staff-enabled'].checked, webhook: data.get('staff-webhook'), events }
      }));
      if (result) toast('Discord-instellingen opgeslagen.');
    },

    async 'player-message'(form, data, button) {
      if (await playerAction(button, 'message', data.get('value'))) form.reset();
    },

    async 'player-teleport'(form, data, button) {
      if (await playerAction(button, 'teleport', data.get('value'))) form.reset();
    },

    async 'player-give'(form, data, button) {
      await playerAction(button, 'give', data.get('value'), { amount: Number(data.get('amount') || 1) });
    },

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
    if (image && image.tagName === 'IMG' && image.dataset.fallback && !image.src.endsWith(image.dataset.fallback)) { image.src = image.dataset.fallback; return; }
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
      const result = await post('/login', { token: match[1] });
      if (result.step) {
        renderTwoFactor(result);
        return true;
      }
      state.me = result;
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
      if (error.status === 401) {
        try {
          const pending = await api('/login/state');
          if (pending.step && pending.step !== 'none') {
            renderTwoFactor(pending);
            return;
          }
        } catch { /* gewoon het inlogscherm */ }
      }
      renderLogin(error.status === 401 ? null : error.message);
      return;
    }
    renderShell();
    navigate();
  }

  boot();
})();
