# Current architecture

Cloudflare static assets + read-only edge facade → Supabase/PostGIS curated public RPCs.
Supabase owns a protected event registry and private intake. A separately authorized publisher is
still to be implemented. This is not a deployed live service.

Read the detailed, current documents:
- HOSTING.md: provider split, build/runtime settings and environment isolation.
- ENVIRONMENT.md: public config versus Worker and migration credentials.
- DATABASE.md: canonical migrations, rights/role boundaries and forward deployment.
- NATIVE_PARITY.md: shared sample/resources, actual source work and visual acceptance gates.
- BUILD_STATUS.md: executed tests versus unexecuted provider/native builds.

`services/api` remains the original FastAPI/SQLite reference; its published OpenAPI is not the Worker
or PostgreSQL interface. Do not deploy its local database through a Cloudflare static-assets build.
Native screens use sample mode until a tested real-data/map adapter is introduced.

Cloudflare and Supabase are operators, not the public-data licence. Publishable keys are not admin
secrets and do not establish authorization by themselves; database grants/RLS and narrow RPCs define
what the read service may access. No client gets a database password or publication privilege.

Historical architecture/design material is retained under docs/provenance and docs/reference. It is
not a second active schema or an assertion that older build claims are current.
