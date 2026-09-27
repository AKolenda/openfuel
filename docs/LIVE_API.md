# Live station API v1

Base URL: `https://openfuel.ca/api/v1`. The current implementation is
`services/live/worker.mjs` with the D1 schema in `services/live/migrations/`.
No secret is needed for public reads or community reports. This is separate from
`PROTOTYPE_API.md` and the retained FastAPI/PostGIS reference implementations.

Station records, city search, regions and monthly averages come from the bundled
snapshot in `packages/data`; the site build writes the stations as static files
per 0.5° area, which the Worker reads. D1 holds current prices, reports and the
daily usage count. [Running costs](RUNNING_COSTS.md) explains how requests use D1.

## Nearby stations

`GET /stations?lat=53.546&lon=-113.494&radius=10000&fuel=regular`

Latitude and longitude are required and must be finite valid coordinates.
Radius is 100–50,000 metres, default 10,000. Fuel is `regular`, `premium` or
`diesel`, default `regular`. Coordinates identify the searched area; no device
location is inferred by the server. Distances are straight-line metres.

The response includes `mode: "live"`, `is_demo: false`, `currency: "CAD"`,
`unit: "L"`, `generated_at`, `location`, `stations`, `coverage` and an optional
`market_reference`. Up to 200 matching stations are returned, nearest first.
`coverage.truncated` is true when more than 200 stations matched. A search covers
at most 20 half-degree areas, which only narrows 50 km searches in the far north.

Stations have an OSM-derived ID such as `osm-node-123`, a name, latitude,
longitude, available address/amenity metadata, `source`, `source_url`,
`distanceMetres` and `synthetic: false`. Names, addresses, opening state and
amenities may be missing or outdated. Imported records do not prove a station
is currently operating.

Recognized brands also have nullable `brandKey`, `brandLogoUrl` and
`brandLogoSourceUrl` fields. The catalog in `packages/brands` matches known brand
aliases and supplies fixed HTTPS image URLs; arbitrary imported website URLs are
never used as image sources. Clients fetch logos directly from Wikimedia Commons,
Co-op, Shell or Tempo and show initials if no match or image is available. OpenFuel
does not host or bundle station logo files. These fields identify a station brand;
they do not imply a partnership or verify the station's current operator.

`prices` contains nullable `regular`, `premium` and `diesel` values. A price is
an integer in thousandths of CAD per litre: `1499` means $1.499/L or 149.9 ¢/L.
This is a unit example, not a real observation. A null means no community price
is available. Per-grade `ages` are minutes, `observedAt` are ISO timestamps,
`priceSources` identify community reports, and `stale` flags reports older than
24 hours. Age does not verify a price. The server timestamp records submission
receipt; clients should submit only prices actually observed that day.

`market_reference`, when present, is a dated Statistics Canada monthly average
for the nearest covered region within 100 km or Canada. Its `period`, `kind`,
`source`, `source_url` and explanatory note travel with the value. It must never
be copied into a station's pump-price field or described as today's price.

Answers are sent with `Cache-Control: private, max-age=15`, so a client's HTTP
cache may reuse one for 15 seconds. Request with `Cache-Control: no-cache` to skip
that copy, for example straight after your own report. Prices can also be up to
about 15 seconds behind D1 on other Worker instances.

## Search a city

`GET /geocode?q=Edmonton` returns up to eight `{name,latitude,longitude}`
results from the bundled Canadian GeoNames cities15000 index, with attribution.
The query must be 2–80 characters. It is city search, not address or postal-code
geocoding; smaller communities may be absent. Explicit coordinates and map-area
search remain available. Answers are sent with
`Cache-Control: public, max-age=86400`.

## Report an observed price

`POST /reports`, `Content-Type: application/json`:

```json
{
  "station_id": "osm-node-123",
  "fuel_type": "regular",
  "price_milli": 1499,
  "client_id": "example-installation-id",
  "request_id": "example-unique-request-id"
}
```

Use a real station ID returned by your chosen API. The example is for local tests;
never submit invented prices to production. Prices must be integers from 500 to
3999 (50.0–399.9 ¢/L). `client_id` is a random per-install identifier of 16–80
URL-safe characters, not an authenticated identity. Optional `request_id` has
the same length/character bounds and supports idempotent retries.

A new report returns HTTP 201 with `{ok:true,is_demo:false,verification:"unverified",
report:{id,station_id,fuel_type,price_milli,observed_at}}`. Repeating the same
request ID, client, station, grade and price returns HTTP 200 and the original
receipt. Reusing an ID with different data returns 409. The accepted price is
published to the current prices in the same transaction. The Worker instance that
accepted it shows it at once, other instances within about 15 seconds, and a
client's cached stations answer may hide it for 15 seconds more. Client IDs are
not returned.

Only show success after validating the response. Preserve an uncertain request
ID for retry; do not silently queue offline writes. Limits include a best-effort
edge limit of 20 submissions per minute per IP, an atomic 30 reports per hour
per client ID, and a 100,000-report archive ceiling. These limit abuse but do not
prove identity or prevent coordinated false reports.

Errors use `{error,message}`: 400 invalid input, 404 station absent, 409 retry
conflict, 413 body over 2 KB, 415 invalid content type, 429 rate limit
(`rate_limited`, with `Retry-After`) or full archive (`report_capacity`), 503
`spending_cap` (below) and 503 database unavailable (`temporarily_unavailable`).
Public station browsing remains available when the report archive is full.

## Daily database limit

The Worker keeps a daily D1 budget below Cloudflare's Workers Free limits; see
[running costs](RUNNING_COSTS.md). Once it is spent, or Cloudflare's own D1 limit
is reached, the API answers HTTP 503 until midnight UTC, with a `Retry-After`
header giving the seconds left:

```json
{
  "error": "spending_cap",
  "reason": "daily_database_budget",
  "scope": "all",
  "resets_at": "2026-09-28T00:00:00.000Z",
  "message": "OpenFuel's free database allowance for today is used up. Saved stations still show; live prices return after midnight UTC.",
  "donate_url": null
}
```

`scope: "all"` pauses database reads: a nearby search still answers when the
Worker instance or its data centre already holds prices for every area it needs,
each price with its age; otherwise it gets this answer, as do health and reports.
`scope: "reports"` pauses new reports only; browsing continues. `reason` names
the specific limit, such as `daily_database_budget` or `d1_free_daily_read_limit`;
clients should act on `scope`. `donate_url` is the operator's donate link, or
null. City search and regions read no database and keep working.

When Cloudflare's daily Worker request limit is reached, Cloudflare answers
itself with a non-JSON HTTP 429 page. Clients treat a 429 without a JSON body
like `scope: "all"` until midnight UTC and keep saved stations on screen.

## Health and coverage

`GET /health` asks D1 to answer (`SELECT 1`, no rows read) and returns service
status, live mode, the snapshot's station count and `writesEnabled`, which is
false while new reports are paused. It is sent with `Cache-Control: no-store`.
`GET /regions` describes the Canadian snapshot and its attribution/import metadata
(`Cache-Control: public, max-age=3600`). These report configuration and coverage,
not accuracy or freshness of every station. The API permits public CORS reads
and reports without cookies. No station moderation or account API is deployed.

The application disables automatic invocation logging and Worker tracing to
avoid recording location-bearing query strings. Provider-level connection
metadata may still exist. See [privacy](PRIVACY.md).
