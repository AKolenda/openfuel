# Historical prototype API

This document records the earlier six-station sample service. The current public
app uses [LIVE_API.md](LIVE_API.md) and real imported geography.

# Prototype API v1

Base: `https://openfuel-prototype.openfuel-monorepo.workers.dev/api/v1`.
All records are synthetic. Anonymous reads and sample price reports are enabled. No client needs a
secret. This contract is separate from the earlier FastAPI OpenAPI reference.

## Read stations

`GET /stations` returns `{mode:"prototype", is_demo:true, region:"demo-region", stations:[...],
generated_at:"ISO timestamp", next_cursor:null}`. Station display/map fields match
`packages/mobile/stations.json`. Additional fields: `ages` (per-grade minutes), `observedAt`
(per-grade ISO timestamp), `priceSources` (`sample` or `community-prototype`), and `memberDiscount`
(integer, default 0). Missing fuel prices are null. Coordinates x/y refer to sample artwork, never
latitude/longitude. Optional query: fuel=regular|premium|diesel, region=demo-region, limit=1..100.

## Report a sample price

`POST /reports`, Content-Type application/json:

```json
{"station_id":"parkside","fuel_type":"regular","price_milli":1429,"client_id":"a-persistent-install-uuid"}
```

`price_milli` is an integer 500..3999 (50.0..399.9 cents/L). `client_id` is a random install ID,
16..80 URL-safe characters, used for rate limits; it is not a user account. Optional `request_id`
(16..80 URL-safe characters) provides idempotent retries. Reusing it with different data returns409.

Success: HTTP201 `{ok:true,is_demo:true,report:{id,station_id,fuel_type,price_milli,observed_at}}`.
An identical request_id retry returns200. The new price is immediately available on GET /stations.

Errors use `{error,message}`: 400 invalid fields/JSON, 404 station missing, 413 body over2KB,
415 wrong content type, 429 report limit, 503 database unavailable. Do not mark an offline report as
saved. Clients should retain the form and invite retry. Report client IDs are never returned.

## Health and coverage

`GET /health` checks the database and returns `ok`, `database`, `writesEnabled`, `is_demo`, and
`station_count`. `GET /regions` describes the one sample region. CORS allows public cross-origin
clients without cookies; OPTIONS supports GET/HEAD/POST. No other mutations are exposed.
