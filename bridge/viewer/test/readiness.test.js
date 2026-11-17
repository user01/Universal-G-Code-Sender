import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readinessView, readinessHtml } from '../src/twin/readiness.js';
import { createLiveState, applyRecord } from '../src/live/state.js';
import { LiveConnection, HttpError } from '../src/live/connection.js';

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const REPORT = {
  state: 'warnings',
  warnings: 2,
  infos: 1,
  items: [
    { id: 'not_homed', severity: 'warn', message: 'Not homed since connecting.', fix: 'Home the machine ($H) in UGS.' },
    { id: 'stock_not_confirmed', severity: 'warn', message: "You haven't confirmed where this file's stock is.", fix: 'Press Confirm stock.' },
    { id: 'controller_travel_differs', severity: 'info', message: 'Max travel differs.', fix: 'Nothing to do.' },
  ],
};

test('banner lists the warnings (not the infos) and offers Confirm for a placement warning', () => {
  const v = readinessView(REPORT);
  assert.equal(v.title, 'Twin: 2 warnings: what you see may not match the machine');
  assert.deepEqual(v.items.map((i) => i.id), ['not_homed', 'stock_not_confirmed']);
  assert.equal(v.confirm, true);
  const html = readinessHtml(v, esc);
  assert.match(html, /class="banner twin"/);
  assert.match(html, /<b>not_homed<\/b>: Not homed since connecting\. <span class="fix">Fix: Home the machine \(\$H\) in UGS\.<\/span>/);
  assert.match(html, /data-action="confirm-stock"/);
  assert.match(html, /You haven&#39;t|You haven't/);
  assert.doesNotMatch(readinessHtml(v, esc, true), /data-action="confirm-stock">/); // disabled while busy
  assert.match(readinessHtml(v, esc, true), /disabled>/);
});

test('no banner when managed, missing or infos only; no Confirm for other warnings', () => {
  assert.equal(readinessView(null), null);
  assert.equal(readinessView({ state: 'managed', items: [] }), null);
  assert.equal(readinessView({ items: [REPORT.items[2]] }), null);
  assert.equal(readinessHtml(null, esc), '');
  assert.equal(readinessView({ items: [REPORT.items[0]] }).confirm, false);
  assert.equal(readinessView({ items: [{ id: 'g54_changed_outside', severity: 'warn', message: 'm', fix: 'f' }] }).confirm, true);
  assert.equal(readinessView({ items: [REPORT.items[0]] }).title, 'Twin: 1 warning: what you see may not match the machine');
});

test('readiness event records update the live state', () => {
  const s = createLiveState();
  const ch = applyRecord(s, { seq: 1, t: '', kind: 'event', src: 'bridge', data: { type: 'readiness', ...REPORT } });
  assert.ok(ch.has('readiness'));
  assert.equal(s.readiness.items.length, 3);
  applyRecord(s, { seq: 2, t: '', kind: 'event', src: 'bridge', data: { type: 'readiness', state: 'managed', items: [] } });
  assert.equal(readinessView(s.readiness), null);
});

test('confirm POSTs with the viewer token and surfaces a refusal', async () => {
  const calls = [];
  const fetchImpl = async (url, opts) => {
    calls.push({ url, opts });
    if (calls.length === 1) return { ok: true, status: 200, json: async () => ({ confirmed: true, readiness: { state: 'managed', items: [] } }) };
    return { ok: false, status: 409, json: async () => ({ error: 'not_ready', message: 'no file loaded' }) };
  };
  const conn = new LiveConnection({ base: 'http://127.0.0.1:8765/api/v1', token: 'abc', onRecord: () => {}, fetchImpl, pageUrl: 'http://127.0.0.1:8765/view/' });
  const r = await conn.post('/twin/confirm', {});
  assert.equal(r.confirmed, true);
  assert.equal(calls[0].url, 'http://127.0.0.1:8765/api/v1/twin/confirm');
  assert.equal(calls[0].opts.method, 'POST');
  assert.equal(calls[0].opts.headers.Authorization, 'Bearer v.abc');
  await assert.rejects(conn.post('/twin/confirm', {}), (e) => e instanceof HttpError && e.status === 409 && e.body.message === 'no file loaded');
});
