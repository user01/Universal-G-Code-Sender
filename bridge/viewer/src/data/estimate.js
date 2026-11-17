// Job time: the bridge's planner estimate (GET /file/estimate) when available, else the parser's
// rough distance ÷ feed figure. Plus formatting of the live ETA (`eta` in /job/state and `eta`
// records). Pure, runs in Node.
//
// GET /file/estimate (contract with the plugin):
//   {version:1, source:"grbl-planner", total_s, machine_s, line_end_s:[per 0-based file line,
//    cumulative end time], ops:[{name,tool,first_line,last_line,start_s,end_s}],
//    tools:[{tool,name,s}], tool_changes:[{line,from,to,at_s}], assumptions:[…]}
// ETA: {elapsed_s, adjusted_remaining_s, ratio, predicted_finish, current_op, current_tool,
//       op_remaining_s, next_tool_change:{line,tool,in_s}|null, tool_changes_remaining}
// Every field may be absent.

const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);

/**
 * @typedef {object} Estimate
 * @property {'grbl-planner'|'rough'} source
 * @property {string} label  for the UI
 * @property {Float64Array} lineTime  seconds at the end of each line (index = 1-based line; [0] = 0, [n+1] = total)
 * @property {number} total  seconds, machine time plus tool changes when the bridge counts them
 * @property {{line:number, from:any, to:any, at_s:number}[]} toolChanges
 * @property {string[]} assumptions
 */

/** The parser's figure: feeds and a fixed rapid rate, no acceleration. */
export function roughEstimate(program, rapidRate = 5000) {
  return {
    source: 'rough',
    label: 'rough (distance ÷ feed)',
    lineTime: program.lineTime,
    total: program.totalTime,
    toolChanges: program.toolChanges.map((tc) => ({ line: tc.line, from: null, to: tc.tool, at_s: program.lineTime[tc.line] ?? 0 })),
    assumptions: [`rapids at ${rapidRate} mm/min`, 'no acceleration', 'tool changes take no time'],
  };
}

/**
 * The bridge's estimate, mapped onto the program's lines. Returns null when it doesn't fit the
 * program (different line count, bad data), so the caller falls back to `roughEstimate`.
 */
export function bridgeEstimate(est, program) {
  if (!est || typeof est !== 'object' || !Array.isArray(est.line_end_s)) return null;
  const n = program.lineCount;
  const les = est.line_end_s;
  if (les.length !== n && les.length !== n + 1) return null; // a trailing newline may count as a line
  const lineTime = new Float64Array(n + 2);
  let prev = 0;
  for (let l = 1; l <= n; l++) {
    const v = num(les[l - 1]);
    prev = v === null ? prev : Math.max(prev, v);
    lineTime[l] = prev;
  }
  const total = num(est.total_s) ?? prev;
  lineTime[n + 1] = Math.max(total, prev);
  return {
    source: 'grbl-planner',
    label: 'planner (acceleration, your $ settings)',
    lineTime,
    total: lineTime[n + 1],
    machine: num(est.machine_s),
    toolChanges: (est.tool_changes ?? []).map((t) => ({ line: t.line, from: t.from ?? null, to: t.to ?? null, at_s: num(t.at_s) ?? lineTime[t.line] ?? 0 })),
    assumptions: Array.isArray(est.assumptions) ? est.assumptions.map(String) : [],
    ops: Array.isArray(est.ops) ? est.ops : null,
    tools: Array.isArray(est.tools) ? est.tools : null,
  };
}

/**
 * Start, end and duration of each operation from an estimate's lineTime.
 * @param {{lineStart:number, lineEnd:number}[]} ops
 */
export function opTimes(estimate, ops) {
  const lt = estimate.lineTime;
  return ops.map((o) => {
    const start = lt[Math.max(0, o.lineStart - 1)] ?? 0;
    const end = lt[Math.min(lt.length - 1, o.lineEnd)] ?? start;
    return { start, end, s: Math.max(0, end - start) };
  });
}

/** Seconds per tool (sum of its operations), in first-use order. */
export function toolTimes(estimate, ops) {
  const times = opTimes(estimate, ops);
  const out = new Map();
  ops.forEach((o, i) => out.set(o.tool, (out.get(o.tool) ?? 0) + times[i].s));
  return [...out.entries()].map(([tool, s]) => ({ tool, s }));
}

/** "1:05", "12:40", "1:02:03" */
export function duration(sec) {
  if (sec === null || sec === undefined || !Number.isFinite(sec)) return '–';
  const s = Math.max(0, Math.round(sec));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}` : `${m}:${String(r).padStart(2, '0')}`;
}

/** "4 min", "45 s", "1 h 05 min" */
export function approx(sec) {
  if (sec === null || sec === undefined || !Number.isFinite(sec)) return '–';
  const s = Math.max(0, Math.round(sec));
  if (s < 60) return `${s} s`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')} min`;
}

/** Wall-clock "18:42" for a Date or epoch ms. */
export function clock(t) {
  const d = t instanceof Date ? t : new Date(t);
  if (Number.isNaN(d.getTime())) return '–';
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/** Normalize an ETA object (from /job/state `eta` or an `eta` record); null when it has nothing useful. */
export function normalizeEta(e) {
  if (!e || typeof e !== 'object') return null;
  const ntc = e.next_tool_change && typeof e.next_tool_change === 'object' ? {
    line: num(e.next_tool_change.line), tool: e.next_tool_change.tool ?? null, in_s: num(e.next_tool_change.in_s),
  } : null;
  const out = {
    elapsed_s: num(e.elapsed_s),
    remaining_s: num(e.adjusted_remaining_s) ?? num(e.remaining_s),
    ratio: num(e.ratio),
    finish: typeof e.predicted_finish === 'string' || typeof e.predicted_finish === 'number' ? e.predicted_finish : null,
    op: e.current_op ?? null,
    tool: e.current_tool ?? null,
    op_remaining_s: num(e.op_remaining_s),
    next_tool_change: ntc,
    tool_changes_remaining: num(e.tool_changes_remaining),
  };
  const any = Object.values(out).some((v) => v !== null);
  return any ? out : null;
}

const toolText = (t) => {
  if (t === null || t === undefined || t === '') return null;
  const m = /(\d+)/.exec(String(t));
  return m ? `T${String(Number(m[1])).padStart(2, '0')}` : String(t);
};

/**
 * One-line live summary: "Now: T21 contour · 4 min left in op · job ≈ 18:42 · next tool change in 12 min".
 * `now` is epoch ms (for the finish time when only the remaining seconds are known).
 */
export function etaLine(eta, now = Date.now()) {
  const e = eta && 'remaining_s' in eta ? eta : normalizeEta(eta);
  if (!e) return '';
  const parts = [];
  const what = [toolText(e.tool), e.op].filter(Boolean).join(' ');
  if (what) parts.push(`Now: ${what}`);
  if (e.op_remaining_s !== null) parts.push(`${approx(e.op_remaining_s)} left in op`);
  let finish = null;
  if (e.finish !== null) finish = new Date(e.finish);
  if ((!finish || Number.isNaN(finish.getTime())) && e.remaining_s !== null) finish = new Date(now + e.remaining_s * 1000);
  if (finish && !Number.isNaN(finish.getTime())) parts.push(`job ≈ ${clock(finish)}${e.remaining_s !== null ? ` (${approx(e.remaining_s)} left)` : ''}`);
  else if (e.remaining_s !== null) parts.push(`${approx(e.remaining_s)} left`);
  if (e.next_tool_change?.in_s !== null && e.next_tool_change?.in_s !== undefined) {
    const t = toolText(e.next_tool_change.tool);
    parts.push(`next tool change${t ? ` (${t})` : ''} in ${approx(e.next_tool_change.in_s)}`);
  } else if (e.tool_changes_remaining === 0) parts.push('no more tool changes');
  return parts.join(' · ');
}

/**
 * Clock end time of each operation while a job runs: ops already finished get null; the rest are
 * now + (op end − time at the current line) × ratio (the live speed correction, default 1).
 */
export function opFinishClock(times, currentTime, ratio = 1, now = Date.now()) {
  return times.map((t) => (t.end <= currentTime ? null : new Date(now + (t.end - currentTime) * (ratio || 1) * 1000)));
}
