# Live mobile verification — 12 September 2026

These results apply to the real-station clients and the public `https://openfuel.ca/api/v1`
API. Earlier prototype evidence in this repository may show sample stations; it does not
describe the current mobile release.

## Downloadable native Android APK

| Field | Verified value |
| --- | --- |
| Build | Kotlin / Jetpack Compose / MapLibre, debug signed |
| Version | `0.2.0-live`, version code `3` |
| Package | `ca.openfuel.prototype` |
| Minimum Android | Android 8.0 / API 26 |
| Tested Android | API 35 x86_64 emulator |
| Supported CPU libraries | arm64-v8a, armeabi-v7a, x86, x86_64 |
| API | `https://openfuel.ca/api/v1`, live mode |
| Size | **23,634,212 bytes** |
| SHA-256 | `215e588ba24e12ec0cbecfe0a5d7808c66d890203f270adbdcd6102fa3cb1234` |
| Manifest | Cleartext network traffic disabled |
| Signature | Android `apksigner verify` passed |

The artifact is built at `apps/android/app/build/outputs/apk/debug/app-debug.apk` and
distributed at `https://openfuel.ca/downloads/openfuel-android.apk`. Code/resource shrinking
and compressed native libraries keep the universal APK under the website's 25 MiB file limit.

**Automated checks:** five Kotlin unit tests passed. Four instrumentation tests passed
against the seeded, isolated local D1 API. Those checks cover fresh arrival and explicit
area selection, foreground GPS, real nearby station parsing, rejecting sample responses,
keeping unknown prices visible, station details, persistent caching, and a native report
submitted to the local database and recovered after reloading.

The local report test wrote only to `http://10.0.2.2:8787/api/v1`. It did not submit a test
price to production. Compose instrumentation runs with compact shrinking disabled because
the test APK shares runtime classes that the shrinker can remove; the emulator-test flag
does this automatically. See [Android build instructions](../apps/android/README.md).

**Exact final APK smoke check:** the compact APK with the hash above was installed on the
emulator and connected to the public HTTPS API. Foreground location permission, a real
OpenStreetMap map, the location dot, **179 nearby Edmonton stations**, and green tappable
station markers were verified. Tapping the Hughes map marker opened that station's native
details, including “No report yet,” directions and report controls. No test price was sent.
The final public APK also passed the five unit tests after the map-marker change.

## Expo Go client

Expo SDK 57: TypeScript checking, four domain tests, Android Hermes bundle export and
all **21 Expo Doctor checks** passed. Expo Go ran on the same API 35 emulator and loaded
179 stations from the public HTTPS API, with no invented prices. Real map tiles, station
markers, the foreground location dot and native station details were inspected. A map
marker opened the correct native station sheet; panning exposed **Search this area**.

The map uses bundled Leaflet 1.9.4 through the Expo Go-supported WebView. A device test found
that Expo Go's shared Google Maps credential failed authorization, so the client now uses
the OpenStreetMap map without a map key. The remaining controls are native React Native.
See [Expo Go setup](../apps/expo/README.md) for the Metro/QR workflow and map attribution.

## Screenshot provenance and limits

All coordinates shown below are **controlled emulator coordinates**, set to central
Edmonton for testing. These are not a person's measured location. Screenshots show the
public live API and unreported pump prices, not the locally submitted test price.

- [Final native map](../evidence/android-native/live-public-emulator-map.png)
- [Native station selected from its map marker](../evidence/android-native/live-public-emulator-station.png)
- [Expo Go with the public live API](../evidence/expo/live-public-emulator-map.png)

The freshly attached domain initially had a cached negative DNS response on this machine.
Android Private DNS was temporarily pointed to `dns.google` to verify public HTTPS access,
then restored to its previous setting. No production endpoint was substituted in the APK.

No physical phone was modified or tested. The APK is a directly installable development
build, not a Google Play release. Expo Go requires Metro; no EAS build or permanent hosted
Expo update was published. iOS device behavior was not tested in this run.
