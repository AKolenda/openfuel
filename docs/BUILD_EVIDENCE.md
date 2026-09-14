# Live mobile verification — 13 September 2026

Current Android release evidence is in
[`release-0.3.0.json`](../evidence/android-native/release-0.3.0.json).
Tests use controlled emulator coordinates. No physical phone was modified and no
invented pump price was submitted to production.

## Downloadable Android APK

| Field | Verified value |
| --- | --- |
| Version | `0.3.0-live`, version code `4` |
| Package | `ca.openfuel.prototype` |
| Minimum Android | Android 8 / API 26 |
| Tested device | API 35 x86_64 emulator |
| CPU libraries | arm64-v8a, armeabi-v7a, x86, x86_64 |
| API | `https://openfuel.ca/api/v1`, live mode |
| Size | **20,128,593 bytes**, about 15% smaller than the prior 23,634,212-byte APK |
| SHA-256 | `8b686503b0e87cb6a61da99002f21eae87e6878cc16fc7b40d8fea3fa98d31c0` |
| Network | Cleartext disabled |
| Signature | Debug signed; certificate matches the previous APK for updates |

The [APK download](https://openfuel.ca/downloads/openfuel-android.apk) runs without
Metro. The approved curved F appears in the launcher/header. Status icons remain
dark on the light app surface. Location, grades and Saved share one opaque row.
The results sheet hides completely with a downward swipe and restores through a
small Show stations control. Search this area sits below recenter and information.

Brand logos load directly from curated public hosts and appear in the list and map.
A native Canvas overlay draws cached marker bitmaps over MapLibre, skips offscreen
markers and handles station taps. This avoids the invisible sprite images observed
with the previous renderer. The app restores a two-decimal saved area and cached
stations before fresh requests finish; stored distances use that coarse area.

Eight JVM tests and four emulator checks passed; the local-report-writing check
was skipped against the public API. The exact compact APK was separately installed
and checked for real tiles, visible logos, location, marker-to-detail selection,
map-only controls and restoration. Earlier isolated local report evidence remains
historical; backend report validation and persistence also pass in-memory tests.

Build a clean compact APK after instrumentation, since switching packaging modes
can leave unused padding in an incremental APK. Code/resource shrinking, compressed
native libraries and excluding debug UI tooling keep this universal build below
Cloudflare's 25 MiB asset limit. This is a development sideload, not a store release.
See [Android instructions](../apps/android/README.md).

## Expo and Swift

Current Expo source passes TypeScript checking, six domain tests, six exact embedded
Leaflet browser regressions and Android Hermes export. Those checks cover bounded
logo dimensions, marker names/taps/updates, price handling and coarse snapshots.
The approved F assets are wired into the header and app icon. A final layout adjustment
keeps Search this area above Show stations when the results sheet is hidden.
Browser regressions intercept test data; they do not verify native Expo Go rendering.
The earlier [Expo emulator screenshot](../evidence/expo/live-public-emulator-map.png)
is from 12 September, before this branding/layout update. No new native Expo Go run,
EAS APK or hosted Expo update is claimed. Metro is required for Expo Go.

Swift source now uses the live station API, MapKit, foreground CoreLocation, coarse
area caching and remote station logos, with the approved F and current controls.
Thirty portable tests passed, with one opt-in live test skipped; source syntax,
localization/plist and asset checks passed. Earlier read-only live API evidence is
dated separately. **Apple SDK compilation, iPhone UI verification and an IPA still
require macOS/Xcode.** See [iOS evidence](../apps/ios/BUILD_EVIDENCE.md).

Historical sample-era logs and earlier native screenshots do not verify this release.
