// SPDX-License-Identifier: AGPL-3.0-only
// Own process: the budget is module state.
import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.mjs';
import {assets} from './test-assets.mjs';

const at = (lat, lon) => new Request(`https://openfuel.test/api/v1/stations?lat=${lat}&lon=${lon}&radius=10000`);
test('areas already cached keep answering after the daily cap is reached', async () => {
  const store = new Map();
  globalThis.caches = {default: {
    match: async key => store.get(key.url)?.clone(),
    put: async (key, response) => { store.set(key.url, response.clone()); },
    delete: async key => store.delete(key.url),
  }};
  const statement = () => {
    const bound = () => ({bind: () => bound(), all: async () => ({results: [], meta: {rows_read: 100, rows_written: 0}})});
    return bound();
  };
  const env = {ASSETS: assets(), DB: {prepare: statement, batch: async queries => Promise.all(queries.map(q => q.all()))}, D1_DAILY_READ_BUDGET: '300'};
  const realNow = Date.now;
  try {
    // The first search also reads the shared total (200 rows here), the second 100.
    assert.equal((await worker.fetch(at(53.5461, -113.4938), env)).status, 200);
    assert.equal((await worker.fetch(at(51.0447, -114.0719), env)).status, 200);
    assert.equal((await worker.fetch(at(52.2681, -113.8112), env)).status, 503, 'a new area needs D1 and the budget is spent');
    // Past the version check interval, the cached areas still answer instead of failing.
    Date.now = () => realNow() + 20_000;
    assert.equal((await worker.fetch(at(53.5461, -113.4938), env)).status, 200);
    assert.equal((await worker.fetch(at(51.0447, -114.0719), env)).status, 200);
  } finally { Date.now = realNow; delete globalThis.caches; }
});
