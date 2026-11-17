// Turn the parsed program + job into the material-removal simulation input. Pure, runs in Node.
//
// Principle (from the cnc-sim-viewer skill): a wrong picture is worse than none. A tool whose
// diameter is unknown, or whose shape we can't model (V-bit, chamfer, engraving, tapered, drag), cuts
// nothing in the simulation and is listed in `skipped` with the reason, so the page can say so.
import { KIND, FLAG } from '../gcode/parser.js';
import { toolId } from '../gcode/header.js';
import { sampleProfile } from './heightfield.js';

/** Default cell size (mm) and the cell budget for big stock (the cell grows to stay under it). */
export const DEFAULT_CELL = 0.25;
export const MAX_CELLS = 700_000;

/** Cell size for a stock of sx × sy mm: `cell`, coarsened in 0.05 mm steps until the grid fits MAX_CELLS. */
export function cellSize(sx, sy, cell = DEFAULT_CELL, maxCells = MAX_CELLS) {
  let cs = cell;
  while (Math.round(sx / cs) * Math.round(sy / cs) > maxCells) cs = Math.round((cs + 0.05) * 100) / 100;
  return cs;
}

/**
 * How the simulation models one tool: {kind:'flat'|'ball', r} or {skip: reason}.
 * @param {object|undefined} t  job tool
 */
export function toolModel(t) {
  if (!t) return { skip: 'tool not described in the file' };
  const type = String(t.type ?? 'flat').toLowerCase();
  const d = t.diameter;
  if (!(d > 0)) return { skip: 'diameter unknown' };
  if (type === 'flat' || type === 'endmill' || type === 'end mill' || type === 'upcut' || type === 'downcut') return { kind: 'flat', r: d / 2 };
  if (type === 'ball' || type === 'ballnose' || type === 'ball nose') return { kind: 'ball', r: d / 2 };
  return { skip: `${type} shape not simulated` };
}

/**
 * @param {import('../gcode/parser.js').ParsedProgram} program
 * @param {import('../gcode/job.js').Job} job
 * @param {{cell?: number}} [o]
 * @returns {{ok:false, reason:string} | {ok:true, nx:number, ny:number, cs:number, top:number, bottom:number,
 *   origin:number[], seg:Float32Array, rad:Float32Array, prof:Int16Array, profs:Float32Array[],
 *   skipped:{tool:string, reason:string, segments:number}[], cutting:number}}
 */
export function buildSimInput(program, job, { cell } = {}) {
  const s = job.stock;
  if (!s) return { ok: false, reason: 'no stock size (STOCK header or sidecar)' };
  const sx = s.max[0] - s.min[0], sy = s.max[1] - s.min[1];
  if (!(sx > 0 && sy > 0)) return { ok: false, reason: 'stock has no area' };
  const cs = cellSize(sx, sy, cell);
  const nx = Math.max(1, Math.round(sx / cs)), ny = Math.max(1, Math.round(sy / cs));
  const top = s.max[2], bottom = s.min[2];

  const { count, pos, kind, flags, line } = program.seg;
  const seg = new Float32Array(count * 6);
  const rad = new Float32Array(count);
  const prof = new Int16Array(count).fill(-1);
  const profs = [];
  const profIndex = new Map(); // tool num -> profs index
  const models = new Map();
  const skipped = new Map();
  const tcs = program.toolChanges.filter((tc) => tc.tool !== null);
  let ti = -1, tool = null, cutting = 0;
  // A move that touches the stock before any M6: the file loads its first tool by hand (no M6), so
  // use the first tool the file names, if there's exactly one candidate.
  const firstTool = tcs.length ? tcs[0].tool : (job.tools.size === 1 ? [...job.tools.keys()][0] : null);

  for (let i = 0; i < count; i++) {
    while (ti + 1 < tcs.length && tcs[ti + 1].line <= line[i]) tool = tcs[++ti].tool;
    const o = i * 6;
    seg[o] = pos[o] - s.min[0]; seg[o + 1] = pos[o + 1] - s.min[1]; seg[o + 2] = pos[o + 2];
    seg[o + 3] = pos[o + 3] - s.min[0]; seg[o + 4] = pos[o + 4] - s.min[1]; seg[o + 5] = pos[o + 5];
    if (kind[i] === KIND.PROBE || (flags[i] & FLAG.INCOMPLETE_START)) continue;
    if (pos[o + 2] >= top && pos[o + 5] >= top) continue; // above the stock: nothing to decide
    const t = tool ?? firstTool;
    if (!models.has(t)) models.set(t, toolModel(t === null ? undefined : job.tools.get(t)));
    const m = models.get(t);
    if (m.skip) {
      const key = t === null ? '?' : toolId(t);
      const e = skipped.get(key) ?? { tool: key, reason: t === null ? 'no tool loaded (no M6 before the cut)' : m.skip, segments: 0 };
      e.segments++;
      skipped.set(key, e);
      continue;
    }
    rad[i] = m.r;
    cutting++;
    if (m.kind === 'ball') {
      if (!profIndex.has(t)) { profIndex.set(t, profs.length); profs.push(sampleProfile({ kind: 'ball', R: m.r }, m.r)); }
      prof[i] = profIndex.get(t);
    }
  }
  return { ok: true, nx, ny, cs, top, bottom, origin: [s.min[0], s.min[1]], seg, rad, prof, profs, skipped: [...skipped.values()], cutting };
}

/** Simulation position for "the program up to and including `seg`": segments < seg+1 complete. */
export function simIndexAfterSeg(seg) {
  return { i: seg + 1, f: 0 };
}
