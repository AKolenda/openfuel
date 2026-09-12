# Canadian public data snapshots

These files contain real public geography and dated reference statistics.
**They contain zero seeded station pump prices.** Community observations are
stored separately in D1 and are not included in the source distribution.

| File | Included snapshot | Source and licence |
| --- | --- | --- |
| `canada-stations.jsonl` | 12,543 fuel-station records; OSM snapshot 2026-09-12 05:38:56 UTC | © OpenStreetMap contributors, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/) |
| `canada-cities.json` | 510 Canadian entries from cities15000 | [GeoNames](https://www.geonames.org/), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) |
| `market-averages.json` | 54 regular, premium and diesel monthly reference values; July 2026 | Statistics Canada table 18-10-0001-01, [Statistics Canada Open Licence](https://www.statcan.gc.ca/en/terms-conditions/open-licence) |
| `metadata.json` | Counts, source labels and snapshot period | Metadata for the above imports |

OpenStreetMap station data is distributed under ODbL 1.0, independently of the
AGPL source-code licence. Preserve contributor attribution and the data licence
when redistributing or adapting the station database. [OSM copyright and attribution](https://www.openstreetmap.org/copyright).

GeoNames city names, administrative names, coordinates and populations are adapted
from its [download extract](https://www.geonames.org/export/). Only Canadian
entries are retained, with normalized search names and display labels. The
upstream cities15000 extract includes cities over 15,000 population and some
administrative centres; it is not a complete gazetteer or address index.

Adapted from Statistics Canada, table 18-10-0001-01, July 2026. This does not
constitute an endorsement by Statistics Canada of this product.

The [monthly retail-price table](https://www150.statcan.gc.ca/t1/tbl1/en/tv.action?pid=1810000101)
values are converted from cents/L to integer thousandths of CAD/L and paired
with approximate city centres for reference selection. They are monthly regional
statistics, not measurements at individual stations. The date must remain visible.

## Import transformations and limits

`tools/import_live_data.py` reads an Overpass JSON export of Canadian
`amenity=fuel` nodes, ways and relations. Ways/relations use returned centre
coordinates. IDs retain OSM type and ID; each record links to its source object.
Entries tagged with private/no access or aviation/marine fuel are excluded.
Name, brand, address and selected amenities are copied when present. Operating
status is unknown; missing or old tags are not verified by this import. Mapping
may omit stations or contain duplicates and closed sites. No external fuel-price
site is scraped and no price is inferred from station brands or regional averages.

The `osm_snapshot_at` field records the upstream OSM base timestamp. The existing
`imported_at` field repeats that timestamp; it is not an independently measured
fetch/processing time. Raw downloads stay in ignored `.local/imports/`.

## Refresh the snapshot

Fetch these source files into a local directory before running the importer:

- `canada-fuel-osm.json`: Overpass JSON result for the query below.
- `cities15000.zip`: [GeoNames extract](https://download.geonames.org/export/dump/cities15000.zip).
- `admin1CodesASCII.txt`: [GeoNames administrative names](https://download.geonames.org/export/dump/admin1CodesASCII.txt).
- `18100001-eng.zip`: [Statistics Canada complete CSV table](https://www150.statcan.gc.ca/n1/tbl/csv/18100001-eng.zip).

```overpass
[out:json][timeout:240];
area["ISO3166-1"="CA"][admin_level=2]->.canada;
nwr[amenity=fuel](area.canada);
out center tags;
```

Choose an available public Overpass instance and respect its usage limits; this
is a maintainer import, never a query made by every app user. The query above
describes the reproducible geographic selection. Current data can differ from
the checked-in snapshot as upstream records change.

```sh
python3 tools/import_live_data.py --refresh-from .local/imports
python3 tools/import_live_data.py --sql .local/real-data.sql
npm run db:local
npm run db:seed:local
```

Review changes to counts, coordinates, source licences and the selected statistics
period. Update this notice when publishing a new reference period. After local
verification, `npm run db:remote` and `npm run db:seed:remote` apply the configured
remote schema and import. Seed SQL upserts geography/reference records and does
not insert, overwrite or delete community price reports. It does not prune
stations removed from a later upstream snapshot; removals need a deliberate
review and migration that preserves report history.
