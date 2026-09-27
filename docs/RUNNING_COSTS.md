# Running costs, limits and donations

OpenFuel runs on one Cloudflare Worker with a D1 database. Static files (the website,
map code, the Android APK download) are served free and never touch the database.
Map tiles come from OpenFreeMap's free public service.

## How database use is kept low

Cloudflare bills D1 by rows read and written, not by queries, so the design keeps both the rows and
the round trips per request as low as possible.

- **Station locations never touch D1.** The site build writes the station snapshot as one static
  file per 0.5° area (`data/stations/<area>.json`, about 1,070 files), plus `index.json` listing the
  areas that have stations and `ids.json` with the station ids that reports are checked against
  before D1. The Worker reads the files it needs through its static-assets binding, keeps its 300
  most recently used areas, and skips areas without stations. D1's `stations` table, seeded from the
  same snapshot, only validates reports.
- **One D1 query per stations request.** D1 keeps one `area_prices` row per area holding all of its
  current prices and a version. A search sends the versions it already has for all its areas in one
  query and gets a price list back only for areas whose version changed: about one or two rows read
  per area, and only for the areas of the stations it returns.
- **Shared between instances.** Price lists are kept in each Worker instance and in the data centre's
  Cache API, stamped with when D1 was last asked. Within 15 seconds of a check, any instance in that
  data centre answers without D1.
- **Fresh prices.** Every change to `current_prices` (a report, a manual UPDATE or DELETE, a restore)
  sets or removes that one entry in its area's row and gives the area a new random version, so a
  replaced price is never served once an area is re-checked. The instance that took a report shows the
  new price at once; other instances within about 15 seconds, and browsers may reuse a stations answer
  for 15 seconds more (the web app skips its copy for 15 seconds after its own report). For 30 seconds
  after a confirmed report, the web and Expo apps show it over any stations answer with no price or an
  older one for that station and fuel, so a refetch in that window cannot bring the old price back.
- **One D1 call per report.** Checking the request ID, inserting, and reading the area's new prices
  run as one batch of 10–11 rows read, however many prices the area has, plus one row per report the
  same install made in the last hour (its hourly limit check). D1 keeps stations that later snapshots
  drop, which no search shows, so a report for a station not in the deployed snapshot (from an app's
  old saved list, say) gets 404 without the insert batch: at most a one-row lookup of its request ID,
  so a retry of a report accepted before the snapshot changed still gets its receipt. The Worker
  checks `data/stations/ids.json` (about 250 KB, read once per instance): reading and parsing it took
  about 1.5 ms and a lookup under 0.5 ms in Node 22.
- **No database for static facts.** Regions, city search and the Statistics Canada averages come
  from the bundled snapshot files; health only asks D1 to answer (`SELECT 1`, no rows read).
- **Cheap writes.** The report capacity check reads one row, and re-seeding skips rows that did
  not change.

Measured locally with the real Worker and local D1, for an Edmonton 10 km search:

| Request | D1 calls | Rows read |
| --- | --- | --- |
| New Worker instance (includes reading the day's budget total) | 1 | 5 |
| Repeat within 15 s, same instance or data centre | 0 | 0 |
| Re-check after 15 s, prices unchanged | 1 | 4 |
| Price report, first price for that station and grade | 1 | 10 (7 rows written) |
| Price report replacing a price | 1 | 11 (5 rows written) |

Before this design, the same cold search took 2 D1 calls and about 453 rows (448 of them station
locations), and a report took 3 calls. Searches cover at most 20 areas, which only trims 50 km
searches in the far north.

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
answers `"scope":"reports"` for new reports only; browsing continues. Cloudflare's own D1 write
limit stops all queries, so once it is reached only areas already cached keep answering. The
website shows saved prices under a notice with the donate link, and Expo explains it in its error
message. If Cloudflare's daily *request* limit is reached, Cloudflare itself answers 429 with error
1027: an HTML page, or JSON with `"error_code":1027` when the client asks for JSON. The apps treat
it the same way until midnight UTC; other Cloudflare 429s, such as 1015, are not the daily limit.

The count is kept per Worker instance, which reads the day's shared total along with its first D1
query and adds its own usage to the `usage_budget` table after responding: rows written by a report
straight away, rows read two minutes after its first query and then every ten minutes (or 25,000 rows). It is approximate; that is why the defaults leave 20% headroom.
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
`--profile <profile>` to each command. Through npm it goes after `--`
(`npm run db:remote -- --profile <profile>`, and the same for `db:seed:remote` and `deploy`);
without the `--`, npm keeps `--profile` for itself and passes only the name on to wrangler.

Apply the D1 migration before deploying the Worker that uses it. A Worker deployed first answers
health, reports and any stations search that finds stations with 503 `temporarily_unavailable`,
since those read the `area_prices` and `usage_budget` tables that migration 0002 creates; city
search (geocode) and regions read no database and keep working.

If the station snapshot changed, seed D1 after the migration and before deploying, so the station
files and D1's stations table match (otherwise new stations cannot be reported and moved stations
lose their prices until they do). The seed cannot go first: its last statement updates
`current_prices.area`, which migration 0002 adds, so before the migration it fails with
`no such column: area` and D1 returns to its previous state. A remote seed runs as a file import,
and D1 is unavailable to serve queries until the import finishes, so the API cannot answer
requests that need D1 until then (wrangler warns of this; the seed script passes `--yes`, so it
does not ask). No seed is needed if production was last seeded from the current snapshot, that is
when the `imported_at` stored in D1 matches the one in `packages/data/metadata.json`:

```sh
npx wrangler d1 execute openfuel-data --remote --command "SELECT json_extract(data,'$.imported_at') AS imported_at FROM dataset_metadata WHERE id='canada'"
```

Then:

```sh
npx wrangler d1 migrations list openfuel-data --remote   # 0002 should be listed as not yet applied
npm run db:remote        # applies services/live/migrations/0002_area_cache_and_budget.sql
npm run db:seed:remote   # only if the snapshot changed (see above), and only after db:remote
npm run deploy           # builds the site (including the station files) and deploys the Worker
```

If `area_prices` is ever edited out of step with `current_prices`, rebuild it. This is safe while
reports arrive:

```sh
npx wrangler d1 execute openfuel-data --remote --command "UPDATE area_prices SET prices='{}', version=((version+1+(random() & 1073741823)) & 2147483647) | 1; INSERT INTO area_prices(area,version,prices) SELECT area,(random() & 2147483647) | 1,json_group_object(station_id||':'||fuel_type,json_array(station_id,fuel_type,price_milli,observed_at,source)) FROM current_prices WHERE area IS NOT NULL GROUP BY area ON CONFLICT(area) DO UPDATE SET version=((area_prices.version+1+(random() & 1073741823)) & 2147483647) | 1, prices=excluded.prices"
```

Do not enable Workers Caching (the cache in front of the Worker): it bills every request,
including the free static files and station files.
