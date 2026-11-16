// Live layers (SPEC §6.1 "Live state"): the tool at its reported position, the trail of the actual
// path, the planned position at the current streamed line, probe hits with their values.
//
// The live tool, trail and probe hits are in MACHINE coordinates (scene root), from status MPos, so a
// WCO change doesn't move them. The planned marker is in WORK coordinates (under viewer.work), since
// it comes from the program.
import * as THREE from 'three';
import { makeLabel, disposeTree } from './labels.js';

const LIVE_COLOR = 0xff3dd2; // magenta: distinct from the operation colors, plunges and warnings
const PLAN_COLOR = 0x59c3e8;
const PROBE_OK = 0x8bd346;
const PROBE_FAIL = 0xff3b30;

const f3 = (v) => (Math.round(v * 1000) / 1000).toFixed(3);

export class LiveLayer {
  /** @param {import('./viewer.js').Viewer} viewer */
  constructor(viewer) {
    this.viewer = viewer;
    this.group = new THREE.Group();
    this.group.name = 'live (machine)';
    viewer.scene.add(this.group);
    this.workGroup = new THREE.Group();
    this.workGroup.name = 'live (work)';
    viewer.work.add(this.workGroup);

    // live tool: a cylinder standing on the tip, plus a ring on the tip so it reads from above
    this.tool = new THREE.Group();
    this.tool.name = 'live tool';
    this.toolBody = new THREE.Mesh(new THREE.CylinderGeometry(1, 1, 1, 24, 1), new THREE.MeshBasicMaterial({ color: LIVE_COLOR, transparent: true, opacity: 0.55, depthWrite: false }));
    this.toolBody.rotation.x = Math.PI / 2;
    this.tool.add(this.toolBody);
    const ring = new THREE.Mesh(new THREE.RingGeometry(0.9, 1.25, 32), new THREE.MeshBasicMaterial({ color: LIVE_COLOR, side: THREE.DoubleSide, depthTest: false, transparent: true }));
    ring.renderOrder = 7;
    this.toolRing = ring;
    this.tool.add(ring);
    const tip = new THREE.Mesh(new THREE.SphereGeometry(0.6, 12, 8), new THREE.MeshBasicMaterial({ color: 0xffffff, depthTest: false, transparent: true }));
    tip.renderOrder = 8;
    this.tool.add(tip);
    this.tool.visible = false;
    this.group.add(this.tool);
    this.toolLabel = null;
    this.setToolDiameter(3.175);

    // trail: one Line over a preallocated buffer shared with the live state (no copies)
    this.trailGeom = new THREE.BufferGeometry();
    this.trail = new THREE.Line(this.trailGeom, new THREE.LineBasicMaterial({ color: LIVE_COLOR, transparent: true, opacity: 0.95, depthTest: false }));
    this.trail.renderOrder = 6;
    this.trail.frustumCulled = false;
    this.trail.name = 'trail';
    this.group.add(this.trail);
    this._trailArray = null;
    this._trailVersion = -1;

    // planned position (work coords)
    this.plan = new THREE.Group();
    this.plan.name = 'planned position';
    const pm = new THREE.Mesh(new THREE.OctahedronGeometry(1.6), new THREE.MeshBasicMaterial({ color: PLAN_COLOR, wireframe: true, depthTest: false, transparent: true }));
    pm.renderOrder = 7;
    this.plan.add(pm);
    this.plan.visible = false;
    this.workGroup.add(this.plan);
    this.planLabel = null;

    this.probes = new THREE.Group();
    this.probes.name = 'probe hits';
    this.group.add(this.probes);
    this._probeCount = 0;
  }

  setToolDiameter(d) {
    const r = Math.max(0.5, d / 2);
    this.toolBody.scale.set(r, 20, r);
    this.toolBody.position.set(0, 0, 10);
    this.toolRing.scale.set(r, r, 1);
  }

  /** Live tool at machine position `mpos` (null hides it). */
  setTool(mpos, text) {
    if (!mpos) { this.tool.visible = false; return; }
    this.tool.position.set(...mpos);
    this.tool.visible = true;
    if (text !== this._toolText) {
      if (this.toolLabel) { this.tool.remove(this.toolLabel); disposeTree(this.toolLabel); }
      this.toolLabel = makeLabel(text, { color: '#ffb3ef', border: 'rgba(255,61,210,0.6)' });
      this.toolLabel.position.set(0, 0, 21);
      this.tool.add(this.toolLabel);
      this._toolText = text;
    }
  }

  /** @param {{pts: Float32Array, n: number, version: number}} trail */
  setTrail(trail) {
    if (trail.version === this._trailVersion) return;
    if (this._trailArray !== trail.pts) {
      this.trailGeom.setAttribute('position', new THREE.BufferAttribute(trail.pts, 3));
      this._trailArray = trail.pts;
    }
    const attr = this.trailGeom.getAttribute('position');
    attr.needsUpdate = true;
    this.trailGeom.setDrawRange(0, trail.n);
    this._trailVersion = trail.version;
  }

  setTrailVisible(v) { this.trail.visible = v; }

  /** Planned position (work coords) with a label, or null. */
  setPlanned(pos, text) {
    if (!pos) { this.plan.visible = false; return; }
    this.plan.position.set(...pos);
    this.plan.visible = true;
    if (text !== this._planText) {
      if (this.planLabel) { this.plan.remove(this.planLabel); disposeTree(this.planLabel); }
      this.planLabel = makeLabel(text, { color: '#a9e3f5', border: 'rgba(89,195,232,0.5)' });
      this.planLabel.center.set(1, 1); // hangs left-below, clear of the live tool label
      this.plan.add(this.planLabel);
      this._planText = text;
    }
  }

  /**
   * Probe hits (machine coords). Each shows its machine position and its G54 Z with the work offset
   * in force when it was taken (what GRBL's WPos said then; a later Z0 or tool change shifts G54).
   * @param {{mpos:number[], ok:boolean, wco:number[]|null}[]} probes
   */
  setProbes(probes) {
    if (probes.length === this._probeCount) return;
    this._probeCount = probes.length;
    disposeTree(this.probes);
    this.probes.clear();
    probes.forEach((p, i) => {
      const color = p.ok ? PROBE_OK : PROBE_FAIL;
      const m = new THREE.Mesh(new THREE.SphereGeometry(1.4, 16, 12), new THREE.MeshBasicMaterial({ color, depthTest: false, transparent: true }));
      m.renderOrder = 8;
      m.position.set(...p.mpos);
      this.probes.add(m);
      const cross = new THREE.LineSegments(
        new THREE.BufferGeometry().setFromPoints([
          new THREE.Vector3(-5, 0, 0), new THREE.Vector3(5, 0, 0), new THREE.Vector3(0, -5, 0), new THREE.Vector3(0, 5, 0),
        ]),
        new THREE.LineBasicMaterial({ color, depthTest: false, transparent: true }),
      );
      cross.position.set(...p.mpos);
      cross.renderOrder = 8;
      this.probes.add(cross);
      const work = p.wco ? ` · G54 Z${f3(p.mpos[2] - p.wco[2])} then` : '';
      const l = makeLabel(`Probe ${i + 1}${p.ok ? '' : ' (no contact)'}\nMZ${f3(p.mpos[2])}${work}\nMX${f3(p.mpos[0])} MY${f3(p.mpos[1])}`, { color: p.ok ? '#c8f29a' : '#ffb4ae', border: p.ok ? 'rgba(139,211,70,0.5)' : 'rgba(255,59,48,0.6)' });
      l.position.set(...p.mpos);
      l.center.set(1, 0); // to the left of the hit, so it doesn't sit on the tool label
      this.probes.add(l);
    });
  }

  dispose() {
    disposeTree(this.group);
    this.viewer.scene.remove(this.group);
    disposeTree(this.workGroup);
    this.viewer.work.remove(this.workGroup);
  }
}
