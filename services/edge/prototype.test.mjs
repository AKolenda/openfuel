// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import worker from './prototype.mjs';

function database() {
  const sql = new DatabaseSync(':memory:');
  sql.exec('PRAGMA foreign_keys = ON');
  for (const migration of ['0001_prototype.sql', '0002_samples.sql', '0003_report_ordering.sql']) sql.exec(readFileSync(new URL('./migrations/' + migration, import.meta.url), 'utf8'));
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
const get = (path = '/api/v1/stations') => new Request('https://openfuel.test' + path);
const payload = {station_id: 'parkside', fuel_type: 'regular', price_milli: 1399, client_id: 'test-install-12345678'};
const post = (value = payload) => new Request('https://openfuel.test/api/v1/reports', {method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify(value)});
test('health checks seeded database and sample mode', async () => {
  const response = await worker.fetch(get('/api/v1/health'), database());
  const body = await response.json();
  assert.equal(body.station_count, 6); assert.equal(body.writesEnabled, true); assert.equal(body.is_demo, true);
});
test('sample station contract preserves null diesel and known geography', async () => {
  const body = await (await worker.fetch(get(), database())).json();
  assert.equal(body.stations.length, 6); assert.equal(body.stations[0].prices.regular, 1429);
  assert.equal(body.stations.find(s => s.id === 'juniper').prices.diesel, null);
  assert.ok(body.stations.every(s => s.synthetic && Number.isInteger(s.memberDiscount) && s.ages.regular >= 0));
});
test('accepted report persists and appears on independent client reads', async () => {
  const env = database();
  const response = await worker.fetch(post(), env); assert.equal(response.status, 201);
  const report = (await response.json()).report;
  assert.match(report.id, /^[a-f0-9-]{36}$/); assert.equal(report.client_id, undefined);
  const body = await (await worker.fetch(get(), env)).json();
  assert.equal(body.stations[0].prices.regular, 1399); assert.equal(body.stations[0].priceSources.regular, 'community-prototype');
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
    .bind('old-report', 'parkside', 'regular', 1700, payload.client_id, '2020-01-01T00:00:00.000Z').run();
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
  assert.equal(await (await worker.fetch(new Request('https://openfuel.test/api/v1/stations', {method: 'HEAD'}), database())).text(), '');
  assert.equal(await (await worker.fetch(get('/'), database())).text(), 'site');
});
test('query filters reject unknown and duplicated parameters', async () => {
  for (const query of ['?region=edmonton', '?limit=1000', '?fuel=water', '?fuel=regular&fuel=diesel', '?sql=drop']) {
    assert.equal((await worker.fetch(get('/api/v1/stations' + query), database())).status, 400);
  }
});
