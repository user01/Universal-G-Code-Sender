import { test } from 'node:test';
import assert from 'node:assert/strict';
import { arcCenterFromR, tessellateArc, arcSweep } from '../src/gcode/arcs.js';
import { parseGcode, KIND } from '../src/gcode/parser.js';

const close = (a, b, eps = 1e-6) => assert.ok(Math.abs(a - b) <= eps, `expected ${b}, got ${a}`);
const pts = (p) => Array.from({ length: p.seg.count }, (_, i) => Array.from(p.seg.pos.slice(i * 6 + 3, i * 6 + 6)));

test('G2 with I/J: clockwise quarter circle stays on the radius and ends exactly', () => {
  const p = parseGcode('G90 G17 G0 X0 Y10 Z0\nG2 X10 Y0 I0 J-10 F500\n');
  const P = pts(p);
  assert.ok(P.length > 4);
  for (const q of P) close(Math.hypot(q[0], q[1]), 10, 0.011);
  assert.deepEqual(P[P.length - 1], [10, 0, 0]);
  // clockwise from (0,10) to (10,0) passes through the first quadrant
  for (const q of P) assert.ok(q[0] >= -1e-6 && q[1] >= -1e-6);
  assert.ok(Array.from(p.seg.kind).every((k) => k === KIND.ARC));
  assert.ok(Array.from(p.seg.line).every((l) => l === 2));
});

test('G3 with I/J: counter-clockwise goes the long way round for the same endpoints', () => {
  const p = parseGcode('G0 X0 Y10 Z0\nG3 X10 Y0 I0 J-10 F500\n');
  const P = pts(p);
  assert.ok(P.some((q) => q[0] < -9)); // passes x = -10
  close(arcSweep({ start: [0, 10, 0], end: [10, 0, 0], center: [0, 0, 0], clockwise: false }), 1.5 * Math.PI);
});

test('full circle when start equals end', () => {
  const s = tessellateArc({ start: [10, 0, 0], end: [10, 0, 0], center: [0, 0, 0], clockwise: true, tolerance: 0.01 });
  assert.ok(s.length > 50);
  assert.ok(s.some((q) => q[0] < -9.9));
});

test('chord tolerance controls the segment count', () => {
  const fine = tessellateArc({ start: [10, 0, 0], end: [-10, 0, 0], center: [0, 0, 0], clockwise: false, tolerance: 0.001 });
  const coarse = tessellateArc({ start: [10, 0, 0], end: [-10, 0, 0], center: [0, 0, 0], clockwise: false, tolerance: 0.1 });
  assert.ok(fine.length > coarse.length);
  // midpoint of each chord within tolerance of the circle
  let prev = [10, 0, 0];
  for (const q of coarse) {
    const mx = (prev[0] + q[0]) / 2, my = (prev[1] + q[1]) / 2;
    assert.ok(10 - Math.hypot(mx, my) <= 0.1 + 1e-9);
    prev = q;
  }
});

test('helix: Z interpolates along the arc', () => {
  const p = parseGcode('G0 X10 Y0 Z0\nG2 X10 Y0 Z-2 I-10 J0 F500\n');
  const P = pts(p);
  const zs = P.map((q) => q[2]);
  for (let i = 1; i < zs.length; i++) assert.ok(zs[i] <= zs[i - 1] + 1e-9);
  close(zs[zs.length - 1], -2);
  close(zs[Math.floor(zs.length / 2) - 1], -1, 0.1);
});

test('R form: positive R is the short arc, negative R the long arc', () => {
  const short = arcCenterFromR([0, 0, 0], [10, 0, 0], 10, true, 17);
  close(short[0], 5); close(short[1], -Math.sqrt(75));
  const long = arcCenterFromR([0, 0, 0], [10, 0, 0], -10, true, 17);
  close(long[0], 5); close(long[1], Math.sqrt(75));
  const semi = arcCenterFromR([0, 0, 0], [10, 0, 0], 5, true, 17);
  close(semi[0], 5); close(semi[1], 0);
  assert.equal(arcCenterFromR([0, 0, 0], [10, 0, 0], 2, true, 17), null);

  const p = parseGcode('G0 X0 Y0 Z0\nG2 X10 Y0 R5 F500\n');
  const P = pts(p);
  assert.ok(P.some((q) => q[1] > 4.9)); // G2 from (0,0) to (10,0) bulges to +Y
});

test('G18 (ZX) and G19 (YZ) planes', () => {
  const p = parseGcode('G18 G0 X0 Y0 Z0\nG2 X10 Z0 I5 K0 F500\n');
  const P = pts(p);
  for (const q of P) close(q[1], 0);
  assert.ok(P.some((q) => Math.abs(q[2]) > 4.9));
  const r = parseGcode('G19 G0 X0 Y0 Z0\nG3 Y10 Z0 J5 K0 F500\n');
  for (const q of pts(r)) close(q[0], 0);
});

test('G90.1 absolute arc centers', () => {
  const p = parseGcode('G90.1 G0 X0 Y10 Z0\nG2 X10 Y0 I0 J0 F500\n');
  for (const q of pts(p)) close(Math.hypot(q[0], q[1]), 10, 0.011);
});

test('inch arcs scale centers and radius', () => {
  const p = parseGcode('G20 G0 X0 Y1 Z0\nG2 X1 Y0 I0 J-1 F10\n');
  const P = pts(p);
  for (const q of P) close(Math.hypot(q[0], q[1]), 25.4, 0.02);
});
