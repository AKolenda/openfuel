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

/** Adds the base map and reports which one it chose: onReady(layer, 'openfreemap' | 'openstreetmap'). */
function baseMapLayer(map, style, options, onReady) {
  const raster = () => onReady(L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {...options, attribution: OPENSTREETMAP_CREDIT}).addTo(map), 'openstreetmap');
  let webgl = false;
  try { const canvas = document.createElement('canvas'); webgl = !!(canvas.getContext('webgl2') || canvas.getContext('webgl')); } catch {}
  if (!webgl || !style || typeof maplibregl === 'undefined' || !L.maplibreGL) return raster();
  let pending = false, done = false;
  const finish = choose => { done = true; map.off('moveend', start); choose(); };
  function vector(styleJSON, index) {
    const glStyle = {...styleJSON, sources: {...styleJSON.sources,
      openmaptiles: {type: 'vector', tiles: index.tiles, minzoom: index.minzoom ?? 0, maxzoom: index.maxzoom ?? 14}}};
    let layer;
    try { layer = L.maplibreGL({style: glStyle, attributionControl: {customAttribution: OPENFREEMAP_CREDIT}}).addTo(map); }
    catch { return raster(); }
    onReady(layer, 'openfreemap');
  }
  function start() {
    if (pending || done) return;
    pending = true;
    // The tile index is checked first. A refusal (an HTTP error or an unreadable index) switches to
    // raster tiles for the visit; a lost connection leaves the map as it is and retries on the next move.
    Promise.resolve(typeof style === 'string' ? fetch(style).then(response => response.json()) : style)
      .then(styleJSON => fetch(OPENFREEMAP_TILES).then(response => {
        if (!response.ok) return finish(raster);
        return response.json().then(index => finish(() => vector(styleJSON, index)), () => finish(raster));
      }))
      .catch(() => {})
      .finally(() => { pending = false; });
  }
  map.on('moveend', start);
  start();
}
