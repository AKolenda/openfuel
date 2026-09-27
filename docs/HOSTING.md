# Cloudflare hosting

The active Worker is `openfuel`, the D1 database is `openfuel-data`, and the
public custom domain is [openfuel.ca](https://openfuel.ca/). The configuration is
`wrangler.jsonc`; active migrations are in `services/live/migrations/`. The
older `openfuel-prototype` service used six synthetic stations and is historical.

```sh
npm ci
npm run db:local
npm run db:seed:local
npm run dev
# Verify locally before changing the configured remote database:
npm run db:remote
npm run db:seed:remote
npm run deploy
```

Wrangler uses the operator's existing OAuth login or a scoped token supplied
outside the repository. Forks must configure their own account, D1 database and
domain before remote commands. Read the account and resource configuration before
seeding or deploying. The import upserts geography and monthly references while
preserving existing community reports. Never reset remote D1 as part of deployment.

The site build also writes the station snapshot as static files, one per 0.5° area
under `/data/stations/`, which the Worker reads for nearby searches. D1's `stations`
table, seeded from the same snapshot, only validates reports. Apply new migrations
before deploying the Worker that uses them, and seed a changed snapshot before
deploying, so the files and the table match. [Running costs](RUNNING_COSTS.md)
gives the exact order.

Build Android before the final web build so the APK and checksum included under
`/downloads/` match the current app. The source ZIP uses an explicit allowlist
and excludes credentials, build caches and local database state. The live API
contract is published at `/openapi.json` when generated in `packages/contracts/`.

## Usage and operations

The architecture uses Workers Static Assets (website, map code, station files and
downloads) and D1 (current prices, reports and a daily usage count). Map tiles come
from OpenFreeMap's free public service. It has no paid fuel-data feed or map
subscription. Actual cost and capacity depend on the Cloudflare account plan and
usage; consult [Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/)
and [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/). Monitor
account-wide requests, D1 rows read/written and storage in the dashboard. Dataset
imports consume writes. A nearby search covers at most 20 areas, returns at most
200 stations and makes at most one D1 query for prices; now and then a Worker instance also adds
its usage to the daily budget table after responding (see RUNNING_COSTS.md).

The Worker keeps its own daily D1 budget below the Workers Free limits. Once it is
spent, the API answers `503 {"error":"spending_cap", ...}` until midnight UTC; the
website and Android app keep saved prices on screen under a notice.
[Running costs](RUNNING_COSTS.md) lists the budget variables (`D1_DAILY_READ_BUDGET`,
`D1_DAILY_WRITE_BUDGET`), the optional donate links for the Worker
(`OPENFUEL_DONATE_URL`), website, Android and Expo, and the Cloudflare dashboard
settings: plan, budget alert and rate limiting rule.

The report archive is capped at 100,000 records, with 30 accepted reports per hour
per installation and a best-effort edge limit of 20 attempts per minute per IP.
These are abuse controls, not authentication or price verification. The source
snapshot has no pump prices; production reports must be real observations.

Automatic invocation logging and Worker tracing are disabled because nearby
search URLs contain coordinates. Application error messages omit those query
strings and private report fields. Preserve that behavior when adding telemetry;
see [privacy](PRIVACY.md). OpenFreeMap is donation-funded with no availability
guarantee; OpenStreetMap's tile servers, used when WebGL is missing or OpenFreeMap
refuses its tiles, have their own usage policy. There is no bulk/offline
tile-download feature.

A private backup can be written to ignored local storage:

```sh
npx wrangler d1 export openfuel-data --remote --output .local/openfuel-backup.sql
```

Backup contents include reports and installation IDs; do not add them to Git or
source downloads. Restore procedures and recovery testing remain operational work.
Schema changes belong in new numbered migrations, verified locally first.
