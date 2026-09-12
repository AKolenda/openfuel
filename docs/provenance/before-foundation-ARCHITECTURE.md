# One repository, explicit component boundaries

The monorepo shares version control, documentation, API schema, fixture references, web assets,
tooling and CI. It does **not** force SwiftUI and Compose through a JavaScript runtime or pretend their
current screens are identical. No React Native, Flutter, WebView or shared UI bridge was introduced.

```text
apps/web ─────────────┐
apps/docs ────────────┼── static build → dist/site (/, /docs/, /preview/)
apps/web/preview ─────┘                 local demo; no live API

apps/ios/OpenFuelCore ─── HTTP ─── services/api ─── local SQLite ledger
       ↑                               ↑
apps/ios SwiftUI             packages/contracts/openapi.json

apps/android Compose ─── local synthetic sample data (not yet API-connected)
services/registry ────── pure policy model + unexecuted PostgreSQL/PostGIS draft
```

packages/fixtures/api-stations.json is the canonical API-response example copied into Swift tests.
The current Android fixture intentionally remains local in Core.kt and differs from that API response.
The repository records that gap rather than inventing an adapter. packages/design records the approved
visual target; it is not yet a cross-platform design-token generation pipeline.

A future backend integration PR should add explicit Android/API mapping and native contract tests,
then a visual SwiftUI map port should produce simulator screenshots. Keeping those changes in one PR
with affected schema, tests and docs is the benefit of this layout. Consolidation alone is not the port.

The public station registry is proposed to use PostgreSQL/PostGIS. The incoming detailed source and
moderation discussion is in docs/reference/DATABASE_AND_STATION_LIFECYCLE.md. Preserve source licences
and private evidence separation; no live Supabase/Appwrite account or deployment has been created here.
