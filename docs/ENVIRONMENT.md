# Configuration

The public API base is `https://openfuel.ca/api/v1`.

Web clients use same-origin `/api/v1`. `/config.json` contains only an explicit public allowlist:
`environment`, `apiBaseURL`, `sourceURL`, `mode: live`, `writesEnabled: true` and `donateURL`.
Actual connectivity is established through API responses, not these build-time flags. No private
env value is serialized. The `OPENFUEL_PUBLIC_ENV`, `OPENFUEL_PUBLIC_API_BASE_URL`,
`OPENFUEL_PUBLIC_SOURCE_URL` and optional `OPENFUEL_PUBLIC_DONATE_URL` build inputs are read from
`.env` or the environment. A donate URL must be HTTPS without credentials; when set, the station
map shows donate links.

Android uses the `OPENFUEL_API_BASE_URL` Gradle property/environment variable with the public URL as
default, and an optional `OPENFUEL_DONATE_URL`. Expo reads `EXPO_PUBLIC_API_URL` and the optional
`EXPO_PUBLIC_DONATE_URL`. iOS uses the project-generated public API URL; see apps/ios/README.md for
override syntax. No client contains Cloudflare account credentials, and all refuse sample responses.

Wrangler reads `wrangler.jsonc`: binding `DB` is D1, `ASSETS` is the static site (the Worker also
reads the station files through it) and `REPORT_LIMITER` is the public report limiter. Worker
variables `D1_DAILY_READ_BUDGET` (default 4,000,000) and `D1_DAILY_WRITE_BUDGET` (default 80,000)
set the daily D1 budget, and optional `OPENFUEL_DONATE_URL` adds a donate link to the limit answer;
see RUNNING_COSTS.md. Wrangler OAuth remains in the user's private CLI configuration.
Do not copy it to `.env`, source archives or client configuration. `.env` / `.dev.vars` / local
platform config files, signing keys and database files are excluded from source and static builds.

Legacy Supabase variables in retained migration/reference files are not used by the deployed service.
