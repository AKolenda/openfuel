# Native implementation and visual acceptance gate

The target is the **approved HTML design**, not an arbitrary native starter. This iteration ports
the map-first screen into SwiftUI and updates Compose to the same sample geography, station identities,
colours and layout tokens. There is no React Native or WebView. The native applications have NOT been
fully built, type-checked against their platform SDKs, or visually reviewed on devices here. No exact
pixel-parity claim is made from source inspection or core tests.

## A single fixture and design source

`packages/mobile/stations.json` contains the same six IDs, brands, samples, positions and report ages
as the browser reference. `packages/mobile/basemap.svg` is the original map illustration only; the
PNG derivative is copied to each platform as a local map background. Station markers, text, buttons,
search fields and sheets are native interactive controls, not an HTML screenshot or web wrapper.
`packages/design/tokens.json` records approved colours and dimensions.

```sh
python tools/generate_mobile.py
python tools/generate_mobile.py --check
```

The generator produces Swift and Kotlin sample/token source plus matching map resources. CI checks
for drift. Do not independently edit generated platform fixtures or ship sample SVG coordinates as
latitude/longitude. The old three-station API contract fixture is separate and tests the retained
reference adapter; it is not the map UI dataset.

## Actual improvements in this source iteration

- iOS replaces the old system List landing screen with `MapHomeView`, `SampleMapView`, `StationRowView`,
  `FuelMenuView` and a separate state model. It includes the map-first list, Cards, beside-price/full
  actions, native sheets, local draft storage and Apple/Google handoff preference.
- Android retains Kotlin/Compose but uses the shared map artwork/camera and updated row geometry,
  dual List/Cards controls, stable screen/element tags and atomic local-draft persistence.
- Both use the same price units, missing-grade handling, sorted sample data, freshness threshold and
  48 logical-pixel Go target. iOS uses SF Symbols; Android uses Material icons, not generated logos.
- All menus are native bottom sheets. iOS settings keep Apply outside the scrolling fields.
- Native screenshot harnesses capture the actual app, not Chromium images. Screenshot/CI code is
  provided, but not executed here. Reference snapshots in `goldens/html` are BROWSER-only.
- Compile-time API configuration accepts a public HTTPS URL; both map screens remain **demo mode**.
  The new read Worker/DB adapter is not wired into these sample maps. That integration must replace
  illustration coordinates with a real map/camera/dataset, not overlay live prices on invented roads.

## Review matrix

Capture list, Cards, full-width Go, sorting, filters/settings, About, station detail and report form.
Add missing-price, empty-search, stale-price, long-brand, saved-only, keyboard, French and large-text
states before release. Use a small phone and a large phone on both platforms. Check that:

* the map uses available space and no UI is obscured by safe areas/system bars;
* the row remains compact and the price/freshness never collide with the 48-point/dp Go target;
* tapping Go does not activate the station's details; VoiceOver/TalkBack announces the destination;
* menus anchor to the bottom, long content scrolls, Apply/Close remain reachable, swipe/Escape works;
* price conditions, age and missing diesel semantics match the reference; sample searches are explicit;
* text scaling and French don't truncate actions or hide essential price/condition information.

These are **acceptance criteria**, not completed device-test results. Shared tokens reduce divergence,
but SwiftUI, Compose and browser fonts/safe-area behavior are not identical rendering engines.
Aim for equivalent geometry/hierarchy/interactions, then approve real per-platform screenshots.

## Capture and compare

```sh
# Browser reference images only, with Chromium and Playwright:
python tools/capture_reference.py
# Core checks, runnable without mobile SDKs:
python tools/project.py android-core
python tools/project.py ios-core
```

For real native capture, run `.github/workflows/native-review.yml` after publishing this repo. It
uses an Android emulator and a Mac iPhone simulator. This workflow is manual and has not run here.
Android screenshots are uploaded from instrumented tests. iOS screenshots are attachments in the
xcresult bundle; export them in Xcode, retaining their screen labels and device/OS metadata.

Initial review is human against the approved HTML and this checklist. Afterwards, commit approved
same-platform goldens with approval metadata, then run:

```sh
python tools/visual_gate.py --platform android --current evidence/native/android-normalized
python tools/visual_gate.py --platform ios --current evidence/native/ios-normalized
```

The tool fails on a missing baseline, missing capture, incompatible dimensions or material image
change. It writes diff images for review. It cannot certify functional correctness or automatically
claim a browser screenshot is a real native screen. No native approval.json is fabricated in this repo.

## Toolchain and scope

Android: AGP 8.9.2, Gradle 8.11.1, Kotlin 2.1.20, SDK 35, JDK 17+. iOS: iOS17+, SwiftUI, XcodeGen and
Xcode on macOS. Those pins are compatible-source targets, not a successful build assertion.
Standalone Kotlin and Linux Swift core tests do not compile Compose or SwiftUI views. Native Maps
handoff, platform permissions, app icons, accessibility, device performance, release signing,
store compliance and full source/artwork rights review remain release work.

References:
https://developer.android.com/training/testing/ui-tests/screenshot
https://developer.android.com/develop/ui/compose/components/bottom-sheets
https://developer.apple.com/documentation/swiftui/view/presentationdetents(_:)
