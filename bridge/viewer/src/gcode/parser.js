// G-code interpreter for the viewer (GRBL 1.1 dialect). Pure, no Three.js, runs in Node for tests.
//
// Output is a flat "segment table" in WORK coordinates (mm), so the scene can merge geometry per
// operation without per-segment objects. Arcs are tessellated into several segments that share
// the source line number.

import { arcCenterFromR, tessellateArc } from './arcs.js';

/** Segment kinds. */
export const KIND = Object.freeze({ RAPID: 0, FEED: 1, ARC: 2, PROBE: 3 });
/** Segment flag bits. */
export const FLAG = Object.freeze({ PLUNGE: 1, MACHINE: 2 /* G53 */, INCOMPLETE_START: 4 });

const WORD_RE = /([A-Z])\s*([-+]?(?:\d+\.?\d*|\.\d+))/g;

/** Strip `( … )` and `; …` comments; returns { code, comments: [text] }. */
export function splitComments(raw) {
  let code = '';
  const comments = [];
  let i = 0;
  const s = raw;
  while (i < s.length) {
    const ch = s[i];
    if (ch === '(') {
      // GRBL has no nested comments, but some posts write "(T3 6.35 mm (0.25 in) …)"; match depth.
      let depth = 0;
      let j = i;
      for (; j < s.length; j++) {
        if (s[j] === '(') depth++;
        else if (s[j] === ')' && --depth === 0) break;
      }
      comments.push(s.slice(i + 1, j));
      i = j + 1;
    } else if (ch === ';') {
      comments.push(s.slice(i + 1));
      break;
    } else {
      code += ch;
      i++;
    }
  }
  return { code, comments };
}

/** Parse the code part of a line into [{letter, value}]. */
export function parseWords(code) {
  const out = [];
  const up = code.toUpperCase();
  WORD_RE.lastIndex = 0;
  let m;
  while ((m = WORD_RE.exec(up))) out.push({ letter: m[1], value: parseFloat(m[2]) });
  return out;
}

export function initialState() {
  return {
    motion: 0, // 0,1,2,3, 38.2…, or 80 (cancelled)
    absolute: true, // G90 / G91
    arcAbsolute: false, // G90.1 / G91.1 (IJK mode)
    plane: 17,
    units: 'mm', // G21 / G20
    feedMode: 94, // G93 / G94
    feed: 0, // mm/min
    wcs: 54,
    spindle: 5, // 3, 4 or 5
    speed: 0,
    coolant: 9,
    tool: null, // tool currently in the spindle (after M6)
    selectedTool: null, // last T word
    pos: [0, 0, 0], // work coordinates, mm
    known: [false, false, false],
    ended: false,
  };
}

/**
 * Stateful interpreter. Call `exec(text, lineNo)` for each line; segments go to `sink`.
 * `sink` methods (all optional): seg(x0,y0,z0,x1,y1,z1,kind,flags,line,feed),
 * toolChange({line, tool, pos}), dwell(seconds, line), note({line, msg}).
 */
export class GcodeInterpreter {
  constructor({ wco = [0, 0, 0], arcTolerance = 0.01, sink = {} } = {}) {
    this.wco = wco;
    this.arcTolerance = arcTolerance;
    this.sink = sink;
    this.state = initialState();
  }

  exec(raw, lineNo) {
    const { code } = splitComments(raw);
    if (!code.trim()) return;
    const words = parseWords(code);
    if (!words.length) return;
    const st = this.state;
    const sink = this.sink;

    let motion = null;
    let nonModal = null; // 4, 10, 28, 30, 53, 92
    let machine = false;
    let hasAxis = false;
    const axis = [undefined, undefined, undefined];
    const ijk = [undefined, undefined, undefined];
    let r;
    let p;
    let m6 = false;
    let tWord = null;

    for (const { letter, value } of words) {
      switch (letter) {
        case 'G': {
          const g = Math.round(value * 10) / 10;
          if (g === 0 || g === 1 || g === 2 || g === 3) motion = g;
          else if (g >= 38.2 && g <= 38.5) motion = g;
          else if (g === 80) motion = 80;
          else if (g === 17 || g === 18 || g === 19) st.plane = g;
          else if (g === 20) st.units = 'in';
          else if (g === 21) st.units = 'mm';
          else if (g === 90) st.absolute = true;
          else if (g === 91) st.absolute = false;
          else if (g === 90.1) st.arcAbsolute = true;
          else if (g === 91.1) st.arcAbsolute = false;
          else if (g === 93 || g === 94) st.feedMode = g;
          else if (g >= 54 && g <= 59) {
            st.wcs = g;
            if (g !== 54) sink.note?.({ line: lineNo, msg: `G${g}: viewer assumes the same offset as G54` });
          } else if (g === 53) machine = true;
          else if (g === 4 || g === 10 || g === 28 || g === 30 || g === 92 || g === 28.1 || g === 30.1 || g === 92.1) nonModal = g;
          // G40, G43.1, G49, G61 etc.: no effect on the drawn path
          break;
        }
        case 'M': {
          const mm = Math.round(value);
          if (mm === 3 || mm === 4 || mm === 5) st.spindle = mm;
          else if (mm === 6) m6 = true;
          else if (mm === 7 || mm === 8 || mm === 9) st.coolant = mm;
          else if (mm === 2 || mm === 30) st.ended = true;
          break;
        }
        case 'X': axis[0] = value; hasAxis = true; break;
        case 'Y': axis[1] = value; hasAxis = true; break;
        case 'Z': axis[2] = value; hasAxis = true; break;
        case 'I': ijk[0] = value; break;
        case 'J': ijk[1] = value; break;
        case 'K': ijk[2] = value; break;
        case 'R': r = value; break;
        case 'P': p = value; break;
        case 'F': st.feed = st.units === 'in' ? value * 25.4 : value; break;
        case 'S': st.speed = value; break;
        case 'T': tWord = Math.round(value); break;
        default: break; // N, L, etc.
      }
    }

    if (tWord !== null) st.selectedTool = tWord;
    if (m6) {
      const tool = st.selectedTool;
      st.tool = tool;
      sink.toolChange?.({ line: lineNo, tool, pos: st.known.every(Boolean) ? st.pos.slice() : null });
    }
    if (motion !== null) st.motion = motion;

    if (nonModal === 4) {
      if (p !== undefined) sink.dwell?.(p, lineNo);
      return;
    }
    if (nonModal === 10 || nonModal === 92 || nonModal === 92.1 || nonModal === 28.1 || nonModal === 30.1) {
      if (nonModal === 92) sink.note?.({ line: lineNo, msg: 'G92 offset ignored by the viewer' });
      return; // axis words belong to the offset command, not a move
    }
    if (nonModal === 28 || nonModal === 30) {
      // Moves through the optional point to a stored position we don't know.
      st.known = [false, false, false];
      sink.note?.({ line: lineNo, msg: `G${nonModal}: stored position unknown offline` });
      return;
    }
    if (!hasAxis) return;
    if (st.motion === 80) return;

    const scale = st.units === 'in' ? 25.4 : 1;
    const start = st.pos.slice();
    const startKnown = st.known.slice();
    const target = start.slice();
    const tKnown = startKnown.slice();
    for (let i = 0; i < 3; i++) {
      if (axis[i] === undefined) continue;
      const v = axis[i] * scale;
      if (machine) { target[i] = v - this.wco[i]; tKnown[i] = true; }
      else if (st.absolute) { target[i] = v; tKnown[i] = true; }
      else target[i] = start[i] + v; // stays unknown if start was unknown
    }
    st.pos = target;
    st.known = tKnown;

    if (!startKnown.every(Boolean) || !tKnown.every(Boolean)) return; // can't draw from an unknown point

    let flags = machine ? FLAG.MACHINE : 0;
    const mo = st.motion;
    if (mo === 0) {
      this._emit(start, target, KIND.RAPID, flags, lineNo, 0);
    } else if (mo === 1 || (mo >= 38.2 && mo <= 38.5)) {
      const kind = mo === 1 ? KIND.FEED : KIND.PROBE;
      const dz = target[2] - start[2];
      const dxy = Math.hypot(target[0] - start[0], target[1] - start[1]);
      if (kind === KIND.FEED && dz < -1e-3 && dxy < -dz) flags |= FLAG.PLUNGE;
      this._emit(start, target, kind, flags, lineNo, st.feed);
    } else if (mo === 2 || mo === 3) {
      const cw = mo === 2;
      let center;
      if (r !== undefined) {
        center = arcCenterFromR(start, target, r * scale, cw, st.plane);
        if (!center) {
          sink.note?.({ line: lineNo, msg: 'arc radius too small for its chord; drawn as a line' });
          this._emit(start, target, KIND.ARC, flags, lineNo, st.feed);
          return;
        }
      } else {
        center = start.slice();
        for (let i = 0; i < 3; i++) {
          if (ijk[i] === undefined) continue;
          center[i] = st.arcAbsolute ? ijk[i] * scale : start[i] + ijk[i] * scale;
        }
      }
      const pts = tessellateArc({ start, end: target, center, clockwise: cw, plane: st.plane, tolerance: this.arcTolerance, turns: p ?? 1 });
      let prev = start;
      for (const q of pts) {
        this._emit(prev, q, KIND.ARC, flags, lineNo, st.feed);
        prev = q;
      }
    }
  }

  _emit(a, b, kind, flags, line, feed) {
    this.sink.seg?.(a[0], a[1], a[2], b[0], b[1], b[2], kind, flags, line, feed);
  }
}

/** Growable typed segment table. */
class SegTable {
  constructor(cap = 4096) { this._alloc(cap); this.count = 0; }
  _alloc(cap) {
    const old = this.pos ? this : null;
    const pos = new Float32Array(cap * 6);
    const line = new Int32Array(cap);
    const kind = new Uint8Array(cap);
    const flags = new Uint8Array(cap);
    const feed = new Float32Array(cap);
    const tEnd = new Float32Array(cap);
    if (old) {
      pos.set(old.pos); line.set(old.line); kind.set(old.kind); flags.set(old.flags); feed.set(old.feed); tEnd.set(old.tEnd);
    }
    Object.assign(this, { pos, line, kind, flags, feed, tEnd, cap });
  }
  push(x0, y0, z0, x1, y1, z1, k, f, ln, fd, t) {
    if (this.count === this.cap) this._alloc(this.cap * 2);
    const i = this.count++;
    const o = i * 6;
    const p = this.pos;
    p[o] = x0; p[o + 1] = y0; p[o + 2] = z0; p[o + 3] = x1; p[o + 4] = y1; p[o + 5] = z1;
    this.kind[i] = k; this.flags[i] = f; this.line[i] = ln; this.feed[i] = fd; this.tEnd[i] = t;
  }
  trim() {
    const n = this.count;
    return {
      count: n,
      pos: this.pos.slice(0, n * 6),
      line: this.line.slice(0, n),
      kind: this.kind.slice(0, n),
      flags: this.flags.slice(0, n),
      feed: this.feed.slice(0, n),
      tEnd: this.tEnd.slice(0, n),
    };
  }
}

/**
 * Parse a whole program.
 * @param {string} text
 * @param {object} [opts]
 * @param {number[]} [opts.wco=[0,0,0]]  work offset (machine = work + wco); only used to place G53 moves
 * @param {number} [opts.rapidRate=5000]  mm/min, for the time estimate
 * @param {number} [opts.arcTolerance=0.01]
 * @returns {ParsedProgram}
 *
 * @typedef {object} ParsedProgram
 * @property {string[]} lines  source lines; line numbers are 1-based (lines[n-1])
 * @property {number} lineCount
 * @property {{count:number,pos:Float32Array,line:Int32Array,kind:Uint8Array,flags:Uint8Array,feed:Float32Array,tEnd:Float32Array}} seg
 * @property {{line:number,tool:number|null,pos:number[]|null}[]} toolChanges
 * @property {Float64Array} lineTime  estimated elapsed seconds at the end of each line (index = line number)
 * @property {number} totalTime  seconds
 * @property {{min:number[],max:number[]}|null} cutBounds  bounds of feed/arc moves (work)
 * @property {{min:number[],max:number[]}|null} allBounds  bounds of all moves (work)
 * @property {{line:number,msg:string}[]} notes
 * @property {object} finalState
 */
export function parseGcode(text, { wco = [0, 0, 0], rapidRate = 5000, arcTolerance = 0.01 } = {}) {
  const lines = text.split(/\r?\n/);
  if (lines.length && lines[lines.length - 1] === '') lines.pop();
  const table = new SegTable(Math.max(1024, lines.length * 2));
  const toolChanges = [];
  const notes = [];
  const lineTime = new Float64Array(lines.length + 2);
  let t = 0;
  const cutMin = [Infinity, Infinity, Infinity], cutMax = [-Infinity, -Infinity, -Infinity];
  const allMin = [Infinity, Infinity, Infinity], allMax = [-Infinity, -Infinity, -Infinity];
  const grow = (mn, mx, x, y, z) => {
    if (x < mn[0]) mn[0] = x; if (x > mx[0]) mx[0] = x;
    if (y < mn[1]) mn[1] = y; if (y > mx[1]) mx[1] = y;
    if (z < mn[2]) mn[2] = z; if (z > mx[2]) mx[2] = z;
  };
  const sink = {
    seg(x0, y0, z0, x1, y1, z1, kind, flags, line, feed) {
      const len = Math.hypot(x1 - x0, y1 - y0, z1 - z0);
      const rate = kind === KIND.RAPID || !(feed > 0) ? rapidRate : feed;
      t += (len / rate) * 60;
      table.push(x0, y0, z0, x1, y1, z1, kind, flags, line, feed, t);
      grow(allMin, allMax, x0, y0, z0); grow(allMin, allMax, x1, y1, z1);
      if (kind !== KIND.RAPID) { grow(cutMin, cutMax, x0, y0, z0); grow(cutMin, cutMax, x1, y1, z1); }
    },
    toolChange(tc) { toolChanges.push(tc); },
    dwell(sec) { t += sec; },
    note(n) { if (notes.length < 200) notes.push(n); },
  };
  const interp = new GcodeInterpreter({ wco, arcTolerance, sink });
  for (let i = 0; i < lines.length; i++) {
    interp.exec(lines[i], i + 1);
    lineTime[i + 1] = t;
  }
  lineTime[lines.length + 1] = t;
  const bounds = (mn, mx) => (mn[0] === Infinity ? null : { min: mn, max: mx });
  return {
    lines,
    lineCount: lines.length,
    seg: table.trim(),
    toolChanges,
    lineTime,
    totalTime: t,
    cutBounds: bounds(cutMin, cutMax),
    allBounds: bounds(allMin, allMax),
    notes,
    finalState: interp.state,
  };
}

/** Index of the last segment whose line is <= `line` (segments are in line order), or -1. */
export function lastSegAtOrBefore(segLine, line) {
  let lo = 0, hi = segLine.length - 1, ans = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (segLine[mid] <= line) { ans = mid; lo = mid + 1; } else hi = mid - 1;
  }
  return ans;
}

/** Line number reached at elapsed time `sec` (by the estimate). */
export function lineAtTime(lineTime, sec) {
  let lo = 1, hi = lineTime.length - 2, ans = 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (lineTime[mid] <= sec) { ans = mid; lo = mid + 1; } else hi = mid - 1;
  }
  return ans;
}
