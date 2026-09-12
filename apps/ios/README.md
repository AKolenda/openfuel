# iOS · native SwiftUI prototype

OpenFuel retains the map-first native interface, rounded station cards, forest-green controls,
grade filters, search, saved stations and map-app handoff. The app connects to the same Cloudflare
Worker and D1 database as the website and Android app. This is a **shared prototype with fictional
stations, map, distances and initial prices**, not a live fuel feed.

At launch, the app loads the last saved station snapshot and refreshes `GET /api/v1/stations`.
Pull down the station list or tap refresh to sync. It refreshes once a minute while foregrounded
and on returning to the app. An offline launch uses saved prices, or bundled samples on a first
launch. Saved report ages advance while offline. The connection banner states which data is shown.

Open a station, choose **Report price**, enter CAD cents per litre (for example `142.9`), and submit.
The app calls `POST /api/v1/reports`. A matching server receipt is required before a success message
or price change. A failed request keeps the form open; it is not silently queued. Each installation
persists a random client UUID for the server's report limits. Favorites, preferences and station
suggestion drafts stay on the device. Map handoff searches a sample brand in Canada because the
illustrated station addresses are invented.

Retrying an unchanged report reuses its request UUID so a lost server response does not create
another report. Changing station, grade or amount starts a new request. Pending reports are held
only for the current app session; there is no background upload queue.

## Build on macOS

Requires macOS, Xcode with the iOS 17+ SDK, Xcode command-line tools, and XcodeGen. There is no shared
WebView: the app uses SwiftUI, Foundation networking, a bundled map illustration and native controls.

From the repository root:

```sh
brew install xcodegen
python3 tools/generate_mobile.py --check
swift test --package-path apps/ios/OpenFuelCore
python3 tools/project.py ios-build
open apps/ios/OpenFuel.xcodeproj
```

The committed public endpoint, `https://openfuel-prototype.openfuel-monorepo.workers.dev/api/v1`,
in `Config/Base.xcconfig` is used by default. To use your own deployment,
copy `apps/ios/Config/Local.example.xcconfig` to `apps/ios/Config/Local.xcconfig` and set its HTTPS URL,
including `/api/v1`. Xcode configuration uses `https:/$()/…` to avoid interpreting `//` as a comment.
No account token or private API key belongs in the app. The new prototype client requires HTTPS.

In Xcode, select the OpenFuel scheme and an iPhone simulator, then Run. For a physical iPhone, choose
your development team under Signing & Capabilities, select the connected device and Run. An IPA for
distribution requires an Apple signing identity and a macOS archive/export step. The existing
`.github/workflows/ios.yml` builds the simulator app on macOS when run in GitHub; the native review
workflow captures real simulator screenshots. These workflows are source configuration, not evidence
that a hosted workflow or Apple SDK build has run.

## Verification

`OpenFuelCore` is a standalone Swift package and can be tested on Linux. Tests cover exact monetary
units, filters, public URL rules, Cloudflare JSON adaptation (including null unavailable grades),
report receipt validation, HTTP failure handling, offline cache aging and persistent client identity.
`OpenFuelUITests` runs deterministic screenshot states using `--screenshots`; this debug-only mode
uses bundled data and never submits reports.

See `BUILD_EVIDENCE.md` for the executed checks and the remaining Apple platform build gate.
