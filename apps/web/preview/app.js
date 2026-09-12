/* SPDX-License-Identifier: AGPL-3.0-only
 * Real station map. Leaflet 1.9.4 is bundled with its BSD-2-Clause licence.
 * OSM tiles are fetched only for the visible map and use normal HTTP caching.
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
};
const saved = storage.read('favorites', []);
const favorites = new Set(Array.isArray(saved) ? saved.filter(id => typeof id === 'string') : []);
const state = {fuel:'regular', radius:10000, sort:'distance', saved:false, center:null, user:null, source:null, label:'', stations:[], selected:null, loadedAt:0, connection:'idle', generation:0, reportVersion:0, coverage:null};
let clientId = storage.read('client-id', null);
if (typeof clientId !== 'string' || !/^[a-zA-Z0-9_-]{16,80}$/.test(clientId)) { clientId = crypto.randomUUID(); storage.write('client-id', clientId); }
let pendingReport = storage.read('pending-report', null), reportStation = null, locatePending = false, locationGeneration = 0, searchGeneration = 0, toastTimer;
const map = L.map('map', {zoomControl:false, preferCanvas:true}).setView([57, -106], 4);
L.control.zoom({position:'bottomright'}).addTo(map);
L.control.scale({position:'bottomleft', imperial:false}).addTo(map);
const tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom:19, minZoom:3, updateWhenIdle:true, keepBuffer:1,
  attribution:'&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap contributors</a>',
}).addTo(map);
// Public OSM tiles are not stored in our service worker, bulk downloaded, or prefetched.
tiles.on('tileerror', () => { $('tile-warning').hidden = false; });
tiles.on('tileload', () => { $('tile-warning').hidden = true; });
const markerLayer = L.layerGroup().addTo(map), locationLayer = L.layerGroup().addTo(map);
function mapFocusPoint() {
  const size=map.getSize(),panel=document.querySelector('.results-panel').getBoundingClientRect();
  if(matchMedia('(max-width:760px)').matches) {
    const top=document.querySelector('.search-header').getBoundingClientRect().bottom+18;
    return L.point(size.x/2,Math.max(top+30,(top+panel.top)/2));
  }
  return L.point((panel.right+size.x)/2,size.y/2);
}
function visibleMapCenter() { return map.containerPointToLatLng(mapFocusPoint()); }
map.on('dragend zoomend', () => {
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
  return `${Math.floor(age/1440)} days ago`;
}
function normalize(records) {
  if (!Array.isArray(records)) throw Error('Station data could not be read. Try refreshing.');
  const ids = new Set();
  return records.filter(s => s && typeof s.id==='string' && /^osm-(node|way|relation)-\d+$/.test(s.id) && !s.synthetic && typeof s.name==='string' && Number.isFinite(s.latitude) && Math.abs(s.latitude)<=90 && Number.isFinite(s.longitude) && Math.abs(s.longitude)<=180 && !ids.has(s.id) && ids.add(s.id)).map(s => ({
    ...s, prices:Object.fromEntries(grades.map(f => [f,Number.isInteger(s.prices?.[f]) && s.prices[f]>=500 && s.prices[f]<=3999 ? s.prices[f] : null])),
    address:typeof s.address==='string' ? s.address : '', ages:s.ages||{}, observedAt:s.observedAt||{},
  }));
}
async function api(path, options={}) {
  const controller=new AbortController(), timer=setTimeout(()=>controller.abort(),20000);
  try {
    const response=await fetch(`/api/v1/${path}`,{...options,cache:'no-store',signal:controller.signal});
    const body=await response.json().catch(()=>null);
    if (!response.ok) throw Error(body?.message || (response.status===429 ? 'Too many requests. Wait a minute and try again.' : 'Could not reach OpenFuel. Try again.'));
    if (!body) throw Error('The response could not be read. Try again.');
    return body;
  } finally { clearTimeout(timer); }
}
function cacheKey(center=state.center) { return `${center.lat.toFixed(3)},${center.lon.toFixed(3)}:${state.radius}`; }
function cacheCurrent() {
  if (!state.center) return;
  const existing=storage.read('areas', []), areas=Array.isArray(existing) ? existing : [];
  const item={key:cacheKey(),stations:state.stations,loadedAt:state.loadedAt,coverage:state.coverage};
  storage.write('areas',[item,...areas.filter(a=>a.key!==item.key)].slice(0,4));
}
function restoreArea() {
  const existing=storage.read('areas', []);
  const area=Array.isArray(existing) && existing.find(a=>a.key===cacheKey());
  if (!area || !Number.isFinite(area.loadedAt)) return false;
  try { state.stations=normalize(area.stations);state.loadedAt=area.loadedAt;state.coverage=area.coverage;return true; } catch { return false; }
}
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
  if (state.connection==='loading') text='Finding real stations in this area…';
  if (state.connection==='online') text=`${count} station${count===1?'':'s'}${state.coverage?.truncated?' · Narrow the radius for all results':''} · Prices shown only when reported`;
  if (state.connection==='offline') text=state.loadedAt ? `Saved station details · Last updated ${new Date(state.loadedAt).toLocaleString()}` : 'Unable to load stations. Check your connection and refresh.';
  $('connection-status').textContent=text;
  $('connection-status').classList.toggle('offline',state.connection==='offline');
}
function render() {
  renderStatus();
  document.querySelectorAll('[data-fuel]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.fuel===state.fuel)));
  $('saved-button').setAttribute('aria-pressed',String(state.saved));
  markerLayer.clearLayers();
  if (!state.center) return;
  const list=visibleStations();
  if (!list.length) {
    const title=state.saved ? 'No saved stations in this area.' : state.connection==='loading' ? 'Finding fuel nearby…' : state.connection==='offline' ? 'No saved details for this area.' : 'No stations found here yet.';
    const help=state.saved ? 'Open a station and save it to keep it handy.' : state.connection==='offline' ? 'Reconnect and refresh, or return to an area you already searched.' : 'Try a larger radius, move the map, or search another Canadian city.';
    $('station-list').innerHTML=`<div class="empty-state"><span class="empty-icon">⌕</span><h2>${title}</h2><p>${help}</p></div>`;
  } else {
    $('station-list').innerHTML=list.map(s=>{
      const price=s.prices[state.fuel], age=reportAge(s);
      return `<article class="station-card" data-station="${esc(s.id)}"><span class="station-initial" aria-hidden="true">${esc(s.name.slice(0,2).toUpperCase())}</span><button class="station-main" data-detail="${esc(s.id)}" aria-label="View ${esc(s.name)}, ${esc(s.address||distanceLabel(s))}"><span class="station-name">${esc(s.name)}</span><span class="station-address">${esc(s.address||'Address not listed')}</span><span class="station-distance">${esc(distanceLabel(s))} · straight-line</span></button><button class="station-price" data-${price==null?'report':'detail'}="${esc(s.id)}" aria-label="${price==null?'Report a price for':`${cents(price)} cents per litre at`} ${esc(s.name)}">${price==null?'<strong class="missing">No price yet</strong><span class="report-label">Report price</span>':`<strong>${cents(price)}</strong><small>¢/L · ${esc(ageLabel(age))}</small><small>Unverified${age>=1440?' · stale':''}</small>`}</button></article>`;
    }).join('');
  }
  for (const s of list) {
    const price=s.prices[state.fuel], title=`${s.name}: ${price==null ? 'no reported price' : `${cents(price)} cents per litre, unverified`}`;
    const marker=L.marker([s.latitude,s.longitude],{title,alt:title,icon:L.divIcon({className:`fuel-marker${price==null?' unknown':''}`,html:`<span class="marker-pill">${price==null?'Fuel':cents(price)}</span>`,iconSize:[58,34],iconAnchor:[29,38]})});
    marker.on('click',()=>openDetails(s.id));marker.addTo(markerLayer);
    marker.getElement()?.setAttribute('aria-label',title);
  }
}
async function refreshStations() {
  if (!state.center) return;
  const generation=++state.generation, reportVersion=state.reportVersion;
  const center={...state.center},radius=state.radius;
  state.connection='loading';render();
  try {
    const query=new URLSearchParams({lat:center.lat.toFixed(6),lon:center.lon.toFixed(6),radius:String(radius),fuel:state.fuel});
    const response=await api(`stations?${query}`);
    if (generation!==state.generation || reportVersion!==state.reportVersion) return;
    if (response.is_demo!==false || response.mode!=='live') throw Error('OpenFuel is still serving sample data. Please try again after the live update.');
    state.stations=normalize(response.stations);state.coverage=response.coverage;state.loadedAt=Date.now();state.connection='online';cacheCurrent();render();
  } catch (error) {
    if (generation!==state.generation || reportVersion!==state.reportVersion) return;
    state.connection='offline';render();toast(error.name==='AbortError' ? 'The request timed out. Try Refresh.' : error.message);
  }
}
function chooseLocation(lat,lon,label,source='search') {
  if (!Number.isFinite(lat)||Math.abs(lat)>90||!Number.isFinite(lon)||Math.abs(lon)>180) return;
  // A city or map choice wins over an earlier, still-pending device request.
  locationGeneration++;searchGeneration++;locatePending=false;$('locate-button').disabled=false;
  state.generation++;state.center={lat,lon};state.source=source;state.label=label;state.stations=[];state.loadedAt=0;state.selected=null;state.coverage=null;
  $('location-status').textContent=label;
  $('search-results').hidden=true;
  map.setView([lat,lon],13,{animate:false});
  map.panBy(map.getSize().divideBy(2).subtract(mapFocusPoint()),{animate:false});$('search-area').hidden=true;
  restoreArea();refreshStations();
}
function locate() {
  if (locatePending) return;
  if (!navigator.geolocation) { $('location-status').textContent='Location is unavailable in this browser. Search a city instead.';return; }
  const generation=++locationGeneration;
  locatePending=true;$('locate-button').disabled=true;
  $('location-status').textContent='Your browser will ask for location. You can search a city instead.';
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
    $('location-status').textContent=state.center ? `${state.label}. ${reason}` : reason;
  },{enableHighAccuracy:true,timeout:15000,maximumAge:60000});
}
async function searchPlaces(event) {
  event.preventDefault();const query=$('place-search').value.trim(), generation=++searchGeneration;
  if (!query) { $('search-results').hidden=true;$('place-search').focus();return; }
  const coordinateMatch=/^(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)$/.exec(query);
  if (coordinateMatch) {
    const lat=Number(coordinateMatch[1]),lon=Number(coordinateMatch[2]);
    if (Math.abs(lat)<=90 && Math.abs(lon)<=180) { chooseLocation(lat,lon,`Searched coordinates · ${lat.toFixed(4)}, ${lon.toFixed(4)}`);return; }
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
    state.reportVersion++;pendingReport=null;storage.write('pending-report',null);
    station.prices[fuel]=value;station.observedAt[fuel]=response.report.observed_at;station.ages[fuel]=0;state.fuel=fuel;
    state.connection='online';cacheCurrent();$('report-dialog').close();render();toast('Price shared as an unverified community report.');refreshStations();
  } catch(error) {
    $('report-error').textContent=error.name==='AbortError'?'No confirmation received. Reconnect and retry; your request will not be duplicated.':error.message;
    $('report-error').hidden=false;
  } finally {button.disabled=false;button.textContent='Share price';}
}
function toast(message) {clearTimeout(toastTimer);$('toast').textContent=message;$('toast').classList.add('visible');toastTimer=setTimeout(()=>$('toast').classList.remove('visible'),6500);}
document.addEventListener('click',event=>{
  const target=event.target.closest('button');if(!target)return;
  if(target.hasAttribute('data-close')){target.closest('dialog').close();return;}
  if(target.dataset.fuel){state.fuel=target.dataset.fuel;render();return;}
  if(target.dataset.detail){openDetails(target.dataset.detail);return;}
  if(target.dataset.report){openReport(target.dataset.report);return;}
  if(target.dataset.save){const id=target.dataset.save;if(favorites.has(id))favorites.delete(id);else favorites.add(id);storage.write('favorites',[...favorites]);openDetails(id);render();}
});
$('search-form').addEventListener('submit',searchPlaces);
$('place-search').addEventListener('input',()=>{searchGeneration++;$('search-results').hidden=true;});
$('place-search').addEventListener('keydown',event=>{if(event.key==='Escape')$('search-results').hidden=true;});
$('locate-button').addEventListener('click',locate);$('empty-locate').addEventListener('click',locate);
$('search-area').addEventListener('click',()=>{const center=visibleMapCenter();chooseLocation(center.lat,center.lng,`Map area · ${center.lat.toFixed(3)}, ${center.lng.toFixed(3)}`,'map');});
$('refresh-button').addEventListener('click',refreshStations);
$('radius').addEventListener('change',event=>{state.radius=Number(event.target.value);if(state.center){state.stations=[];state.loadedAt=0;restoreArea();refreshStations();}});
$('sort').addEventListener('change',event=>{state.sort=event.target.value;render();});
$('saved-button').addEventListener('click',()=>{state.saved=!state.saved;render();});
$('report-form').addEventListener('submit',submitReport);
for(const id of ['about-button','privacy-button'])$(id).addEventListener('click',()=>$('about-dialog').showModal());
$('sheet-toggle').addEventListener('click',()=>{const expanded=document.querySelector('.results-panel').classList.toggle('expanded');$('sheet-toggle').setAttribute('aria-expanded',String(expanded));$('sheet-toggle').setAttribute('aria-label',expanded?'Collapse station list':'Expand station list');});
$('clear-local').addEventListener('click',()=>{
  try{Object.keys(localStorage).filter(key=>key.startsWith('openfuel-')).forEach(key=>localStorage.removeItem(key));}catch{}
  favorites.clear();pendingReport=null;clientId=crypto.randomUUID();storage.write('client-id',clientId);state.saved=false;render();toast('Saved stations and cached areas cleared from this browser.');
});
document.querySelectorAll('dialog').forEach(dialog=>dialog.addEventListener('click',event=>{if(event.target===dialog){const rect=dialog.getBoundingClientRect();if(event.clientX<rect.left||event.clientX>rect.right||event.clientY<rect.top||event.clientY>rect.bottom)dialog.close();}}));
addEventListener('online',refreshStations);
addEventListener('offline',()=>{if(state.center){state.generation++;state.connection='offline';render();}});
setInterval(()=>{if(!document.hidden&&state.center)render();},60000);
if('serviceWorker' in navigator)navigator.serviceWorker.register('./sw.js').catch(()=>{});
// A one-shot request on entry lets the browser own the permission decision.
// Denial never substitutes a fictional location or a bundled sample station.
requestAnimationFrame(()=>locate());
})();
