// SPDX-License-Identifier: AGPL-3.0-only
// Own process: the budget is module state.
import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.mjs';

test('health reports writes as closed once the write budget is spent, before any report is refused', async () => {
  const statement = query => {
    const bound = values => ({
      bind: (...next) => bound(next),
      all: async () => ({results: [], meta: {rows_read: 1, rows_written: 0}}),
      first: async () => /usage_budget/.test(query) ? {rows_read: 1, rows_written: 5000} : null,
    });
    return bound([]);
  };
  // Another isolate already wrote past this budget; a stations request's flush learns the total.
  const env = {DB: {prepare: statement, batch: async queries => Promise.all(queries.map(q => q.all()))}, D1_DAILY_WRITE_BUDGET: '100'};
  assert.equal((await worker.fetch(new Request('https://openfuel.test/api/v1/stations?lat=45.5&lon=-73.6&radius=10000'), env)).status, 200);
  const health = await (await worker.fetch(new Request('https://openfuel.test/api/v1/health'), env)).json();
  assert.equal(health.writesEnabled, false);
});
