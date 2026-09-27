// SPDX-License-Identifier: AGPL-3.0-only
// Real Canadian station discovery with public, unverified community pump reports.
import {correctedStation} from '../../packages/data/corrections.mjs';
import {stationBrand} from '../../packages/brands/resolve.mjs';
import metadata from '../../packages/data/metadata.json' with {type: 'json'};
import marketAverages from '../../packages/data/market-averages.json' with {type: 'json'};
import cities from '../../packages/data/canada-cities.json' with {type: 'json'};
const grades = new Set(['regular', 'premium', 'diesel']);
const headers = {
  'content-type': 'application/json; charset=utf-8',
  'cache-control': 'no-store',
  'access-control-allow-origin': '*',
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'strict-origin-when-cross-origin',
};
const json = (body, status = 200, extra = {}) => new Response(JSON.stringify(body), {status, headers: {...headers, ...extra}});
class InputError extends Error {
  constructor(code, message, status = 400) { super(message); this.code = code; this.status = status; }
}

// Daily D1 row budget, kept below the Workers Free limits (5M rows read, 100k written per UTC day) so
// OpenFuel stops with a clear answer before Cloudflare does, and never runs up a bill on a paid plan.
// Usage is counted per isolate and added to a one-row-per-day table now and then, so it is approximate.
// Reads and writes are capped separately: a spent write budget pauses reports, not browsing.
const budget = {day: '', learned: false, pendingRead: 0, pendingWritten: 0, read: 0, written: 0, flushedAt: 0, flushing: false, readCappedUntil: 0, writeCappedUntil: 0};
const utcDay = now => new Date(now).toISOString().slice(0, 10);
const nextUtcMidnight = now => Date.UTC(new Date(now).getUTCFullYear(), new Date(now).getUTCMonth(), new Date(now).getUTCDate() + 1);
class SpendingCap extends Error {
  constructor(reason, until) { super(reason); this.reason = reason; this.until = until; }
}
function limits(env) {
  return {read: Number(env.D1_DAILY_READ_BUDGET) || 4_000_000, written: Number(env.D1_DAILY_WRITE_BUDGET) || 80_000};
}
function rollDay(now) {
  const day = utcDay(now);
  // A new isolate or day reads the shared total along with its first D1 query (see learnBudget) and
  // adds its own reads two minutes later, then every ten minutes (see flushBudget).
  if (budget.day !== day) Object.assign(budget, {day, learned: false, pendingRead: 0, pendingWritten: 0, read: 0, written: 0, flushedAt: now - 480_000});
}
/** The statement that reads today's shared total, for an isolate that has not read it yet; add it to a batch. */
function budgetStatement(env) {
  return budget.learned ? null : env.DB.prepare('SELECT rows_read, rows_written FROM usage_budget WHERE day = ?1').bind(budget.day);
}
function learnBudget(day, row) {
  if (budget.day !== day) return;
  budget.learned = true;
  if (row) { budget.read = Math.max(budget.read, row.rows_read); budget.written = Math.max(budget.written, row.rows_written); }
}
function checkBudget(env, kind = 'read', now = Date.now()) {
  rollDay(now);
  const cap = limits(env);
  if (budget.readCappedUntil > now) throw new SpendingCap('daily_database_limit', budget.readCappedUntil);
  if (budget.read + budget.pendingRead >= cap.read) {
    budget.readCappedUntil = nextUtcMidnight(now);
    throw new SpendingCap('daily_database_budget', budget.readCappedUntil);
  }
  if (kind !== 'write') return;
  if (budget.writeCappedUntil > now) throw new SpendingCap('daily_report_limit', budget.writeCappedUntil);
  if (budget.written + budget.pendingWritten >= cap.written) {
    budget.writeCappedUntil = nextUtcMidnight(now);
    throw new SpendingCap('daily_report_budget', budget.writeCappedUntil);
  }
}
/** Whether a report would be accepted now, by the same test checkBudget(env, 'write') applies. */
function writesOpen(env, now = Date.now()) {
  rollDay(now);
  return !(budget.writeCappedUntil > now) && budget.written + budget.pendingWritten < limits(env).written;
}
function meter(results) {
  for (const result of [results].flat()) {
    budget.pendingRead += Number(result?.meta?.rows_read) || 0;
    budget.pendingWritten += Number(result?.meta?.rows_written) || 0;
  }
  return results;
}
async function flushBudget(env, now = Date.now()) {
  if (budget.flushing || (!budget.pendingRead && !budget.pendingWritten)) return;
  // Rows written (by reports) are added straight after the request, so the write budget holds even
  // across short-lived isolates; reads every ten minutes or 25,000 rows. Retries wait five seconds.
  const writes = budget.pendingWritten && now - budget.flushedAt >= 5_000;
  if (!writes && budget.pendingRead < 25_000 && now - budget.flushedAt < 600_000) return;
  const day = budget.day, read = budget.pendingRead, written = budget.pendingWritten;
  budget.flushing = true; budget.pendingRead = 0; budget.pendingWritten = 0; budget.flushedAt = now;
  try {
    // The flush itself reads and writes about one row each, counted in its own values.
    const total = await env.DB.prepare('INSERT INTO usage_budget(day,rows_read,rows_written) VALUES(?1,?2,?3) ON CONFLICT(day) DO UPDATE SET rows_read=rows_read+excluded.rows_read, rows_written=rows_written+excluded.rows_written RETURNING rows_read, rows_written')
      .bind(day, read + 2, written + 1).first();
    // A flush that finishes after midnight UTC belongs to the previous day's row.
    if (total && budget.day === day) { budget.read = total.rows_read; budget.written = total.rows_written; budget.learned = true; }
  } catch { if (budget.day === day) { budget.pendingRead += read; budget.pendingWritten += written; } }
  finally { budget.flushing = false; }
}
const d1LimitPattern = /exceeded D1's free tier daily row (read|write) limit/i;

// Areas are 0.5 degree squares, computed exactly as in migration 0002 and tools/import_live_data.py.
const areaRow = lat => Math.floor((lat + 90) * 2), areaColumn = lon => Math.floor((lon + 180) * 2);
const areaId = (row, column) => row * 1000 + column;
// At most this many areas per search, which keeps static file reads and D1 parameters within Workers
// Free limits. It only narrows 50 km searches in the far north, where it trims the east-west edges.
const MAX_AREAS = 20;
// Workers Free allows 50 subrequests per request, and Cache API calls share that quota. Static file
// reads through the assets binding are counted too, to be safe. This leaves room for the D1 call, the
// budget flush and a margin; station files come first and the data centre's price cache gets the rest.
const SUBREQUESTS = 44;
// Isolate copies are capped so a long-lived isolate that served much of Canada stays well within memory.
const MEMO_LIMIT = 300;
function remember(map, key, value) {
  map.delete(key); map.set(key, value);
  if (map.size > MEMO_LIMIT) map.delete(map.keys().next().value);
  return value;
}
// Station geography comes from static files the site build writes from the same snapshot as D1's
// stations table (data/stations/<area>.json, plus index.json listing the areas that have stations).
// An isolate keeps the index and its most recently used areas' files; they change only with a
// deploy, which starts new isolates. Only files that have arrived are kept: a load still in flight
// belongs to its request, which may end (and cancel it) before it settles.
let stationIndex = null;
const areaStations = new Map();
async function stationFile(env, origin, name) {
  const response = await env.ASSETS.fetch(new Request(`${origin}/data/stations/${name}.json`));
  if (!response.ok) throw new Error(`station file ${name}: ${response.status}`);
  return response.json();
}
/** Each area's station records; `spent.count` counts the files this request reads. */
async function stationsIn(env, origin, areas, spent) {
  if (!stationIndex) { spent.count++; stationIndex = new Set((await stationFile(env, origin, 'index')).areas); }
  const index = stationIndex;
  return Promise.all(areas.map(async area => {
    if (!index.has(area)) return [];
    if (areaStations.has(area)) return remember(areaStations, area, areaStations.get(area));
    spent.count++;
    return remember(areaStations, area, await stationFile(env, origin, area));
  }));
}
// Prices: D1 keeps one area_prices row per area with all of its current prices and a version that
// changes whenever one of them does. A search asks D1 once, for all its areas together, and gets the
// price list only for areas whose version changed. Lists are kept per isolate and shared with the
// other isolates in the data centre through the Cache API, stamped with when D1 was last asked; an
// area is asked again at most every VERSION_SECONDS, which bounds how old a price can look.
const PRICE_CACHE_SECONDS = 7 * 86400, VERSION_SECONDS = 15;
const priceKey = (origin, area) => new Request(`${origin}/__cache/v2/prices/${area}`);
// area_prices.prices is an object keyed by station and fuel; lists hold its [station_id, fuel_type, price_milli, observed_at, source] values.
const priceList = text => Object.values(JSON.parse(text));
// Per-isolate {version, checkedAt, seenAt, list} by area, kept per D1 binding. checkedAt is this
// Worker's clock just before it asked D1, for freshness. seenAt is D1's own clock when it answered,
// which orders answers however late they arrive: a list never replaces one D1 gave later.
const d1Clock = "CAST(round((julianday('now')-2440587.5)*86400000) AS INTEGER)";
const newer = (entry, than) => !than || entry.seenAt > than.seenAt;
const priceMemo = new WeakMap();
const pricesFor = db => { if (!priceMemo.has(db)) priceMemo.set(db, new Map()); return priceMemo.get(db); };
async function cached(key) {
  const response = await globalThis.caches?.default.match(key);
  return response ? response.json() : undefined;
}
function store(ctx, key, value, seconds) {
  const cache = globalThis.caches?.default;
  if (!cache) return;
  const put = cache.put(key, new Response(JSON.stringify(value), {headers: {'content-type': 'application/json', 'cache-control': `public, max-age=${seconds}`}}))
    .catch(() => {});
  ctx?.waitUntil ? ctx.waitUntil(put) : put;
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
async function report(request, env, ctx) {
  const body = await readReport(request);
  // Edge limit is best effort per IP, while a SQL trigger enforces per-install/global limits atomically.
  if (env.REPORT_LIMITER) {
    const {success} = await env.REPORT_LIMITER.limit({key: request.headers.get('cf-connecting-ip') || body.client_id});
    if (!success) throw new InputError('rate_limited', 'Too many reports. Try again in a minute.', 429);
  }
  checkBudget(env, 'write');
  const id = body.request_id || crypto.randomUUID(), observed_at = new Date().toISOString(), asked = Date.now();
  // One D1 call: an earlier report with this request ID, the insert (only for a known station and a new
  // ID, so a concurrent retry cannot insert twice), and the station's area prices after the insert.
  const learn = budgetStatement(env), day = budget.day;
  const [old, , place, total] = meter(await env.DB.batch([
    env.DB.prepare('SELECT id, station_id, fuel_type, price_milli, client_id, observed_at FROM price_reports WHERE id = ?1').bind(id),
    env.DB.prepare('INSERT INTO price_reports (id, station_id, fuel_type, price_milli, client_id, observed_at) SELECT ?1, ?2, ?3, ?4, ?5, ?6 ' +
      'WHERE EXISTS (SELECT 1 FROM stations WHERE id = ?2) AND NOT EXISTS (SELECT 1 FROM price_reports WHERE id = ?1)')
      .bind(id, body.station_id, body.fuel_type, body.price_milli, body.client_id, observed_at),
    env.DB.prepare(`SELECT CAST((latitude+90)*2 AS INTEGER)*1000+CAST((longitude+180)*2 AS INTEGER) AS area, area_prices.version AS version, area_prices.prices AS prices, ${d1Clock} AS seen ` +
      'FROM stations LEFT JOIN area_prices ON area_prices.area = CAST((latitude+90)*2 AS INTEGER)*1000+CAST((longitude+180)*2 AS INTEGER) WHERE stations.id = ?1')
      .bind(body.station_id),
    ...(learn ? [learn] : []),
  ])).map(result => result.results[0]);
  if (learn) learnBudget(day, total);
  if (!place) throw new InputError('station_not_found', 'This station was not found in the imported directory.', 404);
  // This isolate sees the area's prices as they are after the report at once, and so does any isolate
  // in the data centre that checks its cache next; others within VERSION_SECONDS.
  const entry = {version: place.version ?? 0, checkedAt: asked, seenAt: place.seen, list: place.prices ? priceList(place.prices) : []};
  const memo = pricesFor(env.DB);
  if (newer(entry, memo.get(place.area))) {
    remember(memo, place.area, entry);
    store(ctx, priceKey(new URL(request.url).origin, place.area), entry, PRICE_CACHE_SECONDS);
  }
  if (old) {
    if (old.client_id !== body.client_id || old.station_id !== body.station_id || old.fuel_type !== body.fuel_type || old.price_milli !== body.price_milli) {
      throw new InputError('report_conflict', 'Use a new request ID for a different report.', 409);
    }
    delete old.client_id;
    return json({ok: true, report: old, is_demo: false, verification: 'unverified'}, 200);
  }
  return json({ok: true, report: {id, station_id: body.station_id, fuel_type: body.fuel_type, price_milli: body.price_milli, observed_at}, is_demo: false, verification: 'unverified'}, 201);
}
const radians = n => n * Math.PI / 180;
export function distanceMetres(lat1, lon1, lat2, lon2) {
  const a = Math.sin(radians(lat2-lat1)/2)**2 + Math.cos(radians(lat1))*Math.cos(radians(lat2))*Math.sin(radians(lon2-lon1)/2)**2;
  return Math.round(6371000 * 2 * Math.atan2(Math.sqrt(Math.min(1,a)), Math.sqrt(Math.max(0,1-a))));
}
export function locationQuery(query) {
  for (const key of query.keys()) {
    if (!['lat', 'lon', 'radius', 'fuel'].includes(key) || query.getAll(key).length !== 1)
      throw new InputError('invalid_query', 'Use one latitude, longitude, radius, and fuel grade.');
  }
  if (!query.has('lat') || !query.has('lon') || query.getAll('lat').length !== 1 || query.getAll('lon').length !== 1 ||
      !query.get('lat').trim() || !query.get('lon').trim()) throw new InputError('location_required', 'Allow location access or search for a Canadian city.');
  const lat=Number(query.get('lat')), lon=Number(query.get('lon')), radius=Number(query.get('radius') || 10000), fuel=query.get('fuel') || 'regular';
  if ((query.has('radius') && !query.get('radius').trim()) || (query.has('fuel') && !query.get('fuel').trim()) ||
      !Number.isFinite(lat) || Math.abs(lat)>90 || !Number.isFinite(lon) || Math.abs(lon)>180 ||
      !Number.isFinite(radius) || radius<100 || radius>50000 || !grades.has(fuel))
    throw new InputError('invalid_query','Use valid coordinates, a radius from 100 to 50000 metres, and a supported fuel grade.');
  return {lat,lon,radius,fuel};
}
export function normalizeSearch(value) {
  return value.normalize('NFKD').replace(/\p{M}/gu,'').toLowerCase();
}
function marketReference(lat, lon, fuel) {
  const averages=marketAverages.filter(r=>r.fuel_type===fuel);
  const regional=averages.filter(r=>r.city!=='Canada').map(r=>({...r,distance:distanceMetres(lat,lon,r.latitude,r.longitude)})).sort((a,b)=>a.distance-b.distance)[0];
  return regional?.distance<=100000?regional:averages.find(r=>r.city==='Canada');
}
// Each record's corrected and branded form, made when a search first returns it and kept with the record.
const places = new WeakMap();
function placeOf(record) {
  if (!places.has(record)) {
    const place = {...correctedStation(record), latitude: record.latitude, longitude: record.longitude};
    places.set(record, {...place, ...stationBrand(place)});
  }
  return places.get(record);
}
/** The areas a search covers, at most MAX_AREAS. */
export function searchAreas(lat, lon, radius) {
  const dy=radius/110000, south=Math.max(-90,lat-dy), north=Math.min(89.999999,lat+dy);
  const rowCount=areaRow(north)-areaRow(south)+1;
  // A span of 2*dx degrees touches at most 4*dx+2 half-degree columns.
  const dx=Math.min(radius/(110000*Math.max(0.001,Math.cos(radians(lat)))), Math.max(0,(Math.floor(MAX_AREAS/rowCount)-2)/4));
  const west=Math.max(-180,lon-dx), east=Math.min(179.999999,lon+dx);
  const areas=[];
  for(let row=areaRow(south);row<=areaRow(north);row++) for(let column=areaColumn(west);column<=areaColumn(east);column++) areas.push(areaId(row,column));
  return areas;
}
async function nearby(env, query, origin, ctx) {
  const {lat,lon,radius,fuel}=locationQuery(query);
  const areas=searchAreas(lat,lon,radius);
  const now=Date.now(), memo=pricesFor(env.DB), spent={count:0};
  // Nearest stations first, from the static geography; prices are only needed for their areas.
  const candidates=[];
  (await stationsIn(env,origin,areas,spent)).forEach((places,i)=>{
    for(const place of places) {
      const distance=distanceMetres(lat,lon,place.latitude,place.longitude);
      if(distance<=radius) candidates.push([distance,place,areas[i]]);
    }
  });
  candidates.sort((a,b)=>a[0]-b[0]);
  const returned=candidates.slice(0,200);
  const priced=[...new Set(returned.map(candidate=>candidate[2]))];
  // Price lists: this isolate's copy, then the data centre's, each if D1 was asked within VERSION_SECONDS.
  const fresh=entry=>entry && now-entry.checkedAt<VERSION_SECONDS*1000;
  const prices=new Map(), known=new Map();
  let stale=[];
  for(const area of priced) {
    const own=memo.get(area);
    if(fresh(own)) prices.set(area,own.list); else { stale.push(area); if(own) known.set(area,own); }
  }
  // Each area shared through the data centre's cache takes a lookup and possibly a store.
  const shareable=new Set(stale.slice(0,Math.max(0,Math.floor((SUBREQUESTS-spent.count)/2))));
  await Promise.all([...shareable].map(async area=>{
    const shared=await cached(priceKey(origin,area)), own=memo.get(area);
    if(!shared || !newer(shared,own)) return;
    known.set(area,shared);
    if(fresh(shared)) { prices.set(area,shared.list); remember(memo,area,shared); }
  }));
  stale=stale.filter(area=>!prices.has(area));
  if(stale.length) {
    try { checkBudget(env); }
    catch(error) {
      // Over the cap, areas seen before keep their last known prices (each shows its age).
      if(!(error instanceof SpendingCap) || stale.some(area=>!known.has(area))) throw error;
      for(const area of stale) prices.set(area,known.get(area).list);
      stale=[];
    }
  }
  if(stale.length) {
    // One query for every stale area: its version, and its list only if the version is not the one known.
    // Every stale area gets a row (version NULL if it never had a price), stamped with D1's clock.
    const statements=[env.DB.prepare(`WITH known(area,version) AS (VALUES ${stale.map((_,i)=>`(?${2*i+1},?${2*i+2})`).join(',')}) `+
      `SELECT known.area AS area, area_prices.version AS version, CASE WHEN area_prices.version=known.version THEN NULL ELSE area_prices.prices END AS prices, ${d1Clock} AS seen `+
      'FROM known LEFT JOIN area_prices ON area_prices.area=known.area').bind(...stale.flatMap(area=>[area,known.get(area)?.version??0]))];
    // An isolate's first D1 call of the day also reads the shared budget total, in the same round trip.
    const learn=budgetStatement(env), day=budget.day;
    if(learn) statements.push(learn);
    const asked=Date.now(), results=meter(await env.DB.batch(statements));
    if(learn) learnBudget(day,results[1].results[0]);
    const rows=new Map(results[0].results.map(row=>[row.area,row]));
    for(const area of stale) {
      const row=rows.get(area), version=row?.version??0;
      const entry={version,checkedAt:asked,seenAt:row?.seen,list:!version?[]:row.prices===null?known.get(area)?.list??[]:priceList(row.prices)};
      // A report handled here, or another read, may have stored what D1 said after this answer.
      const current=memo.get(area);
      if(!newer(entry,current)) { prices.set(area,current.list); continue; }
      prices.set(area,entry.list);
      remember(memo,area,entry);
      if(shareable.has(area)) store(ctx,priceKey(origin,area),entry,PRICE_CACHE_SECONDS);
    }
  }
  const byStation=new Map();
  for(const area of priced) for(const price of prices.get(area)||[]) {
    if(!byStation.has(price[0])) byStation.set(price[0],[]);
    byStation.get(price[0]).push(price);
  }
  const stations=returned.map(([distance,record])=>{
    const station={...placeOf(record),distanceMetres:distance,prices:{regular:null,premium:null,diesel:null},ages:{},observedAt:{},priceSources:{},stale:{},synthetic:false};
    for(const [,fuelType,priceMilli,observedAt,source] of byStation.get(record.id)||[]) {
      station.prices[fuelType]=priceMilli;
      station.ages[fuelType]=Math.max(0,Math.floor((now-Date.parse(observedAt))/60000));
      station.observedAt[fuelType]=observedAt;
      station.priceSources[fuelType]=source;
      station.stale[fuelType]=station.ages[fuelType]>1440;
    }
    return station;
  });
  const reference=marketReference(lat,lon,fuel);
  return {mode:'live',is_demo:false,generated_at:new Date(now).toISOString(),currency:'CAD',unit:'L',
    location:{latitude:lat,longitude:lon,radiusMetres:radius},stations,
    coverage:{...metadata,matched_count:candidates.length,returned_count:stations.length,truncated:candidates.length>200,source_url:'https://www.openstreetmap.org/copyright',prices:'community-unverified'},
    market_reference:reference?{city:reference.city,period:reference.period,fuel_type:fuel,price_milli:reference.price_milli,kind:'monthly_average',source:'Statistics Canada',source_url:'https://www150.statcan.gc.ca/t1/tbl1/en/tv.action?pid=1810000101',note:'Dated regional monthly average, not a station pump price.'}:null};
}
// City search runs on the bundled GeoNames list (the same data the cities table holds), so it never
// reads D1: matching names first by prefix, then by population, as the SQL version did.
function geocode(query) {
  if ([...query.keys()].some(key => key !== 'q') || query.getAll('q').length !== 1)
    throw new InputError('invalid_search', 'Enter one Canadian city name.');
  const raw=query.get('q')?.trim()||'';
  if(raw.length<2 || raw.length>80) throw new InputError('invalid_search','Enter 2 to 80 characters of a Canadian city name.');
  const search=normalizeSearch(raw);
  const results=cities.filter(city=>city.search_name.includes(search))
    .sort((a,b)=>Number(b.search_name.startsWith(search))-Number(a.search_name.startsWith(search))||b.population-a.population)
    .slice(0,8).map(({name,latitude,longitude})=>({name,latitude,longitude}));
  return {results,source:'GeoNames',attribution:'GeoNames, CC BY 4.0',coverage:'Canadian cities with population over 15000; coordinates can be entered directly.'};
}
function spendingCap(error, env) {
  const now=Date.now();
  const reports=/report|write/.test(error.reason);
  return json({error:'spending_cap',reason:error.reason,scope:reports?'reports':'all',resets_at:new Date(error.until).toISOString(),
    message:reports?"OpenFuel's free database allowance for new reports today is used up. Browsing still works; reports return after midnight UTC."
      :"OpenFuel's free database allowance for today is used up. Saved stations still show; live prices return after midnight UTC.",
    donate_url:env.OPENFUEL_DONATE_URL||null},503,{'retry-after':String(Math.max(60,Math.ceil((error.until-now)/1000)))});
}
export default {
  async fetch(request,env,ctx) {
    const url=new URL(request.url);
    if(!url.pathname.startsWith('/api/')) return env.ASSETS.fetch(request);
    if(request.method==='OPTIONS') return new Response(null,{status:204,headers:{...headers,'access-control-allow-methods':'GET, HEAD, POST, OPTIONS','access-control-allow-headers':'Content-Type, Accept'}});
    try {
      if(!env.DB) return json({error:'not_configured',message:'The station database is not configured.'},503);
      if(url.pathname==='/api/v1/reports' && request.method==='POST') return await report(request,env,ctx);
      if(!['GET','HEAD'].includes(request.method)) return json({error:'method_not_allowed'},405,{allow:url.pathname==='/api/v1/reports'?'POST, OPTIONS':'GET, HEAD, OPTIONS'});
      let body, cacheControl;
      // Health checks that D1 answers (SELECT 1 reads no rows); the counts come from the bundled snapshot.
      if(url.pathname==='/api/v1/health') {
        checkBudget(env);
        const learn=budgetStatement(env), day=budget.day;
        if(learn) learnBudget(day,meter(await env.DB.batch([env.DB.prepare('SELECT 1 AS ok'),learn]))[1].results[0]);
        else meter(await env.DB.prepare('SELECT 1 AS ok').all());
        body={ok:true,service:'openfuel',database:'cloudflare-d1',databaseConfigured:true,writesEnabled:writesOpen(env),mode:'live',is_demo:false,station_count:metadata.station_count};
        cacheControl='no-store';
      } else if(url.pathname==='/api/v1/stations') { body=await nearby(env,url.searchParams,url.origin,ctx); cacheControl='private, max-age=15'; }
      else if(url.pathname==='/api/v1/geocode') { body=geocode(url.searchParams); cacheControl='public, max-age=86400'; }
      else if(url.pathname==='/api/v1/regions') { body={regions:[{id:'CA',name:'Canada',is_demo:false,...metadata}]}; cacheControl='public, max-age=3600'; }
      else return json({error:'not_found'},404);
      const extra={'cache-control':cacheControl};
      return request.method==='HEAD'?new Response(null,{headers:{...headers,...extra}}):json(body,200,extra);
    } catch(error) {
      if(error instanceof InputError) return json({error:error.code,message:error.message},error.status,error.status===429?{'retry-after':'60'}:{});
      if(error instanceof SpendingCap) return spendingCap(error,env);
      const limit=String(error.message).match(d1LimitPattern);
      if(limit) {
        // D1's read limit stops everything. Its write limit stops reports; only reports write, so a
        // read that fails with it means D1 is refusing all queries.
        const until=nextUtcMidnight(Date.now()), kind=limit[1].toLowerCase(), reporting=url.pathname==='/api/v1/reports';
        if(kind==='write') budget.writeCappedUntil=until;
        if(kind==='read' || !reporting) budget.readCappedUntil=until;
        return spendingCap(new SpendingCap(kind==='write' && !reporting?'d1_free_daily_limit':`d1_free_daily_${kind}_limit`,until),env);
      }
      if(String(error.message).includes('report_capacity_limit')) return json({error:'report_capacity',message:'The report archive is full. Station browsing remains available.'},429);
      if(String(error.message).includes('report_rate_limit')) return json({error:'rate_limited',message:'The hourly report limit was reached. Try again later.'},429,{'retry-after':'3600'});
      // Never log user coordinates, report identifiers, bodies, or IP addresses.
      console.error(JSON.stringify({event:'api_request_failed',path:url.pathname}));
      return json({error:'temporarily_unavailable',message:'Could not reach the station database. Please try again.'},503);
    } finally {
      if(env.DB) { const flush=flushBudget(env); ctx?.waitUntil ? ctx.waitUntil(flush) : await flush; }
    }
  }
};
