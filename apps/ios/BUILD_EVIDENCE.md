# iOS verification · 2026-09-11

The Swift core and its real Cloudflare client were executed successfully on Linux using the official
Swift 6.0.3 Ubuntu 24.04 toolchain. The native SwiftUI app has not been built with an Apple SDK in this
workspace, because the host has no macOS/Xcode. No IPA or native iOS screenshot is claimed.

| Check | Result |
| --- | --- |
| `swift test --package-path apps/ios/OpenFuelCore` | 21 passed, 0 failed; 1 opt-in deployed test skipped in the ordinary run |
| Deployed `PrototypeLiveTests` | 1 passed, 0 failed against the public Cloudflare Worker |
| `swiftc -frontend -parse apps/ios/OpenFuel/*.swift` | Passed for every native SwiftUI source file |
| Model Foundation logic type check on Linux | Passed using small UI property-wrapper/protocol stubs; this does **not** validate SwiftUI against an Apple SDK |
| `python3 tools/generate_mobile.py --check` | Passed: six matching shared mobile resources |
| `python3 tools/project.py fixtures --check` | Passed: canonical Swift API fixture matches |
| English/French static localization references and XML property lists | Passed |
| Apple SDK build, simulator run, native screenshots, signed IPA | Not executed; requires macOS/Xcode |

The deployed integration check ran at 2026-09-11 23:24 America/Edmonton (2026-09-12 05:24 UTC) against
`https://openfuel-prototype.openfuel-monorepo.workers.dev/api/v1`. It fetched all six stations,
submitted the current regular test price for `parkside`, retried the same request UUID and confirmed
that the server returned the same report ID, then fetched again and verified the persisted price
and zero-minute report age. This used the same Swift `PrototypeAPIClient` used by the native app,
including HTTPS URLSession transport and Codable response decoding.

Raw output is saved in [core-test-evidence.txt](core-test-evidence.txt) and
[live-test-evidence.txt](live-test-evidence.txt). The earlier certificate provisioning failure was
resolved before the successful deployed integration check.

To repeat the deployed check (it deliberately writes one prototype report):

```sh
OPENFUEL_LIVE_TEST_API_BASE_URL=https://openfuel-prototype.openfuel-monorepo.workers.dev/api/v1 \
  swift test --package-path apps/ios/OpenFuelCore --filter PrototypeLiveTests
```

Build and run the app on macOS using the steps in [README.md](README.md). Existing GitHub macOS
workflows can perform the simulator build and screenshot checks once the repository is hosted.
