# Prototype database

The active prototype uses Cloudflare D1 in deployment and Wrangler's local SQLite-backed D1 in
development. `services/edge/migrations/0001_prototype.sql` defines tables, constraints, indexes and
transactional triggers; `0002_samples.sql` inserts the shared six-station fixture once.

- `stations`: stable sample ID, display order, JSON map/display fields.
- `current_prices`: one integer price and observation timestamp per station and grade.
- `price_reports`: accepted sample price changes, generated report ID, install ID and server time.

A trigger updates current_prices atomically after a report insert. Invalid prices/grades fail SQL
constraints. A trigger blocks more than 30 reports/hour per install or 10,000 total reports. All SQL
values use prepared bindings. IDs are private operational identifiers, never included in station
responses. The public API does not accept station creation, arbitrary SQL or reviewer actions.

```sh
npm run db:local
npm run db:remote
npx wrangler d1 migrations list openfuel-prototype --remote
```

Local and remote are distinct stores. Deploying website assets never uploads local database files.
Existing applied migrations are not rewritten; future changes use new numbered migrations. Tests
execute the actual schema and triggers against SQLite; deployment smoke tests exercise remote D1.

The earlier `supabase/migrations` directory remains a future registry reference, not the prototype's
active migration path. No Supabase resources were created. The FastAPI/SQLite service is also a
separate reference and is not deployed. Historical database documentation is in docs/provenance.
