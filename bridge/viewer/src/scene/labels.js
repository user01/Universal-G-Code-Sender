// Text labels as screen-sized sprites (constant pixel size).
import * as THREE from 'three';

/**
 * @param {string} text
 * @param {object} [o]
 * @param {string} [o.color='#e8edf3']
 * @param {string} [o.background='rgba(16,20,26,0.78)']
 * @param {number} [o.px=13]  font size in CSS px at the default label scale
 * @param {number} [o.scale=0.032]  sprite height relative to the viewport (sizeAttenuation off)
 */
export function makeLabel(text, { color = '#e8edf3', background = 'rgba(16,20,26,0.78)', px = 11, scale = 0.03, border } = {}) {
  const dpr = 2;
  const font = `600 ${px * dpr}px ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif`;
  const c = document.createElement('canvas');
  const ctx = c.getContext('2d');
  ctx.font = font;
  const lines = String(text).split('\n');
  const w = Math.ceil(Math.max(...lines.map((l) => ctx.measureText(l).width))) + 12 * dpr;
  const lh = px * dpr * 1.3;
  const h = Math.ceil(lh * lines.length + 6 * dpr);
  c.width = w; c.height = h;
  ctx.font = font;
  ctx.fillStyle = background;
  roundRect(ctx, 0, 0, w, h, 5 * dpr);
  ctx.fill();
  if (border) { ctx.strokeStyle = border; ctx.lineWidth = 2 * dpr; roundRect(ctx, 1, 1, w - 2, h - 2, 5 * dpr); ctx.stroke(); }
  ctx.fillStyle = color;
  ctx.textBaseline = 'middle';
  lines.forEach((l, i) => ctx.fillText(l, 6 * dpr, 3 * dpr + lh * (i + 0.5)));
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.minFilter = THREE.LinearFilter;
  const mat = new THREE.SpriteMaterial({ map: tex, sizeAttenuation: false, depthTest: false, transparent: true });
  const s = new THREE.Sprite(mat);
  s.center.set(0, 0); // anchor bottom-left at the point
  s.renderOrder = 10;
  // Screen size in CSS px; the viewer sets the actual scale per frame (updateLabelScales).
  s.userData.isLabel = true;
  s.userData.px = h / dpr;
  s.userData.aspect = w / h;
  s.scale.set(scale * s.userData.aspect, scale, 1);
  return s;
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

/**
 * Keep labels at a constant pixel size. Sprites with sizeAttenuation=false are sized in
 * view-height units for a perspective camera and in world units for an orthographic one.
 */
export function updateLabelScales(root, camera, viewportHeightPx) {
  const perPx = camera.isOrthographicCamera
    ? (camera.top - camera.bottom) / camera.zoom / viewportHeightPx
    : (2 * Math.tan((camera.fov * Math.PI) / 360)) / viewportHeightPx;
  root.traverse((o) => {
    if (!o.userData.isLabel || !o.visible) return;
    const k = o.userData.px * perPx;
    o.scale.set(k * o.userData.aspect, k, 1);
  });
}

/** Dispose a subtree's geometries, materials and textures. */
export function disposeTree(obj) {
  obj.traverse((o) => {
    o.geometry?.dispose?.();
    const mats = Array.isArray(o.material) ? o.material : o.material ? [o.material] : [];
    for (const m of mats) { m.map?.dispose?.(); m.dispose?.(); }
  });
}
