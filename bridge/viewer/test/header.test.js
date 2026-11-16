import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseGcode } from '../src/gcode/parser.js';
import { parseHeader, parseStock, toolFieldsFrom, zeroOrigin } from '../src/gcode/header.js';
import { buildJob, cutExtentWithCutter, analyzeWarnings, stockBox, WARN } from '../src/gcode/job.js';
import { sidecarCandidates } from '../src/data/sources.js';
import { normalizeProfile, defaultWorkOffset, spoilboardZ, BUILTIN_PROFILE } from '../src/data/profile.js';

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

test('fallback segmentation: motion before the first M6 is its own segment; segments without motion are dropped; ends trimmed', () => {
  const program = parseGcode('G0 X0 Y0 Z5\nG1 Z-1 F100\n(comment)\n\nT1 M6\nT2 M6\nG0 Z5\nG1 Z-2\n(end)\n');
  const job = buildJob({ program, header: parseHeader(program.lines) });
  assert.deepEqual(job.ops.map((o) => [o.tool, o.lineStart, o.lineEnd]), [[null, 1, 2], [2, 6, 8]]);
  const whole = parseGcode('G0 X0 Y0 Z5\nG1 Z-1 F100\n');
  assert.deepEqual(buildJob({ program: whole, header: parseHeader(whole.lines) }).ops.map((o) => [o.name, o.lineStart, o.lineEnd]), [['program', 1, 2]]);
});

// D11 (PLAN.md), post-headers.md §4: the M6 block between two operations belongs to neither.
test('D11: an op ends before an intervening M6, trimmed back over blank and comment lines', () => {
  const src = [
    '(---- OP 01 | T02 | face | top ----)', // 1
    'G0 X0 Y0 Z5', //                           2
    'G1 Z-1 F100', //                           3
    'G53 G0 Z-5', //                            4
    'M5', //                                    5
    '', //                                      6
    '(TOOL CHANGE)', //                         7
    'T21 M6', //                                8
    'S18000 M3', //                             9
    '', //                                     10
    '(---- OP 02 | T21 | clear | hi ----)', // 11
    'G0 X1 Y1 Z5', //                          12
    'G1 Z-2 F100', //                          13
  ].join('\n');
  const program = parseGcode(src);
  const job = buildJob({ program, header: parseHeader(program.lines) });
  assert.deepEqual(job.ops.map((o) => [o.index, o.tool, o.lineStart, o.lineEnd]), [[1, 2, 1, 5], [2, 21, 11, 13]]);
  // lines 6-10 (the tool-change block) are in no operation
  const opAt = (l) => job.ops.findIndex((o) => l >= o.lineStart && l <= o.lineEnd);
  for (let l = 6; l <= 10; l++) assert.equal(opAt(l), -1, `line ${l}`);
  assert.deepEqual(job.issues, []);
});

test('D11: an M6 before the op\'s first move stays in the op (marker, then tool change, then motion)', () => {
  const program = parseGcode(['(---- OP 1: pocket ----)', 'T3 M6', 'G0 X0 Y0 Z5', 'G1 Z-1 F100', '(---- OP 2: profile ----)', 'T4 M6', 'G0 Z5', 'G1 Z-3'].join('\n'));
  const job = buildJob({ program, header: parseHeader(program.lines) });
  assert.deepEqual(job.ops.map((o) => [o.tool, o.lineStart, o.lineEnd]), [[3, 1, 4], [4, 5, 8]]);
});

test('op tool fallback comes from the first move, not the marker line; marker/loaded mismatch and M6 without T are flagged', () => {
  // colon markers carry no tool: the op's tool is the one loaded at its first move (line 3), not at the marker (line 1)
  const a = parseGcode(['(---- OP 1: rough ----)', 'T5 M6', 'G0 X0 Y0 Z5', 'G1 Z-1 F100'].join('\n'));
  assert.equal(buildJob({ program: a, header: parseHeader(a.lines) }).ops[0].tool, 5);
  // marker says T02 but T03 is in the spindle
  const b = parseGcode(['T3 M6', '(---- OP 01 | T02 | face | x ----)', 'G0 X0 Y0 Z5', 'G1 Z-1 F100', 'M6', 'G1 Z-2'].join('\n'));
  const jb = buildJob({ program: b, header: parseHeader(b.lines) });
  assert.equal(jb.ops[0].tool, 2);
  assert.equal(jb.ops[0].toolInGcode, 3);
  assert.deepEqual(jb.issues.map((i) => [i.kind, i.line]), [['m6_no_t', 5], ['tool_mismatch', 2]]);
  assert.deepEqual(b.toolChanges.map((t) => [t.line, t.tool, t.hasT]), [[1, 3, true], [5, 3, false]]);
  assert.ok(b.notes.some((n) => n.line === 5 && /M6 without a T word/.test(n.msg)));
  // the job-level findings show up in the warnings list
  const w = analyzeWarnings({ program: b, job: jb, wco: [0, 0, 0] });
  assert.equal(w.counts.other, 2);
  assert.deepEqual(w.items.slice(0, 2).map((i) => i.kind), ['m6_no_t', 'tool_mismatch']);
  // "T7" on an earlier line still counts as the T word for the next M6
  const c = parseGcode('T7\nG0 X0 Y0 Z5\nM6\n');
  assert.deepEqual(c.toolChanges.map((t) => [t.tool, t.hasT]), [[7, true]]);
});

test('D12: sidecar candidates', () => {
  assert.deepEqual(sidecarCandidates('../testdata/part.nc'), ['../testdata/part.job.json', '../testdata/part.nc.job.json']);
  assert.deepEqual(sidecarCandidates('/f/part.nc?x=1'), ['/f/part.job.json?x=1', '/f/part.nc.job.json?x=1']);
  assert.deepEqual(sidecarCandidates('noext'), ['noext.job.json']);
});

test('profile spoilboard_z_machine is honored when set', () => {
  const assumed = normalizeProfile(BUILTIN_PROFILE, 'test');
  assert.equal(spoilboardZ(assumed), -85);
  const measured = normalizeProfile({ ...BUILTIN_PROFILE, spoilboard_z_machine: -78.5 }, 'test');
  assert.equal(spoilboardZ(measured), -78.5);
  assert.equal(normalizeProfile({ ...BUILTIN_PROFILE, spoilboard_z_machine: null }, 'test').spoilboardZ, undefined);
  const stock = stockBox([240, 95, 19.4]);
  assert.deepEqual(defaultWorkOffset(measured, stock).map((v) => Math.round(v * 1000) / 1000), [-425, -436, -59.1]);
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
  assert.deepEqual(program.toolChanges.map((t) => [t.line, t.tool]), [[48, 2], [17646, 21]]);
  // D11: same ranges as post-headers.md §4 and the Python reference (ugs_mcp.gcode.job)
  assert.deepEqual(job.ops.map((o) => [o.lineStart, o.lineEnd]), [
    [53, 455], [456, 5892], [5893, 13384], [13385, 17636], [17651, 19686], [19687, 20143], [20144, 25715], [25716, 26404], [26405, 26781],
  ]);
  for (const l of [17646, 17647, 17648]) assert.ok(job.ops.every((o) => l < o.lineStart || l > o.lineEnd), `line ${l} (tool change) is in no op`);
  assert.deepEqual(job.issues, []);
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
      // the tool loaded at the op's first move matches the op's tool (no tool_mismatch in the corpus)
      const first = Array.from(program.moveLines).find((l) => l >= op.lineStart);
      let t = null;
      for (const tc of program.toolChanges) if (tc.line <= first) t = tc.tool;
      if (t !== null && op.tool !== null) assert.equal(op.tool, t, `${f} op ${op.index} ${op.name}`);
      // D11: no op contains an M6 after its first move
      assert.ok(!program.toolChanges.some((tc) => tc.line > first && tc.line <= op.lineEnd), `${f} op ${op.index} contains an M6`);
    }
    assert.ok(!job.issues.some((i) => i.kind === 'tool_mismatch'), f);
  }
});
