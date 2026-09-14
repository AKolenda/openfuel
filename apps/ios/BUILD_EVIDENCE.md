# iOS verification · 2026-09-13

The active iOS source is migrated to the current live API, MapKit, foreground
location, real-area cache startup and direct remote brand logos. Portable Swift
logic has been executed on Linux with Swift 6.0.3. Native Apple frameworks cannot
be built here: **there is no Apple SDK compile, simulator result or signed IPA**.

| Executed check | Result |
| --- | --- |
| `swift test --package-path apps/ios/OpenFuelCore` | 30 passed, 0 failed; one opt-in live test skipped in the ordinary run |
| `LiveReadOnlyTests` against `https://openfuel.ca/api/v1` | Passed: 179 real Edmonton-area stations and two Montréal city-search matches; zero reports submitted |
| `swiftc -frontend -parse` for all active UI and UI-test sources | Passed syntax parsing; does not resolve SwiftUI, MapKit, UIKit or CoreLocation APIs |
| Foundation model semantic check with small UI/location stubs | Passed; validates model/core usage, not Apple framework integration |
| English/French literal localization references and property-list XML | Passed |
| Approved F header and 1024px app icon | Canonical PNG copies and asset catalog JSON verified; Apple rendering not executed |
| Apple SDK build, iOS simulator, physical iPhone, IPA | Not executed; requires macOS/Xcode |

The core checks exercise live-mode rejection of synthetic data, real-coordinate
validation, nullable prices remaining visible, rounded coordinate queries, city
search escaping/filtering, exact report receipt/request matching, HTTP failure
handling, saved-area/API-origin cache boundaries, report aging and remote-logo
host restrictions. The new persistence regression verifies that encoded snapshots
drop the exact device fix and its original distance values, and that an older
precise cache is rewritten to the coarse format when read. Existing historical
fixture/reference tests remain separate.

Raw outputs: [core-test-evidence.txt](core-test-evidence.txt) and
[live-test-evidence.txt](live-test-evidence.txt). The read-only live test ran on
2026-09-12 at 06:32 UTC; it makes no claim about the tester's physical location
or correctness of a reported pump price. Android/Expo screenshots are not iOS
evidence.

The current UI-test source checks hide/restore controls and city fallback in an
empty real-data state. It has not been run without Xcode. The first Mac build must
verify MapKit annotations, foreground permission flow, logo rendering/cache,
status bar/safe areas, keyboard behavior, large text and French layout before
calling the native iPhone client tested. No pixel-parity approval is claimed.
