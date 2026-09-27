# OpenFuel

OpenFuel is an open-source, Canada-first fuel-station map with a working web app,
a native Kotlin Android app, a React Native project for Expo Go, and current
SwiftUI iOS source. One Cloudflare Worker serves real station locations from
static snapshot files and public community price reports from a D1 database. The
SwiftUI app still needs an Apple SDK build and simulator verification on a Mac.

- [Public repository](https://github.com/AKolenda/openfuel)
- [Website](https://openfuel.ca/) and [station map](https://openfuel.ca/preview/)
- [Android APK](https://github.com/AKolenda/openfuel/releases/latest/download/openfuel-android.apk) — development build, Android 8+
- [API health](https://openfuel.ca/api/v1/health) and [source download](https://github.com/AKolenda/openfuel/releases/latest/download/openfuel-source.zip)

Allow location when you enter the app to find nearby stations. If permission is
unavailable, search a Canadian city or choose an area on the map. The app uses
real geographic coordinates, shows stations with unknown prices, and keeps saved
station information available when the connection fails. A remembered area,
rounded to two decimal places, lets cached stations appear immediately on return
while the app refreshes and requests current location. It is labelled as a saved
area, not a fresh GPS fix. The station sheet can be hidden to use the full map.

The website and Android app draw OpenFreeMap vector tiles with MapLibre in
OpenFuel's own style ([map style](packages/map-style/README.md)), with
OpenStreetMap raster tiles when WebGL is missing or OpenFreeMap refuses. Expo
uses OpenStreetMap raster tiles and the SwiftUI app uses Apple MapKit. Otherwise
Google Maps and Apple Maps only open directions. If OpenFuel's database reaches
its free daily limit, the website and Android app keep saved prices on screen
until midnight UTC; see [running costs](docs/RUNNING_COSTS.md).

Station logos load directly from a small set of remote image providers; missing
or unavailable logos fall back to initials. OpenFuel does not bundle or proxy
station logo images. Ordinary browser/device caches can reuse those downloads.
See the [brand URL catalog](packages/brands/README.md) and [privacy details](docs/PRIVACY.md).

The included snapshot contains **12,543 OpenStreetMap fuel stations**. Station
coverage and metadata may be incomplete. **No pump prices are invented or seeded.**
A station price appears after somebody reports an actual observation; reports are
unverified and include their age. July 2026 Statistics Canada monthly averages are
separate reference information, never station pump prices. See [data provenance](packages/data/README.md).

## Run locally

Use Node.js 24 and Python 3.11+:

```sh
npm ci
npm run dev
# http://localhost:8787/
```

The checked-in public snapshot supplies the local seed; no cloud login or external
fuel-data subscription is needed. Local D1 state stays in ignored `.wrangler/`.
The seed updates imported geography and dated reference data without changing
community reports. A plain static server renders the shell but does not supply the API.

## Run on Android

The downloadable Kotlin APK runs independently of a development computer. It is
a debug sideload build, not a Google Play release. [Native build instructions](apps/android/README.md)
and [verification status](docs/BUILD_STATUS.md) describe what has been tested.

For React Native development with Expo Go:

```sh
npm --prefix apps/expo ci
npm run expo
```

Scan Metro's QR code using a compatible Expo Go installation. Keep Metro running
and the phone on the same network. [Expo instructions](apps/expo/README.md) cover
SDK compatibility, emulator use, API configuration and its separate native map setup.

The current SwiftUI source uses the same live API, native MapKit, foreground
location, remote station logos and coarse saved areas. Portable Swift core and
read-only API checks are separate from Apple framework validation: **no Apple SDK
build, simulator run or IPA is claimed**. Building and testing the iPhone app
requires macOS and Xcode; device distribution also requires signing. See the
[iOS instructions](apps/ios/README.md). Expo offers a separate React Native iPhone
development path.

## Monorepo

| Path | Role |
| --- | --- |
| `apps/web` | Website and real Leaflet/MapLibre station map at `/preview/` |
| `apps/docs` | Searchable handbook |
| `apps/android` | Kotlin/Jetpack Compose app and emulator checks |
| `apps/expo` | React Native/Expo Go client |
| `apps/ios` | Current SwiftUI/MapKit source and independently testable Swift core; Apple SDK validation pending |
| `services/live` | Current Cloudflare API and D1 migrations |
| `packages/data` | Attributed station, city and monthly-average snapshots |
| `packages/brands` | Curated remote station-logo URLs and matching rules, without image binaries |
| `packages/map-style` | OpenFuel's base map style, a fork of OpenFreeMap Liberty |
| `packages/contracts` | Current and historical API contracts |
| `packages/design` | Approved OpenFuel identity and design resources |
| `packages/mobile` | Historical shared test fixtures |
| `services/edge`, `services/api`, `services/registry`, `supabase` | Retained reference implementations and tests |

## Check and deploy

```sh
npm run test:live
npm run test:edge
npm run build
# After selecting your own Cloudflare account, D1 database and domain:
npm run db:remote
npm run db:seed:remote
npm run deploy
```

Wrangler is pinned by `package-lock.json`. Forks must replace the public account,
database and domain configuration in `wrangler.jsonc` with their own resources.
Apply migrations and seed before deploying; [running costs](docs/RUNNING_COSTS.md)
gives the order, the daily D1 budget and the optional donate links.
Keep OAuth tokens, `.env`, `.dev.vars`, local databases and signing material out of Git.
Source packaging uses an explicit allowlist and excludes private state and build products.

See [live API](docs/LIVE_API.md), [hosting](docs/HOSTING.md), [privacy](docs/PRIVACY.md),
[security and publication audit](docs/PUBLICATION_AUDIT.md), and [contributing](CONTRIBUTING.md).
Original code is AGPL-3.0-only. Imported datasets and dependencies retain their
separate licences; see [licence scope](LICENSE.md), [NOTICE](NOTICE) and [data notices](packages/data/README.md).
