// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync, mkdtempSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {execFileSync} from 'node:child_process';
import worker, {distanceMetres} from './worker.mjs';

const temp = mkdtempSync(join(tmpdir(), 'openfuel-test-'));
let seed;
try {
  execFileSync('python3', [new URL('../../tools/import_live_data.py', import.meta.url).pathname, '--sql', join(temp, 'seed.sql')]);
  seed = readFileSync(join(temp, 'seed.sql'), 'utf8');
} finally { rmSync(temp, {recursive: true, force: true}); }

function database() {
  const sql = new DatabaseSync(':memory:');
  sql.exec('PRAGMA foreign_keys = ON');
  sql.exec(readFileSync(new URL('./migrations/0001_live.sql', import.meta.url), 'utf8'));
  sql.exec(seed);
  const binding = {
    prepare(query) {
      const statement = sql.prepare(query);
      const prepared = args => ({bind: (...values) => prepared(values),
        first: async () => statement.get(...args) || null,
        all: async () => ({results: statement.all(...args)}),
        run: async () => statement.run(...args)});
      return prepared([]);
    },
    batch: queries => Promise.all(queries.map(query => query.all())),
  };
  return {DB: binding, ASSETS: {fetch: async () => new Response('site')}};
}
const nearbyPath = '/api/v1/stations?lat=53.5461&lon=-113.4938&radius=10000';
const get = (path = nearbyPath) => new Request('https://openfuel.test' + path);
const payload = {station_id: 'pending', fuel_type: 'regular', price_milli: 1399, client_id: 'test-install-12345678'};
const post = (value = payload) => new Request('https://openfuel.test/api/v1/reports', {method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify(value)});
const firstEnv = database();
const nearby = await (await worker.fetch(get(), firstEnv)).json();
const stationId = nearby.stations[0].id;
payload.station_id = stationId;
test('health checks imported database and live mode', async () => {
  const body = await (await worker.fetch(get('/api/v1/health'), firstEnv)).json();
  assert.equal(body.station_count, 12543); assert.equal(body.writesEnabled, true); assert.equal(body.is_demo, false);
});
test('real station geography is nearby, ordered and contains no invented prices', async () => {
  assert.ok(nearby.stations.length > 20);
  assert.ok(nearby.stations.every((s, i, all) => !s.synthetic && s.prices.regular === null && s.prices.premium === null && s.prices.diesel === null && s.distanceMetres <= 10000 && (!i || s.distanceMetres >= all[i-1].distanceMetres)));
  assert.match(nearby.stations[0].source_url, /^https:\/\/www.openstreetmap.org\//);
  assert.equal(nearby.market_reference.kind, 'monthly_average');
  assert.match(nearby.market_reference.city, /Edmonton/);
  assert.equal(nearby.coverage.pump_prices_seeded, 0);
});
test('location is required, never silently defaults to a fictional location', async () => {
  const response = await worker.fetch(get('/api/v1/stations'), firstEnv);
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error, 'location_required');
});
test('Canadian city search is accent-insensitive and handles SQL wildcards literally', async () => {
  const body = await (await worker.fetch(get('/api/v1/geocode?q=montreal'), firstEnv)).json();
  assert.ok(body.results.some(r => r.name.startsWith('Montréal,')));
  for (const q of ['%25%25', '%27%20OR%201%3D1', '__']) {
    const result = await (await worker.fetch(get('/api/v1/geocode?q=' + q), firstEnv)).json();
    assert.deepEqual(result.results, []);
  }
});
test('distance and empty coverage behave sensibly', async () => {
  assert.equal(distanceMetres(0,0,0,0), 0);
  assert.ok(Math.abs(distanceMetres(0,0,0,1)-111195) < 2);
  const body = await (await worker.fetch(get('/api/v1/stations?lat=0&lon=0'), firstEnv)).json();
  assert.deepEqual(body.stations, []);
});
test('accepted report persists and appears on independent client reads', async () => {
  const env = database();
  const response = await worker.fetch(post(), env); assert.equal(response.status, 201);
  const report = (await response.json()).report;
  assert.match(report.id, /^[a-f0-9-]{36}$/); assert.equal(report.client_id, undefined);
  const body = await (await worker.fetch(get(), env)).json();
  assert.equal(body.stations[0].prices.regular, 1399); assert.equal(body.stations[0].priceSources.regular, 'community-unverified');
  assert.ok(!JSON.stringify(body).includes(payload.client_id));
});
test('retry with request ID is idempotent; changed payload conflicts', async () => {
  const env = database(), value = {...payload, request_id: 'request-1234567890'};
  assert.equal((await worker.fetch(post(value), env)).status, 201);
  assert.equal((await worker.fetch(post(value), env)).status, 200);
  assert.equal((await worker.fetch(post({...value, price_milli: 1400}), env)).status, 409);
});
test('concurrent identical retries return the same persisted report', async () => {
  const env = database(), value = {...payload, request_id: 'request-concurrent-123456'};
  const responses = await Promise.all([worker.fetch(post(value), env), worker.fetch(post(value), env)]);
  assert.deepEqual(responses.map(r => r.status).sort(), [200, 201]);
  assert.equal((await responses[0].json()).report.id, (await responses[1].json()).report.id);
});
test('an earlier observed report cannot overwrite a newer price', async () => {
  const env = database();
  assert.equal((await worker.fetch(post(), env)).status, 201);
  await env.DB.prepare('INSERT INTO price_reports VALUES (?1, ?2, ?3, ?4, ?5, ?6)')
    .bind('old-report', stationId, 'regular', 1700, payload.client_id, '2020-01-01T00:00:00.000Z').run();
  assert.equal((await (await worker.fetch(get(), env)).json()).stations[0].prices.regular, 1399);
});
for (const [key, value] of [['station_id', "parkside' OR 1=1"], ['fuel_type', 'water'], ['price_milli', 499], ['price_milli', 4000], ['price_milli', 12.3], ['price_milli', '1429'], ['client_id', ''], ['unexpected', 'value']]) {
  test(`validates report ${key}=${value}`, async () => assert.equal((await worker.fetch(post({...payload, [key]: value}), database())).status, 400));
}
test('unknown station does not create a report', async () => assert.equal((await worker.fetch(post({...payload, station_id: 'missing'}), database())).status, 404));
test('body size is bounded even without content-length', async () => assert.equal((await worker.fetch(post({...payload, client_id: 'x'.repeat(3000)}), database())).status, 413));
test('per-install SQL limit prevents further price mutations', async () => {
  const env = database();
  for (let i = 0; i < 30; i++) assert.equal((await worker.fetch(post(), env)).status, 201);
  assert.equal((await worker.fetch(post({...payload, price_milli: 1700}), env)).status, 429);
  assert.equal((await (await worker.fetch(get(), env)).json()).stations[0].prices.regular, 1399);
});
test('edge rate limit rejects reports', async () => assert.equal((await worker.fetch(post(), {...database(), REPORT_LIMITER: {limit: async () => ({success: false})}})).status, 429));
test('CORS preflight permits native/browser report contract', async () => {
  const response = await worker.fetch(new Request('https://openfuel.test/api/v1/reports', {method: 'OPTIONS'}), database());
  assert.equal(response.status, 204); assert.match(response.headers.get('access-control-allow-methods'), /POST/);
});
test('HEAD has no body; assets bypass database', async () => {
  assert.equal(await (await worker.fetch(new Request('https://openfuel.test' + nearbyPath, {method: 'HEAD'}), database())).text(), '');
  assert.equal(await (await worker.fetch(get('/'), database())).text(), 'site');
});
test('query filters reject unknown and duplicated parameters', async () => {
  for (const query of ['?region=edmonton', '?limit=1000', '?fuel=water', '?fuel=regular&fuel=diesel', '?sql=drop', '?radius=1', '?radius=50001', '?radius=200&radius=300', '?lat=54', '?radius=']) {
    assert.equal((await worker.fetch(get(nearbyPath + query.replace('?', '&')), database())).status, 400);
  }
});
