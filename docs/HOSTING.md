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

Build Android before the final web build so the APK and checksum included under
`/downloads/` match the current app. The source ZIP uses an explicit allowlist
and excludes credentials, build caches and local database state. The live API
contract is published at `/openapi.json` when generated in `packages/contracts/`.

## Usage and operations

The architecture uses Workers Static Assets and D1. It has no paid fuel-data feed
or map subscription. Actual cost and capacity depend on the Cloudflare account
plan and usage; consult [Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/)
and [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/). Monitor
account-wide requests, D1 rows read/written and storage in the dashboard. Dataset
imports consume writes, and spatial queries have bounded candidate/result counts.

The report archive is capped at 100,000 records, with 30 accepted reports per hour
per installation and a best-effort edge limit of 20 attempts per minute per IP.
These are abuse controls, not authentication or price verification. The source
snapshot has no pump prices; production reports must be real observations.

Automatic invocation logging and Worker tracing are disabled because nearby
search URLs contain coordinates. Application error messages omit those query
strings and private report fields. Preserve that behavior when adding telemetry;
see [privacy](PRIVACY.md). OpenStreetMap public tiles have separate usage rules;
there is no bulk/offline tile-download feature.

A private backup can be written to ignored local storage:

```sh
npx wrangler d1 export openfuel-data --remote --output .local/openfuel-backup.sql
```

Backup contents include reports and installation IDs; do not add them to Git or
source downloads. Restore procedures and recovery testing remain operational work.
Schema changes belong in new numbered migrations, verified locally first.
