// Machine-frame objects (machine coordinates, mm): envelope, homing corner, the bed (spoilboard,
// Y rails, front/back extrusions from the profile `bed` section), BitSetter, tool-change park and a
// FRONT marker. Built from a normalized profile (see src/data/profile.js). Not photoreal: thin
// outlines and low boxes, so the job stays the focus. Heights hang off the spoilboard Z, which is
// set later (measured profile value, else under the stock).
import * as THREE from 'three';
import { makeLabel, disposeTree } from './labels.js';

const outlineBox = (size, color, opacity = 0.7, fill = 0) => {
  const g = new THREE.Group();
  const geo = new THREE.BoxGeometry(...size);
  g.add(new THREE.LineSegments(new THREE.EdgesGeometry(geo), new THREE.LineBasicMaterial({ color, transparent: true, opacity })));
  if (fill) g.add(new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ color, transparent: true, opacity: fill, depthWrite: false })));
  return g;
};

export class MachineLayer {
  constructor() {
    this.group = new THREE.Group();
    this.group.name = 'machine';
    this.spoilboard = null;
  }

  /** @param {import('../data/profile.js').MachineProfile} p */
  build(p) {
    disposeTree(this.group);
    this.group.clear();
    this.profile = p;
    const { min, max } = p.envelope;
    const size = [max[0] - min[0], max[1] - min[1], max[2] - min[2]];
    const center = [(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2];
    const bed = p.bed;
    const assumed = bed && !bed.verified ? ' (assumed)' : '';

    // Envelope (reachable tool-tip box)
    const box = new THREE.LineSegments(
      new THREE.EdgesGeometry(new THREE.BoxGeometry(...size)),
      new THREE.LineBasicMaterial({ color: p.envelope.verified ? 0x6b7a90 : 0x8a7350, transparent: true, opacity: 0.45 }),
    );
    box.position.set(...center);
    box.name = 'envelope';
    this.group.add(box);
    const envLabel = makeLabel(`Travel ${size[0]} × ${size[1]} × ${size[2]}${p.envelope.verified ? '' : ' (profile: unverified)'}`, { color: '#c9b48a' });
    envLabel.position.set(min[0], max[1], max[2]);
    this.group.add(envLabel);

    // Homing corner
    const home = new THREE.Mesh(new THREE.SphereGeometry(4, 16, 12), new THREE.MeshBasicMaterial({ color: 0x9fb4cc }));
    this.group.add(home);
    const homeLabel = makeLabel(`Home ${p.homingCorner ?? '(0,0,0)'}`, { color: '#9fb4cc' });
    this.group.add(homeLabel);

    // Spoilboard: the bed section's rectangle when known, else the envelope footprint as a plane.
    const sbRect = bed?.spoilboard ?? { min: [min[0], min[1]], max: [max[0], max[1]], thickness: 0 };
    const sbW = sbRect.max[0] - sbRect.min[0], sbD = sbRect.max[1] - sbRect.min[1];
    const sb = new THREE.Group();
    sb.name = 'spoilboard';
    const sbSurf = new THREE.Mesh(
      new THREE.PlaneGeometry(sbW, sbD),
      new THREE.MeshBasicMaterial({ color: 0x3a3226, transparent: true, opacity: 0.55, side: THREE.DoubleSide, depthWrite: false }),
    );
    sbSurf.renderOrder = -1;
    sb.add(sbSurf);
    const grid = new THREE.GridHelper(Math.max(sbW, sbD), Math.round(Math.max(sbW, sbD) / 50), 0x5a4c39, 0x4a3f30);
    grid.rotation.x = Math.PI / 2;
    grid.position.z = 0.05;
    grid.scale.set(sbW / Math.max(sbW, sbD), 1, sbD / Math.max(sbW, sbD));
    sb.add(grid);
    if (sbRect.thickness) {
      const slab = outlineBox([sbW, sbD, sbRect.thickness], 0x9c8058, 0.6);
      slab.position.z = -sbRect.thickness / 2;
      sb.add(slab);
    }
    sb.userData.xy = [(sbRect.min[0] + sbRect.max[0]) / 2, (sbRect.min[1] + sbRect.max[1]) / 2];
    sb.userData.thickness = sbRect.thickness;
    this.spoilboard = sb;
    this.group.add(sb);
    this.spoilLabel = makeLabel(`Spoilboard${bed?.spoilboard ? ` ${Math.round(sbW)} × ${Math.round(sbD)}` : ''}${assumed}`, { color: '#b59b74' });
    this.spoilLabel.userData.xy = [sbRect.min[0], sbRect.min[1]];
    this.group.add(this.spoilLabel);

    // Frame: Y rails (left/right) and front/back extrusions, as low outlined boxes under/next to the bed.
    this.frame = new THREE.Group();
    this.frame.name = 'frame';
    if (bed?.yRails) {
      const r = bed.yRails;
      for (const x of r.x) {
        const b = outlineBox([r.width, r.y[1] - r.y[0], r.height], 0x6f7f95, 0.65, 0.08);
        b.position.set(x, (r.y[0] + r.y[1]) / 2, 0);
        b.userData.zFromSpoil = -(sbRect.thickness || 0) + r.height / 2; // stands on the table, beside the bed
        this.frame.add(b);
      }
    }
    if (bed?.xExtrusions) {
      const e = bed.xExtrusions;
      for (const y of e.y) {
        const b = outlineBox([e.x[1] - e.x[0], e.depth, e.height], 0x6f7f95, 0.65, 0.08);
        b.position.set((e.x[0] + e.x[1]) / 2, y, 0);
        b.userData.zFromSpoil = -(sbRect.thickness || 0) - e.height / 2; // under the spoilboard
        this.frame.add(b);
      }
    }
    if (this.frame.children.length) {
      const fl = makeLabel(`Frame${assumed}`, { color: '#9aa9bd' });
      fl.userData.xy = [bed.yRails ? bed.yRails.x[0] : min[0], bed.yRails ? bed.yRails.y[0] : min[1]];
      fl.userData.zFromSpoil = bed.yRails ? bed.yRails.height - (sbRect.thickness || 0) : 0;
      this.frameLabel = fl;
      this.frame.add(fl);
    }
    this.group.add(this.frame);

    // FRONT marker (the operator's side: machine -Y)
    const frontY = Math.min(sbRect.min[1], min[1]) - 25;
    this.front = makeLabel('FRONT', { color: '#ffffff', border: 'rgba(255,255,255,0.35)' });
    this.front.userData.xy = [(sbRect.min[0] + sbRect.max[0]) / 2, frontY];
    this.group.add(this.front);
    const arrow = new THREE.ArrowHelper(new THREE.Vector3(0, -1, 0), new THREE.Vector3(0, 0, 0), 30, 0xffffff, 10, 8);
    arrow.userData.xy = [(sbRect.min[0] + sbRect.max[0]) / 2, frontY + 35];
    this.frontArrow = arrow;
    this.group.add(arrow);

    // BitSetter: a puck at its measured XY with the keep-out ring the stock must not cover.
    if (p.bitsetter?.xy) {
      const [bx, by] = p.bitsetter.xy;
      const bs = new THREE.Group();
      const puck = new THREE.Mesh(new THREE.CylinderGeometry(9, 9, 30, 24), new THREE.MeshBasicMaterial({ color: 0x5fa8d3, transparent: true, opacity: 0.85 }));
      puck.rotation.x = Math.PI / 2;
      puck.position.z = 15;
      bs.add(puck);
      const ring = new THREE.Mesh(new THREE.RingGeometry((bed?.bitsetterRadius ?? 20) - 1, bed?.bitsetterRadius ?? 20, 40), new THREE.MeshBasicMaterial({ color: 0x5fa8d3, transparent: true, opacity: 0.5, side: THREE.DoubleSide }));
      ring.position.z = 0.2;
      bs.add(ring);
      bs.userData.xy = [bx, by];
      bs.name = 'bitsetter';
      this.bitsetter = bs;
      this.group.add(bs);
      this.bitsetterLabel = makeLabel(`BitSetter X${bx} Y${by}${p.bitsetter.verified ? '' : ' (by eye)'}`, { color: '#8fcbef' });
      this.group.add(this.bitsetterLabel);
    } else this.bitsetter = null;

    // Tool-change park
    if (p.toolChangePark) {
      const park = new THREE.Mesh(new THREE.OctahedronGeometry(5), new THREE.MeshBasicMaterial({ color: 0xc792ea, wireframe: true }));
      park.position.set(...p.toolChangePark);
      this.group.add(park);
      const l = makeLabel('Tool-change park', { color: '#d7b5f0' });
      l.position.set(...p.toolChangePark);
      this.group.add(l);
    }
    this.setSpoilboardZ(min[2] + 10);
    return this.group;
  }

  /** Put the spoilboard top (and everything standing on the bed) at machine Z. */
  setSpoilboardZ(z) {
    if (!this.spoilboard) return;
    this.spoilZ = z;
    const [sx, sy] = this.spoilboard.userData.xy;
    this.spoilboard.position.set(sx, sy, z);
    this.spoilLabel.position.set(...this.spoilLabel.userData.xy, z);
    for (const c of this.frame.children) {
      const xy = c.userData.xy ?? [c.position.x, c.position.y];
      c.position.set(xy[0], xy[1], z + (c.userData.zFromSpoil ?? 0));
    }
    this.front.position.set(...this.front.userData.xy, z);
    this.frontArrow.position.set(...this.frontArrow.userData.xy, z);
    if (this.bitsetter) {
      const [bx, by] = this.bitsetter.userData.xy;
      this.bitsetter.position.set(bx, by, z);
      this.bitsetterLabel.position.set(bx, by, z + 32);
    }
  }
}
