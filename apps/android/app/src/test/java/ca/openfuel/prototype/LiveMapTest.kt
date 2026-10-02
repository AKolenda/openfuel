// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class LiveMapTest {
    private fun stackFrom(json: String): List<String>? {
        var ids: List<String>? = null
        MapBridge(ready = {}, moved = { _, _, _ -> }, selected = { ids = it }, base = {}).selectedStack(json)
        return ids
    }
    @Test fun stackedChipsPassOneToTwelveShortIds() {
        assertEquals(listOf("osm-way-149608840", "osm-node-9398106284"), stackFrom("""["osm-way-149608840","osm-node-9398106284"]"""))
        assertEquals(12, stackFrom(JSONArray((1..12).map { "id-$it" }).toString())?.size)
        assertEquals(1, stackFrom(JSONArray(listOf("x".repeat(120))).toString())?.size)
        for (json in listOf("[]", JSONArray((1..13).map { "id-$it" }).toString(), """[""]""", JSONArray(listOf("x".repeat(121))).toString(),
            "[1]", """["a",null]""", """[["a"]]""", "{}", """"a"""", "not json")) assertNull(json, stackFrom(json))
    }
    @Test fun movesReportTheVisibleSizeOnlyWhenKnown() {
        var side = 0.0
        val bridge = MapBridge(ready = {}, moved = { _, _, metres -> side = metres }, selected = {}, base = {})
        bridge.movedView(51.04, -114.07, 2400.0)
        assertEquals(2400.0, side, 0.0)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -5.0)) { side = 0.0; bridge.movedView(51.04, -114.07, bad); assertTrue(side.isNaN()) }
        side = 0.0; bridge.moved(51.04, -114.07) // The Leaflet page.
        assertTrue(side.isNaN())
    }
    @Test fun leafletSelectionRemainsSingleAndRejectsInvalidIds() {
        val selections = mutableListOf<List<String>>()
        val bridge = MapBridge(ready = {}, moved = { _, _, _ -> }, selected = { selections.add(it) }, base = {})
        bridge.selected("osm-node-1")
        bridge.selected("")
        bridge.selected("x".repeat(121))
        assertEquals(listOf(listOf("osm-node-1")), selections)
    }
    /** The page's files come only from mapFile(), so a file it loads that is not served, or not bundled, leaves the map blank. */
    @Test fun everyFileTheMapPageLoadsIsServedAndBundled() {
        val page = File("src/main/assets/station-map.html").readText()
        val script = File("src/main/assets/station-map.js").readText()
        // The page's own tags, and the Leaflet page's files that station-map.js adds without WebGL.
        val leaflet = Regex("""\[('[^']+'(?:,'[^']+')*)]\.map\(file=>""").find(script)!!.groupValues[1].split(',').map { it.trim('\'') }
        val loaded = listOf("station-map.html") + Regex("""(?:src|href)="([^"]+)"""").findAll(page).map { it.groupValues[1] } + leaflet
        assertEquals(mapFiles.keys, loaded.toSet())
        // The same directories as the app's assets in build.gradle.kts.
        val bundled = listOf("src/main/assets", "../../web/preview/vendor", "../../web/preview/map").map(::File)
        for (name in mapFiles.keys) {
            val source = if (name == "map-style.js") "openfuel-style.json" else name
            assertTrue("$source is not bundled", bundled.any { File(it, source).isFile })
        }
    }
}
