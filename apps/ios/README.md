# OpenFuel for iOS

The active SwiftUI client now uses the current real-station API at
`https://openfuel.ca/api/v1`, native MapKit geography and foreground location.
The source is kept current alongside Android and Expo while a Mac is unavailable.
The header and app icon use the approved curved F from
[`packages/design/brand`](../../packages/design/brand/README.md).
**No Apple SDK build, simulator run or IPA is claimed.** See [verification](BUILD_EVIDENCE.md).

On startup the app restores its last successful real search area and cached
stations, labelled as saved, before requesting foreground location. If no saved
area exists, it asks the person to choose an area rather than displaying a guessed
position or sample stations. Permission denial leaves Canadian city search and
manual coordinates available. Panning the map exposes **Search this area** below
the recenter and information controls. The station sheet can be expanded, dragged
away entirely, and restored with **Show stations**. Location, fuel grades and
Saved share one compact control row. The visible status bar uses the light scheme.

Stations without reported prices remain on both map and list. Community prices
show age and unverified status. Direction links use actual station coordinates.
A price submission requires the person's confirmation that they observed today's
standard pump price and a matching server receipt. Uncertain retries reuse the
request ID in the current session; there is no background submission queue.

## Remote logos and local data

`brandLogoUrl` and `brandKey` are optional API fields. Logo requests accept only
HTTPS image URLs at `thumb.wikimedia.org`, `www.fuel.crs`, `www.shell.ca` and `www.tempo.crs`, with
no URL credentials, fragments or alternate ports. Redirects remain inside that
allowlist. The app downloads station logos directly from those hosts; no station logo image
is committed to the repository or stored/proxied by OpenFuel's server. Missing, unsupported or
failed images fall back to text initials.

List rows and map annotations share coalesced requests, an 8 MB/64-entry memory
cache, bounded URLSession caches, and decoded thumbnails limited to 128 pixels.
A response over 2 MB is rejected while streaming. Cached network data and decoded
images stay on the device. The saved starting area is rounded to two decimal places (roughly 1 km), with
a generic label and distances recomputed from that broad centre; the exact device
fix stays in memory. Saved stations, the report installation UUID and the latest
station snapshot also remain on the device. Earlier precise snapshots are migrated
to the coarse format on their first read. Cache reads are tied
to the API origin and area; the old synthetic cache file is never loaded by the
active app. Cached report ages continue advancing after a restart.

Nearby API queries round coordinates to three decimal places, roughly 100 m.
MapKit receives map requests; logo hosts receive their own image requests. There
is no background location tracking or analytics SDK. Public reports contain the
station ID, grade, price and random installation/request identifiers. Clearing
local app data does not delete reports already accepted by the service.

## Build when a Mac is available

Use Xcode with iOS 17 or later and XcodeGen:

```sh
brew install xcodegen
swift test --package-path apps/ios/OpenFuelCore
python3 tools/project.py ios-build
open apps/ios/OpenFuel.xcodeproj
```

Select the OpenFuel scheme and an iPhone simulator, then Run. For a physical
phone, set your development team under Signing & Capabilities. Distribution
requires an Apple signing identity and a macOS archive/export step.

The default public API is in `Config/Base.xcconfig`. To use your own HTTPS
service, copy `Config/Local.example.xcconfig` to ignored `Config/Local.xcconfig`.
Use `https:/$()/…` so XCConfig does not parse `//` as a comment. The active client
requires HTTPS in Debug and Release; no account token or private API key belongs
in the app. `Info.plist` and its debug counterpart request only when-in-use
location access and retain normal App Transport Security.

First Mac validation should cover location grant/denial/restricted states, warm
launch on a saved area, city/manual search, MapKit marker selection, sheet hiding
and restoration, logo failures, large text, French labels, offline age display,
and keyboard/report validation against an isolated test API. The UI tests cover
empty-state controls without injecting sample geography. `--ui-testing` disables
automatic location prompting in Debug builds only; it never supplies fake stations.

## Run portable checks

```sh
swift test --package-path apps/ios/OpenFuelCore
OPENFUEL_READ_ONLY_TEST_API_BASE_URL=https://openfuel.ca/api/v1 \
  swift test --package-path apps/ios/OpenFuelCore --filter LiveReadOnlyTests
```

The integration test performs GET requests only. All report tests use intercepted
local test responses; never send invented prices to real production stations.
Historical `PrototypeAPI` and generated sample fixtures remain reference tests,
but the active UI instantiates `LiveAPIClient` and never `SampleMapView`.


The current source keeps the map viewport fixed behind the results panel, moves
fuel selection and About/data-source details into Settings, and places Search
this area near the top after a roughly 750-metre pan. Results use a native List
with pull-to-refresh; the handle resizes the panel. These latest SwiftUI changes
still require Apple SDK compilation and device verification on a Mac.
