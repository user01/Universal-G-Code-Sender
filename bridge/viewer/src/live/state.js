// Live viewer state: a pure reducer from bridge records to what the viewer shows.
// No DOM, no Three.js, no network: runs in Node (test/live-state.test.js).
//
// Inputs (bridge/docs/api.md, log-format.md):
//   applyStatus(state, BridgeStatus)     GET /status snapshot
//   applyJobState(state, journal|null)   GET /job/state (404 → null)
//   applyRecord(state, LogRecord)        each record from WS /events (backlog first, then live)
// Each returns a Set of what changed: 'controller', 'trail', 'wco', 'stream', 'probes', 'banners',
// 'authority', 'connection', 'file', 'tool', 'job'. The UI redraws only those parts.
//
// Coordinates: GRBL reports machine positions (MPos) and the work offset (WCO); work = MPos − WCO.
// The trail and probe hits are kept in machine coordinates, so a WCO change doesn't move them.

/** GRBL 1.1 alarm texts (short). */
export const GRBL_ALARMS = {
  1: 'Hard limit triggered',
  2: 'Soft limit: target outside the machine travel',
  3: 'Reset while in motion: position may be lost, re-home',
  4: 'Probe fail: probe not in the expected state before the cycle',
  5: 'Probe fail: no contact within the travel',
  6: 'Homing fail: reset during the cycle',
  7: 'Homing fail: door opened',
  8: 'Homing fail: pull-off didn\'t clear the limit switch',
  9: 'Homing fail: limit switch not found',
  10: 'Homing fail: second switch of a dual axis not found',
};

const EPS_WCO = 1e-4;
const TRAIL_STEP = 0.05; // mm between stored trail points
const PROBE_DEDUPE = 0.002; // mm: an `event probe` and the `[PRB:…]` rx line describe the same hit

/** @param {{trailMax?: number}} [opts] */
export function createLiveState({ trailMax = 50000 } = {}) {
  return {
    seq: 0, // highest record seq applied
    link: 'connecting', // set by the connection: connecting | online | offline | locked | unauthorized
    linkDetail: '',
    connected: null, // UGS ↔ controller connection (null = unknown)
    port: null,
    controller: null, // {state, mpos, wpos, wco, feed, spindle, line, pins}
    wco: null, // [x,y,z] machine = work + wco
    wcoSource: null, // 'status' | 'origin'
    origin: null, // last `origin` record data
    authority: { locked: false, level: null },
    homed: null,
    trail: { pts: new Float32Array(trailMax * 3), n: 0, max: trailMax, version: 0 },
    stream: { state: 'idle', file: null, sha256: null, lineSent: null, lineAcked: null, linesTotal: null, lineExec: null },
    journal: null, // GET /job/state summary
    job: null, // last `job` record data
    file: null, // {path, sha256, lines} of the program loaded in UGS
    tool: { current: null },
    probes: [], // {mpos:[x,y,z], ok, wco:[…]|null, seq, src:'event'|'rx'}
    banners: { alarm: null, toolChange: null, hold: null, error: null },
    lastAlarmCode: null,
    _cmdFileLine: new Map(), // tx cmd_id → file line, until its ok/error arrives
    _homingCmd: null, // cmd_id of a pending $H
  };
}

// ------------------------------------------------------------------------------------- helpers

const near = (a, b, eps) => !!a && !!b && Math.abs(a[0] - b[0]) <= eps && Math.abs(a[1] - b[1]) <= eps && Math.abs(a[2] - b[2]) <= eps;
const vec3 = (v) => (Array.isArray(v) && v.length === 3 && v.every((x) => typeof x === 'number' && Number.isFinite(x)) ? v.slice() : null);

/** `[PRB:-3.000,-4.000,-41.250:1]` → {mpos, ok} or null. */
export function parsePrb(line) {
  const m = /^\[PRB:([-\d.]+),([-\d.]+),([-\d.]+):([01])\]/.exec(line ?? '');
  return m ? { mpos: [Number(m[1]), Number(m[2]), Number(m[3])], ok: m[4] === '1' } : null;
}

/** Tool id like "T21" from a tool field (string "T21"/"21" or a number). */
export function toolLabel(t) {
  if (t === null || t === undefined || t === '') return null;
  const m = /(\d+)/.exec(String(t));
  return m ? `T${String(Number(m[1])).padStart(2, '0')}` : String(t);
}

export function alarmText(code) {
  return GRBL_ALARMS[code] ?? 'alarm';
}

/**
 * The program line the machine is at, best estimate first: the controller's `Ln` while streaming,
 * else the highest acked file line (GRBL's planner runs up to ~15 blocks behind an ok), else the
 * highest sent line, else the journal's last line known done.
 * @returns {{line:number, basis:'Ln'|'acked'|'sent'|'journal'}|null}
 */
export function currentLine(s) {
  const st = s.stream;
  if (st.lineExec) return { line: st.lineExec, basis: 'Ln' };
  if (st.lineAcked) return { line: st.lineAcked, basis: 'acked' };
  if (st.lineSent) return { line: st.lineSent, basis: 'sent' };
  if (s.journal?.line_done) return { line: s.journal.line_done, basis: 'journal' };
  return null;
}

/** True when a stream is running or paused (the scrubber should follow it). */
export function streaming(s) {
  return s.stream.state === 'running' || s.stream.state === 'paused';
}

/** Drop the trail (e.g. operator's "clear trail"). */
export function clearTrail(s) {
  s.trail.n = 0;
  s.trail.version++;
}

function pushTrail(s, p, changes) {
  const t = s.trail;
  if (t.n > 0) {
    const o = (t.n - 1) * 3;
    if (Math.hypot(p[0] - t.pts[o], p[1] - t.pts[o + 1], p[2] - t.pts[o + 2]) < TRAIL_STEP) return;
  }
  if (t.n >= t.max) { // keep the newest half
    const keep = Math.floor(t.max / 2);
    t.pts.copyWithin(0, (t.n - keep) * 3, t.n * 3);
    t.n = keep;
  }
  t.pts.set(p, t.n * 3);
  t.n++;
  t.version++;
  changes.add('trail');
}

function setWco(s, wco, source, changes) {
  if (!wco) return;
  if (s.wco && near(s.wco, wco, EPS_WCO)) { if (source === 'origin') s.wcoSource = 'origin'; return; }
  s.wco = wco.slice();
  s.wcoSource = source;
  changes.add('wco');
}

function setBanner(s, key, value, changes) {
  const a = s.banners[key];
  if (a === value || (a && value && JSON.stringify(a) === JSON.stringify(value))) return;
  s.banners[key] = value;
  changes.add('banners');
}

function addProbe(s, mpos, ok, src, seq, changes) {
  const last = s.probes[s.probes.length - 1];
  if (last && near(last.mpos, mpos, PROBE_DEDUPE) && seq - last.seq < 200) {
    if (src === 'event') last.src = 'event';
    return;
  }
  s.probes.push({ mpos: mpos.slice(), ok, wco: s.wco ? s.wco.slice() : null, seq, src });
  if (s.probes.length > 200) s.probes.shift();
  changes.add('probes');
}

function applyController(s, c, changes) {
  if (!c) return;
  const prev = s.controller;
  const mpos = vec3(c.mpos);
  let wco = vec3(c.wco);
  const wpos = vec3(c.wpos);
  if (!wco && mpos && wpos) wco = [mpos[0] - wpos[0], mpos[1] - wpos[1], mpos[2] - wpos[2]];
  s.controller = { state: c.state, substate: c.substate ?? null, mpos, wpos, wco, feed: c.feed ?? null, spindle: c.spindle ?? null, line: c.line ?? null, pins: c.pins ?? null };
  changes.add('controller');
  if (wco) setWco(s, wco, 'status', changes);
  if (mpos) pushTrail(s, mpos, changes);
  if (typeof c.line === 'number' && streaming(s)) { s.stream.lineExec = c.line; changes.add('stream'); }

  const state = c.state;
  if (state === 'Alarm') {
    const code = s.lastAlarmCode;
    setBanner(s, 'alarm', { code, text: code ? alarmText(code) : 'controller in alarm' }, changes);
  } else if (s.banners.alarm && state && state !== 'Unknown') {
    setBanner(s, 'alarm', null, changes);
    s.lastAlarmCode = null;
  }
  setBanner(s, 'hold', state === 'Hold' ? { text: c.substate === 0 ? 'Feed hold (complete): resume to continue' : 'Feed hold' } : state === 'Door' ? { text: 'Safety door open' } : null, changes);
  if (state === 'Tool' && !s.banners.toolChange) setBanner(s, 'toolChange', { tool: null, step: 'tool', text: 'Tool change' }, changes);
  if (prev?.state === 'Tool' && state !== 'Tool' && s.banners.toolChange?.step === 'tool') setBanner(s, 'toolChange', null, changes);
}

function toolChangeBanner(tool, step, line) {
  const t = toolLabel(tool);
  const steps = {
    change_requested: 'swap the bit, then confirm in UGS',
    confirmed: 'measuring on the BitSetter',
    park: 'moving to the tool-change park',
    swap: 'swap the bit, then confirm in UGS',
    measure: 'measuring on the BitSetter',
    return: 'returning to the job',
  };
  return { tool: t, step, line: line ?? null, text: `Tool change${t ? `: load ${t}` : ''}${steps[step] ? ` · ${steps[step]}` : step ? ` · ${step}` : ''}` };
}

// ------------------------------------------------------------------------------------- snapshots

/** GET /status snapshot (BridgeStatus). */
export function applyStatus(s, b) {
  const changes = new Set();
  if (!b) return changes;
  const conn = b.connection ?? {};
  if (s.connected !== !!conn.connected) { s.connected = !!conn.connected; changes.add('connection'); }
  s.port = conn.port ?? null;
  s.homed = b.homed_this_connection ?? null;
  if (b.authority) {
    if (s.authority.locked !== !!b.authority.locked || s.authority.level !== b.authority.level) changes.add('authority');
    s.authority = { locked: !!b.authority.locked, level: b.authority.level };
  }
  if (b.alarm?.code) s.lastAlarmCode = b.alarm.code;
  applyController(s, b.controller, changes);
  if (b.stream) {
    const st = b.stream;
    Object.assign(s.stream, {
      state: st.state ?? s.stream.state,
      file: st.file ?? s.stream.file,
      sha256: st.sha256 ?? s.stream.sha256,
      lineSent: st.line_sent ?? s.stream.lineSent,
      lineAcked: st.line_acked ?? s.stream.lineAcked,
      linesTotal: st.lines_total ?? s.stream.linesTotal,
    });
    changes.add('stream');
  }
  if (b.interceptor && b.interceptor.state && !/^(idle|done|inactive)$/i.test(b.interceptor.state)) {
    setBanner(s, 'toolChange', toolChangeBanner(b.interceptor.tool, b.interceptor.step ?? b.interceptor.state), changes);
  }
  if (b.tool && 'current' in b.tool) { s.tool.current = toolLabel(b.tool.current); changes.add('tool'); }
  return changes;
}

/** GET /job/state (the journal), or null for 404. */
export function applyJobState(s, j) {
  const changes = new Set(['job']);
  s.journal = j ? { stream: j.stream, line_acked: j.line_acked, line_done: j.line_done, operation: j.operation ?? null, tool: j.tool ?? null, sha256: j.file?.sha256 ?? null, lines: j.file?.lines ?? null } : null;
  if (j && !streaming(s)) {
    if (!s.stream.linesTotal && j.file?.lines) s.stream.linesTotal = j.file.lines;
    changes.add('stream');
  }
  return changes;
}

// ------------------------------------------------------------------------------------- records

/** Apply one LogRecord. Records at or below the last seq are ignored (reconnect backlog overlap). */
export function applyRecord(s, rec) {
  const changes = new Set();
  if (!rec || typeof rec.seq !== 'number' || rec.seq <= s.seq) return changes;
  s.seq = rec.seq;
  const d = rec.data ?? {};
  switch (rec.kind) {
    case 'status':
      applyController(s, d, changes);
      break;

    case 'session':
      if (d.event === 'connect') {
        s.connected = true; s.port = d.port ?? null; s.homed = false;
        if (s.stream.state === 'error') { s.stream.state = 'idle'; changes.add('stream'); } // a new connection, no stream yet
        changes.add('connection');
      } else if (d.event === 'disconnect' || d.event === 'bridge_stop') {
        s.connected = false;
        if (s.controller) s.controller = { ...s.controller, state: 'Unknown' };
        if (streaming(s)) s.stream.state = 'error';
        s._cmdFileLine.clear();
        setBanner(s, 'hold', null, changes);
        changes.add('connection').add('controller').add('stream');
      }
      break;

    case 'tx': {
      if (/^\$H\b/i.test(d.line ?? '') && d.cmd_id) s._homingCmd = d.cmd_id;
      if (typeof d.file_line === 'number') {
        if (d.cmd_id) s._cmdFileLine.set(d.cmd_id, d.file_line);
        if (d.file_line > (s.stream.lineSent ?? 0)) s.stream.lineSent = d.file_line;
        if (!streaming(s)) s.stream.state = 'running';
        if (s.banners.toolChange?.step === 'measured') setBanner(s, 'toolChange', null, changes); // the stream moved on
        changes.add('stream');
      }
      break;
    }

    case 'rx': {
      const line = d.line ?? '';
      if (d.cmd_id && s._cmdFileLine.has(d.cmd_id) && (line === 'ok' || line.startsWith('error:') || line.startsWith('ALARM:'))) {
        const fl = s._cmdFileLine.get(d.cmd_id);
        s._cmdFileLine.delete(d.cmd_id);
        if (fl > (s.stream.lineAcked ?? 0)) s.stream.lineAcked = fl;
        if (line.startsWith('error:')) setBanner(s, 'error', { line: fl, text: `Line ${fl}: GRBL ${line}` }, changes);
        changes.add('stream');
      }
      if (d.cmd_id && d.cmd_id === s._homingCmd && line === 'ok') { s.homed = true; s._homingCmd = null; changes.add('connection'); }
      const alarm = /^ALARM:(\d+)/.exec(line);
      if (alarm) {
        s.lastAlarmCode = Number(alarm[1]);
        setBanner(s, 'alarm', { code: s.lastAlarmCode, text: alarmText(s.lastAlarmCode) }, changes);
      }
      const prb = parsePrb(line);
      if (prb) addProbe(s, prb.mpos, prb.ok, 'rx', rec.seq, changes);
      break;
    }

    case 'event':
      switch (d.type) {
        case 'alarm': {
          const code = typeof d.code === 'number' ? d.code : s.lastAlarmCode;
          if (code) s.lastAlarmCode = code;
          setBanner(s, 'alarm', { code: code ?? null, text: code ? alarmText(code) : String(d.ugs_alarm ?? 'alarm') }, changes);
          break;
        }
        case 'probe': {
          const p = vec3(d.position);
          if (p && (d.coordinates ?? 'machine') === 'machine') addProbe(s, p, d.ok !== false, 'event', rec.seq, changes);
          else if (p && s.wco) addProbe(s, [p[0] + s.wco[0], p[1] + s.wco[1], p[2] + s.wco[2]], d.ok !== false, 'event', rec.seq, changes);
          break;
        }
        case 'stream': {
          const map = { started: 'running', resumed: 'running', paused: 'paused', paused_on_error: 'paused', canceled: 'canceled', complete: 'completed' };
          if (map[d.event]) { s.stream.state = map[d.event]; changes.add('stream'); }
          if (d.event === 'started') { s.stream.lineExec = null; setBanner(s, 'error', null, changes); }
          break;
        }
        case 'interceptor': {
          const st = String(d.state ?? '');
          if (!st || /^(idle|done|inactive|finished|completed|canceled)$/i.test(st)) setBanner(s, 'toolChange', null, changes);
          else setBanner(s, 'toolChange', toolChangeBanner(d.tool ?? s.banners.toolChange?.tool, d.step ?? st, d.file_line), changes);
          break;
        }
        default:
          break;
      }
      break;

    case 'command':
      if (d.state === 'alarm' && typeof d.alarm_code === 'number') {
        s.lastAlarmCode = d.alarm_code;
        setBanner(s, 'alarm', { code: d.alarm_code, text: alarmText(d.alarm_code) }, changes);
      }
      break;

    case 'file':
      if (d.action === 'loaded') {
        const changed = s.file?.sha256 !== d.sha256 || s.file?.path !== d.path;
        s.file = { path: d.path, sha256: d.sha256, lines: d.lines ?? null };
        if (changed) changes.add('file');
      } else if (d.action === 'stream_start') {
        Object.assign(s.stream, { state: 'running', file: d.path, sha256: d.sha256, lineSent: null, lineAcked: null, lineExec: null, linesTotal: d.lines ?? s.stream.linesTotal });
        s._cmdFileLine.clear();
        setBanner(s, 'error', null, changes);
        if (s.file?.sha256 !== d.sha256) { s.file = { path: d.path, sha256: d.sha256, lines: d.lines ?? null }; changes.add('file'); }
        changes.add('stream');
      } else if (d.action === 'stream_end') {
        s.stream.state = d.result === 'canceled' ? 'canceled' : 'completed';
        s.stream.lineExec = null;
        changes.add('stream');
      }
      break;

    case 'authority':
      if (d.by !== 'claude' || d.requested === undefined) { // a request from Claude doesn't change the state
        s.authority = { locked: !!d.locked, level: d.level ?? s.authority.level };
        changes.add('authority');
      }
      break;

    case 'origin': {
      s.origin = { ...d };
      const w = vec3(d.wco);
      if (w && (d.wcs ?? 'G54') === 'G54') setWco(s, w, 'origin', changes);
      changes.add('wco');
      break;
    }

    case 'tool':
      if (d.event === 'change_requested') setBanner(s, 'toolChange', toolChangeBanner(d.tool, 'change_requested'), changes);
      else if (d.event === 'confirmed') setBanner(s, 'toolChange', toolChangeBanner(d.tool ?? s.banners.toolChange?.tool, 'confirmed'), changes);
      else if (d.event === 'measured') {
        s.tool.current = toolLabel(d.tool) ?? s.tool.current;
        changes.add('tool');
        if (s.banners.toolChange) setBanner(s, 'toolChange', { ...s.banners.toolChange, step: 'measured', text: `${toolLabel(d.tool) ?? 'Tool'} measured${typeof d.trigger_mz === 'number' ? ` (trigger Z${d.trigger_mz.toFixed(3)})` : ''} · returning to the job` }, changes);
      }
      break;

    case 'job':
      s.job = { ...d };
      if (d.event === 'started' || d.event === 'resume_started') { s.stream.state = 'running'; changes.add('stream'); }
      else if (d.event === 'paused') { s.stream.state = 'paused'; changes.add('stream'); }
      else if (d.event === 'stopped' || d.event === 'completed') { s.stream.state = d.event === 'completed' ? 'completed' : 'canceled'; s.stream.lineExec = null; changes.add('stream'); }
      changes.add('job');
      break;

    default:
      break;
  }
  return changes;
}
