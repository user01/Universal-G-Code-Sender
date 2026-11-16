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
  bitsetter: { xy: [-4.0, -449.0], verified: false },
};

/**
 * @typedef {object} MachineProfile
 * @property {string} name
 * @property {{min:number[],max:number[],verified:boolean}} envelope
 * @property {string} [homingCorner]
 * @property {number} [safeZ]
 * @property {number[]} [toolChangePark]
 * @property {{xy:number[],verified:boolean}} [bitsetter]
 * @property {number} [spoilboardZ]  profile `spoilboard_z_machine`, when measured
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
 * envelope's front-left corner, stock bottom on the spoilboard (`spoilboardZ`).
 * @param {MachineProfile} p
 * @param {{min:number[],max:number[]}|null} stockBox  work coords
 */
export function defaultWorkOffset(p, stockBox) {
  const { min } = p.envelope;
  const spoilZ = spoilboardZ(p);
  const sx = stockBox ? stockBox.min[0] : 0;
  const sy = stockBox ? stockBox.min[1] : 0;
  const sz = stockBox ? stockBox.min[2] : -10;
  return [min[0] + 20 - sx, min[1] + 20 - sy, spoilZ - sz];
}
