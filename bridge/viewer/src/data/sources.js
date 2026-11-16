// Data sources. The viewer only talks to a source through this interface, so live mode
// (served by the plugin at /view/, see bridge/docs/api.md) can replace the offline one.
//
// interface JobSource {
//   kind: 'offline' | 'url' | 'live'
//   load(): Promise<{ gcode: {name, text}|null, sidecar: object|null, profile: object|null }>
//   connection?: LiveConnection           // live only: GET calls and WS /events (src/live/connection.js)
// }

import { isProfile } from './profile.js';
import { LiveConnection } from '../live/connection.js';

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
      // D12: try "<stem>.job.json", then "<file>.nc.job.json" next to the file; absence is fine
      for (const guess of sidecarCandidates(this.file)) {
        let j;
        try { j = await get(guess, true); } catch { continue; }
        sidecar = checkSidecar(j, guess); // a sidecar that exists but is invalid is an error, not a silent skip
        break;
      }
    }
    const profile = this.profile ? await get(this.profile, true) : null;
    return { gcode, sidecar, profile };
  }
}

/**
 * Live source: the program, profile and job data from a running bridge (bridge/docs/api.md), with a
 * read-only viewer token sent as `Authorization: Bearer v.<token>`. Two ways in:
 *   /view/#vt=<token>                           served by the plugin (same origin)
 *   ?live=http://127.0.0.1:<port>#vt=<token>    dev: viewer served elsewhere (the bridge must allow CORS)
 * `vt` may also be a query parameter, but the hash keeps it out of server logs and Referer headers.
 */
export class LiveSource {
  kind = 'live';
  /** @param {{base?: string, token: string, origin?: string|null}} o */
  constructor({ base = '/api/bridge/v1', token, origin = null }) {
    this.base = base;
    this.token = token;
    this.origin = origin; // dev `?live=` origin, or null when same-origin
    this.connection = new LiveConnection({ base, token, onRecord: () => {} });
  }

  /** From the page URL; null when not in live mode. Throws on a `live=` URL that isn't loopback. */
  static fromLocation(loc = window.location) {
    const hash = new URLSearchParams(loc.hash.replace(/^#/, ''));
    const query = new URLSearchParams(loc.search);
    const token = hash.get('vt') ?? query.get('vt');
    const live = query.get('live');
    if (!token) return null;
    if (live) {
      const u = new URL(live);
      if (!/^https?:$/.test(u.protocol) || !['127.0.0.1', 'localhost', '[::1]'].includes(u.hostname)) {
        throw new Error(`live=${live}: only a loopback bridge (127.0.0.1) is allowed`);
      }
      return new LiveSource({ base: `${u.origin}/api/bridge/v1`, token, origin: u.origin });
    }
    return new LiveSource({ token });
  }

  status() { return this.connection.get('/status'); }
  profile() { return this.connection.get('/profile'); }
  fileInfo() { return this.connection.getOptional('/file'); }
  fileContent() { return this.connection.get('/file/content', { text: true }); }
  origins() { return this.connection.getOptional('/origins'); }
  jobState() { return this.connection.getOptional('/job/state'); }

  /** The loaded program (null when UGS has none) with its metadata. */
  async loadProgram() {
    const info = await this.fileInfo();
    if (!info) return { gcode: null, fileInfo: null };
    const text = await this.fileContent();
    return { gcode: { name: info.path.split(/[\\/]/).pop(), text, path: info.path, sha256: info.sha256 }, fileInfo: info };
  }

  /**
   * Everything the viewer needs to draw the loaded job. The sidecar isn't available over the API
   * (open question for package E), so the job model comes from the post headers.
   */
  async load() {
    const [status, profile, program, origins, jobState] = await Promise.all([
      this.status(),
      this.profile().catch(() => null),
      this.loadProgram(),
      this.origins().catch(() => null),
      this.jobState().catch(() => null),
    ]);
    return { gcode: program.gcode, fileInfo: program.fileInfo, sidecar: null, profile, status, origins, jobState };
  }
}

/**
 * Sidecar names to try for a program path or URL, in order (decision D12, post-headers.md §5):
 * canonical `<stem>.job.json`, then `<file>.nc.job.json`.
 */
export function sidecarCandidates(file) {
  const q = file.search(/[?#]/);
  const p = q < 0 ? file : file.slice(0, q);
  const rest = q < 0 ? '' : file.slice(q);
  const stem = p.replace(/\.[^./]+$/, '');
  const out = [`${stem}.job.json${rest}`];
  if (stem !== p) out.push(`${p}.job.json${rest}`);
  return out;
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
