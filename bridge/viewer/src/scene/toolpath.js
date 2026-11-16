// Toolpath geometry in WORK coordinates (the layer's group is placed at the work offset).
//
// One merged LineSegments per (operation × class): cuts, plunges, rapids (dashed). Every object
// keeps `segIdx` (global segment index per local segment, ascending), so scrubbing is a binary
// search + setDrawRange, and picking maps back to the program's segment table.
import * as THREE from 'three';
import { KIND, FLAG } from '../gcode/parser.js';
import { WARN } from '../gcode/job.js';
import { makeLabel, disposeTree } from './labels.js';

export const OP_COLORS = ['#4e9af1', '#3ec7a3', '#a77bf3', '#f28e2b', '#59c3e8', '#8bd346', '#e66bb6', '#c9a26b', '#7f8cff', '#2fb37a', '#d98cf0', '#9fc5e8'];
export const COLORS = { plunge: '#ffe14d', rapid: '#8792a2', warn: '#ff3b30', other: '#9aa3ad', highlight: '#ffffff', toolChange: '#ffd27f' };

export function opColor(i) { return i < 0 ? COLORS.other : OP_COLORS[i % OP_COLORS.length]; }

function upperBound(arr, v) { // first index with arr[i] > v
  let lo = 0, hi = arr.length;
  while (lo < hi) { const m = (lo + hi) >> 1; if (arr[m] <= v) lo = m + 1; else hi = m; }
  return lo;
}

export class ToolpathLayer {
  constructor() {
    this.group = new THREE.Group();
    this.group.name = 'toolpath';
    this.objects = []; // {obj, segIdx, op, cls}
    this.clipPlanes = [];
    this.opVisible = [];
    this.classVisible = { cut: true, plunge: true, rapid: true, warn: true };
    this.scrubToolEnabled = true; // the tool at the scrub position (off in live mode)
  }

  /**
   * @param {import('../gcode/parser.js').ParsedProgram} program
   * @param {import('../gcode/job.js').Job} job
   * @param {{flags:Uint8Array}} warnings
   */
  build(program, job, warnings) {
    this.dispose();
    this.program = program;
    this.job = job;
    const { count, pos, kind, flags } = program.seg;
    const nOps = job.ops.length;
    const bucketOf = (i) => (job.opOfSeg[i] < 0 ? nOps : job.opOfSeg[i]); // last bucket = outside any op
    const classOf = (i) => (kind[i] === KIND.RAPID ? 2 : flags[i] & FLAG.PLUNGE ? 1 : 0);

    // count per bucket × class
    const counts = Array.from({ length: nOps + 1 }, () => [0, 0, 0]);
    let warnCount = 0;
    for (let i = 0; i < count; i++) {
      counts[bucketOf(i)][classOf(i)]++;
      if (warnings.flags[i]) warnCount++;
    }
    const bufs = counts.map((c) => c.map((n) => ({ pos: new Float32Array(n * 6), idx: new Int32Array(n), n: 0 })));
    const warnBuf = { pos: new Float32Array(warnCount * 6), idx: new Int32Array(warnCount), n: 0 };
    for (let i = 0; i < count; i++) {
      const b = bufs[bucketOf(i)][classOf(i)];
      b.pos.set(pos.subarray(i * 6, i * 6 + 6), b.n * 6);
      b.idx[b.n++] = i;
      if (warnings.flags[i]) {
        warnBuf.pos.set(pos.subarray(i * 6, i * 6 + 6), warnBuf.n * 6);
        warnBuf.idx[warnBuf.n++] = i;
      }
    }

    this.opVisible = Array(nOps + 1).fill(true);
    for (let o = 0; o <= nOps; o++) {
      const color = new THREE.Color(opColor(o === nOps ? -1 : o));
      const [cut, plunge, rapid] = bufs[o];
      if (cut.n) this._add(cut, o, 'cut', new THREE.LineBasicMaterial({ color }));
      if (plunge.n) this._add(plunge, o, 'plunge', new THREE.LineBasicMaterial({ color: COLORS.plunge }));
      if (rapid.n) {
        const obj = this._add(rapid, o, 'rapid', new THREE.LineDashedMaterial({ color: COLORS.rapid, dashSize: 2.5, gapSize: 2, transparent: true, opacity: 0.75 }));
        // per-segment dash distances (each segment starts a new dash pattern)
        const ld = new Float32Array(rapid.n * 2);
        for (let k = 0; k < rapid.n; k++) {
          const o6 = k * 6, p = rapid.pos;
          ld[k * 2 + 1] = Math.hypot(p[o6 + 3] - p[o6], p[o6 + 4] - p[o6 + 1], p[o6 + 5] - p[o6 + 2]);
        }
        obj.geometry.setAttribute('lineDistance', new THREE.BufferAttribute(ld, 1));
      }
    }
    if (warnBuf.n) {
      const obj = this._add(warnBuf, -1, 'warn', new THREE.LineBasicMaterial({ color: COLORS.warn, depthTest: false, transparent: true }));
      obj.renderOrder = 5;
    }

    // Tool-change markers: where the M6 happens, else where the new tool first moves
    this.markers = new THREE.Group();
    for (const tc of program.toolChanges) {
      let p = tc.pos;
      const after = upperBound(program.seg.line, tc.line);
      if (!p && after < count) p = Array.from(pos.subarray(after * 6, after * 6 + 3));
      if (!p) continue;
      const t = job.tools.get(tc.tool);
      const short = t?.name && t.name.length > 34 ? `${t.name.slice(0, 33)}…` : t?.name;
      const name = `${t?.id ?? `T${tc.tool}`}${short ? ` ${short}` : ''}`;
      const m = new THREE.Mesh(new THREE.OctahedronGeometry(3), new THREE.MeshBasicMaterial({ color: COLORS.toolChange }));
      m.position.set(...p);
      const l = makeLabel(`M6 → ${name}\nline ${tc.line}`, { color: COLORS.toolChange, border: 'rgba(255,210,127,0.5)' });
      l.position.set(...p);
      m.userData.line = tc.line;
      l.userData.line = tc.line;
      this.markers.add(m, l);
    }
    this.group.add(this.markers);

    // Current tool (at the scrub position). Basis for the V2 live tool.
    this.toolMarker = new THREE.Group();
    const coneMat = new THREE.MeshBasicMaterial({ color: 0xe8edf3, transparent: true, opacity: 0.85 });
    this.toolCone = new THREE.Mesh(new THREE.CylinderGeometry(1, 1, 1, 24, 1, true), coneMat);
    this.toolCone.rotation.x = Math.PI / 2;
    this.toolMarker.add(this.toolCone);
    this.toolMarker.visible = false;
    this.group.add(this.toolMarker);

    this.highlight = new THREE.LineSegments(new THREE.BufferGeometry(), new THREE.LineBasicMaterial({ color: COLORS.highlight, depthTest: false, transparent: true }));
    this.highlight.renderOrder = 6;
    this.group.add(this.highlight);

    this.setClip(this.clipPlanes);
    if (this.dim) this.setDim(true);
  }

  _add(buf, op, cls, material) {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(buf.pos, 3));
    g.computeBoundingSphere();
    const obj = new THREE.LineSegments(g, material);
    obj.userData = { op, cls };
    obj.frustumCulled = false;
    this.group.add(obj);
    const rec = { obj, segIdx: buf.idx, op, cls };
    this.objects.push(rec);
    return obj;
  }

  _applyVisibility() {
    for (const r of this.objects) {
      const opOk = r.op < 0 ? true : this.opVisible[r.op];
      r.obj.visible = opOk && this.classVisible[r.cls];
    }
  }

  setOpVisible(op, v) { this.opVisible[op] = v; this._applyVisibility(); }
  setClassVisible(cls, v) { this.classVisible[cls] = v; this._applyVisibility(); }
  setMarkersVisible(v) { if (this.markers) this.markers.visible = v; }

  /** Show segments with global index <= maxSeg. */
  setScrub(maxSeg) {
    for (const r of this.objects) r.obj.geometry.setDrawRange(0, upperBound(r.segIdx, maxSeg) * 2);
    this.scrubSeg = maxSeg;
    if (!this.program) return;
    const seg = this.program.seg;
    if (maxSeg < 0 || maxSeg >= seg.count) { this.toolMarker.visible = false; return; }
    const o = maxSeg * 6 + 3;
    const p = [seg.pos[o], seg.pos[o + 1], seg.pos[o + 2]];
    const op = this.job.opOfSeg[maxSeg];
    const tool = op >= 0 ? this.job.tools.get(this.job.ops[op].tool) : null;
    const d = tool?.diameter ?? 3;
    const len = Math.max(8, tool?.flute_length ?? 12);
    this.toolCone.scale.set(d / 2, len, d / 2);
    this.toolCone.position.set(0, 0, len / 2);
    this.toolMarker.position.set(...p);
    this.toolMarker.visible = this.scrubToolEnabled && maxSeg < seg.count - 1;
  }

  /** Dim the program path (live mode), so the live trail stands out. */
  setDim(on) {
    this.dim = on;
    for (const r of this.objects) {
      if (r.cls === 'warn') continue;
      const m = r.obj.material;
      if (m.userData.baseOpacity === undefined) m.userData.baseOpacity = m.opacity;
      m.transparent = true;
      m.opacity = on ? m.userData.baseOpacity * 0.3 : m.userData.baseOpacity;
      m.needsUpdate = true;
    }
  }

  /** World-space clipping planes for all toolpath materials. */
  setClip(planes) {
    this.clipPlanes = planes;
    for (const r of this.objects) { r.obj.material.clippingPlanes = planes; r.obj.material.needsUpdate = true; }
  }

  /** Highlight all segments of a source line. */
  highlightLine(line) {
    const g = this.highlight.geometry;
    if (!this.program || !line) { g.setDrawRange(0, 0); return; }
    const { seg } = this.program;
    const a = upperBound(seg.line, line - 1), b = upperBound(seg.line, line);
    const pos = seg.pos.slice(a * 6, b * 6);
    g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    g.setDrawRange(0, (b - a) * 2);
    g.computeBoundingSphere();
  }

  /** Raycast visible path objects. Returns the global segment index or -1. */
  pick(raycaster) {
    const vis = this.objects.filter((r) => r.obj.visible && r.cls !== 'warn');
    const hits = raycaster.intersectObjects(vis.map((r) => r.obj), false);
    // prefer cuts and plunges over rapids when both are under the cursor
    hits.sort((a, b) => (a.object.userData.cls === 'rapid') - (b.object.userData.cls === 'rapid') || a.distance - b.distance);
    for (const h of hits) {
      const rec = vis.find((r) => r.obj === h.object);
      const local = Math.floor(h.index / 2);
      const segI = rec.segIdx[local];
      if (segI === undefined) continue;
      if (this.clipPlanes.length && this.clipPlanes.some((pl) => pl.distanceToPoint(h.point) < 0)) continue;
      return segI;
    }
    return -1;
  }

  dispose() {
    disposeTree(this.group);
    this.group.clear();
    this.objects = [];
  }
}
