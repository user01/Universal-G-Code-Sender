// Drives the material-removal worker: (re)initialises it for a program, then follows the scrubber.
// Only the latest requested position is sent (requests coalesce while the worker is busy).
import { buildSimInput } from '../sim/simjob.js';

export class SimController {
  /**
   * @param {import('../scene/viewer.js').Viewer} viewer
   * @param {(status: {state:string, text:string, skipped?:object[]}) => void} onStatus
   */
  constructor(viewer, onStatus = () => {}) {
    this.viewer = viewer;
    this.onStatus = onStatus;
    this.worker = null;
    this.ready = false;
    this.busy = false;
    this.pending = null;
    this.sent = null;
    this.reqId = 0;
    this.input = null;
    this.status = { state: 'off', text: '' };
  }

  /** Start a simulation for this program/job; `cell` overrides the cell size (?cell=). */
  start(program, job, { cell } = {}) {
    this.stop();
    const input = buildSimInput(program, job, { cell });
    if (!input.ok) {
      this._status({ state: 'off', text: `Stock not simulated: ${input.reason}` });
      this.viewer.setSim(null);
      return;
    }
    this.input = input;
    this.viewer.setSim(input);
    const skippedText = input.skipped.length
      ? ` · NOT simulated: ${input.skipped.map((s) => `${s.tool} (${s.reason})`).join(', ')}, so its cuts are missing from the stock`
      : '';
    if (!input.cutting) {
      this._status({ state: 'empty', text: `No simulated cuts${skippedText}`, skipped: input.skipped });
      return;
    }
    let worker;
    try {
      worker = new Worker(new URL('../sim/sim-worker.js', import.meta.url), { type: 'module' });
    } catch (e) {
      this._status({ state: 'error', text: `Stock simulation unavailable: ${e.message}` });
      this.viewer.setSim(null);
      return;
    }
    this.worker = worker;
    this._status({ state: 'computing', text: `Simulating the stock (${input.nx} × ${input.ny} cells at ${input.cs} mm)…${skippedText}`, skipped: input.skipped });
    worker.onmessage = (e) => this._onMessage(e.data, skippedText);
    worker.onerror = (e) => { console.error('sim worker', e.message); this._status({ state: 'error', text: `Stock simulation failed: ${e.message}` }); };
    const { nx, ny, cs, top, seg, rad, prof, profs } = input;
    worker.postMessage({ type: 'init', nx, ny, cs, top, seg, rad, prof, profs });
  }

  _onMessage(m, skippedText) {
    if (m.type === 'progress') {
      this._status({ ...this.status, state: 'computing', text: `Simulating the stock… ${Math.round(m.frac * 100)}%${skippedText}` });
    } else if (m.type === 'ready') {
      this.ready = true;
      this._status({ state: 'ready', text: `Stock simulated in ${m.ms < 1000 ? `${Math.max(1, Math.round(m.ms))} ms` : `${(m.ms / 1000).toFixed(1)} s`} (${this.input.cs} mm cells)${skippedText}`, skipped: this.input.skipped });
      this._pump();
    } else if (m.type === 'state') {
      this.busy = false;
      if (m.rect) this.viewer.applySimRect(m.rect);
      this._pump();
    }
  }

  /** Show the stock as cut after `segCount` complete segments plus `frac` of the next. */
  goto(segCount, frac = 0) {
    if (!this.input) return;
    this.pending = { i: segCount, f: frac };
    this._pump();
  }

  _pump() {
    if (!this.worker || !this.ready || this.busy || !this.pending) return;
    const p = this.pending;
    this.pending = null;
    if (this.sent && this.sent.i === p.i && this.sent.f === p.f) return;
    this.busy = true;
    this.sent = p;
    this.worker.postMessage({ type: 'goto', i: p.i, f: p.f, id: ++this.reqId });
  }

  /** Resolves when the worker has caught up with the last request (for tests and screenshots). */
  async settled(timeoutMs = 20000) {
    const t0 = Date.now();
    while (this.worker && (!this.ready || this.busy || this.pending)) {
      if (Date.now() - t0 > timeoutMs) throw new Error('simulation did not settle');
      await new Promise((r) => setTimeout(r, 30));
    }
  }

  stop() {
    this.worker?.terminate();
    this.worker = null;
    this.ready = false;
    this.busy = false;
    this.pending = null;
    this.sent = null;
    this.input = null;
  }

  _status(s) { this.status = s; this.onStatus(s); }
}
