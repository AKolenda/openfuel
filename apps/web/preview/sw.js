/* SPDX-License-Identifier: AGPL-3.0-only */
// Bump with each release: installing a new worker replaces the whole shell at once, while the
// background refresh below updates files one by one.
const CACHE='openfuel-live-shell-v9';
// MapLibre is not in the page itself; the base map loads it on demand, and listing it here keeps it cached for return visits.
const SHELL=['./','./index.html','./app.js','./styles.css','./vendor/leaflet.js','./vendor/leaflet.css','./vendor/maplibre-gl.js','./vendor/maplibre-gl.css','./vendor/leaflet-maplibre-gl.js','./map/base-map.js','./map/openfuel-style.json','/brand/openfuel-wordmark-light.svg','/favicon.svg'];
self.addEventListener('install',event=>event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(SHELL)).then(()=>self.skipWaiting())));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(key=>(key.startsWith('openfuel-preview-')||key.startsWith('openfuel-live-shell-'))&&key!==CACHE).map(key=>caches.delete(key)))).then(()=>self.clients.claim())));
// A response counts as unchanged only when it has a validator and every validator matches.
const unchanged=(cached,response)=>!!cached&&!!(response.headers.get('etag')||response.headers.get('last-modified'))&&['etag','last-modified','content-length'].every(name=>cached.headers.get(name)===response.headers.get(name));
self.addEventListener('fetch',event=>{
  const request=event.request,url=new URL(request.url);
  // Only the listed application shell and OpenFuel identity files are cached. Never intercept map tiles,
  // location queries, price reports, geocoding, downloads, or other origins.
  if(request.method!=='GET'||url.origin!==self.location.origin||!SHELL.some(path=>new URL(path,self.location.href).pathname===url.pathname))return;
  // Stale-while-revalidate: answer from the cache at once and refresh it in the background,
  // writing to the cache only when the file actually changed. The next visit gets the new copy.
  event.respondWith(caches.open(CACHE).then(cache=>cache.match(request).then(cached=>{
    const network=fetch(request).then(response=>{
      if(response.ok&&!unchanged(cached,response))event.waitUntil(cache.put(request,response.clone()));
      return response;
    });
    // A redirected copy (index.html is redirected to ./) cannot answer a page navigation.
    if(!cached||(cached.redirected&&request.mode==='navigate'))return network.catch(()=>cached||Response.error());
    event.waitUntil(network.catch(()=>{}));
    return cached;
  })));
});
