// Live connection to the bridge API (bridge/docs/api.md): authenticated GETs and WS /events with
// reconnect and backoff. Environment-injected (fetch, WebSocket, timers) so Node tests can drive it.
//
// Link states reported through onLink(state, detail):
//   connecting   first attempt or a retry in progress
//   online       WebSocket open, records flowing
//   offline      bridge unreachable (UGS not running, plugin stopped, network error)
//   locked       the bridge refused the viewer (HTTP 423 / close 4423). The API says the lock doesn't
//                apply to viewer tokens, so this only happens if the plugin decides otherwise.
//   unauthorized the viewer token was rejected (401 / close 4401): UGS restarted, reopen the viewer
//   refused      HTTP answers but the WebSocket upgrade failed without a close code

export class HttpError extends Error {
  constructor(status, body, path) {
    super(`${path}: HTTP ${status}${body?.error ? ` ${body.error}` : ''}`);
    this.status = status;
    this.code = body?.error ?? null;
    this.body = body ?? null;
    this.path = path;
  }
}

export const BACKOFF_MS = [500, 1000, 2000, 4000, 8000, 10000];

/** Link state for a failed request or a closed socket. */
export function classifyFailure({ status, closeCode } = {}) {
  if (status === 401 || closeCode === 4401) return 'unauthorized';
  if (status === 423 || closeCode === 4423) return 'locked';
  return 'offline';
}

export class LiveConnection {
  /**
   * @param {object} o
   * @param {string} o.base      API base URL, absolute or relative to the page (`/api/bridge/v1`)
   * @param {string} o.token     viewer token (without the `v.` prefix)
   * @param {(rec:object)=>void} o.onRecord
   * @param {(state:string, detail:string)=>void} [o.onLink]
   * @param {()=>Promise<number>} [o.onOpenSince]  called before each (re)connect; returns `since` for WS /events
   */
  constructor({ base, token, onRecord, onLink = () => {}, onOpenSince, fetchImpl, WebSocketImpl, timers, backoff = BACKOFF_MS, pageUrl }) {
    this.base = base.replace(/\/$/, '');
    this.token = token;
    this.onRecord = onRecord;
    this.onLink = onLink;
    this.onOpenSince = onOpenSince;
    this.fetch = fetchImpl ?? globalThis.fetch.bind(globalThis);
    this.WebSocket = WebSocketImpl ?? globalThis.WebSocket;
    this.timers = timers ?? { setTimeout: globalThis.setTimeout.bind(globalThis), clearTimeout: globalThis.clearTimeout.bind(globalThis) };
    this.backoff = backoff;
    this.pageUrl = pageUrl ?? globalThis.location?.href ?? 'http://127.0.0.1/';
    this.attempt = 0;
    this.lastSeq = 0;
    this.state = 'connecting';
    this.ws = null;
    this.timer = null;
    this.stopped = true;
    this.retryAt = null;
  }

  _setLink(state, detail = '') {
    this.state = state;
    this.onLink(state, detail);
  }

  /** Authenticated GET. JSON by default; `{text: true}` for text/plain. Throws HttpError or a network TypeError. */
  async get(path, { text = false } = {}) {
    const r = await this.fetch(`${this.base}${path}`, { headers: { Authorization: `Bearer v.${this.token}` }, cache: 'no-store' });
    if (!r.ok) {
      let body = null;
      try { body = await r.json(); } catch { /* not JSON */ }
      throw new HttpError(r.status, body, path);
    }
    return text ? r.text() : r.json();
  }

  /**
   * Authenticated POST with the viewer token. The bridge accepts it only for the operator's own
   * actions (today: POST /twin/confirm, D27); everything else answers 403.
   */
  async post(path, body = {}) {
    const r = await this.fetch(`${this.base}${path}`, {
      method: 'POST',
      headers: { Authorization: `Bearer v.${this.token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      cache: 'no-store',
    });
    let json = null;
    try { json = await r.json(); } catch { /* not JSON */ }
    if (!r.ok) throw new HttpError(r.status, json, path);
    return json;
  }

  /** GET that maps 404 to null. */
  async getOptional(path, opts) {
    try { return await this.get(path, opts); } catch (e) { if (e instanceof HttpError && e.status === 404) return null; throw e; }
  }

  /** ws:// URL for /events with the viewer token as `vt` (api.md, "Additions after W1"). */
  eventsUrl(since) {
    const u = new URL(`${this.base}/events`, this.pageUrl);
    u.protocol = u.protocol === 'https:' ? 'wss:' : 'ws:';
    u.searchParams.set('since', String(since));
    u.searchParams.set('vt', this.token);
    return u.toString();
  }

  start() {
    this.stopped = false;
    this._connect();
  }

  stop() {
    this.stopped = true;
    if (this.timer) this.timers.clearTimeout(this.timer);
    this.timer = null;
    if (this.ws) { try { this.ws.close(1000); } catch { /* already closed */ } }
    this.ws = null;
  }

  async _connect() {
    if (this.stopped) return;
    this._setLink('connecting', this.attempt ? `retry ${this.attempt}` : '');
    let since;
    try {
      since = this.onOpenSince ? await this.onOpenSince() : this.lastSeq;
    } catch (e) {
      this._fail(classifyFailure({ status: e?.status }), e?.message ?? String(e));
      return;
    }
    if (this.stopped) return;
    let ws;
    try {
      ws = new this.WebSocket(this.eventsUrl(since));
    } catch (e) {
      this._fail('offline', e.message);
      return;
    }
    this.ws = ws;
    let opened = false;
    ws.onopen = () => {
      opened = true;
      this.attempt = 0;
      this._setLink('online');
    };
    ws.onmessage = (ev) => {
      let rec;
      try { rec = JSON.parse(ev.data); } catch { return; }
      if (typeof rec.seq === 'number' && rec.seq > this.lastSeq) this.lastSeq = rec.seq;
      this.onRecord(rec);
    };
    ws.onerror = () => { /* followed by onclose */ };
    ws.onclose = async (ev) => {
      if (this.ws !== ws) return;
      this.ws = null;
      if (this.stopped) return;
      let state = classifyFailure({ closeCode: ev.code });
      let detail = ev.code ? `WebSocket closed (${ev.code}${ev.reason ? ` ${ev.reason}` : ''})` : 'WebSocket closed';
      if (state === 'offline') {
        // A refused upgrade looks like 1006 in a browser: ask over HTTP why.
        try {
          await this.get('/status');
          // HTTP works but the upgrade failed: a browser can't see why (an HTTP 423/401 on the
          // upgrade looks like 1006), so say so rather than calling the bridge offline
          if (!opened) { state = 'refused'; detail = 'the bridge refused the event stream'; }
        } catch (e) {
          state = classifyFailure({ status: e?.status });
          detail = e?.message ?? String(e);
        }
      }
      this._fail(state, detail);
    };
  }

  _fail(state, detail) {
    if (this.stopped) return;
    const delay = this.backoff[Math.min(this.attempt, this.backoff.length - 1)];
    this.attempt++;
    this.retryAt = Date.now() + delay;
    this._setLink(state, detail);
    this.timer = this.timers.setTimeout(() => { this.timer = null; this._connect(); }, delay);
  }
}
