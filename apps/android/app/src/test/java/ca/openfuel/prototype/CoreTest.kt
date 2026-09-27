// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
class CoreTest {
    @Test fun storedSearchAreaIsCoarseAndKeepsCityIdentity() {
        val point = SearchPoint(53.54612345, -113.4938123, "Edmonton · chosen city", SearchSource.CITY).forStorage()
        assertEquals(53.55, point.latitude, 0.0)
        assertEquals(-113.49, point.longitude, 0.0)
        assertEquals(SearchSource.CITY, point.source)
        assertEquals("Edmonton · chosen city", point.label)
    }
    @Test fun savedDistancesUseOnlyTheCoarseSearchArea() {
        val first = SearchPoint(53.54612345, -113.4938123, "Last location area", SearchSource.DEVICE)
        val second = SearchPoint(53.552, -113.491, "Last location area", SearchSource.DEVICE)
        assertEquals(approximateDistanceMetres(first,53.56,-113.51), approximateDistanceMetres(second,53.56,-113.51))
    }
    @Test fun logoRequestsStayOnThePublicCatalogHosts() {
        assertNotNull(allowedBrandLogoUrl("https://thumb.wikimedia.org/wikipedia/commons/logo.png"))
        for (url in listOf("http://www.shell.ca/logo.png", "https://www.shell.ca.evil.example/logo.png", "https://user:secret@www.shell.ca/logo.png", "https://127.0.0.1/logo.png")) assertNull(allowedBrandLogoUrl(url))
    }
    @Test fun pricesAreExact() { assertEquals(1429, FuelCore.parsePrice("142.9")); assertNull(FuelCore.parsePrice("142.99")) }
    @Test fun staleReportsDoNotWin() { assertEquals("cedar", FuelCore.visible(SampleData.stations(),Grade.REGULAR,SortMode.BEST,Filters()).last().id) }
    @Test fun newStationsRequireReview() { assertEquals("pending_review",FuelCore.newProposal("p",ProposalKind.NEW_STATION,null,"Sample",45.0,-75.0,"").state) }
    @Test fun mapSearchNeverUsesFictionalAddress() { assertFalse(FuelCore.sampleMapUrl(SampleData.stations().first()).contains("Parkway")) }
    @Test fun unknownPricesRemainVisibleAndSortLast() {
        val unknown = SampleData.stations().first().copy(id = "osm-node-1", prices = emptyMap(), ages = emptyMap(), latitude = 53.54, longitude = -113.49)
        val visible = FuelCore.visible(listOf(unknown, SampleData.stations().last()), Grade.REGULAR, SortMode.PRICE, Filters())
        assertEquals(2, visible.size)
        assertEquals(unknown.id, visible.last().id)
        assertTrue(FuelCore.directionsUrl(unknown).contains("destination=53.54,-113.49"))
    }
    @Test fun ownReportsShowOverOlderOrMissingPricesForThirtySeconds() {
        val reportedAt = Instant.parse("2026-09-27T12:00:00Z").toEpochMilli()
        val confirmedAt = reportedAt + 400
        val priced = SampleData.stations().first().copy(id = "osm-node-3", prices = mapOf(Grade.REGULAR to 1459, Grade.DIESEL to 1529),
            ages = mapOf(Grade.REGULAR to 90, Grade.DIESEL to 90), priceSources = emptyMap(),
            observedAt = mapOf(Grade.REGULAR to reportedAt - 5_400_000, Grade.DIESEL to reportedAt - 5_400_000))
        val unpriced = priced.copy(id = "osm-node-4", prices = emptyMap(), ages = emptyMap(), observedAt = emptyMap())
        val untouched = priced.copy(id = "osm-node-5")
        val list = listOf(priced, unpriced, untouched)
        val reports = listOf(OwnReport("osm-node-3", Grade.REGULAR, 1429, reportedAt, confirmedAt),
            OwnReport("osm-node-4", Grade.PREMIUM, 1639, reportedAt, confirmedAt))
        val shown = FuelCore.withOwnReports(list, reports, confirmedAt + 29_999)
        assertEquals(1429, shown[0].price(Grade.REGULAR))
        assertEquals(0, shown[0].age(Grade.REGULAR))
        assertEquals(reportedAt, shown[0].observedAt[Grade.REGULAR])
        assertEquals("Community · unverified", shown[0].priceSources[Grade.REGULAR])
        // Only the reported station and fuel change.
        assertEquals(1529, shown[0].price(Grade.DIESEL))
        assertEquals(90, shown[0].age(Grade.DIESEL))
        assertEquals(1639, shown[1].price(Grade.PREMIUM))
        assertNull(shown[1].price(Grade.REGULAR))
        assertEquals(untouched, shown[2])
        // The age counts from the server's observed_at, not from when this device heard back.
        assertEquals(2, FuelCore.withOwnReports(list, reports.map { it.copy(confirmedAt = reportedAt + 150_000) }, reportedAt + 150_000)[0].age(Grade.REGULAR))
        // After 30 s, or if the clock moved back before the confirmation, the answer is shown as is.
        assertEquals(list, FuelCore.withOwnReports(list, reports, confirmedAt + 30_000))
        assertEquals(list, FuelCore.withOwnReports(list, reports, confirmedAt - 1))
    }
    @Test fun newerPricesFromOthersWinOverOwnReports() {
        val reportedAt = Instant.parse("2026-09-27T12:00:00Z").toEpochMilli()
        val report = OwnReport("osm-node-3", Grade.REGULAR, 1429, reportedAt, reportedAt)
        fun listed(observedAt: Long?) = SampleData.stations().first().copy(id = "osm-node-3", prices = mapOf(Grade.REGULAR to 1439),
            ages = mapOf(Grade.REGULAR to 0), observedAt = if (observedAt == null) emptyMap() else mapOf(Grade.REGULAR to observedAt))
        fun shown(station: Station) = FuelCore.withOwnReports(listOf(station), listOf(report), reportedAt + 5_000).single()
        assertEquals(1439, shown(listed(reportedAt + 1)).price(Grade.REGULAR))
        assertEquals(reportedAt + 1, shown(listed(reportedAt + 1)).observedAt[Grade.REGULAR])
        // The server's copy of this report is kept as it is.
        assertEquals(listed(reportedAt), shown(listed(reportedAt)))
        assertEquals(1429, shown(listed(reportedAt - 1)).price(Grade.REGULAR))
        // A snapshot saved before report times were kept counts as older.
        assertEquals(1429, shown(listed(null)).price(Grade.REGULAR))
    }
    @Test fun theLatestOwnReportWinsWhateverTheOrder() {
        val reportedAt = Instant.parse("2026-09-27T12:00:00Z").toEpochMilli()
        val first = OwnReport("osm-node-3", Grade.REGULAR, 1449, reportedAt, reportedAt)
        val second = OwnReport("osm-node-3", Grade.REGULAR, 1419, reportedAt + 10_000, reportedAt + 10_000)
        val station = SampleData.stations().first().copy(id = "osm-node-3", observedAt = emptyMap())
        for (reports in listOf(listOf(first, second), listOf(second, first)))
            assertEquals(1419, FuelCore.withOwnReports(listOf(station), reports, reportedAt + 20_000).single().price(Grade.REGULAR))
        // Once the first report's 30 s are over, the second still shows until its own end.
        assertEquals(1419, FuelCore.withOwnReports(listOf(station), listOf(first, second), reportedAt + 35_000).single().price(Grade.REGULAR))
    }
    @Test fun dailyLimitAnswersAreRecognised() {
        val now = Instant.parse("2026-09-26T19:30:00Z")
        val midnight = Instant.parse("2026-09-27T00:00:00Z")
        val cap = """{"error":"spending_cap","reason":"d1_free_daily_read_limit","scope":"all","resets_at":"2026-09-27T00:00:00.000Z",""" +
            """"message":"OpenFuel's free database allowance for today is used up. Saved stations still show; live prices return after midnight UTC.","donate_url":null}"""
        assertEquals(midnight, serviceLimit(503, cap, now)?.resetsAt)
        assertTrue(serviceLimit(503, cap, now)!!.message!!.startsWith("OpenFuel's free database allowance for today"))
        assertEquals(midnight, serviceLimit(503, """{"error":"spending_cap"}""", now)?.resetsAt)
        // Cloudflare's own daily request limit (error 1027) is RFC 9457 JSON when JSON is asked for, as this app does, and a page otherwise.
        val daily = """{"type":"https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-1xxx-errors/error-1027/",""" +
            """"title":"Error 1027: This website has been temporarily rate limited","status":429,""" +
            """"detail":"You cannot access this site because the owner has reached their plan limits. Check back later once traffic has gone down.",""" +
            """"instance":"9f140b785e57c458","error_code":1027,"error_name":"workers_daily_limit","error_category":"rate_limit",""" +
            """"ray_id":"9f140b785e57c458","timestamp":"2026-09-26T19:30:00Z","zone":"openfuel.ca","cloudflare_error":true,"retryable":false,""" +
            """"owner_action_required":true,"footer":"This error was generated by Cloudflare on behalf of the website owner."}"""
        assertEquals(midnight, serviceLimit(429, daily, now)?.resetsAt)
        assertEquals("OpenFuel's database reached its free daily limit.", serviceLimit(429, daily, now)?.message)
        assertNotNull(serviceLimit(429, """{"error_code":1027}""", now))
        assertNotNull(serviceLimit(429, """{"error_name":"workers_daily_limit"}""", now))
        assertEquals(midnight, serviceLimit(429, "<!DOCTYPE html><html><head><title>Error 1027</title></head><body>This website has been temporarily rate limited</body></html>", now)?.resetsAt)
        assertEquals(midnight, serviceLimit(429, "", now)?.resetsAt)
        // Cloudflare's other 429s, OpenFuel's JSON rate limits and ordinary outages are not the daily limit.
        val rateLimited = """{"type":"https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-1xxx-errors/error-1015/",""" +
            """"title":"Error 1015: You are being rate limited","status":429,"detail":"The website owner has limited how often you can make requests.",""" +
            """"instance":"9f140b785e57c458","error_code":1015,"error_name":"rate_limited","error_category":"rate_limit",""" +
            """"ray_id":"9f140b785e57c458","timestamp":"2026-09-26T19:30:00Z","zone":"openfuel.ca","cloudflare_error":true,"retryable":true,"retry_after":30}"""
        assertNull(serviceLimit(429, rateLimited, now))
        assertNull(serviceLimit(429, """{"error":"rate_limited","message":"The hourly report limit was reached."}""", now))
        assertNull(serviceLimit(503, """{"error":"temporarily_unavailable","message":"Could not reach the station database."}""", now))
        assertNull(serviceLimit(502, "<html><body>Bad gateway</body></html>", now))
    }
    @Test fun gpsFixesInTheSavedCellKeepItsStations() {
        val saved = SearchPoint(53.55, -113.49, "Last location area", SearchSource.DEVICE)
        assertTrue(SearchPoint(53.5461, -113.4938, "Your location", SearchSource.DEVICE).sameCell(saved))
        assertFalse(SearchPoint(53.5561, -113.4938, "Your location", SearchSource.DEVICE).sameCell(saved))
        val station = SampleData.stations().first().copy(id = "osm-node-2", latitude = 53.56, longitude = -113.50, distanceMetres = 1)
        val fix = SearchPoint(53.5461, -113.4938, "Your location", SearchSource.DEVICE)
        assertEquals(distanceMetres(53.546, -113.494, 53.56, -113.50), listOf(station).measuredFrom(fix).single().distanceMetres)
    }
    @Test fun fixesInTheLoadedCellAskAgainOnceItsStationsAreOld() {
        val loadedAt = Instant.parse("2026-09-26T19:30:00Z").toEpochMilli()
        val resetsAt = Instant.parse("2026-09-27T00:00:00Z")
        fun keeps(syncState: String, now: Long, syncing: Boolean = false, silent: Boolean = false) =
            fixKeepsStations(syncing, syncState, silent, loadedAt, resetsAt, now)
        assertTrue(keeps("connected", loadedAt + 59_999))
        // Older stations, or a clock moved back, load again, including when the user taps "Use my location".
        assertFalse(keeps("connected", loadedAt + 60_000))
        assertFalse(keeps("connected", loadedAt - 1))
        // A running load is shared, and the silent fix at startup does not repeat the startup request.
        assertTrue(keeps("cached", loadedAt + 600_000, syncing = true))
        assertTrue(keeps("connected", loadedAt + 600_000, silent = true))
        // Under the daily limit the cell is asked for again once the limit has reset.
        assertTrue(keeps("limited", resetsAt.toEpochMilli() - 1))
        assertFalse(keeps("limited", resetsAt.toEpochMilli()))
        // Saved or failed answers are asked for again.
        assertFalse(keeps("cached", loadedAt + 1_000))
        assertFalse(keeps("offline", loadedAt + 1_000, silent = true))
    }
    @Test fun requestsForOneCellShareOneLoad() = runBlocking {
        val loads = SharedLoads<Pair<Double, Double>, Int>(this)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val first = loads.start(53.55 to -113.49) { calls++; gate.await(); 7 }
        val second = loads.start(53.55 to -113.49) { calls++; 8 }
        val other = loads.start(51.04 to -114.07) { calls++; 9 }
        gate.complete(Unit)
        assertEquals(listOf(7, 7, 9), listOf(first.await(), second.await(), other.await()))
        assertEquals(2, calls)
        // A finished load is not reused: the next request for the cell asks again.
        assertEquals(10, loads.start(53.55 to -113.49) { calls++; 10 }.await())
        assertEquals(3, calls)
    }
}
