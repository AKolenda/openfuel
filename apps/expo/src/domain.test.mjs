import assert from 'node:assert/strict';
import test from 'node:test';
import { parseCents, priceLabel, reportAge, sortedStations, validateResponse } from './domain.ts';

const station = (id, price, distanceMetres) => ({ id, name: 'Test station', latitude: 53, longitude: -113,
  distanceMetres, prices: { regular: price, premium: null, diesel: null }, synthetic: false });

test('Canadian pump display converts cents to integer milli without dollar ambiguity', () => {
  assert.equal(parseCents('149.9'), 1499);
  assert.equal(parseCents('149,9'), 1499);
  assert.equal(priceLabel(1499), '149.9');
  for (const bad of ['1.499', '149.99', '49.9', '400', '149x9', '', 'Infinity']) assert.equal(parseCents(bad), null);
});

test('stations without prices remain visible after sorting', () => {
  const stations = [station('unknown', null, 100), station('known', 1499, 400), station('unknown-near', null, 50)];
  assert.deepEqual(sortedStations(stations, 'regular', 'price').map(s => s.id), ['known', 'unknown-near', 'unknown']);
  assert.deepEqual(sortedStations(stations, 'regular', 'distance').map(s => s.id), ['unknown-near', 'unknown', 'known']);
  assert.equal(priceLabel(null), '—');
  assert.equal(stations[0].id, 'unknown');
});

test('sample responses and invalid geography cannot silently appear as live', () => {
  const response = { mode: 'live', is_demo: false, stations: [station('osm-node-1', null, 50)] };
  assert.equal(validateResponse(response), response);
  assert.throws(() => validateResponse({ ...response, is_demo: true }));
  assert.throws(() => validateResponse({ ...response, stations: [{ ...response.stations[0], synthetic: true }] }));
  assert.throws(() => validateResponse({ ...response, stations: [{ ...response.stations[0], latitude: 190 }] }));
  assert.throws(() => validateResponse({ ...response, stations: [{ ...response.stations[0], prices: { regular: '1499' } }] }));
});

test('unknown report timestamps are not described as fresh', () => {
  assert.equal(reportAge(undefined), 'Time unavailable');
  assert.equal(reportAge('invalid'), 'Time unavailable');
  assert.equal(reportAge('2026-09-10T12:00:00Z', Date.parse('2026-09-11T12:00:00Z')), '1 day ago');
});
