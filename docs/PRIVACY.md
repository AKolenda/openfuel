# OpenFuel data flows

The website, native Android app and Expo client use the public Cloudflare Worker
and D1 database to browse real Canadian station locations and submit unverified
community pump prices. The earlier SwiftUI client is retained source; its live
station and device-location flows have not been verified.

The web app requests a one-shot foreground location on entry, subject to browser
permission. Android and Expo request foreground location through the operating
system. Permission may be declined; city search or choosing a map area provides
an alternative. No background tracking or journey history is requested. Nearby
station requests send the searched coordinates to OpenFuel. The web query uses
six decimal places; Expo rounds to three decimal places, roughly 100 m. Rounding
reduces precision but does not make a location anonymous.

The browser keeps the returned device position in memory during the session and
stores up to four searched-area station snapshots with area keys rounded to three
decimals. Mobile clients persist selected areas and cached station details on the
device. Saved stations, preferences and random reporting identifiers also remain
in browser/app storage. Cached information can reveal areas that were searched
and can be outdated. Clearing browser/app data removes local state; the web map
also provides a local-data clearing action. Map tiles are not downloaded for
offline areas or stored by the web app's service worker.

Cloudflare receives network addresses and request metadata while serving the
website, API and downloads. Coordinates appear in nearby-search query strings.
The current Worker configuration disables invocation logs and tracing to avoid
automatic capture of those query strings. Application error logs record only a
generic event and pathname. This is not a guarantee about all hosting-provider
connection metadata or operator tooling. Do not enable request tracing or log
full URLs, report bodies, identifiers, IP addresses or coordinates without first
reviewing this privacy behavior.

OpenStreetMap receives requests for tiles viewed in the web map and Kotlin map,
including ordinary network metadata. The web browser sends the site origin as
referrer. Expo displays bundled Leaflet in a WebView and also requests OpenStreetMap tiles.
The tile provider can infer the viewed area from map requests. Directions
open an external map service with the selected station coordinates. City search
uses a bundled GeoNames index served by OpenFuel; user city queries are not
forwarded to GeoNames. The clients do not bundle advertising or analytics SDKs.
Cloudflare can inject its Web Analytics beacon into hosted pages; the station
map’s restrictive script policy blocks that injected beacon.

A report sends a station ID, fuel grade, integer price, random installation ID
and optionally a request ID for retries. Device GPS coordinates are not part of
the report body. The server assigns the receipt timestamp and stores accepted
reports in D1; other users see the resulting price and its age. Installation IDs
support rate limiting and connect reports from the same installation. They are
not returned in public station responses or receipts. Random identifiers are
not a guarantee of anonymity. Cloudflare's edge limiter also uses the requesting
IP address; the report table has no IP-address field.

Only report a price actually observed at the selected station, with no personal
information. Reports require a confirmed response; clients do not silently upload
offline submissions in the background. Stored public prices are unverified, even
when recently submitted. There is no account-based deletion interface, automated
report-retention job or deployed station-moderation service yet. Clearing local
storage does not delete reports already accepted by D1. The archive ceiling
stops further reports rather than deleting old ones. Private station evidence
should not be submitted or placed in public source or issues.
