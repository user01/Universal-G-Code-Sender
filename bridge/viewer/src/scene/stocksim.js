// Simulated stock: a heightfield grid displaced in the vertex shader from a float texture that the
// sim worker updates in dirty rectangles. Shader adapted from the cnc-sim-viewer skill (app.js).
// Lives in the work group: local XY = stock-local (front-left corner at 0,0), Z = work Z.
import * as THREE from 'three';

const VS = `
  uniform sampler2D uH; uniform vec2 uGrid; uniform float uCs; uniform vec2 uStock; uniform float uZmin;
  varying vec3 vW; varying float vH;
  #include <clipping_planes_pars_vertex>
  void main() {
    ivec2 ij = ivec2(position.xy + 0.5);
    float h = texelFetch(uH, ij, 0).r;
    vec2 xy = (position.xy + 0.5) * uCs;
    if (ij.x == 0) xy.x = 0.0;
    if (ij.y == 0) xy.y = 0.0;
    if (ij.x == int(uGrid.x) - 1) xy.x = uStock.x;
    if (ij.y == int(uGrid.y) - 1) xy.y = uStock.y;
    float z = position.z > 0.5 ? uZmin : h;
    vec4 wp = modelMatrix * vec4(xy, z, 1.0);
    vW = wp.xyz; vH = h;
    vec4 mvPosition = viewMatrix * wp;
    gl_Position = projectionMatrix * mvPosition;
    #include <clipping_planes_vertex>
  }`;

const FS = `
  uniform float uZmin; uniform float uTop;
  varying vec3 vW; varying float vH;
  #include <clipping_planes_pars_fragment>
  void main() {
    #include <clipping_planes_fragment>
    if (vH < uZmin + 0.02) discard;                               // through-cut: see-through
    vec3 n = normalize(cross(dFdx(vW), dFdy(vW)));
    vec3 V = normalize(cameraPosition - vW);
    if (dot(n, V) < 0.0) n = -n;
    float wall = 1.0 - smoothstep(0.3, 0.85, abs(n.z));
    float depth = clamp((uTop - vH) / max(uTop - uZmin, 1.0), 0.0, 1.0);
    vec3 wood = vec3(0.86, 0.70, 0.48);
    bool raw = vH > uTop - 0.002 && wall < 0.3;
    vec3 col = raw ? wood * 1.04 : wood * (0.92 - 0.30 * depth);     // cut floors darker the deeper they are
    col = mix(col, col * vec3(0.80, 0.68, 0.52), wall * 0.75);
    vec3 L1 = normalize(vec3(-0.45, -0.55, 0.70)), L2 = normalize(vec3(0.7, 0.4, 0.35));
    float lit = 0.34 + 0.16 * abs(n.z) + 0.5 * max(dot(n, L1), 0.0) + 0.2 * max(dot(n, L2), 0.0);
    gl_FragColor = vec4(col * lit, 1.0);
  }`;

export class StockSimLayer {
  constructor() {
    this.group = new THREE.Group();
    this.group.name = 'stock simulation';
    this.mesh = null;
  }

  /** @param {{nx:number, ny:number, cs:number, top:number, bottom:number, origin:number[]}} sim */
  build(sim, clippingPlanes = []) {
    this.dispose();
    const { nx, ny, cs, top, bottom, origin } = sim;
    this.nx = nx; this.ny = ny;
    this.heights = new Float32Array(nx * ny).fill(top);
    const tex = new THREE.DataTexture(this.heights, nx, ny, THREE.RedFormat, THREE.FloatType);
    tex.minFilter = tex.magFilter = THREE.NearestFilter;
    tex.generateMipmaps = false;
    tex.needsUpdate = true;
    this.tex = tex;
    const uniforms = {
      uH: { value: tex }, uGrid: { value: new THREE.Vector2(nx, ny) }, uCs: { value: cs },
      uStock: { value: new THREE.Vector2(nx * cs, ny * cs) }, uZmin: { value: bottom }, uTop: { value: top },
    };
    this.material = new THREE.ShaderMaterial({ uniforms, vertexShader: VS, fragmentShader: FS, side: THREE.DoubleSide, clipping: true, clippingPlanes });

    // top surface: one vertex per cell; plus the four side walls (z flag 1 = bottom)
    const pos = new Float32Array(nx * ny * 3);
    for (let j = 0, o = 0; j < ny; j++) for (let i = 0; i < nx; i++, o += 3) { pos[o] = i; pos[o + 1] = j; }
    const idx = [];
    for (let j = 0; j < ny - 1; j++) for (let i = 0; i < nx - 1; i++) {
      const a = j * nx + i, b = a + 1, c = a + nx, d = c + 1;
      idx.push(a, b, c, b, d, c);
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    geo.setIndex(nx * ny > 65535 ? new THREE.Uint32BufferAttribute(idx, 1) : new THREE.Uint16BufferAttribute(idx, 1));
    const top_ = new THREE.Mesh(geo, this.material);
    top_.frustumCulled = false;

    const wall = (cells) => {
      const p = [];
      const ix = [];
      cells.forEach(([i, j], k) => { p.push(i, j, 0, i, j, 1); if (k) { const a = (k - 1) * 2; ix.push(a, a + 1, a + 2, a + 1, a + 3, a + 2); } });
      const g = new THREE.BufferGeometry();
      g.setAttribute('position', new THREE.Float32BufferAttribute(p, 3));
      g.setIndex(ix);
      const m = new THREE.Mesh(g, this.material);
      m.frustumCulled = false;
      return m;
    };
    const row = (j) => Array.from({ length: nx }, (_, i) => [i, j]);
    const col = (i) => Array.from({ length: ny }, (_, j) => [i, j]);
    this.group.add(top_, wall(row(0)), wall(row(ny - 1)), wall(col(0)), wall(col(nx - 1)));
    this.group.position.set(origin[0], origin[1], 0);
    this.mesh = top_;
  }

  /** Copy a dirty rectangle from the worker into the texture. */
  applyRect(r) {
    if (!this.heights || !r) return;
    for (let j = 0; j < r.h; j++) this.heights.set(r.data.subarray(j * r.w, (j + 1) * r.w), (r.y0 + j) * this.nx + r.x0);
    this.tex.needsUpdate = true;
  }

  setClip(planes) {
    if (this.material) { this.material.clippingPlanes = planes; this.material.needsUpdate = true; }
  }

  dispose() {
    for (const c of this.group.children) c.geometry?.dispose();
    this.group.clear();
    this.material?.dispose();
    this.tex?.dispose();
    this.material = null; this.tex = null; this.heights = null; this.mesh = null;
  }
}
