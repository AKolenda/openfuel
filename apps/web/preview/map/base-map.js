/* SPDX-License-Identifier: AGPL-3.0-only
 * OpenFuel's base map: OpenStreetMap data from OpenFreeMap vector tiles, drawn by MapLibre in
 * OpenFuel's style (a fork of OpenFreeMap Liberty in the manner of CARTO Voyager; see
 * packages/map-style). Without WebGL, or when OpenFreeMap refuses its tiles, OpenStreetMap's
 * standard raster tiles are used instead.
 * Tiles load on demand for the visible map; OpenFuel never prefetches or stores them.
 */
const OPENFREEMAP_TILES = 'https://tiles.openfreemap.org/planet';
const OPENFREEMAP_CREDIT = '<a href="https://openfreemap.org" target="_blank" rel="noopener">OpenFreeMap</a> &copy; <a href="https://www.openmaptiles.org/" target="_blank" rel="noopener">OpenMapTiles</a> · Style after <a href="https://github.com/CartoDB/basemap-styles" target="_blank" rel="noopener">CARTO Voyager</a> · Data &copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap contributors</a>';
const OPENSTREETMAP_CREDIT = '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap contributors</a>';
// MapLibre (about 1 MB) loads only when the base map starts, from this site, relative to the page.
const MAPLIBRE_FILES = ['vendor/maplibre-gl.css', 'vendor/maplibre-gl.js', 'vendor/leaflet-maplibre-gl.js'];

/** Adds MapLibre and its Leaflet binding to the page. Resolves true once both run, false if a file fails. */
function loadMapLibre() {
  // The Android map page loads MapLibre itself before this script.
  if (typeof maplibregl !== 'undefined' && L.maplibreGL) return Promise.resolve(true);
  const elements = MAPLIBRE_FILES.map(path => {
    const element = document.createElement(path.endsWith('.css') ? 'link' : 'script');
    // Scripts keep their order: the binding needs MapLibre to have run first.
    if (element.tagName === 'LINK') { element.rel = 'stylesheet'; element.href = path; } else { element.src = path; element.async = false; }
    return element;
  });
  const loaded = Promise.all(elements.map(element => new Promise((resolve, reject) => { element.onload = resolve; element.onerror = reject; })));
  document.head.append(...elements);
  return loaded.then(() => typeof maplibregl !== 'undefined' && !!L.maplibreGL, () => { elements.forEach(element => element.remove()); return false; });
}

/** Adds the base map and reports which one it chose: onReady(layer, 'openfreemap' | 'openstreetmap'). */
function baseMapLayer(map, style, options, onReady) {
  const raster = () => onReady(L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {...options, attribution: OPENSTREETMAP_CREDIT}).addTo(map), 'openstreetmap');
  let webgl = false;
  try { const canvas = document.createElement('canvas'); webgl = !!(canvas.getContext('webgl2') || canvas.getContext('webgl')); } catch {}
  if (!webgl || !style) return raster();
  let pending = false, done = false, library = null;
  const finish = choose => { done = true; map.off('moveend', start); choose(); };
  function vector(styleJSON, index) {
    const glStyle = {...styleJSON, sources: {...styleJSON.sources,
      openmaptiles: {type: 'vector', tiles: index.tiles, minzoom: index.minzoom ?? 0, maxzoom: index.maxzoom ?? 14}}};
    let layer;
    // No padding: MapLibre draws only the visible map, not a margin of extra tiles around it.
    try { layer = L.maplibreGL({style: glStyle, padding: 0, attributionControl: {customAttribution: OPENFREEMAP_CREDIT}}).addTo(map); }
    catch { return raster(); }
    onReady(layer, 'openfreemap');
  }
  function start() {
    if (pending || done) return;
    pending = true;
    // MapLibre, the style and the tile index load in parallel. A refused tile index (an HTTP error or an
    // unreadable index) or map code that will not load switches to raster tiles for the visit; a lost
    // connection leaves the map as it is and retries on the next move.
    if (!library) library = loadMapLibre();
    const styleJSON = typeof style === 'string' ? fetch(style).then(response => response.json()) : style;
    const index = fetch(OPENFREEMAP_TILES).then(response => response.ok ? response.json().catch(() => null) : null);
    Promise.all([library, styleJSON, index])
      .then(([ready, styleJSON, index]) => finish(ready && index ? () => vector(styleJSON, index) : raster))
      // Map code that failed along with the connection is fetched again with the next attempt.
      .catch(() => library.then(ready => { if (!ready) library = null; }))
      .finally(() => { pending = false; });
  }
  map.on('moveend', start);
  start();
}
