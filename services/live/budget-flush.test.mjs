// SPDX-License-Identifier: AGPL-3.0-only
// Own process: the budget is module state.
import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.mjs';
import {assets} from './test-assets.mjs';

test('usage reaches the shared daily total: reports at once, reads after two minutes, and a failed flush keeps its counts', async () => {
  const flushes = [];
  let failFlush = false;
  const statement = query => {
    const bound = values => ({
      bind: (...next) => bound(next),
      all: async () => {
        if (/FROM usage_budget/.test(query)) return {results: [], meta: {rows_read: 1, rows_written: 0}};
        if (/^INSERT INTO price_reports/.test(query)) return {results: [], meta: {rows_read: 3, rows_written: 7}};
        if (/FROM stations LEFT JOIN area_prices/.test(query)) return {results: [{area: 287133, version: 5, prices: '{}', seen: 1}], meta: {rows_read: 2, rows_written: 0}};
        return {results: [], meta: {rows_read: 10, rows_written: 0}};
      },
      first: async () => {
        if (!/INSERT INTO usage_budget/.test(query)) return null;
        if (failFlush) throw new Error('D1 is unavailable');
        flushes.push(values.slice(1));
        return {rows_read: 1000, rows_written: 50};
      },
    });
    return bound([]);
  };
  const env = {ASSETS: assets(), DB: {prepare: statement, batch: async queries => Promise.all(queries.map(q => q.all()))}};
  const search = (lat, lon) => worker.fetch(new Request(`https://openfuel.test/api/v1/stations?lat=${lat}&lon=${lon}&radius=10000`), env);
  const report = () => worker.fetch(new Request('https://openfuel.test/api/v1/reports', {method: 'POST', headers: {'content-type': 'application/json'},
    body: JSON.stringify({station_id: 'osm-node-1', fuel_type: 'regular', price_milli: 1399, client_id: 'test-install-12345678'})}), env);
  const realNow = Date.now, start = realNow();
  let offset = 0;
  Date.now = () => start + offset;
  try {
    assert.equal((await search(53.5461, -113.4938)).status, 200);
    assert.equal(flushes.length, 0, 'reads are added later');
    offset = 121_000;
    assert.equal((await search(51.0447, -114.0719)).status, 200);
    assert.equal(flushes.length, 1, "a new isolate adds its reads after two minutes");
    assert.ok(flushes[0][0] > 2);
    assert.equal(flushes[0][1], 1, 'nothing written yet, plus the flush itself');
    offset = 130_000;
    assert.equal((await report()).status, 201);
    assert.equal(flushes.length, 2, 'rows written by a report are added straight after it');
    assert.equal(flushes[1][1], 8, "the report's 7 rows plus the flush's own");
    offset = 140_000; failFlush = true;
    assert.equal((await report()).status, 201);
    assert.equal(flushes.length, 2);
    offset = 150_000; failFlush = false;
    assert.equal((await report()).status, 201);
    assert.equal(flushes.length, 3);
    assert.equal(flushes[2][1], 15, 'the failed flush kept its count for the next one');
  } finally { Date.now = realNow; }
});
