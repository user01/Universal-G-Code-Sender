// Job model: combines the parsed program, the post headers and the optional sidecar
// (<program>.job.json, schema bridge/schema/job-sidecar.schema.json). Pure, no Three.js.
//
// Precedence: sidecar > post headers > derived from the toolpath.

import { KIND, splitComments } from './parser.js';
import { toolId, toolNum, zeroOrigin } from './header.js';

/**
 * @param {object} a
 * @param {import('./parser.js').ParsedProgram} a.program
 * @param {ReturnType<import('./header.js').parseHeader>} a.header
 * @param {object|null} [a.sidecar]
 * @returns {Job}
 */
export function buildJob({ program, header, sidecar = null }) {
  const scUnits = sidecar?.units === 'in' ? 25.4 : 1;
  const tools = new Map();
  for (const t of header.tools.values()) tools.set(t.num, { ...t, source: 'header' });
  for (const t of sidecar?.tools ?? []) {
    const n = toolNum(t.id);
    if (n === null) continue;
    const prev = tools.get(n) ?? {};
    tools.set(n, {
      ...prev, ...t, num: n, id: toolId(n),
      diameter: t.diameter !== undefined ? t.diameter * scUnits : prev.diameter,
      flute_length: t.flute_length !== undefined ? t.flute_length * scUnits : prev.flute_length,
      source: 'sidecar',
    });
  }
  // tools only seen at M6
  for (const tc of program.toolChanges) if (tc.tool !== null && !tools.has(tc.tool)) tools.set(tc.tool, { id: toolId(tc.tool), num: tc.tool, type: 'flat', source: 'gcode' });

  const ops = buildOperations({ program, header, sidecar });
  const opOfSeg = assignSegmentsToOps(program, ops);
  const issues = jobIssues(program, ops);

  // --- stock ---------------------------------------------------------------------------
  let stock = null;
  if (sidecar?.stock?.size) {
    const size = sidecar.stock.size.map((v) => v * scUnits);
    stock = { size, origin: sidecar.stock.origin || 'front-left-top', material: sidecar.stock.material, source: 'sidecar', approx: false };
  } else if (header.stock?.size?.[0]) {
    const sz = header.stock.size.slice();
    if (!sz[2]) sz[2] = program.cutBounds ? Math.max(1, -program.cutBounds.min[2]) : 10;
    const scale = header.stock.units === 'in' ? 25.4 : 1;
    const origin = header.zero?.origin;
    // Zero not on the stock (e.g. a jig top): if every cut is above Z0, guess the stock sits on Z0.
    const guess = program.cutBounds && program.cutBounds.min[2] > 0 ? 'front-left-bottom' : 'front-left-top';
    stock = {
      size: sz.map((v) => v * scale),
      origin: origin || guess,
      originAssumed: !origin,
      material: undefined,
      source: 'header',
      approx: header.stock.approx,
      text: header.stock.text,
    };
  }
  if (stock) Object.assign(stock, stockBox(stock.size, stock.origin));

  // --- floor (deepest allowed Z, work) ---------------------------------------------------
  let floor = null;
  if (typeof sidecar?.spoilboard_limit_z === 'number') floor = { z: sidecar.spoilboard_limit_z * scUnits, source: 'sidecar spoilboard_limit_z' };
  else if (header.floor) floor = { z: header.floor.z, source: `header: ${header.floor.text}` };
  else if (stock) floor = { z: stock.min[2], source: 'stock bottom' };

  const clearance = typeof sidecar?.clearance_z_work === 'number'
    ? { z: sidecar.clearance_z_work * scUnits, source: 'sidecar' }
    : header.clearance?.z !== undefined ? { z: header.clearance.z, source: 'header' } : null;

  const fixtures = (sidecar?.fixtures ?? []).map((f) => ({ ...f, box: f.box.map((p) => p.map((v) => v * scUnits)) }));
  const tabs = (sidecar?.tabs ?? []).map((t) => ({ ...t }));

  return {
    name: header.file,
    project: header.project,
    tools,
    ops,
    opOfSeg,
    issues,
    stock,
    floor,
    clearance,
    fixtures,
    tabs,
    cutExtent: header.cutExtent,
    estRuntimeMin: header.estRuntimeMin,
    zero: sidecar?.zero ?? (header.zero ? { text: header.zero.text } : null),
    sources: { sidecar: !!sidecar, header: header.opMarkers.length > 0 || !!header.stock },
  };
}

/** Box (work coords) for a stock of `size` placed by an origin like "front-left-top". */
export function stockBox(size, origin = 'front-left-top') {
  const [sx, sy, sz] = size;
  const o = origin.toLowerCase();
  const center = /center|middle/.test(o);
  const x0 = /right/.test(o) ? -sx : /left/.test(o) ? 0 : center ? -sx / 2 : 0;
  const y0 = /back|rear/.test(o) ? -sy : /front/.test(o) ? 0 : center ? -sy / 2 : 0;
  const z0 = /bottom/.test(o) ? 0 : -sz;
  return { min: [x0, y0, z0], max: [x0 + sx, y0 + sy, z0 + sz] };
}

/**
 * Operations as [{index, name, tool (num|null), toolInGcode?, desc, lineStart, lineEnd, source}], in
 * file order. Lines are 1-based and inclusive. Lines outside every operation (the header, and the
 * tool-change block between two operations: decision D11) belong to no operation.
 *
 * Follows bridge/docs/post-headers.md §4 and its reference implementation
 * (bridge/mcp/src/ugs_mcp/gcode/job.py `operations`): an operation runs from its marker to the line
 * before the next marker, or before the first M6 after its first motion line, whichever comes
 * first; then its end moves back over blank and comment-only lines.
 */
export function buildOperations({ program, header, sidecar }) {
  const n = program.lineCount;
  const moves = program.moveLines ?? Int32Array.from(new Set(program.seg.line));
  const m6Lines = program.toolChanges.map((tc) => tc.line);
  const firstMoveFrom = (line) => { const i = lowerBound(moves, line); return i < moves.length ? moves[i] : null; };
  const toolAtLine = (line) => {
    if (line === null) return null;
    let t = null;
    for (const tc of program.toolChanges) { if (tc.line <= line) t = tc.tool; else break; }
    return t;
  };
  const trimEnd = (start, end) => {
    while (end > start && !hasCode(program.lines[end - 1])) end--;
    return end;
  };

  if (sidecar?.operations?.length) {
    return sidecar.operations.map((o, i) => ({
      index: i + 1, name: o.name, tool: toolNum(o.tool), desc: o.description ?? '',
      lineStart: o.lines[0], lineEnd: o.lines[1], zRange: o.z_range, plungeFeed: o.plunge_feed, source: 'sidecar',
    }));
  }

  let markers = header.opMarkers;
  if (markers.length && markers[0].style === 'freecad') {
    // FreeCAD: (Begin operation: X) runs to the next Begin. Ops with no motion (tool controllers, fixtures) are dropped.
    const ops = [];
    markers.forEach((mk, i) => {
      const next = i + 1 < markers.length ? markers[i + 1].line : n + 1;
      const first = firstMoveFrom(mk.line);
      if (first === null || first >= next) return;
      ops.push({ index: ops.length + 1, name: mk.name, tool: toolAtLine(first), desc: '', lineStart: mk.line, lineEnd: trimEnd(mk.line, next - 1), source: 'header' });
    });
    if (ops.length) return ops;
    markers = [];
  }

  if (markers.length) {
    return markers.map((mk, i) => {
      const first = firstMoveFrom(mk.line);
      let boundary = i + 1 < markers.length ? markers[i + 1].line : n + 1;
      if (first !== null) {
        const m6 = m6Lines.find((l) => l > first && l < boundary);
        if (m6 !== undefined) boundary = m6;
      }
      const planned = header.plannedOps.find((p) => p.index === mk.index);
      const loaded = toolAtLine(first); // the tool in the spindle at the op's first move
      const tool = mk.tool ?? planned?.tool ?? loaded;
      const op = {
        index: mk.index,
        name: mk.name || planned?.name || mk.desc || `op${String(mk.index).padStart(2, '0')}`,
        tool,
        desc: mk.desc || planned?.desc || '',
        lineStart: mk.line,
        lineEnd: trimEnd(mk.line, boundary - 1),
        source: 'header',
      };
      if (loaded !== null && tool !== null && loaded !== tool) op.toolInGcode = loaded;
      return op;
    });
  }

  // Fallback: one operation per M6 segment (motion before the first M6 is its own segment), or the whole file.
  const tcs = program.toolChanges;
  const starts = tcs.length && tcs[0].line === 1 ? m6Lines : [1, ...m6Lines];
  const ops = [];
  starts.forEach((start, i) => {
    const next = i + 1 < starts.length ? starts[i + 1] : n + 1;
    const first = firstMoveFrom(start);
    if (first === null || first >= next) return;
    const tool = toolAtLine(first);
    ops.push({
      index: ops.length + 1,
      name: tcs.length ? (tool !== null ? `${toolId(tool)} (from line ${start})` : `no tool (lines ${start}–)`) : 'program',
      tool,
      desc: tcs.length ? 'split at tool changes (no operation headers)' : 'no operation headers',
      lineStart: start,
      lineEnd: trimEnd(start, next - 1),
      source: 'gcode',
    });
  });
  return ops;
}

/**
 * Job-level findings that aren't tied to one segment (post-headers.md §3, §4): an M6 with no T word
 * since the previous M6, and an op marker whose tool differs from the tool loaded at its first move.
 * @returns {{kind:string,line:number,msg:string}[]}
 */
export function jobIssues(program, ops) {
  const out = [];
  for (const tc of program.toolChanges) {
    if (tc.hasT === false) out.push({ kind: 'm6_no_t', line: tc.line, msg: `M6 without a T word since the previous tool change${tc.tool !== null ? ` (would load ${toolId(tc.tool)} again)` : ''}` });
  }
  for (const op of ops) {
    if (op.toolInGcode !== undefined) out.push({ kind: 'tool_mismatch', line: op.lineStart, msg: `OP ${String(op.index).padStart(2, '0')} ${op.name}: marker says ${toolId(op.tool)}, but ${toolId(op.toolInGcode)} is loaded at its first move` });
  }
  return out;
}

function hasCode(raw) {
  return raw !== undefined && splitComments(raw).code.trim() !== '';
}

function lowerBound(arr, v) { // first index with arr[i] >= v
  let lo = 0, hi = arr.length;
  while (lo < hi) { const m = (lo + hi) >> 1; if (arr[m] < v) lo = m + 1; else hi = m; }
  return lo;
}

/** Int16Array: operation index (0-based into ops) for each segment, or -1. */
export function assignSegmentsToOps(program, ops) {
  const { count, line } = program.seg;
  const out = new Int16Array(count).fill(-1);
  // ops may overlap or be unsorted in a sidecar; build a line -> op lookup (last wins)
  const lut = new Int16Array(program.lineCount + 2).fill(-1);
  ops.forEach((o, i) => { for (let l = Math.max(1, o.lineStart); l <= Math.min(o.lineEnd, program.lineCount); l++) lut[l] = i; });
  for (let i = 0; i < count; i++) out[i] = lut[line[i]];
  return out;
}

/** Cut bounds (feed + arc moves, work coords) of each operation, without the cutter radius. */
export function opCutBounds(program, job) {
  const res = job.ops.map(() => ({ min: [Infinity, Infinity, Infinity], max: [-Infinity, -Infinity, -Infinity], segs: 0, segStart: -1, segEnd: -1 }));
  const { count, pos, kind } = program.seg;
  for (let i = 0; i < count; i++) {
    const o = job.opOfSeg[i];
    if (o < 0) continue;
    const r = res[o];
    if (r.segStart < 0) r.segStart = i;
    r.segEnd = i;
    if (kind[i] === KIND.RAPID) continue;
    r.segs++;
    for (let k = 0; k < 6; k++) {
      const v = pos[i * 6 + k], a = k % 3;
      if (v < r.min[a]) r.min[a] = v;
      if (v > r.max[a]) r.max[a] = v;
    }
  }
  return res;
}

/** Overall cut extent including the cutter radius of each operation's tool. */
export function cutExtentWithCutter(program, job) {
  const per = opCutBounds(program, job);
  const mn = [Infinity, Infinity, Infinity], mx = [-Infinity, -Infinity, -Infinity];
  per.forEach((b, i) => {
    if (!b.segs) return;
    const d = job.tools.get(job.ops[i].tool)?.diameter ?? 0;
    const r = d / 2;
    mn[0] = Math.min(mn[0], b.min[0] - r); mx[0] = Math.max(mx[0], b.max[0] + r);
    mn[1] = Math.min(mn[1], b.min[1] - r); mx[1] = Math.max(mx[1], b.max[1] + r);
    mn[2] = Math.min(mn[2], b.min[2]); mx[2] = Math.max(mx[2], b.max[2]);
  });
  return { min: mn, max: mx };
}

/** Warning flag bits per segment. */
export const WARN = Object.freeze({ BELOW_FLOOR: 1, OUTSIDE_ENVELOPE: 2 });

/**
 * Find warnings. `wco` places work coordinates in the machine (machine = work + wco).
 * @param {object} o
 * @param {import('./parser.js').ParsedProgram} o.program
 * @param {Job} o.job
 * @param {number[]} o.wco
 * @param {{min:number[],max:number[]}} [o.envelope]  machine coords
 * @param {number} [o.eps=0.01]
 * @returns {{flags:Uint8Array, items:{kind:string,line:number,msg:string}[], counts:{belowFloor:number,outsideEnvelope:number}}}
 */
export function analyzeWarnings({ program, job, wco, envelope, eps = 0.01 }) {
  const { count, pos, kind, line } = program.seg;
  const flags = new Uint8Array(count);
  const items = (job.issues ?? []).slice(); // job-level findings first (M6 without T, tool mismatch)
  const counts = { belowFloor: 0, outsideEnvelope: 0, other: items.length };
  const floorZ = job.floor?.z;
  const lastLine = { floor: -1, env: -1 };
  for (let i = 0; i < count; i++) {
    const o = i * 6;
    if (floorZ !== undefined && kind[i] !== KIND.RAPID) {
      const z = Math.min(pos[o + 2], pos[o + 5]);
      if (z < floorZ - eps) {
        flags[i] |= WARN.BELOW_FLOOR;
        counts.belowFloor++;
        if (line[i] !== lastLine.floor && items.length < 500) items.push({ kind: 'below_floor', line: line[i], msg: `Z${z.toFixed(3)} below ${job.floor.source} (Z${floorZ})` });
        lastLine.floor = line[i];
      }
    }
    if (envelope) {
      let out = false;
      for (let k = 0; k < 6 && !out; k++) {
        const a = k % 3;
        const m = pos[o + k] + wco[a];
        if (m < envelope.min[a] - eps || m > envelope.max[a] + eps) out = true;
      }
      if (out) {
        flags[i] |= WARN.OUTSIDE_ENVELOPE;
        counts.outsideEnvelope++;
        if (line[i] !== lastLine.env && items.length < 500) items.push({ kind: 'outside_envelope', line: line[i], msg: 'move outside the machine envelope (with the current work offset)' });
        lastLine.env = line[i];
      }
    }
  }
  return { flags, items, counts };
}

/** Re-export for UI convenience. */
export { toolId, zeroOrigin };

/**
 * @typedef {object} Job
 * @property {string|undefined} name
 * @property {Map<number, object>} tools
 * @property {{index:number,name:string,tool:number|null,desc:string,lineStart:number,lineEnd:number,source:string}[]} ops
 * @property {Int16Array} opOfSeg
 * @property {{size:number[],origin:string,min:number[],max:number[],source:string,approx:boolean}|null} stock
 * @property {{z:number,source:string}|null} floor
 * @property {{z:number,source:string}|null} clearance
 */
