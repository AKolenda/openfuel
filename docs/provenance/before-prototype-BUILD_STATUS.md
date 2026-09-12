# Build and integration status — Cloudflare/Supabase foundation pass

This is an updated source foundation, not a deployed database, a ready APK/IPA or a visually verified
native release. The distinction below is part of the release gate, not a suggestion that core tests
prove everything.

## Actually executed in this environment

| Check | Result | What it establishes |
| --- | --- | --- |
| Repository/env/deployment-guard/packaging/visual-tool unit tests | 60 passed | Public allowlist, secret input exclusions, exact target confirmation, source integrity and comparison-tool behavior |
| Existing FastAPI/SQLite service | 45 passed | Local reference service/ledger; not Postgres or a cloud deployment |
| Existing station-review policy | 20 passed | Policy logic, not live reviewer authentication or accepted DB updates |
| New read-only edge facade | 35 Node tests passed | Fixed/bounded public RPC handling, failure sanitation, read-only methods, no client credentials forwarded, local-dev restrictions; mocked provider |
| Kotlin core | 35 checks passed | Shared fixtures, price/filter rules, camera math and draft validation; not Compose or Android storage/runtime |
| Swift core | 16 XCTest tests passed | Existing 5 API/core tests plus 11 preview/persistence/camera/config tests; not SwiftUI Apple SDK compilation |
| Browser/HTTP integration | 122 checks passed | Built site/docs/reference routes, source download, mobile/desktop behavior and new handbook chapters |
| Shared mobile generation | 6 generated outputs match | Platform fixture/token source and copied map resources are in sync |
| SwiftUI source parse | Syntax parsed successfully | Parser only; not type-checking against SwiftUI/UIKit |
| Reference screen capture | 8 HTML screens captured | Browser design reference only, clearly labelled in packages/design/goldens/html |

Browser navigation to localhost was blocked by the environment policy. HTTP routes were checked
separately; browser tests executed the built local resources inlined into a Chromium document.
Remote logos were disabled/blocked. No JavaScript errors or unintended external requests occurred
in the tested default pages. This is not an end-to-end public-hosting/device-network test.

## Not executed / intentionally blocked

- Wrangler installation, bundle dry-run, workerd execution, real Cloudflare deploy, DNS/custom domain.
- Supabase CLI/local stack, actual PostgreSQL migration execution, 20 pgTAP assertions, grants/RLS tests
  against a real server, linked staging/production push, restore/PITR drill or production role provisioning.
- Full Android Gradle/Compose compilation, instrumentation/screenshots, signed APK or store release.
- XcodeGen/Xcode/SwiftUI build, XCTest UI capture, physical iPhone/iPad run or store release.
- Native pixel/interaction parity approval. The visual gate exits BLOCKED when baselines are absent;
  there are deliberately no relabelled browser screenshots masquerading as native app results.
- Real map/price ingestion, backend connection in native demo screens, authenticated public proposals,
  reviewed publisher service, source-licence reconciliation, private evidence deletion job, externally
  witnessed production checkpoints or public feed availability.

The full Android and iOS build commands were attempted and failed clearly on missing platform tools.
The DB helper stopped before any operation because the pinned CLI is unavailable. Native screenshot
workflow files are provided, not evidence that they ran. Current test output is under evidence/.

## Release gates to run next

1. Rebuild a LOCAL Supabase DB from canonical migrations and execute pgTAP; review actual grants.
2. Apply exact same history to a separate staging project, configure a nonprivileged read Worker, then
   smoke-test genuine URLs/provider failure boundaries and backups. Never seed/reset production.
3. Build native apps; run native-review on fixed emulators/simulators; review actual screens against the
   HTML. Complete small/large, French, accessibility text-size and keyboard cases before approval.
4. Wire a real map/data adapter with explicit loading/offline/stale states; keep samples labelled and
   separate. PUBLIC env settings alone do not enable live data or a publication service.
5. Review operational security, source/artwork rights, privacy/retention, signing and migration ownership.
