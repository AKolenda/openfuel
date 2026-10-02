// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype
import org.junit.Assert.*
import org.junit.Test
class PlacesTest {
    @Test fun cityNamesAreShortWithProvinceCodes() {
        assertEquals("Lethbridge, AB", shortPlaceName("Lethbridge, Alberta, Canada"))
        assertEquals("Ahuntsic-Cartierville, QC", shortPlaceName("Ahuntsic-Cartierville, Quebec, Canada"))
        assertEquals("Charlottetown, PE", shortPlaceName("Charlottetown, Prince Edward Island, Canada"))
        assertEquals("Yellowknife, NT", shortPlaceName(" Yellowknife ,Northwest Territories,Canada "))
        // French names map to the same codes.
        assertEquals("Moncton, NB", shortPlaceName("Moncton, Nouveau-Brunswick, Canada"))
        assertEquals("Gatineau, QC", shortPlaceName("Gatineau, Québec, Canada"))
        assertEquals("Halifax, NS", shortPlaceName("Halifax, Nouvelle-Écosse, Canada"))
        // Anything else after the first part is dropped, as is Canada.
        assertEquals("Banff, AB", shortPlaceName("Banff, Improvement District No. 9, Alberta, Canada"))
        assertEquals("Springfield", shortPlaceName("Springfield, Somewhere"))
        assertEquals("Calgary", shortPlaceName("Calgary"))
        assertEquals("Canada", shortPlaceName("Canada"))
    }
    @Test fun presetCitiesKeepTheirSavedLabelsAndTags() {
        assertEquals(listOf("Edmonton", "Calgary", "Vancouver", "Toronto"), PRESET_CITIES.map { it.shortLabel() })
        assertTrue(PRESET_CITIES.all { it.source == SearchSource.CITY && it.label.endsWith(" · chosen city") })
    }
    @Test fun searchHereScalesWithTheVisibleMap() {
        val calgary = SearchPoint(51.0447, -114.0719, "Calgary · chosen city")
        fun east(metres: Double, side: Double = Double.NaN) =
            MapMove(SearchPoint(calgary.latitude, calgary.longitude + metres / (111_320 * Math.cos(Math.toRadians(calgary.latitude))), "Map area", SearchSource.MAP), side)
        assertFalse(mapMovedAway(calgary, null))
        // Without the map's size, 750 m.
        assertFalse(mapMovedAway(calgary, east(700.0)))
        assertTrue(mapMovedAway(calgary, east(800.0)))
        // A quarter of the short side: a 7 km wide view needs 1.75 km.
        assertFalse(mapMovedAway(calgary, east(1_400.0, 7_000.0)))
        assertTrue(mapMovedAway(calgary, east(2_100.0, 7_000.0)))
        // Zoomed in, never under 300 m.
        assertFalse(mapMovedAway(calgary, east(250.0, 600.0)))
        assertTrue(mapMovedAway(calgary, east(350.0, 600.0)))
        // Zooming in or out on the loaded point is not a move, although the saved cell is rounded.
        assertFalse(mapMovedAway(calgary, east(0.0, 400.0)))
        assertFalse(mapMovedAway(calgary, east(0.0, 70_000.0)))
    }
    @Test fun recentAreasRecoverValidEntriesAndDeduplicateStoredCells() {
        val data = """[null,{"latitude":53.546,"longitude":-113.494,"label":"Edmonton"},
            {"latitude":53.547,"longitude":-113.493,"label":"Duplicate"},
            {"latitude":51.0447,"longitude":-114.0719,"label":"Calgary"},
            {"latitude":51,"longitude":-114,"label":"  "}]"""
        assertEquals(listOf("Edmonton", "Calgary"), decodeRecentAreas(data).map { it.label })
    }
    @Test fun recentAreasKeepFourChosenCitiesCoarselyAndNeverDeviceFixes() {
        val cities = listOf(SearchPoint(49.69564, -112.84514, "Lethbridge, AB"), SearchPoint(53.5461, -113.4938, "Edmonton · chosen city"),
            SearchPoint(51.0447, -114.0719, "Calgary · chosen city"), SearchPoint(49.2827, -123.1207, "Vancouver · chosen city"),
            SearchPoint(43.6532, -79.3832, "Toronto · chosen city"))
        val recent = cities.fold(emptyList<SearchPoint>()) { list, city -> withRecentArea(list, city) }
        assertEquals(listOf("Toronto · chosen city", "Vancouver · chosen city", "Calgary · chosen city", "Edmonton · chosen city"), recent.map { it.label })
        assertEquals(43.65, recent.first().latitude, 0.0)
        // Choosing one again moves it to the top once; a nearby point in the same cell counts as the same city.
        val again = withRecentArea(recent, SearchPoint(51.0431, -114.0701, "Calgary · chosen city"))
        assertEquals(listOf("Calgary · chosen city", "Toronto · chosen city", "Vancouver · chosen city", "Edmonton · chosen city"), again.map { it.label })
        assertEquals(again, withRecentArea(again, SearchPoint(51.0447, -114.0719, "Your location", SearchSource.DEVICE)))
        assertEquals(again, withRecentArea(again, SearchPoint(51.1, -114.2, "Map area", SearchSource.MAP)))
        assertEquals(again, decodeRecentAreas(encodeRecentAreas(again)))
        assertEquals(emptyList<SearchPoint>(), decodeRecentAreas(null))
        assertEquals(emptyList<SearchPoint>(), decodeRecentAreas("not json"))
        assertEquals(listOf("Edmonton"), decodeRecentAreas("""[{"latitude":999,"longitude":0,"label":"Nowhere"},{"latitude":53.546,"longitude":-113.494,"label":"Edmonton"}]""").map { it.label })
    }
}
