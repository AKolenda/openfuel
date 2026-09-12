# OpenFuel for Expo Go

A React Native client for the shared OpenFuel Cloudflare API. It opens a real map,
asks for foreground location access and finds real nearby fuel stations. Prices are
community reports: an unreported price stays blank and the station stays visible.
The app has station search, Canadian city search, map-area search, three fuel grades,
distance/price sorting, saved stations, directions, a report form and a local cache.

## Run on your phone

Use Node.js 22.13 or newer and **Expo Go for SDK 57**. Get the matching Android
version from [Expo's official download page](https://expo.dev/go?sdkVersion=57&platform=android&device=true).
If your installed Expo Go reports an incompatible SDK, select SDK 57 on that page.

From the monorepo root:

```sh
npm --prefix apps/expo ci
npm --prefix apps/expo start
```

Scan the QR code shown by Metro with Expo Go on Android (or the Camera app on
iPhone). The phone and computer need to be on the same network. Keep Metro running.
Allow location access when the app asks. If you decline, use the area name in the
header to choose a city, or pan the map and tap **Search this area**.

For a USB-connected Android emulator, use the explicit emulator serial so you do
not accidentally select a connected physical phone:

```sh
adb -s emulator-5554 reverse tcp:8081 tcp:8081
adb -s emulator-5554 shell am start -a android.intent.action.VIEW -d exp://127.0.0.1:8081
```

Install the matching Expo Go on the emulator first. `npm run android` uses Expo's
device picker; if several devices are connected, select the intended one.

This is a development client that needs Metro. The independently built, downloadable
Kotlin APK lives in [`../android`](../android/README.md) and runs without Metro.
There is no published Expo account, EAS Update link or permanent hosted QR code.

## API configuration

The default is `https://openfuel.ca/api/v1`. To use your own deployment, copy
`.env.example` to `.env.local`, change `EXPO_PUBLIC_API_URL`, then restart Metro.
This URL is intentionally public and is embedded in the JavaScript bundle. Never
put Cloudflare tokens, database credentials or other secrets in `EXPO_PUBLIC_*`.

For local API tests through the Android emulator, use
`EXPO_PUBLIC_API_URL=http://10.0.2.2:8787/api/v1`. Keep test reports on a local or
isolated test API; do not submit invented prices to real production stations.

The API must return `mode: "live"` and `is_demo: false`. The client refuses sample
responses and synthetic stations. Prices are integer CAD millidollars per litre;
1499 is displayed as 149.9 cents per litre. Reports require a standard pump price
actually observed today and are labelled unverified. The app reuses a request ID
when retrying an uncertain report submission from the same form.

## What is stored and sent

The phone stores the most recent station response, search area, saved station IDs
and a random installation ID in AsyncStorage. A network failure displays saved data
only when it belongs to the searched area, with its saved age. Saved prices can be
outdated. Map tiles themselves are not downloaded for offline use.

Nearby searches send coordinates rounded to three decimal places (roughly 100 m)
to OpenFuel. No background location tracking is enabled. Reports send station ID,
fuel grade, price, random installation ID and request ID; no GPS coordinates.
OpenStreetMap receives on-demand map tile requests. Station metadata comes from
OpenStreetMap contributors under ODbL; attribution remains visible on the map.
Opening directions hands the destination to Google Maps.

## Checks

```sh
npm --prefix apps/expo run typecheck
npm --prefix apps/expo test
npm --prefix apps/expo run export:android
cd apps/expo
npx expo-doctor
```

Tests cover cents/millidollar conversion, missing-price visibility, rejection of
sample data and invalid coordinates, and honest report-age labels. Bundle export
checks Android JavaScript compilation; it is not an APK build or a device test.

## Map configuration

SDK 57 and its compatible native module versions are pinned in `package-lock.json`.
The map uses bundled Leaflet 1.9.4 inside
[`react-native-webview`, included in Expo Go](https://docs.expo.dev/versions/latest/sdk/webview/).
The list, station details, location permission, search and report forms remain native
React Native controls. This avoids dependence on Expo Go's shared Google Maps key,
which failed authorization in device testing. No map key or hosted HTML page is needed.

Leaflet code/CSS and its BSD licence are in `src/vendor`; the map works as soon as
on-demand tiles load. WebView HTTP caching, an OpenFuel User-Agent and a valid
referrer are enabled. Tile prefetch and bulk/offline downloads are disabled.
The community tile service follows the
[OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/)
and has no availability guarantee. Choose a supported provider before large-scale use.
The separate Kotlin APK uses native MapLibre. Neither project is a signed store release.
