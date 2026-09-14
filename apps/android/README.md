# Android · Kotlin / Jetpack Compose

A Kotlin/Compose Android app with a bundled Leaflet map in an isolated WebView and real OpenStreetMap station
coordinates. On first arrival, the app explains location use and requests Android's foreground
precise/approximate permission. Location is a bounded one-shot fix, never background tracking.
Declining permission offers Canadian city search. The current search area is always labelled;
a chosen city or cached area never masquerades as GPS. Pan/zoom and tap **Search this area**
to load another part of the map, or tap the location button to recenter.

Fresh installs show a Canada overview until you grant location access or choose an area.
No city is silently selected. Nearby searches round coordinates to three decimal places
(roughly 100 m) before sending them to the API.

The public HTTPS API is `https://openfuel.ca/api/v1`. Stations without a price remain visible
and reportable. Prices are actual community submissions, labelled **unverified**, with report
age. No invented starting prices or sample stations appear. Confirm prices at the pump.
Directions open the station's actual coordinates. Search, fuel grades, sorting, favorites,
Cards/List layouts and report forms are native Compose controls. Only the map uses WebView; no hosted web page or Metro server is required.

The last chosen city or area and its real stations appear immediately on reopening while the
network and foreground location refresh. A later GPS fix cannot replace a newer city/map
selection. Saved coordinates use two decimal places (about 1 km); cached distances are
recomputed from that coarse area, and older snapshots are migrated. Precise GPS stays in memory.
Offline states explicitly identify saved data. Failed or unacknowledged reports never change displayed prices;
retry IDs prevent duplicate writes. Suggestions remain clearly labelled local drafts.

Map tiles are fetched on demand from OpenStreetMap, with visible attribution, an app-specific
User-Agent and HTTP caching. A one-tile viewport buffer is used; bulk/offline downloading is not enabled. This
community tile service has usage limits and no availability guarantee; plan a supported tile
provider or self-hosting before large-scale adoption. There are no paid map keys or analytics.

Station brand logos load directly from the API's curated HTTPS URLs on Wikimedia, Shell, Co-op and Tempo, with 4 MiB memory and 8 MiB disposable device HTTP caches. No station logo binaries are
bundled or hosted by OpenFuel. The bundled map renderer anchors cached brand/price markers geographically and handles station taps. Camera movement reuses marker art within the same layer as the map tiles. Prices appear above the brand icon.

The approved curved F is used in the launcher and header. Location, fuel grades and Saved
share one opaque row. Drag the station sheet fully down to browse the whole map; **Show station list** restores it into List view. Recenter, information and **Search this area** are stacked in that
order. Status-bar icons remain dark on the app's light surface even in Android dark mode.

## Build

Requirements: JDK 17+, Android SDK 35, accepted Android SDK licences. The checked-in Gradle
wrapper downloads Gradle 8.11.1. No Cloudflare secrets belong in this app.

```sh
# From apps/android:
./gradlew :app:testDebugUnitTest
./gradlew :app:clean :app:assembleDebug
# Use your own HTTPS deployment:
./gradlew :app:assembleDebug -POPENFUEL_API_BASE_URL=https://your-worker.workers.dev/api/v1
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Output is `app/build/outputs/apk/debug/app-debug.apk`. Version 0.3.0-live (code 4), Android 8+
(API 26), signed with the local Android debug key. This is a directly installable development
APK; publishing to Google Play still requires a release signing process and store review.
The default download build shrinks unused code/resources and compresses native libraries,
keeping all four CPU architectures under the website's 25 MiB asset limit.
Always use `:app:clean :app:assembleDebug` for a download after instrumentation: incremental
APK packaging can retain unused ZIP padding when switching from unshrunk to compact builds.

## Verification

`./gradlew :app:testDebugUnitTest` covers exact price parsing, null price visibility, sorting,
and coordinate-based directions. `:app:connectedDebugAndroidTest -POPENFUEL_COMPACT_APK=false` tests real station parsing,
rejects sample geography, verifies GPS, actual station UI and persistent cache. Set an emulator
GPS fix in a seeded Canadian area first, e.g. `adb -s emulator-5554 emu geo fix -113.4938 53.5461`.
The public build's instrumented checks make GET requests only and skip the report-writing test.

Disable compact packaging when running Compose instrumentation: the test APK shares app
runtime classes that the shrinker can remove. This affects the test build only. Rebuild with
the defaults for the downloadable compact APK, then smoke-test that APK on a device.

To test the native price form against an isolated local D1 instance, run the local API on port
8787, seed it, and then use:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -POPENFUEL_API_BASE_URL=http://10.0.2.2:8787/api/v1 -POPENFUEL_EMULATOR_TEST=true
```

The explicit emulator flag permits HTTP only for `10.0.2.2`, and rejects release tasks. The
write test runs only against that local address. Never publish an emulator test build. Rebuild
without either property for the public HTTPS APK; its manifest disables cleartext traffic.

The 0.3.0 verification record and controlled-emulator screenshots are in
[`release-0.3.0.json`](../../evidence/android-native/release-0.3.0.json). Eight unit tests passed;
public HTTPS instrumentation ran five tests successfully, including the deliberately skipped
local-only report test. The exact compact APK was installed and visually checked separately.

Previous generated sample fixtures remain solely as isolated unit-test inputs and design
reference assets. They are never selected by the app's startup or live repository.

Map/data attribution: [OpenStreetMap contributors](https://www.openstreetmap.org/copyright),
ODbL. [Leaflet](https://leafletjs.com/) is bundled with its BSD-2-Clause licence.
[OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/).
