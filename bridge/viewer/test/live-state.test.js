import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  createLiveState, applyStatus, applyJobState, applyRecord, currentLine, parsePrb, toolLabel, clearTrail, streaming,
} from '../src/live/state.js';
import { LiveConnection, classifyFailure, HttpError } from '../src/live/connection.js';

// A tiny record factory in the log-format.md envelope.
function recorder() {
  let seq = 100;
  return (kind, data, src = 'ugs') => ({ seq: ++seq, t: '2026-11-15T17:31:02.118-05:00', mono: seq / 10, session: 's', kind, src, data });
}
const status = (state, mpos, wco = [-425, -436, -65.6], extra = {}) => ({
  state, mpos, wpos: mpos.map((v, i) => Math.round((v - wco[i]) * 1000) / 1000), wco, pins: { raw: '', probe: false }, raw: '<…>', ...extra,
});
const feed = (s, recs) => { const all = new Set(); for (const r of recs) for (const c of applyRecord(s, r)) all.add(c); return all; };

test('status records: live position, work offset and a trail of the actual path', () => {
  const s = createLiveState();
  const r = recorder();
  const ch = feed(s, [
    r('status', status('Idle', [-400, -400, -5])),
    r('status', status('Jog', [-400, -400, -5.02])), // below the 0.05 mm trail step: not stored
    r('status', status('Jog', [-390, -400, -5])),
    r('status', status('Idle', [-380, -400, -5])),
  ]);
  assert.ok(ch.has('controller') && ch.has('trail') && ch.has('wco'));
  assert.deepEqual(s.controller.mpos, [-380, -400, -5]);
  assert.equal(s.controller.state, 'Idle');
  assert.deepEqual(s.wco, [-425, -436, -65.6]);
  assert.equal(s.wcoSource, 'status');
  assert.equal(s.trail.n, 3);
  assert.deepEqual(Array.from(s.trail.pts.slice(6, 9)), [-380, -400, -5]);
  // WCO derived from MPos − WPos when the report has no WCO
  const s2 = createLiveState();
  applyRecord(s2, r('status', { state: 'Idle', mpos: [-10, -20, -30], wpos: [5, 5, 5], wco: null, pins: { raw: '', probe: false }, raw: '' }));
  assert.deepEqual(s2.wco, [-15, -25, -35]);
  clearTrail(s);
  assert.equal(s.trail.n, 0);
});

test('records at or below the last seq are ignored (reconnect backlog overlap)', () => {
  const s = createLiveState();
  const a = { seq: 5, kind: 'status', src: 'ugs', data: status('Idle', [1, 2, 3]) };
  assert.ok(applyRecord(s, a).size > 0);
  assert.equal(applyRecord(s, a).size, 0);
  assert.equal(applyRecord(s, { ...a, seq: 4 }).size, 0);
  assert.equal(s.seq, 5);
});

test('trail keeps the newest half when it fills up', () => {
  const s = createLiveState({ trailMax: 10 });
  const r = recorder();
  for (let i = 0; i < 12; i++) applyRecord(s, r('status', status('Run', [i, 0, 0])));
  assert.equal(s.trail.n, 7); // 10 → 5 kept, then 2 more
  assert.equal(s.trail.pts[0], 5);
  assert.equal(s.trail.pts[(s.trail.n - 1) * 3], 11);
});

test('stream progress from file, tx/rx and status Ln; scrubber line basis', () => {
  const s = createLiveState();
  const r = recorder();
  feed(s, [
    r('file', { action: 'loaded', path: '/p/dr.nc', sha256: 'a'.repeat(64), lines: 26781 }),
    r('file', { action: 'stream_start', path: '/p/dr.nc', sha256: 'a'.repeat(64), lines: 26781 }),
    r('event', { type: 'stream', event: 'started' }),
    r('tx', { line: 'G0 Z5', cmd_id: 'u-1', file_line: 53 }),
    r('tx', { line: 'G1 Z-1', cmd_id: 'u-2', file_line: 54 }),
    r('tx', { line: 'X10', cmd_id: 'u-3', file_line: 55 }),
    r('rx', { line: 'ok', cmd_id: 'u-1' }),
    r('rx', { line: 'ok', cmd_id: 'u-2' }),
  ]);
  assert.equal(s.stream.state, 'running');
  assert.equal(s.stream.linesTotal, 26781);
  assert.equal(s.stream.lineSent, 55);
  assert.equal(s.stream.lineAcked, 54);
  assert.deepEqual(currentLine(s), { line: 54, basis: 'acked' });
  applyRecord(s, r('status', status('Run', [0, 0, 0], undefined, { line: 53 })));
  assert.deepEqual(currentLine(s), { line: 53, basis: 'Ln' });
  // an error on a file line: banner, and the line counts as answered
  applyRecord(s, r('rx', { line: 'error:20', cmd_id: 'u-3' }));
  assert.equal(s.stream.lineAcked, 55);
  assert.match(s.banners.error.text, /Line 55: GRBL error:20/);
  feed(s, [r('event', { type: 'stream', event: 'paused' })]);
  assert.equal(s.stream.state, 'paused');
  assert.ok(streaming(s));
  feed(s, [r('file', { action: 'stream_end', path: '/p/dr.nc', sha256: 'a'.repeat(64), result: 'complete' })]);
  assert.equal(s.stream.state, 'completed');
  assert.ok(!streaming(s));
  // a status Ln outside a stream doesn't move the line
  applyRecord(s, r('status', status('Idle', [0, 0, 0], undefined, { line: 9 })));
  assert.deepEqual(currentLine(s), { line: 55, basis: 'acked' });
});

test('a different program loaded in UGS is reported as a file change', () => {
  const s = createLiveState();
  const r = recorder();
  assert.ok(applyRecord(s, r('file', { action: 'loaded', path: '/a.nc', sha256: 'a'.repeat(64), lines: 10 })).has('file'));
  assert.ok(!applyRecord(s, r('file', { action: 'loaded', path: '/a.nc', sha256: 'a'.repeat(64), lines: 10 })).has('file'));
  assert.ok(applyRecord(s, r('file', { action: 'loaded', path: '/a.nc', sha256: 'b'.repeat(64), lines: 10 })).has('file'));
});

test('probe hits from event records and [PRB:] lines, deduplicated', () => {
  assert.deepEqual(parsePrb('[PRB:-3.000,-4.000,-41.250:1]'), { mpos: [-3, -4, -41.25], ok: true });
  assert.equal(parsePrb('ok'), null);
  const s = createLiveState();
  const r = recorder();
  feed(s, [
    r('status', status('Idle', [-300, -390, -40])),
    r('command', { id: 'c-1-0', origin: 'claude', line: 'G38.2 Z-60 F100', state: 'sent' }, 'claude'),
    r('rx', { line: '[PRB:-300.000,-390.000,-52.900:1]', cmd_id: 'c-1-0' }, 'claude'),
    r('rx', { line: 'ok', cmd_id: 'c-1-0' }, 'claude'),
    r('event', { type: 'probe', position: [-300, -390, -52.9], units: 'mm', coordinates: 'machine' }),
  ]);
  assert.equal(s.probes.length, 1);
  assert.deepEqual(s.probes[0].mpos, [-300, -390, -52.9]);
  assert.equal(s.probes[0].ok, true);
  assert.equal(s.probes[0].src, 'event');
  assert.deepEqual(s.probes[0].wco, [-425, -436, -65.6]); // for the work Z label: -52.9 − (−65.6) = 12.7
  feed(s, [r('rx', { line: '[PRB:-4.000,-449.000,-80.000:0]' }, 'controller')]);
  assert.equal(s.probes.length, 2);
  assert.equal(s.probes[1].ok, false);
});

test('origin records and WCO changes move the work zero', () => {
  const s = createLiveState();
  const r = recorder();
  applyRecord(s, r('status', status('Idle', [-300, -390, -40])));
  const ch = applyRecord(s, r('origin', { wcs: 'G54', wco: [-425, -436, -66.1], method: 'bitzero_z', tool: 'T02' }, 'bridge'));
  assert.ok(ch.has('wco'));
  assert.deepEqual(s.wco, [-425, -436, -66.1]);
  assert.equal(s.wcoSource, 'origin');
  assert.equal(s.origin.method, 'bitzero_z');
  // the next status report carries the same WCO: no change
  assert.ok(!applyRecord(s, r('status', status('Idle', [-300, -390, -40], [-425, -436, -66.1]))).has('wco'));
  // G55 origins don't move the G54 axes
  applyRecord(s, r('origin', { wcs: 'G55', wco: [0, 0, 0], method: 'g10' }));
  assert.deepEqual(s.wco, [-425, -436, -66.1]);
});

test('tool change banner: requested, confirmed, measured, cleared when the stream moves on', () => {
  const s = createLiveState();
  const r = recorder();
  feed(s, [r('file', { action: 'stream_start', path: '/p', sha256: 'a'.repeat(64), lines: 100 }), r('tx', { line: 'T21 M6', cmd_id: 'u-9', file_line: 48 })]);
  feed(s, [r('event', { type: 'interceptor', state: 'active', trigger: 'M6', file_line: 48, step: 'park', tool: 'T21' })]);
  assert.match(s.banners.toolChange.text, /Tool change: load T21 · moving to the tool-change park/);
  assert.equal(s.banners.toolChange.line, 48);
  feed(s, [r('tool', { event: 'change_requested', tool: 'T21' })]);
  assert.match(s.banners.toolChange.text, /swap the bit/);
  feed(s, [r('tool', { event: 'confirmed', tool: 'T21' })]);
  assert.match(s.banners.toolChange.text, /measuring/);
  feed(s, [r('tool', { event: 'measured', tool: 'T21', trigger_mz: -80.123 })]);
  assert.equal(s.tool.current, 'T21');
  assert.match(s.banners.toolChange.text, /T21 measured \(trigger Z-80\.123\)/);
  feed(s, [r('tx', { line: 'S18000 M3', cmd_id: 'u-10', file_line: 49 })]);
  assert.equal(s.banners.toolChange, null);
  // the interceptor going idle clears it too; and status `Tool` raises a generic one
  feed(s, [r('status', status('Tool', [0, 0, 0]))]);
  assert.equal(s.banners.toolChange.text, 'Tool change');
  feed(s, [r('status', status('Idle', [0, 0, 0]))]);
  assert.equal(s.banners.toolChange, null);
  feed(s, [r('event', { type: 'interceptor', state: 'active', tool: 'T03' }), r('event', { type: 'interceptor', state: 'idle' })]);
  assert.equal(s.banners.toolChange, null);
  assert.equal(toolLabel('3'), 'T03');
  assert.equal(toolLabel(21), 'T21');
});

test('alarm banner from ALARM lines, events and status; cleared after unlock', () => {
  const s = createLiveState();
  const r = recorder();
  feed(s, [r('rx', { line: 'ALARM:2', cmd_id: 'c-4-0' }, 'claude')]);
  assert.deepEqual(s.banners.alarm, { code: 2, text: 'Soft limit: target outside the machine travel' });
  feed(s, [r('event', { type: 'alarm', code: 2 }), r('status', status('Alarm', [0, 0, 0]))]);
  assert.equal(s.banners.alarm.code, 2);
  feed(s, [r('status', status('Idle', [0, 0, 0]))]);
  assert.equal(s.banners.alarm, null);
  // alarm state without a known code (e.g. power-up lock)
  feed(s, [r('status', status('Alarm', [0, 0, 0]))]);
  assert.equal(s.banners.alarm.code, null);
  feed(s, [r('command', { id: 'c-5-0', origin: 'claude', line: 'G38.2 Z-5 F50', state: 'alarm', alarm_code: 5 }, 'claude')]);
  assert.equal(s.banners.alarm.code, 5);
  feed(s, [r('status', status('Hold', [0, 0, 0], undefined, { substate: 0 }))]);
  assert.match(s.banners.hold.text, /Feed hold/);
});

test('connection and authority: disconnect, MCP lock (the viewer stays connected)', () => {
  const s = createLiveState();
  const r = recorder();
  feed(s, [r('session', { event: 'connect', port: 'tcp://127.0.0.1:sim', driver: 'TCP' }), r('status', status('Idle', [0, 0, 0]))]);
  assert.equal(s.connected, true);
  assert.equal(s.homed, false);
  feed(s, [r('tx', { line: '$H', cmd_id: 'u-1' }, 'operator'), r('rx', { line: 'ok', cmd_id: 'u-1' }, 'operator')]);
  assert.equal(s.homed, true);
  feed(s, [r('authority', { locked: true, level: 'observe', by: 'operator' }, 'operator')]);
  assert.deepEqual(s.authority, { locked: true, level: 'observe' });
  // a request from Claude doesn't change the level
  feed(s, [r('authority', { locked: true, level: 'observe', by: 'claude', requested: 'motion', reason: 'x' }, 'claude')]);
  assert.deepEqual(s.authority, { locked: true, level: 'observe' });
  feed(s, [r('file', { action: 'stream_start', path: '/p', sha256: 'a'.repeat(64), lines: 100 })]);
  feed(s, [r('session', { event: 'disconnect', reason: 'connection_closed' })]);
  assert.equal(s.connected, false);
  assert.equal(s.controller.state, 'Unknown');
  assert.equal(s.stream.state, 'error');
  feed(s, [r('session', { event: 'connect', port: 'tcp://127.0.0.1:sim', driver: 'TCP' })]);
  assert.equal(s.stream.state, 'idle');
});

test('snapshots: GET /status and GET /job/state', () => {
  const s = createLiveState();
  applyStatus(s, {
    seq: 42,
    connection: { connected: true, port: 'tcp://x' },
    controller: status('Run', [-10, -10, -10]),
    homed_this_connection: true,
    alarm: null,
    authority: { locked: false, level: 'motion' },
    stream: { state: 'running', file: '/p/dr.nc', sha256: 'a'.repeat(64), line_sent: 500, line_acked: 480, lines_total: 26781 },
    interceptor: { state: 'swap', step: 'swap', tool: 'T21' },
    tool: { current: 'T02', length_known: true },
  });
  assert.equal(s.seq, 0, 'a snapshot does not advance the record seq');
  assert.equal(s.connected, true);
  assert.equal(s.stream.lineAcked, 480);
  assert.deepEqual(currentLine(s), { line: 480, basis: 'acked' });
  assert.equal(s.tool.current, 'T02');
  assert.match(s.banners.toolChange.text, /load T21/);
  const s2 = createLiveState();
  applyJobState(s2, { format: 'ugs-bridge-job-state/1', updated: 'x', file: { path: '/p', sha256: 'a'.repeat(64), lines: 26781 }, stream: 'stopped', line_acked: 17700, line_done: 17690, operation: 'clear_hi' });
  assert.deepEqual(currentLine(s2), { line: 17690, basis: 'journal' });
  assert.equal(s2.stream.linesTotal, 26781);
  applyJobState(s2, null);
  assert.equal(currentLine(s2), null);
});

test('the reducer is fast enough for a full stream (100k records)', () => {
  const s = createLiveState();
  const r = recorder();
  const t0 = performance.now();
  for (let i = 1; i <= 50000; i++) {
    applyRecord(s, r('tx', { line: 'X1', cmd_id: `u-${i}`, file_line: i }));
    applyRecord(s, r('rx', { line: 'ok', cmd_id: `u-${i}` }));
  }
  const ms = performance.now() - t0;
  assert.equal(s.stream.lineAcked, 50000);
  assert.equal(s._cmdFileLine.size, 0);
  assert.ok(ms < 1000, `${ms} ms`);
});

// ------------------------------------------------------------------------------------- connection

class FakeWs {
  static last = null;
  constructor(url) { this.url = url; FakeWs.last = this; FakeWs.all.push(this); }
  close(code = 1000) { this.onclose?.({ code }); }
  static all = [];
}

function fakeTimers() {
  const q = [];
  return { q, setTimeout: (fn, ms) => { q.push({ fn, ms }); return q.length; }, clearTimeout: () => {} };
}

test('classifyFailure maps HTTP and close codes to link states', () => {
  assert.equal(classifyFailure({ status: 401 }), 'unauthorized');
  assert.equal(classifyFailure({ closeCode: 4401 }), 'unauthorized');
  assert.equal(classifyFailure({ status: 423 }), 'locked');
  assert.equal(classifyFailure({ closeCode: 4423 }), 'locked');
  assert.equal(classifyFailure({ closeCode: 1006 }), 'offline');
  assert.equal(classifyFailure({}), 'offline');
});

test('LiveConnection: viewer auth header, ws URL with vt and since, backoff, link states', async () => {
  const calls = [];
  let statusReply = { ok: true, status: 200, json: async () => ({ seq: 7 }) };
  const fetchImpl = async (url, opts) => { calls.push([url, opts.headers.Authorization]); return statusReply; };
  const timers = fakeTimers();
  const links = [];
  const recs = [];
  FakeWs.all = [];
  const c = new LiveConnection({
    base: 'http://127.0.0.1:9999/api/bridge/v1', token: 'tok', fetchImpl, WebSocketImpl: FakeWs, timers,
    onRecord: (x) => recs.push(x), onLink: (st) => links.push(st), backoff: [10, 20, 40],
    onOpenSince: async () => { await c.get('/status'); return 3; },
  });
  c.start();
  await new Promise((r) => setTimeout(r, 0));
  assert.deepEqual(calls[0], ['http://127.0.0.1:9999/api/bridge/v1/status', 'Bearer v.tok']);
  assert.equal(FakeWs.last.url, 'ws://127.0.0.1:9999/api/bridge/v1/events?since=3&vt=tok');
  FakeWs.last.onopen();
  FakeWs.last.onmessage({ data: JSON.stringify({ seq: 11, kind: 'status', data: {} }) });
  FakeWs.last.onmessage({ data: 'not json' });
  assert.deepEqual(links, ['connecting', 'online']);
  assert.equal(recs.length, 1);
  assert.equal(c.lastSeq, 11);

  // the bridge goes away: the ws closes, /status fails → offline, retry after the first backoff step
  c.fetch = async () => { throw new TypeError('fetch failed'); };
  await FakeWs.last.onclose({ code: 1006 });
  assert.equal(links.at(-1), 'offline');
  assert.equal(timers.q.at(-1).ms, 10);
  // retry: still down → next step
  timers.q.at(-1).fn();
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(links.at(-1), 'offline');
  assert.equal(timers.q.at(-1).ms, 20);
  // token rejected
  c.fetch = async () => ({ ok: false, status: 401, json: async () => ({ error: 'unauthorized' }) });
  timers.q.at(-1).fn();
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(links.at(-1), 'unauthorized');
  // back: a successful open resets the backoff
  c.fetch = fetchImpl;
  statusReply = { ok: true, status: 200, json: async () => ({ seq: 20 }) };
  timers.q.at(-1).fn();
  await new Promise((r) => setTimeout(r, 0));
  FakeWs.last.onopen();
  assert.equal(links.at(-1), 'online');
  assert.equal(c.attempt, 0);
  // the server closes with 4423 (if a plugin refuses viewers while locked)
  await FakeWs.last.onclose({ code: 4423, reason: 'locked_by_operator' });
  assert.equal(links.at(-1), 'locked');
  // an upgrade refused with a plain HTTP status: the browser sees 1006 before open, HTTP still works
  timers.q.at(-1).fn();
  await new Promise((r) => setTimeout(r, 0));
  await FakeWs.last.onclose({ code: 1006 });
  assert.equal(links.at(-1), 'refused');
  c.stop();
  const n = timers.q.length;
  timers.q.at(-1).fn(); // a pending retry after stop does nothing
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(timers.q.length, n);
});

test('LiveConnection.get raises HttpError with status and code; getOptional maps 404 to null', async () => {
  const c = new LiveConnection({ base: '/api/bridge/v1', token: 't', pageUrl: 'http://127.0.0.1:1/view/', onRecord() {}, fetchImpl: async (u) => (u.endsWith('/file') ? { ok: false, status: 404, json: async () => ({ error: 'not_found' }) } : { ok: false, status: 423, json: async () => ({ error: 'locked_by_operator' }) }), WebSocketImpl: FakeWs, timers: fakeTimers() });
  assert.equal(await c.getOptional('/file'), null);
  await assert.rejects(c.get('/status'), (e) => e instanceof HttpError && e.status === 423 && e.code === 'locked_by_operator');
  assert.equal(c.eventsUrl(5), 'ws://127.0.0.1:1/api/bridge/v1/events?since=5&vt=t');
});
