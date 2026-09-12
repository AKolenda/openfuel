# Change log

## Cloudflare / Supabase foundation and native-map alignment

Added isolated env templates, real public-config generation, read-only Worker/RPC contract, canonical
PostGIS migrations/pgTAP assertions, guarded staging/production deployment workflow, shared native
fixtures/map/tokens, a SwiftUI map-first implementation, improved Compose scene/state, local persistence,
and native screenshot harnesses with a fail-closed human-approval gate. No live deployment or native
binary is implied. See BUILD_STATUS.md for what actually ran.

# Consolidation checkpoint

- Combined two supplied archives into one source tree; Android duplicates are byte-identical.
- Made website variant A the root page and retained variant B as an optional design route.
- Added the cleaned, searchable docs UI without the top-right licence badge or menu dots.
- Replaced duplicated embedded map documents with one shared preview route.
- Extracted web CSS/JavaScript and deduplicated embedded image assets for normal source editing.
- Retained native Android and the earlier SwiftUI/API starter without claiming a visual port.
- Restored the missing OpenAPI contract referenced by backend tests; added fixture drift checking.
- Centralized root build/test/package commands, licence scope and read-only CI workflows.
- Recorded actual new checks separately from the incoming archives' historical logs.
