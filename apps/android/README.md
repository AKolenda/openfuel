# Android · Kotlin / Jetpack Compose

A Kotlin/Compose Android app with a bundled MapLibre map (Leaflet without WebGL) in an isolated WebView and real OpenStreetMap station
coordinates. On first arrival, the app explains location use and requests Android's foreground
precise/approximate permission. Location is a bounded one-shot fix, never background tracking.
Declining permission offers Canadian city search. The current search area is always labelled;
a chosen city or cached area never masquerades as GPS. Pan and tap **Search here**
to load another part of the map, or tap the location button to recenter.

Fresh installs load no map, and request no tiles, until you grant location access or choose an area.
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
The saved area's fresh stations are requested as the app starts, before the map loads; the GPS fix
at startup in the same saved area moves only the location dot and distances. A later fix in the area
on screen, such as Use my location, also asks for its stations again once they are more than a minute
old, or once the daily limit has reset. API answers are reused for as
long as their Cache-Control allows (15 seconds for stations), except for Refresh and just after a report.
For 30 seconds after the server confirms a report, station lists (fresh answers and the saved snapshot)
show it wherever they have no price or an older one for that station and fuel, because other Worker
instances and caches can still answer with the earlier price; a newer price from someone else is kept.
The confirmed report is stored only for those 30 seconds, so it also survives a quick restart.
When OpenFuel's database reaches its free daily limit, saved prices stay on screen under a notice
giving the local time live prices return. Cloudflare's daily request limit (error 1027, which it sends
as JSON to this app and as a 429 page otherwise) gets the same notice until midnight UTC; its other
429 answers, such as 1015, do not.

The base map is OpenStreetMap data from OpenFreeMap's vector tiles, drawn by the bundled MapLibre in
OpenFuel's style ([packages/map-style](../../packages/map-style/README.md)). Tiles load on demand with
visible attribution, an app-specific User-Agent and HTTP caching; bulk/offline downloading is not enabled.
OpenFreeMap is free and has no usage limits, but is donation-funded with no availability guarantee.
Without WebGL, or if OpenFreeMap refuses its tiles, the map uses OpenStreetMap's raster tiles, which
have usage limits. There are no analytics.

Station brand logos load directly from the API's curated HTTPS URLs on Wikimedia, Shell, Co-op and Tempo, with 4 MiB memory and 8 MiB disposable device HTTP caches. No station logo binaries are
bundled or hosted by OpenFuel. MapLibre draws the base map, the cached brand/price chips and the location dot in one WebGL frame and handles station taps; each distinct chip is drawn once, so a pinch or pan moves no page elements. Prices appear above the brand icon.

The approved curved F is used in the launcher and header. The area selector opens a dropdown
with location, recent cities and city search. **Search here** appears beside it after a pan;
the distance threshold scales with zoom. Fuel grades, About and data-source details live in
Settings. Tapping overlapping station markers opens details with an **Also at this spot** row
for the other stations, without changing the map zoom.

Drag the station panel's header or list to move between collapsed, half-open and expanded.
The list starts scrolling when the panel is expanded; pulling down from the list's top lowers
it again. The panel always retains its header. Tap the refresh button in the header to update
prices. Sort opens a dropdown, and Saved changes the heading and empty state to saved stations.
Map attribution and the location button move above the panel and fade out at its expanded stop.
The map stays full-size during drags and becomes invisible while covered by the expanded list.
Menu sheets slide closed and
crossfade between their contents. Cached logo encoding, station serialization and atomic
snapshot writes run off the UI thread. Snapshots from older versions migrate automatically.
No device-independent frame-rate improvement is claimed.
Status-bar icons remain dark on the light app surface even in Android dark mode.

## Build

Requirements: JDK 17+, Android SDK 35, accepted Android SDK licences. The checked-in Gradle
wrapper downloads Gradle 8.11.1. No Cloudflare secrets belong in this app.

```sh
# From apps/android:
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
# Use your own HTTPS deployment:
./gradlew :app:assembleRelease -POPENFUEL_API_BASE_URL=https://your-worker.workers.dev/api/v1
# Optional donate link (Gradle property or environment variable):
./gradlew :app:assembleRelease -POPENFUEL_DONATE_URL=https://example.org/donate
adb install -r app/build/outputs/apk/release/app-release.apk
```

`OPENFUEL_DONATE_URL` must be an HTTPS URL without credentials, quotes or whitespace; the build
fails otherwise. When set, Settings and About show **Donate to cover the database and map costs**
(opening the browser), and the daily-limit notice and report form get a Donate button. Without it
no donate UI appears.

The download is `app/build/outputs/apk/release/app-release.apk`, version 0.3.3-live (code 7),
Android 8+ (API 26). It is non-debuggable, with R8 code shrinking, resource shrinking and
compressed native libraries. It uses this machine's Android debug key, as previous downloads
did. Build published updates with the same keystore and compare their certificate digests
with `apksigner verify --print-certs` before publishing, so existing installations keep their
data. CI artifacts use the runner's own key and cannot replace that signing step. Google Play
publishing still requires a release signing process and store review.

`python3 tools/project.py android-build` tests and builds both variants;
`python3 tools/project.py release-assets` prepares the release APK, checksum and source archive.
For development and WebView inspection, use `:app:assembleDebug` instead.

## Verification

`./gradlew :app:testDebugUnitTest` covers exact price parsing, null price visibility, sorting,
coordinate-based directions and recognition of the daily-limit answers. `:app:connectedDebugAndroidTest -POPENFUEL_COMPACT_APK=false` tests real station parsing,
rejects sample geography, verifies GPS, actual station UI and persistent cache. Set an emulator
GPS fix in a seeded Canadian area first, e.g. `adb -s emulator-5554 emu geo fix -113.4938 53.5461`.
The public build's instrumented checks make GET requests only and skip the report-writing test.

Disable compact packaging when running Compose instrumentation: the test APK shares app
runtime classes that the shrinker can remove. This affects the test build only. Build `:app:assembleRelease` for the downloadable APK, then smoke-test that APK on a device.

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
The 0.3.2 record, [`release-0.3.2.json`](../../evidence/android-native/release-0.3.2.json), lists
eight unit tests and six public HTTPS instrumentation tests passed, with the local-only report
test skipped. Both records predate the OpenFreeMap base map and the daily-limit notice.

Previous generated sample fixtures remain solely as isolated unit-test inputs and design
reference assets. They are never selected by the app's startup or live repository.

Map/data attribution: [OpenStreetMap contributors](https://www.openstreetmap.org/copyright),
ODbL; [OpenFreeMap](https://openfreemap.org) and [OpenMapTiles](https://www.openmaptiles.org/).
[Leaflet](https://leafletjs.com/) (BSD-2-Clause), [MapLibre GL JS](https://maplibre.org/) (BSD-3-Clause)
and its Leaflet binding (ISC) are bundled with their licences.
[OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/).
