// SPDX-License-Identifier: AGPL-3.0-only
// Real Canadian station discovery with public, unverified community pump reports.
import {stationBrand} from '../../packages/brands/resolve.mjs';
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
async function report(request, env) {
  const body = await readReport(request);
  // Edge limit is best effort per IP, while a SQL trigger enforces per-install/global limits atomically.
  if (env.REPORT_LIMITER) {
    const {success} = await env.REPORT_LIMITER.limit({key: request.headers.get('cf-connecting-ip') || body.client_id});
    if (!success) throw new InputError('rate_limited', 'Too many reports. Try again in a minute.', 429);
  }
  const existing = await env.DB.prepare('SELECT id FROM stations WHERE id = ?1').bind(body.station_id).first();
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
    await env.DB.prepare('INSERT INTO price_reports (id, station_id, fuel_type, price_milli, client_id, observed_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)')
      .bind(id, body.station_id, body.fuel_type, body.price_milli, body.client_id, observed_at).run();
  } catch (error) {
    // A concurrent retry may have committed after the initial lookup.
    if (body.request_id) { const committed = await findOld(); if (committed) return repeated(committed); }
    throw error;
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
async function nearby(db, query) {
  const {lat,lon,radius,fuel}=locationQuery(query);
  const dy=radius/110000, dx=Math.min(180, radius/(110000*Math.max(0.001,Math.cos(radians(lat)))));
  const bounds=[lat-dy,lat+dy,lon-dx,lon+dx];
  const [stationRows,priceRows,metadata,averages]=await db.batch([
    db.prepare('SELECT id,latitude,longitude,data FROM stations WHERE latitude BETWEEN ?1 AND ?2 AND longitude BETWEEN ?3 AND ?4 LIMIT 2000').bind(...bounds),
    db.prepare('SELECT p.* FROM current_prices p JOIN stations s ON s.id=p.station_id WHERE s.latitude BETWEEN ?1 AND ?2 AND s.longitude BETWEEN ?3 AND ?4 LIMIT 6000').bind(...bounds),
    db.prepare("SELECT data FROM dataset_metadata WHERE id='canada'"),
    db.prepare('SELECT * FROM market_averages WHERE fuel_type=?1').bind(fuel),
  ]);
  const now=Date.now();
  const prices=new Map();
  for(const row of priceRows.results) { if(!prices.has(row.station_id)) prices.set(row.station_id,[]); prices.get(row.station_id).push(row); }
  const stations=stationRows.results.map(row=>{
    const station={...JSON.parse(row.data),distanceMetres:distanceMetres(lat,lon,row.latitude,row.longitude),prices:{regular:null,premium:null,diesel:null},ages:{},observedAt:{},priceSources:{},stale:{},synthetic:false};
    for(const price of prices.get(row.id)||[]) {
      station.prices[price.fuel_type]=price.price_milli;
      station.ages[price.fuel_type]=Math.max(0,Math.floor((now-Date.parse(price.observed_at))/60000));
      station.observedAt[price.fuel_type]=price.observed_at;
      station.priceSources[price.fuel_type]=price.source;
      station.stale[price.fuel_type]=station.ages[price.fuel_type]>1440;
    }
    return {...station, ...stationBrand(station)};
  }).filter(s=>s.distanceMetres<=radius).sort((a,b)=>a.distanceMetres-b.distanceMetres);
  const regional=averages.results.filter(r=>r.city!=='Canada').map(r=>({...r,distance:distanceMetres(lat,lon,r.latitude,r.longitude)})).sort((a,b)=>a.distance-b.distance)[0];
  const reference=regional?.distance<=100000?regional:averages.results.find(r=>r.city==='Canada');
  return {mode:'live',is_demo:false,generated_at:new Date(now).toISOString(),currency:'CAD',unit:'L',
    location:{latitude:lat,longitude:lon,radiusMetres:radius},stations:stations.slice(0,200),
    coverage:{...JSON.parse(metadata.results[0]?.data||'{"country":"CA"}'),matched_count:stations.length,returned_count:Math.min(stations.length,200),truncated:stations.length>200 || stationRows.results.length===2000,source_url:'https://www.openstreetmap.org/copyright',prices:'community-unverified'},
    market_reference:reference?{city:reference.city,period:reference.period,fuel_type:fuel,price_milli:reference.price_milli,kind:'monthly_average',source:'Statistics Canada',source_url:'https://www150.statcan.gc.ca/t1/tbl1/en/tv.action?pid=1810000101',note:'Dated regional monthly average, not a station pump price.'}:null};
}
async function geocode(db, query) {
  if ([...query.keys()].some(key => key !== 'q') || query.getAll('q').length !== 1)
    throw new InputError('invalid_search', 'Enter one Canadian city name.');
  const raw=query.get('q')?.trim()||'';
  if(raw.length<2 || raw.length>80) throw new InputError('invalid_search','Enter 2 to 80 characters of a Canadian city name.');
  const search=normalizeSearch(raw).replace(/[\\%_]/g,c=>'\\'+c);
  const rows=await db.prepare("SELECT name,latitude,longitude FROM cities WHERE search_name LIKE ?1 ESCAPE '\\' ORDER BY CASE WHEN search_name LIKE ?2 ESCAPE '\\' THEN 0 ELSE 1 END,population DESC LIMIT 8").bind('%'+search+'%',search+'%').all();
  return {results:rows.results,source:'GeoNames',attribution:'GeoNames, CC BY 4.0',coverage:'Canadian cities with population over 15000; coordinates can be entered directly.'};
}
export default {
  async fetch(request,env) {
    const url=new URL(request.url);
    if(!url.pathname.startsWith('/api/')) return env.ASSETS.fetch(request);
    if(request.method==='OPTIONS') return new Response(null,{status:204,headers:{...headers,'access-control-allow-methods':'GET, HEAD, POST, OPTIONS','access-control-allow-headers':'Content-Type, Accept'}});
    try {
      if(!env.DB) return json({error:'not_configured',message:'The station database is not configured.'},503);
      if(url.pathname==='/api/v1/reports' && request.method==='POST') return await report(request,env);
      if(!['GET','HEAD'].includes(request.method)) return json({error:'method_not_allowed'},405,{allow:url.pathname==='/api/v1/reports'?'POST, OPTIONS':'GET, HEAD, OPTIONS'});
      let body;
      if(url.pathname==='/api/v1/health') {
        const row=await env.DB.prepare('SELECT count(*) AS station_count FROM stations').first();
        body={ok:true,service:'openfuel',database:'cloudflare-d1',databaseConfigured:true,writesEnabled:true,mode:'live',is_demo:false,station_count:row.station_count};
      } else if(url.pathname==='/api/v1/stations') body=await nearby(env.DB,url.searchParams);
      else if(url.pathname==='/api/v1/geocode') body=await geocode(env.DB,url.searchParams);
      else if(url.pathname==='/api/v1/regions') { const row=await env.DB.prepare("SELECT data FROM dataset_metadata WHERE id='canada'").first(); body={regions:[{id:'CA',name:'Canada',is_demo:false,...JSON.parse(row?.data||'{}')}]}; }
      else return json({error:'not_found'},404);
      return request.method==='HEAD'?new Response(null,{headers}):json(body);
    } catch(error) {
      if(error instanceof InputError) return json({error:error.code,message:error.message},error.status,error.status===429?{'retry-after':'60'}:{});
      if(String(error.message).includes('report_capacity_limit')) return json({error:'report_capacity',message:'The report archive is full. Station browsing remains available.'},429);
      if(String(error.message).includes('report_rate_limit')) return json({error:'rate_limited',message:'The hourly report limit was reached. Try again later.'},429,{'retry-after':'3600'});
      // Never log user coordinates, report identifiers, bodies, or IP addresses.
      console.error(JSON.stringify({event:'api_request_failed',path:url.pathname}));
      return json({error:'temporarily_unavailable',message:'Could not reach the station database. Please try again.'},503);
    }
  }
};
