#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Build OpenFuel's map style from the OpenFreeMap Liberty snapshot.

The fork is a palette and a few layer changes applied to packages/map-style/liberty.json,
so a new Liberty release is picked up by refreshing the snapshot (--update) and rebuilding.
An override for a layer Liberty no longer has fails the build instead of silently dropping.
"""
from pathlib import Path
from urllib.request import urlopen
import argparse
import copy
import json

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM_URL = 'https://tiles.openfreemap.org/styles/liberty'
SNAPSHOT = ROOT / 'packages/map-style/liberty.json'
OUTPUT = ROOT / 'apps/web/preview/map/openfuel-style.json'

# Light, low-contrast base in the manner of CARTO Voyager (CC BY 4.0) and Google Maps:
# pale land, blue water, soft greens, white streets, yellow major roads, grey labels.
LAND, WATER, WATER_LABEL = '#f3f1ed', '#a9d3f5', '#3f78a8'
PARK, WOOD, GRASS = '#c8e6c1', '#cbe5c0', '#d4ebcc'
MINOR, MINOR_CASE = '#ffffff', '#d6d0c5'
SECONDARY, SECONDARY_CASE = '#fffdf2', '#d9cba9'
PRIMARY, PRIMARY_CASE = '#fff3c4', '#e9c678'
MOTORWAY, MOTORWAY_CASE = '#fdd97d', '#e8b85a'
RAIL, BUILDING, BUILDING_EDGE = '#cfcfcf', '#e9e6e0', '#dcd8d0'
LABEL, LABEL_SOFT, ROAD_LABEL, HALO = '#3c4043', '#5f6368', '#5f6368', '#ffffff'

PAINT = {
    'background': {'background-color': LAND},
    'park': {'fill-color': PARK, 'fill-opacity': 0.8, 'fill-outline-color': PARK},
    'park_outline': {'line-color': PARK},
    'landuse_residential': {'fill-color': 'rgba(232,228,220,0.3)'},
    'landcover_wood': {'fill-color': WOOD, 'fill-opacity': 0.6},
    'landcover_grass': {'fill-color': GRASS, 'fill-opacity': 0.6},
    'landcover_ice': {'fill-color': '#eef4f6'},
    'landcover_sand': {'fill-color': '#f5eed4'},
    'landuse_pitch': {'fill-color': '#d8ecd0'},
    'landuse_track': {'fill-color': '#d8ecd0'},
    'landuse_cemetery': {'fill-color': '#dde8d2'},
    'landuse_hospital': {'fill-color': '#f9e1e1'},
    'landuse_school': {'fill-color': '#f1ecdd'},
    'water': {'fill-color': WATER},
    'waterway_tunnel': {'line-color': WATER},
    'waterway_river': {'line-color': WATER},
    'waterway_other': {'line-color': WATER},
    'aeroway_fill': {'fill-color': '#ecebe7'},
    'aeroway_runway': {'line-color': '#ffffff'},
    'aeroway_taxiway': {'line-color': '#ffffff'},
    'road_path_pedestrian': {'line-color': '#ffffff'},
    'building': {'fill-color': BUILDING, 'fill-outline-color': BUILDING_EDGE},
    'boundary_3': {'line-color': '#c9c4cc'},
    'boundary_2': {'line-color': '#9e9ca6'},
    'boundary_disputed': {'line-color': '#9e9ca6'},
    'waterway_line_label': {'text-color': '#4f8fc4', 'text-halo-color': 'rgba(255,255,255,0.8)'},
    'water_name_point_label': {'text-color': WATER_LABEL, 'text-halo-color': 'rgba(255,255,255,0.8)'},
    'water_name_line_label': {'text-color': WATER_LABEL, 'text-halo-color': 'rgba(255,255,255,0.8)'},
    'highway-name-path': {'text-color': ROAD_LABEL, 'text-halo-color': HALO},
    'highway-name-minor': {'text-color': ROAD_LABEL},
    'highway-name-major': {'text-color': ROAD_LABEL},
    'poi_transit': {'text-color': '#3b6fa0'},
    'airport': {'text-color': LABEL_SOFT},
    'label_other': {'text-color': LABEL_SOFT, 'text-halo-color': HALO},
    'label_village': {'text-color': LABEL, 'text-halo-color': HALO},
    'label_town': {'text-color': LABEL, 'text-halo-color': HALO},
    'label_city': {'text-color': LABEL, 'text-halo-color': HALO},
    'label_city_capital': {'text-color': LABEL, 'text-halo-color': HALO},
    'label_state': {'text-color': '#80868b', 'text-halo-color': HALO},
    'label_country_1': {'text-color': LABEL_SOFT, 'text-halo-color': HALO},
    'label_country_2': {'text-color': LABEL_SOFT, 'text-halo-color': HALO},
    'label_country_3': {'text-color': LABEL_SOFT, 'text-halo-color': HALO},
}
for poi in ('poi_r1', 'poi_r7', 'poi_r20'):
    PAINT[poi] = {'text-color': '#6b6f73'}
# Road classes repeat for tunnels, surface roads and bridges.
for prefix in ('tunnel_', 'road_', 'bridge_'):
    for name, colour in {'motorway': MOTORWAY, 'motorway_casing': MOTORWAY_CASE,
                         'motorway_link': MOTORWAY, 'motorway_link_casing': MOTORWAY_CASE,
                         'trunk_primary': PRIMARY, 'trunk_primary_casing': PRIMARY_CASE,
                         'link': PRIMARY, 'link_casing': PRIMARY_CASE,
                         'secondary_tertiary': SECONDARY, 'secondary_tertiary_casing': SECONDARY_CASE,
                         'service_track': MINOR, 'service_track_casing': MINOR_CASE,
                         'major_rail': RAIL, 'major_rail_hatching': RAIL,
                         'transit_rail': RAIL, 'transit_rail_hatching': RAIL}.items():
        PAINT[prefix + name] = {'line-color': colour}
for layer in ('road_minor', 'tunnel_minor', 'bridge_street'):
    PAINT[layer] = {'line-color': MINOR}
for layer in ('road_minor_casing', 'tunnel_street_casing', 'bridge_street_casing'):
    PAINT[layer] = {'line-color': MINOR_CASE}

# A flat map: no shaded relief at low zoom and no 3D building blocks.
REMOVE_LAYERS = {'natural_earth', 'building-3d'}
REMOVE_SOURCES = {'ne2_shaded'}
# OpenFuel draws its own station markers, so the base map's fuel icons would duplicate them.
HIDE_FUEL_POIS = ('poi_r1', 'poi_r7', 'poi_r20')


def build(upstream: dict) -> dict:
    style = copy.deepcopy(upstream)
    layers = {layer['id']: layer for layer in style['layers']}
    missing = sorted((set(PAINT) | REMOVE_LAYERS | set(HIDE_FUEL_POIS)) - set(layers))
    if missing:
        raise ValueError('Liberty no longer has these layers; update tools/map_style.py: ' + ', '.join(missing))
    for layer_id, paint in PAINT.items():
        layers[layer_id].setdefault('paint', {}).update(paint)
    for layer_id in HIDE_FUEL_POIS:
        layers[layer_id]['filter'] = ['all', layers[layer_id]['filter'], ['!=', ['get', 'class'], 'fuel']]
    # Flat buildings replace the 3D blocks at every zoom from 13.
    layers['building'].pop('maxzoom', None)
    style['layers'] = [layer for layer in style['layers'] if layer['id'] not in REMOVE_LAYERS]
    style['sources'] = {name: source for name, source in style['sources'].items() if name not in REMOVE_SOURCES}
    style['name'] = 'OpenFuel'
    style['metadata'] = {'openfuel:source': 'Generated by tools/map_style.py from packages/map-style/liberty.json; do not edit.'}
    return style


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--update', action='store_true', help='refresh the Liberty snapshot from OpenFreeMap first')
    args = parser.parse_args()
    if args.update:
        with urlopen(UPSTREAM_URL, timeout=30) as response:
            SNAPSHOT.write_text(json.dumps(json.load(response), indent=2, ensure_ascii=False) + '\n')
    OUTPUT.write_text(json.dumps(build(json.loads(SNAPSHOT.read_text())), separators=(',', ':'), ensure_ascii=False) + '\n')
    print(f'Map style: {OUTPUT.relative_to(ROOT)}')


if __name__ == '__main__':
    main()
