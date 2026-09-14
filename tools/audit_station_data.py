#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Audit every imported station; flags are review candidates, never automatic deletions.
Optional --osm PATH adds survey dates and raw-tag checks from the matching import.
"""
import argparse
from collections import Counter, defaultdict
from datetime import date, timedelta
import hashlib
import json
import math
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'packages/data'

def distance(a, b):
    lat1, lat2 = map(math.radians, (a['latitude'], b['latitude']))
    angle = math.sin((lat1-lat2)/2)**2 + math.cos(lat1)*math.cos(lat2)*math.sin(math.radians(a['longitude']-b['longitude'])/2)**2
    return round(12742000*math.asin(min(1, math.sqrt(angle))))

def audit(raw=None):
    checked = date.today()
    cutoff = (checked - timedelta(days=730)).isoformat()
    path = DATA/'canada-stations.jsonl'
    stations = [json.loads(line) for line in path.read_text().splitlines()]
    flags = defaultdict(list)
    ids = Counter(s['id'] for s in stations)
    cells = defaultdict(list)
    corrections = {c['station_id']:c for c in json.loads((DATA/'station-corrections.json').read_text())}
    for s in stations:
        sid = s['id']; lat, lon = s['latitude'], s['longitude']
        if ids[sid] > 1: flags['duplicate_id'].append(sid)
        if not (41 <= lat <= 84 and -142 <= lon <= -52): flags['outside_canadian_bounds'].append(sid)
        if s['name'].lower() in ('fuel station','gas station','gas','fuel'): flags['unnamed'].append(sid)
        if not s['brand']: flags['missing_brand'].append(sid)
        if s['address'] == 'Address not recorded in OpenStreetMap': flags['missing_address'].append(sid)
        if 'husky' in (s['name']+' '+s['brand']).lower() and sid not in corrections: flags['husky_brand_review'].append(sid)
        cell = (math.floor(lat*1000), math.floor(lon*1000))
        for y in range(cell[0]-1,cell[0]+2):
            for x in range(cell[1]-2,cell[1]+3):
                for other in cells[(y,x)]:
                    d = distance(s,other)
                    if d <= 25: flags['nearby_possible_duplicates'].append({'ids':[other['id'],sid], 'metres':d})
        cells[cell].append(s)
    if raw:
        tags = {f"osm-{s['type']}-{s['id']}":s.get('tags',{}) for s in json.loads(Path(raw).read_text())['elements']}
        for s in stations:
            t = tags.get(s['id'], {})
            dates = [t[k] for k in ('check_date','survey:date') if re.fullmatch(r'\d{4}-\d{2}-\d{2}',t.get(k,''))]
            if not dates: flags['no_recorded_survey_date'].append(s['id'])
            elif max(dates) < cutoff: flags['survey_older_than_two_years'].append(s['id'])
            if t.get('fuel') in ('wood','propane','Furnace Oil') and not any(t.get('fuel:'+k)=='yes' for k in ('octane_87','octane_91','diesel')):
                flags['road_fuel_coverage_review'].append(s['id'])
    return {'checked_at':checked.isoformat(),'snapshot_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
            'stations_checked':len(stations),'reviewed_tempo_corrections':len(corrections),
            'limits':'Structural and source consistency audit, not a physical survey. Missing dates do not prove stale data; nearby records may be separate forecourts. Remaining names, closure status and pump prices are not independently verified.',
            'counts':{k:len(v) for k,v in sorted(flags.items())},'flags':dict(sorted(flags.items()))}

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--osm',type=Path);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    result=audit(args.osm);args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k!='flags'},indent=2))
