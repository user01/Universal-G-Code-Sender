// Live mode controller: connection → reducer (src/live/state.js) → scene layers and panel.
//
// Flow: GET /status (+ /profile, /file, /file/content, /origins, /job/state on the first connect),
// then WS /events?since=…&vt=… . Every record goes through applyRecord(); the UI redraws what
// changed once per animation frame. On a drop the connection retries with backoff, and each retry
// starts with a fresh GET /status and resumes the event stream from the last applied seq.
import { createLiveState, applyStatus, applyJobState, applyRecord, currentLine, streaming, clearTrail } from '../live/state.js';
import { LiveConnection } from '../live/connection.js';
import { LiveLayer } from '../scene/live.js';
import { normalizeProfile } from '../data/profile.js';
import { lastSegAtOrBefore } from '../gcode/parser.js';
import { toolId } from '../gcode/header.js';
import { etaLine } from '../data/estimate.js';
import { readinessView, readinessHtml } from '../twin/readiness.js';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const f3 = (v) => (typeof v === 'number' ? v.toFixed(3) : '–');
const xyz = (p) => (p ? `X${f3(p[0])} Y${f3(p[1])} Z${f3(p[2])}` : '–');
const ALL = ['controller', 'trail', 'wco', 'stream', 'probes', 'banners', 'authority', 'connection', 'file', 'tool', 'job', 'link', 'eta'];

export class LiveController {
  /**
   * @param {import('./app.js').App} app
   * @param {import('../data/sources.js').LiveSource} source
   * @param {{backlog?: number, connection?: object}} [o]  backlog: records to replay on the first connect
   */
  constructor(app, source, { backlog = 2000, connectionOptions = {} } = {}) {
    this.app = app;
    this.viewer = app.viewer;
    this.source = source;
    this.backlog = backlog;
    this.state = createLiveState();
    this.layer = new LiveLayer(this.viewer);
    this.viewer.toolpath.scrubToolEnabled = false; // the live tool and the planned marker replace it
    this.viewer.toolpath.dim = true; // the program path dimmed so the live trail stands out (checkbox)
    this.conn = new LiveConnection({
      base: source.base,
      token: source.token,
      onRecord: (r) => this._onRecord(r),
      onLink: (st, detail) => this._onLink(st, detail),
      onOpenSince: () => this._refresh(),
      ...connectionOptions,
    });
    source.connection = this.conn;
    this.loaded = false;
    this.pending = new Set();
    this._scheduled = false;
    this.follow = true;
    this.stats = { records: 0, flushes: 0 };
    this._bindUi();
  }

  start() {
    $('live-section').hidden = false;
    const badge = $('mode');
    badge.textContent = this.source.origin ? `live · ${this.source.origin.replace(/^https?:\/\//, '')}` : 'live';
    badge.classList.add('live');
    $('wco-tag').textContent = 'live';
    $('wco-tag').className = 'tag live';
    $('wco-hint').textContent = 'The work offset comes from the machine (status WCO and origin records). Typing a value here overrides the view until the next change.';
    this._tick = setInterval(() => { if (this.state.link !== 'online') this._mark('banners'); }, 1000);
    this._etaTick = setInterval(() => { if (this.state.eta) this._mark('eta'); }, 30000); // the finish clock drifts with "now"
    this.conn.start();
  }

  stop() {
    clearInterval(this._tick);
    clearInterval(this._etaTick);
    clearTimeout(this._estTimer);
    this.conn.stop();
  }

  // ------------------------------------------------------------------------------- data

  /** Before every (re)connect: snapshot over HTTP, returns `since` for WS /events. */
  async _refresh() {
    const st = this.state;
    const status = await this.conn.get('/status');
    this._merge(applyStatus(st, status));
    if (!this.loaded) {
      const [profile, origins, jobState, readiness] = await Promise.all([
        this.conn.get('/profile').catch(() => null),
        this.conn.getOptional('/origins').catch(() => null),
        this.conn.getOptional('/job/state').catch(() => null),
        this.conn.getOptional('/twin/readiness').catch(() => null),
      ]);
      this.state.readiness = readiness;
      this._mark('readiness');
      if (profile) {
        try {
          this.app.profile = normalizeProfile(profile, 'GET /profile');
          this.viewer.setProfile(this.app.profile);
          this.app._stats();
        } catch (e) { console.warn('profile', e); }
      }
      this.origins = origins?.origins ?? [];
      this._merge(applyJobState(st, jobState));
      await this._loadProgram();
      this.loaded = true;
      return Math.max(0, (status.seq ?? 0) - this.backlog);
    }
    // reconnect: the program may have changed while we were away
    const info = await this.conn.getOptional('/file').catch(() => null);
    if ((info?.sha256 ?? null) !== (this.programSha ?? null)) await this._loadProgram();
    this._merge(applyJobState(st, await this.conn.getOptional('/job/state').catch(() => null)));
    st.readiness = await this.conn.getOptional('/twin/readiness').catch(() => st.readiness);
    this._mark('readiness');
    return Math.max(st.seq, this.conn.lastSeq);
  }

  async _loadProgram() {
    let program;
    try {
      program = await this.source.loadProgram();
    } catch (e) {
      this.app._error(`Couldn't load the program from the bridge: ${e.message}`);
      return;
    }
    const { gcode, fileInfo } = program;
    this.programSha = fileInfo?.sha256 ?? null;
    if (fileInfo) this.state.file = { path: fileInfo.path, sha256: fileInfo.sha256, lines: fileInfo.lines ?? null };
    if (!gcode) { this._mark('banners', 'file'); return; }
    const wco = this.state.wco ?? undefined;
    this.app.loadJob(gcode, null, wco ? { wco, wcoSource: 'live', keepView: !!this.app.program } : { keepView: !!this.app.program });
    $('file-name').textContent = `${gcode.name} (loaded in UGS)`;
    this._mark(...ALL);
    this._loadEstimate(this.programSha);
  }

  /**
   * GET /file/estimate: the plugin's planner estimate for the loaded program. 404 (older plugin, or
   * no program) keeps the rough estimate; a 202/empty answer while it's still computing is retried.
   */
  async _loadEstimate(sha, attempt = 0) {
    clearTimeout(this._estTimer);
    let est = null;
    try { est = await this.conn.getOptional('/file/estimate'); } catch (e) { console.warn('estimate', e.message); }
    if (sha !== this.programSha) return; // another program was loaded meanwhile
    if (est?.sha256 && sha && est.sha256 !== sha) est = null;
    if (est && !Array.isArray(est.line_end_s) && attempt < 5) {
      this._estTimer = setTimeout(() => this._loadEstimate(sha, attempt + 1), 2000);
      return;
    }
    this.estimate = est;
    this.app.setEstimate(est);
  }

  _onRecord(rec) {
    this.stats.records++;
    const ch = applyRecord(this.state, rec);
    if (ch.has('file') && this.state.file?.sha256 !== this.programSha) this._reloadSoon();
    this._merge(ch);
  }

  _reloadSoon() {
    clearTimeout(this._reloadTimer);
    this._reloadTimer = setTimeout(() => this._loadProgram(), 150);
  }

  _onLink(st, detail) {
    this.state.link = st;
    this.state.linkDetail = detail;
    this._mark('link', 'banners');
  }

  _merge(ch) { for (const c of ch) this.pending.add(c); this._schedule(); }
  _mark(...cs) { for (const c of cs) this.pending.add(c); this._schedule(); }

  _schedule() {
    if (this._scheduled) return;
    this._scheduled = true;
    const run = () => { this._scheduled = false; this._flush(); };
    if (typeof document !== 'undefined' && document.hidden) setTimeout(run, 100);
    else requestAnimationFrame(run);
  }

  // ------------------------------------------------------------------------------- drawing

  _flush() {
    const ch = this.pending;
    this.pending = new Set();
    this.stats.flushes++;
    const st = this.state;
    const app = this.app;

    if (ch.has('wco') && st.wco && app.program && !sameVec(st.wco, app.wco)) {
      app.setWorkOffset(st.wco.slice(), { source: 'live' });
    }
    if (ch.has('controller') || ch.has('tool') || ch.has('stream')) {
      const d = this._toolDiameter();
      if (d) this.layer.setToolDiameter(d);
      const c = st.controller;
      const tool = this._currentTool();
      this.layer.setTool(c?.mpos ?? null, `${c?.state ?? '?'}${tool !== null ? ` · ${toolId(tool)}` : ''}`);
    }
    if (ch.has('trail')) this.layer.setTrail(st.trail);
    if (ch.has('probes')) { this.layer.setProbes(st.probes); this._fillProbes(); }
    if (ch.has('stream') || ch.has('file') || ch.has('job')) this._stream();
    if (ch.has('eta') || ch.has('stream')) this._eta();
    if (ch.size) this._fillInfo();
    if (ch.has('banners') || ch.has('link') || ch.has('connection') || ch.has('file') || ch.has('stream') || ch.has('readiness')) this._banners();
    this.viewer.requestRender();
  }

  _currentTool() {
    if (this.state.tool.current) return Number(/\d+/.exec(this.state.tool.current)?.[0]);
    const app = this.app;
    const cur = currentLine(this.state);
    if (!app.program || !cur) return null;
    const op = app.job.ops.find((o) => cur.line >= o.lineStart && cur.line <= o.lineEnd);
    return op?.tool ?? null;
  }

  _toolDiameter() {
    const t = this._currentTool();
    return t !== null ? this.app.job?.tools.get(t)?.diameter : undefined;
  }

  _stream() {
    const st = this.state;
    const app = this.app;
    const cur = currentLine(st);
    const total = app.program?.lineCount ?? st.stream.linesTotal ?? st.file?.lines ?? null;
    const showBars = !!(total && (cur || streaming(st)));
    for (const id of ['live-progress', 'scrub-progress']) $(id).hidden = !showBars;
    if (showBars) {
      const pct = (v) => `${Math.max(0, Math.min(100, ((v ?? 0) / total) * 100))}%`;
      for (const id of ['live-progress', 'scrub-progress']) {
        $(id).querySelector('.acked').style.width = pct(st.stream.lineAcked ?? st.journal?.line_done);
        $(id).querySelector('.sent').style.width = pct(st.stream.lineSent);
      }
      const ex = $('scrub-progress').querySelector('.exec');
      ex.style.left = pct(cur?.line);
    }
    if (!app.program || !cur) { this.layer.setPlanned(null); return; }
    // planned position: where the program is at the end of the current line (work coordinates)
    const seg = lastSegAtOrBefore(app.program.seg.line, cur.line);
    if (seg >= 0) {
      const p = app.program.seg.pos;
      const o = seg * 6 + 3;
      this.layer.setPlanned([p[o], p[o + 1], p[o + 2]], `planned · line ${cur.line} (${cur.basis})`);
    } else this.layer.setPlanned(null);
    if (this.follow && streaming(st) && cur.line !== app.scrubLine) app.scrubTo(cur.line);
  }

  _eta() {
    const eta = streaming(this.state) ? this.state.eta : null;
    const text = eta ? etaLine(eta) : '';
    for (const id of ['eta-line', 'eta-pill']) { $(id).textContent = text; $(id).hidden = !text; }
    this.app.setLiveEta(eta);
  }

  _fillInfo() {
    const st = this.state;
    const c = st.controller;
    const rows = [];
    const add = (k, v) => rows.push(`<dt>${esc(k)}</dt><dd>${v}</dd>`);
    add('Bridge', `${esc(st.link)}${st.linkDetail ? ` <span class="hint">${esc(st.linkDetail)}</span>` : ''}`);
    add('Controller', st.connected === false ? '<span class="bad">not connected</span>'
      : c ? `<span class="state ${esc(c.state)}">${esc(c.state)}</span>${st.homed === false ? ' <span class="tag warn">not homed</span>' : ''}${st.port ? ` <span class="hint">${esc(st.port)}</span>` : ''}` : '–');
    if (c) {
      add('MPos', `<span class="mono">${xyz(c.mpos)}</span>`);
      add('WPos', `<span class="mono">${xyz(c.wpos ?? (c.mpos && st.wco ? c.mpos.map((v, i) => v - st.wco[i]) : null))}</span>`);
      if (c.feed !== null || c.spindle !== null) add('Feed / S', `<span class="mono">F${c.feed ?? '–'} S${c.spindle ?? '–'}</span>`);
    }
    const origin = st.origin ? `${esc(st.origin.method ?? '')}${st.origin.tool ? ` · ${esc(st.origin.tool)}` : ''}` : '';
    const stored = (this.origins ?? []).find((o) => o.wco && st.wco && sameVec(o.wco, st.wco, 0.01));
    add('WCO', `<span class="mono">${xyz(st.wco)}</span>${origin ? ` <span class="hint">${origin}</span>` : ''}${stored ? ` <span class="hint">= stored “${esc(stored.name ?? stored.id)}”</span>` : ''}`);
    const t = this._currentTool();
    const tool = t !== null ? this.app.job?.tools.get(t) : null;
    add('Tool', t !== null ? `${esc(toolId(t))}${tool?.diameter ? ` ⌀${tool.diameter}` : ''}${st.tool.current ? '' : ' <span class="hint">(from the program)</span>'}` : '–');
    const s = st.stream;
    const total = this.app.program?.lineCount ?? s.linesTotal;
    const cur = currentLine(st);
    add('Stream', `${esc(s.state)}${cur ? ` · line ${cur.line.toLocaleString()}${total ? ` / ${total.toLocaleString()}` : ''} <span class="hint">(${cur.basis}${s.lineSent && s.lineSent !== cur.line ? `, sent ${s.lineSent.toLocaleString()}` : ''})</span>` : ''}`);
    if (st.job?.op || st.journal?.operation) add('Operation', esc(st.job?.op ?? st.journal.operation));
    add('Claude', st.authority.locked ? '<span class="tag link locked">locked out by the operator</span>' : `${esc(st.authority.level ?? '–')}`);
    $('live-info').innerHTML = rows.join('');
    const link = $('live-link');
    link.textContent = st.link;
    link.className = `tag link ${st.link}`;
  }

  _fillProbes() {
    const ul = $('probe-list');
    ul.innerHTML = this.state.probes.slice().reverse().slice(0, 20).map((p, i, arr) => {
      const n = this.state.probes.length - i;
      const w = p.wco ? ` · G54 Z${f3(p.mpos[2] - p.wco[2])} then` : '';
      return `<li class="${p.ok ? '' : 'fail'}">#${n} MZ${f3(p.mpos[2])}${w}${p.ok ? '' : ' no contact'}</li>`;
    }).join('');
  }

  _banners() {
    const st = this.state;
    const out = [];
    const b = (cls, text, sub = '') => out.push(`<div class="banner ${cls}">${esc(text)}${sub ? `<span class="sub">${esc(sub)}</span>` : ''}</div>`);
    const retry = this.conn.retryAt ? Math.max(0, Math.ceil((this.conn.retryAt - Date.now()) / 1000)) : null;
    if (st.link === 'offline') b('offline', 'Bridge offline', retry !== null ? `reconnecting in ${retry} s` : 'reconnecting…');
    else if (st.link === 'locked') b('locked', 'Viewer refused: UGS access is locked by the operator', retry !== null ? `retry in ${retry} s` : '');
    else if (st.link === 'refused') b('locked', 'The bridge refused the event stream', retry !== null ? `retry in ${retry} s` : '');
    else if (st.link === 'unauthorized') b('unauthorized', 'Viewer token rejected', 'UGS restarted? Reopen the viewer from UGS');
    else if (st.link === 'connecting' && this.conn.attempt > 0) b('connecting', 'Reconnecting…');
    if (st.link === 'online' && st.connected === false) b('disconnected', 'UGS isn\'t connected to the controller', 'positions are stale');
    if (st.banners.alarm) b('alarm', `ALARM${st.banners.alarm.code ? ` ${st.banners.alarm.code}` : ''}`, st.banners.alarm.text);
    if (st.banners.toolChange) b('tool', st.banners.toolChange.text, st.banners.toolChange.line ? `line ${st.banners.toolChange.line}` : '');
    if (st.banners.hold) b('hold', st.banners.hold.text);
    if (st.banners.error) b('error', st.banners.error.text);
    if (st.link === 'online' && this.loaded && !this.app.program) b('nofile', 'No program loaded in UGS', 'the live tool is still shown');
    out.push(readinessHtml(readinessView(st.readiness), esc, this._confirming));
    if (this._confirmMsg) b('error', this._confirmMsg);
    // keep the readiness list open across redraws once the operator opened it
    const open = !!document.querySelector('#banners .twin details[open]');
    $('banners').innerHTML = out.join('');
    if (open) document.querySelector('#banners .twin details')?.setAttribute('open', '');
  }

  /** D27: the operator confirms the stock sits where this view shows it (viewer token only). */
  async _confirmStock() {
    this._confirming = true;
    this._confirmMsg = null;
    this._mark('readiness');
    try {
      const r = await this.conn.post('/twin/confirm', {});
      if (r?.readiness) this.state.readiness = r.readiness;
    } catch (e) {
      this._confirmMsg = `Stock not confirmed: ${e?.body?.message ?? e?.message ?? e}`;
    } finally {
      this._confirming = false;
      this._mark('readiness');
    }
  }

  _bindUi() {
    $('banners').addEventListener('click', (e) => {
      if (e.target?.closest?.('[data-action="confirm-stock"]')) this._confirmStock();
    });
    $('live-follow').addEventListener('change', (e) => { this.follow = e.target.checked; this._mark('stream'); });
    $('live-dim').addEventListener('change', (e) => { this.viewer.toolpath.setDim(e.target.checked); this.viewer.requestRender(); });
    $('live-trail').addEventListener('change', (e) => { this.layer.setTrailVisible(e.target.checked); this.viewer.requestRender(); });
    $('live-clear-trail').addEventListener('click', () => { clearTrail(this.state); this._mark('trail'); });
    // dragging the scrubber while streaming stops following until the box is ticked again
    $('scrub').addEventListener('input', () => {
      if (this.follow && streaming(this.state)) { this.follow = false; $('live-follow').checked = false; }
    });
  }
}

function sameVec(a, b, eps = 1e-4) {
  return !!a && !!b && Math.abs(a[0] - b[0]) <= eps && Math.abs(a[1] - b[1]) <= eps && Math.abs(a[2] - b[2]) <= eps;
}
