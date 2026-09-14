# Live station API v1

Base URL: `https://openfuel.ca/api/v1`. The current implementation is
`services/live/worker.mjs` with the D1 schema in `services/live/migrations/`.
No secret is needed for public reads or community reports. This is separate from
`PROTOTYPE_API.md` and the retained FastAPI/PostGIS reference implementations.

## Nearby stations

`GET /stations?lat=53.546&lon=-113.494&radius=10000&fuel=regular`

Latitude and longitude are required and must be finite valid coordinates.
Radius is 100–50,000 metres, default 10,000. Fuel is `regular`, `premium` or
`diesel`, default `regular`. Coordinates identify the searched area; no device
location is inferred by the server. Distances are straight-line metres.

The response includes `mode: "live"`, `is_demo: false`, `currency: "CAD"`,
`unit: "L"`, `generated_at`, `location`, `stations`, `coverage` and an optional
`market_reference`. Up to 200 matching stations are returned, nearest first.
`coverage.truncated` indicates when the result or bounding search was capped.

Stations have an OSM-derived ID such as `osm-node-123`, a name, latitude,
longitude, available address/amenity metadata, `source`, `source_url`,
`distanceMetres` and `synthetic: false`. Names, addresses, opening state and
amenities may be missing or outdated. Imported records do not prove a station
is currently operating.

Recognized brands also have nullable `brandKey`, `brandLogoUrl` and
`brandLogoSourceUrl` fields. The catalog in `packages/brands` matches known brand
aliases and supplies fixed HTTPS image URLs; arbitrary imported website URLs are
never used as image sources. Clients fetch logos directly from Wikimedia Commons,
Co-op or Shell and show initials if no match or image is available. OpenFuel does
not host or bundle station logo files. These fields identify a station brand;
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

## Search a city

`GET /geocode?q=Edmonton` returns up to eight `{name,latitude,longitude}`
results from the bundled Canadian GeoNames cities15000 index, with attribution.
The query must be 2–80 characters. It is city search, not address or postal-code
geocoding; smaller communities may be absent. Explicit coordinates and map-area
search remain available.

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
published atomically to subsequent station reads. Client IDs are not returned.

Only show success after validating the response. Preserve an uncertain request
ID for retry; do not silently queue offline writes. Limits include a best-effort
edge limit of 20 submissions per minute per IP, an atomic 30 reports per hour
per client ID, and a 100,000-report archive ceiling. These limit abuse but do not
prove identity or prevent coordinated false reports.

Errors use `{error,message}`: 400 invalid input, 404 station absent, 409 retry
conflict, 413 body over 2 KB, 415 invalid content type, 429 rate/capacity limit,
and 503 database unavailable. Public station browsing remains available when
the report archive is full.

## Health and coverage

`GET /health` checks D1 and returns service status, live mode, station count,
and write configuration. `GET /regions` describes the Canadian snapshot and
its attribution/import metadata. These report configuration and coverage,
not accuracy or freshness of every station. The API permits public CORS reads
and reports without cookies. No station moderation or account API is deployed.

The application disables automatic invocation logging and Worker tracing to
avoid recording location-bearing query strings. Provider-level connection
metadata may still exist. See [privacy](PRIVACY.md).
