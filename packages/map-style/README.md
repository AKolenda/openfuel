# OpenFuel map style

OpenFuel's base map is a fork of [OpenFreeMap](https://openfreemap.org)'s Liberty
style, recoloured for a light, low-contrast look in the manner of CARTO Voyager and
Google Maps: pale land, blue water, soft greens, white streets with grey edges, yellow
major roads and grey labels. It also draws buildings flat, drops the low-zoom shaded
relief, and hides the base map's fuel-station icons because OpenFuel draws its own.

The map data, fonts and icons still come from OpenFreeMap's free public service
(`tiles.openfreemap.org`); no key or account is needed. MapLibre GL JS draws the
style inside the Leaflet map on the website, and on its own in the Android WebView.

## Files

| Path | Role |
| --- | --- |
| `liberty.json` | Snapshot of `https://tiles.openfreemap.org/styles/liberty` |
| `../../tools/map_style.py` | OpenFuel's palette and layer changes |
| `../../apps/web/preview/map/openfuel-style.json` | Generated style used by the apps; do not edit |

## Change the look or follow Liberty

Edit the palette in `tools/map_style.py`, then rebuild:

```sh
python3 tools/map_style.py            # rebuild from the snapshot
python3 tools/map_style.py --update   # refresh the Liberty snapshot, then rebuild
```

If Liberty renames or removes a layer that the palette changes, the build stops and
names the layer. A repository test checks that the generated style matches the
snapshot and script.

## Licences

- OpenFreeMap styles project: MIT.
- Liberty is a fork of OSM Liberty, itself a fork of OSM Bright and derived from
  Mapbox Open Styles. Its code is BSD 3-Clause and its design is
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). OpenFuel's changes to
  the design are released under the same terms.
- The map schema and tiles come from [OpenMapTiles](https://www.openmaptiles.org/)
  (code BSD 3-Clause, design CC BY 4.0) with data © OpenStreetMap contributors (ODbL).
- Fonts are Noto Sans (SIL Open Font License 1.1); icons are Maki (CC0 1.0).
- The palette follows CARTO's [Voyager](https://github.com/CartoDB/basemap-styles) style
  (design CC BY 4.0, © CARTO); no CARTO tiles or services are used. The map credit reads
  "OpenFreeMap © OpenMapTiles · Style after CARTO Voyager · Data © OpenStreetMap contributors".
