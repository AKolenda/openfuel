#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Build reproducible D1 seed SQL from the attributed, price-free public snapshot.

Rebuild snapshot from downloaded upstream files with --refresh-from PATH.
See packages/data/README.md for sources and their separate data licences.
"""
import argparse
import csv
import io
import json
from pathlib import Path
import unicodedata
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'packages/data'

def normalize(value):
    return ''.join(c for c in unicodedata.normalize('NFKD', value) if not unicodedata.combining(c)).casefold()

def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))

def refresh(source):
    source = Path(source)
    payload = json.loads((source / 'canada-fuel-osm.json').read_text())
    records = []
    for item in payload['elements']:
        tags = item.get('tags', {})
        point = item.get('center', item)
        if 'lat' not in point or 'lon' not in point:
            continue
        if tags.get('access') in ('private', 'no') or tags.get('fuel') in ('aviation', 'marine'):
            continue
        street = ' '.join(filter(None, [tags.get('addr:housenumber'), tags.get('addr:street')]))
        address = ', '.join(filter(None, [street, tags.get('addr:city'), tags.get('addr:province'), tags.get('addr:postcode')]))
        records.append({'id': f"osm-{item['type']}-{item['id']}",
            'name': tags.get('name') or tags.get('brand') or tags.get('operator') or 'Fuel station',
            'brand': tags.get('brand') or tags.get('operator') or '',
            'address': address or 'Address not recorded in OpenStreetMap',
            'latitude': point['lat'], 'longitude': point['lon'],
            'amenities': [label for key, label in [('toilets','Restrooms'),('shop','Shop'),('car_wash','Car wash')] if tags.get(key) not in (None, 'no')],
            'open': None, 'source': 'OpenStreetMap',
            'source_url': f"https://www.openstreetmap.org/{item['type']}/{item['id']}",
            'synthetic': False})
    records.sort(key=lambda r: r['id'])
    (DATA / 'canada-stations.jsonl').write_text(''.join(compact(r)+'\n' for r in records))
    admins = {line.split('\t')[0]: line.split('\t')[1] for line in (source/'admin1CodesASCII.txt').read_text().splitlines()}
    with zipfile.ZipFile(source/'cities15000.zip') as archive:
        rows = [r.split('\t') for r in archive.read('cities15000.txt').decode().splitlines()]
    cities = []
    for r in rows:
        if r[8] != 'CA': continue
        region = admins.get(f'CA.{r[10]}', '')
        name = f'{r[1]}, {region}, Canada'
        cities.append({'id':r[0], 'name':name, 'search_name':normalize(name + ' ' + r[2]), 'latitude':float(r[4]), 'longitude':float(r[5]), 'population':int(r[14])})
    (DATA/'canada-cities.json').write_text(json.dumps(cities, ensure_ascii=False, indent=2)+'\n')
    # Statistics Canada city averages are reference context only; NEVER seeded as station prices.
    coordinates = {
      'Canada':(56.1304,-106.3468), 'Calgary':(51.0447,-114.0719), 'Edmonton':(53.5461,-113.4938),
      'Charlottetown':(46.2382,-63.1311), 'Halifax':(44.6488,-63.5752), 'Montréal':(45.5017,-73.5673),
      'Ottawa':(45.4215,-75.6972), 'Québec':(46.8139,-71.2080), 'Regina':(50.4452,-104.6189),
      'Saint John':(45.2733,-66.0633), 'Saskatoon':(52.1332,-106.6700), "St. John's":(47.5615,-52.7126),
      'Thunder Bay':(48.3809,-89.2477), 'Toronto':(43.6532,-79.3832), 'Vancouver':(49.2827,-123.1207),
      'Victoria':(48.4284,-123.3656), 'Whitehorse':(60.7212,-135.0568), 'Winnipeg':(49.8951,-97.1384),
      'Yellowknife':(62.4540,-114.3718)}
    grades = {'Regular unleaded gasoline at self service filling stations':'regular', 'Premium unleaded gasoline at self service filling stations':'premium', 'Diesel fuel at self service filling stations':'diesel'}
    with zipfile.ZipFile(source/'18100001-eng.zip') as archive:
        rows=list(csv.DictReader(io.StringIO(archive.read('18100001.csv').decode('utf-8-sig'))))
    latest = max(r['REF_DATE'] for r in rows if r['VALUE'] and r['Type of fuel'] in grades)
    averages=[]
    for r in rows:
        if r['REF_DATE'] != latest or r['Type of fuel'] not in grades or not r['VALUE']: continue
        city=next((c for c in coordinates if r['GEO'].startswith(c)), None)
        if city is None: raise ValueError('Missing reference city: '+r['GEO'])
        lat,lon=coordinates[city]
        averages.append({'city':r['GEO'], 'latitude':lat, 'longitude':lon, 'period':latest, 'fuel_type':grades[r['Type of fuel']], 'price_milli':round(float(r['VALUE'])*10)})
    (DATA/'market-averages.json').write_text(json.dumps(averages,ensure_ascii=False,indent=2)+'\n')
    metadata={'country':'CA','station_count':len(records),'osm_snapshot_at':payload['osm3s']['timestamp_osm_base'], 'imported_at':payload['osm3s']['timestamp_osm_base'], 'station_source':'OpenStreetMap contributors','station_license':'ODbL-1.0','city_source':'GeoNames','city_license':'CC-BY-4.0','market_source':'Statistics Canada table 18-10-0001-01','market_period':latest, 'pump_prices_seeded':0}
    (DATA/'metadata.json').write_text(json.dumps(metadata,indent=2)+'\n')
    print(f"Snapshot: {len(records)} stations, {len(cities)} cities, {len(averages)} monthly averages ({latest}); zero invented station prices.")

def quote(value):
    if value is None: return 'NULL'
    if isinstance(value,(int,float)): return str(value)
    return "'"+str(value).replace("'", "''")+"'"

def generate(destination):
    lines=['-- Real public geography and monthly references. Does not modify community reports.']
    def insert(table, columns, values):
        # Updating source snapshots leaves user reports untouched.
        updates=','.join(f'{c}=excluded.{c}' for c in columns[1:])
        lines.append(f"INSERT INTO {table}({','.join(columns)}) VALUES({','.join(quote(v) for v in values)}) ON CONFLICT DO UPDATE SET {updates};")
    for line in (DATA/'canada-stations.jsonl').read_text().splitlines():
        r=json.loads(line); insert('stations',['id','latitude','longitude','data'],[r['id'],r['latitude'],r['longitude'],compact(r)])
    for r in json.loads((DATA/'canada-cities.json').read_text()):
        insert('cities',list(r),list(r.values()))
    for r in json.loads((DATA/'market-averages.json').read_text()):
        insert('market_averages',list(r),list(r.values()))
    insert('dataset_metadata',['id','data'],['canada',compact(json.loads((DATA/'metadata.json').read_text()))])
    path=Path(destination);path.parent.mkdir(parents=True,exist_ok=True);path.write_text('\n'.join(lines)+'\n')
    print(f'Wrote {len(lines)-1} seed statements to {path}')

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--refresh-from',type=Path)
    parser.add_argument('--sql',type=Path)
    args=parser.parse_args()
    if args.refresh_from: refresh(args.refresh_from)
    if args.sql: generate(args.sql)
    if not args.refresh_from and not args.sql: parser.error('choose --sql PATH or --refresh-from PATH')
