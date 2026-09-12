# Prototype configuration

The deployed sample API base is:
`https://openfuel-prototype.openfuel-monorepo.workers.dev/api/v1`.

Web clients use same-origin `/api/v1`. `/config.json` contains only an explicit public allowlist:
`environment`, `apiBaseURL`, `sourceURL`, `mode: prototype`, and `writesEnabled: true`. Actual
connectivity is established through API responses, not these build-time flags. No private env value
is serialized. The `OPENFUEL_PUBLIC_ENV`, `OPENFUEL_PUBLIC_API_BASE_URL`, and
`OPENFUEL_PUBLIC_SOURCE_URL` build inputs remain supported.

Android uses `OPENFUEL_API_BASE_URL` Gradle property/environment variable with the deployed URL as
default. iOS uses the project-generated public API URL; see apps/ios/README.md for override syntax.
No native app contains Cloudflare account credentials. Both expose sample mode explicitly.

Wrangler reads `wrangler.jsonc`: binding `DB` is D1, `ASSETS` is the static site and `REPORT_LIMITER`
is the public report limiter. Wrangler OAuth remains in the user's private CLI configuration.
Do not copy it to `.env`, source archives or client configuration. `.env` / `.dev.vars` / local
platform config files, signing keys and database files are excluded from source and static builds.

Legacy Supabase variables in retained migration/reference files are not used by the deployed prototype.
