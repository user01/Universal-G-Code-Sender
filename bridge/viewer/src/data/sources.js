// Data sources. The viewer only talks to a source through this interface, so live mode
// (served by the plugin at /view/, see bridge/docs/api.md) can replace the offline one.
//
// interface JobSource {
//   kind: 'offline' | 'url' | 'live'
//   load(): Promise<{ gcode: {name, text}|null, sidecar: object|null, profile: object|null }>
//   subscribe?(onRecord): () => void      // live only: LogRecords from WS /events
//   status?(): Promise<BridgeStatus>      // live only: GET /status
// }

import { isProfile } from './profile.js';

const GCODE_EXT = /\.(nc|gcode|ngc|tap|gc|cnc|txt)$/i;

/** Files picked or dropped by the user: one G-code file, optional `<program>.job.json`, optional profile JSON. */
export class OfflineFileSource {
  kind = 'offline';
  /** @param {FileList|File[]} files */
  constructor(files) { this.files = Array.from(files); }

  async load() {
    let gcode = null, sidecar = null, profile = null;
    const jsons = [];
    for (const f of this.files) {
      if (/\.json$/i.test(f.name)) jsons.push(f);
      else if (GCODE_EXT.test(f.name) || !gcode) gcode = { name: f.name, text: await f.text() };
    }
    for (const f of jsons) {
      let j;
      try { j = JSON.parse(await f.text()); } catch (e) { throw new Error(`${f.name}: not valid JSON (${e.message})`); }
      if (isProfile(j)) profile = j;
      else sidecar = checkSidecar(j, f.name);
    }
    return { gcode, sidecar, profile };
  }
}

/** Load by URL (query string `?file=…&sidecar=…&profile=…`), relative to the page. Used for screenshots and links. */
export class UrlSource {
  kind = 'url';
  constructor({ file, sidecar, profile }) { Object.assign(this, { file, sidecar, profile }); }

  async load() {
    const get = async (u, asJson) => {
      const r = await fetch(new URL(u, document.baseURI));
      if (!r.ok) throw new Error(`${u}: HTTP ${r.status}`);
      return asJson ? r.json() : r.text();
    };
    const gcode = this.file ? { name: decodeURIComponent(this.file.split('/').pop()), text: await get(this.file) } : null;
    let sidecar = null;
    if (this.sidecar) sidecar = checkSidecar(await get(this.sidecar, true), this.sidecar);
    else if (this.file) {
      // try "<program>.job.json" next to the file; absence is fine
      const guess = this.file.replace(/\.[^./]+$/, '') + '.job.json';
      try { sidecar = checkSidecar(await get(guess, true), guess); } catch { sidecar = null; }
    }
    const profile = this.profile ? await get(this.profile, true) : null;
    return { gcode, sidecar, profile };
  }
}

/**
 * Live source (V2 / package E): served by the plugin at /view/#vt=<token>; read-only viewer token
 * sent as `Authorization: Bearer v.<token>`. V1 implements the GET calls it can use offline-free
 * (profile, file info) and leaves streaming as a stub.
 */
export class LiveSource {
  kind = 'live';
  constructor({ base = '/api/bridge/v1', token }) { this.base = base; this.token = token; }

  /** From `location.hash` (`#vt=<token>`); null when not launched by the plugin. */
  static fromLocation(loc = window.location) {
    const m = /(?:^#|&)vt=([^&]+)/.exec(loc.hash);
    return m ? new LiveSource({ token: decodeURIComponent(m[1]) }) : null;
  }

  async _get(path) {
    const r = await fetch(this.base + path, { headers: { Authorization: `Bearer v.${this.token}` } });
    if (!r.ok) throw new Error(`${path}: HTTP ${r.status}`);
    return r.json();
  }

  status() { return this._get('/status'); }
  profile() { return this._get('/profile'); }
  fileInfo() { return this._get('/file'); }

  async load() {
    // The API has no endpoint that returns the program text yet (GET /file is metadata only).
    // Open question for package E: add GET /file/content (or serve it under /view/).
    const profile = await this.profile().catch(() => null);
    return { gcode: null, sidecar: null, profile };
  }

  /** WS /events. Browsers can't set headers on a WebSocket; the token transport is an open question for package E. */
  subscribe() {
    throw new Error('live events are not implemented in viewer V1');
  }
}

/** Light validation against bridge/schema/job-sidecar.schema.json (required fields only). */
export function checkSidecar(j, name = 'sidecar') {
  if (!j || typeof j !== 'object') throw new Error(`${name}: not an object`);
  if (j.format !== 'ugs-bridge-job/1') throw new Error(`${name}: format must be "ugs-bridge-job/1"`);
  if (j.units !== 'mm' && j.units !== 'in') throw new Error(`${name}: units must be "mm" or "in"`);
  for (const op of j.operations ?? []) {
    if (!Array.isArray(op.lines) || op.lines.length !== 2) throw new Error(`${name}: operation "${op.name}" needs lines [start, end]`);
  }
  return j;
}
