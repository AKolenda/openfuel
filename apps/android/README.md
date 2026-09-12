# Android · Kotlin / Jetpack Compose

A native Android app with MapLibre's native OpenGL map and real OpenStreetMap station
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
Cards/List layouts and report forms are native Compose controls, with no WebView.

Last confirmed real stations and their search coordinates cache on the device. Offline states
explicitly identify saved data. Failed or unacknowledged reports never change displayed prices;
retry IDs prevent duplicate writes. Suggestions remain clearly labelled local drafts.

Map tiles are fetched on demand from OpenStreetMap, with visible attribution, an app-specific
User-Agent and HTTP caching. Tile prefetch and bulk/offline download are not enabled. This
community tile service has usage limits and no availability guarantee; plan a supported tile
provider or self-hosting before large-scale adoption. There are no paid map keys or analytics.

## Build

Requirements: JDK 17+, Android SDK 35, accepted Android SDK licences. The checked-in Gradle
wrapper downloads Gradle 8.11.1. No Cloudflare secrets belong in this app.

```sh
# From apps/android:
./gradlew :app:testDebugUnitTest :app:assembleDebug
# Use your own HTTPS deployment:
./gradlew :app:assembleDebug -POPENFUEL_API_BASE_URL=https://your-worker.workers.dev/api/v1
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Output is `app/build/outputs/apk/debug/app-debug.apk`. Version 0.2.0-live (code 3), Android 8+
(API 26), signed with the local Android debug key. This is a directly installable development
APK; publishing to Google Play still requires a release signing process and store review.
The default download build shrinks unused code/resources and compresses native libraries,
keeping all four CPU architectures under the website's 25 MiB asset limit.

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

Previous generated sample fixtures remain solely as isolated unit-test inputs and design
reference assets. They are never selected by the app's startup or live repository.

Map/data attribution: [OpenStreetMap contributors](https://www.openstreetmap.org/copyright),
ODbL. [MapLibre Android](https://maplibre.org/maplibre-native/android/examples/getting-started/).
[OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/).
