# Build and verification status — 6 September 2026

## Delivered

- Two standalone Canadian landing pages, responsive English/French website copy, local source export and embedded map demos.
- Map preview v10: all six custom application menus open at the bottom on mobile, including About. Real entry/exit animations, keyboard Escape, handle tap and downward drag, safe-area spacing and reduced-motion support.
- New native Android Kotlin/Compose source with station discovery, map illustration, native sheets, Maps intents, local price edits and pending station suggestions. This is not a WebView.
- Executable station-governance policy model and a **draft** PostgreSQL/PostGIS schema.
- Earlier SwiftUI starter and FastAPI/SQLite backend retained as references, not integrated with the new samples.

## Executed checks

| Check | Outcome | Scope |
| --- | --- | --- |
| Mobile application sheets | **66/66 assertions passed** | Six menus at 320, 390 and 430 px widths; bottom alignment, closing, default List, sorting, applied settings, Escape, short/long handle drags, reduced motion and no horizontal overflow |
| Landing pages | **14/14 assertions passed** | Two concepts, 1440 and 390 px widths; EN/FR toggle, mobile navigation placement, no horizontal overflow and actual embedded application rendering |
| Landing actions | **10/10 assertions passed** | French at 320 px, mobile source/build-status sheets, actual HTML file export, fuel-board changes and synthetic CSV download |
| Android domain logic | **32/32 checks passed** | Compiled with installed Kotlin/JVM compiler; exact prices, invalid inputs, sorting/filtering, local updates, map-query shaping and pending proposals |
| Source and Android XML | **12/12 assertions passed** | Well-formed XML, matching EN/FR resource keys, resolved string references, source-manifest permissions, no WebView/RN imports, native sheet and workflow presence |
| Station review policy | **20 unit tests passed** | Independent token deduplication, review thresholds, closure/reopening, conflict detection, public payloads, chain tampering and checkpoint rollback |

No JavaScript runtime errors occurred in the executed browser cases. Tests used local HTML rendering in Chromium with network image requests blocked; real remote logo rendering and real Google/Apple Maps handoff are not established by these tests. This is not a full accessibility, security, performance or cross-browser audit.

## Not built or verified

**There is no APK in this bundle.** The Android SDK, Gradle installation and Compose dependencies were not available. Direct dependency downloads failed in this environment. The recorded `android/build.sh` attempt stopped at its missing-tool check. The full Android UI has not been compiled against Android libraries, installed or run on a phone/emulator. Kotlin domain checks and XML validation are not an Android build.

The opt-in GitHub Actions workflow is configured to run Android tests, assemble a debug APK, fingerprint it and upload it as a workflow artifact. It has **not been run**. No production keys, credentials, repository, account, store listing or deployment were created.

The iOS reference has not received an Xcode build or the new visual design. The older backend tests were not rerun for this bundle. PostgreSQL/PostGIS schema installation, RLS policies, transactions, permissions and migrations were not executed against a database server. The Python policy is a model, not an authenticated moderation API.

Not implemented: live stations/prices, source import/update workers, secure moderator authentication, evidence uploads/redaction/deletion jobs, native real-map integration, independent audit witnesses, production backups, abuse controls or legally cleared logo packaging.

## Re-run available checks

From the repository root (Python 3.11+):

```sh
python -m unittest discover -s registry -v
python tests/check_source.py
# Playwright Python package and Chromium are prerequisites for browser checks.
python tests/check_landings.py
python tests/check_mobile_sheets.py
python tests/check_website_actions.py
# Kotlin/JDK are prerequisites; Android SDK is not required for these domain checks.
cd android
kotlinc app/src/main/java/ca/openfuel/prototype/Core.kt scripts/CoreChecks.kt -include-runtime -d core-checks.jar
java -jar core-checks.jar
```

Browser scripts default to `/usr/bin/chromium`; set `CHROMIUM_EXECUTABLE` for another installation. See `android/README.md` for the separate Android build prerequisites and test checklist.

All station addresses, positions, prices, report ages and histories remain synthetic. A passing test does not make them real, prove privacy or grant rights to third-party brands.
