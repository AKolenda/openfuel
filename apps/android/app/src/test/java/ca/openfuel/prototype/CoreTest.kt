// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype
import org.junit.Assert.*
import org.junit.Test
class CoreTest {
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
