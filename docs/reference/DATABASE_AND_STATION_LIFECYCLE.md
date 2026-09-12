# OpenFuel Canada — database and station lifecycle

Design proposal, 6 September 2026. This is a technical direction, not a deployed service or legal opinion. The SQL in `registry/schema-draft.sql` has not been executed here. The small Python policy model is executable and tested, but is not an authenticated API.

## Recommendation

Use **PostgreSQL with PostGIS as the durable data model**, and choose **Supabase for the first managed deployment** if its operational model and Canadian-region arrangements fit the project. Keep schema migrations, import workers, the publication API and exports independent of Supabase-specific business logic. Plain PostgreSQL/PostGIS plus a small service remains the escape route.

Supabase explicitly supports PostGIS proximity and geographic queries, offers self-hosting, and lists a Canada Central project region. Sources: https://supabase.com/docs/guides/database/extensions/postgis ; https://supabase.com/docs/guides/self-hosting ; https://supabase.com/docs/guides/platform/regions .

A Canadian primary region is not proof that every log, analytics event, edge invocation, backup copy or support workflow stays in Canada. Document the complete data path before making a residency claim. Supabase distinguishes regional database/Auth/Storage from global components in https://supabase.com/regions .

**Appwrite is a legitimate alternative, not disqualified for lacking geo support.** Its current documentation supports point/line/polygon columns and distance/relationship queries. Its self-hosted geo-query documentation specifically says to choose MariaDB and excludes MongoDB spatial support. Its general self-hosted database page also lists PostgreSQL: test the exact release, backend and feature combination rather than assuming all adapters offer equivalent spatial functionality. Sources: https://appwrite.io/docs/products/databases/tablesdb/geo-queries ; https://appwrite.io/docs/advanced/self-hosting/configuration/databases ; https://appwrite.io/docs/advanced/self-hosting/installation .

The reason to prefer Supabase/PostGIS here is direct SQL access to a geospatial, relational station registry: geometry deduplication, source reconciliation, status history, immutable event appends, data exports and transactional publication fit that model. This is an architectural judgment, not a measured claim that Supabase is faster or universally better. Appwrite is attractive if its integrated APIs and SDKs are a strong team preference; budget time for the same data-governance and export work either way.

The minimal alternative is PostgreSQL/PostGIS + the existing FastAPI service + an object store for snapshots/evidence. It trades fewer platform-specific dependencies for more authentication, operations, monitoring and backup work. PostGIS stores, indexes and queries spatial data: https://postgis.net/ .

## “Open database” has four different meanings

1. Open-source engine: PostgreSQL/PostGIS or a self-hostable platform.
2. Open application: server and clients can be audited, modified and independently run.
3. Open dataset: the public station/price facts have an explicit reusable data licence and usable exports.
4. Accountable publication: sources, corrections and retained checkpoints let another party examine what changed.

None of these means an open database password or public write access. Read access and export are public; publication is mediated. Never ship a service-role key in a mobile app. Supabase documents that privileged service keys bypass Row Level Security: https://supabase.com/docs/guides/database/postgres/row-level-security .

Open-source software does not automatically license the data stored in it. Nor can a genuinely open-source licence forbid a particular industry from using public information; the OSI definition prohibits field-of-endeavour restrictions: https://opensource.org/osd . Protect the project by not building a driving-surveillance dataset, keeping raw evidence and identities out of public exports, and establishing appropriate governance. Insurers could still read the same public station prices as everyone else.

## Suggested architecture

Native SwiftUI / Kotlin Compose clients
  -> local cache of public regional snapshots
  -> read-only price/station API and object-store exports
  -> publication service (policy + authorisation + transaction)
  -> PostgreSQL/PostGIS public facts + segregated private review data

Permitted source imports and community proposals
  -> validation and duplicate suggestions
  -> private review queue
  -> moderator decision
  -> atomic station projection + append-only public event
  -> refreshed cache / regional delta / retained checkpoint

Use a private schema for evidence and reviewer/attester information, not merely obscure table names. The draft enables RLS and defaults to no client access. The deployment still needs explicit public read policies, server identity boundaries, API-schema exposure, private storage ACLs and integration tests.

Prefer coarse region/tile requests and on-device distance calculations to sending exact GPS coordinates to a station API. Tile requests, IP logs, navigation handoffs and contribution timing can still reveal location information. Do not call this anonymous without a threat model. Do not enable third-party behavioural analytics, persistent advertising identifiers, trip history, speed/braking analysis or background geofences.

## Initial station inventory

Seed one launch province or metropolitan area from a legally reusable station dataset. OpenStreetMap has `amenity=fuel` plus tags for names, brand, operator, hours, fuels and payments. It is a station-inventory starting point, not a guaranteed current pump-price feed. Source: https://wiki.openstreetmap.org/wiki/Tag:amenity=fuel .

Geofabrik provides Canada/province extracts and incremental updates. Start with one region instead of a full-country download, use checksums and checkpointed import jobs, and follow its update cadence. Sources: https://download.geofabrik.de/north-america/canada.html ; https://www.geofabrik.de/data/download.html .

Keep our station UUID independent of a source's object ID. Store provider, object type (node/way/relation), external ID, source version, licence and last-seen date separately. One station may have multiple source references. Do not carry OSM contributor identities into our public ledger. A seed record should say “Imported from OSM, source date …”, not “physically verified today”.

Other inputs can include operator-maintained station information and jurisdictional open-data directories, where the intended reuse is permitted. A business licence is evidence, not proof that pumps are operating. A publicly viewable commercial locator is not automatically an open data licence. Do not copy from Google Maps or competing fuel apps to populate the reusable registry without rights for that use.

### OSM licensing and map infrastructure

A conservative direction is to publish an OSM-derived canonical station registry under ODbL, retain attribution/source metadata, and maintain independently collected price observations as a separately licensed dataset. Whether a combination is derivative or collective depends on the actual use—not merely separate SQL tables. Obtain a licence review before asserting the price layer is exempt from ODbL obligations. Sources: https://osmfoundation.org/wiki/Licence/Community_Guidelines/Collective_Database_Guideline_Guideline ; https://osmfoundation.org/wiki/Licence/Attribution_Guidelines .

The original synthetic fixtures in this bundle are CC0. This does not relicense any future import or third-party logo.

MapLibre Native is an appropriate renderer to evaluate for both platforms, but it is not itself a complete map-data service. Source: https://maplibre.org/projects/native/ . Plan a separately licensed tile supply, attribution, caching and offline policy. OpenStreetMap's public standard and vector tile services prohibit bulk/offline tile downloads; use an explicitly permitted provider or your own tile pipeline. Sources: https://operations.osmfoundation.org/policies/tiles/ ; https://operations.osmfoundation.org/policies/vector/ . No live tiles are integrated in these prototypes.

## When somebody finds a new station

Use “Suggest a station”, not a direct “Publish” button.

The submission contains the station's pin, name/brand, address, apparent operating state, fuel types and optional evidence. It need not contain the contributor's home, exact current device location or driving route. Make browsing account-free. Contributions may need optional authentication or another abuse-control mechanism; that is an explicit privacy/quality tradeoff, not a reason to track every reader.

On submission, validate coordinates and required fields; normalize names; suggest possible duplicates using distance, address, brand and carriageway. An 80 m candidate search is an initial configurable heuristic, not an automatic merge rule. Two stations across a road, a truck cardlock and a public forecourt can be distinct. A changed brand or an OSM node-to-way replacement can be the same physical station.

The proposal remains pending. Show a pending marker only in a clearly differentiated contributor/review mode; never rank an unverified submission as the cheapest confirmed station. Several corroborating submissions can raise review priority. The model uses three distinct, per-proposal attester tokens for triage; that is not proof of three independent humans and never authorizes publication.

For v1, a permitted-source import can establish the baseline, but community additions and edits need moderator evidence review. A proposal may be accepted with strong verified operator/public-source evidence even before three public reports. Do not disadvantage rural locations by requiring a large crowd in all cases.

Once accepted, update the canonical station and append a public event in one transaction. Use optimistic version checks so two reviewers cannot silently overwrite each other. Invalidate only the affected cached regions. Preserve reason categories and source evidence summaries that are safe to publish; keep raw submissions private.

## Closures, reopenings and rebrands

“Closed now” (opening hours), “temporarily closed”, “permanently closed”, “construction”, and “unknown/unverified” are different facts. Do not infer permanent closure from stale prices or the absence of reports.

A community closure report opens a review, not a deletion. For a pilot, require verified evidence and two distinct authorised moderator approvals for permanent closure. This is a conservative initial rule to tune against measured false-closure rates, not an industry standard. Conflicting credible evidence should trigger a disputed/recheck workflow rather than a race to accumulate votes.

Keep a permanently closed station's UUID and public history; remove it from ordinary active-station searches. Reopening creates another event against the same physical-site identity when appropriate. A rebrand changes brand/name with history; it does not create a fresh station that loses all evidence. Merges retain aliases/tombstones and redirect duplicate IDs.

OSM lifecycle prefixes can indicate disused, abandoned, construction or demolished features. Source: https://wiki.openstreetmap.org/wiki/Lifecycle_prefix . Preserve previous source associations so an import notices when `amenity=fuel` disappears. A deletion or tagging change is a reconciliation signal, not automatic proof that the business closed. Upstream outages, duplicate cleanup, source-ID changes and stale source information must not silently wipe our map.

## Keeping the inventory current

Use restartable, idempotent import jobs. Store the last successfully applied source version. Reject out-of-order batches, stage large changes, and require review when a batch suddenly removes a large share of a region. Match our overrides and newer evidence before applying an older upstream state. Do not automatically overwrite OpenFuel's verified closure with a stale source record that says “open”.

Track source last-seen, last-verified, open disputes and last price observation separately. Schedule rechecks based on age, activity and risk rather than declaring a quiet location closed. If a source is unavailable, retain the last usable dataset and show its age. Human contributions can complement source updates; neither is guaranteed complete.

Measure time-to-verification, duplicate rate, false-closure reversals, source lag, correction turnaround, evidence-retention compliance and price freshness by region. Publish coverage gaps rather than a nationwide guarantee before there is coverage.

## Privacy, abuse resistance and auditability

Do not expose permanent contributor IDs or public “my fuel reports” histories. In the review model, account identity is converted to a proposal-scoped HMAC; different proposals use unlinkable stored tokens unless an authorised service has the original identity and secret. This is minimisation, not anonymity. Production must authenticate the account, authorise reviewers, prevent self-review and reason about coordinated or newly created accounts.

Rate-limit writes, bound object sizes, constrain retries and use idempotency keys. A per-install identifier is not a person; multiple reports or IP addresses do not establish independent evidence. Avoid collecting driving telemetry to solve moderation. Start with a smaller moderated pilot rather than using invasive fraud controls by default.

Photo evidence, if introduced, needs explicit rights/consent, malware scanning, EXIF removal, redaction of faces/plates and storage with short retention. Do not place raw evidence or its sensitive identifiers in an immutable public ledger. The schema's 30-day evidence expiry is a proposed policy; a deletion worker and retention audit are not implemented.

Public event payloads should contain typed, reviewed business facts and enumerated decision reasons. Even a business-address field can contain someone’s personal information if a malicious submission is accepted: screening must happen before publication. For mistaken disclosure, stop further redistribution, redact affected exports and publish a non-sensitive removal notice; do not claim an immutable hash excuses privacy obligations.

A hash chain makes changes evident against an independently retained head. It does not prove a station exists, or stop an administrator rebuilding a chain. Add signed checkpoints, independent witnesses/mirrors and a verifier that rejects rollback relative to a checkpoint it already trusts. Keep operational signing keys separate from application database credentials. The earlier backend-reference contains the previous price-ledger implementation; neither these new UIs nor this policy model is connected to it yet.

## What is implemented versus proposed

Implemented here: native UI source with local suggestion drafts; a Python state/decision model; strict per-proposal token deduplication; review requirements; version conflict checks; public-event shaping; a hash verifier and retained-checkpoint test; a PostgreSQL/PostGIS schema draft.

Not implemented: production authentication, secure moderator service, source imports, OSM synchronization, live geocoding/tiles, a PostgreSQL deployment, scheduled evidence deletion, notifications, witness hosting, production signing or a privacy audit. The policy model must not be used as a public API: its reviewer arguments are deliberately supplied by tests, not authenticated users.
