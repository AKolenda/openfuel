/* SPDX-License-Identifier: AGPL-3.0-only
 * Real station map. Leaflet 1.9.4 is bundled with its BSD-2-Clause licence.
 * Map tiles are fetched only for the visible map and use normal HTTP caching.
 * Prices are integer thousandths of CAD/litre; shown as Canadian cents/litre.
 */
(() => {
'use strict';
const $ = id => document.getElementById(id);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const grades = ['regular','premium','diesel'];
const cap = value => value.charAt(0).toUpperCase() + value.slice(1);
const cents = value => value == null ? '—' : (value / 10).toFixed(1);
const storage = {
  read(key, fallback) { try { return JSON.parse(localStorage.getItem(`openfuel-live-v1:${key}`)) ?? fallback; } catch { return fallback; } },
  write(key, value) { try { localStorage.setItem(`openfuel-live-v1:${key}`, JSON.stringify(value)); } catch { /* A private browser can still use the app. */ } },
  remove(key) { try { localStorage.removeItem(`openfuel-live-v1:${key}`); } catch {} },
};
// Retire the old, finer-grained area keys. New saved centres are rounded to
// hundredths of a degree (roughly a kilometre), never a saved GPS fix.
storage.remove('areas');
const logoHosts=new Set(['thumb.wikimedia.org','www.fuel.crs','www.shell.ca','www.tempo.crs']);
const failedLogos=new Set();
function logoURL(value) {
  try { const url=new URL(value);return url.protocol==='https:'&&logoHosts.has(url.hostname)&&!url.username&&!url.password ? url.href : null; } catch { return null; }
}
function brandBadge(station,marker=false) {
  // Brands without a curated logo show their initials, on the map as in the list.
  const url=logoURL(station.brandLogoUrl),fallback=esc(station.name.slice(0,2).toUpperCase());
  return `<span class="${marker?'marker-brand':'station-initial'} brand-badge" aria-hidden="true"><span class="brand-fallback">${fallback}</span>${url&&!failedLogos.has(url)?`<img data-brand-logo src="${esc(url)}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer">`:''}</span>`;
}
document.addEventListener('load',event=>{if(event.target.matches?.('img[data-brand-logo]'))event.target.parentElement.classList.add('logo-loaded');},true);
document.addEventListener('error',event=>{if(event.target.matches?.('img[data-brand-logo]')){failedLogos.add(event.target.src);event.target.remove();}},true);
const saved = storage.read('favorites', []);
const favorites = new Set(Array.isArray(saved) ? saved.filter(id => typeof id === 'string') : []);
const state = {fuel:'regular', radius:10000, sort:'distance', saved:false, center:null, user:null, source:null, label:'', stations:[], selected:null, loadedAt:0, loadFailed:false, connection:'idle', generation:0, reportVersion:0, coverage:null};
let clientId = storage.read('client-id', null);
if (typeof clientId !== 'string' || !/^[a-zA-Z0-9_-]{16,80}$/.test(clientId)) { clientId = crypto.randomUUID(); storage.write('client-id', clientId); }
let pendingReport = storage.read('pending-report', null), reportStation = null, locatePending = false, locationGeneration = 0, searchGeneration = 0, toastTimer;
// pendingRequest is the station request on its way ({generation, reportVersion, key}); fixTimer runs while a return visit waits for the device fix.
let pendingRequest = null, fixTimer = null, onlineTimer, markerFrame = 0, reportedAt = 0;
// A snapshot this young is as current as the server's own short caches, so it is not asked for again.
const FRESH_SNAPSHOT_MS = 30000;
// Other Worker instances can answer with the price from before this browser's report for about 15 seconds,
// and the HTTP cache for 15 more. For 30 seconds each confirmed report ({station_id, fuel_type, price_milli,
// observed_at, confirmedAt}) is laid over every station list shown; it is kept across a page reload.
const OWN_REPORT_MS = 30000;
const savedReports = storage.read('own-reports', []);
let ownReports = Array.isArray(savedReports) ? savedReports : [];
const map = L.map('map', {zoomControl:false, preferCanvas:true}).setView([57, -106], 4);
L.control.zoom({position:'bottomright'}).addTo(map);
const tileOptions = {maxZoom:19, minZoom:3, updateWhenIdle:true, keepBuffer:1};
// Map tiles are not stored in our service worker, bulk downloaded, or prefetched.
function watchTiles(layer) {
  const warn = failed => { $('tile-warning').hidden = !failed; };
  const gl = layer.getMaplibreMap?.();
  if (gl) {
    gl.on('error', () => warn(true));
    gl.on('sourcedata', event => { if (event.tile) warn(false); });
  } else {
    layer.on('tileerror', () => warn(true));
    layer.on('tileload', () => warn(false));
  }
  return layer;
}
// The base map starts once the first centre is known: a saved area, the device location or its refusal
// (the Canada overview), or a search. A first visit then does not download the overview it immediately
// leaves; until then the map shows its background colour. An unanswered permission prompt gets the
// overview after 2.5 seconds.
let baseMapStarted = false;
const baseMapTimer = setTimeout(startBaseMap, 2500);
function startBaseMap() {
  if (baseMapStarted) return;
  baseMapStarted = true;clearTimeout(baseMapTimer);
  baseMapLayer(map, 'map/openfuel-style.json', tileOptions, (layer, name) => {
    watchTiles(layer);
    $('tile-provider').textContent = name === 'openfreemap' ? 'OpenFreeMap' : 'OpenStreetMap';
  });
}
L.control.scale({position:'bottomleft', imperial:false}).addTo(map);
const markerLayer = L.layerGroup().addTo(map), locationLayer = L.layerGroup().addTo(map);
function mapFocusPoint() {
  const size=map.getSize(),panel=document.querySelector('.results-panel').getBoundingClientRect();
  if(matchMedia('(max-width:760px)').matches) {
    const top=document.querySelector('.search-header').getBoundingClientRect().bottom+18;
    const bottom=document.querySelector('.app').classList.contains('sheet-hidden')?size.y-64:panel.top;
    return L.point(size.x/2,Math.max(top+30,(top+bottom)/2));
  }
  return L.point(document.querySelector('.app').classList.contains('sheet-hidden')?size.x/2:(panel.right+size.x)/2,size.y/2);
}
function visibleMapCenter() { return map.containerPointToLatLng(mapFocusPoint()); }
function hideStations(hidden) {
  const center=visibleMapCenter();
  document.querySelector('.app').classList.toggle('sheet-hidden',hidden);
  document.querySelector('.results-panel').classList.remove('expanded');
  $('sheet-toggle').setAttribute('aria-expanded','false');$('sheet-toggle').setAttribute('aria-label','Expand station list');
  $('show-stations').hidden=!hidden;
  map.panBy(map.latLngToContainerPoint(center).subtract(mapFocusPoint()),{animate:false});
  if(hidden)$('show-stations').focus();
  else $('station-list').focus({preventScroll:true});
}
map.on('moveend zoomend', () => {
  const c = visibleMapCenter();
  $('search-area').hidden = !!state.center && distance(c.lat,c.lng,state.center.lat,state.center.lon) < 150;
});
function distance(lat1, lon1, lat2, lon2) {
  const r = Math.PI/180, dlat=(lat2-lat1)*r, dlon=(lon2-lon1)*r;
  const a = Math.sin(dlat/2)**2 + Math.cos(lat1*r)*Math.cos(lat2*r)*Math.sin(dlon/2)**2;
  return 6371008.8 * 2 * Math.atan2(Math.sqrt(a),Math.sqrt(Math.max(0,1-a)));
}
function distanceLabel(station) {
  if (!state.center) return '';
  const metres=distance(state.center.lat,state.center.lon,station.latitude,station.longitude);
  return `${metres < 1000 ? `${Math.round(metres/10)*10} m` : `${(metres/1000).toFixed(1)} km`} ${state.source === 'device' ? 'away' : 'from search centre'}`;
}
function reportAge(station, fuel=state.fuel) {
  const observed = Date.parse(station.observedAt?.[fuel]);
  if (Number.isFinite(observed)) return Math.max(0,Math.floor((Date.now()-observed)/60000));
  const age = station.ages?.[fuel];
  return Number.isFinite(age) ? Math.max(0,age)+Math.max(0,Math.floor((Date.now()-state.loadedAt)/60000)) : null;
}
function ageLabel(age) {
  if (age == null) return 'Time unavailable';
  if (age === 0) return 'Just reported';
  if (age < 60) return `${Math.floor(age)} min ago`;
  if (age < 1440) return `${Math.floor(age/60)} hr ago`;
  // Older reports in days and hours, such as 13 days 3 hr, not a rounded-down day count.
  const days=Math.floor(age/1440), hours=Math.floor(age%1440/60);
  return `${days} ${days===1?'day':'days'}${hours?` ${hours} hr`:''} ago`;
}
function normalize(records) {
  if (!Array.isArray(records)) throw Error('Station data could not be read. Try refreshing.');
  const ids = new Set();
  return records.filter(s => s && typeof s.id==='string' && /^osm-(node|way|relation)-\d+$/.test(s.id) && !s.synthetic && typeof s.name==='string' && Number.isFinite(s.latitude) && Math.abs(s.latitude)<=90 && Number.isFinite(s.longitude) && Math.abs(s.longitude)<=180 && !ids.has(s.id) && ids.add(s.id)).map(s => ({
    ...s, prices:Object.fromEntries(grades.map(f => [f,Number.isInteger(s.prices?.[f]) && s.prices[f]>=500 && s.prices[f]<=3999 ? s.prices[f] : null])),
    address:typeof s.address==='string' ? s.address : '', ages:s.ages||{}, observedAt:s.observedAt||{},
  }));
}
/** Shows this browser's reports from the last 30 seconds where the list has no price for that fuel or an older one. */
function withOwnReports(stations) {
  const now=Date.now(), kept=ownReports.length;
  ownReports=ownReports.filter(r => r && typeof r.station_id==='string' && grades.includes(r.fuel_type) && Number.isInteger(r.price_milli) && now-r.confirmedAt>=0 && now-r.confirmedAt<OWN_REPORT_MS);
  // An expired report is not kept in storage either.
  if (ownReports.length!==kept) storage.write('own-reports',ownReports.length?ownReports:null);
  for (const report of ownReports) {
    const station=stations.find(s => s.id===report.station_id), fuel=report.fuel_type;
    // A price someone else observed after this report wins.
    if (!station || station.prices[fuel]!=null && Date.parse(station.observedAt[fuel])>=Date.parse(report.observed_at)) continue;
    station.prices[fuel]=report.price_milli;station.observedAt[fuel]=report.observed_at;station.ages[fuel]=0;
    station.priceSources={...station.priceSources,[fuel]:'community-unverified'};
  }
  return stations;
}
// Official builds can set a donation page for the database and map costs; without one no donate UI appears.
const donateURL=document.querySelector('meta[name="openfuel-donate-url"]')?.content||'';
document.querySelectorAll('.donate-link').forEach(link=>{if(donateURL){link.hidden=false;if(link.href!==undefined)link.href=donateURL;}});
/** The database reached its daily allowance: the Worker's own cap, or Cloudflare's daily request limit. */
class ServiceLimit extends Error {
  constructor(resetsAt) { super('OpenFuel reached its free daily database limit.'); this.name='ServiceLimit'; this.resetsAt=resetsAt; }
}
const nextUtcMidnight=()=>{const now=new Date();return new Date(Date.UTC(now.getUTCFullYear(),now.getUTCMonth(),now.getUTCDate()+1));};
async function api(path, options={}) {
  const controller=new AbortController(), timer=setTimeout(()=>controller.abort(),20000);
  try {
    // Asking for JSON also gets Cloudflare's own errors as JSON, so its 1015 and 1027 can be told apart.
    const response=await fetch(`/api/v1/${path}`,{...options,headers:{Accept:'application/json',...options.headers},signal:controller.signal});
    const body=await response.json().catch(()=>null);
    if (body?.error==='spending_cap') throw new ServiceLimit(new Date(body.resets_at||nextUtcMidnight()));
    // Cloudflare answers the daily Worker request limit itself (error 1027): as problem JSON when asked for
    // JSON, otherwise as a non-JSON 429 page. Its other 429s, such as 1015 rate limiting, are not the limit.
    if (response.status===429 && (!body || body.error_code===1027 || body.error_name==='workers_daily_limit')) throw new ServiceLimit(nextUtcMidnight());
    if (!response.ok) throw Error(body?.message || (response.status===429 ? 'Too many requests. Wait a minute and try again.' : 'Could not reach OpenFuel. Try again.'));
    if (!body) throw Error('The response could not be read. Try again.');
    return body;
  } finally { clearTimeout(timer); }
}
function coarseCenter(center=state.center) { return {lat:Number(center.lat.toFixed(2)),lon:Number(center.lon.toFixed(2))}; }
function cacheKey(center=state.center) { return `${center.lat.toFixed(2)},${center.lon.toFixed(2)}:${state.radius}`; }
function rememberArea() {
  if(!state.center)return;
  const previous=storage.read('last-area',null);
  const label=state.source==='search'?state.label:state.source==='previous'&&typeof previous?.label==='string'?previous.label:'Your last searched area';
  storage.write('last-area',{...coarseCenter(),label,radius:state.radius,fuel:state.fuel});
}
function cacheCurrent() {
  if (!state.center) return;
  const existing=storage.read('areas-v2', []), areas=Array.isArray(existing) ? existing : [];
  // Server distances could reconstruct a precise device position when combined
  // with public station coordinates. Recalculate them from the chosen centre.
  const stations=state.stations.map(({distanceMetres,...station})=>station);
  const item={key:cacheKey(),stations,loadedAt:state.loadedAt,coverage:state.coverage};
  storage.write('areas-v2',[item,...areas.filter(a=>a.key!==item.key)].slice(0,4));
}
function restoreArea() {
  const existing=storage.read('areas-v2', []);
  const area=Array.isArray(existing) && existing.find(a=>a.key===cacheKey());
  if (!area || !Number.isFinite(area.loadedAt)) return false;
  try { state.stations=withOwnReports(normalize(area.stations));state.loadedAt=area.loadedAt;state.coverage=area.coverage;return true; } catch { return false; }
}
/** Shows the saved area and its snapshot without asking the server; the caller decides when to load it. */
function restoreLastArea() {
  const last=storage.read('last-area',null);
  if(!last||!Number.isFinite(last.lat)||Math.abs(last.lat)>90||!Number.isFinite(last.lon)||Math.abs(last.lon)>180||![5000,10000,25000,50000].includes(last.radius))return false;
  state.radius=last.radius;state.fuel=grades.includes(last.fuel)?last.fuel:'regular';$('radius').value=String(state.radius);
  const label=typeof last.label==='string'&&last.label.length<=160?last.label:'Your last searched area';
  const center=coarseCenter(last);
  chooseLocation(center.lat,center.lon,`${label} · saved area`,'previous',false,false);
  return true;
}
const snapshotFresh=()=>{const age=Date.now()-state.loadedAt;return age>=0&&age<FRESH_SNAPSHOT_MS;};
/** Loads the chosen area unless its snapshot is fresh enough to show as it is. */
function loadArea() {
  if(!snapshotFresh()){refreshStations();return;}
  // An answer still on its way (for the radius just left, say) must not replace this snapshot.
  state.generation++;pendingRequest=null;
  stopWaitingForFix();state.connection='online';state.loadFailed=false;render();
}
function stopWaitingForFix() { clearTimeout(fixTimer);fixTimer=null; }
function visibleStations() {
  return state.stations.filter(s => !state.saved || favorites.has(s.id)).sort((a,b) => {
    if (state.sort==='price') {
      const ap=a.prices[state.fuel],bp=b.prices[state.fuel];
      if (ap==null && bp!=null) return 1;
      if (bp==null && ap!=null) return -1;
      if (ap!=null && bp!=null && ap!==bp) return ap-bp;
    }
    return distance(state.center.lat,state.center.lon,a.latitude,a.longitude)-distance(state.center.lat,state.center.lon,b.latitude,b.longitude);
  });
}
function renderStatus() {
  $('refresh-button').disabled=!state.center || state.connection==='loading';
  $('refresh-button').textContent=state.connection==='loading' ? 'Loading…' : 'Refresh';
  const count=visibleStations().length;
  $('results-title').textContent=state.saved ? 'Saved stations' : 'Fuel near you';
  let text='Real stations. Community pump prices.';
  if (state.connection==='loading') text=state.loadedAt?'Saved station details · Refreshing…':'Finding real stations in this area…';
  if (state.connection==='online') text=`${count} station${count===1?'':'s'}${state.coverage?.truncated?' · Narrow the radius for all results':''} · Prices shown only when reported`;
  if (state.connection==='offline') text=state.loadedAt ? `Saved station details · Last updated ${new Date(state.loadedAt).toLocaleString()}` : 'Unable to load stations. Check your connection and refresh.';
  if (state.connection==='limited') text=state.loadedAt ? `Saved station details · Last updated ${new Date(state.loadedAt).toLocaleString()}` : 'Live station details are paused for today.';
  $('connection-status').textContent=text;
  $('connection-status').classList.toggle('offline',state.connection==='offline'||state.connection==='limited');
  $('limit-banner').hidden=state.connection!=='limited';
  if(state.connection==='limited')$('limit-text').textContent=`OpenFuel's database reached its free daily limit, so live prices are paused until about ${state.limitResetsAt.toLocaleTimeString([], {hour:'numeric',minute:'2-digit'})}.${state.loadedAt?' Saved prices still show.':''}${donateURL?' Donations pay for more database capacity.':''}`;
}
function render() {
  renderStatus();
  document.querySelectorAll('[data-fuel]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.fuel===state.fuel)));
  $('saved-button').setAttribute('aria-pressed',String(state.saved));
  cancelAnimationFrame(markerFrame);
  if (!state.center) { markerLayer.clearLayers();return; }
  const list=visibleStations();
  if (!list.length) {
    const title=state.saved ? 'No saved stations in this area.' : state.connection==='loading' ? 'Finding fuel nearby…' : state.connection==='offline' ? 'No saved details for this area.' : state.connection==='limited' ? 'Live prices are paused for today.' : 'No stations found here yet.';
    const help=state.saved ? 'Open a station and save it to keep it handy.' : state.connection==='offline' ? 'Reconnect and refresh, or return to an area you already searched.' : state.connection==='limited' ? 'Areas you searched before still show their saved details.' : 'Try a larger radius, move the map, or search another Canadian city.';
    $('station-list').innerHTML=`<div class="empty-state"><span class="empty-icon">⌕</span><h2>${title}</h2><p>${help}</p></div>`;
  } else {
    $('station-list').innerHTML=list.map(s=>{
      const price=s.prices[state.fuel], age=reportAge(s);
      return `<article class="station-card" data-station="${esc(s.id)}">${brandBadge(s)}<button class="station-main" data-detail="${esc(s.id)}" aria-label="View ${esc(s.name)}, ${esc(s.address||distanceLabel(s))}"><span class="station-name">${esc(s.name)}</span><span class="station-address">${esc(s.address||'Address not listed')}</span><span class="station-distance">${esc(distanceLabel(s))} · straight-line</span></button><button class="station-price" data-${price==null?'report':'detail'}="${esc(s.id)}" aria-label="${price==null?'Report a price for':`${cents(price)} cents per litre at`} ${esc(s.name)}">${price==null?'<strong class="missing">No price yet</strong><span class="report-label">Report price</span>':`<strong>${cents(price)}</strong><small>¢/L · ${esc(ageLabel(age))}</small><small>Unverified${age>=1440?' · stale':''}</small>`}</button></article>`;
    }).join('');
  }
  // The list paints first; the map markers follow in the next frame.
  markerFrame=requestAnimationFrame(()=>{
    markerLayer.clearLayers();
    for (const s of list) {
      const price=s.prices[state.fuel], title=`${s.name}: ${price==null ? 'no reported price' : `${cents(price)} cents per litre, unverified`}`;
      const badge=brandBadge(s,true),width=86;
      const marker=L.marker([s.latitude,s.longitude],{title,alt:title,icon:L.divIcon({className:`fuel-marker${price==null?' unknown':''}`,html:`<span class="marker-pill">${badge}<span>${price==null?'Fuel':cents(price)}</span></span>`,iconSize:[width,36],iconAnchor:[width/2,40]})});
      marker.on('click',()=>openDetails(s.id));marker.addTo(markerLayer);
      marker.getElement()?.setAttribute('aria-label',title);
    }
  });
}
async function refreshStations(fresh=false) {
  if (!state.center) return;
  stopWaitingForFix();
  const generation=++state.generation, reportVersion=state.reportVersion;
  const center={...state.center},radius=state.radius;
  pendingRequest={generation,reportVersion,key:cacheKey(center)};
  state.connection='loading';render();
  try {
    const query=new URLSearchParams({lat:center.lat.toFixed(6),lon:center.lon.toFixed(6),radius:String(radius),fuel:state.fuel});
    // Normal loads may reuse the browser's copy for 15 seconds; Refresh, and loads within 15 seconds
    // of this browser's own report, always ask again so the copy cannot hide the new price.
    const response=await api(`stations?${query}`,fresh===true||Date.now()-reportedAt<15000?{cache:'no-cache'}:{});
    if (generation!==state.generation || reportVersion!==state.reportVersion) return;
    if (response.is_demo!==false || response.mode!=='live') throw Error('OpenFuel is still serving sample data. Please try again after the live update.');
    state.stations=withOwnReports(normalize(response.stations));state.coverage=response.coverage;state.loadedAt=Date.now();state.loadFailed=false;state.connection='online';cacheCurrent();render();
  } catch (error) {
    if (generation!==state.generation || reportVersion!==state.reportVersion) return;
    state.loadFailed=true;
    if (error instanceof ServiceLimit) { state.connection='limited';state.limitResetsAt=error.resetsAt;render();return; }
    state.connection='offline';render();toast(error.name==='AbortError' ? 'The request timed out. Try Refresh.' : error.message);
  } finally {
    if (pendingRequest?.generation===generation) pendingRequest=null;
  }
}
function closePlaceChoices() {
  $('search-results').hidden=true;$('area-selector').setAttribute('aria-expanded','false');
}
function showAreaChoices() {
  if($('area-selector').getAttribute('aria-expanded')==='true'){closePlaceChoices();return;}
  searchGeneration++;
  $('search-results').innerHTML='<p class="search-message">Choose where to find fuel.</p><button class="search-result" data-area-action="city">Search a Canadian city or coordinates</button><button class="search-result" data-area-action="device">Use my device location</button><button class="search-result" data-area-action="map">Use the visible map area</button>';
  $('search-results').hidden=false;$('area-selector').setAttribute('aria-expanded','true');
}
function chooseLocation(lat,lon,label,source='search',remember=true,load=true) {
  if (!Number.isFinite(lat)||Math.abs(lat)>90||!Number.isFinite(lon)||Math.abs(lon)>180) return;
  // A city or map choice wins over an earlier, still-pending device request.
  locationGeneration++;searchGeneration++;locatePending=false;$('locate-button').disabled=false;stopWaitingForFix();
  // A choice in the same area as the station request already on its way (a device fix in the saved
  // area, for example) keeps that request instead of sending another, unless this browser's report was
  // confirmed since it was sent: its answer is then discarded.
  const requested=pendingRequest?.generation===state.generation&&pendingRequest.reportVersion===state.reportVersion&&pendingRequest.key===cacheKey({lat,lon});
  if(!requested)state.generation++;
  state.center={lat,lon};state.source=source;state.label=label;state.stations=[];state.loadedAt=0;state.selected=null;state.coverage=null;
  $('location-status').textContent=label;
  const shortLabel=source==='device'?'Nearby':source==='search'?label.split(',')[0]:source==='previous'?'Saved area':'Map area';
  $('area-label').textContent=shortLabel;$('area-selector').setAttribute('aria-label',`Choose location, currently ${shortLabel}`);$('area-selector').title=label;
  closePlaceChoices();
  map.setView([lat,lon],13,{animate:false});
  map.panBy(map.getSize().divideBy(2).subtract(mapFocusPoint()),{animate:false});$('search-area').hidden=true;
  if(remember)rememberArea();
  restoreArea();
  if(load&&!requested)loadArea();else render();
  // After the station request, so the prices go out first.
  startBaseMap();
}
function locate(automatic=false) {
  if (locatePending) return;
  if (!navigator.geolocation) { $('location-status').textContent='Location is unavailable in this browser. Search a city instead.';startBaseMap();return; }
  const generation=++locationGeneration;
  locatePending=true;$('locate-button').disabled=true;
  $('location-status').textContent=state.center?`${state.label} · Checking device location…`:'Your browser will ask for location. You can search a city instead.';
  // Station areas are kilometres wide, so a coarse fix is enough and arrives sooner. The automatic fix
  // on entry may be up to five minutes old; a tap asks for the current location, at most a minute old.
  navigator.geolocation.getCurrentPosition(position=>{
    if(generation!==locationGeneration)return;
    locatePending=false;$('locate-button').disabled=false;
    const {latitude,longitude,accuracy}=position.coords;
    state.user={lat:latitude,lon:longitude,accuracy};
    locationLayer.clearLayers();
    L.circle([latitude,longitude],{radius:Math.max(1,accuracy),color:'#2e7edb',weight:1,fillOpacity:.08,interactive:false}).addTo(locationLayer);
    L.marker([latitude,longitude],{title:'Your device location',alt:'Your device location',icon:L.divIcon({className:'user-location',iconSize:[18,18],iconAnchor:[9,9]})}).bindTooltip(`Your location · accurate to about ${Math.round(accuracy)} m`).addTo(locationLayer);
    chooseLocation(latitude,longitude,`Your location · accurate to about ${Math.round(accuracy)} m`,'device');
  },error=>{
    if(generation!==locationGeneration)return;
    locatePending=false;$('locate-button').disabled=false;
    const reason=error.code===1?'Location permission is off. Search a city, or enable location in your browser.':error.code===3?'Location took too long. Try again or search a city.':'Could not find your device location. Try again or search a city.';
    $('location-status').textContent=state.center ? `${state.label} · ${error.code===1?'Device location off':'Device location unavailable'}` : reason;
    // Without a fix a return visit loads its saved area now, and a first visit shows the Canada overview.
    if(fixTimer)loadArea();
    startBaseMap();
  },{enableHighAccuracy:false,timeout:15000,maximumAge:automatic?300000:60000});
}
async function searchPlaces(event) {
  event.preventDefault();const query=$('place-search').value.trim(), generation=++searchGeneration;
  if (!query) { closePlaceChoices();$('place-search').focus();return; }
  const coordinateMatch=/^(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)$/.exec(query);
  if (coordinateMatch) {
    const lat=Number(coordinateMatch[1]),lon=Number(coordinateMatch[2]);
    if (Math.abs(lat)<=90 && Math.abs(lon)<=180) { chooseLocation(lat,lon,`Searched coordinates · ${lat.toFixed(4)}, ${lon.toFixed(4)}`,'coordinates');return; }
  }
  $('search-results').hidden=false;$('search-results').innerHTML='<p class="search-message" role="status">Searching Canadian places…</p>';
  try {
    const data=await api(`geocode?q=${encodeURIComponent(query)}`);
    if (generation!==searchGeneration) return;
    const results=Array.isArray(data.results) ? data.results.filter(p=>typeof p.name==='string'&&Number.isFinite(p.latitude)&&Math.abs(p.latitude)<=90&&Number.isFinite(p.longitude)&&Math.abs(p.longitude)<=180) : [];
    $('search-results').innerHTML=results.length ? results.map((p,i)=>`<button class="search-result" data-place="${i}">${esc(p.name)}</button>`).join('') : '<p class="search-message">No matching Canadian city. Try a nearby city, coordinates such as “53.54, -113.49”, or move the map and choose Search this area.</p>';
    $('search-results').querySelectorAll('[data-place]').forEach(button=>button.addEventListener('click',()=>{const p=results[Number(button.dataset.place)];chooseLocation(p.latitude,p.longitude,p.name);}));
  } catch(error) { if(generation===searchGeneration)$('search-results').innerHTML=`<p class="search-message" role="alert">${esc(error.name==='AbortError'?'Search timed out.':error.message)} You can also enter latitude, longitude or move the map.</p>`; }
}
function directionsURL(station,provider) {
  const destination=`${station.latitude},${station.longitude}`;
  if (provider==='apple') {const url=new URL('https://maps.apple.com/');url.searchParams.set('daddr',destination);url.searchParams.set('dirflg','d');return url.href;}
  const url=new URL('https://www.google.com/maps/dir/');url.searchParams.set('api','1');url.searchParams.set('destination',destination);url.searchParams.set('travelmode','driving');return url.href;
}
function openDetails(id) {
  const station=state.stations.find(s=>s.id===id);if(!station)return;
  state.selected=id;
  const price=station.prices[state.fuel],age=reportAge(station);
  $('detail-content').innerHTML=`<div class="dialog-head"><h2 id="detail-title">${esc(station.name)}</h2><button class="close-button" data-close aria-label="Close station details">×</button></div><div class="dialog-body"><p class="detail-address">${esc(station.address||'Address not listed in OpenStreetMap')}</p><p class="detail-coordinates">${esc(distanceLabel(station))} · straight-line<br>${station.latitude.toFixed(5)}, ${station.longitude.toFixed(5)}</p><div class="detail-price"><span>${cap(state.fuel)} · standard pump price</span><strong>${cents(price)}${price!=null?'<small>¢/L</small>':''}</strong><p>${price==null?'No price reported yet. Be the first to check the pump.':`${esc(ageLabel(age))} · Unverified community report${age>=1440?' · this price may be out of date.':''}`}</p></div><div class="detail-fuels">${grades.map(f=>`<div>${cap(f)}<strong>${cents(station.prices[f])}${station.prices[f]!=null?' ¢/L':''}</strong></div>`).join('')}</div><button class="primary-button full-width" data-report="${esc(id)}">Share a pump price</button><div class="direction-links"><a class="secondary-button" href="${esc(directionsURL(station,'google'))}" target="_blank" rel="noopener noreferrer">Google Maps</a><a class="secondary-button" href="${esc(directionsURL(station,'apple'))}" target="_blank" rel="noopener noreferrer">Apple Maps</a></div><button class="secondary-button full-width detail-save" data-save="${esc(id)}">${favorites.has(id)?'♥ Saved — remove':'♡ Save station'}</button><p class="detail-note">Station details from <a href="https://www.openstreetmap.org/${id.replace('osm-','').replace('-', '/')}" target="_blank" rel="noopener">OpenStreetMap</a>. Prices are unverified community observations. Hours and amenities may be incomplete. Confirm the price at the pump.</p></div>`;
  if(!$('detail-dialog').open)$('detail-dialog').showModal();
}
function openReport(id) {
  const station=state.stations.find(s=>s.id===id);if(!station)return;
  reportStation=id;$('detail-dialog').close();
  $('report-form').reset();$('report-fuel').value=state.fuel;$('report-station').textContent=`${station.name} · ${station.address||`${station.latitude.toFixed(5)}, ${station.longitude.toFixed(5)}`}`;
  $('report-error').hidden=true;$('report-price').removeAttribute('aria-invalid');$('report-dialog').showModal();
}
async function submitReport(event) {
  event.preventDefault();const station=state.stations.find(s=>s.id===reportStation),fuel=$('report-fuel').value;if(!station||!grades.includes(fuel))return;
  const match=/^(\d{1,3})(?:\.(\d))?$/.exec($('report-price').value.trim());
  const value=match ? Number(match[1])*10+Number(match[2]||0) : NaN;
  if (!Number.isInteger(value)||value<500||value>3999) { $('report-error').textContent='Enter 50.0 to 399.9 cents per litre, with at most one decimal.';$('report-error').hidden=false;$('report-price').setAttribute('aria-invalid','true');return; }
  if(!$('report-observed').checked)return;
  const button=$('submit-report');if(button.disabled)return;
  if(pendingReport?.station_id!==station.id||pendingReport?.fuel_type!==fuel||pendingReport?.price_milli!==value||pendingReport?.client_id!==clientId||typeof pendingReport?.request_id!=='string'||!/^[a-zA-Z0-9_-]{16,80}$/.test(pendingReport.request_id))pendingReport={station_id:station.id,fuel_type:fuel,price_milli:value,client_id:clientId,request_id:crypto.randomUUID()};
  const request={...pendingReport};storage.write('pending-report',request);button.disabled=true;button.textContent='Sharing…';$('report-error').hidden=true;
  try {
    const response=await api('reports',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(request)});
    if(!response.ok||response.is_demo!==false||response.report?.id!==request.request_id||response.report?.station_id!==station.id||response.report?.fuel_type!==fuel||response.report?.price_milli!==value)throw Error('No confirmation received. Refresh before trying again.');
    state.reportVersion++;reportedAt=Date.now();pendingReport=null;storage.write('pending-report',null);
    ownReports=[...ownReports,{station_id:station.id,fuel_type:fuel,price_milli:value,observed_at:response.report.observed_at,confirmedAt:reportedAt}];
    withOwnReports(state.stations);storage.write('own-reports',ownReports);state.fuel=fuel;
    // The confirmed report is shown straight away; refetching the area would only cost another request.
    state.connection='online';cacheCurrent();$('report-dialog').close();render();toast('Price shared as an unverified community report.');
  } catch(error) {
    $('report-error').textContent=error.name==='AbortError'?'No confirmation received. Reconnect and retry; your request will not be duplicated.':error instanceof ServiceLimit?`OpenFuel reached its free daily database limit, so reports are paused until about ${error.resetsAt.toLocaleTimeString([], {hour:'numeric',minute:'2-digit'})}.${donateURL?' Donations pay for more capacity.':''}`:error.message;
    $('report-error').hidden=false;
  } finally {button.disabled=false;button.textContent='Share price';}
}
function toast(message) {clearTimeout(toastTimer);$('toast').textContent=message;$('toast').classList.add('visible');toastTimer=setTimeout(()=>$('toast').classList.remove('visible'),6500);}
document.addEventListener('click',event=>{
  const target=event.target.closest('button');if(!target)return;
  if(target.hasAttribute('data-close')){target.closest('dialog').close();return;}
  if(target.dataset.fuel){state.fuel=target.dataset.fuel;rememberArea();render();return;}
  if(target.dataset.detail){openDetails(target.dataset.detail);return;}
  if(target.dataset.report){openReport(target.dataset.report);return;}
  if(target.dataset.save){const id=target.dataset.save;if(favorites.has(id))favorites.delete(id);else favorites.add(id);storage.write('favorites',[...favorites]);openDetails(id);render();}
});
$('area-selector').addEventListener('click',showAreaChoices);
$('search-results').addEventListener('click',event=>{
  const action=event.target.closest('[data-area-action]')?.dataset.areaAction;if(!action)return;
  closePlaceChoices();
  if(action==='city'){$('place-search').focus();$('place-search').select();}
  if(action==='device')locate();
  if(action==='map'){$('search-area').click();}
});
document.addEventListener('keydown',event=>{if(event.key==='Escape'&&!$('search-results').hidden){const wasMenu=$('area-selector').getAttribute('aria-expanded')==='true';closePlaceChoices();if(wasMenu)$('area-selector').focus();}});
document.addEventListener('click',event=>{if(!event.target.closest('.search-header'))closePlaceChoices();});
$('search-form').addEventListener('submit',searchPlaces);
$('place-search').addEventListener('input',()=>{searchGeneration++;closePlaceChoices();});
$('place-search').addEventListener('keydown',event=>{if(event.key==='Escape')closePlaceChoices();});
$('locate-button').addEventListener('click',()=>locate());$('empty-locate').addEventListener('click',()=>locate());
$('search-area').addEventListener('click',()=>{const center=visibleMapCenter();chooseLocation(center.lat,center.lng,`Map area · ${center.lat.toFixed(3)}, ${center.lng.toFixed(3)}`,'map');});
$('refresh-button').addEventListener('click',()=>refreshStations(true));
$('radius').addEventListener('change',event=>{state.radius=Number(event.target.value);if(state.center){rememberArea();state.stations=[];state.loadedAt=0;restoreArea();loadArea();}});
$('sort').addEventListener('change',event=>{state.sort=event.target.value;render();});
$('saved-button').addEventListener('click',()=>{state.saved=!state.saved;render();});
$('report-form').addEventListener('submit',submitReport);
for(const id of ['about-button','privacy-button'])$(id).addEventListener('click',()=>$('about-dialog').showModal());
$('sheet-toggle').addEventListener('click',()=>{const expanded=document.querySelector('.results-panel').classList.toggle('expanded');$('sheet-toggle').setAttribute('aria-expanded',String(expanded));$('sheet-toggle').setAttribute('aria-label',expanded?'Collapse station list':'Expand station list');});
$('hide-stations').addEventListener('click',()=>hideStations(true));
$('show-stations').addEventListener('click',()=>hideStations(false));
let sheetPointer=null,suppressSheetClick=false;
$('sheet-toggle').addEventListener('pointerdown',event=>{sheetPointer={id:event.pointerId,y:event.clientY};$('sheet-toggle').setPointerCapture(event.pointerId);});
$('sheet-toggle').addEventListener('pointerup',event=>{
  if(!sheetPointer||sheetPointer.id!==event.pointerId)return;
  const delta=event.clientY-sheetPointer.y;sheetPointer=null;
  if(Math.abs(delta)<35)return;
  suppressSheetClick=true;
  if(delta>0)hideStations(true);
  else {document.querySelector('.results-panel').classList.add('expanded');$('sheet-toggle').setAttribute('aria-expanded','true');$('sheet-toggle').setAttribute('aria-label','Collapse station list');}
});
$('sheet-toggle').addEventListener('pointercancel',()=>{sheetPointer=null;});
$('sheet-toggle').addEventListener('click',event=>{if(suppressSheetClick){event.stopImmediatePropagation();suppressSheetClick=false;}},true);
$('clear-local').addEventListener('click',()=>{
  try{Object.keys(localStorage).filter(key=>key.startsWith('openfuel-')).forEach(key=>localStorage.removeItem(key));}catch{}
  state.generation++;locationGeneration++;locatePending=false;$('locate-button').disabled=false;stopWaitingForFix();
  if(state.connection==='loading')state.connection='idle';
  favorites.clear();pendingReport=null;ownReports=[];clientId=crypto.randomUUID();storage.write('client-id',clientId);state.saved=false;render();toast('Saved stations and cached areas cleared from this browser.');
});
document.querySelectorAll('dialog').forEach(dialog=>dialog.addEventListener('click',event=>{if(event.target===dialog){const rect=dialog.getBoundingClientRect();if(event.clientX<rect.left||event.clientX>rect.right||event.clientY<rect.top||event.clientY>rect.bottom)dialog.close();}}));
// Reconnecting reloads only when the last load failed or the data is over a minute old. A mobile
// connection can drop and return several times in a row, so the check waits for it to settle.
addEventListener('online',()=>{clearTimeout(onlineTimer);onlineTimer=setTimeout(()=>{
  if(!state.center||state.connection==='loading')return;
  if(state.loadFailed||Date.now()-state.loadedAt>60000)refreshStations();
  else if(state.connection==='offline'){state.connection='online';render();}
},2000);});
addEventListener('offline',()=>{clearTimeout(onlineTimer);if(state.center){if(state.connection==='loading')state.loadFailed=true;state.generation++;state.connection='offline';render();}});
setInterval(()=>{if(!document.hidden&&state.center)render();},60000);
if('serviceWorker' in navigator)navigator.serviceWorker.register('./sw.js').catch(()=>{});
function locationGranted() {
  try { return navigator.permissions.query({name:'geolocation'}).then(status=>status.state==='granted',()=>false); } catch { return Promise.resolve(false); }
}
// A one-shot request on entry lets the browser own the permission decision.
// Denial never substitutes a fictional location or a bundled sample station.
// A return visit shows its saved area at once and sends one station request. With location already
// granted it waits up to 3 seconds for the fix and loads that area (the saved one when the fix rounds
// to it); otherwise it loads the saved area. A snapshot under 30 seconds old is not asked for again.
if(!restoreLastArea())requestAnimationFrame(()=>locate(true));
else locationGranted().then(granted=>{
  if(state.source!=='previous')return;
  if(granted&&!snapshotFresh()){state.connection='loading';render();fixTimer=setTimeout(loadArea,3000);}
  else loadArea();
  requestAnimationFrame(()=>locate(true));
});
})();
