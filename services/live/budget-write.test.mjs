// SPDX-License-Identifier: AGPL-3.0-only
// Own process: the budget is module state.
import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.mjs';

const nearby = new Request('https://openfuel.test/api/v1/stations?lat=53.5461&lon=-113.4938&radius=10000');
const report = () => new Request('https://openfuel.test/api/v1/reports', {method: 'POST', headers: {'content-type': 'application/json'},
  body: JSON.stringify({station_id: 'osm-node-1', fuel_type: 'regular', price_milli: 1399, client_id: 'test-install-12345678'})});
test("D1's write limit pauses reports but not browsing", async () => {
  const statement = query => {
    const bound = () => ({
      bind: () => bound(),
      all: async () => ({results: [], meta: {rows_read: 1, rows_written: 0}}),
      first: async () => /FROM stations WHERE id/.test(query) ? {id: 'osm-node-1', latitude: 53.5, longitude: -113.5} : null,
      run: async () => { throw new Error("D1_ERROR: Your account has exceeded D1's free tier daily row write limit. Upgrade to a paid plan or wait until tomorrow (midnight UTC) to continue."); },
    });
    return bound();
  };
  const env = {DB: {prepare: statement, batch: async queries => Promise.all(queries.map(q => q.all()))}};
  const refused = await worker.fetch(report(), env);
  assert.equal(refused.status, 503);
  const body = await refused.json();
  assert.equal(body.error, 'spending_cap');
  assert.equal(body.scope, 'reports');
  assert.equal((await worker.fetch(nearby, env)).status, 200);
  assert.equal((await worker.fetch(report(), env)).status, 503);
  const health = await (await worker.fetch(new Request('https://openfuel.test/api/v1/health'), env)).json();
  assert.equal(health.writesEnabled, false);
});
