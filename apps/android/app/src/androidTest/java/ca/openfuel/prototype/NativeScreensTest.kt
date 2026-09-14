// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Production-safe: only GET requests. Inject test GPS into an emulator before running. */
@RunWith(AndroidJUnit4::class)
class NativeScreensTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun evaluate(scenario: ActivityScenario<MainActivity>, script: String): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = ""
        scenario.onActivity { activity ->
            val web = activity.window.decorView.findViewWithTag<android.webkit.WebView>("openfuel-map-webview")
            web.evaluateJavascript(script) { result = it; latch.countDown() }
        }
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        return result
    }

    @Test fun panSearchAndCardsSheetRestoreStayUsable() {
        runBlocking { StationRepository(context).refresh(SearchPoint(51.0447, -114.0719, "Calgary · chosen city", SearchSource.CITY)) }
        context.getSharedPreferences("openfuel-prototype", Context.MODE_PRIVATE).edit().clear().putBoolean("location-intro-seen", true).commit()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            compose.waitUntil(20_000) { evaluate(scenario, "typeof window.setStations") == "\"function\"" }
            compose.waitUntil(40_000) { evaluate(scenario, "markers.size").toInt() > 0 }
            compose.onNodeWithTag("layout-cards").performTouchInput { click() }
            compose.onNodeWithTag("layout-cards").assertIsSelected()
            compose.onNodeWithTag("station-sheet-handle", useUnmergedTree = true).performTouchInput { swipe(start = center, end = Offset(center.x, center.y + 600), durationMillis = 200) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("show-stations").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("show-stations").assertIsDisplayed().performTouchInput { click() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("show-stations").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("layout-list").assertIsSelected()
            compose.onNodeWithTag("station-sheet-handle", useUnmergedTree = true).performTouchInput { swipe(start = center, end = Offset(center.x, center.y + 600), durationMillis = 200) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("show-stations").fetchSemanticsNodes().isNotEmpty() }
            assertTrue("Map has a usable viewport: " + evaluate(scenario, "JSON.stringify(map.getSize())"), evaluate(scenario, "map.getSize().y").toDouble() > 200)
            val before = evaluate(scenario, "map.getCenter().lng").toDouble()
            val previousIDs = StationRepository(context).initial().stations.map { it.id }.toSet()
            compose.onNodeWithTag("station-map").performTouchInput { swipe(start = Offset(width*.2f,height*.5f), end = Offset(width*.8f,height*.5f), durationMillis = 650) }
            compose.waitUntil(5_000) { kotlin.math.abs(evaluate(scenario, "map.getCenter().lng").toDouble() - before) > .02 }
            // Stop any inertial scroll before recording the precise camera searched.
            evaluate(scenario, "map.stop();true")
            val longitude = evaluate(scenario, "map.getCenter().lng").toDouble()
            val zoom = evaluate(scenario, "map.getZoom()").toDouble()
            compose.onNodeWithTag("search-map-area").assertIsDisplayed().performTouchInput { click() }
            compose.waitUntil(40_000) { StationRepository(context).initial().stations.map { it.id }.toSet() != previousIDs && StationRepository(context).initial().point.source == SearchSource.MAP }
            assertEquals(longitude, StationRepository(context).initial().point.longitude, .0051)
            assertEquals(longitude, evaluate(scenario, "map.getCenter().lng").toDouble(), .00001)
            assertEquals(zoom, evaluate(scenario, "map.getZoom()").toDouble(), 0.0)
            compose.onNodeWithTag("show-stations").assertIsDisplayed()
            // Test-only marker, never sent to the API: price is above the logo and the
            // logo centre stays anchored throughout an animated pan and zoom.
            evaluate(scenario, """window.setStations({stations:[{id:'visual-test',name:'Test only',lat:map.getCenter().lat,lon:map.getCenter().lng,price:1499}],logos:{},location:null});true""")
            assertEquals("true", evaluate(scenario, "document.querySelector('.station-price').getBoundingClientRect().bottom <= document.querySelector('.station-brand').getBoundingClientRect().top"))
            evaluate(scenario, """window.maxDrift=0;window.trackAnchor=()=>{const item=markers.get('visual-test'),p=map.latLngToContainerPoint(item.marker.getLatLng()),r=document.querySelector('.station-brand').getBoundingClientRect(),m=map.getContainer().getBoundingClientRect();window.maxDrift=Math.max(window.maxDrift,Math.abs(r.x+r.width/2-m.x-p.x),Math.abs(r.y+r.height/2-m.y-p.y));};map.on('move',trackAnchor);map.panBy([100,50],{animate:true,duration:.4});true""")
            compose.waitUntil(5_000) { evaluate(scenario, "!map._panAnim._inProgress") == "true" }
            assertTrue("Marker follows the map frame", evaluate(scenario, "window.maxDrift").toDouble() <= 2.0)
            screenshot("android-pan-search-restorable-sheet")
        }
    }

    @Test fun chosenCityRestoresAndStationSheetCanFullyHide() {
        val city = SearchPoint(51.0447, -114.0719, "Calgary · chosen city", SearchSource.CITY)
        val repository = StationRepository(context)
        runBlocking { repository.refresh(city) }
        context.getSharedPreferences("openfuel-prototype", Context.MODE_PRIVATE).edit().clear().putBoolean("location-intro-seen", true).commit()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            compose.onNodeWithText("Calgary").assertExists()
            compose.onNodeWithTag("station-sheet-handle", useUnmergedTree = true).performTouchInput { swipe(start = center, end = Offset(center.x, center.y + 600), durationMillis = 200) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("show-stations").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Calgary").assertExists()
            screenshot("android-map-only")
            compose.onNodeWithTag("show-stations").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("show-stations").fetchSemanticsNodes().isEmpty() }
            assertEquals(SearchSource.CITY, repository.initial().point.source)
            assertEquals("Calgary · chosen city", repository.initial().point.label)
            assertEquals(51.04, repository.initial().point.latitude, 0.0)
            assertEquals(-114.07, repository.initial().point.longitude, 0.0)
            assertTrue(repository.initial().stations.all { station ->
                station.distanceMetres == approximateDistanceMetres(repository.initial().point, station.latitude!!, station.longitude!!)
            })
        }
    }

    @Test fun firstArrivalWaitsForLocationOrChosenArea() {
        context.getSharedPreferences("openfuel-prototype", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("openfuel-live-v2", Context.MODE_PRIVATE).edit().clear().commit()
        val initial = StationRepository(context).initial()
        assertFalse(initial.cached)
        assertTrue(initial.stations.isEmpty())
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            compose.onNodeWithText("Find fuel around you").assertExists()
            compose.onNodeWithText("Choose a city").performClick()
            compose.onNodeWithText("Choose your area").assertExists()
            compose.onNodeWithText("Edmonton · chosen city").assertExists()
            screenshot("android-first-arrival")
        }
    }

    @Test fun actualLocationRealStationsAndNativeMap() {
        context.getSharedPreferences("openfuel-prototype", Context.MODE_PRIVATE).edit().clear().putBoolean("location-intro-seen", true).commit()
        context.getSharedPreferences("openfuel-live-v2", Context.MODE_PRIVATE).edit().clear().commit()
        var stationCount = 0
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            // Android grants foreground location only while the activity is visible.
            val fix = runBlocking { currentSearchPoint(context) }
            assertNotNull("Inject emulator GPS first", fix)
            assertTrue("Test GPS should be in Canada", fix!!.latitude in 41.0..84.0)
            val stations = runBlocking { StationRepository(context).refresh(fix) }
            stationCount = stations.size
            assertTrue("Seeded nearby stations expected", stations.isNotEmpty())
            assertTrue(stations.all { FuelCore.validStationPoint(it.latitude,it.longitude) })
            compose.waitUntil(40_000) { compose.onAllNodesWithText("Your location").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(40_000) { compose.onAllNodesWithTag("station-${stations.first().id}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("© OpenStreetMap contributors").assertExists()
            screenshot("android-live-location")
            compose.onNodeWithTag("layout-cards").performClick()
            compose.onNodeWithTag("layout-list").performClick()
            compose.onNodeWithTag("station-${stations.first().id}").performClick()
            compose.onNodeWithText(context.getString(R.string.open_maps)).assertExists()
            compose.onNodeWithText(context.getString(R.string.report_price)).assertExists()
            screenshot("android-real-station")
        }
        val saved = StationRepository(context).initial()
        assertTrue(saved.cached)
        assertEquals(stationCount, saved.stations.size)
    }

    private fun screenshot(name: String) {
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory,"$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        bitmap.recycle()
    }
}
