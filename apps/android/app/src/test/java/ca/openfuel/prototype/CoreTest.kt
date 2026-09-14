// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype
import org.junit.Assert.*
import org.junit.Test
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
}
