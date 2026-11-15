import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseGcode } from '../src/gcode/parser.js';
import { parseHeader, parseStock, toolFieldsFrom, zeroOrigin } from '../src/gcode/header.js';
import { buildJob, cutExtentWithCutter, analyzeWarnings, stockBox, WARN } from '../src/gcode/job.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const NC = path.resolve(here, '../../testdata/nc');
const PUBLIC = path.resolve(here, '../../testdata-public');
const DR_RYAN = path.join(NC, 'dr_ryan_complete_T02_then_T21.nc');
const close = (a, b, eps = 1e-6) => assert.ok(Math.abs(a - b) <= eps, `expected ${b}, got ${a}`);
const load = (f, sidecar = null) => {
  const program = parseGcode(fs.readFileSync(f, 'utf8'));
  const header = parseHeader(program.lines);
  return { program, header, job: buildJob({ program, header, sidecar }) };
};

test('stock header variants', () => {
  assert.deepEqual(parseStock('at least 240 x 95 x 19.4 mm hard maple. Z0 = RAW top, through cut Z-19.7').size, [240, 95, 19.4]);
  assert.equal(parseStock('at least 240 x 95 x 19.4 mm, through cut Z-19.7').floorZ, -19.7);
  assert.equal(parseStock('at least 240 x 95 x 19.4 mm').approx, true);
  const r = parseStock('hard maple blank 298 x 184, raw 18.3-20.5 thick, in wood jig');
  assert.deepEqual(r.size, [298, 184, 20.5]);
  assert.deepEqual(r.thicknessRange, [18.3, 20.5]);
  assert.deepEqual(parseStock('MDF piece >= 370 x 277 x 12.7 [17x17 works]').size, [370, 277, 12.7]);
  assert.equal(parseStock('the installed jig itself [MDF]').size, null);
});

test('tool fields from free text', () => {
  const f = toolFieldsFrom('2-flute downcut spiral 1/4 in, Whiteside RD2100   feed 1778 / plunge 508 mm/min   CSV RPM 18000   ROUTER DIAL 3.0   CSV DOC 2.54  flute 25.4 mm');
  assert.deepEqual(f, { feed: 1778, plunge: 508, rpm: 18000, router_dial: 3, flute_length: 25.4, diameter: 6.35 });
  assert.equal(toolFieldsFrom('6.350 mm (0.2500 in)   feed  2032 mm/min').diameter, 6.35);
});

test('zero origin', () => {
  assert.equal(zeroOrigin('X0 Y0 = FRONT-LEFT corner of the STOCK, Z0 = TOP of the RAW stock.'), 'front-left-top');
  assert.equal(zeroOrigin('X0 Y0 Z0 = jig board OUTSIDE front-left corner, top face'), undefined);
});

test('stock box placement', () => {
  assert.deepEqual(stockBox([100, 50, 10], 'front-left-top'), { min: [0, 0, -10], max: [100, 50, 0] });
  assert.deepEqual(stockBox([100, 50, 10], 'back-right-bottom'), { min: [-100, -50, 0], max: [0, 0, 10] });
  assert.deepEqual(stockBox([100, 50, 10], 'center-top'), { min: [-50, -25, -10], max: [50, 25, 0] });
});

test('synthetic header markers: pipe and colon styles, FreeCAD fallback', () => {
  const lines = [
    '(STOCK: 100 x 60 x 12 mm pine)',
    '(ZERO: X0 Y0 = stock FRONT-LEFT corner, Z0 = RAW STOCK TOP)',
    '(CLEARANCE: every rapid/retract at Z+5 [work].)',
    '(CUT EXTENT incl cutter: X -1.5..101.5  Y 0.0..60.0  Zmin -12.30)',
    '(---- OP 01 | T02 | face | Face the top ----)',
    '(---- OP 2: pocket ----)',
    '(Begin operation: Profile)',
  ];
  const h = parseHeader(lines);
  assert.deepEqual(h.cutExtent, { x: [-1.5, 101.5], y: [0, 60], zmin: -12.3 });
  assert.equal(h.clearance.z, 5);
  assert.deepEqual(h.opMarkers.map((m) => [m.line, m.tool, m.name, m.style]), [[5, 2, 'face', 'pipe'], [6, null, 'pocket', 'colon'], [7, null, 'Profile', 'freecad']]);
});

test('fallback segmentation by tool change when there are no headers', () => {
  const program = parseGcode('T1 M6\nG0 X0 Y0 Z5\nG1 Z-1 F100\nT2 M6\nG0 Z5\nG1 Z-2\n');
  const job = buildJob({ program, header: parseHeader(program.lines) });
  assert.deepEqual(job.ops.map((o) => [o.tool, o.lineStart, o.lineEnd]), [[1, 1, 3], [2, 4, 6]]);
  assert.deepEqual(Array.from(job.opOfSeg), [0, 1, 1]);
});

test('warnings: below floor and outside envelope', () => {
  const program = parseGcode('G0 X0 Y0 Z5\nG1 Z-11 F100\nG0 Z5\nG0 X500\n');
  const header = parseHeader(['(STOCK: 100 x 60 x 10 mm)', '(ZERO: stock front-left, Z0 = stock top)']);
  const job = buildJob({ program, header });
  assert.equal(job.floor.z, -10);
  const w = analyzeWarnings({ program, job, wco: [-400, -400, -50], envelope: { min: [-445, -456, -95], max: [0, 0, 0] } });
  assert.equal(w.counts.belowFloor, 1);
  assert.ok(w.flags[0] & WARN.BELOW_FLOOR);
  assert.equal(w.counts.outsideEnvelope, 1); // X500 + WCO -400 = machine X100 > 0
  assert.ok(w.flags[2] & WARN.OUTSIDE_ENVELOPE);
});

test('sidecar overrides header (synthetic sample)', { skip: !fs.existsSync(path.join(PUBLIC, 'sample-sign.nc')) }, () => {
  const sidecar = JSON.parse(fs.readFileSync(path.join(PUBLIC, 'sample-sign.job.json'), 'utf8'));
  const { program, header, job } = load(path.join(PUBLIC, 'sample-sign.nc'), sidecar);
  assert.equal(job.stock.source, 'sidecar');
  assert.equal(job.floor.source, 'sidecar spoilboard_limit_z');
  assert.equal(job.ops.length, sidecar.operations.length);
  assert.deepEqual(job.ops.map((o) => o.name), sidecar.operations.map((o) => o.name));
  // header and sidecar agree on the ops for the sample
  assert.deepEqual(header.opMarkers.map((m) => m.line), sidecar.operations.map((o) => o.lines[0]));
  const w = analyzeWarnings({ program, job, wco: [-425, -436, -76], envelope: { min: [-445, -456, -95], max: [0, 0, 0] } });
  assert.equal(w.counts.belowFloor, 0);
  assert.equal(w.counts.outsideEnvelope, 0);
});

test('dr_ryan: 9 operations, T02 then T21, cut extent and Zmin', { skip: !fs.existsSync(DR_RYAN) && 'bridge/testdata/nc not present' }, () => {
  const { program, header, job } = load(DR_RYAN);
  assert.equal(program.lineCount, 26781);
  assert.equal(job.ops.length, 9);
  assert.deepEqual(job.ops.map((o) => o.tool), [2, 2, 2, 2, 21, 21, 21, 21, 21]);
  assert.deepEqual(job.ops.map((o) => o.name), ['face_pad', 'rough_hi', 'rough_lo_a', 'rough_lo_b', 'clear_hi', 'finish_top', 'clear_lo', 'finish_walls_a', 'finish_walls_b']);
  assert.deepEqual(program.toolChanges.map((t) => t.tool), [2, 21]);
  assert.equal(header.plannedOps.length, 9);
  assert.deepEqual(header.cutExtent, { x: [7.6, 232.4], y: [7.6, 87.4], zmin: -19.7 });
  assert.deepEqual(job.stock.size, [240, 95, 19.4]);
  assert.equal(job.floor.z, -19.7);
  assert.equal(job.clearance.z, 5);
  assert.equal(job.tools.get(2).diameter, 6.35);
  assert.equal(job.tools.get(21).diameter, 3.175);
  // the parsed toolpath (plus cutter radius) reproduces the post's CUT EXTENT
  const ext = cutExtentWithCutter(program, job);
  close(ext.min[0], 7.6, 0.05); close(ext.max[0], 232.4, 0.05);
  close(ext.min[1], 7.6, 0.05); close(ext.max[1], 87.4, 0.05);
  close(program.cutBounds.min[2], -19.7, 1e-4);
  // every segment after the first op marker belongs to an op
  const firstOpSeg = Array.from(program.seg.line).findIndex((l) => l >= job.ops[0].lineStart);
  assert.ok(Array.from(job.opOfSeg).slice(firstOpSeg).every((o) => o >= 0));
  // time estimate is in the ballpark of the post's EST RUNTIME (62 min)
  assert.ok(Math.abs(program.totalTime / 60 - header.estRuntimeMin) < 5);
});

test('real files parse without errors and keep op tools consistent with M6', { skip: !fs.existsSync(NC) && 'bridge/testdata/nc not present' }, () => {
  for (const f of fs.readdirSync(NC).filter((x) => x.endsWith('.nc'))) {
    const { program, job } = load(path.join(NC, f));
    assert.ok(program.seg.count > 0, f);
    assert.ok(job.ops.length > 0, f);
    for (const op of job.ops) {
      let t = null;
      for (const tc of program.toolChanges) if (tc.line <= op.lineStart) t = tc.tool;
      if (t !== null && op.tool !== null) assert.equal(op.tool, t, `${f} op ${op.index} ${op.name}`);
    }
  }
});
