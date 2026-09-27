// SPDX-License-Identifier: AGPL-3.0-only
// Own process: the budget is module state.
import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.mjs';
import {assets} from './test-assets.mjs';

const nearby = new Request('https://openfuel.test/api/v1/stations?lat=53.5461&lon=-113.4938&radius=10000');
const report = () => new Request('https://openfuel.test/api/v1/reports', {method: 'POST', headers: {'content-type': 'application/json'},
  body: JSON.stringify({station_id: 'osm-node-1', fuel_type: 'regular', price_milli: 1399, client_id: 'test-install-12345678'})});
test("D1's write limit pauses reports but not browsing", async () => {
  const limit = "D1_ERROR: Your account has exceeded D1's free tier daily row write limit. Upgrade to a paid plan or wait until tomorrow (midnight UTC) to continue.";
  const statement = query => {
    const bound = () => ({bind: () => bound(), query, all: async () => ({results: [], meta: {rows_read: 1, rows_written: 0}})});
    return bound();
  };
  // D1 rolls back a batch whose insert hits the write limit and reports the error.
  const batch = async queries => {
    if (queries.some(q => /^INSERT/.test(q.query))) throw new Error(limit);
    return Promise.all(queries.map(q => q.all()));
  };
  const env = {ASSETS: assets(), DB: {prepare: statement, batch}};
  const refused = await worker.fetch(report(), env);
  assert.equal(refused.status, 503);
  const body = await refused.json();
  assert.equal(body.error, 'spending_cap');
  assert.equal(body.scope, 'reports');
  assert.equal((await worker.fetch(nearby, env)).status, 200);
  assert.equal((await worker.fetch(report(), env)).status, 503);
  const health = await (await worker.fetch(new Request('https://openfuel.test/api/v1/health'), env)).json();
  assert.equal(health.writesEnabled, false);
  // Cloudflare's docs say either daily limit stops all queries: a read refused this way pauses browsing too.
  const refusing = {...env, DB: {prepare: statement, batch: async () => { throw new Error(limit); }}};
  const browsing = await worker.fetch(new Request('https://openfuel.test/api/v1/stations?lat=51.0447&lon=-114.0719&radius=10000'), refusing);
  assert.equal(browsing.status, 503);
  assert.equal((await browsing.json()).scope, 'all');
});
