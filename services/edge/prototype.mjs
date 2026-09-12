// SPDX-License-Identifier: AGPL-3.0-only
// Shared sample playground. No live prices, credentials, or private evidence are served.
const grades = new Set(['regular', 'premium', 'diesel']);
const headers = {
  'content-type': 'application/json; charset=utf-8',
  'cache-control': 'no-store',
  'access-control-allow-origin': '*',
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'no-referrer',
};
const json = (body, status = 200, extra = {}) => new Response(JSON.stringify(body), {status, headers: {...headers, ...extra}});
class InputError extends Error {
  constructor(code, message, status = 400) { super(message); this.code = code; this.status = status; }
}
async function readReport(request) {
  if (!/^application\/json(?:;|$)/i.test(request.headers.get('content-type') || '')) {
    throw new InputError('invalid_content_type', 'Send a JSON price report.', 415);
  }
  const reader = request.body?.getReader();
  if (!reader) throw new InputError('invalid_report', 'A price report is required.');
  const chunks = []; let size = 0;
  for (;;) {
    const {value, done} = await reader.read(); if (done) break;
    size += value.byteLength;
    if (size > 2048) { await reader.cancel(); throw new InputError('report_too_large', 'Keep the report under 2 KB.', 413); }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  let body;
  try { body = JSON.parse(new TextDecoder().decode(bytes)); }
  catch { throw new InputError('invalid_json', 'The price report is not valid JSON.'); }
  if (!body || Array.isArray(body) || typeof body !== 'object' ||
      Object.keys(body).some(k => !['station_id', 'fuel_type', 'price_milli', 'client_id', 'request_id'].includes(k)) ||
      typeof body.station_id !== 'string' || !/^[a-z0-9-]{1,64}$/.test(body.station_id) ||
      !grades.has(body.fuel_type) || !Number.isInteger(body.price_milli) || body.price_milli < 500 || body.price_milli > 3999 ||
      typeof body.client_id !== 'string' || !/^[a-zA-Z0-9_-]{16,80}$/.test(body.client_id) ||
      (body.request_id !== undefined && (typeof body.request_id !== 'string' || !/^[a-zA-Z0-9_-]{16,80}$/.test(body.request_id)))) {
    throw new InputError('invalid_report', 'Choose a station, a fuel grade, and a price from 50.0 to 399.9 cents/L.');
  }
  return body;
}
async function stations(db, query) {
  const params = [...query.keys()];
  if (new Set(params).size !== params.length || params.some(k => !['fuel', 'region', 'limit'].includes(k)) ||
      (query.has('fuel') && !grades.has(query.get('fuel'))) ||
      (query.has('region') && query.get('region') !== 'demo-region') ||
      (query.has('limit') && !/^(?:[1-9]|[1-9][0-9]|100)$/.test(query.get('limit')))) {
    throw new InputError('invalid_query', 'This prototype currently covers the sample region.');
  }
  const [stationRows, priceRows] = await db.batch([
    db.prepare('SELECT id, data FROM stations ORDER BY sort_order LIMIT 100'),
    db.prepare('SELECT station_id, fuel_type, price_milli, observed_at, source FROM current_prices'),
  ]);
  const now = Date.now();
  const values = stationRows.results.map(row => {
    const station = JSON.parse(row.data);
    station.ages = {}; station.priceSources = {}; station.observedAt = {};
    for (const price of priceRows.results.filter(p => p.station_id === row.id)) {
      station.prices[price.fuel_type] = price.price_milli;
      station.ages[price.fuel_type] = Math.max(0, Math.floor((now - Date.parse(price.observed_at)) / 60000));
      station.priceSources[price.fuel_type] = price.source;
      station.observedAt[price.fuel_type] = price.observed_at;
    }
    station.age = station.ages[query.get('fuel') || 'regular'] ?? station.age;
    station.memberDiscount ??= 0;
    station.synthetic = true;
    return station;
  });
  return {mode: 'prototype', is_demo: true, region: 'demo-region', generated_at: new Date(now).toISOString(),
    stations: values.slice(0, Number(query.get('limit') || 100)), next_cursor: null};
}
async function report(request, env) {
  const body = await readReport(request);
  // Edge limit is best effort per IP, while a SQL trigger enforces per-install/global limits atomically.
  if (env.REPORT_LIMITER) {
    const {success} = await env.REPORT_LIMITER.limit({key: request.headers.get('cf-connecting-ip') || body.client_id});
    if (!success) throw new InputError('rate_limited', 'Too many reports. Try again in a minute.', 429);
  }
  const existing = await env.DB.prepare('SELECT id FROM stations WHERE id = ?1').bind(body.station_id).first();
  if (!existing) throw new InputError('station_not_found', 'Choose one of the sample stations.', 404);
  const id = body.request_id || crypto.randomUUID();
  const findOld = () => env.DB.prepare('SELECT id, station_id, fuel_type, price_milli, client_id, observed_at FROM price_reports WHERE id = ?1').bind(id).first();
  const repeated = old => {
    if (old.client_id !== body.client_id || old.station_id !== body.station_id || old.fuel_type !== body.fuel_type || old.price_milli !== body.price_milli) {
      throw new InputError('report_conflict', 'Use a new request ID for a different report.', 409);
    }
    delete old.client_id;
    return json({ok: true, report: old, is_demo: true}, 200);
  };
  const old = await findOld();
  if (old) return repeated(old);
  const observed_at = new Date().toISOString();
  try {
    await env.DB.prepare('INSERT INTO price_reports (id, station_id, fuel_type, price_milli, client_id, observed_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)')
      .bind(id, body.station_id, body.fuel_type, body.price_milli, body.client_id, observed_at).run();
  } catch (error) {
    // A concurrent retry may have committed after the initial lookup.
    if (body.request_id) { const committed = await findOld(); if (committed) return repeated(committed); }
    throw error;
  }
  return json({ok: true, report: {id, station_id: body.station_id, fuel_type: body.fuel_type, price_milli: body.price_milli, observed_at}, is_demo: true}, 201);
}
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (!url.pathname.startsWith('/api/')) return env.ASSETS.fetch(request);
    if (request.method === 'OPTIONS') return new Response(null, {status: 204, headers: {...headers,
      'access-control-allow-methods': 'GET, HEAD, POST, OPTIONS', 'access-control-allow-headers': 'Content-Type, Accept'}});
    try {
      if (!env.DB) return json({error: 'not_configured', message: 'The prototype database is not configured.'}, 503);
      if (url.pathname === '/api/v1/reports' && request.method === 'POST') return await report(request, env);
      if (!['GET', 'HEAD'].includes(request.method)) return json({error: 'method_not_allowed'}, 405, {allow: url.pathname === '/api/v1/reports' ? 'POST, OPTIONS' : 'GET, HEAD, OPTIONS'});
      let body;
      if (url.pathname === '/api/v1/health') {
        const row = await env.DB.prepare('SELECT count(*) AS station_count FROM stations').first();
        body = {ok: true, service: 'openfuel-prototype', database: 'cloudflare-d1', databaseConfigured: true,
          writesEnabled: true, mode: 'prototype', is_demo: true, station_count: row.station_count};
      } else if (url.pathname === '/api/v1/stations') body = await stations(env.DB, url.searchParams);
      else if (url.pathname === '/api/v1/regions') body = {regions: [{id: 'demo-region', name: 'Sample town', is_demo: true, station_count: 6}]};
      else return json({error: 'not_found'}, 404);
      return request.method === 'HEAD' ? new Response(null, {headers}) : json(body);
    } catch (error) {
      if (error instanceof InputError) return json({error: error.code, message: error.message}, error.status, error.status === 429 ? {'retry-after': '60'} : {});
      if (String(error.message).includes('prototype_capacity_limit')) return json({error: 'prototype_full', message: 'This sample playground is full. Its maintainer needs to archive reports before it can accept more.'}, 429);
      if (String(error.message).includes('prototype_rate_limit')) return json({error: 'rate_limited', message: 'The prototype report limit was reached. Try again later.'}, 429, {'retry-after': '3600'});
      console.error(JSON.stringify({event: 'prototype_request_failed', path: url.pathname}));
      return json({error: 'temporarily_unavailable', message: 'Could not reach the shared database. Please try again.'}, 503);
    }
  },
};
