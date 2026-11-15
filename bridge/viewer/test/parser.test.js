import { test } from 'node:test';
import assert from 'node:assert/strict';
import { GcodeInterpreter, parseGcode, splitComments, KIND, FLAG, lastSegAtOrBefore, lineAtTime } from '../src/gcode/parser.js';

const close = (a, b, eps = 1e-6, msg) => assert.ok(Math.abs(a - b) <= eps, `${msg ?? ''} expected ${b}, got ${a}`);
const segs = (p) => Array.from({ length: p.seg.count }, (_, i) => ({
  a: Array.from(p.seg.pos.slice(i * 6, i * 6 + 3)),
  b: Array.from(p.seg.pos.slice(i * 6 + 3, i * 6 + 6)),
  kind: p.seg.kind[i], flags: p.seg.flags[i], line: p.seg.line[i], feed: p.seg.feed[i],
}));

test('comments: parens, nested parens, semicolon', () => {
  assert.deepEqual(splitComments('G0 X1 (move) Y2 ; tail'), { code: 'G0 X1  Y2 ', comments: ['move', ' tail'] });
  const r = splitComments('(   T3    6.350 mm (0.2500 in)   feed  2032 mm/min)');
  assert.equal(r.code.trim(), '');
  assert.equal(r.comments.length, 1);
});

test('modal state: motion, feed, units, distance mode, plane, wcs, spindle', () => {
  const it = new GcodeInterpreter();
  const st = it.state;
  it.exec('G21 G90 G17 G94', 1);
  assert.equal(st.units, 'mm'); assert.equal(st.absolute, true); assert.equal(st.plane, 17);
  it.exec('G0 X0 Y0 Z5', 2);
  it.exec('G1 Z-1 F300', 3);
  assert.equal(st.motion, 1); assert.equal(st.feed, 300);
  it.exec('X10', 4); // modal G1 continues, F modal
  assert.deepEqual(st.pos, [10, 0, -1]);
  it.exec('G18', 5); assert.equal(st.plane, 18);
  it.exec('G55', 6); assert.equal(st.wcs, 55);
  it.exec('S18000 M3', 7); assert.equal(st.spindle, 3); assert.equal(st.speed, 18000);
  it.exec('M5', 8); assert.equal(st.spindle, 5);
  it.exec('G20', 9);
  it.exec('G1 X1 F10', 10); // inches
  close(st.pos[0], 25.4); close(st.feed, 254);
  it.exec('G80', 11); it.exec('X2', 12); // motion cancelled: no move
  close(st.pos[0], 25.4);
  it.exec('M2', 13); assert.equal(st.ended, true);
});

test('feed on a G0 line is modal for the following G1', () => {
  const p = parseGcode('G90\nG0 X0 Y0 Z5 F5000\nG1 X10\n');
  const s = segs(p);
  assert.equal(s.length, 1);
  assert.equal(s[0].kind, KIND.FEED);
  assert.equal(s[0].feed, 5000);
});

test('no segment is drawn from an unknown start position', () => {
  const p = parseGcode('G53 G0 Z-5\nG0 X0 Y0 Z5\nG1 Z0 F100\n', { wco: [-400, -400, -60] });
  const s = segs(p);
  assert.equal(s.length, 1); // only the plunge; the first two moves start from unknown X/Y
  assert.deepEqual(s[0].a, [0, 0, 5]);
  assert.ok(s[0].flags & FLAG.PLUNGE);
});

test('G53 moves are converted from machine to work coordinates with the WCO', () => {
  const p = parseGcode('G0 X10 Y20 Z5\nG53 G0 Z-5\nG0 X0\n', { wco: [-400, -300, -60] });
  const s = segs(p);
  assert.equal(s.length, 2);
  assert.deepEqual(s[0].b, [10, 20, 55]); // machine Z-5 = work Z55 when WCO z = -60
  assert.ok(s[0].flags & FLAG.MACHINE);
  assert.ok(!(s[1].flags & FLAG.MACHINE)); // G53 is non-modal
  assert.deepEqual(s[1].b, [0, 20, 55]);
});

test('G91 incremental moves and G90 return', () => {
  const p = parseGcode('G90 G0 X0 Y0 Z0\nG91\nG1 X5 F100\nY5\nZ-1\nG90\nX0\n');
  const s = segs(p);
  assert.deepEqual(s.map((x) => x.b), [[5, 0, 0], [5, 5, 0], [5, 5, -1], [0, 5, -1]]);
});

test('G91 from an unknown start keeps the axis unknown', () => {
  const it = new GcodeInterpreter();
  it.exec('G91 G0 X5', 1);
  assert.equal(it.state.known[0], false);
});

test('G10/G92/G28 axis words are not moves', () => {
  const p = parseGcode('G0 X0 Y0 Z0\nG10 L2 P1 X100\nG92 X0\nG1 X1 F100\n');
  assert.equal(p.seg.count, 1);
  assert.equal(p.notes.length, 1); // G92 noted
  const q = parseGcode('G0 X0 Y0 Z0\nG28 G91 Z0\nG90 G1 X1 F100\n');
  assert.equal(q.seg.count, 0); // position unknown after G28
});

test('M6 records tool changes with the selected tool and position', () => {
  const p = parseGcode('T2 M6\nG0 X1 Y2 Z3\nT21\nM6\nM6 T4\n');
  assert.deepEqual(p.toolChanges.map((t) => t.tool), [2, 21, 4]);
  assert.deepEqual(p.toolChanges[0].pos, null);
  assert.deepEqual(p.toolChanges[1].pos, [1, 2, 3]);
  assert.equal(p.finalState.tool, 4);
});

test('plunge flag: steep down feed moves only', () => {
  const p = parseGcode('G0 X0 Y0 Z5\nG1 Z0 F100\nX10 Z-0.5\nX10 Z-3\n');
  const s = segs(p);
  assert.ok(s[0].flags & FLAG.PLUNGE);
  assert.ok(!(s[1].flags & FLAG.PLUNGE)); // shallow ramp
  assert.ok(s[2].flags & FLAG.PLUNGE);
});

test('time estimate: feed and rapid rates, dwell', () => {
  const p = parseGcode('G0 X0 Y0 Z0\nG0 X50\nG1 X150 F600\nG4 P2\n', { rapidRate: 3000 });
  close(p.totalTime, 1 + 10 + 2, 1e-4);
  assert.equal(lineAtTime(p.lineTime, 5), 2);
  assert.equal(lastSegAtOrBefore(p.seg.line, 2), 0);
  assert.equal(lastSegAtOrBefore(p.seg.line, 1), -1);
});
