// Live integration: the fake bridge (fake_live.py) drives the viewer in headless Chrome.
//
//   cd bridge && PLAYWRIGHT=/path/to/node_modules/playwright-core/index.mjs \
//     node viewer/test/live/drive.mjs <out-dir>
//
// playwright-core is not a repo dependency: install it anywhere and point PLAYWRIGHT at it. It
// drives the installed Google Chrome (channel "chrome"). Everything binds 127.0.0.1 on ephemeral
// ports and is stopped at the end. Each step asserts on the viewer's live state
// (window.ugsViewer.live.state) and saves a screenshot to <out-dir>.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const { chromium } = await import(process.env.PLAYWRIGHT ?? 'playwright-core');
const here = path.dirname(fileURLToPath(import.meta.url));
const bridgeDir = path.resolve(here, '../../..');
const outDir = path.resolve(process.argv[2] ?? 'live-out');
fs.mkdirSync(outDir, { recursive: true });
const results = [];
const children = [];
process.on('exit', () => { for (const c of children) if (c.exitCode === null) c.kill(); });

function lines(proc) {
  const rl = readline.createInterface({ input: proc.stdout });
  const waiters = [];
  const buf = [];
  rl.on('line', (l) => {
    let m;
    try { m = JSON.parse(l); } catch { return; }
    const i = waiters.findIndex((w) => w.pred(m));
    if (i >= 0) waiters.splice(i, 1)[0].resolve(m); else buf.push(m);
  });
  return (pred, ms = 120000) => {
    const i = buf.findIndex(pred);
    if (i >= 0) return Promise.resolve(buf.splice(i, 1)[0]);
    return new Promise((resolve, reject) => {
      const w = { pred, resolve };
      waiters.push(w);
      setTimeout(() => { const k = waiters.indexOf(w); if (k >= 0) { waiters.splice(k, 1); reject(new Error('timeout waiting for bench output')); } }, ms);
    });
  };
}

// 1. a static server for the ?live= dev mode (the plugin-served /view/ is the main path)
const stat = spawn('python3', ['-u', '-m', 'http.server', '--bind', '127.0.0.1', '0', '--directory', path.join(bridgeDir, 'viewer')], { stdio: ['ignore', 'pipe', 'pipe'] });
children.push(stat);
const statPort = await new Promise((resolve, reject) => {
  const onData = (d) => { const m = /port (\d+)/.exec(String(d)); if (m) resolve(Number(m[1])); };
  stat.stdout.on('data', onData);
  stat.stderr.on('data', onData);
  setTimeout(() => reject(new Error('static server did not start')), 10000);
});

// 2. the bench
const bench = spawn('uv', ['run', 'python', 'viewer/test/live/fake_live.py', '--time-scale', '3', '--cors-origin', `http://127.0.0.1:${statPort}`], { cwd: bridgeDir, stdio: ['pipe', 'pipe', 'inherit'] });
children.push(bench);
const next = lines(bench);
const send = async (cmd, pred) => {
  bench.stdin.write(`${cmd}\n`);
  const name = cmd.split(' ')[0];
  return next(pred ?? ((m) => m.cmd === name));
};
const ready = await next((m) => m.ready);
console.log('bench', ready.port, ready.program);

const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
const errors = [];
const newPage = async (url) => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource|WebSocket connection/.test(m.text())) errors.push(m.text()); });
  await page.goto(url);
  return page;
};
const live = (page, fn, arg) => page.evaluate(fn, arg);
const waitLive = (page, fn, arg, timeout = 30000) => page.waitForFunction(fn, arg, { timeout, polling: 100 });
async function shot(page, name, note, check) {
  await page.waitForTimeout(250);
  await page.evaluate(() => window.ugsViewer.viewer.render());
  await page.screenshot({ path: path.join(outDir, `${name}.png`) });
  const st = await live(page, () => {
    const s = window.ugsViewer.live?.state;
    return s && {
      link: s.link, connected: s.connected, state: s.controller?.state, mpos: s.controller?.mpos, wco: s.wco, wcoSource: s.wcoSource,
      trail: s.trail.n, probes: s.probes.map((p) => p.mpos), stream: { ...s.stream }, banners: { ...s.banners }, authority: s.authority,
      banner: [...document.querySelectorAll('#banners .banner')].map((b) => b.textContent),
      appWco: window.ugsViewer.app.wco, scrub: window.ugsViewer.app.scrubLine,
    };
  });
  results.push({ name, note, state: st, check });
  console.log('shot', name, JSON.stringify(st?.banner ?? []));
}
const setView = (page, v, target) => page.evaluate(([vv, t]) => window.ugsViewer.viewer.setView(vv, t), [v, target]);
async function frame(page, center, size, dir = [0.9, -1.2, 1.0]) {
  // perspective camera looking at a machine-coordinate point
  await page.evaluate(([c, s, d]) => {
    const v = window.ugsViewer.viewer;
    v.setView('iso');
    const cam = v.camera;
    const n = Math.hypot(...d);
    cam.position.set(c[0] + (d[0] / n) * s, c[1] + (d[1] / n) * s, c[2] + (d[2] / n) * s);
    v.controls.target.set(...c);
    cam.lookAt(...c);
    v.controls.update();
    v.requestRender();
  }, [center, size, dir]);
}

try {
  // ---- plugin-served viewer, before any program is loaded
  const page = await newPage(`http://127.0.0.1:${ready.port}/view/#vt=${ready.vt}`);
  await waitLive(page, () => window.ugsViewer?.live?.state.link === 'online');
  await waitLive(page, () => window.ugsViewer.live.state.controller);
  await shot(page, 'live-01-noprogram', 'Online, no program loaded in UGS: the live tool at home', async () => {});
  assert.equal(await live(page, () => !!window.ugsViewer.app.program), false);

  // ---- operator homes, sets a rough zero, loads dr_ryan
  await send('setup');
  await waitLive(page, () => window.ugsViewer.app.program && window.ugsViewer.live.state.file);
  await waitLive(page, () => window.ugsViewer.app.wcoSource === 'live');
  const loaded = await live(page, () => ({ lines: window.ugsViewer.app.program.lineCount, ops: window.ugsViewer.app.job.ops.length, name: window.ugsViewer.app.gcode.name }));
  console.log('program', loaded);
  assert.ok(loaded.lines > 0);

  // ---- Claude probes Z with the BitZero: probe hit marker, origin record moves the work zero
  const wcoBefore = await live(page, () => window.ugsViewer.app.wco.slice());
  await send('probe');
  await waitLive(page, () => window.ugsViewer.live.state.probes.length >= 1 && window.ugsViewer.live.state.wcoSource === 'origin');
  await waitLive(page, (w) => Math.abs(window.ugsViewer.app.wco[2] - w[2]) > 0.1, wcoBefore);
  const probe = await live(page, () => window.ugsViewer.live.state.probes[0]);
  await frame(page, [probe.mpos[0], probe.mpos[1], probe.mpos[2] - 20], 170);
  await shot(page, 'live-02-probe', `BitZero probe: hit at MZ${probe.mpos[2]}, origin record moves the G54 axes (WCO Z ${wcoBefore[2]} → live)`);
  assert.equal(probe.ok, true);

  // ---- stream the end of OP 04 into the tool change (resume-style), watch progress and trail
  await setView(page, 'iso');
  const streamDone = send('stream 17560 17660', (m) => m.cmd === 'stream' || m.event === 'waiting');
  await waitLive(page, () => window.ugsViewer.live.state.stream.lineAcked > 17615 && window.ugsViewer.live.state.controller.state === 'Run', null, 60000);
  const mid = await live(page, () => window.ugsViewer.live.state.controller.mpos);
  const wco = await live(page, () => window.ugsViewer.app.wco);
  await frame(page, [wco[0] + 203, wco[1] + 46, wco[2] - 19], 105, [0.25, -0.6, 1]);
  await shot(page, 'live-03-streaming', 'Streaming OP 04 (lines 17560…): live tool, trail of the actual path, planned position at the acked line, progress on the scrubber');
  console.log('mid', mid);
  const waiting = await streamDone;
  assert.equal(waiting.event, 'waiting');
  await waitLive(page, () => /Tool change: load T21/.test(document.getElementById('banners').textContent));
  await frame(page, [-270, -405, -45], 330, [0.6, -1.3, 1.1]);
  await shot(page, 'live-04-toolchange', 'M6 at line 17646: the interceptor parked the machine; banner asks for T21');
  bench.stdin.write('confirm\n');
  await next((m) => m.cmd === 'stream');
  await waitLive(page, () => window.ugsViewer.live.state.tool.current === 'T21');
  await setView(page, 'top', 'machine');
  await shot(page, 'live-05-after-measure', 'After the BitSetter: second probe hit (BitSetter), WCO Z shifted for T21, back at the pre-change XY');
  await send('stream 17661 17760');
  await waitLive(page, () => window.ugsViewer.live.state.stream.lineAcked >= 17700);
  await setView(page, 'iso');
  await shot(page, 'live-06-op05', 'Streaming continues into OP 05 with T21; scrubber follows the stream');

  // ---- alarm
  await send('alarm');
  await waitLive(page, () => window.ugsViewer.live.state.banners.alarm);
  await shot(page, 'live-07-alarm', 'Soft-limit alarm (G53 G0 X100): banner with the GRBL text');
  await send('unlock');
  await waitLive(page, () => !window.ugsViewer.live.state.banners.alarm);

  // ---- operator locks Claude out: the viewer is unaffected, says so
  await send('lock');
  await waitLive(page, () => window.ugsViewer.live.state.authority.locked);
  assert.equal(await live(page, () => window.ugsViewer.live.state.link), 'online');
  await shot(page, 'live-08-mcp-locked', 'Operator lock for Claude: the read-only viewer stays online and shows the lock');
  await send('unlock-mcp');

  // ---- UGS loses the controller
  await send('disconnect');
  await waitLive(page, () => window.ugsViewer.live.state.connected === false);
  await shot(page, 'live-09-disconnected', 'UGS lost the controller');
  await send('reconnect');
  await waitLive(page, () => window.ugsViewer.live.state.connected === true);

  // ---- the bridge goes away (UGS quit), then comes back; while away it refuses viewers once
  await send('stop');
  await waitLive(page, () => window.ugsViewer.live.state.link === 'offline');
  await shot(page, 'live-10-offline', 'Bridge offline: backoff countdown');
  await send('refuse on');
  await send('start');
  await waitLive(page, () => window.ugsViewer.live.state.link === 'locked', null, 30000);
  await shot(page, 'live-11-refused', 'A bridge that refuses the viewer while locked (close 4423): locked state');
  const seqBefore = await live(page, () => window.ugsViewer.live.state.seq);
  await send('refuse off');
  await waitLive(page, () => window.ugsViewer.live.state.link === 'online', null, 30000);
  await send('jog -200 -300 -10');
  await waitLive(page, (s) => window.ugsViewer.live.state.seq > s && Math.abs(window.ugsViewer.live.state.controller.mpos[0] + 200) < 0.01, seqBefore);
  await shot(page, 'live-12-reconnected', 'Reconnected: events resume from the last seq; a jog moves the live tool');

  // ---- wrong token
  const bad = await newPage(`http://127.0.0.1:${ready.port}/view/#vt=wrong`);
  await waitLive(bad, () => window.ugsViewer?.live?.state.link === 'unauthorized');
  await shot(bad, 'live-13-unauthorized', 'A stale viewer token: unauthorized');
  await bad.close();

  // ---- dev mode: viewer served elsewhere, ?live=<bridge>
  const dev = await newPage(`http://127.0.0.1:${statPort}/index.html?live=http://127.0.0.1:${ready.port}#vt=${ready.vt}`);
  await waitLive(dev, () => window.ugsViewer?.live?.state.link === 'online' && window.ugsViewer.app.program);
  await shot(dev, 'live-14-devmode', 'Dev mode: ?live=http://127.0.0.1:<port>#vt=… from a separate static server (CORS)');
  await dev.close();

  const perf = await live(page, () => ({ ...window.ugsViewer.live.stats, trail: window.ugsViewer.live.state.trail.n, seq: window.ugsViewer.live.state.seq }));
  results.push({ name: 'stats', perf });
  console.log('stats', perf);
} finally {
  fs.writeFileSync(path.join(outDir, 'results.json'), JSON.stringify({ results, errors }, null, 1));
  await browser.close();
  bench.stdin.write('quit\n');
  await new Promise((r) => { bench.on('exit', r); setTimeout(r, 8000); });
  for (const c of children) if (c.exitCode === null) c.kill();
  console.log('errors', errors);
}
assert.deepEqual(errors, []);
console.log('live integration: ok');
