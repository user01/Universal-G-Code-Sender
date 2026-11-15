// Machine-frame objects (machine coordinates, mm): envelope, homing corner, spoilboard, BitSetter,
// tool-change park. Built from a normalized profile (see src/data/profile.js).
import * as THREE from 'three';
import { makeLabel, disposeTree } from './labels.js';

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
    const { min, max } = p.envelope;
    const size = [max[0] - min[0], max[1] - min[1], max[2] - min[2]];
    const center = [(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2];

    // Envelope (reachable tool-tip box)
    const box = new THREE.LineSegments(
      new THREE.EdgesGeometry(new THREE.BoxGeometry(...size)),
      new THREE.LineBasicMaterial({ color: p.envelope.verified ? 0x6b7a90 : 0x8a7350, transparent: true, opacity: 0.8 }),
    );
    box.position.set(...center);
    box.name = 'envelope';
    this.group.add(box);
    const envLabel = makeLabel(`Machine envelope ${size[0]} × ${size[1]} × ${size[2]}${p.envelope.verified ? '' : ' (profile: unverified)'}`, { color: '#c9b48a' });
    envLabel.position.set(min[0], max[1], max[2]);
    this.group.add(envLabel);

    // Homing corner
    const home = new THREE.Mesh(new THREE.SphereGeometry(4, 16, 12), new THREE.MeshBasicMaterial({ color: 0x9fb4cc }));
    home.position.set(0, 0, 0);
    this.group.add(home);
    const homeLabel = makeLabel(`Home ${p.homingCorner ?? '(0,0,0)'}`, { color: '#9fb4cc' });
    homeLabel.position.set(0, 0, 0);
    this.group.add(homeLabel);

    // Spoilboard plane (Z set later from the work offset and stock)
    const sb = new THREE.Mesh(
      new THREE.PlaneGeometry(size[0], size[1]),
      new THREE.MeshBasicMaterial({ color: 0x3a3226, transparent: true, opacity: 0.55, side: THREE.DoubleSide, depthWrite: false }),
    );
    sb.position.set(center[0], center[1], min[2]);
    sb.name = 'spoilboard';
    sb.renderOrder = -1;
    const grid = new THREE.GridHelper(Math.max(size[0], size[1]), Math.round(Math.max(size[0], size[1]) / 50), 0x5a4c39, 0x4a3f30);
    grid.rotation.x = Math.PI / 2;
    grid.position.set(0, 0, 0.05);
    grid.scale.set(size[0] / Math.max(size[0], size[1]), size[1] / Math.max(size[0], size[1]), 1);
    sb.add(grid);
    this.spoilboard = sb;
    this.group.add(sb);
    this.spoilLabel = makeLabel('Spoilboard', { color: '#b59b74' });
    this.group.add(this.spoilLabel);

    // BitSetter
    if (p.bitsetter?.xy) {
      const [bx, by] = p.bitsetter.xy;
      const bs = new THREE.Mesh(new THREE.CylinderGeometry(9, 9, 30, 24), new THREE.MeshBasicMaterial({ color: 0x5fa8d3, transparent: true, opacity: 0.8 }));
      bs.rotation.x = Math.PI / 2;
      bs.userData.xy = [bx, by];
      bs.name = 'bitsetter';
      this.bitsetter = bs;
      this.group.add(bs);
      this.bitsetterLabel = makeLabel(`BitSetter X${bx} Y${by}${p.bitsetter.verified ? '' : ' (unverified)'}`, { color: '#8fcbef' });
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
    this.profile = p;
    return this.group;
  }

  /** Put the spoilboard (and BitSetter base) at machine Z. */
  setSpoilboardZ(z) {
    if (!this.spoilboard) return;
    this.spoilboard.position.z = z;
    const { min } = this.profile.envelope;
    this.spoilLabel.position.set(min[0], min[1], z);
    if (this.bitsetter) {
      const [bx, by] = this.bitsetter.userData.xy;
      this.bitsetter.position.set(bx, by, z + 15);
      this.bitsetterLabel.position.set(bx, by, z + 32);
    }
  }
}
