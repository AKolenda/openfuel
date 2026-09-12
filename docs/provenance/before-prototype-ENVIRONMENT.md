# Environment variables: four scopes, not one shared secret file

| Scope | Template | Real values go in | Public? |
| --- | --- | --- | --- |
| Static-site build | `.env.example` | local `.env` or Cloudflare **Build** variables | Yes: emitted to `/config.json` |
| Worker runtime | `.dev.vars.example` | local `.dev.vars`; deployed `wrangler.jsonc` vars + Worker secrets | Runtime only, although publishable API key is inherently public |
| DB migrations | `env/migrations.env.example` | local `.env.migrations` or protected CI environment secrets | No |
| Android/iOS compile config | platform templates | Gradle properties/env; ignored `Config/Local.xcconfig` | Yes: compiled into a distributed binary |

`example.env` would be only a convention. This repo includes conventional `.env.example` **and a real
reader**: `tools/public_config.py`. Only three variables are allowed into public output:

```dotenv
OPENFUEL_PUBLIC_ENV=development
OPENFUEL_PUBLIC_API_BASE_URL=/api/v1
OPENFUEL_PUBLIC_SOURCE_URL=/downloads/openfuel-source.zip
```

Process environment values override local `.env`. Unknown variables are ignored, not serialized.
Invalid/non-HTTPS/credential-bearing URLs, query secrets and malformed environment names are rejected.
`/config.json` explicitly reports `mode: demo` and `writesEnabled: false`. Website and native demo
fixtures do not become live simply because a valid API URL was supplied. Never display fictional
map coordinates or sample prices as real records when adding the future live-data adapter.

## Worker bindings

`SUPABASE_URL` is nonsecret and versioned separately in each Wrangler environment. Bind the matching
`sb_publishable_...` key using `wrangler secret put SUPABASE_PUBLISHABLE_KEY --env ...`. It authorizes
only anonymous/public RPCs, not arbitrary table or write access. This read-only worker must never
receive `sb_secret_...`, `service_role`, database passwords, migration access tokens or signing keys.

Local `.dev.vars` is consumed by Wrangler; it does NOT get copied to `dist/site`. Wrangler treats
`.dev.vars` and `.env` differently; using `.dev.vars` avoids mixing local runtime bindings with this
project's public site-build configuration. Keep actual files out of Git. Copies with names such as
`env/production.env` are also excluded by the source packager and static copier.

## Migration credentials

```sh
cp env/migrations.env.example .env.migrations
cp deploy/projects.example.json deploy/projects.json
```

The migration helper reads only `SUPABASE_ACCESS_TOKEN`, `SUPABASE_DB_PASSWORD`, and the optional
`SUPABASE_PROJECT_ID`. In CI use secrets for token/password and an environment-scoped public project
ID variable; the helper verifies the latter against the selected checked-in project allowlist.
Project IDs are not passwords. Staging and production must be distinct IDs.

Never place these credentials in Cloudflare build variables, JavaScript, Info.plist, Android resources,
Gradle BuildConfig, screenshots, or shell commands with literal passwords. The public site does not
need privileged credentials to build; the Worker does not need them to serve public prices.

## Native compile configuration

Android reads `OPENFUEL_API_BASE_URL` from a Gradle property or environment, validates HTTPS, and writes
it as a public `BuildConfig.API_BASE_URL`. `OPENFUEL_DATA_MODE` is a Gradle property and only `demo` is
accepted until a real map/data adapter is implemented. Run from the repo root with tools installed:

```sh
export OPENFUEL_API_BASE_URL=https://YOUR_STAGING_WORKER.workers.dev/api/v1
python tools/project.py android-build
```

For iOS copy `apps/ios/Config/Local.example.xcconfig` to `Local.xcconfig` in the same folder and edit
only its public base URL. The `https:/$()/` syntax is intentional: literal `//` starts an xcconfig
comment. XcodeGen applies `Config/Base.xcconfig` to Debug/Release. Do not add secrets to xcconfig.

Neither a `.env` variable name nor an encrypted CI setting makes a value secret after it is copied
into an app binary. All users can ultimately inspect client configuration. The new screens remain
an offline demo and do not consume the configured public API yet; the previous iOS APIClient remains
a separately tested reference adapter, not an authenticated production client.

## Current safeguards and limits

Executed tests cover public allowlisting/precedence, invalid URLs, private file exclusion from static
assets/source archives, and target-guarded migration commands. These are not a secret-scanner audit of
arbitrary user-added files. Review diffs and run an independent secret scanner before publishing.
Rotate any real key that has leaked; removing it from a later ZIP or commit is not rotation.

Official references:
https://developers.cloudflare.com/workers/configuration/environment-variables/
https://developers.cloudflare.com/workers/configuration/secrets/
https://developers.cloudflare.com/workers/ci-cd/builds/configuration/
https://supabase.com/docs/guides/api/api-keys
