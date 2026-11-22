#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Build OpenFuel's public open-data files from the live API's export pages.

Run daily by .github/workflows/open-data.yml, which uploads the files to the `open-data` GitHub release.
The Worker strips installation and report IDs and cuts times to the minute before anything leaves D1
(services/live/worker.mjs, exportQueries); this script refuses any row with other fields as well.
Needs OPENFUEL_EXPORT_TOKEN. Python standard library only.
"""
from __future__ import annotations
import argparse
import csv
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'packages/data'
FIELDS = {
    'reports': ['station_id', 'fuel_type', 'price_milli', 'observed_at'],
    'prices': ['station_id', 'fuel_type', 'price_milli', 'observed_at', 'source'],
}
STATION_FIELDS = ['station_id', 'name', 'brand', 'address', 'latitude', 'longitude', 'osm_url']
# Release files; the website redirects /open-data/<name> to them (apps/web/_redirects).
FILES = {
    'reports': 'openfuel-price-reports.csv.gz',
    'prices': 'openfuel-current-prices.csv.gz',
    'stations': 'openfuel-stations.csv.gz',
    'manifest': 'openfuel-open-data.json',
}
ATTRIBUTION = ('OpenFuel community price reports and OpenStreetMap station data. '
               'Station data © OpenStreetMap contributors. Licensed under ODbL 1.0.')


class NotDeployed(Exception):
    pass


def fetch(api: str, token: str, table: str, after: int) -> dict:
    request = urllib.request.Request(f'{api}/api/v1/export?table={table}&after={after}', headers={
        'authorization': f'Bearer {token}', 'accept': 'application/json', 'user-agent': 'OpenFuel-open-data/1 (+https://openfuel.ca/open-data/)'})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code == 404:
                raise NotDeployed() from error
            if error.code == 401:
                sys.exit('The export token was refused: OPENFUEL_EXPORT_TOKEN does not match OPENFUEL_EXPORT_TOKEN_SHA256 in wrangler.jsonc.')
            if error.code == 503 and 'spending_cap' in error.read().decode('utf-8', 'replace'):
                sys.exit("OpenFuel's daily database budget is spent; the export runs again tomorrow.")
            if attempt == 3:
                raise
        except urllib.error.URLError:
            if attempt == 3:
                raise
        time.sleep(5 * (attempt + 1))
    raise AssertionError('unreachable')


def export_table(api: str, token: str, table: str) -> list[dict]:
    rows, after = [], 0
    while True:
        page = fetch(api, token, table, after)
        for row in page['rows']:
            if sorted(row) != sorted(FIELDS[table]):
                sys.exit(f'Refusing to publish: an exported {table} row has fields {sorted(row)}, expected {FIELDS[table]}.')
            rows.append(row)
        if page['next'] is None:
            return rows
        if not isinstance(page['next'], int) or page['next'] <= after:
            sys.exit(f'The {table} export did not advance past {after}.')
        after = page['next']


def stations() -> list[dict]:
    """The deployed station snapshot, with the same reviewed identity corrections the app applies."""
    corrections = {c['station_id']: c for c in json.loads((DATA / 'station-corrections.json').read_text())}
    out = []
    for line in (DATA / 'canada-stations.jsonl').read_text().splitlines():
        if not line.strip():
            continue
        s = json.loads(line)
        c = corrections.get(s['id'])
        if c and s['name'] == c['previous_name'] and s['brand'] == c['previous_brand'] and \
                abs(s['latitude'] - c['latitude']) <= .00001 and abs(s['longitude'] - c['longitude']) <= .00001:
            s = {**s, 'name': c['name'], 'brand': c['brand']}
        out.append({'station_id': s['id'], 'name': s['name'], 'brand': s['brand'], 'address': s['address'],
                    'latitude': s['latitude'], 'longitude': s['longitude'], 'osm_url': s['source_url']})
    return out


def write_csv(path: Path, fields: list[str], rows: list[dict]) -> None:
    text = io.StringIO(newline='')
    writer = csv.DictWriter(text, fieldnames=fields, lineterminator='\n')
    writer.writeheader()
    writer.writerows(rows)
    # mtime=0 so unchanged data produces identical bytes and checksums from day to day.
    path.write_bytes(gzip.compress(text.getvalue().encode('utf-8'), compresslevel=9, mtime=0))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api', default='https://openfuel.ca')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args(argv)
    token = os.environ.get('OPENFUEL_EXPORT_TOKEN', '').strip()
    if not token:
        sys.exit('Set OPENFUEL_EXPORT_TOKEN (the GitHub secret of the same name).')
    try:
        reports = export_table(args.api.rstrip('/'), token, 'reports')
        prices = export_table(args.api.rstrip('/'), token, 'prices')
    except NotDeployed:
        print(f'::warning::{args.api}/api/v1/export answered 404: the Worker with the export endpoint is not deployed yet. Nothing was published.')
        set_output('ready', 'false')
        return 0
    prices.sort(key=lambda r: (r['station_id'], r['fuel_type']))
    args.out.mkdir(parents=True, exist_ok=True)
    write_csv(args.out / FILES['reports'], FIELDS['reports'], reports)
    write_csv(args.out / FILES['prices'], FIELDS['prices'], prices)
    station_rows = stations()
    write_csv(args.out / FILES['stations'], STATION_FIELDS, station_rows)
    metadata = json.loads((DATA / 'metadata.json').read_text())
    manifest = {
        'generated_at': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
        'license': 'ODbL-1.0', 'license_url': 'https://opendatacommons.org/licenses/odbl/1-0/',
        'attribution': ATTRIBUTION,
        'about': 'https://openfuel.ca/open-data/',
        'notes': ['Prices are unverified community reports, not measured or official prices.',
                  'price_milli is thousandths of a Canadian dollar per litre: 1429 means $1.429/L (142.9 cents).',
                  'observed_at is when OpenFuel received the report, in UTC, cut to the minute.',
                  'No installation IDs, report IDs, IP addresses or locations of the people reporting are included.'],
        'osm_snapshot_at': metadata['osm_snapshot_at'],
        'files': {},
    }
    for key, count in (('reports', len(reports)), ('prices', len(prices)), ('stations', len(station_rows))):
        data = (args.out / FILES[key]).read_bytes()
        manifest['files'][FILES[key]] = {'rows': count, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
    (args.out / FILES['manifest']).write_text(json.dumps(manifest, indent=2) + '\n')
    (args.out / 'RELEASE_NOTES.md').write_text(release_notes(manifest))
    print(f"Open data: {len(reports)} reports, {len(prices)} current prices, {len(station_rows)} stations -> {args.out}")
    set_output('ready', 'true')
    return 0


def release_notes(manifest: dict) -> str:
    rows = '\n'.join(f"| `{name}` | {info['rows']:,} | `{info['sha256'][:16]}…` |" for name, info in manifest['files'].items())
    return f"""OpenFuel's community fuel-price reports and the station list they refer to, refreshed daily. Last updated {manifest['generated_at']}.

| File | Rows | SHA-256 |
| --- | ---: | --- |
{rows}

**Licence:** [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/). Credit "OpenFuel and © OpenStreetMap contributors", and share adapted databases under the same licence.

- Prices are **unverified** reports from people at the pump. They are not official prices, and some will be wrong.
- `price_milli` is thousandths of a dollar per litre: `1429` means $1.429/L.
- `observed_at` is when OpenFuel received the report, in UTC, cut to the minute.
- Nothing in these files identifies who reported a price: no installation IDs, report IDs, IP addresses or locations.

More at https://openfuel.ca/open-data/. These files are not part of an app release.
"""


def set_output(name: str, value: str) -> None:
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as f:
            f.write(f'{name}={value}\n')


if __name__ == '__main__':
    sys.exit(main())
