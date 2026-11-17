// Where the stock really is, and whether that's sane. Pure, runs in Node.
//
// The stock's machine position = live WCO (G54) + the stock box relative to work zero (from the
// sidecar or the STOCK/ZERO header; default X0 Y0 = front-left corner, Z0 = top). Checks:
//   - the stock overlaps the BitSetter (profile bitsetter.xy, footprint radius from the bed section)
//   - the stock leaves the spoilboard (profile bed.spoilboard; assumed until measured)
//   - a cut (below the stock top) runs outside the stock in XY by more than its tool diameter + 1 mm
import { KIND } from '../gcode/parser.js';

/** Stock box in machine coordinates: work box + wco. */
export function stockMachineBox(stock, wco) {
  if (!stock || !wco) return null;
  return { min: stock.min.map((v, i) => v + wco[i]), max: stock.max.map((v, i) => v + wco[i]) };
}

/** Does the XY rectangle [min,max] intersect the circle (c, r)? */
export function rectHitsCircle(min, max, c, r) {
  const cx = Math.max(min[0], Math.min(c[0], max[0]));
  const cy = Math.max(min[1], Math.min(c[1], max[1]));
  return (cx - c[0]) ** 2 + (cy - c[1]) ** 2 < r * r;
}

/** Is the XY rectangle a inside b (with eps)? */
export function rectInside(a, b, eps = 0.01) {
  return a.min[0] >= b.min[0] - eps && a.min[1] >= b.min[1] - eps && a.max[0] <= b.max[0] + eps && a.max[1] <= b.max[1] + eps;
}

/**
 * @param {object} o
 * @param {import('../data/profile.js').MachineProfile} o.profile
 * @param {object|null} o.stock  job.stock (work coords)
 * @param {number[]} o.wco
 * @param {import('../gcode/parser.js').ParsedProgram} [o.program]
 * @param {import('../gcode/job.js').Job} [o.job]
 * @param {string} [o.wcoSource]  'live' | 'user' | 'assumed'
 * @returns {{box:{min:number[],max:number[]}|null, items:{kind:string, msg:string, line?:number, assumed?:boolean}[]}}
 */
export function placementChecks({ profile, stock, wco, program = null, job = null, wcoSource = 'live' }) {
  const items = [];
  const box = stockMachineBox(stock, wco);
  const where = wcoSource === 'live' ? '' : ` (with the ${wcoSource} work offset)`;
  if (box) {
    const bs = profile.bitsetter;
    const bsR = profile.bed?.bitsetterRadius ?? 20;
    if (bs?.xy && rectHitsCircle(box.min, box.max, bs.xy, bsR)) {
      items.push({ kind: 'stock_on_bitsetter', msg: `Stock overlaps the BitSetter at X${bs.xy[0]} Y${bs.xy[1]}${where}` });
    }
    const sb = profile.bed?.spoilboard;
    if (sb && !rectInside(box, sb)) {
      items.push({ kind: 'stock_off_spoilboard', msg: `Stock reaches past the spoilboard${where}${profile.bed.verified ? '' : ' (spoilboard size assumed, not measured)'}`, assumed: !profile.bed.verified });
    }
  }
  if (stock && program && job) {
    const { count, pos, kind, line } = program.seg;
    const top = stock.max[2];
    const toolAt = toolLookup(program);
    let n = 0, first = null, worst = 0;
    for (let i = 0; i < count; i++) {
      if (kind[i] === KIND.PROBE) continue;
      const o = i * 6;
      if (pos[o + 2] >= top && pos[o + 5] >= top) continue;
      const d = job.tools.get(toolAt(line[i]))?.diameter ?? 0;
      const m = d + 1;
      for (const k of [0, 3]) {
        const x = pos[o + k], y = pos[o + k + 1];
        const out = Math.max(stock.min[0] - x, x - stock.max[0], stock.min[1] - y, y - stock.max[1]);
        if (out > m) {
          n++;
          if (out > worst) worst = out;
          if (first === null) first = line[i];
          break;
        }
      }
    }
    if (n) items.push({ kind: 'cut_outside_stock', line: first, msg: `${n} cutting move${n === 1 ? '' : 's'} outside the stock by up to ${worst.toFixed(1)} mm (first at line ${first}): stock size or zero wrong?` });
  }
  return { box, items };
}

/** line → tool number loaded at that line (last M6 at or before it). */
function toolLookup(program) {
  const tcs = program.toolChanges.filter((t) => t.tool !== null);
  const first = tcs.length ? tcs[0].tool : null;
  return (ln) => {
    let t = first;
    for (const tc of tcs) { if (tc.line <= ln) t = tc.tool; else break; }
    return t;
  };
}
