# Current architecture

Website + native Android + native SwiftUI → Cloudflare Worker → Cloudflare D1.

The Worker serves `/api/v1/health`, `/api/v1/regions`, `/api/v1/stations` and `/api/v1/reports`.
Cloudflare Static Assets serves the website, docs, source download and Android APK. API routes run
the Worker first; static requests avoid application/database work. No external paid API is required.

D1 owns six synthetic station records, current per-grade prices, and accepted prototype reports.
A SQLite trigger publishes each accepted report to the current-price table in the same transaction.
Integer prices avoid rounding errors. Prepared SQL, bounded request bodies, input validation,
per-install SQL limits and a best-effort edge IP limiter constrain public sample writes.

Clients cache station snapshots, favorites and settings locally. They distinguish cached/sample
fallback from a connected shared database, and show report errors without claiming a saved change.
Native UI uses SwiftUI/Compose controls over shared sample map art; neither native app is a WebView.

This playground cannot establish verified real-world prices. Authenticated intake, moderation,
licensed station/price imports and real geographic maps remain future work. Supabase/PostGIS and
the FastAPI registry are historical foundations and are not deployed or called by this Worker.

See PROTOTYPE_API.md, DATABASE.md, HOSTING.md and BUILD_STATUS.md for the active implementation.
