/* SPDX-License-Identifier: AGPL-3.0-only */
const CACHE='openfuel-live-shell-v4';
const SHELL=['./','./index.html','./app.js','./styles.css','./vendor/leaflet.js','./vendor/leaflet.css','/brand/openfuel-wordmark-light.svg','/favicon.svg'];
self.addEventListener('install',event=>event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(SHELL)).then(()=>self.skipWaiting())));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(key=>(key.startsWith('openfuel-preview-')||key.startsWith('openfuel-live-shell-'))&&key!==CACHE).map(key=>caches.delete(key)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',event=>{
  const request=event.request,url=new URL(request.url);
  // Only the listed application shell and OpenFuel identity files are cached. Never intercept map tiles,
  // location queries, price reports, geocoding, downloads, or other origins.
  if(request.method!=='GET'||url.origin!==self.location.origin||!SHELL.some(path=>new URL(path,self.location.href).pathname===url.pathname))return;
  event.respondWith(fetch(request).then(response=>{if(response.ok)event.waitUntil(caches.open(CACHE).then(cache=>cache.put(request,response.clone())));return response;}).catch(()=>caches.match(request).then(response=>response||Response.error())));
});
