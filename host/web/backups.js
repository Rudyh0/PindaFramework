/* PindaHost - backups: maken, downloaden, uploaden, terugzetten, en de planning (automatische backups en de dagelijkse herstart). */
'use strict';

(() => {
  const { $, $$, html, render, icon, api, post, toast, openModal, confirmDialog, busy,
    pageHead, card, emptyRow, every, watchJob, actions, forms, state, bytes, num, ago, dateTime, pct } = window.PH;

  const isAdmin = () => !!(state.user && state.user.admin);
  const DAYS = [[1, 'ma'], [2, 'di'], [3, 'wo'], [4, 'do'], [5, 'vr'], [6, 'za'], [7, 'zo']];
  const EVERY = [1, 2, 3, 4, 6, 8, 12];
  const TRIGGERS = {
    automatisch: ['Automatisch', 'info'], handmatig: ['Handmatig', 'plain'],
    'voor-herstel': ['Voor terugzetten', 'warning'], 'voor-herstart': ['Voor herstart', 'plain']
  };
  const bk = { data: null, busy: false, signature: '' };
  // Soort backup → [tekst, kleur]; nooit iets van het prototype (een manifest kan van alles bevatten).
  const triggerLabel = trigger => Object.hasOwn(TRIGGERS, trigger) ? TRIGGERS[trigger] : [String(trigger || 'onbekend'), 'plain'];

  const backupUrl = (name, action) => `/backups/${encodeURIComponent(name)}/${action}`;

  // =============================================================== pagina

  window.PH.pages.backups = async (main, alive) => {
    const data = await api('/backups');
    if (!alive()) return;
    renderBackups(main, data);
    // Loopt er iets, dan vaker kijken; anders af en toe (de planning kan intussen iets doen).
    let last = 0;
    every(4000, async () => {
      if (!bk.busy && Date.now() - last < 30000) return;
      last = Date.now();
      let fresh;
      try { fresh = await api('/backups'); } catch { return; }
      if (!alive() || !$('#backup-list')) return;
      const signature = listSignature(fresh);
      if (signature !== bk.signature || fresh.busy !== bk.busy) refreshList(fresh);
    });
  };

  const listSignature = data => JSON.stringify([data.backups.map(b => [b.name, b.size, b.pinned]), data.busy, data.schedule.lastBackup, data.schedule.lastRestart]);

  function renderBackups(main, data) {
    bk.data = data;
    bk.busy = data.busy;
    bk.signature = listSignature(data);
    const admin = isAdmin();
    render(main, html`${pageHead('Backups', 'De servermap, de website en de databases in één zip. Elke nacht automatisch, of nu met één knop; terugzetten kan per onderdeel.',
        html`${admin ? html`<button class="btn" data-action="backup-upload">${icon('upload')}<span class="hide-sm">Uploaden</span></button>` : ''}
          <button class="btn primary" data-action="backup-create" id="backup-create" ${data.busy ? 'disabled' : ''}>${icon('plus')} Nu een backup maken</button>`)}
      <input type="file" id="backup-file" accept=".zip,application/zip" hidden>
      <div id="backup-status">${statusView(data)}</div>
      <div style="margin-top:16px" id="backup-list">${listView(data)}</div>
      <form data-form="backup-schedule" class="schedule-form" style="margin-top:16px">${scheduleView(data)}</form>`);
    bindSchedule(main);
    const input = $('#backup-file');
    input.addEventListener('change', () => {
      const files = Array.from(input.files || []);
      input.value = '';
      if (!files.length) return;
      window.PH.queueUploads(files.map(file => ({ file, dir: '.' })), 'backups', () => {
        if (location.hash.startsWith('#/backups')) window.PH.reloadPage();
      });
    });
  }

  function refreshList(data) {
    bk.data = data;
    bk.busy = data.busy;
    bk.signature = listSignature(data);
    render($('#backup-status'), statusView(data));
    render($('#backup-list'), listView(data));
    const create = $('#backup-create');
    if (create) create.disabled = data.busy;
  }

  // =============================================================== bovenaan: stand van zaken

  function resultLine(result) {
    if (!result) return '';
    return html`${result.ok ? icon('check') : icon('warn')} ${result.ok ? 'gelukt' : 'mislukt'} · ${ago(result.at)}`;
  }

  function statusView(d) {
    const s = d.schedule;
    const newest = d.backups.find(b => b.manifest);
    const used = d.disk.total > 0 ? d.disk.total - d.disk.free : 0;
    const usedPct = Math.min(100, pct(used, d.disk.total));
    const alerts = [];
    if (d.busy) alerts.push(html`<div class="alert info">${icon('clock')} <div>Er loopt nu een backup of terugzetten. De lijst werkt zichzelf bij.</div></div>`);
    if (s.lastBackup && !s.lastBackup.ok) alerts.push(html`<div class="alert error">${icon('warn')} <div><b>De laatste automatische backup lukte niet</b> (${dateTime(s.lastBackup.at)}): ${s.lastBackup.message}</div></div>`);
    if (s.lastRestart && !s.lastRestart.ok && !/^overgeslagen: de server stond uit/.test(s.lastRestart.message)) {
      alerts.push(html`<div class="alert warning">${icon('warn')} <div><b>De laatste geplande herstart</b> (${dateTime(s.lastRestart.at)}): ${s.lastRestart.message}</div></div>`);
    }
    if (d.disk.free >= 0 && d.disk.free < 2 * 1024 ** 3) alerts.push(html`<div class="alert warning">${icon('disk')} <div>Nog maar ${bytes(d.disk.free)} vrij op de schijf. Verwijder oude backups of bewaar er minder.</div></div>`);
    return html`${alerts.length ? html`<div class="stack" style="margin-bottom:16px">${alerts}</div>` : ''}
      <div class="grid stats server-stats">
        <div class="card stat"><div class="stat-label">Volgende backup</div><div class="stat-icon">${icon('calendar')}</div>
          <div class="stat-value compact">${d.nextBackup ? dateTime(d.nextBackup) : 'Uit'}</div>
          <div class="stat-sub">${s.lastBackup ? html`vorige ${resultLine(s.lastBackup)}` : s.backup.enabled ? `bewaart ${num(s.backup.keep)} automatische backups` : 'automatische backups staan uit'}</div></div>
        <div class="card stat"><div class="stat-label">Nieuwste backup</div><div class="stat-icon">${icon('backup')}</div>
          <div class="stat-value compact">${newest ? ago(newest.manifest.created) : 'Nog geen'}</div>
          <div class="stat-sub">${newest ? `${bytes(newest.size)} · ${triggerLabel(newest.manifest.trigger)[0].toLowerCase()}` : 'maak er nu een'}</div></div>
        <div class="card stat"><div class="stat-label">Opslag</div><div class="stat-icon">${icon('disk')}</div>
          <div class="stat-value compact">${bytes(d.total)}</div>
          ${d.disk.total > 0 ? html`<div class="meter ${usedPct > 90 ? 'error' : usedPct > 75 ? 'warning' : ''}"><i style="width:${usedPct}%"></i></div>` : ''}
          <div class="stat-sub">${num(d.backups.length)} ${d.backups.length === 1 ? 'backup' : 'backups'}${d.disk.free >= 0 ? ` · ${bytes(d.disk.free)} vrij` : ''}</div></div>
        <div class="card stat"><div class="stat-label">Dagelijkse herstart</div><div class="stat-icon">${icon('restart')}</div>
          <div class="stat-value compact">${s.restart.enabled ? `${s.restart.time} uur` : 'Uit'}</div>
          <div class="stat-sub">${d.nextRestart ? `volgende ${dateTime(d.nextRestart)}` : 'stel hieronder in'}${s.lastRestart && s.lastRestart.ok ? html` · vorige ${resultLine(s.lastRestart)}` : ''}</div></div>
      </div>`;
  }

  // =============================================================== de lijst

  function contents(manifest) {
    if (!manifest) return html`<span class="muted small">—</span>`;
    const parts = [];
    if (manifest.server) parts.push(html`<span class="badge plain" title="${num(manifest.server.files)} bestanden, ${bytes(manifest.server.bytes)}">${icon('cube')} Server</span>`);
    if (manifest.website) parts.push(html`<span class="badge plain" title="${num(manifest.website.files)} bestanden">${icon('web')} Website</span>`);
    if (manifest.databases && manifest.databases.length) parts.push(html`<span class="badge plain" title="${manifest.databases.join(', ')}">${icon('database')} ${manifest.databases.length === 1 ? manifest.databases[0] : `${manifest.databases.length} databases`}</span>`);
    return html`<div class="backup-parts">${parts.length ? parts : html`<span class="muted small">leeg</span>`}</div>`;
  }

  function listView(d) {
    const admin = isAdmin();
    const rows = d.backups.length ? d.backups.map(b => {
      const m = b.manifest;
      const [label, cls] = b.foreign ? ['Geen backup van het paneel', 'error'] : b.uploaded ? ['Geüpload', 'info'] : triggerLabel(m.trigger);
      const when = m ? m.created : b.modified;
      return html`<tr>
        <td><div class="backup-when"><b>${dateTime(when)}</b> <span class="badge ${cls}">${label}</span>${b.pinned ? html` <span class="badge warning" title="Wordt niet automatisch opgeruimd">${icon('pin')} Vast</span>` : ''}</div>
          <div class="muted small mono backup-name">${b.name}</div>
          ${m && m.note ? html`<div class="small backup-note">${m.note}</div>` : ''}
          ${m && (m.by || m.minecraft) ? html`<div class="muted small">${m.by ? `door ${m.by}` : ''}${m.by && m.minecraft ? ' · ' : ''}${m.minecraft ? `Minecraft ${m.minecraft}` : ''}</div>` : ''}
          ${m && m.warnings && m.warnings.length ? html`<div class="small warning-text">${icon('warn')} ${m.warnings.join(' ')}</div>` : ''}</td>
        <td class="hide-sm">${contents(m)}</td>
        <td class="nowrap">${bytes(b.size)}</td>
        <td class="actions">
          <a class="btn sm" href="/api${backupUrl(b.name, 'download')}" download title="Downloaden" aria-label="${b.name} downloaden">${icon('download')}<span>Downloaden</span></a>
          ${admin && m ? html`<button class="btn sm" data-action="backup-restore" data-name="${b.name}" ${d.busy ? 'disabled' : ''}>${icon('undo')}<span>Terugzetten</span></button>` : ''}
          ${m ? html`<button class="btn sm ghost icon-only ${b.pinned ? 'active' : ''}" data-action="backup-pin" data-name="${b.name}" data-pinned="${b.pinned ? '1' : ''}" title="${b.pinned ? 'Losmaken (mag weer automatisch opgeruimd worden)' : 'Vastzetten (nooit automatisch opruimen)'}" aria-label="${b.pinned ? 'Losmaken' : 'Vastzetten'}">${icon('pin')}</button>` : ''}
          ${admin ? html`<button class="btn sm danger icon-only" data-action="backup-delete" data-name="${b.name}" title="Verwijderen" aria-label="${b.name} verwijderen">${icon('trash')}</button>` : ''}
        </td></tr>`;
    }) : emptyRow(4, 'Nog geen backups. Maak er nu een, of wacht op de eerste automatische.');
    return card('Alle backups', 'backup', html`<div class="table-wrap"><table class="backup-table">
        <thead><tr><th>Backup</th><th class="hide-sm">Inhoud</th><th>Grootte</th><th></th></tr></thead>
        <tbody>${rows}</tbody></table></div>
      <div class="card-body muted small">Backups staan in <span class="mono">/opt/pinda/backups</span> op deze VPS. Download af en toe een backup naar je eigen computer: als de VPS kapotgaat, zijn de backups daar ook weg.${isAdmin() ? ' Een gedownloade backup kun je hier ook weer uploaden en terugzetten.' : ''}</div>`,
      html`<span class="count">${num(d.backups.length)}</span>`);
  }

  // =============================================================== maken, uploaden, vastzetten, verwijderen

  actions['backup-create'] = () => {
    const d = bk.data;
    openModal(html`<form data-form="backup-create">
      <div class="modal-head"><h3>Nu een backup maken</h3><p>De server kan gewoon blijven draaien: opslaan staat heel even uit, zodat de wereld heel in de backup komt.</p></div>
      <div class="modal-body stack">
        <label class="check"><input type="checkbox" checked disabled> Servermap (wereld, plugins, instellingen)</label>
        <label class="check"><input type="checkbox" name="website" checked> Website</label>
        <label class="check"><input type="checkbox" name="databases" ${d.mariadb ? 'checked' : 'disabled'}> Databases (MariaDB)${d.mariadb ? '' : d.mariadbInstalled ? ' — MariaDB draait niet' : ' — niet geïnstalleerd'}</label>
        <label class="field">Notitie <span class="hint">optioneel, bijv. "voor de update naar 26.2"</span><input class="input" name="note" maxlength="200" autocomplete="off"></label>
        <p class="muted small">Niet mee: ${(d.schedule.backup.exclude || []).join(', ') || 'niets'}. Dat haalt de server zelf weer op (of het zijn logs).</p>
      </div>
      <div class="modal-foot"><button type="button" class="btn ghost" data-action="close">Annuleren</button>
        <button class="btn primary">${icon('backup')} Backup maken</button></div></form>`);
  };

  forms['backup-create'] = async (form, data, button) => {
    const job = await busy(button, () => post('/backups', { note: data.get('note') || '', website: !!data.get('website'), databases: !!data.get('databases') }));
    if (job) watchJob(job, () => window.PH.reloadPage());
  };

  actions['backup-upload'] = () => $('#backup-file')?.click();

  actions['backup-pin'] = async button => {
    const pinned = !button.dataset.pinned;
    const result = await busy(button, () => post(backupUrl(button.dataset.name, 'pin'), { pinned }));
    if (!result) return;
    toast(pinned ? 'Vastgezet: deze backup wordt niet automatisch opgeruimd.' : 'Losgemaakt.');
    window.PH.reloadPage();
  };

  actions['backup-delete'] = async button => {
    const name = button.dataset.name;
    const ok = await confirmDialog({ title: 'Backup verwijderen?', text: `${name} wordt definitief weggehaald.`, confirm: 'Verwijderen', danger: true });
    if (!ok) return;
    const result = await busy(button, () => post(backupUrl(name, 'delete')));
    if (!result) return;
    toast('Backup verwijderd.');
    window.PH.reloadPage();
  };

  // =============================================================== terugzetten

  actions['backup-restore'] = button => {
    const d = bk.data;
    const backup = d.backups.find(b => b.name === button.dataset.name);
    if (!backup || !backup.manifest) return;
    const m = backup.manifest;
    const running = d.server.state === 'active' || d.server.state === 'activating';
    const dbs = m.databases || [];
    openModal(html`<form data-form="backup-restore" data-name="${backup.name}">
      <div class="modal-head"><h3>Backup terugzetten</h3><p>${dateTime(m.created)} · ${bytes(backup.size)}${m.note ? ` · ${m.note}` : ''}</p></div>
      <div class="modal-body stack">
        <p class="muted small">Wat je aanvinkt, wordt vervangen door de stand uit de backup. Wat er sindsdien veranderd is, ben je daar kwijt.</p>
        <div class="restore-parts">
          ${m.server ? html`<label class="check"><input type="checkbox" name="server" checked> <span><b>Servermap</b> <span class="muted small">${num(m.server.files)} ${m.server.files === 1 ? 'bestand' : 'bestanden'}, ${bytes(m.server.bytes)}${m.minecraft ? ` · Minecraft ${m.minecraft}` : ''}</span></span></label>` : ''}
          ${m.website ? html`<label class="check"><input type="checkbox" name="website" checked> <span><b>Website</b> <span class="muted small">${num(m.website.files)} ${m.website.files === 1 ? 'bestand' : 'bestanden'}</span></span></label>` : ''}
          ${dbs.map(db => html`<label class="check"><input type="checkbox" name="db" value="${db}" ${d.mariadb ? 'checked' : 'disabled'}> <span><b>Database ${db}</b>${d.mariadb ? '' : html` <span class="muted small">MariaDB draait niet</span>`}</span></label>`)}
        </div>
        ${m.server && running ? html`<div class="alert warning small">${icon('warn')} <div>De server wordt gestopt (de wereld eerst bewaard) en daarna weer gestart. Spelers zijn even van de server.</div></div>` : ''}
        ${m.minecraft && d.server.version && m.minecraft !== d.server.version ? html`<div class="alert info small">${icon('cube')} <div>Deze backup is van Minecraft ${m.minecraft}; nu staat ${d.server.version} erop. Met de servermap komt ook de server.jar van toen terug.</div></div>` : ''}
        <label class="check"><input type="checkbox" name="safety" checked> Eerst een backup van de huidige stand (aangeraden)</label>
        <label class="check"><input type="checkbox" name="start" ${running || !d.server.installed ? 'checked' : ''}> Server daarna starten</label>
      </div>
      <div class="modal-foot"><button type="button" class="btn ghost" data-action="close">Annuleren</button>
        <button class="btn solid-danger">${icon('undo')} Terugzetten</button></div></form>`);
  };

  forms['backup-restore'] = async (form, data, button) => {
    const body = { confirm: true, server: !!data.get('server'), website: !!data.get('website'), databases: data.getAll('db'),
      safety: !!data.get('safety'), start: !!data.get('start') };
    if (!body.server && !body.website && !body.databases.length) { toast('Kies wat je wilt terugzetten.', 'error'); return; }
    const name = form.dataset.name;
    if (!body.safety) {
      // Het formulier verdwijnt voor deze vraag; de keuzes staan al in body.
      const ok = await confirmDialog({ title: 'Zonder backup van de huidige stand?', confirm: 'Toch terugzetten', danger: true,
        text: 'Gaat er iets mis of wil je toch terug, dan is de huidige stand weg.' });
      if (!ok) return;
      button = null;
    }
    const job = await busy(button, () => post(backupUrl(name, 'restore'), body));
    if (job) watchJob(job, () => window.PH.reloadPage());
  };

  // =============================================================== planning

  function dayChips(name, days, disabled) {
    const all = !days || !days.length;
    return html`<div class="check-chips day-chips">${DAYS.map(([value, label]) => html`<label class="check-chip"><input type="checkbox" name="${name}" value="${value}" ${all || days.includes(value) ? 'checked' : ''} ${disabled}> ${label}</label>`)}</div>`;
  }

  function timeRow(value, disabled) {
    return html`<div class="time-row"><input class="input" type="time" name="time" value="${value}" required ${disabled} aria-label="Tijd">
      ${disabled ? '' : html`<button type="button" class="btn sm ghost icon-only" data-action="bk-time-remove" title="Tijd weghalen" aria-label="Tijd weghalen">${icon('x')}</button>`}</div>`;
  }

  function timezones(current) {
    let zones = [];
    try { zones = Intl.supportedValuesOf('timeZone'); } catch { /* oudere browser */ }
    const browser = Intl.DateTimeFormat().resolvedOptions().timeZone;
    for (const zone of [current, browser, 'Europe/Amsterdam', 'Europe/Brussels', 'UTC']) if (zone && !zones.includes(zone)) zones.unshift(zone);
    return zones;
  }

  function scheduleView(d) {
    const s = d.schedule;
    const b = s.backup;
    const r = s.restart;
    const admin = isAdmin();
    const off = admin ? '' : 'disabled';
    const times = b.times && b.times.length ? b.times : ['04:00'];
    return html`<div class="grid two">
      ${card('Automatische backups', 'calendar', html`<div class="card-body stack">
        <label class="switch-row"><span><b>Automatisch backups maken</b><span class="muted small">Ook als niemand inlogt; de oudste automatische backups worden opgeruimd.</span></span>
          <span class="switch"><input type="checkbox" name="backupEnabled" ${b.enabled ? 'checked' : ''} ${off}><span></span></span></label>
        <div class="schedule-body" data-for="backupEnabled">
          <div class="seg" role="radiogroup" aria-label="Wanneer">
            <label><input type="radio" name="mode" value="daily" ${b.mode !== 'interval' ? 'checked' : ''} ${off}><span>Op vaste tijden</span></label>
            <label><input type="radio" name="mode" value="interval" ${b.mode === 'interval' ? 'checked' : ''} ${off}><span>Elke paar uur</span></label>
          </div>
          <div class="mode-daily" ${b.mode === 'interval' ? 'hidden' : ''}>
            <div class="field-label muted small">Tijden</div>
            <div class="time-list" id="time-list">${times.map(t => timeRow(t, off))}</div>
            ${admin ? html`<button type="button" class="btn sm ghost" data-action="bk-time-add">${icon('plus')} Tijd erbij</button>` : ''}
          </div>
          <label class="field mode-interval" ${b.mode === 'interval' ? '' : 'hidden'}>Elke
            <select name="every" ${off}>${EVERY.map(n => html`<option value="${n}" ${n === b.every ? 'selected' : ''}>${n === 1 ? 'uur' : `${n} uur`} (${Array.from({ length: Math.min(4, 24 / n) }, (_, i) => `${String(i * n).padStart(2, '0')}:00`).join(', ')}${24 / n > 4 ? ', …' : ''})</option>`)}</select></label>
          <div class="field-label muted small">Op deze dagen</div>
          ${dayChips('backupDays', b.days, off)}
          <label class="field">Bewaren <span class="hint">zoveel automatische backups (en zoveel van vóór een herstart); de oudste gaat weg. Handmatige, geüploade en vastgezette blijven staan.</span>
            <input class="input" type="number" name="keep" min="1" max="100" value="${b.keep}" required ${off}></label>
          <label class="check"><input type="checkbox" name="website" ${b.website ? 'checked' : ''} ${off}> Website erbij</label>
          <label class="check"><input type="checkbox" name="databases" ${b.databases ? 'checked' : ''} ${off}> Databases erbij (MariaDB)</label>
          <details class="advanced"><summary>Overslaan in de servermap</summary>
            <label class="field">Eén pad per regel, vanaf de servermap. Standaard: ${d.defaultExclude.join(', ')}.
              <textarea class="input mono" name="exclude" rows="5" spellcheck="false" ${off}>${(b.exclude || []).join('\n')}</textarea></label>
          </details>
        </div></div>`)}
      ${card('Dagelijkse herstart', 'restart', html`<div class="card-body stack">
        <label class="switch-row"><span><b>Elke dag herstarten</b><span class="muted small">Houdt de server fris (geheugen, lag). Staat de server uit, dan gebeurt er niets.</span></span>
          <span class="switch"><input type="checkbox" name="restartEnabled" ${r.enabled ? 'checked' : ''} ${off}><span></span></span></label>
        <div class="schedule-body" data-for="restartEnabled">
          <label class="field">Tijd<input class="input time-input" type="time" name="restartTime" value="${r.time}" required ${off}></label>
          <div class="field-label muted small">Op deze dagen</div>
          ${dayChips('restartDays', r.days, off)}
          <label class="check"><input type="checkbox" name="warn" ${r.warn ? 'checked' : ''} ${off}> Spelers waarschuwen (5 minuten, 1 minuut en 10 seconden van tevoren)</label>
          <label class="check"><input type="checkbox" name="backupFirst" ${r.backupFirst ? 'checked' : ''} ${off}> Eerst een backup maken</label>
          <p class="muted small">Tip: zet de herstart een uur na de nachtelijke backup, of vink "eerst een backup" aan.</p>
        </div></div>`)}
    </div>
    <div class="schedule-foot card">
      <label class="field">Tijdzone <span class="hint">voor alle tijden hierboven</span>
        <select name="timezone" ${off}>${timezones(s.timezone).map(zone => html`<option value="${zone}" ${zone === s.timezone ? 'selected' : ''}>${zone.replace(/_/g, ' ')}</option>`)}</select></label>
      ${admin ? html`<button class="btn primary">${icon('save')} Planning opslaan</button>` : html`<p class="muted small">Alleen een beheerder kan de planning aanpassen.</p>`}
    </div>`;
  }

  function bindSchedule(main) {
    const form = $('form[data-form="backup-schedule"]', main);
    if (!form) return;
    const sync = () => {
      for (const body of $$('.schedule-body', form)) {
        const toggle = form.elements[body.dataset.for];
        body.classList.toggle('off', !toggle.checked);
      }
      const interval = form.elements.mode.value === 'interval';
      $('.mode-daily', form).hidden = interval;
      $('.mode-interval', form).hidden = !interval;
    };
    form.addEventListener('change', sync);
    sync();
  }

  actions['bk-time-add'] = () => {
    const list = $('#time-list');
    if (!list) return;
    if ($$('input', list).length >= 6) { toast('Hooguit 6 tijden.', 'error'); return; }
    const used = $$('input', list).map(input => input.value);
    const next = ['04:00', '12:00', '20:00', '16:00', '08:00', '00:00'].find(t => !used.includes(t)) || '12:00';
    list.insertAdjacentHTML('beforeend', String(timeRow(next, '')));
  };

  actions['bk-time-remove'] = button => {
    const list = $('#time-list');
    if ($$('input', list).length <= 1) { toast('Er moet minstens één tijd zijn (of zet automatische backups uit).', 'error'); return; }
    button.closest('.time-row').remove();
  };

  const daysOf = (form, name) => {
    const days = $$(`input[name="${name}"]:checked`, form).map(input => Number(input.value));
    return days.length === 7 ? [] : days;
  };

  forms['backup-schedule'] = async (form, data, button) => {
    const backupDays = daysOf(form, 'backupDays');
    const restartDays = daysOf(form, 'restartDays');
    if (!$$('input[name="backupDays"]:checked', form).length || !$$('input[name="restartDays"]:checked', form).length) {
      toast('Kies minstens één dag.', 'error');
      return;
    }
    const schedule = {
      timezone: data.get('timezone'),
      backup: {
        enabled: !!data.get('backupEnabled'), mode: data.get('mode'), times: data.getAll('time').filter(Boolean), every: Number(data.get('every')),
        days: backupDays, keep: Number(data.get('keep')), website: !!data.get('website'), databases: !!data.get('databases'),
        exclude: String(data.get('exclude') || '').split('\n').map(line => line.trim()).filter(Boolean)
      },
      restart: {
        enabled: !!data.get('restartEnabled'), time: data.get('restartTime'), days: restartDays,
        warn: !!data.get('warn'), backupFirst: !!data.get('backupFirst')
      }
    };
    const result = await busy(button, () => post('/backups/schedule', schedule));
    if (!result) return;
    toast('Planning opgeslagen.');
    bk.data = result;
    render($('#backup-status'), statusView(result));
  };
})();
