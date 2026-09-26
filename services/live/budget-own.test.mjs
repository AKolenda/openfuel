// SPDX-License-Identifier: AGPL-3.0-only
// Separate process from budget.test.mjs, which leaves the isolate capped.
import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.mjs';

test('the Worker stops at its own daily D1 row budget before Cloudflare does', async () => {
  const flushes = [];
  const statement = query => {
    const bound = values => ({
      bind: (...next) => bound(next),
      all: async () => ({results: [], meta: {rows_read: 100, rows_written: 0}}),
      first: async () => {
        if (/usage_budget/.test(query)) { flushes.push(values); return {rows_read: values[1], rows_written: values[2]}; }
        return null;
      },
    });
    return bound([]);
  };
  const env = {DB: {prepare: statement, batch: async queries => Promise.all(queries.map(q => q.all()))}, D1_DAILY_READ_BUDGET: '500',
    OPENFUEL_DONATE_URL: 'https://ko-fi.com/openfuel'};
  const at = lat => new Request(`https://openfuel.test/api/v1/stations?lat=${lat}&lon=-113.4938&radius=10000`);
  assert.equal((await worker.fetch(at(53.5), env)).status, 200);
  // One search runs three metered statements (two area rows and prices): 300 rows.
  assert.equal(flushes.length, 1, 'the first metered request records usage');
  assert.equal((await worker.fetch(at(54.5), env)).status, 200);
  const capped = await worker.fetch(at(55.5), env);
  assert.equal(capped.status, 503);
  const body = await capped.json();
  assert.equal(body.error, 'spending_cap');
  assert.equal(body.reason, 'daily_database_budget');
  assert.equal(body.donate_url, 'https://ko-fi.com/openfuel');
});
