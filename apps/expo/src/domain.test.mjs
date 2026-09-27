import assert from 'node:assert/strict';
import test from 'node:test';
import { areaSnapshot, safeLogoUrl, parseCents, priceLabel, reportAge, sortedStations, validateResponse, withOwnReports } from './domain.ts';

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

test('remote logo requests are confined to curated HTTPS providers', () => {
  assert.equal(safeLogoUrl('https://www.fuel.crs/logo.png'), 'https://www.fuel.crs/logo.png');
  for (const url of ['http://www.fuel.crs/logo.png', 'https://www.fuel.crs.evil.test/logo.png',
    'https://user@www.fuel.crs/logo.png', 'https://www.fuel.crs:444/logo.png', 'file:///logo.png', null]) {
    assert.equal(safeLogoUrl(url), null);
  }
});

test('warm snapshots discard precise query coordinates and device-relative distances', () => {
  const point = {latitude: 53.546129, longitude: -113.493876};
  const data = {mode: 'live', is_demo: false, location: point, stations: [station('one', null, 17)]};
  const cached = areaSnapshot(data, point, 'Near your location', '2026-09-12T12:00:00Z');
  assert.deepEqual(cached.point, {latitude: 53.55, longitude: -113.49});
  assert.equal(cached.label, 'Nearby area');
  assert.equal(cached.data.location, undefined);
  assert.notEqual(cached.data.stations[0].distanceMetres, 17);
  assert.equal(data.stations[0].distanceMetres, 17);
  assert.equal(validateResponse(cached.data).stations[0].prices.regular, null);
});

test('own reports stay over older station answers for 30 seconds only', () => {
  const confirmedAt = Date.parse('2026-09-27T12:00:05Z');
  const report = { station_id: 'osm-node-1', fuel_type: 'regular', price_milli: 1499, observed_at: '2026-09-27T12:00:00.000Z', confirmedAt };
  const answer = (price, observedAt) => [
    { ...station('osm-node-1', price, 50), observedAt: observedAt ? { regular: observedAt } : {}, priceSources: observedAt ? { regular: 'community-unverified' } : {} },
    { ...station('osm-node-2', 1389, 90), observedAt: { regular: '2026-09-27T11:00:00Z' }, priceSources: { regular: 'community-unverified' } },
  ];
  const unpriced = answer(null);
  const shown = withOwnReports(unpriced, [report], confirmedAt + 29_999);
  assert.equal(shown[0].prices.regular, 1499);
  assert.equal(shown[0].observedAt.regular, report.observed_at);
  assert.equal(shown[0].priceSources.regular, 'community-unverified');
  assert.equal(shown[0].prices.premium, null);
  assert.equal(shown[1], unpriced[1]);
  assert.equal(unpriced[0].prices.regular, null);
  // An older price, or one without a readable time, gives way to the report.
  assert.equal(withOwnReports(answer(1459, '2026-09-27T11:00:00Z'), [report], confirmedAt)[0].prices.regular, 1499);
  assert.equal(withOwnReports(answer(1459), [report], confirmedAt)[0].prices.regular, 1499);
  // The same or a later observation, such as someone else's newer report, is shown as it is.
  const newer = answer(1479, '2026-09-27T12:00:03Z');
  assert.equal(withOwnReports(newer, [report], confirmedAt)[0], newer[0]);
  const same = answer(1499, report.observed_at);
  assert.equal(withOwnReports(same, [report], confirmedAt)[0], same[0]);
  // Once 30 seconds have passed (or the clock went back), the answer is shown as it is.
  const stale = answer(1459, '2026-09-27T11:00:00Z');
  assert.equal(withOwnReports(stale, [report], confirmedAt + 30_000), stale);
  assert.equal(withOwnReports(stale, [report], confirmedAt - 1), stale);
});

test('several own reports apply by observation time, also to saved snapshots', () => {
  const confirmedAt = Date.parse('2026-09-27T12:00:10Z');
  const first = { station_id: 'osm-node-1', fuel_type: 'regular', price_milli: 1499, observed_at: '2026-09-27T12:00:00Z', confirmedAt };
  const second = { ...first, price_milli: 1489, observed_at: '2026-09-27T12:00:08Z' };
  const diesel = { ...first, fuel_type: 'diesel', price_milli: 1699 };
  const [shown] = withOwnReports([station('osm-node-1', null, 50)], [second, first, diesel], confirmedAt);
  assert.equal(shown.prices.regular, 1489);
  assert.equal(shown.prices.diesel, 1699);
  const snapshot = areaSnapshot({ mode: 'live', is_demo: false, stations: [station('osm-node-1', null, 50)] }, { latitude: 53, longitude: -113 }, 'Edmonton', '2026-09-27T11:59:00Z');
  assert.equal(withOwnReports(validateResponse(snapshot.data).stations, [first], confirmedAt)[0].prices.regular, 1499);
});
