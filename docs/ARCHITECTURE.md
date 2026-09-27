# Current architecture

Website + native Android + Expo + SwiftUI source → Cloudflare Worker → static station files and Cloudflare D1.

The Worker serves `/api/v1/health`, `/api/v1/regions`, `/api/v1/stations`, `/api/v1/geocode` and
`/api/v1/reports`. Cloudflare Static Assets serves the website, docs, station files, source download
and Android APK. API routes run the Worker first; static requests avoid application/database work.
No external paid API is required.

Station locations come from the bundled OpenStreetMap snapshot in `packages/data`. The site build
writes it as one static file per 0.5° area, which the Worker reads through its assets binding. D1
holds current per-grade prices, read as one `area_prices` row per area, accepted community reports,
a daily usage count, and a copy of the stations that only validates reports. SQLite triggers publish
each accepted report to the current prices and its area's row in the same transaction. Integer
prices avoid rounding errors. Prepared SQL, bounded request bodies, input validation, per-install
SQL limits, a best-effort edge IP limiter and a daily D1 budget constrain public writes and cost.
City search, regions and monthly averages come from bundled files and read no database.

Clients cache station snapshots, favorites and settings locally, label saved data with its age,
and show report errors without claiming a saved change. The website and Android app draw
OpenFreeMap vector tiles with MapLibre inside a Leaflet map (on Android in a WebView; the rest of
its interface is Compose), with OpenStreetMap raster tiles as the fallback. Expo uses Leaflet with
OpenStreetMap raster tiles in a WebView, and SwiftUI uses MapKit.

Community prices are unverified observations. Authenticated intake, moderation and licensed price
imports remain future work. Supabase/PostGIS and the FastAPI registry are historical foundations
and are not deployed or called by this Worker.

See LIVE_API.md, RUNNING_COSTS.md, HOSTING.md, PRIVACY.md and BUILD_STATUS.md for the active
implementation.
