// Scene, camera, controls, views, picking, snapshots. Z is up; world units are machine mm.
// The work group sits at the work offset (WCO), so everything job-related is in work coordinates.
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { MachineLayer } from './machine.js';
import { ToolpathLayer } from './toolpath.js';
import { StockSimLayer } from './stocksim.js';
import { makeLabel, disposeTree, updateLabelScales } from './labels.js';

export class Viewer {
  /** @param {HTMLElement} container */
  constructor(container) {
    this.container = container;
    const renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: false });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.localClippingEnabled = true;
    renderer.setClearColor(0x14181e);
    container.appendChild(renderer.domElement);
    this.renderer = renderer;

    this.scene = new THREE.Scene();
    this.perspCamera = new THREE.PerspectiveCamera(40, 1, 0.5, 20000);
    this.perspCamera.up.set(0, 0, 1);
    this.orthoCamera = new THREE.OrthographicCamera(-1, 1, 1, -1, -20000, 20000);
    this.orthoCamera.up.set(0, 0, 1);
    this.camera = this.perspCamera;
    this.controls = new OrbitControls(this.camera, renderer.domElement);
    this.controls.enableDamping = false;
    this.controls.screenSpacePanning = true;
    this.controls.addEventListener('change', () => this.requestRender());

    this.machine = new MachineLayer();
    this.scene.add(this.machine.group);
    this.work = new THREE.Group();
    this.work.name = 'work';
    this.scene.add(this.work);
    this.toolpath = new ToolpathLayer();
    this.work.add(this.toolpath.group);
    this.stockGroup = new THREE.Group();
    this.stockGroup.name = 'stock';
    this.work.add(this.stockGroup);
    this.stockSim = new StockSimLayer();
    this.work.add(this.stockSim.group);
    this.zeroGroup = new THREE.Group();
    this.zeroGroup.name = 'work zero';
    this.work.add(this.zeroGroup);

    this.clipPlane = new THREE.Plane(new THREE.Vector3(0, 0, -1), 0);
    this.clipHelper = new THREE.Mesh(
      new THREE.PlaneGeometry(1, 1),
      new THREE.MeshBasicMaterial({ color: 0x59c3e8, transparent: true, opacity: 0.08, side: THREE.DoubleSide, depthWrite: false }),
    );
    this.clipHelper.visible = false;
    this.scene.add(this.clipHelper);

    this.raycaster = new THREE.Raycaster();
    this._needsRender = true;
    new ResizeObserver(() => this.resize()).observe(container);
    this.resize();
    const loop = () => {
      if (this._needsRender) { this._needsRender = false; this.render(); }
      requestAnimationFrame(loop);
    };
    requestAnimationFrame(loop);
  }

  requestRender() { this._needsRender = true; }

  render() {
    updateLabelScales(this.scene, this.camera, this.height || 1);
    this.renderer.render(this.scene, this.camera);
  }

  resize() {
    const w = this.container.clientWidth || 1, h = this.container.clientHeight || 1;
    this.width = w; this.height = h;
    this.renderer.setSize(w, h, false);
    this.renderer.domElement.style.width = '100%';
    this.renderer.domElement.style.height = '100%';
    this.perspCamera.aspect = w / h;
    this.perspCamera.updateProjectionMatrix();
    this._orthoFrustum();
    this.requestRender();
  }

  _orthoFrustum() {
    const c = this.orthoCamera;
    const halfH = this._orthoHalfH ?? 100;
    const aspect = (this.width || 1) / (this.height || 1);
    c.top = halfH; c.bottom = -halfH; c.left = -halfH * aspect; c.right = halfH * aspect;
    c.updateProjectionMatrix();
  }

  _useCamera(cam) {
    if (this.camera === cam) return;
    this.camera = cam;
    this.controls.object = cam;
  }

  setProfile(profile) {
    this.profile = profile;
    this.machine.build(profile);
    this.requestRender();
  }

  /** Work offset: machine = work + wco. */
  setWorkOffset(wco, spoilboardZ) {
    this.wco = wco;
    this.work.position.set(...wco);
    this.work.updateMatrixWorld(true);
    this.machine.setSpoilboardZ(spoilboardZ);
    this._updateClip();
    this.requestRender();
  }

  setJob(program, job, warnings) {
    this.toolpath.build(program, job, warnings);
    this._buildStock(job);
    this._buildZero(job);
    this._updateClip();
    this.requestRender();
  }

  _buildStock(job) {
    disposeTree(this.stockGroup);
    this.stockGroup.clear();
    const s = job.stock;
    if (!s) return;
    const size = [s.max[0] - s.min[0], s.max[1] - s.min[1], s.max[2] - s.min[2]];
    const c = [(s.max[0] + s.min[0]) / 2, (s.max[1] + s.min[1]) / 2, (s.max[2] + s.min[2]) / 2];
    const geo = new THREE.BoxGeometry(...size);
    this.stockMat = new THREE.MeshBasicMaterial({ color: 0xd6a86b, transparent: true, opacity: 0.12, depthWrite: false });
    const mesh = new THREE.Mesh(geo, this.stockMat);
    mesh.position.set(...c);
    mesh.visible = !this.stockSim.mesh; // the simulated stock replaces the translucent box
    this.stockFill = mesh;
    const edges = new THREE.LineSegments(new THREE.EdgesGeometry(geo), new THREE.LineBasicMaterial({ color: 0xd6a86b, transparent: true, opacity: s.approx || s.originAssumed ? 0.55 : 0.9 }));
    edges.position.set(...c);
    this.stockGroup.add(mesh, edges);
    const fmt = (v) => (Math.round(v * 100) / 100).toString();
    const notes = [s.source, s.approx ? 'approx.' : null, s.originAssumed ? `origin assumed ${s.origin}` : null].filter(Boolean).join(', ');
    const label = makeLabel(`Stock ${size.map(fmt).join(' × ')} mm${s.material ? ` ${s.material}` : ''} (${notes})`, { color: '#e6c597' });
    label.position.set(s.max[0], s.min[1], s.min[2]);
    this.stockGroup.add(label);

    for (const f of job.fixtures ?? []) {
      const [a, b] = f.box;
      const fs = [Math.abs(b[0] - a[0]) || 0.2, Math.abs(b[1] - a[1]) || 0.2, Math.abs(b[2] - a[2]) || 0.2];
      const fg = new THREE.BoxGeometry(...fs);
      const fe = new THREE.LineSegments(new THREE.EdgesGeometry(fg), new THREE.LineBasicMaterial({ color: f.kind === 'keepout' ? 0xff6b6b : 0x9aa3ad, transparent: true, opacity: 0.6 }));
      fe.position.set((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2);
      this.stockGroup.add(fe);
    }
    for (const t of job.tabs ?? []) {
      if (!t.at) continue;
      const m = new THREE.Mesh(new THREE.BoxGeometry(3, 3, 1), new THREE.MeshBasicMaterial({ color: 0xe6c597 }));
      m.position.set(t.at[0], t.at[1], t.top_z ?? s.min[2]);
      this.stockGroup.add(m);
    }
  }

  _buildZero(job) {
    disposeTree(this.zeroGroup);
    this.zeroGroup.clear();
    const axes = new THREE.AxesHelper(25);
    axes.material.depthTest = false;
    axes.renderOrder = 4;
    this.zeroGroup.add(axes);
    this.zeroLabel = makeLabel('G54 zero', { color: '#ffffff' });
    this.zeroGroup.add(this.zeroLabel);
    if (job.clearance) {
      const z = job.clearance.z;
      const s = job.stock;
      const [x0, y0, x1, y1] = s ? [s.min[0], s.min[1], s.max[0], s.max[1]] : [0, 0, 50, 50];
      const g = new THREE.BufferGeometry().setFromPoints([
        new THREE.Vector3(x0, y0, z), new THREE.Vector3(x1, y0, z), new THREE.Vector3(x1, y0, z), new THREE.Vector3(x1, y1, z),
        new THREE.Vector3(x1, y1, z), new THREE.Vector3(x0, y1, z), new THREE.Vector3(x0, y1, z), new THREE.Vector3(x0, y0, z),
      ]);
      const l = new THREE.LineSegments(g, new THREE.LineDashedMaterial({ color: 0x59c3e8, dashSize: 4, gapSize: 4, transparent: true, opacity: 0.5 }));
      l.computeLineDistances();
      l.name = 'clearance';
      this.zeroGroup.add(l);
    }
  }

  setZeroLabel(text) {
    if (!this.zeroGroup) return;
    if (this.zeroLabel) { this.zeroGroup.remove(this.zeroLabel); disposeTree(this.zeroLabel); }
    this.zeroLabel = makeLabel(text, { color: '#ffffff', border: 'rgba(255,255,255,0.35)' });
    this.zeroLabel.center.set(0, 1); // hangs below the zero point, clear of the M6 marker
    this.zeroGroup.add(this.zeroLabel);
    this.requestRender();
  }

  /** Simulated stock (null removes it and brings back the translucent box). */
  setSim(sim) {
    if (sim) this.stockSim.build(sim, this.sectionZ !== null && this.sectionZ !== undefined ? [this.clipPlane] : []);
    else this.stockSim.dispose();
    if (this.stockFill) this.stockFill.visible = !sim;
    this.requestRender();
  }

  applySimRect(rect) {
    this.stockSim.applyRect(rect);
    this.requestRender();
  }

  /** Section plane: keep everything at or below work Z `z`; null disables it. */
  setSection(z) {
    this.sectionZ = z;
    this._updateClip();
    this.requestRender();
  }

  _updateClip() {
    const on = this.sectionZ !== null && this.sectionZ !== undefined;
    const wz = this.wco ? this.wco[2] : 0;
    if (on) this.clipPlane.constant = this.sectionZ + wz;
    const planes = on ? [this.clipPlane] : [];
    this.toolpath.setClip(planes);
    if (this.stockMat) { this.stockMat.clippingPlanes = planes; this.stockMat.needsUpdate = true; }
    this.stockSim.setClip(planes);
    this.clipHelper.visible = on;
    if (on && this.profile) {
      const { min, max } = this.profile.envelope;
      this.clipHelper.scale.set(max[0] - min[0], max[1] - min[1], 1);
      this.clipHelper.position.set((min[0] + max[0]) / 2, (min[1] + max[1]) / 2, this.sectionZ + wz);
    }
  }

  /** Box in world coords to frame: the job (work bounds + stock) or the envelope. */
  _frameBox(kind) {
    const box = new THREE.Box3();
    if (kind !== 'machine' && this.jobBounds) {
      box.set(new THREE.Vector3(...this.jobBounds.min), new THREE.Vector3(...this.jobBounds.max));
      box.translate(this.work.position);
    } else if (this.profile) {
      box.set(new THREE.Vector3(...this.profile.envelope.min), new THREE.Vector3(...this.profile.envelope.max));
      // the whole bed: spoilboard, frame and BitSetter
      const bed = this.profile.bed;
      const z = this.machine.spoilZ ?? this.profile.envelope.min[2];
      const add = (x, y) => box.expandByPoint(new THREE.Vector3(x, y, z));
      if (bed?.spoilboard) { add(...bed.spoilboard.min); add(...bed.spoilboard.max); }
      if (bed?.yRails) for (const x of bed.yRails.x) for (const y of bed.yRails.y) add(x, y);
      if (this.profile.bitsetter?.xy) add(this.profile.bitsetter.xy[0], this.profile.bitsetter.xy[1] - 30);
    } else box.set(new THREE.Vector3(-100, -100, -50), new THREE.Vector3(100, 100, 50));
    return box;
  }

  setJobBounds(b) { this.jobBounds = b; }

  /** 'top' | 'front' | 'iso' | 'machine' */
  setView(view, target = 'job') {
    const box = this._frameBox(view === 'machine' ? 'machine' : target);
    const c = box.getCenter(new THREE.Vector3());
    const size = box.getSize(new THREE.Vector3());
    const dirs = {
      top: new THREE.Vector3(0, -0.0001, 1),
      front: new THREE.Vector3(0, -1, 0.0001),
      iso: new THREE.Vector3(1, -1.25, 1.1),
      machine: new THREE.Vector3(1, -1.25, 1.1),
    };
    const dir = (dirs[view] ?? dirs.iso).clone().normalize();
    const aspect = (this.width || 1) / (this.height || 1);
    if (view === 'top' || view === 'front') {
      // orthographic: true-scale plan and elevation
      const [w, h] = view === 'top' ? [size.x, size.y] : [size.x, size.z];
      this._orthoHalfH = Math.max(w / aspect, h, 10) / 2 * 1.12;
      this._useCamera(this.orthoCamera);
      this.orthoCamera.zoom = 1;
      this._orthoFrustum();
      this.orthoCamera.position.copy(c).addScaledVector(dir, 5000);
    } else {
      this._useCamera(this.perspCamera);
      const fov = (this.perspCamera.fov * Math.PI) / 180;
      const extent = size.length() * 0.8;
      const dist = Math.max((extent / 2) / Math.tan(fov / 2) * 1.15, 20);
      this.perspCamera.position.copy(c).addScaledVector(dir, dist);
      this.perspCamera.near = Math.max(0.1, dist / 1000);
      this.perspCamera.far = dist * 20 + 5000;
      this.perspCamera.updateProjectionMatrix();
    }
    this.view = view;
    this.controls.target.copy(c);
    this.camera.lookAt(c);
    this.controls.update();
    this.requestRender();
  }

  /** Segment index under a client point, or -1. */
  pickSegment(clientX, clientY) {
    const r = this.renderer.domElement.getBoundingClientRect();
    const ndc = new THREE.Vector2(((clientX - r.left) / r.width) * 2 - 1, -((clientY - r.top) / r.height) * 2 + 1);
    this.raycaster.setFromCamera(ndc, this.camera);
    // ~5 px threshold in world units at the target distance
    const cam = this.camera;
    const worldPerPx = cam.isOrthographicCamera
      ? (cam.top - cam.bottom) / cam.zoom / r.height
      : (2 * cam.position.distanceTo(this.controls.target) * Math.tan((cam.fov * Math.PI) / 360)) / r.height;
    this.raycaster.params.Line.threshold = worldPerPx * 5;
    return this.toolpath.pick(this.raycaster);
  }

  /** PNG data URL of the current view (renders synchronously first). */
  snapshot() {
    this.render();
    return this.renderer.domElement.toDataURL('image/png');
  }

  /** glTF binary of the visible scene. */
  async exportGLB() {
    const { GLTFExporter } = await import('three/addons/exporters/GLTFExporter.js');
    const exporter = new GLTFExporter();
    return exporter.parseAsync(this.scene, { binary: true, onlyVisible: true });
  }
}
