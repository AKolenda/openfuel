# Database change workflow and maintenance

**One canonical path: `supabase/migrations/`.** The earlier SQL sketch is retained under
`docs/provenance/registry-schema-draft.sql`; it is not another install step. The existing SQLite
service is a local reference, not the production database or a Cloudflare filesystem database.
The new migrations are written for a fresh Supabase PostgreSQL 15-compatible project. They have
NOT been applied or SQL-engine-tested in the creation environment. The first staging run must pass
all database tests before this becomes a live dependency.

## Model and privilege boundaries

`app_private.stations` is the efficient current projection. `events` is accepted history;
`price_observations` links prices to accepted event sequence. `proposals` and `evidence` are private,
expiring intake. Stable UUIDs survive a rename/reopening; merges keep tombstones and history.
PostGIS geography and a GiST index support subsequent spatial queries; current versioned read RPCs
return bounded coarse-region pages instead of asking every phone to upload exact GPS searches.

RLS is enabled on all private tables; the schema and tables are revoked from anonymous, ordinary
authenticated and service-role API access. The only public capabilities are two reviewed RPCs:
`openfuel_stations_v1` and `openfuel_regions_v1`. They are SECURITY DEFINER functions with an empty
search path and allowlisted fields, not a user-supplied SQL/URL proxy. Do not expose `app_private`
in the Supabase API schema list. Test actual grants/RLS in every environment.

`openfuel_publisher` is a NOLOGIN capability role with only explicit publication functions. A separate
reviewed publisher service would need carefully provisioned credentials and real authorization.
That service is **not implemented**. The function is a trusted primitive, not proof that a user or
moderator really approved a change. Do not give the role to the Cloudflare public-read Worker or a
client. Membership publication is deliberately refused until programme eligibility is modeled.

Accepted station/price changes lock the short ledger transaction, enforce expected station versions,
append an event and update the projection atomically. Corrections/retractions are new events. The
hash protocol is explicitly `openfuel-registry-v1`: domain + NUL + previous hash bytes + int8 sequence
+ exact stored UTF-8 payload. It is NOT interchangeable with the older SQLite protocol. Retain exact
payload bytes. Independent witnesses/signed checkpoints and a public export worker still need wiring;
triggers/hashes alone do not stop an administrator rewriting history or prove the pump price true.

## Local → PR → staging → approval → production

Install Docker and Supabase CLI **2.48.3** (the reviewed compatibility pin, not a latest-version claim).
The local CLI creates development services and keys; none belong in the public source repository.

```sh
supabase start
supabase db reset --local       # DESTRUCTIVE to the LOCAL development database only.
supabase db lint --local --level warning
supabase test db
```

Local seeds are in `supabase/seed/demo.sql`. They create fictitious examples for DB tests. Never add
`--include-seed` to a remote push or place seed fixture SQL in the migrations directory. A remote
fresh project correctly has no published stations after migrations alone.

For the next schema change:

```sh
supabase migration new add_station_access_details
# Edit only the new timestamped SQL. Include appropriate grants/RLS/indexes.
supabase db reset --local
supabase db lint --local --level warning
supabase test db
# Commit SQL + related API changes + DB tests + updated contracts together.
```

Once a migration has reached any shared environment, treat it as immutable. Add a forward migration
for a fix; do not rename, delete or rewrite deployment history. Avoid remote Dashboard SQL Editor
schema edits: the database should be reconstructable from Git. Emergency fixes must be reconciled
into the migration history immediately with an reviewed record, not left as invisible drift.

## Controlled remote pushes

Copy `deploy/projects.example.json` to `deploy/projects.json` and set different real project refs.
Copy `env/migrations.env.example` to `.env.migrations`, or use protected environment secrets.
With the correct CLI and credentials configured:

```sh
python tools/database.py plan --target staging
python tools/database.py apply --target staging --confirm YOUR_EXACT_STAGING_PROJECT_REF
# Run staged API/privilege checks, restore rehearsal and backwards-compatibility tests.
python tools/database.py plan --target production
python tools/database.py apply --target production --confirm YOUR_EXACT_PRODUCTION_PROJECT_REF
```

The helper verifies the CLI pin, project allowlist, distinct environments and exact confirmation.
It lists migration history and performs `db push --dry-run` before a push. It never performs a remote
reset or seed. The CI version accepts only `main`, serializes deploys per target, and uses the staging/
production GitHub Environment. Configure required human reviewers for production before enabling it.

Choose **one** migration deployer. Do not run this workflow and an automatic Supabase Git integration
against the same target. Cloudflare website builds and app launches must never apply SQL migrations.
A failing database deployment does not justify silently resetting production or uploading local data.

## Backwards compatibility for installed apps

An app-store update is not installed by every user at the same time. Use expand/migrate/contract:

1. Add new nullable/defaulted structures and versioned read API support without breaking old fields.
2. Backfill in resumable, bounded batches using a separate job and checkpoint; do not hold a huge
   migration transaction while rewriting the entire history or downloading an external feed.
3. Deploy compatible server/client changes and observe errors/usage without creating user profiles.
4. Enforce constraints/remove old paths only after a documented support window and compatibility tests.

Keep large index builds/locks under review; `CREATE INDEX CONCURRENTLY` cannot be wrapped in an ordinary
transaction block. Stage-test the exact deployment method for that operation rather than pasting it
inside these transactional greenfield migrations. Set bounded lock/statement timeouts in the real
release process. Separate schema changes from importer content updates and sample data.

## Routine maintenance

| Frequency | What to verify |
| --- | --- |
| Every PR | Recreate local DB from migrations; pgTAP access/validation tests; native/API contract compatibility; source/fixture drift |
| Every deploy | Correct target; pending SQL diff and locks; valid restore point; staging smoke tests; controlled production approval |
| Scheduled | Backup status; DB and object-store retention; index/query latency; slow imports; duplicate/closure review backlog |
| Regular rehearsal | Restore to a separate environment; compare accepted-event heads and public projections; verify public exports exclude private evidence |
| Before release | Key rotation, rollback plan, RLS audit, licensed source inventory, actual device UI/network testing |

Use managed backups and point-in-time recovery where your plan and recovery objectives support it.
Backups/PITR are not free-form “undo migration” controls. Test restore time and document acceptable
data loss. Supabase database backups do not include Storage object contents: protect evidence objects
separately. Public snapshots are not private operational backups. Restoring must reconcile migrations,
publication sequence and client caches; never hide an incompatible restored state behind a healthy UI.

Expired private evidence needs deletion from database AND object storage, with a reviewed appeal
retention policy. No cleanup worker is included yet. A retained price history contains only accepted
station facts, not identities/receipt photos/device trails. Audit and review logs must follow that
same distinction. Keep operational exceptions private and documented.

## Status / commands not yet run

The 20 pgTAP assertions and clean-local-database GitHub workflow are included, but require Docker and
Supabase tooling unavailable here. SQL has been read, not executed. No production database, role login,
region, credentials, remote migration or backup plan has been provisioned by this source delivery.

Official references:
https://supabase.com/docs/guides/deployment/database-migrations
https://supabase.com/docs/reference/cli/supabase-init
https://supabase.com/docs/guides/database/postgres/row-level-security
https://supabase.com/docs/guides/database/extensions/postgis
https://supabase.com/docs/guides/platform/backups
