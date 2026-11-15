// Post-processor header parser. Pure, no Three.js.
//
// Reads the comment lines the user's post (post/user.py) writes, plus the FreeCAD grbl_post
// "(Begin operation: …)" markers as a fallback. Every pattern relied on is listed in
// bridge/viewer/README.md ("Header patterns") so it can be reconciled with bridge/docs/post-headers.md.

const NUM = '(-?\\d+(?:\\.\\d+)?)';

/** Inner text of a whole-line comment, or null. Handles nested parens like "(T3  6.35 mm (0.25 in) …)". */
export function commentText(line) {
  const s = line.trim();
  if (!s.startsWith('(')) return s.startsWith(';') ? s.slice(1).trim() : null;
  return s.endsWith(')') ? s.slice(1, -1) : s.slice(1);
}

/** Tool number from "T02" / "T2" / 2. */
export function toolNum(t) {
  if (t === null || t === undefined) return null;
  if (typeof t === 'number') return t;
  const m = /T?\s*(\d+)/i.exec(String(t));
  return m ? parseInt(m[1], 10) : null;
}

/** Display id, two digits like the post: 2 -> "T02". */
export function toolId(n) {
  return n === null || n === undefined ? '?' : `T${String(n).padStart(2, '0')}`;
}

function toolType(text) {
  if (/ball/i.test(text)) return 'ball';
  if (/v-?groove|v-?bit|\d+\s*deg\b|engrav/i.test(text)) return 'vbit';
  if (/chamfer/i.test(text)) return 'chamfer';
  return 'flat';
}

function diameterFrom(text) {
  let m = /\bdia(?:meter)?\.?\s*(\d+(?:\.\d+)?)\s*mm/i.exec(text);
  if (m) return parseFloat(m[1]);
  m = /^\s*(\d+(?:\.\d+)?)\s*mm\b/i.exec(text);
  if (m) return parseFloat(m[1]);
  m = /(?:^|[\s,])(\d+)\/(\d+)\s*in?\b(?!\s*max)/i.exec(text);
  if (m) return (parseInt(m[1], 10) / parseInt(m[2], 10)) * 25.4;
  m = /(?:^|[\s,])(\d+(?:\.\d+)?)\s*in\b(?!\s*max)/i.exec(text);
  if (m) return parseFloat(m[1]) * 25.4;
  return undefined;
}

/** Extract tool fields from free text. */
export function toolFieldsFrom(text) {
  const f = {};
  let m;
  if ((m = /\bfeed\s+(\d+(?:\.\d+)?)/i.exec(text))) f.feed = parseFloat(m[1]);
  if ((m = /\bplunge\s+(\d+(?:\.\d+)?)/i.exec(text))) f.plunge = parseFloat(m[1]);
  if ((m = /(\d{4,6})\s*RPM/i.exec(text)) || (m = /RPM\s+(\d{4,6})/i.exec(text))) f.rpm = parseFloat(m[1]);
  if ((m = /DIAL\s+(?:TO\s+)?(\d+(?:\.\d+)?)/i.exec(text))) f.router_dial = parseFloat(m[1]);
  if ((m = /flute\s+(\d+(?:\.\d+)?)\s*mm/i.exec(text))) f.flute_length = parseFloat(m[1]);
  const d = diameterFrom(text);
  if (d !== undefined) f.diameter = Math.round(d * 1000) / 1000;
  return f;
}

/** Tool name from the description: chunks before "feed", "LOADS AT", "CSV", "ROUTER", "… RPM". */
function toolName(desc) {
  const chunks = desc.split(/\s{2,}/).map((c) => c.trim()).filter(Boolean);
  const keep = [];
  for (const c of chunks) {
    if (/^(feed|loads at|csv|router|already|\d+\s*rpm|\d+(?:\.\d+)?\s*mm\b)/i.test(c)) break;
    keep.push(c.replace(/,\s*$/, ''));
  }
  return keep.join(', ').replace(/\s+/g, ' ').trim() || undefined;
}

/**
 * Parse header and marker comments.
 * @param {string[]} lines  source lines (line n is lines[n-1])
 */
export function parseHeader(lines) {
  const h = {
    file: undefined,
    project: undefined,
    stock: null, // {size:[x,y,z]|null, text, approx, thicknessRange?, floorZ?}
    zero: null, // {text, origin}
    clearance: null, // {z, text}
    units: undefined,
    tools: new Map(), // num -> {id, num, name, type, diameter, ...}
    plannedOps: [], // from the (OPERATIONS:) list
    opMarkers: [], // {line, index, tool(num|null), name, desc, style}
    opEnds: [], // FreeCAD "(Finish operation: …)" lines
    cutExtent: null, // {x:[a,b], y:[a,b], zmin}
    estRuntimeMin: undefined,
    floor: null, // {z, text}: deliberate floor below the stock (through cut / FLOOR)
    rapidRate: undefined,
  };

  const tool = (num) => {
    let t = h.tools.get(num);
    if (!t) { t = { id: toolId(num), num }; h.tools.set(num, t); }
    return t;
  };
  const merge = (t, fields, name) => {
    for (const [k, v] of Object.entries(fields)) if (t[k] === undefined && v !== undefined) t[k] = v;
    if (name && !t.name) t.name = name;
    if (!t.type && (t.name || name)) t.type = toolType(t.name || name);
  };

  let lastTool = null; // tool a continuation line ("   feed 1778 / plunge 508") belongs to

  for (let i = 0; i < lines.length; i++) {
    const c = commentText(lines[i]);
    if (c === null) { lastTool = null; continue; }
    const lineNo = i + 1;
    const t = c.trim();
    let m;

    // --- body markers -------------------------------------------------------------------
    if ((m = /^-{2,}\s*OP\s+(\d+)\s*\|\s*T(\d+)\s*\|\s*([^|]+?)\s*(?:\|\s*(.*?))?\s*-{2,}\s*$/i.exec(t))) {
      h.opMarkers.push({ line: lineNo, index: parseInt(m[1], 10), tool: parseInt(m[2], 10), name: m[3].trim(), desc: (m[4] || '').trim(), style: 'pipe' });
      lastTool = null;
      continue;
    }
    if ((m = /^-{2,}\s*OP\s+(\d+)\s*:\s*(.*?)\s*-{2,}\s*$/i.exec(t))) {
      h.opMarkers.push({ line: lineNo, index: parseInt(m[1], 10), tool: null, name: m[2].trim(), desc: '', style: 'colon' });
      lastTool = null;
      continue;
    }
    if ((m = /^Begin operation:\s*(.+)$/i.exec(t))) {
      h.opMarkers.push({ line: lineNo, index: h.opMarkers.length + 1, tool: null, name: m[1].trim(), desc: '', style: 'freecad' });
      continue;
    }
    if ((m = /^Finish operation:\s*(.+)$/i.exec(t))) {
      h.opEnds.push({ line: lineNo, name: m[1].trim() });
      continue;
    }

    // --- tools ------------------------------------------------------------------------
    // (TOOL [FIRST]: T02 desc …) (TOOL AFTER …: T21 desc …) (TOOL: T21 desc …)
    if ((m = /^TOOL\b[^:]*:\s*T(\d+)\s+(.*)$/i.exec(t))) {
      const tt = tool(parseInt(m[1], 10));
      merge(tt, toolFieldsFrom(m[2]), toolName(m[2]));
      lastTool = tt;
      continue;
    }
    // (INSTALL IN COLLET:   T21 desc) / (  LOAD:  T02  desc)
    if ((m = /^\s*(?:INSTALL IN COLLET|LOAD)\s*:\s*T(\d+)\s+(.*)$/i.exec(c))) {
      const tt = tool(parseInt(m[1], 10));
      merge(tt, toolFieldsFrom(m[2]), toolName(m[2]));
      lastTool = tt;
      continue;
    }
    // (  1. T01  3-wing surfacing bit 1 in    Whiteside 6210   LOADS AT …)
    if ((m = /^\s*\d+\.\s+T(\d+)\s+(.*)$/.exec(c))) {
      const tt = tool(parseInt(m[1], 10));
      merge(tt, toolFieldsFrom(m[2]), toolName(m[2]));
      lastTool = tt;
      continue;
    }
    // FreeCAD/other: (   T3    6.350 mm (0.2500 in)   feed  2032 mm/min   18000 RPM)
    if ((m = /^\s+T(\d+)\s+(\d+(?:\.\d+)?\s*mm.*)$/.exec(c))) {
      const tt = tool(parseInt(m[1], 10));
      merge(tt, toolFieldsFrom(m[2]));
      lastTool = tt;
      continue;
    }
    // continuation lines of a tool block: (  GEOM:  dia 6.350 mm …) (  BIT:  Whiteside …) (       … feed 1778 / plunge 508 mm/min)
    if (lastTool && /^\s{2,}/.test(c)) {
      const bit = /^\s*BIT:\s*(.*?)\s*(?:\[|$)/i.exec(c);
      if (bit && lastTool.name && !lastTool.name.includes(bit[1])) lastTool.name += `, ${bit[1]}`;
      const f = toolFieldsFrom(c.replace(/^\s*GEOM:\s*/i, 'dia '));
      if (!/GEOM/i.test(c)) delete f.diameter; // only trust diameters from GEOM lines here
      merge(lastTool, f);
      continue;
    }
    lastTool = null;

    // --- planned operations list --------------------------------------------------------
    // (  1. [T02] face_pad - desc)   (   1. [T01] ~min   1  desc)
    if ((m = /^\s*(\d+)\.\s*\[T(\d+)\]\s*(?:~\s*min\s+\d+\s+)?(.*)$/i.exec(c))) {
      const rest = m[3].trim();
      const dash = /^(\S+)\s+-\s+(.*)$/.exec(rest);
      h.plannedOps.push({ index: parseInt(m[1], 10), tool: parseInt(m[2], 10), name: dash ? dash[1] : rest, desc: dash ? dash[2] : '' });
      continue;
    }

    // --- header fields (first occurrence wins) ------------------------------------------
    if (!h.file && (m = /^FILE:\s*(.+)$/i.exec(t))) { h.file = m[1].trim(); continue; }
    if (!h.project && (m = /^PROJECT:\s*(.+)$/i.exec(t))) { h.project = m[1].trim(); continue; }
    if (!h.stock && (m = /^STOCK:\s*(.+)$/i.exec(t))) {
      h.stock = parseStock(m[1]);
      if (h.stock.floorZ !== undefined && !h.floor) h.floor = { z: h.stock.floorZ, text: m[1].trim() };
      continue;
    }
    if (!h.zero && (m = /^(?:WORK\s+)?ZERO\b[^:]*:\s*(.+)$/i.exec(t))) { h.zero = { text: m[1].trim(), origin: zeroOrigin(m[1]) }; continue; }
    if (!h.clearance && (m = /^CLEARANCE:\s*(.+)$/i.exec(t))) {
      const z = new RegExp(`Z\\s*\\+?\\s*${NUM}`, 'i').exec(m[1]);
      h.clearance = { z: z ? parseFloat(z[1]) : undefined, text: m[1].trim() };
      continue;
    }
    if (!h.units && (m = /^UNITS:\s*(mm|in)/i.exec(t))) { h.units = m[1].toLowerCase(); continue; }
    if (!h.cutExtent && (m = new RegExp(`^CUT EXTENT[^:]*:\\s*X\\s*${NUM}\\s*\\.\\.\\s*${NUM}\\s+Y\\s*${NUM}\\s*\\.\\.\\s*${NUM}\\s+Zmin\\s*${NUM}`, 'i').exec(t))) {
      h.cutExtent = { x: [parseFloat(m[1]), parseFloat(m[2])], y: [parseFloat(m[3]), parseFloat(m[4])], zmin: parseFloat(m[5]) };
      continue;
    }
    if (h.estRuntimeMin === undefined && (m = /^EST RUNTIME:\s*(\d+(?:\.\d+)?)\s*min/i.exec(t))) { h.estRuntimeMin = parseFloat(m[1]); continue; }
    if (!h.floor && (m = new RegExp(`^FLOOR:\\s*Z\\s*${NUM}`, 'i').exec(t))) { h.floor = { z: parseFloat(m[1]), text: t }; continue; }
    if (h.rapidRate === undefined && (m = /^Rapids:\s*(\d+(?:\.\d+)?)/i.exec(t))) { h.rapidRate = parseFloat(m[1]); continue; }
  }

  // Tool hints from operation markers' detail lines: "(     tool T21 2-flute …, feed 1143 / plunge 381 mm/min)"
  for (let i = 0; i < lines.length; i++) {
    const c = commentText(lines[i]);
    if (!c) continue;
    const m = /^\s+tool\s+T(\d+)\s+(.*)$/.exec(c);
    if (m) { const tt = tool(parseInt(m[1], 10)); merge(tt, toolFieldsFrom(m[2]), toolName(m[2].split(/,\s*feed/i)[0])); }
  }
  // FreeCAD tool-controller names: "TC_T03_6.35mm_Upcut002"
  for (const op of h.opMarkers) {
    const m = /^TC_T(\d+)_(\d+(?:\.\d+)?)mm_?(.*)$/i.exec(op.name);
    if (m) { const tt = tool(parseInt(m[1], 10)); merge(tt, { diameter: parseFloat(m[2]) }, m[3] ? `${m[2]} mm ${m[3].replace(/\d+$/, '')}` : undefined); }
  }
  for (const t of h.tools.values()) if (!t.type) t.type = toolType(t.name || '');
  return h;
}

/** "at least 240 x 95 x 19.4 mm hard maple. …, through cut Z-19.7" and variants. */
export function parseStock(text) {
  const s = { size: null, text: text.trim(), approx: /at least|>=|≥|raw|min(?:imum)?\b/i.test(text) };
  const m = /(\d+(?:\.\d+)?)\s*x\s*(\d+(?:\.\d+)?)(?:\s*x\s*(\d+(?:\.\d+)?))?/i.exec(text);
  if (m) {
    let z = m[3] !== undefined ? parseFloat(m[3]) : undefined;
    if (z === undefined) {
      const r = /(\d+(?:\.\d+)?)\s*-\s*(\d+(?:\.\d+)?)\s*(?:mm\s*)?thick/i.exec(text);
      const one = /(\d+(?:\.\d+)?)\s*(?:mm\s*)?thick/i.exec(text);
      if (r) { s.thicknessRange = [parseFloat(r[1]), parseFloat(r[2])]; z = s.thicknessRange[1]; s.approx = true; } else if (one) z = parseFloat(one[1]);
    }
    s.size = [parseFloat(m[1]), parseFloat(m[2]), z ?? null];
  }
  const f = new RegExp(`through\\s+cut\\s+Z\\s*${NUM}`, 'i').exec(text);
  if (f) s.floorZ = parseFloat(f[1]);
  if (/inch|\bin\b\.?\s*$/i.test(text) && !/mm/i.test(text)) s.units = 'in';
  return s;
}

/** Best guess of the stock origin from the ZERO line ("front-left-top" style), or undefined. */
export function zeroOrigin(text) {
  const t = text.toLowerCase();
  if (!/stock/.test(t)) return undefined; // zero on a jig or fixture: stock placement unknown
  const fb = /front/.test(t) ? 'front' : /back|rear/.test(t) ? 'back' : 'front';
  const lr = /left/.test(t) ? 'left' : /right/.test(t) ? 'right' : /center/.test(t) ? 'center' : 'left';
  const tb = /bottom|spoilboard/.test(t) && !/top/.test(t) ? 'bottom' : 'top';
  return `${fb}-${lr}-${tb}`;
}
