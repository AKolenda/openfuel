# OpenFuel data flows

The website, native Android app, Expo client and current SwiftUI source use the
public Cloudflare Worker and D1 database for real Canadian stations and
unverified community pump prices. Swift’s portable core is tested independently;
its Apple SDK build, simulator behavior and IPA remain unverified until a Mac is
available. The iOS flows below describe the current source.

## Location and saved areas

The web app requests a one-shot foreground location on entry, subject to browser
permission. Android, Expo and Swift request foreground location through the
operating system. Permission may be declined; Canadian city search, manual
coordinates or choosing a map area provide alternatives. No background tracking
or journey history is requested.

Nearby-station queries send the selected coordinates to OpenFuel. The web query
uses six decimal places; Android, Expo and Swift round to three decimal places
(roughly 100 m in latitude). Rounding reduces precision but does not make a
location anonymous. Coordinates appear in nearby-search query strings.

A device fix stays in session memory. Each client persists its remembered area
rounded to **two decimal places** (roughly 1 km in latitude), with a generic label
for device/map positions or a city name for a city selection. Cached station
distances are discarded or recalculated from that coarse centre so they do not
preserve a more precise device fix. Previous finer caches are retired or migrated.
A remembered area is labelled as saved and does not stand in for fresh device
location. Cached stations can appear while refresh and permission requests run.

The web browser keeps up to four searched-area station snapshots. Native and Expo
clients retain a recent station snapshot and selected area on the device. Saved
station IDs, preferences and random report identifiers also remain in browser/app
storage. Cached information can reveal searched areas and may be outdated.
Clearing browser/app data removes local state; the web map also offers a local
clearing action. Clearing local state does not delete reports accepted by D1.

## Maps, logos and hosting

OpenStreetMap receives tile requests for the viewed area in the web app, Kotlin
MapLibre map and Expo Leaflet WebView. The tile provider receives ordinary
connection metadata and can infer the viewed area from tile URLs. Web map tile
requests send the site origin as referrer. Swift uses Apple MapKit, which receives
map requests through the platform SDK. Map tiles may use normal browser/platform
HTTP caches; OpenFuel does not bulk-download offline map areas, and the web
service worker does not cache map tiles or API/location queries.

Station logos load **directly from remote providers**: `thumb.wikimedia.org`,
`www.fuel.crs` and `www.shell.ca`. The public API returns optional image URLs from
an allowlisted catalog. No station logo binaries are bundled in the source or
apps, stored on OpenFuel’s server, or served through an OpenFuel image proxy.
Missing or failed logos show text initials. OpenFuel’s own approved F identity is
bundled as a separate first-party asset.

Logo providers receive connection metadata, including an IP address and the
requested image URL. Web and Expo map images use `no-referrer`. Browsers, native
image loaders and WebViews may cache these downloads on the device; Android and
Swift also coalesce shared list/map requests and keep bounded image caches. The
web service worker never caches remote logos. These disposable device caches do
not upload images to OpenFuel.

Directions open an external map service with the selected station coordinates.
The map service handles routing and any origin-location permission. City search
uses the bundled GeoNames index served by OpenFuel; queries are not forwarded to
GeoNames. The clients do not bundle advertising or analytics SDKs. Cloudflare can
inject a Web Analytics beacon into hosted pages; the station map’s restrictive
script policy blocks that injected beacon.

Cloudflare receives network addresses and request metadata while serving the
website, API and downloads. The current Worker configuration disables invocation
logs and tracing to avoid automatic capture of query strings. Application error
logs record only a generic event and pathname. This is not a guarantee about all
hosting-provider connection metadata or operator tooling. Do not enable request
tracing or log full URLs, report bodies, identifiers, IP addresses or coordinates
without first reviewing this behavior.

## Community reports

A report sends a station ID, fuel grade, integer price, random installation ID
and optionally a request ID for retries. Device GPS coordinates are not part of
the report body. The server assigns the receipt timestamp and stores accepted
reports in D1; other users see the resulting price and its age. Installation IDs
support rate limiting and connect reports from the same installation. They are
not returned in public station responses or receipts. Random identifiers are
not a guarantee of anonymity. Cloudflare’s edge limiter also uses the requesting
IP address; the report table has no IP-address field.

Only report a price actually observed at the selected station, with no personal
information. Reports require a confirmed response; clients do not silently upload
offline submissions in the background. Stored public prices are unverified, even
when recently submitted. There is no account-based deletion interface, automated
report-retention job or deployed station-moderation service yet. The archive
ceiling stops further reports rather than deleting old ones. Private station
evidence should not be submitted or placed in public source or issues.
