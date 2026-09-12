#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Read-only checks against a deployed or local live API; never submit pump prices."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--origin', default='https://openfuel.ca')
parser.add_argument('--output', type=Path)
args = parser.parse_args()
origin = args.origin.rstrip('/')

def get(path):
    request = Request(origin + '/api/v1' + path, headers={
        'User-Agent': 'OpenFuel/0.4 (+https://openfuel.ca)', 'Accept': 'application/json'})
    with urlopen(request, timeout=30) as response:
        assert response.status == 200
        return json.load(response)

health = get('/health')
assert health['mode'] == 'live' and health['is_demo'] is False
assert health['station_count'] > 10000
checks = []
for city, lat, lon in [('Edmonton', 53.5461, -113.4938), ('Calgary', 51.0447, -114.0719),
                       ('Toronto', 43.6532, -79.3832), ('Montréal', 45.5017, -73.5673),
                       ('Vancouver', 49.2827, -123.1207), ('Yellowknife', 62.4540, -114.3718)]:
    body = get(f'/stations?lat={lat}&lon={lon}&radius=10000')
    stations = body['stations']
    assert stations and body['is_demo'] is False
    assert all(not row['synthetic'] and row['source'] == 'OpenStreetMap' for row in stations)
    assert all(row['distanceMetres'] <= 10000 for row in stations)
    assert [row['distanceMetres'] for row in stations] == sorted(row['distanceMetres'] for row in stations)
    reference = body.get('market_reference')
    assert reference is None or reference['kind'] == 'monthly_average'
    checks.append({'city': city, 'nearby_stations': len(stations), 'reference_period': reference['period'] if reference else None})
assert any('Montréal' in row['name'] for row in get('/geocode?q=montreal')['results'])
try:
    get('/stations')
    raise AssertionError('Missing location must not silently select an area')
except HTTPError as error:
    assert error.code == 400
result = {'checked_at': datetime.now(timezone.utc).isoformat(), 'origin': origin,
          'read_only': True, 'health': health, 'checks': checks, 'passed': True}
text = json.dumps(result, indent=2, ensure_ascii=False) + '\n'
if args.output:
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(text)
print(text, end='')
