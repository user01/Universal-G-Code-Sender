// Entry point. Query parameters (all optional, for links and headless screenshots):
//   ?file=<url>&sidecar=<url>&profile=<url>   load by URL (relative to the page)
//   &view=top|front|iso|machine  &line=<n>  &wco=x,y,z  &section=<workZ>
// Live mode: /view/#vt=<token> (served by the plugin) or ?live=http://127.0.0.1:<port>#vt=<token>
// (dev, viewer served elsewhere); see src/data/sources.js and src/ui/live.js.
import { Viewer } from './scene/viewer.js';
import { App } from './ui/app.js';
import { loadDefaultProfile, normalizeProfile, BUILTIN_PROFILE } from './data/profile.js';
import { UrlSource, LiveSource } from './data/sources.js';
import { LiveController } from './ui/live.js';

const q = new URLSearchParams(location.search);
const viewer = new Viewer(document.getElementById('canvas-host'));

let live = null, liveError = null;
try { live = LiveSource.fromLocation(); } catch (e) { liveError = e; }
// live mode gets the profile from GET /profile; offline looks for the repo's copy
const profile = live ? normalizeProfile(BUILTIN_PROFILE, 'built-in') : await loadDefaultProfile();

const app = new App(viewer, profile);
window.ugsViewer = { app, viewer }; // for automation (screenshots, future MCP `view` snapshots)
if (liveError) app._error(liveError.message);
if (live) {
  const controller = new LiveController(app, live);
  window.ugsViewer.live = controller;
  controller.start();
}

// "Sample job" button when the repo's public test data is reachable (bridge/ served as root)
const sampleUrl = '../testdata-public/sample-sign.nc';
if (!live) fetch(new URL(sampleUrl, document.baseURI), { method: 'HEAD' }).then((r) => {
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
