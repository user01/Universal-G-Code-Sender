import { test } from 'node:test';
import assert from 'node:assert/strict';
import { stockMachineBox, rectHitsCircle, rectInside, placementChecks } from '../src/twin/placement.js';
import { normalizeProfile, normalizeBed, BUILTIN_PROFILE } from '../src/data/profile.js';
import { parseGcode } from '../src/gcode/parser.js';
import { parseHeader } from '../src/gcode/header.js';
import { buildJob } from '../src/gcode/job.js';
import {
  roughEstimate, bridgeEstimate, opTimes, toolTimes, duration, approx, clock, normalizeEta, etaLine, opFinishClock,
} from '../src/data/estimate.js';

const profile = normalizeProfile(BUILTIN_PROFILE, 'test');
const jobOf = (text) => {
  const program = parseGcode(text);
  return { program, job: buildJob({ program, header: parseHeader(program.lines) }) };
};

// ------------------------------------------------------------------------------ placement

test('stock machine box = work box + WCO (front-left-top default)', () => {
  const stock = { min: [0, 0, -12], max: [120, 80, 0] };
  assert.deepEqual(stockMachineBox(stock, [-345.05, -314, -47.245]), { min: [-345.05, -314, -59.245], max: [-225.05, -234, -47.245] });
  assert.equal(stockMachineBox(null, [0, 0, 0]), null);
});

test('rect/circle and rect inside helpers', () => {
  assert.equal(rectHitsCircle([0, 0], [10, 10], [15, 5], 4), false);
  assert.equal(rectHitsCircle([0, 0], [10, 10], [13, 5], 4), true);
  assert.equal(rectHitsCircle([0, 0], [10, 10], [13, 13], 4), false); // corner distance 4.24
  assert.equal(rectInside({ min: [1, 1], max: [5, 5] }, { min: [0, 0], max: [6, 6] }), true);
  assert.equal(rectInside({ min: [-1, 1], max: [5, 5] }, { min: [0, 0], max: [6, 6] }), false);
});

test('bed section is normalized; missing parts stay undefined', () => {
  const b = normalizeBed({ spoilboard: { min_xy: [-1, -2], max_xy: [3, 4] }, bitsetter_radius: 15 });
  assert.deepEqual(b.spoilboard, { min: [-1, -2], max: [3, 4], thickness: 19 });
  assert.equal(b.yRails, undefined);
  assert.equal(b.bitsetterRadius, 15);
  assert.equal(b.verified, false);
  assert.equal(normalizeBed(null), undefined);
  assert.ok(profile.bed.spoilboard && profile.bed.yRails && profile.bed.xExtrusions);
});

test('stock over the BitSetter and off the spoilboard are flagged', () => {
  const stock = { min: [0, 0, -12], max: [100, 100, 0] };
  // front-right corner of the stock at X -10, Y -440: covers the BitSetter at (-4.2, -448.2) with r 20
  const { items } = placementChecks({ profile, stock, wco: [-110, -440, -50] });
  const kinds = items.map((i) => i.kind);
  assert.ok(kinds.includes('stock_on_bitsetter'));
  assert.ok(kinds.includes('stock_off_spoilboard'));
  assert.match(items.find((i) => i.kind === 'stock_off_spoilboard').msg, /assumed, not measured/);
  // in the middle of the bed: nothing
  assert.deepEqual(placementChecks({ profile, stock, wco: [-300, -300, -50] }).items, []);
  // assumed WCO is named in the message
  assert.match(placementChecks({ profile, stock, wco: [-110, -440, -50], wcoSource: 'assumed' }).items[0].msg, /assumed work offset/);
});

test('cuts outside the stock beyond tool diameter + 1 mm are flagged, overhanging profile cuts are not', () => {
  const header = '(STOCK: 50 x 40 x 10 mm)\n(TOOL: T1 flat 1/4 in)\nG21 G90\nT1 M6\nG0 Z5\n';
  // profile around the stock at the tool radius outside: fine
  const ok = jobOf(`${header}G0 X-3.2 Y-3.2\nG1 Z-10.3 F300\nG1 X53.2\nG1 Y43.2\nG1 X-3.2\nG1 Y-3.2\nG0 Z5\n`);
  assert.deepEqual(placementChecks({ profile, stock: ok.job.stock, wco: [-300, -300, -50], ...ok }).items, []);
  // a pocket 30 mm past the right edge: the stock or zero is wrong
  const bad = jobOf(`${header}G0 X60 Y10\nG1 Z-2 F300\nG1 X80\nG0 Z5\n`);
  const items = placementChecks({ profile, stock: bad.job.stock, wco: [-300, -300, -50], ...bad }).items;
  assert.equal(items.length, 1);
  assert.equal(items[0].kind, 'cut_outside_stock');
  assert.equal(items[0].line, 7);
  assert.match(items[0].msg, /outside the stock by up to 30\.0 mm/);
});

// ------------------------------------------------------------------------------ estimate

const prog = jobOf('(STOCK: 50 x 40 x 10 mm)\nG21 G90\nT1 M6\nG0 X10 Y10\nG1 Z-1 F600\nG1 X40 F600\nT2 M6\nG1 Y30 F600\nM2\n');

test('rough estimate is the parser lineTime, labelled rough', () => {
  const e = roughEstimate(prog.program);
  assert.equal(e.source, 'rough');
  assert.match(e.label, /rough \(distance ÷ feed\)/);
  assert.equal(e.total, prog.program.totalTime);
  assert.deepEqual(e.toolChanges.map((t) => [t.line, t.to]), [[3, 1], [7, 2]]);
});

test('bridge estimate maps line_end_s onto lines; mismatched files fall back (null)', () => {
  const n = prog.program.lineCount;
  const les = Array.from({ length: n }, (_, i) => (i + 1) * 10);
  const e = bridgeEstimate({ version: 1, source: 'grbl-planner', total_s: n * 10 + 30, line_end_s: les, tool_changes: [{ line: 7, from: 'T1', to: 'T2', at_s: 70 }], assumptions: ['tool change 60 s'] }, prog.program);
  assert.equal(e.source, 'grbl-planner');
  assert.equal(e.lineTime[1], 10);
  assert.equal(e.lineTime[n], n * 10);
  assert.equal(e.total, n * 10 + 30);
  assert.equal(e.lineTime[n + 1], n * 10 + 30);
  assert.deepEqual(e.assumptions, ['tool change 60 s']);
  assert.equal(e.toolChanges[0].at_s, 70);
  assert.equal(bridgeEstimate({ line_end_s: [1, 2] }, prog.program), null);
  assert.equal(bridgeEstimate(null, prog.program), null);
  assert.equal(bridgeEstimate({ error: 'not_found' }, prog.program), null);
  // a non-monotonic or missing entry never makes time run backwards
  const holes = les.slice(); holes[3] = null; holes[4] = 1;
  const h = bridgeEstimate({ line_end_s: holes }, prog.program);
  assert.equal(h.lineTime[4], 30);
  assert.equal(h.lineTime[5], 30);
});

test('op and tool times from an estimate', () => {
  const lineTime = Float64Array.from([0, 10, 20, 30, 40, 50, 60, 70, 80, 90]);
  const ops = [{ lineStart: 2, lineEnd: 4, tool: 1 }, { lineStart: 5, lineEnd: 6, tool: 2 }, { lineStart: 7, lineEnd: 8, tool: 1 }];
  assert.deepEqual(opTimes({ lineTime }, ops), [{ start: 10, end: 40, s: 30 }, { start: 40, end: 60, s: 20 }, { start: 60, end: 80, s: 20 }]);
  assert.deepEqual(toolTimes({ lineTime }, ops), [{ tool: 1, s: 50 }, { tool: 2, s: 20 }]);
  const now = Date.UTC(2026, 10, 16, 12, 0, 0);
  const fin = opFinishClock(opTimes({ lineTime }, ops), 50, 1.5, now);
  assert.equal(fin[0], null);
  assert.equal(fin[1].getTime(), now + 15000);
  assert.equal(fin[2].getTime(), now + 45000);
});

test('duration and approx formatting', () => {
  assert.equal(duration(65), '1:05');
  assert.equal(duration(3723), '1:02:03');
  assert.equal(duration(undefined), '–');
  assert.equal(approx(45), '45 s');
  assert.equal(approx(240), '4 min');
  assert.equal(approx(3900), '1 h 05 min');
  assert.equal(clock(new Date(2026, 10, 16, 18, 42)), '18:42');
});

test('ETA line: full, partial and empty', () => {
  const now = new Date(2026, 10, 16, 18, 0, 0).getTime();
  const full = {
    elapsed_s: 300, adjusted_remaining_s: 2520, ratio: 1.1, predicted_finish: new Date(2026, 10, 16, 18, 42).toISOString(),
    current_op: 'contour', current_tool: 'T21', op_remaining_s: 240, next_tool_change: { line: 900, tool: 'T3', in_s: 720 }, tool_changes_remaining: 1,
  };
  assert.equal(etaLine(full, now), 'Now: T21 contour · 4 min left in op · job ≈ 18:42 (42 min left) · next tool change (T03) in 12 min');
  // only remaining seconds: finish computed from now
  assert.equal(etaLine({ adjusted_remaining_s: 600 }, now), 'job ≈ 18:10 (10 min left)');
  assert.equal(etaLine({ current_tool: 21, tool_changes_remaining: 0, next_tool_change: null }, now), 'Now: T21 · no more tool changes');
  assert.equal(etaLine({}, now), '');
  assert.equal(etaLine(null, now), '');
  assert.equal(normalizeEta({ ratio: 'x' }), null);
});
