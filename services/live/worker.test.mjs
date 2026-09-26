// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync, readdirSync, mkdtempSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {execFileSync} from 'node:child_process';
import worker, {distanceMetres} from './worker.mjs';
import {stationBrand, logoImageHosts} from '../../packages/brands/resolve.mjs';

const temp = mkdtempSync(join(tmpdir(), 'openfuel-test-'));
let seed;
try {
  execFileSync('python3', [new URL('../../tools/import_live_data.py', import.meta.url).pathname, '--sql', join(temp, 'seed.sql')]);
  seed = readFileSync(join(temp, 'seed.sql'), 'utf8');
} finally { rmSync(temp, {recursive: true, force: true}); }

const migrations = readdirSync(new URL('./migrations/', import.meta.url)).filter(name => name.endsWith('.sql')).sort();
function database() {
  const sql = new DatabaseSync(':memory:');
  sql.exec('PRAGMA foreign_keys = ON');
  for (const migration of migrations) sql.exec(readFileSync(new URL('./migrations/' + migration, import.meta.url), 'utf8'));
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
test('brand aliases return curated external URLs and reject lookalike names', () => {
  for (const alias of ['PetroCanada', 'Petro-Canada', 'Petro Canada', 'Petro-Pass']) {
    assert.equal(stationBrand({name: alias}).brandKey, 'petro-canada');
  }
  assert.equal(stationBrand({name: 'Calgary Co-op Gas Bar'}).brandKey, 'coop');
  assert.equal(stationBrand({brand: 'Esso', name: 'Fuel stop'}).brandKey, 'esso');
  assert.equal(stationBrand({name: 'Shellfish Market', brandLogoUrl: 'https://example.test/x.png'}).brandLogoUrl, null);
  assert.equal(stationBrand({name: 'Cooper Service'}).brandKey, null);
  assert.ok(nearby.stations.some(s => s.brandKey === 'shell'));
  for (const station of nearby.stations) {
    if (!station.brandKey) { assert.equal(station.brandLogoUrl, null); continue; }
    const url = new URL(station.brandLogoUrl);
    assert.equal(url.protocol, 'https:');
    assert.ok(logoImageHosts.includes(url.hostname));
    assert.equal(url.search, '');
    assert.equal(url.username, '');
    assert.match(station.brandLogoSourceUrl, /^https:\/\//);
  }
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

test('reviewed Tempo identities are served across the directory without moving stations or fabricating prices', async () => {
  const {correctedStation} = await import('../../packages/data/corrections.mjs');
  const corrections = JSON.parse(readFileSync(new URL('../../packages/data/station-corrections.json', import.meta.url), 'utf8'));
  const snapshot = readFileSync(new URL('../../packages/data/canada-stations.jsonl', import.meta.url), 'utf8').trim().split('\n').map(JSON.parse);
  for (const correction of corrections) {
    const original = snapshot.find(s => s.id === correction.station_id);
    assert.ok(original);
    const updated = correctedStation(original);
    assert.equal(updated.brand, 'Tempo');
    assert.equal(updated.id, original.id);
    assert.equal(updated.latitude, original.latitude);
    assert.equal(updated.longitude, original.longitude);
    assert.equal(stationBrand(updated).brandKey, 'tempo');
    assert.equal(correctedStation({...original, name: 'New upstream name'}).name, 'New upstream name');
    assert.equal(correctedStation({...original, latitude: original.latitude + .01}).name, original.name);
  }
  const body = await (await worker.fetch(get('/api/v1/stations?lat=51.047&lon=-114.143&radius=10000'), database())).json();
  const bowTrail = body.stations.find(s => s.id === 'osm-node-266330515');
  assert.equal(bowTrail.name, 'Tempo, Bow Trail');
  assert.equal(bowTrail.prices.regular, null);
  assert.match(bowTrail.correction_source_url, /^https:\/\/www.tempo.crs\//);
});

test('regions and city search never read D1; health only asks D1 to answer', async () => {
  const queries = [];
  const env = {...database(), DB: {prepare: query => { queries.push(query); return {all: async () => ({results: [{ok: 1}], meta: {rows_read: 0}})}; }, batch: () => { throw new Error('D1 must not be read'); }}};
  assert.equal((await (await worker.fetch(get('/api/v1/regions'), env)).json()).regions[0].station_count, 12543);
  const cities = (await (await worker.fetch(get('/api/v1/geocode?q=edmonton'), env)).json()).results;
  assert.match(cities[0].name, /^Edmonton,/);
  assert.deepEqual(queries, []);
  assert.equal((await (await worker.fetch(get('/api/v1/health'), env)).json()).station_count, 12543);
  assert.deepEqual(queries, ['SELECT 1 AS ok']);
});
test('stations, prices and city searches are served from the area cache on repeat requests', async () => {
  const store = new Map();
  globalThis.caches = {default: {
    match: async key => store.get(key.url)?.clone(),
    put: async (key, response) => { store.set(key.url, response.clone()); },
    delete: async key => store.delete(key.url),
  }};
  try {
    const env = database(); let reads = 0;
    const prepare = env.DB.prepare, batch = env.DB.batch;
    env.DB.prepare = query => { if (/^SELECT/.test(query)) reads++; return prepare(query); };
    env.DB.batch = queries => batch(queries);
    const first = await (await worker.fetch(get(), env)).json();
    const afterFirst = reads;
    assert.ok(afterFirst > 0);
    const second = await (await worker.fetch(get(), env)).json();
    assert.equal(reads, afterFirst);
    assert.deepEqual(second.stations.map(s => s.id), first.stations.map(s => s.id));
    // A report bumps its area's price version, so the next read misses the old cached prices.
    assert.equal((await worker.fetch(post(), env)).status, 201);
    const updated = await (await worker.fetch(get(), env)).json();
    assert.equal(updated.stations.find(s => s.id === stationId).prices.regular, 1399);
    await worker.fetch(get('/api/v1/geocode?q=edmonton'), env);
    const beforeRepeat = reads;
    await worker.fetch(get('/api/v1/geocode?q=edmonton'), env);
    assert.equal(reads, beforeRepeat);
  } finally { delete globalThis.caches; }
});
test('stations responses may be reused briefly by the same browser', async () => {
  const response = await worker.fetch(get(), database());
  assert.equal(response.headers.get('cache-control'), 'private, max-age=15');
});
test('a price reported through another isolate reaches readers within the version check interval', async () => {
  const store = new Map();
  globalThis.caches = {default: {
    match: async key => store.get(key.url)?.clone(),
    put: async (key, response) => { store.set(key.url, response.clone()); },
    delete: async key => store.delete(key.url),
  }};
  const realNow = Date.now;
  try {
    const env = database();
    assert.equal((await (await worker.fetch(get(), env)).json()).stations.find(s => s.id === stationId).prices.regular, null);
    // Written straight to D1, as another isolate's report would be; this isolate's memo still holds version 0.
    await env.DB.prepare('INSERT INTO price_reports VALUES (?1, ?2, ?3, ?4, ?5, ?6)')
      .bind('other-isolate-report', stationId, 'regular', 1459, payload.client_id, new Date().toISOString()).run();
    assert.equal((await (await worker.fetch(get(), env)).json()).stations.find(s => s.id === stationId).prices.regular, null);
    Date.now = () => realNow() + 16_000;
    assert.equal((await (await worker.fetch(get(), env)).json()).stations.find(s => s.id === stationId).prices.regular, 1459);
  } finally { Date.now = realNow; delete globalThis.caches; }
});

function cacheStub() {
  const store = new Map();
  globalThis.caches = {default: {
    match: async key => store.get(key.url)?.clone(),
    put: async (key, response) => { store.set(key.url, response.clone()); },
    delete: async key => store.delete(key.url),
  }};
  return () => { delete globalThis.caches; };
}
const priceOf = async (env, id = stationId, path = nearbyPath) =>
  (await (await worker.fetch(get(path), env)).json()).stations.find(s => s.id === id)?.prices.regular;

test('a report handled while a search waits on D1 neither breaks the search nor hides the new price', async () => {
  const restore = cacheStub();
  try {
    const env = database(), batch = env.DB.batch;
    let hold = null;
    env.DB.batch = async queries => { if (hold) await hold; return batch(queries); };
    // Cache one area near the station, then search the wider area while D1 is held.
    await worker.fetch(get('/api/v1/stations?lat=53.55&lon=-113.45&radius=1000'), env);
    let release; hold = new Promise(resolve => { release = resolve; });
    const pending = worker.fetch(get(), env);
    await new Promise(resolve => setTimeout(resolve, 10));
    const reportDone = worker.fetch(post(), env);
    await new Promise(resolve => setTimeout(resolve, 10));
    hold = null; release();
    assert.equal((await reportDone).status, 201);
    assert.equal((await pending).status, 200);
    assert.equal(await priceOf(env), 1399);
  } finally { restore(); }
});
test('changing or removing a price in D1 by hand reaches readers after the version check', async () => {
  const restore = cacheStub(), realNow = Date.now;
  try {
    const env = database();
    assert.equal((await worker.fetch(post(), env)).status, 201);
    assert.equal(await priceOf(env), 1399);
    await env.DB.prepare('UPDATE current_prices SET price_milli=?1 WHERE station_id=?2').bind(1455, stationId).run();
    Date.now = () => realNow() + 16_000;
    assert.equal(await priceOf(env), 1455);
    await env.DB.prepare('DELETE FROM current_prices WHERE station_id=?1').bind(stationId).run();
    Date.now = () => realNow() + 32_000;
    assert.equal(await priceOf(env), null);
  } finally { Date.now = realNow; restore(); }
});
test('far-north 50 km searches stay within Workers limits and still answer', async () => {
  for (const lat of [70, 80, 86.9, 89.9]) {
    const env = database(), prepare = env.DB.prepare;
    let most = 0;
    env.DB.prepare = query => { most = Math.max(most, (query.match(/\?\d+/g) || []).length); return prepare(query); };
    const response = await worker.fetch(get(`/api/v1/stations?lat=${lat}&lon=-100&radius=50000`), env);
    assert.equal(response.status, 200, `lat ${lat}`);
    assert.ok(most <= 20, `lat ${lat}: ${most} bound parameters`);
  }
});
