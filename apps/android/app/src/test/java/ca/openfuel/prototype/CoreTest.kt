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
    @Test fun dailyLimitAnswersAreRecognised() {
        val now = Instant.parse("2026-09-26T19:30:00Z")
        val midnight = Instant.parse("2026-09-27T00:00:00Z")
        assertEquals(Instant.parse("2026-09-27T00:00:00.000Z"),
            serviceLimit(503, true, "spending_cap", "2026-09-27T00:00:00.000Z", "Paused", now)?.resetsAt)
        assertEquals("Paused", serviceLimit(503, true, "spending_cap", "2026-09-27T00:00:00.000Z", "Paused", now)?.message)
        assertEquals(midnight, serviceLimit(503, true, "spending_cap", null, null, now)?.resetsAt)
        // Cloudflare's own daily request limit answers with an HTML 429 page.
        assertEquals(midnight, serviceLimit(429, false, null, null, null, now)?.resetsAt)
        // OpenFuel's JSON rate limits and ordinary outages are not the daily limit.
        assertNull(serviceLimit(429, true, "rate_limited", null, "The hourly report limit was reached.", now))
        assertNull(serviceLimit(503, true, "temporarily_unavailable", null, "Could not reach the station database.", now))
        assertNull(serviceLimit(502, false, null, null, null, now))
    }
    @Test fun gpsFixesInTheSavedCellKeepItsStations() {
        val saved = SearchPoint(53.55, -113.49, "Last location area", SearchSource.DEVICE)
        assertTrue(SearchPoint(53.5461, -113.4938, "Your location", SearchSource.DEVICE).sameCell(saved))
        assertFalse(SearchPoint(53.5561, -113.4938, "Your location", SearchSource.DEVICE).sameCell(saved))
        val station = SampleData.stations().first().copy(id = "osm-node-2", latitude = 53.56, longitude = -113.50, distanceMetres = 1)
        val fix = SearchPoint(53.5461, -113.4938, "Your location", SearchSource.DEVICE)
        assertEquals(distanceMetres(53.546, -113.494, 53.56, -113.50), listOf(station).measuredFrom(fix).single().distanceMetres)
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
