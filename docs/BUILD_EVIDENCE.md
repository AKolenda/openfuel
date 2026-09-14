# Build evidence

The current sideload is **0.3.1-live**, version code **5**, package
`ca.openfuel.prototype`, Android 8+ (API 26).

- Size: **3,779,165 bytes**, 81% smaller than 0.3.0's 20,128,593 bytes.
- SHA-256: `2e0cb1ccc082233d6d82680b181397edffbebfd81a47adbc2924efb438975d5a`.
- API: `https://openfuel.ca/api/v1`; cleartext disabled.
- Debug certificate matches the previous APK, allowing an in-place update.
- Eight JVM tests and five Android API-35 emulator checks passed; one local-only
  reporting test skipped against the public API. No test prices were submitted.

The map now uses bundled Leaflet inside an isolated WebView. Tiles, logos and
prices share one rendering layer, fixing the separate Canvas overlay's drift.
The rest of the interface remains Kotlin/Compose. JavaScript/CSS ship locally;
only OpenStreetMap tile requests are allowed from the map. Station data and bounded
cached logo images come through the native client. File/content access,
geolocation inside WebView, cleartext and web navigation are disabled.

Search this area stays available below recenter and information. It searches the
camera position without changing zoom or recentering. A visible Show station list
button outside the results scaffold restores a fully hidden Cards sheet into
List view. Prices appear above brand icons. The approved F, opaque compact filter
row, foreground location and coarse saved-area cache remain active.

The new gesture regression uses real swipe and tap input, verifies changed station
IDs and saved map coordinates, checks unchanged camera/zoom, and measures marker
anchor drift during animated panning. Its isolated visual price is never sent to
the API. The compact APK was separately installed for visual smoke checking.
See [`release-0.3.1.json`](../evidence/android-native/release-0.3.1.json).

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
the current marker layout, List restoration and Tempo host edits have not been
compiled here. Earlier read-only live API evidence is
dated separately. **Apple SDK compilation, iPhone UI verification and an IPA still
require macOS/Xcode.** See [iOS evidence](../apps/ios/BUILD_EVIDENCE.md).

Historical sample-era logs and earlier native screenshots do not verify this release.
