// SPDX-License-Identifier: AGPL-3.0-only
// The D1 budget is module state, so these run in their own process (node --test runs files separately).
import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.mjs';
import {assets} from './test-assets.mjs';

const nearby = new Request('https://openfuel.test/api/v1/stations?lat=53.5461&lon=-113.4938&radius=10000');
test('the D1 limit error becomes a spending_cap answer until midnight UTC', async () => {
  const env = {ASSETS: assets(), DB: {prepare: () => { throw new Error("D1_ERROR: Your account has exceeded D1's free tier daily row read limit. Upgrade to a paid plan or wait until tomorrow (midnight UTC) to continue."); }}};
  const response = await worker.fetch(nearby, env);
  assert.equal(response.status, 503);
  const body = await response.json();
  assert.equal(body.error, 'spending_cap');
  assert.equal(body.reason, 'd1_free_daily_read_limit');
  assert.ok(Date.parse(body.resets_at) > Date.now());
  assert.ok(Number(response.headers.get('retry-after')) >= 60);
  // Later requests in this isolate answer without touching D1.
  const again = await worker.fetch(nearby, {ASSETS: assets(), DB: {prepare: () => { throw new Error('D1 must not be read'); }}});
  assert.equal((await again.json()).error, 'spending_cap');
});
