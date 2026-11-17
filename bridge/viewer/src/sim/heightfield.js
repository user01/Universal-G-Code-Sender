// Material-removal simulation: a heightfield of the stock top, one Float32 per cell. Pure (no DOM,
// no Three.js), so it runs in the Web Worker (sim-worker.js) and in Node tests.
//
// Ported from the operator's cnc-sim-viewer skill (assets/sim-worker.js; its licence allows copying
// and modification). Changes: segments are independent start/end pairs (our parser's segment table)
// instead of a chained point list, a per-segment "cuts" flag replaces the move type, and the
// IndexedDB cache is not ported (a job here is simulated in about a second; see README).
//
// A flat end mill lowers every cell whose centre lies within the cutter radius of the swept segment,
// exactly (analytic segment/disc interval). Ball cutters sweep their underside profile (sampled).
// State is "segments < ci complete, plus fraction cf of segment ci". Keyframe snapshots every K
// segments make backward scrubbing a snapshot load plus a short replay.

/** Profile sample spacing (mm) for shaped cutters. */
export const PDR = 0.02;

/**
 * Underside height above the tip at radius r for a shaped cutter.
 * @param {{kind:'ball'|'taper', R:number, deg?:number, flute?:number}} sh
 */
export function toolProfile(sh, r) {
  if (sh.kind === 'ball') return sh.R - Math.sqrt(Math.max(sh.R * sh.R - r * r, 0));
  const a = (sh.deg * Math.PI) / 180, rt = sh.R * Math.cos(a), h0 = sh.R * (1 - Math.sin(a));
  if (r <= rt) return sh.R - Math.sqrt(Math.max(sh.R * sh.R - r * r, 0));
  return Math.min(h0 + (r - rt) / Math.tan(a), sh.flute ?? Infinity);
}

/** Sampled profile (Float32Array, every PDR mm) up to rmax; `.rmax` is attached. */
export function sampleProfile(sh, rmax) {
  const n = Math.ceil(rmax / PDR) + 2;
  const pr = new Float32Array(n + 1);
  for (let i = 0; i <= n; i++) pr[i] = toolProfile(sh, Math.min(i * PDR, rmax));
  return pr;
}

export class Heightfield {
  /**
   * @param {object} o
   * @param {number} o.nx  cells in X
   * @param {number} o.ny  cells in Y
   * @param {number} o.cs  cell size (mm); cell (i,j) centre is ((i+.5)cs, (j+.5)cs) in stock-local XY
   * @param {number} o.top  stock top Z (work)
   * @param {Float32Array} o.seg  6 floats per segment: x0 y0 z0 x1 y1 z1 (stock-local XY, work Z)
   * @param {Float32Array} o.rad  footprint radius per segment (0 = cuts nothing)
   * @param {Int16Array} [o.prof]  index into `profs` per segment, -1 = flat
   * @param {Float32Array[]} [o.profs]  sampled underside profiles
   * @param {number} [o.K]  snapshot interval (segments)
   */
  constructor({ nx, ny, cs, top, seg, rad, prof = null, profs = [], K = 0 }) {
    Object.assign(this, { nx, ny, cs, top, seg, rad, prof, profs });
    this.S = rad.length;
    // at most ~300 MB of snapshots
    this.K = K || Math.max(1000, Math.ceil((this.S * nx * ny * 4) / 3e8));
    this.h = new Float32Array(nx * ny).fill(top);
    this.ci = 0;
    this.cf = 0;
    this.snaps = [];
    this._clean();
  }

  _clean() { this.dx0 = 1e9; this.dy0 = 1e9; this.dx1 = -1; this.dy1 = -1; }
  _dirty(i0, j0, i1, j1) {
    if (i0 < this.dx0) this.dx0 = i0; if (j0 < this.dy0) this.dy0 = j0;
    if (i1 > this.dx1) this.dx1 = i1; if (j1 > this.dy1) this.dy1 = j1;
  }
  _dirtyAll() { this.dx0 = 0; this.dy0 = 0; this.dx1 = this.nx - 1; this.dy1 = this.ny - 1; }

  /** Run the whole program once, keeping snapshots; returns the final heights (a copy). */
  build(onProgress) {
    const { S, K } = this;
    this.h.fill(this.top);
    this.snaps = [new Float32Array(this.h)];
    for (let k = 0; k < S; k++) {
      this.apply(k, 0, 1);
      if ((k + 1) % K === 0) this.snaps.push(new Float32Array(this.h));
      if (onProgress && k % 2500 === 0) onProgress(k / S);
    }
    const final = new Float32Array(this.h);
    this.h.fill(this.top);
    this.ci = 0; this.cf = 0;
    this._clean();
    return final;
  }

  /** Apply the part [ta, tb] of segment k. */
  apply(k, ta, tb) {
    if (tb <= ta) return;
    const r = this.rad[k];
    if (!(r > 0)) return;
    const P = this.seg, o = k * 6;
    const ax = P[o], ay = P[o + 1], az = P[o + 2], bx = P[o + 3], by = P[o + 4], bz = P[o + 5];
    if (az >= this.top && bz >= this.top) return; // entirely above the stock
    const pi = this.prof ? this.prof[k] : -1;
    if (pi >= 0 && this.profs[pi]) { this._applyShaped(ax, ay, az, bx, by, bz, r, ta, tb, this.profs[pi]); return; }
    const { nx, ny, cs, h } = this;
    const r2 = r * r;
    const dx = bx - ax, dy = by - ay, dz = bz - az;
    const pax = ax + dx * ta, pay = ay + dy * ta, pbx = ax + dx * tb, pby = ay + dy * tb;
    let i0 = Math.floor((Math.min(pax, pbx) - r) / cs - 0.5), i1 = Math.ceil((Math.max(pax, pbx) + r) / cs - 0.5);
    let j0 = Math.floor((Math.min(pay, pby) - r) / cs - 0.5), j1 = Math.ceil((Math.max(pay, pby) + r) / cs - 0.5);
    if (i0 < 0) i0 = 0; if (j0 < 0) j0 = 0; if (i1 > nx - 1) i1 = nx - 1; if (j1 > ny - 1) j1 = ny - 1;
    if (i1 < i0 || j1 < j0) return;
    this._dirty(i0, j0, i1, j1);
    const a = dx * dx + dy * dy;
    for (let j = j0; j <= j1; j++) {
      const cy = (j + 0.5) * cs, qy = ay - cy, row = j * nx;
      for (let i = i0; i <= i1; i++) {
        const cx = (i + 0.5) * cs, qx = ax - cx;
        const c0 = qx * qx + qy * qy - r2;
        let lo, hi;
        if (a < 1e-12) {
          if (c0 > 0) continue;
          lo = 0; hi = 1;
        } else {
          const b = 2 * (qx * dx + qy * dy);
          const disc = b * b - 4 * a * c0;
          if (disc < 0) continue;
          const sq = Math.sqrt(disc);
          lo = (-b - sq) / (2 * a); hi = (-b + sq) / (2 * a);
        }
        if (lo < ta) lo = ta;
        if (hi > tb) hi = tb;
        if (lo > hi) continue;
        const z0 = az + dz * lo, z1 = az + dz * hi;
        const z = z0 < z1 ? z0 : z1;
        if (z < h[row + i]) h[row + i] = z;
      }
    }
  }

  // Shaped (ball / tapered ball) cutters: a cell drops to the minimum over the move of tipZ(t) + profile(distance(t)),
  // evaluated at the closest approach and every <= cs/2 along the part of the move inside the footprint circle.
  _applyShaped(ax, ay, az, bx, by, bz, r, ta, tb, pr) {
    const { nx, ny, cs, h } = this;
    const r2 = r * r, np = pr.length - 2;
    const dx = bx - ax, dy = by - ay, dz = bz - az;
    const pax = ax + dx * ta, pay = ay + dy * ta, pbx = ax + dx * tb, pby = ay + dy * tb;
    let i0 = Math.floor((Math.min(pax, pbx) - r) / cs - 0.5), i1 = Math.ceil((Math.max(pax, pbx) + r) / cs - 0.5);
    let j0 = Math.floor((Math.min(pay, pby) - r) / cs - 0.5), j1 = Math.ceil((Math.max(pay, pby) + r) / cs - 0.5);
    if (i0 < 0) i0 = 0; if (j0 < 0) j0 = 0; if (i1 > nx - 1) i1 = nx - 1; if (j1 > ny - 1) j1 = ny - 1;
    if (i1 < i0 || j1 < j0) return;
    this._dirty(i0, j0, i1, j1);
    const a = dx * dx + dy * dy, Lseg = Math.sqrt(a + dz * dz);
    const prof = (d2) => { const q = Math.sqrt(d2) / PDR; let i = q | 0; if (i > np) i = np; const f = q - i; return pr[i] * (1 - f) + pr[i + 1] * f; };
    for (let j = j0; j <= j1; j++) {
      const cy = (j + 0.5) * cs, qy = ay - cy, row = j * nx;
      for (let i = i0; i <= i1; i++) {
        const cx = (i + 0.5) * cs, qx = ax - cx;
        const c0 = qx * qx + qy * qy - r2;
        if (a < 1e-12) {
          if (c0 > 0) continue;
          const zz = Math.min(az + dz * ta, az + dz * tb) + prof(qx * qx + qy * qy);
          if (zz < h[row + i]) h[row + i] = zz;
          continue;
        }
        const b = 2 * (qx * dx + qy * dy);
        const disc = b * b - 4 * a * c0;
        if (disc < 0) continue;
        const sq = Math.sqrt(disc);
        let lo = (-b - sq) / (2 * a), hi = (-b + sq) / (2 * a);
        if (lo < ta) lo = ta;
        if (hi > tb) hi = tb;
        if (lo > hi) continue;
        let tc = -b / (2 * a);
        if (tc < lo) tc = lo; else if (tc > hi) tc = hi;
        let best = h[row + i];
        const ns = Math.min(64, Math.max(2, Math.ceil(((hi - lo) * Lseg) / (0.5 * cs))));
        for (let q = 0; q <= ns; q++) {
          const t = lo + ((hi - lo) * q) / ns;
          const ex = qx + dx * t, ey = qy + dy * t, z = az + dz * t + prof(ex * ex + ey * ey);
          if (z < best) best = z;
        }
        { const ex = qx + dx * tc, ey = qy + dy * tc, z = az + dz * tc + prof(ex * ex + ey * ey); if (z < best) best = z; }
        h[row + i] = best;
      }
    }
  }

  /** Move the state to "segments < i complete plus fraction f of segment i". Needs build() first. */
  goto(i, f = 0) {
    const { S, K, snaps } = this;
    if (i >= S) { i = S; f = 0; }
    if (i < 0) { i = 0; f = 0; }
    const backward = i < this.ci || (i === this.ci && f < this.cf);
    const s = Math.min(Math.floor(i / K), snaps.length - 1);
    if (backward || s * K > this.ci) {
      this.h.set(snaps[s]);
      this.ci = s * K; this.cf = 0;
      this._dirtyAll();
    }
    if (i === this.ci) {
      if (f > this.cf) this.apply(this.ci, this.cf, f);
    } else {
      if (this.cf < 1) this.apply(this.ci, this.cf, 1);
      for (let k = this.ci + 1; k < i; k++) this.apply(k, 0, 1);
      if (f > 0) this.apply(i, 0, f);
    }
    this.ci = i; this.cf = f;
  }

  /** The changed rectangle since the last call (copied), or null. */
  takeDirty() {
    if (this.dx1 < 0) return null;
    const { nx, ny, h } = this;
    const x0 = Math.max(0, this.dx0), y0 = Math.max(0, this.dy0), x1 = Math.min(nx - 1, this.dx1), y1 = Math.min(ny - 1, this.dy1);
    const w = x1 - x0 + 1, hh = y1 - y0 + 1;
    const data = new Float32Array(w * hh);
    for (let j = 0; j < hh; j++) {
      const src = (y0 + j) * nx + x0;
      data.set(h.subarray(src, src + w), j * w);
    }
    this._clean();
    return { x0, y0, w, h: hh, data };
  }
}
