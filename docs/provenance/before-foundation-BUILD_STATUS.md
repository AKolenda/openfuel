# Verification for this consolidation

Executed evidence lives in `evidence/results.json` and sibling log files. The docs site displays those
logs; prior archive reports are preserved only as historical inputs under docs/provenance.

## Checks that can run here

Repository/asset/path/licence integrity; FastAPI/SQLite backend tests; registry-policy tests; generated
OpenAPI drift test; Swift fixture parity; Kotlin core executable checks; Swift package tests; and browser
integration across the combined website, docs and preview. These are re-run during this consolidation.
See the evidence records for actual result counts and commands rather than a hand-maintained badge.

## Not verified here

- Full Android/Compose compilation, emulator installation or actual device screenshots. Gradle and
  Android SDK are not installed in this runtime. A debug APK has not been created by consolidation.
- Full SwiftUI/iOS application build or Xcode simulator run. This is Linux without Xcode/XcodeGen.
- GitHub workflow execution, app-store signing/publication, database deployment or SQL/RLS execution.
- Pixel parity of either native app with the approved HTML design. The imported iOS starter is an
  earlier API-first list/detail screen, not the map-first Android/web version.
- Remote logo loading or brand permission. Browser tests block remote hosts and exercise initials.

The two supplied archives had identical Android implementation files. This delivery consolidates
actual source and improves repo wiring; it does not claim to incorporate unavailable source from a
separate chat. The cleaned documentation was corrected to remove those unsupported native-build claims.

## This checkpoint's executed results

| Check | Passed | Evidence |
| --- | ---: | --- |
| Repository integrity / manifests / package hygiene / documentation data | 23 tests | evidence/repository.txt |
| Reference API / event ledger | 45 tests | evidence/api.txt |
| Registry policy | 20 tests | evidence/registry.txt |
| Android standalone Kotlin core | 32 checks | evidence/android-core.txt |
| iOS Foundation package | 5 XCTest methods | evidence/ios-core.txt |
| Built static routes + website/docs/preview browser assertions | 110 checks | evidence/browser-results.json |

The browser environment blocks even localhost navigation by administrator policy. HTTP routes and
source ZIPs were checked through Python's HTTP client. The browser checks therefore rendered the same
built HTML with its local CSS/JavaScript/images inlined. No browser policy was bypassed. The test
harness uses normal localhost browser navigation in an unrestricted environment and records which
mode ran. This verifies rendering/interaction, but is not a claim that browser-network navigation was
successful here. Both native core suites ran as real compiled executables; full native UI did not.

`evidence/android-build-unavailable.txt` and `evidence/ios-build-unavailable.txt` record the actual
preflight failures. No APK, IPA or native simulator screenshot is included.
