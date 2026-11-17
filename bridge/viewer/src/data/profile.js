// Machine profile (bridge/schema/profile.schema.json), normalized for the viewer.
//
// Lookup order: a profile file dropped by the user > GET /profile (live mode) >
// ../profiles/shapeoko4-standard.json (when bridge/ is the served root) > the built-in copy below.

/** Built-in copy of the key values of bridge/profiles/shapeoko4-standard.json (keep in sync). */
export const BUILTIN_PROFILE = {
  format: 'ugs-bridge-profile/1',
  name: 'Shapeoko 4 Standard (built-in copy)',
  envelope_mm: { min: [-445, -456, -95], max: [0, 0, 0], verified: false },
  homing_corner: 'back-right-top (machine 0,0,0)',
  safe_z_machine: -5.0,
  tool_change_park: [-222.0, -440.0, -5.0],
  bitsetter: { xy: [-4.2, -448.2], verified: false },
  bed: {
    verified: false,
    source: 'assumed (built-in copy of the profile bed section)',
    spoilboard: { min_xy: [-460, -432], max_xy: [15, 15], thickness: 19 },
    frame: {
      y_rails: { x: [-500, 55], y: [-505, 45], width: 40, height: 70 },
      x_extrusions: { y: [-490, 30], x: [-520, 75], depth: 30, height: 40 },
    },
    bitsetter_radius: 20,
  },
};

/**
 * Bed layout (profile `bed`), machine XY. Everything optional.
 * @typedef {object} Bed
 * @property {boolean} verified
 * @property {string} [source]
 * @property {{min:number[], max:number[], thickness:number}} [spoilboard]  min/max are [x, y]
 * @property {{x:number[], y:number[], width:number, height:number}} [yRails]
 * @property {{y:number[], x:number[], depth:number, height:number}} [xExtrusions]
 * @property {number} bitsetterRadius
 */

const pair = (v) => (Array.isArray(v) && v.length === 2 && v.every(Number.isFinite) ? v.slice() : null);

/** @returns {Bed|undefined} */
export function normalizeBed(raw) {
  if (!raw || typeof raw !== 'object') return undefined;
  const sbMin = pair(raw.spoilboard?.min_xy), sbMax = pair(raw.spoilboard?.max_xy);
  const yr = raw.frame?.y_rails, xe = raw.frame?.x_extrusions;
  return {
    verified: !!raw.verified,
    source: raw.source,
    spoilboard: sbMin && sbMax ? { min: sbMin, max: sbMax, thickness: Number(raw.spoilboard.thickness) || 19 } : undefined,
    yRails: yr && pair(yr.x) && pair(yr.y) ? { x: yr.x.slice(), y: yr.y.slice(), width: yr.width ?? 40, height: yr.height ?? 60 } : undefined,
    xExtrusions: xe && pair(xe.y) && pair(xe.x) ? { y: xe.y.slice(), x: xe.x.slice(), depth: xe.depth ?? 30, height: xe.height ?? 40 } : undefined,
    bitsetterRadius: Number(raw.bitsetter_radius) || 20,
  };
}

/**
 * @typedef {object} MachineProfile
 * @property {string} name
 * @property {{min:number[],max:number[],verified:boolean}} envelope
 * @property {string} [homingCorner]
 * @property {number} [safeZ]
 * @property {number[]} [toolChangePark]
 * @property {{xy:number[],verified:boolean}} [bitsetter]
 * @property {number} [spoilboardZ]  profile `spoilboard_z_machine`, when measured
 * @property {Bed} [bed]  bed and frame layout (profile `bed`)
 * @property {string} source
 */

/** @returns {MachineProfile} */
export function normalizeProfile(raw, source) {
  const env = raw.envelope_mm ?? raw.envelope;
  if (!env?.min || !env?.max) throw new Error('profile has no envelope_mm');
  return {
    name: raw.name ?? 'machine',
    envelope: { min: env.min.slice(), max: env.max.slice(), verified: !!env.verified },
    homingCorner: raw.homing_corner,
    safeZ: raw.safe_z_machine,
    toolChangePark: raw.tool_change_park,
    bitsetter: raw.bitsetter?.xy ? { xy: raw.bitsetter.xy.slice(), verified: !!raw.bitsetter.verified } : undefined,
    // machine Z of the spoilboard surface (reference tool); null/absent until measured
    spoilboardZ: typeof raw.spoilboard_z_machine === 'number' ? raw.spoilboard_z_machine : undefined,
    bed: normalizeBed(raw.bed),
    source,
  };
}

export function isProfile(json) {
  return json && typeof json === 'object' && (json.format === 'ugs-bridge-profile/1' || !!json.envelope_mm);
}

/** Try the repo's profile next to the viewer, else the built-in copy. */
export async function loadDefaultProfile(fetchImpl = fetch) {
  try {
    const url = new URL('../profiles/shapeoko4-standard.json', document.baseURI);
    const r = await fetchImpl(url, { cache: 'no-cache' });
    if (r.ok) return normalizeProfile(await r.json(), url.pathname);
  } catch { /* not served: fall through */ }
  return normalizeProfile(BUILTIN_PROFILE, 'built-in');
}

/** Machine Z of the spoilboard: the profile's `spoilboard_z_machine`, else assumed 10 mm above the lowest Z. */
export function spoilboardZ(p) {
  return p.spoilboardZ ?? p.envelope.min[2] + 10;
}

/**
 * Default offline work offset (WCO, machine = work + wco): stock front-left 20 mm in from the
 * front-left corner of the envelope (or of the spoilboard, whichever is further in), stock bottom on
 * the spoilboard (`spoilboardZ`).
 * @param {MachineProfile} p
 * @param {{min:number[],max:number[]}|null} stockBox  work coords
 */
export function defaultWorkOffset(p, stockBox) {
  const { min } = p.envelope;
  const sb = p.bed?.spoilboard;
  const x0 = Math.max(min[0], sb ? sb.min[0] : -Infinity), y0 = Math.max(min[1], sb ? sb.min[1] : -Infinity);
  const spoilZ = spoilboardZ(p);
  const sx = stockBox ? stockBox.min[0] : 0;
  const sy = stockBox ? stockBox.min[1] : 0;
  const sz = stockBox ? stockBox.min[2] : -10;
  return [x0 + 20 - sx, y0 + 20 - sy, spoilZ - sz];
}
