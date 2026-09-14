# Public alpha verification

OpenFuel now uses a real Canadian station directory and community pump reports.
The public site is https://openfuel.ca/ and map is https://openfuel.ca/preview/.
Cloudflare serves the website, API, source archive and compact Android APK.

| Check | Current result |
| --- | --- |
| Cloudflare D1 | Live schema applied locally and remotely; 12,543 real stations, 510 city entries and 54 dated monthly averages imported |
| Pump data | Zero seeded station prices; community observations remain separate from regional averages |
| Live Worker and SQLite | 26 tests passed: real geography, required location, city search, reports, retries, ordering, validation and limits |
| Legacy edge regression | 56 tests passed |
| Repository, Python API and policy | 125 tests passed (60 repository, 45 reference API, 20 policy) |
| Local API | Read-only checks passed for Edmonton, Calgary, Toronto, Montréal, Vancouver and Yellowknife; see `evidence/live-api-local.json` |
| Worker deployment | Wrangler dry run and deployment passed; `openfuel.ca` custom domain and TLS verified |
| Public API/downloads | Six-city read-only smoke passed; downloaded APK/source matched local bytes. See `evidence/live-api-production.json` and `evidence/live-downloads.json` |
| Web | 235 browser checks passed across desktop and small mobile screens; real OSM tiles and city fallback verified separately, including the deployed domain. See `evidence/browser-results.json` |
| Native Android | Eight JVM and five public-API emulator checks passed; one local-only report test skipped. Version 0.3.1-live/code 5; 3,779,165-byte HTTPS APK, debug signed, cleartext disabled. Real gesture tests verify new-area search, stable marker anchors and Cards-to-List sheet restoration. See `evidence/android-native/release-0.3.1.json` |
| Expo | Typecheck, six domain tests, six embedded-map browser checks and Android export passed for current source. Prior Expo Go device evidence predates this branding/layout update; no new native Expo Go run is claimed |
| Swift | Source updated for price-above-logo markers, List restoration and Tempo logos. Prior portable results: 30 passed, one skip. Current host has no Swift compiler; Apple SDK/device verification still requires a Mac |
| Source publication | Source and archive Gitleaks scans passed with no leaked credentials; reachable Git history is scanned before public push. See `PUBLICATION_AUDIT.md` |

The production verification command is read-only:

```sh
python3 tools/smoke_live.py --output evidence/live-api-production.json
```

Automated tests use local/in-memory databases for synthetic pump observations.
They never submit made-up prices to the public service. Real device location is
permission-based; emulator/browser tests use controlled test coordinates.

Android remains a development sideload APK, not a Google Play release. The current
SwiftUI source is migrated to the live API and approved F identity; compiling and
verifying its Apple frameworks and iPhone UI still requires macOS/Xcode. Expo is a
separate React Native development client requiring Metro and compatible Expo Go.

Station metadata can be incomplete or outdated. Public reports are unverified;
there is no licensed automatic station-price feed, authenticated station moderation
or scheduled report-retention job. Missing pump prices remain unknown, and dated
monthly averages must never be interpreted as individual station prices.

Historical sample-era logs remain under `evidence/` and `docs/provenance/`. Their
old deployment and test results do not verify the current live implementation.

The first domain checks used current public DNS A records with TLS verification because
the workstation resolver still cached the pre-deployment absent record. This affects
name resolution temporarily; certificate validation was never bypassed.
