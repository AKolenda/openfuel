# Hosting: Cloudflare for delivery, Supabase for PostgreSQL/PostGIS

**Chosen foundation:** Cloudflare Workers Static Assets serves website A, documentation and the
HTML preview; a read-only Worker handles `/api/v1/*`; Supabase PostgreSQL + PostGIS owns station
facts, accepted history and private intake. This is source/configuration, not a deployed account.

D1 uses SQLite. It is useful for other workloads, but this registry is deliberately written for
PostgreSQL roles/transactions and PostGIS. Do not create a second authoritative registry in D1.
Cloudflare is the CDN/read boundary, not an alternative copy of every station record.

```text
Browser ── Cloudflare static assets: /, /docs/, /preview/, /config.json
Clients ── /api/v1/* Worker ── fixed anonymous Supabase RPCs ── private PostGIS registry
Reviewed publisher [NOT IMPLEMENTED] ── narrow publisher role ── accepted-event transaction
GitHub protected environment ── reviewed SQL migrations ── staging, then production
```

The native apps and HTML map currently render fixed demo fixtures. The edge API is implemented
separately; changing an environment variable does not transform fictional geography into live data.
No anonymous contribution/publication API or publisher service is enabled in this delivery.

## 1. Configure two separate Supabase projects

Create a staging project and a production project. For a Canada-first service, review the available
Canada Central region and the actual data flows before selecting it. Primary database location does
not prove that CDN traffic, logs, email, authentication, backups or every provider component stays
inside Canada. No data-residency certification or Canadian incorporation is claimed by this source.

Run the migration checks locally first (Docker + pinned Supabase CLI), then follow DATABASE.md.
These migrations target a fresh Supabase PostgreSQL 15-compatible database. Check the real project's
version/extension schemas and run in staging before any production use. Never run the historical
`docs/provenance/registry-schema-draft.sql` alongside the canonical migrations.

## 2. Configure the Worker

Edit the public `SUPABASE_URL` under the appropriate environment in `wrangler.jsonc`. Replace the
placeholder with the actual project URL; change the Worker names to your owned, available names.
Set the project **publishable** key as a runtime binding. No service-role or secret key is needed:

```sh
# In the repo root, with Node 22+ and Cloudflare login completed:
npx --yes wrangler@4.93.0 login
npx --yes wrangler@4.93.0 secret put SUPABASE_PUBLISHABLE_KEY --env staging
# Paste the staging sb_publishable_... value interactively, not into a committed file.
```

A publishable key is not an admin secret. We use the runtime secret store here for deployment hygiene;
permissions come from the database grants/RLS and the limited public RPCs. The Worker explicitly
rejects secret/service-role credentials. The public key does not need to enter either native binary.
Repeat `secret put` for production using the production key, never the staging key.

## 3. Cloudflare Workers Builds settings

Connect the GitHub repo once you publish it. Use a separate staging Worker/build and production
Worker/build so a preview branch cannot silently use production bindings.

| Setting | Value for production |
| --- | --- |
| Repository root | `/` (the monorepo root) |
| Build command | `python tools/project.py site` |
| Deploy command | `npx --yes wrangler@4.93.0 deploy --env production` |
| Build watch paths | `apps/web/**`, `apps/docs/**`, `packages/**`, `docs/**`, `tools/**`, `services/edge/**`, `wrangler.jsonc`, root licence/config files |
| Build variable | `SKIP_DEPENDENCY_INSTALL=true` |
| Build variable | `PYTHON_VERSION=3.13` |
| Build variable | `NODE_VERSION=22` |
| Public build variable | `OPENFUEL_PUBLIC_ENV=production` |
| Public build variable | `OPENFUEL_PUBLIC_API_BASE_URL=/api/v1` |
| Public build variable | `OPENFUEL_PUBLIC_SOURCE_URL=/downloads/openfuel-source.zip` |

The assets directory is **`dist/site`**, already declared in Wrangler. Do not deploy the repository
root. Do not make a Cloudflare static build run `supabase db push`. Migrations are a separate,
approved job. The source download deliberately excludes local env values, keys, native binaries,
database files and personal deployment state; only templates and code should be published.

Build settings are under the Worker's **Settings → Build → Build variables and secrets**. They affect
the build process. **Settings → Variables and Secrets** affects the running Worker. These are distinct.
Manage nonsecret runtime vars in Wrangler; dashboard plaintext edits may be replaced by the next deploy.
Secrets must be configured separately for each Worker environment. Do not rely on inheritance.

## 4. Local preview and first deployment

```sh
cp .env.example .env              # Only allowlisted PUBLIC build values.
cp .dev.vars.example .dev.vars    # Local Worker bindings; ignored by packaging and Git.
python tools/project.py site
npm run dev:edge                 # Wrangler dev; local Worker + static assets.
# After staging DB review/migrations, with valid staging runtime bindings:
npm run deploy:staging
```

For a local Supabase stack instead of a cloud project, set `OPENFUEL_ENV=development`,
`SUPABASE_URL=http://127.0.0.1:54321`, and place the CLI's local **publishable key** (or **ANON_KEY** for the pinned older stack) in the
`SUPABASE_PUBLISHABLE_KEY` binding. Only that exact local endpoint in development accepts a legacy
anon JWT; Supabase still validates its signature. Production accepts only publishable keys and
an allowlisted Supabase cloud hostname. The example contains no actual key.

Smoke-test your actual deployment:

```sh
curl --fail https://YOUR_STAGING_WORKER.workers.dev/config.json
curl --fail https://YOUR_STAGING_WORKER.workers.dev/api/v1/health
curl --fail https://YOUR_STAGING_WORKER.workers.dev/api/v1/regions
curl --fail 'https://YOUR_STAGING_WORKER.workers.dev/api/v1/stations?region=demo-region'
```

An empty hosted database should return empty public results, not fabricated demo stations. The local
seed is not automatically uploaded to remote projects. The read API returns 503 when unconfigured,
502 for bounded provider failures and 405 for writes; it does not pass SQL/private upstream errors back.

The included Worker tests ran in Node with mocked fetch, not workerd or a live provider. Wrangler's
bundle/deploy dry-run workflow is configured but has not run here. Pin review and provider smoke tests
are deployment gates. No account, domain, keys or project IDs have been invented or provisioned.

## Pages instead?

Cloudflare Pages can serve just the static site: build `python tools/project.py site`, output `dist/site`.
However, this repo's `services/edge/worker.mjs` is a Worker, **not a Pages Function**. Deploy it separately
and point public API URLs at it, or use the recommended single Workers Static Assets deployment.
Do not assume uploading the HTML to Pages also deploys the database/read service.

## Operations

The Worker does not log bodies, coordinates or tokens, and detailed Worker observability is disabled
in the template. Infrastructure providers can still retain network/security metadata: inspect their
settings/contracts before any privacy promise. Add bounded rate limits and alarms before public use.
The current read endpoint deliberately does not cache to avoid stale age labels; versioned regional
snapshots and ETags should be introduced with client-side freshness recalculation and purge tests.

An evidence-image bucket is a separate private service, not the static asset root. Database backups
are not storage-object backups. Never publish raw submissions in a source ZIP or public snapshot.

### Official references (reviewed 2026-09-06)
- https://developers.cloudflare.com/workers/static-assets/
- https://developers.cloudflare.com/workers/ci-cd/builds/configuration/
- https://developers.cloudflare.com/workers/ci-cd/builds/build-image/
- https://developers.cloudflare.com/workers/configuration/environment-variables/
- https://developers.cloudflare.com/workers/configuration/secrets/
- https://developers.cloudflare.com/d1/
- https://supabase.com/docs/guides/database/extensions/postgis
- https://supabase.com/docs/guides/platform/regions
