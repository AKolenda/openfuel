# Running costs, limits and donations

OpenFuel runs on one Cloudflare Worker with a D1 database. Static files (the website,
map code, the Android APK download) are served free and never touch the database.
Map tiles come from OpenFreeMap's free public service.

## How database use is kept low

- **Cached areas.** `/api/v1/stations` works in 0.5° areas. Each area's stations are cached
  in the data centre for up to a week (the cache key changes with the station snapshot,
  brand catalog and corrections). Prices are cached per area *version*: every price report
  bumps its area's version in D1, so cached prices stay valid until a price there changes.
- **Fresh prices.** Each Worker instance re-reads the versions (about one D1 row per area)
  at most every 15 seconds, and browsers may reuse a stations answer for 15 seconds. A new
  report therefore reaches everyone within about 15–30 seconds, and the reporter sees it at once.
- **No database for static facts.** Regions, city search and the Statistics Canada averages come
  from the bundled snapshot files; health only asks D1 to answer (`SELECT 1`, no rows read).
- **Hand edits and restores stay correct.** Any change to `current_prices`, including a manual
  UPDATE or DELETE, gives its area a new random version, so caches never serve a removed price.
- **Cheap writes.** The report capacity check reads one row, and re-seeding skips rows that did
  not change.

Measured locally for an Edmonton 10 km search: 452 rows read on a cold cache (775–1,562
before), 0 rows for repeats until the next version check, and about 10 ms per warm request.
Searches cover at most 20 areas, which only trims 50 km searches in the far north.

## The daily cap

The Worker keeps its own daily D1 budget, below the Workers Free limits (5M rows read and
100k rows written per UTC day):

| Setting (Worker variable) | Default | Meaning |
| --- | --- | --- |
| `D1_DAILY_READ_BUDGET` | 4,000,000 | Rows read per UTC day before the Worker stops using D1 |
| `D1_DAILY_WRITE_BUDGET` | 80,000 | Rows written per UTC day before reports pause |
| `OPENFUEL_DONATE_URL` | none | Donate link included in the limit answer |

When the read budget, or Cloudflare's own D1 read limit, is reached, the API answers
`503 {"error":"spending_cap", "scope":"all", "resets_at": ...}` until midnight UTC. Areas already
cached keep working with their last known prices, each shown with its age. A spent write budget
(or D1's write limit) answers `"scope":"reports"` for new reports only; browsing continues. The website shows saved prices under a notice with the donate link,
and Expo explains it in its error message. If Cloudflare's daily *request* limit is reached,
Cloudflare itself answers with a non-JSON 429 page, which the apps treat the same way.

The count is kept per Worker instance and added to the `usage_budget` table every
two minutes or 25,000 rows, so it is approximate; that is why the defaults leave 20% headroom.
Rows read by `wrangler` or the dashboard count against Cloudflare's limit but not this budget.

## Cloudflare account settings (dashboard)

Cloudflare has no dollar cap for Workers or D1. The only arrangement where Cloudflare
guarantees $0 is the **Workers Free plan**, whose daily limits are hard stops.

1. **Check the plan:** Workers & Pages → Plans. If it says Free, the account cannot be billed
   for Workers or D1; the limits above simply apply. Keep it on Free unless you decide to pay.
2. **If the account is on Workers Paid:** Manage Account → Billing → Billable Usage →
   *Create budget alert* with a small amount (for example $5). It only emails you, about a day
   late, and does not stop anything; the Worker's own budget above is the actual brake.
3. **Block floods before they reach the Worker:** openfuel.ca → Security → WAF → Rate limiting
   rules → create one rule: *URI Path starts with `/api/`*, counting by IP, more than 60 requests
   per 10 seconds → Block. Blocked requests do not count as Worker requests.

The wrangler login used for deploys cannot read billing or change WAF rules, so these steps
are done in the dashboard.

## Donate links

All are optional; without them no donate UI appears.

| Where | Setting |
| --- | --- |
| Website | `OPENFUEL_PUBLIC_DONATE_URL` in `.env` (HTTPS) before `npm run build` / `npm run deploy` |
| Worker limit answer | `OPENFUEL_DONATE_URL` Worker variable (`vars` in `wrangler.jsonc`) |
| Android | `OPENFUEL_DONATE_URL` Gradle property or environment variable |
| Expo | `EXPO_PUBLIC_DONATE_URL` |

## Deploying these changes

Use the wrangler login that owns openfuel.ca. On a machine with several logins, bind that
profile to the repository once (`npx wrangler auth activate <profile>`) or add
`--profile <profile>` to each command. Apply the D1 migration before deploying the Worker
that uses it:

```sh
npm run db:remote   # applies services/live/migrations/0002_area_cache_and_budget.sql
npm run deploy      # builds the site and deploys the Worker
```
