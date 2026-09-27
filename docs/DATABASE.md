# Database

The live service uses Cloudflare D1 (`openfuel-data`) in deployment and Wrangler's local SQLite-backed
D1 in development. `services/live/migrations/0001_live.sql` and `0002_area_cache_and_budget.sql`
define tables, constraints, indexes and transactional triggers; `tools/seed_database.py` imports the
snapshot in `packages/data` without touching community reports.

- `stations`: OSM-derived ID, coordinates and JSON display fields. Nearby searches read the same
  snapshot from static files (`data/stations/`); this table only validates reports.
- `current_prices`: one integer price, observation time, source and 0.5° area per station and grade.
- `area_prices`: one row per area holding all of its current prices as JSON and a version that
  changes with every price change there. Stations requests read prices only from this table.
- `price_reports`: accepted community reports with report ID, install ID and server time.
- `usage_budget`: rows read and written per UTC day, for the Worker's daily D1 budget.
- `cities`, `market_averages`, `dataset_metadata`: imported reference data. The Worker answers
  city search, regions and monthly averages from the bundled files instead.

A trigger publishes each accepted report to `current_prices`, and triggers on `current_prices`
update its area's `area_prices` row in the same transaction, including for manual changes. Invalid
prices/grades fail SQL constraints. Triggers block more than 30 reports/hour per install or
100,000 total reports. All SQL values use prepared bindings. Install IDs are never included in
station responses or receipts. The public API does not accept station creation, arbitrary SQL or
reviewer actions.

```sh
npm run db:local
npm run db:seed:local
npx wrangler d1 migrations list openfuel-data --remote
npm run db:remote
```

Local and remote are distinct stores. Deploying website assets never uploads local database files.
Existing applied migrations are not rewritten; future changes use new numbered migrations. Tests
execute the actual schema and triggers against SQLite. [Running costs](RUNNING_COSTS.md) gives the
deploy order and how to rebuild `area_prices` if it is ever edited out of step.

The six-station prototype schema in `services/edge/migrations` is historical. The earlier
`supabase/migrations` directory remains a future registry reference, not the live migration path.
No Supabase resources were created. The FastAPI/SQLite service is also a separate reference and is
not deployed. Historical database documentation is in docs/provenance.
