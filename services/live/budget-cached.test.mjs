// SPDX-License-Identifier: AGPL-3.0-only
// Own process: the budget is module state.
import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.mjs';

const at = lat => new Request(`https://openfuel.test/api/v1/stations?lat=${lat}&lon=-113.4938&radius=10000`);
test('areas already cached keep answering after the daily cap is reached', async () => {
  const store = new Map();
  globalThis.caches = {default: {
    match: async key => store.get(key.url)?.clone(),
    put: async (key, response) => { store.set(key.url, response.clone()); },
    delete: async key => store.delete(key.url),
  }};
  const statement = query => {
    const bound = values => ({
      bind: (...next) => bound(next),
      all: async () => ({results: [], meta: {rows_read: 100, rows_written: 0}}),
      first: async () => /usage_budget/.test(query) ? {rows_read: values[1], rows_written: values[2]} : null,
    });
    return bound([]);
  };
  const env = {DB: {prepare: statement, batch: async queries => Promise.all(queries.map(q => q.all()))}, D1_DAILY_READ_BUDGET: '500'};
  const realNow = Date.now;
  try {
    assert.equal((await worker.fetch(at(53.5), env)).status, 200);
    assert.equal((await worker.fetch(at(54.5), env)).status, 200);
    assert.equal((await worker.fetch(at(55.5), env)).status, 503, 'a new area needs D1 and the budget is spent');
    // Past the version check interval, the cached areas still answer instead of failing.
    Date.now = () => realNow() + 20_000;
    assert.equal((await worker.fetch(at(53.5), env)).status, 200);
    assert.equal((await worker.fetch(at(54.5), env)).status, 200);
  } finally { Date.now = realNow; delete globalThis.caches; }
});
