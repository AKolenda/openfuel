// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync, readdirSync, mkdtempSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {execFileSync} from 'node:child_process';
import worker, {distanceMetres, searchAreas} from './worker.mjs';
import {stationBrand, logoImageHosts} from '../../packages/brands/resolve.mjs';
import {assets, stationFiles} from './test-assets.mjs';

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
  return {DB: binding, ASSETS: assets()};
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
    env.DB.prepare = query => { if (/^(SELECT|WITH)/.test(query)) reads++; return prepare(query); };
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
    // The search's query runs at once, before the report, but its answer arrives only after the report.
    let gate = null;
    env.DB.batch = async queries => { const results = await batch(queries); if (gate) await gate; return results; };
    let release; gate = new Promise(resolve => { release = resolve; });
    const pending = worker.fetch(get(), env);
    await new Promise(resolve => setTimeout(resolve, 20));
    gate = null;
    assert.equal((await worker.fetch(post(), env)).status, 201);
    release();
    assert.equal((await pending).status, 200);
    assert.equal(await priceOf(env), 1399);
  } finally { restore(); }
});
test('a slow report answer does not replace a newer price this isolate has already seen', async () => {
  const realNow = Date.now;
  try {
    const env = database(), batch = env.DB.batch;
    await worker.fetch(get(), env);
    Date.now = () => realNow() + 16_000;
    // The report commits at once, but its answer is held.
    let hold, release;
    hold = new Promise(resolve => { release = resolve; });
    env.DB.batch = async queries => { const results = await batch(queries); if (hold) { const wait = hold; hold = null; await wait; } return results; };
    const slow = worker.fetch(post(), env);
    await new Promise(resolve => setTimeout(resolve, 20));
    // Another isolate's later report replaces the price, and this isolate's next check sees it.
    await env.DB.prepare('INSERT INTO price_reports VALUES (?1, ?2, ?3, ?4, ?5, ?6)')
      .bind('later-report', stationId, 'regular', 1459, 'another-install-1234', new Date(realNow() + 1000).toISOString()).run();
    assert.equal(await priceOf(env), 1459);
    release();
    assert.equal((await slow).status, 201);
    assert.equal(await priceOf(env), 1459);
  } finally { Date.now = realNow; }
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
test('searches cover every station within the radius, and at most 20 areas in the far north', async () => {
  const snapshot = readFileSync(new URL('../../packages/data/canada-stations.jsonl', import.meta.url), 'utf8').trim().split('\n').map(JSON.parse);
  const area = s => Math.floor((s.latitude + 90) * 2) * 1000 + Math.floor((s.longitude + 180) * 2);
  for (const [lat, lon, radius] of [[53.5461, -113.4938, 10000], [43.6532, -79.3832, 50000], [49.9, -97.1, 50000], [60.72, -135.05, 50000], [45.5, -73.6, 100]]) {
    const areas = new Set(searchAreas(lat, lon, radius));
    for (const station of snapshot) if (distanceMetres(lat, lon, station.latitude, station.longitude) <= radius) assert.ok(areas.has(area(station)), `${station.id} near ${lat}, ${lon}`);
  }
  for (const lat of [70, 80, 86.9, 89.9]) {
    const areas = searchAreas(lat, -100, 50000);
    assert.ok(areas.length <= 20, `lat ${lat}: ${areas.length} areas`);
    const env = database(), calls = spy(env);
    assert.equal((await worker.fetch(get(`/api/v1/stations?lat=${lat}&lon=-100&radius=50000`), env)).status, 200);
    assert.ok(calls.length <= 1);
  }
});

// Records every D1 round trip: a batch, or a statement run on its own. Each entry lists its queries.
function spy(env) {
  const calls = [], batch = env.DB.batch, prepare = env.DB.prepare;
  const tag = (statement, query) => ({query, inner: statement, bind: (...values) => tag(statement.bind(...values), query),
    ...Object.fromEntries(['all', 'first', 'run', 'raw'].filter(name => statement[name]).map(name => [name, (...args) => { calls.push([query]); return statement[name](...args); }]))});
  env.DB.prepare = query => tag(prepare(query), query);
  env.DB.batch = queries => { calls.push(queries.map(q => q.query)); return batch(queries.map(q => q.inner)); };
  return calls;
}
test('every station file matches the snapshot and the area formula', () => {
  const snapshot = readFileSync(new URL('../../packages/data/canada-stations.jsonl', import.meta.url), 'utf8').trim().split('\n').map(JSON.parse);
  const index = JSON.parse(stationFiles.get('index.json'));
  assert.equal(index.areas.length, stationFiles.size - 2);
  const seen = new Map();
  for (const area of index.areas) for (const record of JSON.parse(stationFiles.get(`${area}.json`))) {
    assert.equal(Math.floor((record.latitude + 90) * 2) * 1000 + Math.floor((record.longitude + 180) * 2), area);
    seen.set(record.id, record);
  }
  assert.equal(seen.size, snapshot.length);
  for (const record of snapshot) assert.deepEqual(seen.get(record.id), record);
  // ids.json lists the same stations, area by area, for the report check.
  const ids = JSON.parse(stationFiles.get('ids.json'));
  assert.deepEqual(Object.keys(ids).map(Number), index.areas);
  for (const area of index.areas) assert.deepEqual(ids[area].split(' '), JSON.parse(stationFiles.get(`${area}.json`)).map(record => record.id));
});
test('a stations request asks D1 once, for area prices only, and not again while they are fresh', async () => {
  const restore = cacheStub(), realNow = Date.now;
  try {
    const env = database(), calls = spy(env);
    await worker.fetch(get(), env);
    assert.equal(calls.length, 1, 'one D1 round trip');
    assert.ok(calls[0].every(query => !/FROM stations\b/.test(query)), 'station locations come from the static files');
    assert.match(calls[0][0], /FROM known LEFT JOIN area_prices/);
    await worker.fetch(get(), env);
    assert.equal(calls.length, 1, 'fresh prices come from the isolate');
    Date.now = () => realNow() + 16_000;
    await worker.fetch(get(), env);
    assert.equal(calls.length, 2, 'one query re-checks every area');
  } finally { Date.now = realNow; restore(); }
});
test('another isolate in the same data centre reuses a fresh check instead of asking D1', async () => {
  const restore = cacheStub(), realNow = Date.now;
  try {
    const first = database();
    await worker.fetch(get(), first);
    assert.equal((await worker.fetch(post(), first)).status, 201);
    // A second binding over the same database stands in for another isolate: its own memo, the same Cache API.
    const second = {...first, DB: {...first.DB}}, calls = spy(second);
    assert.equal(await priceOf(second), 1399);
    assert.equal(calls.length, 0);
    Date.now = () => realNow() + 16_000;
    assert.equal(await priceOf(second), 1399);
    assert.equal(calls.length, 1);
  } finally { Date.now = realNow; restore(); }
});
test('an unchanged area is re-checked without sending its prices again', async () => {
  const realNow = Date.now;
  try {
    const env = database();
    assert.equal((await worker.fetch(post(), env)).status, 201);
    const batch = env.DB.batch, answers = [];
    env.DB.batch = async queries => { const results = await batch(queries); answers.push(results[0].results); return results; };
    Date.now = () => realNow() + 16_000;
    assert.equal(await priceOf(env), 1399);
    assert.ok(answers[0].length > 0 && answers[0].every(row => row.prices === null));
  } finally { Date.now = realNow; }
});
test("a report is one D1 call, even as an isolate's first of the day, and its price shows at once", async () => {
  const fresh = (await import('./worker.mjs?first-report')).default, env = database(), calls = spy(env);
  assert.equal((await fresh.fetch(post(), env)).status, 201);
  assert.equal(calls.length, 1);
  assert.ok(calls[0].some(query => /FROM usage_budget/.test(query)), 'the first D1 call also reads the day\'s budget');
  const body = await (await fresh.fetch(get(), env)).json();
  assert.equal(body.stations.find(s => s.id === stationId).prices.regular, 1399);
  assert.equal(calls.length, 2, 'one call for the areas the report did not cover');
  assert.ok(calls[1].every(query => !/FROM stations\b/.test(query)));
});
test('a report for a station D1 still has but the deployed snapshot dropped is refused without asking D1', async () => {
  const fresh = (await import('./worker.mjs?dropped-station')).default, env = database(), dropped = 'osm-node-1';
  assert.ok(!Object.values(JSON.parse(stationFiles.get('ids.json'))).some(ids => ids.split(' ').includes(dropped)));
  // Seeding only adds and updates stations, so D1 keeps those that later snapshots no longer have.
  await env.DB.prepare("INSERT INTO stations VALUES(?1, 53.5461, -113.4938, '{}')").bind(dropped).run();
  const calls = spy(env);
  const response = await fresh.fetch(post({...payload, station_id: dropped}), env);
  assert.equal(response.status, 404);
  assert.equal((await response.json()).error, 'station_not_found');
  assert.equal(calls.length, 0);
  assert.equal((await env.DB.prepare('SELECT count(*) AS reports FROM price_reports').first()).reports, 0);
});
test('reports for snapshot stations, new and repeated, are still one D1 call each, and ids.json is read once', async () => {
  const fresh = (await import('./worker.mjs?snapshot-reports')).default, requests = [], env = {...database(), ASSETS: assets(requests)}, calls = spy(env);
  const value = {...payload, request_id: 'request-snapshot-123456'};
  const created = await fresh.fetch(post(value), env);
  assert.equal(created.status, 201);
  assert.equal(calls.length, 1);
  const repeated = await fresh.fetch(post(value), env);
  assert.equal(repeated.status, 200);
  assert.equal((await repeated.json()).report.id, (await created.json()).report.id);
  assert.equal(calls.length, 2);
  assert.equal((await fresh.fetch(post({...payload, price_milli: 1459}), env)).status, 201);
  assert.equal(calls.length, 3);
  assert.deepEqual(requests, ['/data/stations/ids.json']);
  assert.equal(await priceOf(env), 1459);
});
test('a failed or unfinished ids.json load does not block later reports', async () => {
  const fresh = (await import('./worker.mjs?ids-load')).default, files = assets();
  let load = 'hang';
  const env = {...database(), ASSETS: {fetch: request => {
    const ids = new URL(request.url).pathname.endsWith('/ids.json');
    // The first load never settles, as when Workers cancels its request's subrequests; the next one fails.
    if (ids && load === 'hang') return new Promise(() => {});
    if (ids && load === 'fail') return Promise.reject(new Error('unavailable'));
    return files.fetch(request);
  }}}, calls = spy(env);
  const answer = () => Promise.race([fresh.fetch(post(), env), new Promise(resolve => setTimeout(() => resolve('hung'), 2000))]);
  fresh.fetch(post(), env);
  await new Promise(resolve => setTimeout(resolve, 20));
  load = 'fail';
  const failed = await answer();
  assert.notEqual(failed, 'hung');
  assert.equal(failed.status, 503);
  load = 'ok';
  const response = await answer();
  assert.notEqual(response, 'hung');
  assert.equal(response.status, 201);
  assert.equal(calls.length, 1, 'only the report that could be checked reached D1');
});
test('an isolate at the daily cap answers from areas another isolate cached in the data centre', async () => {
  const restore = cacheStub(), realNow = Date.now;
  try {
    const first = (await import('./worker.mjs?fills-cache')).default;
    assert.equal((await first.fetch(post(), database())).status, 201);
    const env = database();
    assert.equal((await first.fetch(get(), env)).status, 200);
    Date.now = () => realNow() + 16_000;
    const capped = (await import('./worker.mjs?capped')).default;
    const refused = {...env, DB: {...env.DB, batch: () => { throw new Error('D1 must not be read'); }}, D1_DAILY_READ_BUDGET: '-1'};
    const response = await capped.fetch(get(), refused);
    assert.equal(response.status, 200);
    assert.equal((await response.json()).stations.find(s => s.id === stationId).prices.regular, 1399);
  } finally { Date.now = realNow; restore(); }
});
test('migration 0002 carries existing prices over, and a moved station leaves its old area', () => {
  const sql = new DatabaseSync(':memory:');
  sql.exec(readFileSync(new URL('./migrations/0001_live.sql', import.meta.url), 'utf8'));
  sql.exec("INSERT INTO stations VALUES('osm-node-1',53.5,-113.5,'{}'),('osm-node-2',53.6,-113.4,'{}'),('osm-node-3',45.5,-73.6,'{}')");
  const report = sql.prepare('INSERT INTO price_reports VALUES(?,?,?,?,?,?)');
  report.run('r1', 'osm-node-1', 'regular', 1399, 'client-123456789012', '2026-09-26T10:00:00Z');
  report.run('r2', 'osm-node-2', 'diesel', 1599, 'client-123456789012', '2026-09-26T10:01:00Z');
  report.run('r3', 'osm-node-3', 'premium', 1699, 'client-123456789012', '2026-09-26T10:02:00Z');
  sql.exec(readFileSync(new URL('./migrations/0002_area_cache_and_budget.sql', import.meta.url), 'utf8'));
  const areas = () => Object.fromEntries(sql.prepare('SELECT area, version, prices FROM area_prices').all().map(row => {
    assert.equal(row.version % 2, 1); return [row.area, JSON.parse(row.prices)];
  }));
  assert.deepEqual(areas(), {
    287133: {'osm-node-1:regular': ['osm-node-1', 'regular', 1399, '2026-09-26T10:00:00Z', 'community-unverified'], 'osm-node-2:diesel': ['osm-node-2', 'diesel', 1599, '2026-09-26T10:01:00Z', 'community-unverified']},
    271212: {'osm-node-3:premium': ['osm-node-3', 'premium', 1699, '2026-09-26T10:02:00Z', 'community-unverified']},
  });
  // A new snapshot moves osm-node-1; the seed's last statement moves its price to the new area.
  sql.exec("UPDATE stations SET latitude=45.6, longitude=-73.7 WHERE id='osm-node-1'");
  sql.exec(seed.trim().split('\n').at(-1));
  const moved = areas();
  assert.deepEqual(Object.keys(moved[287133]), ['osm-node-2:diesel']);
  assert.deepEqual(Object.keys(moved[271212]).sort(), ['osm-node-1:regular', 'osm-node-3:premium']);
});
test('a station file load left unfinished by an earlier request does not block later ones', async () => {
  const fresh = (await import('./worker.mjs?cancelled-load')).default, files = assets();
  let first = true;
  const env = {...database(), ASSETS: {fetch: request => {
    const path = new URL(request.url).pathname;
    // One file fails at once; the other never settles, as when Workers cancels its request's subrequests.
    if (first && path.endsWith('/287133.json')) return Promise.reject(new Error('unavailable'));
    if (first && path.endsWith('/287132.json')) return new Promise(() => {});
    return files.fetch(request);
  }}};
  assert.equal((await fresh.fetch(get(), env)).status, 503);
  first = false;
  const response = await Promise.race([fresh.fetch(get(), env), new Promise(resolve => setTimeout(() => resolve('hung'), 2000))]);
  assert.notEqual(response, 'hung');
  assert.equal(response.status, 200);
});
test('station files are kept per isolate and empty areas are never requested', async () => {
  const requests = [], env = {...database(), ASSETS: assets(requests)};
  await worker.fetch(get('/api/v1/stations?lat=58.2&lon=-122.6&radius=50000'), env);
  const index = JSON.parse(stationFiles.get('index.json')).areas;
  assert.ok(requests.every(path => path === '/data/stations/index.json' || index.includes(Number(path.match(/(\d+)\.json$/)[1]))));
  const before = requests.length;
  await worker.fetch(get('/api/v1/stations?lat=58.2&lon=-122.6&radius=50000'), env);
  assert.equal(requests.length, before);
});
test('a new isolate stays within 50 subrequests for any 50 km search across Canada', async () => {
  let worst = {count: 0};
  const shared = database();
  for (let lat = 42; lat <= 70; lat += 1.5) for (let lon = -140; lon <= -52; lon += 2.5) {
    // A fresh module instance stands in for a new isolate with nothing in memory.
    const fresh = (await import(`./worker.mjs?isolate=${lat},${lon}`)).default;
    let count = 0;
    const env = {DB: {...shared.DB}, ASSETS: assets()}, batch = env.DB.batch, files = env.ASSETS.fetch, prepare = env.DB.prepare;
    env.DB.batch = queries => { count++; return batch(queries); };
    env.DB.prepare = query => { const statement = prepare(query); return /usage_budget/.test(query) ? {...statement, bind: (...values) => { const bound = statement.bind(...values); return {...bound, first: () => { count++; return bound.first(); }}; }} : statement; };
    env.ASSETS.fetch = request => { count++; return files(request); };
    const store = new Map();
    globalThis.caches = {default: {
      match: async key => { count++; return store.get(key.url)?.clone(); },
      put: async (key, response) => { count++; store.set(key.url, response.clone()); },
    }};
    try {
      const response = await fresh.fetch(get(`/api/v1/stations?lat=${lat}&lon=${lon}&radius=50000`), env, {waitUntil: promise => promise});
      assert.equal(response.status, 200, `${lat}, ${lon}`);
      await new Promise(resolve => setTimeout(resolve, 0));
    } finally { delete globalThis.caches; }
    if (count > worst.count) worst = {count, lat, lon};
  }
  assert.ok(worst.count <= 50, `${worst.count} subrequests at ${worst.lat}, ${worst.lon}`);
});
