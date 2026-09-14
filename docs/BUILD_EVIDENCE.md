# Build evidence

The current sideload is **0.3.2-live**, version code **6**, package
`ca.openfuel.prototype`, Android 8+ (API 26).

- Size: **3,811,809 bytes**, 81% smaller than 0.3.0's 20,128,593 bytes.
- SHA-256: `1efc643a86d092447aa0c99c3c23addfadbadddf60efb15c628ad30d63227ff7`.
- API: `https://openfuel.ca/api/v1`; cleartext disabled.
- Debug certificate matches the previous APK, allowing an in-place update.
- Eight JVM tests and six Android API-35 emulator checks passed; one local-only
  reporting test skipped against the public API. No test prices were submitted.

The map now uses bundled Leaflet inside an isolated WebView. Tiles, logos and
prices share one rendering layer, fixing the separate Canvas overlay's drift.
The rest of the interface remains Kotlin/Compose. JavaScript/CSS ship locally;
only OpenStreetMap tile requests are allowed from the map. Station data and bounded
cached logo images come through the native client. File/content access,
geolocation inside WebView, cleartext and web navigation are disabled.

Fuel grades, About, data-source details and manual refresh live in Settings.
The selected grade appears in the results heading. Attribution is a small label
at the bottom-left edge. Search this area appears at the top after roughly 750
metres of camera movement, preserving zoom and position when used.

Only the panel handle drags/resizes the sheet. List gestures scroll independently,
and pulling down at the top refreshes the data. The map remains full-size behind
the panel. Logo encodings are reused and station serialization runs off the UI
thread; GPS updates only move the location dot. Gesture tests verify that scrolling
and sheet expansion do not resize/recenter the map, Settings retain fuel selection,
and pull-to-refresh produces a newer successful GET snapshot. No device-wide FPS
improvement is claimed without a comparable performance benchmark.

The new gesture regression uses real swipe and tap input, verifies changed station
IDs and saved map coordinates, checks unchanged camera/zoom, and measures marker
anchor drift during animated panning. Its isolated visual price is never sent to
the API. The compact APK was separately installed for visual smoke checking.
See [`release-0.3.2.json`](../evidence/android-native/release-0.3.2.json).

The full station audit covers all 12,543 entries; 17 former-Husky records have
source-linked Tempo corrections. Remaining ambiguous records are flagged, not
automatically renamed or removed. See [data review](../packages/data/README.md).
Live backend tests: 26 passed; legacy edge: 56 passed. Repository/reference API/
policy checks: 125 passed. Built web browser checks: 235 passed.

## Expo and Swift

Current Expo source passes TypeScript checking and six domain tests. The previous
release also passed six embedded Leaflet browser regressions and Android Hermes export. Those checks cover bounded
logo dimensions, marker names/taps/updates, price handling and coarse snapshots.
The approved F assets are wired into the header and app icon. A final layout adjustment
keeps Search this area above Show stations when the results sheet is hidden.
Browser regressions intercept test data; they do not verify native Expo Go rendering.
The earlier [Expo emulator screenshot](../evidence/expo/live-public-emulator-map.png)
is from 12 September, before this branding/layout update. No new native Expo Go run,
EAS APK or hosted Expo update is claimed. Metro is required for Expo Go.

Swift source now uses the live station API, MapKit, foreground CoreLocation, coarse
area caching and remote station logos, with the approved F and current controls.
The previous release passed thirty portable tests with one opt-in skip, plus
syntax, localization/plist and asset checks. This host has no Swift compiler, so
the current Settings, native List/refresh and map layout changes have not been
compiled here. Earlier read-only live API evidence is
dated separately. **Apple SDK compilation, iPhone UI verification and an IPA still
require macOS/Xcode.** See [iOS evidence](../apps/ios/BUILD_EVIDENCE.md).

Historical sample-era logs and earlier native screenshots do not verify this release.
