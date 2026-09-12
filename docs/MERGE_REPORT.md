> Historical merge report. The later Cloudflare/Supabase and map-first native pass is documented in BUILD_STATUS.md, HOSTING.md and NATIVE_PARITY.md.

# What was merged, and what was not

Two inputs were inspected by exact path and SHA-256 (docs/provenance/inputs.json). The Android archive
contains 26 files; all 26 paths exist in the full Canada archive. The only differing common file is
the root README. **All Android implementation/configuration files match exactly.** No arbitrary newer
Android fork was chosen and no Android code was discarded in favour of another version.

| Supplied location | Canonical monorepo location |
| --- | --- |
| web/landing-a.html | apps/web/index.html + styles.css + app.js |
| web/landing-b.html | apps/web/designs/variant-b/ |
| web/app-preview.html | apps/web/preview/ |
| android/ | apps/android/ |
| ios-starter/ | apps/ios/ |
| backend-reference/ | services/api/ |
| registry/ | services/registry/ |
| assets/ | packages/assets/ |
| docs/ | docs/reference/ and docs/provenance/ |
| separately provided docs review HTML | apps/docs/ (layout retained, content reconciled) |

The cleaned docs review described a different native workspace with files/modules/UI tests that were
not supplied in either ZIP. This repository does not fabricate those files or import its old test
counts as fresh evidence. The handbook now describes the actual imported Compose project and the
API-driven SwiftUI starter. It explicitly records their different levels of UI/integration progress.

The backend's final test expected contracts/openapi.json, but neither input included that contract.
The schema is restored by exporting the actual FastAPI app and moved to packages/contracts/openapi.json;
the test now checks that canonical path. The Swift fixture is also copied to packages/fixtures and
checked for byte equality against the bundled test resource. No live data was fetched or relabelled.

New code concerns repository tooling, path/configuration repair, documentation and integration tests.
Native UI redesign, PostgreSQL execution, release signing and backend wiring for Android are not part
of this consolidation. Licences are updated per the project owner's AGPL-3.0-only choice while earlier
scope documents and third-party notices remain preserved.
