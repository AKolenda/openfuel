# Working prototype verification

The website and database are deployed to Cloudflare. The Android debug APK is built and downloadable.
All clients use shared synthetic station/price data; this is not nationwide live fuel pricing.

Website: https://openfuel-prototype.openfuel-monorepo.workers.dev/
App: https://openfuel-prototype.openfuel-monorepo.workers.dev/preview/
APK: https://openfuel-prototype.openfuel-monorepo.workers.dev/downloads/openfuel-android.apk

## Executed in this workspace

| Check | Result | Evidence |
| --- | --- | --- |
| Cloudflare D1 | Three migrations applied locally and remotely; six seeded stations | services/edge/migrations, remote migration history |
| Cloudflare Worker | Deployed, HTTPS health/stations/site/docs return 200 | evidence/prototype-cloud-smoke.json |
| Shared report persistence | HTTPS POST followed by independent GET reflects saved price | evidence/prototype-cloud-smoke.json |
| Worker/SQLite tests | 56 passed, including validation, atomic publication, retries and limits | evidence/prototype-edge-tests.txt |
| Python repo/API/registry | 125 passed | evidence/prototype-python-tests.txt |
| Browser checks | 129 passed across desktop/mobile, no JS exceptions | evidence/browser-results.json |
| Browser interactive checks | Real Cloudflare report, second-tab read, favorites, local offline shell/cache | evidence/prototype-web/results.json |
| Android | Native Gradle compilation, 4 JVM tests, debug APK, 3 emulator tests including cloud reports | apps/android/BUILD_EVIDENCE.md |
| Swift core | 21 passed; separate live Cloudflare integration passed | apps/ios/BUILD_EVIDENCE.md |
| SwiftUI | Source syntax parsed; model logic checked; no Apple SDK build | apps/ios/BUILD_EVIDENCE.md |
| Shared generated assets | Six generated mobile outputs match canonical fixture/design | tools/generate_mobile.py --check |
| npm dependencies | Wrangler4.131.1 pinned; npm audit reported zero vulnerabilities | package-lock.json |

Native Android screenshots are actual emulator captures under evidence/android-native; they are
not relabelled HTML screenshots. Existing strict native pixel parity approval remains unclaimed.

## Remaining scope

A signed IPA and Apple SDK/SwiftUI simulator build require macOS/Xcode and a signing team for
physical iPhones. No App Store or Google Play release is claimed; Android is a debug sideload APK.

Real station coverage, licensed/live prices, real geographic maps, authenticated station moderation
and an audited production service remain future work. The sample API intentionally permits anonymous
sample reports with bounded storage/rate limits. No paid map/data subscription was provisioned.

Earlier unexecuted foundation claims and logs remain under docs/provenance and historical evidence;
use this document and platform BUILD_EVIDENCE.md files for the current prototype.
