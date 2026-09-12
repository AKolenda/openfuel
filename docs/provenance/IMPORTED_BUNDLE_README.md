# OpenFuel Canada — prototype bundle 10

**Interactive web prototypes + native Android source + station-governance design.** Not a live service. There is no installable APK in this bundle; see `android/README.md` for the build limitation and configured build workflow.

## Start here

Open `web/landing-a.html` or `web/landing-b.html` directly in a modern browser. Each is standalone, has English/French website copy, and opens an embedded map demo. No package installation or server is needed. The embedded map uses initials, not remote logos.

Open `web/app-preview.html` for the standalone Canadian-brand map study. Every custom menu becomes a bottom sheet on mobile: information, filters/preferences, sort, map-app chooser, report and audit history. Source references to remote brand artwork remain from the previous version; hosts may receive image requests. Logos fall back to initials when unavailable. All station geography and prices are synthetic.

The Android source is under `android/`. It uses Kotlin/Compose and a local map illustration, not WebView or React Native. The included manual GitHub Actions workflow is configured to generate a debug APK in a prepared runner; it has not run here. SwiftUI starter code is retained in `ios-starter/`; it has not received this new visual pass or an Xcode build.

The previous FastAPI/SQLite price-ledger backend is retained unchanged in `backend-reference/`. It is a reference, not a live connection for the new web/Android samples. The newer PostgreSQL draft and station-review policy are under `registry/`.

## Database direction

See `docs/DATABASE_AND_STATION_LIFECYCLE.md`. Recommendation: portable PostgreSQL/PostGIS schema, initially managed through Supabase if appropriate. Appwrite now has real geo features and is also considered. Open-source software, hosting, openly licensed data and an auditable publication process are distinct commitments.

## Checks

`docs/BUILD_STATUS.md` distinguishes browser/Kotlin/policy checks from unexecuted Android/iOS/PostgreSQL builds. None of the checks establishes live price accuracy, a production privacy guarantee or a right to reuse third-party branding.

No account, deployment, public GitHub repository, database, store listing or production signing identity was created. See LICENSE.md for code/data terms and third-party exclusions.
