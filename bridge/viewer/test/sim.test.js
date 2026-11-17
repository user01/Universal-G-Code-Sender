import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Heightfield, toolProfile } from '../src/sim/heightfield.js';
import { buildSimInput, cellSize, toolModel } from '../src/sim/simjob.js';
import { parseGcode } from '../src/gcode/parser.js';
import { parseHeader } from '../src/gcode/header.js';
import { buildJob } from '../src/gcode/job.js';

const make = (segs, r, extra = {}) => new Heightfield({
  nx: 80, ny: 80, cs: 0.25, top: 0,
  seg: Float32Array.from(segs.flat()), rad: new Float32Array(segs.length).fill(r), ...extra,
});

test('straight flat cut removes exactly the cells within the radius (analytic width)', () => {
  // 20 x 20 mm stock, a 6 mm end mill along Y = 10 at Z -1, from X 2 to X 14
  const hf = make([[2, 10, -1, 14, 10, -1]], 3);
  const final = hf.build();
  const col = 32; // x centre 8.125: well inside the cut in X
  let cut = 0;
  for (let j = 0; j < 80; j++) if (final[j * 80 + col] < 0) cut++;
  // cell centres (j + 0.5) * 0.25 within [7, 13]: j = 28..51
  assert.equal(cut, 24);
  assert.equal(final[28 * 80 + col], -1);
  assert.equal(final[27 * 80 + col], 0);
  // the round end reaches X 14 + 3 = 17; the last column (centre 19.875) is past it
  assert.equal(final[40 * 80 + 79], 0, 'past the end of the round end the stock is intact');
});

test('a cut above the stock top removes nothing', () => {
  const hf = make([[0, 0, 1, 20, 20, 1]], 3);
  const final = hf.build();
  assert.ok(final.every((v) => v === 0));
});

test('goto forward, backward and to the end matches the full build', () => {
  const segs = [];
  for (let k = 0; k < 30; k++) segs.push([1 + k * 0.5, 2, -0.1 * k, 1 + k * 0.5, 18, -0.1 * k]);
  const hf = make(segs, 1, { K: 7 });
  const final = hf.build();
  hf.goto(30, 0);
  assert.deepEqual(Array.from(hf.h), Array.from(final));
  hf.goto(10, 0.5);
  const mid = Float32Array.from(hf.h);
  hf.goto(3, 0);
  hf.goto(10, 0.5);
  assert.deepEqual(Array.from(hf.h), Array.from(mid));
  // the deepest pass so far is segment 10 (half done), at Z -1.0
  assert.ok(Math.min(...hf.h) >= -1.0 - 1e-6);
  assert.ok(Math.min(...hf.h) < -0.85);
  const rect = hf.takeDirty();
  assert.ok(rect && rect.w > 0 && rect.h > 0);
  assert.equal(hf.takeDirty(), null);
});

test('ball cutter leaves a round groove', () => {
  const R = 2;
  const hf = make([[2, 10, -2, 18, 10, -2]], R, { prof: Int16Array.from([0]), profs: [Float32Array.from({ length: Math.ceil(R / 0.02) + 3 }, (_, i) => toolProfile({ kind: 'ball', R }, Math.min(i * 0.02, R)))] });
  const final = hf.build();
  const centre = final[39 * 80 + 40]; // y = 9.875
  const side = final[(39 + 4) * 80 + 40]; // y = 10.875: 1.0 mm off-axis
  assert.ok(Math.abs(centre - (-2 + toolProfile({ kind: 'ball', R }, 0.125))) < 0.01);
  assert.ok(Math.abs(side - (-2 + toolProfile({ kind: 'ball', R }, 0.875))) < 0.01);
});

test('cellSize coarsens big stock', () => {
  assert.equal(cellSize(100, 100), 0.25);
  const cs = cellSize(1200, 600);
  assert.ok(Math.round(1200 / cs) * Math.round(600 / cs) <= 700_000);
});

test('toolModel refuses shapes it cannot draw and unknown diameters', () => {
  assert.deepEqual(toolModel({ type: 'flat', diameter: 6.35 }), { kind: 'flat', r: 3.175 });
  assert.equal(toolModel({ type: 'ball', diameter: 2 }).kind, 'ball');
  assert.match(toolModel({ type: 'vbit', diameter: 12.7 }).skip, /vbit/);
  assert.match(toolModel({ type: 'flat' }).skip, /diameter unknown/);
  assert.match(toolModel(undefined).skip, /not described/);
});

test('buildSimInput: stock-local coordinates, tools per segment, skipped tools listed', () => {
  const text = [
    '(STOCK: 40 x 30 x 6 mm)',
    '(TOOL: T1 flat 1/4 in)',
    'G21 G90',
    'T1 M6',
    'G0 X5 Y5 Z5',
    'G1 Z-1 F300',
    'G1 X35',
    'G0 Z5',
    'T7 M6',
    'G0 X5 Y20',
    'G1 Z-1 F300',
    'G1 X35',
    'G0 Z5',
  ].join('\n');
  const program = parseGcode(text);
  const job = buildJob({ program, header: parseHeader(program.lines) });
  const sim = buildSimInput(program, job);
  assert.equal(sim.ok, true);
  assert.equal(sim.nx, 160);
  assert.equal(sim.ny, 120);
  assert.equal(sim.top, 0);
  assert.equal(sim.bottom, -6);
  assert.deepEqual(sim.skipped, [{ tool: 'T07', reason: 'diameter unknown', segments: 3 }]);
  const hf = new Heightfield(sim);
  const final = hf.build();
  // T1 cut along y = 5 is simulated, the T7 cut along y = 20 is not
  const at = (x, y) => final[Math.floor(y / sim.cs) * sim.nx + Math.floor(x / sim.cs)];
  assert.equal(at(20, 5), -1);
  assert.equal(at(20, 20), 0);
});

test('buildSimInput without stock says why', () => {
  const program = parseGcode('G1 X1 F100');
  const job = buildJob({ program, header: parseHeader(program.lines) });
  assert.deepEqual(buildSimInput(program, job), { ok: false, reason: 'no stock size (STOCK header or sidecar)' });
});
