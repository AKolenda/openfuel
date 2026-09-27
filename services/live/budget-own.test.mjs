// SPDX-License-Identifier: AGPL-3.0-only
// Separate process from budget.test.mjs, which leaves the isolate capped.
import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.mjs';
import {assets} from './test-assets.mjs';

test('the Worker stops at its own daily D1 row budget before Cloudflare does', async () => {
  const learned = [];
  const statement = query => {
    const bound = values => ({
      bind: (...next) => bound(next),
      all: async () => {
        if (/FROM usage_budget/.test(query)) { learned.push(values); return {results: [], meta: {rows_read: 1, rows_written: 0}}; }
        return {results: [], meta: {rows_read: 100, rows_written: 0}};
      },
    });
    return bound([]);
  };
  const env = {ASSETS: assets(), DB: {prepare: statement, batch: async queries => Promise.all(queries.map(q => q.all()))}, D1_DAILY_READ_BUDGET: '200',
    OPENFUEL_DONATE_URL: 'https://ko-fi.com/openfuel'};
  const at = (lat, lon) => new Request(`https://openfuel.test/api/v1/stations?lat=${lat}&lon=${lon}&radius=10000`);
  // Edmonton, Calgary and Red Deer: each search asks D1 for its own areas' prices (100 rows here, 101 with the first total).
  assert.equal((await worker.fetch(at(53.5461, -113.4938), env)).status, 200);
  assert.equal(learned.length, 1, 'the first D1 call also reads the shared daily total');
  assert.equal((await worker.fetch(at(51.0447, -114.0719), env)).status, 200);
  assert.equal(learned.length, 1);
  const capped = await worker.fetch(at(52.2681, -113.8112), env);
  assert.equal(capped.status, 503);
  const body = await capped.json();
  assert.equal(body.error, 'spending_cap');
  assert.equal(body.reason, 'daily_database_budget');
  assert.equal(body.donate_url, 'https://ko-fi.com/openfuel');
});
