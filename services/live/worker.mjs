// SPDX-License-Identifier: AGPL-3.0-only
// Real Canadian station discovery with public, unverified community pump reports.
import {correctedStation} from '../../packages/data/corrections.mjs';
import {stationBrand} from '../../packages/brands/resolve.mjs';
import metadata from '../../packages/data/metadata.json' with {type: 'json'};
import marketAverages from '../../packages/data/market-averages.json' with {type: 'json'};
import brandCatalog from '../../packages/brands/catalog.json' with {type: 'json'};
import stationCorrections from '../../packages/data/station-corrections.json' with {type: 'json'};
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
const budget = {day: '', pendingRead: 0, pendingWritten: 0, read: 0, written: 0, flushedAt: 0, flushing: false, readCappedUntil: 0, writeCappedUntil: 0};
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
  // A new isolate or day flushes on its first metered request, so it learns the shared total at once.
  if (budget.day !== day) Object.assign(budget, {day, pendingRead: 0, pendingWritten: 0, read: 0, written: 0, flushedAt: 0});
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
function meter(results) {
  for (const result of [results].flat()) {
    budget.pendingRead += Number(result?.meta?.rows_read) || 0;
    budget.pendingWritten += Number(result?.meta?.rows_written) || 0;
  }
  return results;
}
async function flushBudget(env, now = Date.now()) {
  if (budget.flushing || (!budget.pendingRead && !budget.pendingWritten)) return;
  if (budget.pendingRead < 25_000 && now - budget.flushedAt < 120_000) return;
  const day = budget.day, read = budget.pendingRead, written = budget.pendingWritten;
  budget.flushing = true; budget.pendingRead = 0; budget.pendingWritten = 0; budget.flushedAt = now;
  try {
    const total = await env.DB.prepare('INSERT INTO usage_budget(day,rows_read,rows_written) VALUES(?1,?2,?3) ON CONFLICT(day) DO UPDATE SET rows_read=rows_read+excluded.rows_read, rows_written=rows_written+excluded.rows_written RETURNING rows_read, rows_written')
      .bind(day, read, written).first();
    // A flush that finishes after midnight UTC belongs to the previous day's row.
    if (total && budget.day === day) {
      budget.read = total.rows_read; budget.written = total.rows_written;
      // The flush itself reads and writes about one row each.
      budget.pendingRead += 2; budget.pendingWritten += 1;
    }
  } catch { if (budget.day === day) { budget.pendingRead += read; budget.pendingWritten += written; } }
  finally { budget.flushing = false; }
}
const d1LimitPattern = /exceeded D1's free tier daily row (read|write) limit/i;

// Areas are 0.5 degree squares. The SQL expressions match the stations_area index and current_prices.area.
const areaRow = lat => Math.floor((lat + 90) * 2), areaColumn = lon => Math.floor((lon + 180) * 2);
const areaId = (row, column) => row * 1000 + column;
// At most this many areas per search, which keeps Cache API calls and D1 parameters within Workers Free
// limits. It only narrows 50 km searches in the far north, where it trims the east-west edges.
const MAX_AREAS = 20;
// Geography changes only with a re-import, brand catalog or correction change, so its cache key
// follows those files. Prices are cached per area version: D1 changes an area's version whenever a
// price there changes, so a cached price stays valid until it is updated. Each isolate re-reads the
// versions (one row per area) at most every VERSION_SECONDS, which bounds how old a price can look.
const GEOGRAPHY_SECONDS = 7 * 86400, PRICE_SECONDS = 7 * 86400, VERSION_SECONDS = 15;
const fingerprint = text => { let hash = 2166136261; for (let i = 0; i < text.length; i++) hash = Math.imul(hash ^ text.charCodeAt(i), 16777619); return (hash >>> 0).toString(36); };
const GEOGRAPHY_VERSION = `g2.${metadata.imported_at}.${fingerprint(JSON.stringify([brandCatalog, stationCorrections]))}`;
const cacheKey = (origin, kind, id) => new Request(`${origin}/__cache/v1/${kind}/${kind === 'prices' ? '' : GEOGRAPHY_VERSION + '/'}${id}`);
// Per-isolate copies, checked before the Cache API: area geography and price lists by version.
const placesMemo = new Map(), pricesMemo = new Map(), versionMemo = new WeakMap();
const versionsFor = db => { if (!versionMemo.has(db)) versionMemo.set(db, new Map()); return versionMemo.get(db); };
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
  const existing = await env.DB.prepare('SELECT id, latitude, longitude FROM stations WHERE id = ?1').bind(body.station_id).first();
  if (!existing) throw new InputError('station_not_found', 'This station was not found in the imported directory.', 404);
  const id = body.request_id || crypto.randomUUID();
  const findOld = () => env.DB.prepare('SELECT id, station_id, fuel_type, price_milli, client_id, observed_at FROM price_reports WHERE id = ?1').bind(id).first();
  const repeated = old => {
    if (old.client_id !== body.client_id || old.station_id !== body.station_id || old.fuel_type !== body.fuel_type || old.price_milli !== body.price_milli) {
      throw new InputError('report_conflict', 'Use a new request ID for a different report.', 409);
    }
    delete old.client_id;
    return json({ok: true, report: old, is_demo: false, verification: 'unverified'}, 200);
  };
  const old = await findOld();
  if (old) return repeated(old);
  const observed_at = new Date().toISOString();
  try {
    meter(await env.DB.prepare('INSERT INTO price_reports (id, station_id, fuel_type, price_milli, client_id, observed_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)')
      .bind(id, body.station_id, body.fuel_type, body.price_milli, body.client_id, observed_at).run());
  } catch (error) {
    // A concurrent retry may have committed after the initial lookup.
    if (body.request_id) { const committed = await findOld(); if (committed) return repeated(committed); }
    throw error;
  }
  // The report changed its area's version in D1, so every data centre reads the new price on its next
  // version check. This isolate checks at once, and a read that started earlier cannot restore the old version.
  const area = areaId(areaRow(existing.latitude), areaColumn(existing.longitude)), memo = versionsFor(env.DB);
  memo.set(area, {version: memo.get(area)?.version ?? 0, checkedAt: -Infinity, invalidatedAt: Date.now()});
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
async function nearby(env, query, origin, ctx) {
  const {lat,lon,radius,fuel}=locationQuery(query);
  const dy=radius/110000, south=Math.max(-90,lat-dy), north=Math.min(89.999999,lat+dy);
  const rowCount=areaRow(north)-areaRow(south)+1;
  // A span of 2*dx degrees touches at most 4*dx+2 half-degree columns.
  const dx=Math.min(radius/(110000*Math.max(0.001,Math.cos(radians(lat)))), Math.max(0,(Math.floor(MAX_AREAS/rowCount)-2)/4));
  const west=Math.max(-180,lon-dx), east=Math.min(179.999999,lon+dx);
  const areas=[];
  for(let row=areaRow(south);row<=areaRow(north);row++) for(let column=areaColumn(west);column<=areaColumn(east);column++) areas.push({row,column,id:areaId(row,column)});
  const now=Date.now(), memo=versionsFor(env.DB);
  // Station rows per area: this isolate's copy, then the Cache API, then D1.
  const geography=new Map();
  for(const area of areas) if(placesMemo.has(area.id)) geography.set(area.id,placesMemo.get(area.id));
  await Promise.all(areas.filter(area=>!geography.has(area.id)).map(async area=>{
    const rows=await cached(cacheKey(origin,'stations',area.id));
    if(rows) { geography.set(area.id,rows); placesMemo.set(area.id,rows); }
  }));
  const missingPlaces=areas.filter(area=>!geography.has(area.id));
  // Price versions for this request; a version older than VERSION_SECONDS is read again.
  const versions=new Map(), unchecked=[];
  for(const area of areas) {
    const entry=memo.get(area.id);
    if(entry && now-entry.checkedAt<VERSION_SECONDS*1000) versions.set(area.id,entry.version); else unchecked.push(area);
  }
  const prices=new Map();
  if(missingPlaces.length||unchecked.length) {
    try { checkBudget(env); }
    catch(error) {
      // Over the cap, areas already cached keep working with the last known prices (each shows its age).
      if(!(error instanceof SpendingCap) || missingPlaces.length) throw error;
      await Promise.all(unchecked.map(async area=>{
        const known=memo.get(area.id)?.version ?? (await cached(cacheKey(origin,'prices',`${area.id}/latest`)))?.version;
        if(known===undefined) throw error;
        versions.set(area.id,known);
      }));
      unchecked.length=0;
    }
  }
  if(missingPlaces.length||unchecked.length) {
    // One statement per area row keeps D1 on the stations_area index.
    const rows=[...new Set(missingPlaces.map(area=>area.row))].map(row=>{
      const columns=missingPlaces.filter(area=>area.row===row).map(area=>area.column);
      return {row,from:Math.min(...columns),to:Math.max(...columns)};
    });
    const statements=rows.map(({row,from,to})=>env.DB.prepare('SELECT id,latitude,longitude,data FROM stations WHERE CAST((latitude+90)*2 AS INTEGER)=?1 AND CAST((longitude+180)*2 AS INTEGER) BETWEEN ?2 AND ?3').bind(row,from,to));
    if(unchecked.length) statements.push(env.DB.prepare(`SELECT area,version FROM area_versions WHERE area IN (${unchecked.map((_,i)=>'?'+(i+1)).join(',')})`).bind(...unchecked.map(area=>area.id)));
    const results=meter(await env.DB.batch(statements));
    const wanted=new Set(missingPlaces.map(area=>area.id));
    for(const area of missingPlaces) geography.set(area.id,[]);
    rows.forEach((_,i)=>{
      for(const row of results[i].results) {
        const id=areaId(areaRow(row.latitude),areaColumn(row.longitude));
        if(wanted.has(id)) geography.get(id).push([row.id,row.latitude,row.longitude,row.data]);
      }
    });
    for(const area of missingPlaces) { placesMemo.set(area.id,geography.get(area.id)); store(ctx,cacheKey(origin,'stations',area.id),geography.get(area.id),GEOGRAPHY_SECONDS); }
    if(unchecked.length) {
      const found=new Map(results[results.length-1].results.map(row=>[row.area,row.version]));
      for(const area of unchecked) {
        const version=found.get(area.id)??0;
        versions.set(area.id,version);
        // A report handled here after this read began has already made the result out of date.
        if(!(memo.get(area.id)?.invalidatedAt>=now)) memo.set(area.id,{version,checkedAt:now});
      }
    }
  }
  // Price lists per area version: this isolate's copy, then the Cache API, then D1.
  const priced=areas.filter(area=>versions.get(area.id)>0);
  for(const area of priced) { const key=`${area.id}/${versions.get(area.id)}`; if(pricesMemo.has(key)) prices.set(area.id,pricesMemo.get(key)); }
  await Promise.all(priced.filter(area=>!prices.has(area.id)).map(async area=>{
    const list=await cached(cacheKey(origin,'prices',`${area.id}/${versions.get(area.id)}`));
    if(list) { prices.set(area.id,list); pricesMemo.set(`${area.id}/${versions.get(area.id)}`,list); }
  }));
  const missingPrices=priced.filter(area=>!prices.has(area.id));
  if(missingPrices.length) {
    checkBudget(env);
    const [result]=meter(await env.DB.batch([env.DB.prepare(`SELECT station_id,fuel_type,price_milli,observed_at,source,area FROM current_prices WHERE area IN (${missingPrices.map((_,i)=>'?'+(i+1)).join(',')})`).bind(...missingPrices.map(area=>area.id))]));
    for(const area of missingPrices) prices.set(area.id,[]);
    for(const row of result.results) prices.get(row.area)?.push([row.station_id,row.fuel_type,row.price_milli,row.observed_at,row.source]);
    for(const area of missingPrices) {
      const version=versions.get(area.id), list=prices.get(area.id);
      pricesMemo.set(`${area.id}/${version}`,list);
      store(ctx,cacheKey(origin,'prices',`${area.id}/${version}`),list,PRICE_SECONDS);
      // The latest list lets a new isolate serve cached areas while the daily cap is reached.
      store(ctx,cacheKey(origin,'prices',`${area.id}/latest`),{version,list},PRICE_SECONDS);
    }
  }
  const byStation=new Map();
  for(const area of areas) for(const price of prices.get(area.id)||[]) {
    if(!byStation.has(price[0])) byStation.set(price[0],[]);
    byStation.get(price[0]).push(price);
  }
  // Distances use the stored coordinates; only the stations returned are parsed and branded.
  const candidates=[];
  for(const area of areas) for(const row of geography.get(area.id)||[]) {
    const distance=distanceMetres(lat,lon,row[1],row[2]);
    if(distance<=radius) candidates.push([distance,row]);
  }
  candidates.sort((a,b)=>a[0]-b[0]);
  const stations=candidates.slice(0,200).map(([distance,[id,latitude,longitude,data]])=>{
    const place={...correctedStation(JSON.parse(data)),latitude,longitude};
    const station={...place,...stationBrand(place),distanceMetres:distance,prices:{regular:null,premium:null,diesel:null},ages:{},observedAt:{},priceSources:{},stale:{},synthetic:false};
    for(const [,fuelType,priceMilli,observedAt,source] of byStation.get(id)||[]) {
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
        meter(await env.DB.prepare('SELECT 1 AS ok').all());
        body={ok:true,service:'openfuel',database:'cloudflare-d1',databaseConfigured:true,writesEnabled:!(budget.writeCappedUntil>Date.now()),mode:'live',is_demo:false,station_count:metadata.station_count};
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
        // D1's read limit stops everything; its write limit only stops reports.
        const until=nextUtcMidnight(Date.now());
        if(limit[1].toLowerCase()==='read') budget.readCappedUntil=until; else budget.writeCappedUntil=until;
        return spendingCap(new SpendingCap(`d1_free_daily_${limit[1].toLowerCase()}_limit`,until),env);
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
