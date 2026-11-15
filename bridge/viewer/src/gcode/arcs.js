// Arc math for G2/G3. Pure functions, no Three.js.
//
// Points are [x, y, z] arrays. `plane` is 17 (XY), 18 (ZX) or 19 (YZ), as in G17/G18/G19.

/** Axis indices [first, second, linear] for a plane. G18 is ZX (first axis Z), as in GRBL/NIST. */
export function planeAxes(plane) {
  switch (plane) {
    case 18: return [2, 0, 1];
    case 19: return [1, 2, 0];
    default: return [0, 1, 2];
  }
}

/**
 * Center of an R-form arc, using GRBL's formula. A negative R selects the arc longer than 180°.
 * Returns the center as [x, y, z] (the linear axis copies the start), or null if R is too small
 * for the chord (GRBL error 33); callers may then treat the move as a straight line.
 */
export function arcCenterFromR(start, end, r, clockwise, plane = 17) {
  const [a, b] = planeAxes(plane);
  const x = end[a] - start[a];
  const y = end[b] - start[b];
  const d2 = x * x + y * y;
  if (d2 === 0) return null; // R form can't describe a full circle
  let disc = 4 * r * r - d2;
  if (disc < 0) {
    // Tolerate rounding in posted files: GRBL allows ~0.5 µm; we allow up to 0.1 % of the chord.
    if (disc > -d2 * 0.002) disc = 0; else return null;
  }
  let h = -Math.sqrt(disc) / Math.sqrt(d2);
  if (!clockwise) h = -h;
  if (r < 0) h = -h;
  const c = start.slice();
  c[a] = start[a] + 0.5 * (x - y * h);
  c[b] = start[b] + 0.5 * (y + x * h);
  return c;
}

/**
 * Tessellate an arc into points (excluding the start, including the exact end).
 * The linear axis is interpolated (helix). The radius is interpolated from start to end, so
 * slightly inconsistent posted arcs still end exactly at the programmed point.
 *
 * @param {object} o
 * @param {number[]} o.start
 * @param {number[]} o.end
 * @param {number[]} o.center
 * @param {boolean} o.clockwise  G2 = true
 * @param {number} [o.plane=17]
 * @param {number} [o.tolerance=0.01]  max chord deviation, mm
 * @param {number} [o.turns=1]  P word: number of turns (full extra turns = turns - 1)
 * @param {number} [o.maxSegments=720]
 * @returns {number[][]}
 */
export function tessellateArc({ start, end, center, clockwise, plane = 17, tolerance = 0.01, turns = 1, maxSegments = 720 }) {
  const [a, b, l] = planeAxes(plane);
  const sa = start[a] - center[a], sb = start[b] - center[b];
  const ea = end[a] - center[a], eb = end[b] - center[b];
  const r0 = Math.hypot(sa, sb);
  const r1 = Math.hypot(ea, eb);
  const a0 = Math.atan2(sb, sa);
  const a1 = Math.atan2(eb, ea);
  let sweep = a1 - a0;
  const eps = 1e-9;
  if (clockwise) {
    if (sweep >= -eps) sweep -= 2 * Math.PI;
  } else if (sweep <= eps) {
    sweep += 2 * Math.PI;
  }
  const extra = Math.max(0, Math.round(turns) - 1);
  sweep += (clockwise ? -1 : 1) * extra * 2 * Math.PI;

  const r = Math.max(r0, r1);
  let n;
  if (r < 1e-9) n = 1;
  else {
    const step = tolerance >= r ? Math.PI / 2 : 2 * Math.acos(1 - tolerance / r);
    n = Math.ceil(Math.abs(sweep) / step);
  }
  n = Math.min(Math.max(n, 1), maxSegments);

  const pts = [];
  for (let i = 1; i < n; i++) {
    const t = i / n;
    const ang = a0 + sweep * t;
    const rr = r0 + (r1 - r0) * t;
    const p = [0, 0, 0];
    p[a] = center[a] + rr * Math.cos(ang);
    p[b] = center[b] + rr * Math.sin(ang);
    p[l] = start[l] + (end[l] - start[l]) * t;
    pts.push(p);
  }
  pts.push(end.slice());
  return pts;
}

/** Signed sweep angle (radians) of an arc; negative = clockwise. Exposed for tests. */
export function arcSweep({ start, end, center, clockwise, plane = 17 }) {
  const [a, b] = planeAxes(plane);
  const a0 = Math.atan2(start[b] - center[b], start[a] - center[a]);
  const a1 = Math.atan2(end[b] - center[b], end[a] - center[a]);
  let sweep = a1 - a0;
  if (clockwise) { if (sweep >= -1e-9) sweep -= 2 * Math.PI; } else if (sweep <= 1e-9) sweep += 2 * Math.PI;
  return sweep;
}
