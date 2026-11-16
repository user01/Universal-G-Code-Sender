// UI controller: wires the data sources, the pure G-code model and the scene.
import { parseGcode, lastSegAtOrBefore, lineAtTime, KIND, FLAG } from '../gcode/parser.js';
import { parseHeader, toolId } from '../gcode/header.js';
import { buildJob, analyzeWarnings, opCutBounds } from '../gcode/job.js';
import { normalizeProfile, defaultWorkOffset } from '../data/profile.js';
import { OfflineFileSource } from '../data/sources.js';
import { opColor } from '../scene/toolpath.js';

const $ = (id) => document.getElementById(id);
const fmt = (v, d = 2) => (v === undefined || v === null || Number.isNaN(v) ? '–' : (Math.round(v * 10 ** d) / 10 ** d).toString());
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const mmss = (sec) => `${Math.floor(sec / 60)}:${String(Math.floor(sec % 60)).padStart(2, '0')}`;

export class App {
  /** @param {import('../scene/viewer.js').Viewer} viewer */
  constructor(viewer, profile) {
    this.viewer = viewer;
    this.profile = profile;
    this.viewer.setProfile(profile);
    this.viewer.setView('machine');
    this.wco = null;
    this.wcoUser = false;
    this.wcoSource = 'assumed'; // 'assumed' (offline default) | 'user' (typed in) | 'live' (machine WCO)
    this.pinnedLine = null;
    this.scrubLine = 0;
    this.playing = false;
    this._bindUi();
    this._stats();
  }

  // ---------------------------------------------------------------------------------- loading

  async openSource(source) {
    this._error(null);
    try {
      const { gcode, sidecar, profile } = await source.load();
      if (profile) {
        this.profile = normalizeProfile(profile, 'loaded file');
        this.viewer.setProfile(this.profile);
      }
      if (gcode) this.loadJob(gcode, sidecar);
      else if (sidecar && this.gcode) this.loadJob(this.gcode, sidecar);
      else if (profile && this.gcode) this.loadJob(this.gcode, this.sidecar);
      else if (!gcode) throw new Error('No G-code file in the selection.');
    } catch (e) {
      console.error(e);
      this._error(e.message || String(e));
    }
  }

  loadJob(gcode, sidecar = null, { wco, wcoSource, keepView = false } = {}) {
    const t0 = performance.now();
    this.gcode = gcode;
    this.sidecar = sidecar;
    const first = parseGcode(gcode.text);
    const header = parseHeader(first.lines);
    const firstJob = buildJob({ program: first, header, sidecar });
    if (wco) { this.wco = wco; this.wcoUser = true; this.wcoSource = wcoSource ?? 'user'; } else if (!this.wcoUser || !this.wco) { this.wco = defaultWorkOffset(this.profile, firstJob.stock); this.wcoSource = 'assumed'; }
    this.header = header;
    this._rebuild(first);
    this.timing = { parse: performance.now() - t0 };
    $('file-name').textContent = gcode.name + (sidecar ? ' + sidecar' : '');
    $('empty').hidden = true;
    for (const id of ['job-section', 'wco-section', 'ops-section', 'warn-section', 'view-section', 'timeline']) $(id).hidden = false;
    this._fillJobInfo();
    this._fillLegend();
    this._setupScrub();
    this._setupSection();
    if (!keepView) this.viewer.setView('iso');
    this._stats();
    document.title = `${gcode.name} · ugs-bridge viewer`;
  }

  /** (Re)build program-dependent state for the current WCO. `parsed` may be reused if it has no G53 moves. */
  _rebuild(parsed) {
    const hasG53 = parsed && Array.from(parsed.seg.flags).some((f) => f & FLAG.MACHINE);
    const program = parsed && !hasG53 ? parsed : parseGcode(this.gcode.text, { wco: this.wco, rapidRate: this.header.rapidRate ?? 5000 });
    const job = buildJob({ program, header: this.header, sidecar: this.sidecar });
    const warnings = analyzeWarnings({ program, job, wco: this.wco, envelope: this.profile.envelope });
    Object.assign(this, { program, job, warnings });
    this.opBounds = opCutBounds(program, job);

    const b = { min: [Infinity, Infinity, Infinity], max: [-Infinity, -Infinity, -Infinity] };
    const add = (mn, mx) => { for (let i = 0; i < 3; i++) { b.min[i] = Math.min(b.min[i], mn[i]); b.max[i] = Math.max(b.max[i], mx[i]); } };
    if (program.cutBounds) add(program.cutBounds.min, program.cutBounds.max);
    if (job.stock) add(job.stock.min, job.stock.max);
    if (b.min[0] === Infinity) add([0, 0, 0], [10, 10, 10]);
    this.viewer.setJobBounds(b);

    // spoilboard: the profile's measured spoilboard_z_machine when set, else under the stock
    const spoilMeasured = typeof this.profile.spoilboardZ === 'number';
    const spoilZ = spoilMeasured ? this.profile.spoilboardZ : this.wco[2] + (job.stock ? job.stock.min[2] : job.floor ? job.floor.z : -10);
    this.viewer.setJob(program, job, warnings);
    this.viewer.setWorkOffset(this.wco, spoilZ);
    $('wco-tag').textContent = this.wcoSource;
    $('wco-tag').className = `tag ${this.wcoSource === 'live' ? 'live' : 'warn'}`;
    this.viewer.setZeroLabel(`G54 zero (${this.wcoSource})\nWCO X${fmt(this.wco[0], 1)} Y${fmt(this.wco[1], 1)} Z${fmt(this.wco[2], 1)}`);
    $('wco-x').value = fmt(this.wco[0], 3); $('wco-y').value = fmt(this.wco[1], 3); $('wco-z').value = fmt(this.wco[2], 3);
    $('spoil-z').textContent = `spoilboard at machine Z${fmt(spoilZ, 1)} (${spoilMeasured ? 'profile' : 'under the stock'})`;
    this._applyToggles();
    this._fillWarnings();
    if (this.scrubLine) this.scrubTo(this.scrubLine);
  }

  setWorkOffset(wco, { source = 'user' } = {}) {
    this.wco = wco;
    this.wcoUser = true;
    this.wcoSource = source;
    if (this.gcode) { this._rebuild(null); this._stats(); this.viewer.setView(this.viewer.view ?? 'iso'); }
  }

  // ---------------------------------------------------------------------------------- panels

  _fillJobInfo() {
    const { job, header, program } = this;
    const rows = [];
    const add = (k, v) => rows.push(`<dt>${esc(k)}</dt><dd>${v}</dd>`);
    if (header.project) add('Project', esc(header.project.length > 120 ? `${header.project.slice(0, 117)}…` : header.project));
    if (job.stock) {
      const s = job.stock;
      add('Stock', `${s.size.map((v) => fmt(v)).join(' × ')} mm${s.material ? ` ${esc(s.material)}` : ''} <span class="hint">(${esc(s.source)}${s.approx ? ', approx.' : ''})</span>`);
      add('Stock origin', `${esc(s.origin)}${s.originAssumed ? ' <span class="tag warn">assumed</span>' : ''}`);
    } else add('Stock', '<span class="hint">unknown (no STOCK header or sidecar)</span>');
    if (job.zero?.text) add('Zero', esc(job.zero.text));
    else if (job.zero) add('Zero', esc(Object.entries(job.zero).map(([k, v]) => `${k}: ${v}`).join(', ')));
    if (job.clearance) add('Clearance', `Z${fmt(job.clearance.z)} (${esc(job.clearance.source)})`);
    if (job.floor) add('Floor', `Z${fmt(job.floor.z, 3)} <span class="hint">(${esc(job.floor.source)})</span>`);
    const tools = [...job.tools.values()].map((t) => `<b>${esc(t.id)}</b> ${t.diameter ? `⌀${fmt(t.diameter, 3)} ` : ''}${esc(t.name ?? t.type ?? '')}`);
    add('Tools', tools.join('<br>') || '–');
    if (header.cutExtent) {
      const c = header.cutExtent;
      add('Cut extent', `X ${c.x[0]}..${c.x[1]} Y ${c.y[0]}..${c.y[1]} Zmin ${c.zmin} <span class="hint">(header)</span>`);
    }
    const est = `${mmss(program.totalTime)} estimated${header.estRuntimeMin ? ` · header ${header.estRuntimeMin} min` : ''}`;
    add('Runtime', `${est} <span class="hint">(feeds, rapids at ${this.header.rapidRate ?? 5000} mm/min, no acceleration)</span>`);
    add('Lines', `${program.lineCount.toLocaleString()} · ${program.seg.count.toLocaleString()} segments`);
    if (program.notes.length) add('Notes', program.notes.slice(0, 5).map((n) => `line ${n.line}: ${esc(n.msg)}`).join('<br>'));
    $('job-info').innerHTML = rows.join('');
  }

  _fillLegend() {
    const { job } = this;
    const ul = $('legend');
    ul.innerHTML = '';
    job.ops.forEach((op, i) => {
      const li = document.createElement('li');
      const t = job.tools.get(op.tool);
      const b = this.opBounds[i];
      li.title = `${op.desc || op.name}\nlines ${op.lineStart}–${op.lineEnd}${b.segs ? `\nZ ${fmt(b.min[2])}..${fmt(b.max[2])}` : ''}${t?.name ? `\n${t.id} ${t.name}` : ''}`;
      li.innerHTML = `<input type="checkbox" checked><span class="sw" style="background:${opColor(i)}"></span>`
        + `<span class="name">${String(op.index).padStart(2, '0')} ${esc(op.name)}</span>`
        + `<span class="meta"><span class="tool">${esc(op.tool !== null ? toolId(op.tool) : '?')}</span> ${op.lineStart}–${op.lineEnd}</span>`;
      li.querySelector('input').addEventListener('change', (e) => { this.viewer.toolpath.setOpVisible(i, e.target.checked); this.viewer.requestRender(); });
      li.querySelector('.name').addEventListener('click', () => this.scrubTo(op.lineEnd));
      li.querySelector('.name').addEventListener('dblclick', () => this.scrubTo(this.program.lineCount));
      ul.appendChild(li);
    });
  }

  _fillWarnings() {
    const { warnings, job } = this;
    const c = warnings.counts;
    const floorTxt = job.floor ? `below floor Z${fmt(job.floor.z, 3)}` : 'below floor (no stock/floor known)';
    $('warn-summary').innerHTML = `<div class="${c.belowFloor ? 'bad' : 'ok'}">${c.belowFloor} cut segments ${esc(floorTxt)}</div>`
      + `<div class="${c.outsideEnvelope ? 'bad' : 'ok'}">${c.outsideEnvelope} segments outside the envelope <span class="hint">(with the ${this.wcoSource} WCO)</span></div>`
      + (c.other ? `<div class="bad">${c.other} program ${c.other === 1 ? 'finding' : 'findings'} (tool changes, op tools)</div>` : '');
    const ul = $('warn-list');
    ul.innerHTML = '';
    for (const w of warnings.items.slice(0, 200)) {
      const li = document.createElement('li');
      li.textContent = `line ${w.line}: ${w.msg}`;
      li.addEventListener('click', () => { this.scrubTo(w.line); this.pin(w.line); });
      ul.appendChild(li);
    }
  }

  // ---------------------------------------------------------------------------------- scrubbing

  _setupScrub() {
    const s = $('scrub');
    const mode = $('scrub-mode').value;
    if (mode === 'line') { s.min = 0; s.max = this.program.lineCount; s.step = 1; s.value = this.program.lineCount; } else { s.min = 0; s.max = Math.ceil(this.program.totalTime); s.step = 1; s.value = s.max; }
    this.scrubTo(this.program.lineCount, { fromSlider: false });
  }

  /** Show the program up to and including `line`. */
  scrubTo(line, { fromSlider = false } = {}) {
    if (!this.program) return;
    line = Math.max(0, Math.min(this.program.lineCount, Math.round(line)));
    this.scrubLine = line;
    const seg = lastSegAtOrBefore(this.program.seg.line, line);
    this.viewer.toolpath.setScrub(seg);
    const t = this.program.lineTime[line] ?? 0;
    if (!fromSlider) $('scrub').value = $('scrub-mode').value === 'line' ? line : Math.round(t);
    // the op that contains the line itself (D11: the tool-change block between two ops is in neither)
    const opI = this.job.ops.findIndex((o) => line >= o.lineStart && line <= o.lineEnd);
    const op = this.job.ops[opI];
    const opText = op ? ` · ${String(op.index).padStart(2, '0')} ${op.name} ${toolId(op.tool)}` : this.job.ops.length && line >= this.job.ops[0].lineStart && line < this.program.lineCount ? ' · between operations' : '';
    $('scrub-readout').textContent = `line ${line.toLocaleString()} / ${this.program.lineCount.toLocaleString()} · ${mmss(t)} / ${mmss(this.program.totalTime)}${opText}`;
    document.querySelectorAll('#legend li').forEach((li, i) => li.classList.toggle('current', i === opI && line < this.program.lineCount));
    this.viewer.requestRender();
  }

  _onSlider() {
    const v = Number($('scrub').value);
    const line = $('scrub-mode').value === 'line' ? v : lineAtTime(this.program.lineTime, v);
    this.scrubTo(line, { fromSlider: true });
  }

  togglePlay() {
    if (!this.program) return;
    this.playing = !this.playing;
    $('play').textContent = this.playing ? '❚❚' : '▶';
    if (!this.playing) return;
    if (this.scrubLine >= this.program.lineCount) this.scrubTo(0);
    let last = performance.now();
    const speed = this.program.totalTime / 60; // whole job in about a minute
    let t = this.program.lineTime[this.scrubLine] ?? 0;
    const step = (now) => {
      if (!this.playing) return;
      t += ((now - last) / 1000) * Math.max(speed, 1);
      last = now;
      const line = t >= this.program.totalTime ? this.program.lineCount : lineAtTime(this.program.lineTime, t);
      this.scrubTo(line);
      if (line >= this.program.lineCount) { this.playing = false; $('play').textContent = '▶'; return; }
      requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }

  // ---------------------------------------------------------------------------------- section

  _setupSection() {
    const s = $('section-z');
    const lo = Math.min(this.program.cutBounds?.min[2] ?? -10, this.job.stock?.min[2] ?? 0) - 0.5;
    const hi = Math.max(this.job.stock?.max[2] ?? 0, this.program.cutBounds?.max[2] ?? 0) + 0.5;
    s.min = fmt(lo, 2); s.max = fmt(hi, 2);
    if (!$('section-on').checked) s.value = s.max;
    this._onSection();
  }

  _onSection() {
    const on = $('section-on').checked;
    $('section-z').disabled = !on;
    const z = Number($('section-z').value);
    $('section-val').textContent = on ? `Z${fmt(z)}` : '';
    this.viewer.setSection(on ? z : null);
  }

  // ---------------------------------------------------------------------------------- hover / pick

  _segInfo(segI, pinned) {
    this._showLine(this.program.seg.line[segI], segI, pinned);
  }

  _showLine(line, segI = -1, pinned = false) {
    const { program, job } = this;
    const box = $('info');
    if (!line) { box.hidden = true; this.viewer.toolpath.highlightLine(null); this.viewer.requestRender(); return; }
    if (segI < 0) segI = Math.max(0, lastSegAtOrBefore(program.seg.line, line));
    // the op that contains the LINE (a line without motion, like an M6, has no segment of its own)
    const opI = job.ops.findIndex((o) => line >= o.lineStart && line <= o.lineEnd);
    const op = job.ops[opI];
    const tc = program.toolChanges.find((x) => x.line === line);
    const k = program.seg.kind[segI];
    const kindName = ['rapid', 'feed', 'arc', 'probe'][k];
    const flags = program.seg.flags[segI];
    const w = this.warnings.flags[segI];
    const o = segI * 6;
    const p = program.seg.pos;
    const from = `X${fmt(p[o], 3)} Y${fmt(p[o + 1], 3)} Z${fmt(p[o + 2], 3)}`;
    const to = `X${fmt(p[o + 3], 3)} Y${fmt(p[o + 4], 3)} Z${fmt(p[o + 5], 3)}`;
    const a = Math.max(1, line - 3), b = Math.min(program.lineCount, line + 3);
    const width = String(b).length;
    const code = [];
    for (let l = a; l <= b; l++) {
      const txt = `<span class="ln">${String(l).padStart(width)}</span>  ${esc(program.lines[l - 1].slice(0, 140))}`;
      code.push(l === line ? `<span class="cur">${txt}</span>` : txt);
    }
    const tags = [kindName, flags & FLAG.PLUNGE ? 'plunge' : null, flags & FLAG.MACHINE ? 'G53' : null, k !== KIND.RAPID ? `F${fmt(program.seg.feed[segI], 0)}` : null].filter(Boolean).join(' · ');
    const warn = w ? `<div class="bad">${w & 1 ? 'below floor ' : ''}${w & 2 ? 'outside envelope' : ''}</div>` : '';
    const where = op ? ` · OP ${String(op.index).padStart(2, '0')} ${esc(op.name)} · ${esc(toolId(op.tool))}` : ` · no operation${tc ? ` (tool change to ${esc(toolId(tc.tool))})` : ''}`;
    box.innerHTML = `<div class="head">Line ${line}${where}${pinned ? ' · pinned (click empty space to clear)' : ''}</div>`
      + `<div class="hint">${program.seg.line[segI] === line ? '' : `no move on this line · last move (line ${program.seg.line[segI]}): `}${tags} · ${from} → ${to} (work)</div>${warn}<pre>${code.join('\n')}</pre>`;
    box.hidden = false;
    this.viewer.toolpath.highlightLine(line);
    this.viewer.requestRender();
  }

  pin(line) {
    this.pinnedLine = line;
    this._showLine(line, -1, true);
  }

  // ---------------------------------------------------------------------------------- export

  snapshotPng() {
    const url = this.viewer.snapshot();
    this._download(url, `${(this.gcode?.name ?? 'view').replace(/\.[^.]+$/, '')}-line${this.scrubLine}.png`);
  }

  async exportGlb() {
    try {
      const buf = await this.viewer.exportGLB();
      const url = URL.createObjectURL(new Blob([buf], { type: 'model/gltf-binary' }));
      this._download(url, `${(this.gcode?.name ?? 'scene').replace(/\.[^.]+$/, '')}.glb`);
      setTimeout(() => URL.revokeObjectURL(url), 10000);
    } catch (e) { this._error(`glTF export failed: ${e.message}`); }
  }

  _download(url, name) {
    const a = document.createElement('a');
    a.href = url; a.download = name;
    document.body.appendChild(a); a.click(); a.remove();
  }

  // ---------------------------------------------------------------------------------- misc

  _applyToggles() {
    const tp = this.viewer.toolpath;
    tp.setClassVisible('rapid', $('show-rapids').checked);
    tp.setClassVisible('plunge', $('show-plunges').checked);
    tp.setMarkersVisible($('show-markers').checked);
    document.querySelectorAll('#legend li input').forEach((cb, i) => tp.setOpVisible(i, cb.checked));
    this.viewer.stockGroup.visible = $('show-stock').checked;
    this.viewer.machine.group.visible = $('show-machine').checked;
    this.viewer.requestRender();
  }

  _error(msg) {
    const e = $('error');
    e.hidden = !msg;
    e.textContent = msg ?? '';
  }

  _stats() {
    const t = this.timing ? `parse+build ${Math.round(this.timing.parse)} ms · ` : '';
    $('stats').textContent = `${t}profile: ${this.profile.name} (${this.profile.source})`;
  }

  _bindUi() {
    $('file-input').addEventListener('change', (e) => { if (e.target.files.length) this.openSource(new OfflineFileSource(e.target.files)); e.target.value = ''; });
    const stage = $('stage');
    let dragDepth = 0;
    stage.addEventListener('dragenter', (e) => { e.preventDefault(); dragDepth++; $('drop-overlay').hidden = false; });
    stage.addEventListener('dragleave', () => { if (--dragDepth <= 0) { dragDepth = 0; $('drop-overlay').hidden = true; } });
    stage.addEventListener('dragover', (e) => e.preventDefault());
    stage.addEventListener('drop', (e) => {
      e.preventDefault(); dragDepth = 0; $('drop-overlay').hidden = true;
      if (e.dataTransfer.files.length) this.openSource(new OfflineFileSource(e.dataTransfer.files));
    });

    $('wco-apply').addEventListener('click', () => this.setWorkOffset([Number($('wco-x').value), Number($('wco-y').value), Number($('wco-z').value)]));
    $('wco-default').addEventListener('click', () => {
      this.wcoUser = false;
      this.wcoSource = 'assumed';
      this.wco = defaultWorkOffset(this.profile, this.job?.stock);
      if (this.gcode) { this._rebuild(null); this.viewer.setView(this.viewer.view ?? 'iso'); }
    });

    $('ops-all').addEventListener('click', () => { document.querySelectorAll('#legend li input').forEach((cb) => { cb.checked = true; }); this._applyToggles(); });
    $('ops-none').addEventListener('click', () => { document.querySelectorAll('#legend li input').forEach((cb) => { cb.checked = false; }); this._applyToggles(); });
    for (const id of ['show-rapids', 'show-plunges', 'show-markers', 'show-stock', 'show-machine']) $(id).addEventListener('change', () => this._applyToggles());

    document.querySelectorAll('[data-view]').forEach((b) => b.addEventListener('click', () => this.viewer.setView(b.dataset.view)));
    $('section-on').addEventListener('change', () => this._onSection());
    $('section-z').addEventListener('input', () => this._onSection());
    $('snap-png').addEventListener('click', () => this.snapshotPng());
    $('export-glb').addEventListener('click', () => this.exportGlb());

    $('scrub').addEventListener('input', () => this._onSlider());
    $('scrub-mode').addEventListener('change', () => { const l = this.scrubLine; this._setupScrub(); this.scrubTo(l); });
    $('play').addEventListener('click', () => this.togglePlay());

    // hover + click on the path
    const canvas = this.viewer.renderer.domElement;
    let pending = null;
    let downAt = null;
    canvas.addEventListener('pointermove', (e) => {
      if (!this.program || e.buttons) return;
      if (pending) { pending = [e.clientX, e.clientY]; return; }
      pending = [e.clientX, e.clientY];
      requestAnimationFrame(() => {
        const [x, y] = pending; pending = null;
        if (this.pinnedLine) return;
        const s = this.viewer.pickSegment(x, y);
        if (s >= 0) this._segInfo(s, false); else this._showLine(null);
      });
    });
    canvas.addEventListener('pointerleave', () => { if (!this.pinnedLine && this.program) this._showLine(null); });
    canvas.addEventListener('pointerdown', (e) => { downAt = [e.clientX, e.clientY]; });
    canvas.addEventListener('pointerup', (e) => {
      if (!this.program || !downAt || Math.hypot(e.clientX - downAt[0], e.clientY - downAt[1]) > 4) return; // a drag, not a click
      const s = this.viewer.pickSegment(e.clientX, e.clientY);
      if (s >= 0) { this.pinnedLine = this.program.seg.line[s]; this._segInfo(s, true); } else { this.pinnedLine = null; this._showLine(null); }
    });

    window.addEventListener('keydown', (e) => {
      if (!this.program || e.target.matches('input[type=number], select')) return;
      if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
        const d = (e.key === 'ArrowRight' ? 1 : -1) * (e.shiftKey ? 100 : 1);
        this.scrubTo(this.scrubLine + d);
        this.pin(Math.max(1, this.scrubLine));
        e.preventDefault();
      } else if (e.key === ' ' && e.target === document.body) { this.togglePlay(); e.preventDefault(); }
      else if (e.key === 'Escape') { this.pinnedLine = null; this._showLine(null); }
    });
  }
}
