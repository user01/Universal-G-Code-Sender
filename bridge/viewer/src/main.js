// Entry point. Query parameters (all optional, for links and headless screenshots):
//   ?file=<url>&sidecar=<url>&profile=<url>   load by URL (relative to the page)
//   &view=top|front|iso|machine  &line=<n>  &wco=x,y,z  &section=<workZ>
// Live mode (#vt=<token>, served by the plugin) is a stub in V1; see src/data/sources.js.
import { Viewer } from './scene/viewer.js';
import { App } from './ui/app.js';
import { loadDefaultProfile, normalizeProfile } from './data/profile.js';
import { UrlSource, LiveSource } from './data/sources.js';

const q = new URLSearchParams(location.search);
const viewer = new Viewer(document.getElementById('canvas-host'));

let profile = await loadDefaultProfile();
const live = LiveSource.fromLocation();
if (live) {
  document.getElementById('mode').textContent = 'live (V1: profile only)';
  const p = await live.profile().catch(() => null);
  if (p) profile = normalizeProfile(p, 'GET /profile');
}

const app = new App(viewer, profile);
window.ugsViewer = { app, viewer }; // for automation (screenshots, future MCP `view` snapshots)

// "Sample job" button when the repo's public test data is reachable (bridge/ served as root)
const sampleUrl = '../testdata-public/sample-sign.nc';
fetch(new URL(sampleUrl, document.baseURI), { method: 'HEAD' }).then((r) => {
  if (!r.ok) return;
  const b = document.getElementById('load-sample');
  b.hidden = false;
  b.addEventListener('click', () => app.openSource(new UrlSource({ file: sampleUrl })));
}).catch(() => {});

if (q.get('file')) {
  const wco = q.get('wco')?.split(',').map(Number);
  const src = new UrlSource({ file: q.get('file'), sidecar: q.get('sidecar'), profile: q.get('profile') });
  try {
    const { gcode, sidecar, profile: p } = await src.load();
    if (p) { app.profile = normalizeProfile(p, q.get('profile')); viewer.setProfile(app.profile); }
    app.loadJob(gcode, sidecar, { wco: wco?.length === 3 && wco.every(Number.isFinite) ? wco : undefined });
    if (q.get('section')) {
      document.getElementById('section-on').checked = true;
      document.getElementById('section-z').value = q.get('section');
      app._onSection();
    }
    if (q.get('line')) app.scrubTo(Number(q.get('line')));
    if (q.get('view')) viewer.setView(q.get('view'));
  } catch (e) {
    console.error(e);
    app._error(e.message);
  }
}
document.body.dataset.ready = '1';
