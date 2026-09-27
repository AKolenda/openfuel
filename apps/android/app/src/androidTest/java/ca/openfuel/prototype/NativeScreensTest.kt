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

    private fun mapView(scenario: ActivityScenario<MainActivity>): Boolean {
        var found = false
        scenario.onActivity { found = it.window.decorView.findViewWithTag<android.webkit.WebView>("openfuel-map-webview") != null }
        return found
    }

    /** Answers "null" until the map WebView exists; it is created after the first frame. */
    private fun evaluate(scenario: ActivityScenario<MainActivity>, script: String): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = ""
        scenario.onActivity { activity ->
            val web = activity.window.decorView.findViewWithTag<android.webkit.WebView>("openfuel-map-webview")
            if (web == null) { result = "null"; latch.countDown() }
            else web.evaluateJavascript(script) { result = it; latch.countDown() }
        }
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        return result
    }

    @Test fun panSearchAndCardsSheetRestoreStayUsable() {
        runBlocking { StationRepository(context).refresh(SearchPoint(51.0447, -114.0719, "Calgary · chosen city", SearchSource.CITY)) }
        context.getSharedPreferences("openfuel-prototype", Context.MODE_PRIVATE).edit().clear().putBoolean("location-intro-seen", true).commit()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            compose.waitUntil(20_000) { evaluate(scenario, "typeof window.setStations") == "\"function\"" }
            compose.waitUntil(40_000) { evaluate(scenario, "document.querySelectorAll('.station-labels button').length").toInt() > 0 }
            compose.onNodeWithTag("layout-cards").performTouchInput { click() }
            compose.onNodeWithTag("layout-cards").assertIsSelected()
            compose.onNodeWithTag("station-sheet-handle", useUnmergedTree = true).performTouchInput { swipe(start = center, end = Offset(center.x, center.y + 600), durationMillis = 200) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("show-stations").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("show-stations").assertIsDisplayed().performTouchInput { click() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("show-stations").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("layout-list").assertIsSelected()
            compose.onNodeWithTag("station-sheet-handle", useUnmergedTree = true).performTouchInput { swipe(start = center, end = Offset(center.x, center.y + 600), durationMillis = 200) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("show-stations").fetchSemanticsNodes().isNotEmpty() }
            assertTrue("Map has a usable viewport: " + evaluate(scenario, "JSON.stringify([map.getContainer().clientWidth,map.getContainer().clientHeight])"), evaluate(scenario, "map.getContainer().clientHeight").toDouble() > 200)
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
            // Chips are drawn in the map's own WebGL frame, so they cannot drift from it. After an animated pan, a
            // tap on a station's logo centre, found from its TalkBack button, opens a station's details.
            evaluate(scenario, "map.panBy([100,50],{duration:400});true")
            compose.waitUntil(5_000) { evaluate(scenario, "map.isMoving()") == "false" }
            screenshot("android-pan-search-restorable-sheet")
            evaluate(scenario, """(()=>{const c=map.getContainer().getBoundingClientRect(),r=[...document.querySelectorAll('.station-labels button')].map(b=>b.getBoundingClientRect())
                .find(r=>r.left>c.left&&r.right<c.right&&r.top>c.top&&r.bottom<c.bottom);
                map.getCanvasContainer().dispatchEvent(new MouseEvent('click',{bubbles:true,clientX:r.left+r.width/2,clientY:r.bottom-20}));return true;})()""")
            compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.open_maps)).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun unclutteredControlsAndIndependentListRefresh() {
        runBlocking { StationRepository(context).refresh(SearchPoint(51.0447, -114.0719, "Calgary · chosen city", SearchSource.CITY)) }
        context.getSharedPreferences("openfuel-prototype", Context.MODE_PRIVATE).edit().clear().putBoolean("location-intro-seen", true).commit()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            compose.waitUntil(20_000) { evaluate(scenario, "typeof window.setStations") == "\"function\"" }
            compose.waitUntil(40_000) { evaluate(scenario, "document.querySelectorAll('.station-labels button').length").toInt() > 0 }
            compose.onNodeWithTag("fuel-regular").assertDoesNotExist()
            compose.onNodeWithTag("map-info").assertDoesNotExist()
            compose.onNodeWithTag("search-map-area").assertDoesNotExist()
            compose.onNodeWithTag("refresh-prices").assertDoesNotExist()
            compose.onNodeWithTag("open-settings").performTouchInput { click() }
            compose.onNodeWithTag("fuel-diesel").assertIsDisplayed().performTouchInput { click() }
            assertEquals("DIESEL", context.getSharedPreferences("openfuel-prototype", Context.MODE_PRIVATE).getString("fuel-grade", null))
            compose.onNodeWithTag("open-about").assertExists()
            compose.onNodeWithTag("fuel-regular").performTouchInput { click() }
            // The settings close action is the SheetTitle close control.
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("fuel-regular").fetchSemanticsNodes().isEmpty() }
            val viewport = evaluate(scenario, "JSON.stringify([map.getContainer().clientWidth,map.getContainer().clientHeight])")
            val camera = evaluate(scenario, "JSON.stringify(map.getCenter())")
            compose.onNodeWithTag("station-sheet-handle", useUnmergedTree = true).performTouchInput { click() }
            compose.waitForIdle()
            val handleBefore = compose.onNodeWithTag("station-sheet-handle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
            compose.onNodeWithTag("station-list").performTouchInput { swipeUp(durationMillis = 400) }
            compose.waitForIdle()
            val handleAfter = compose.onNodeWithTag("station-sheet-handle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
            assertEquals("Scrolling rows does not drag the sheet", handleBefore, handleAfter, 1f)
            assertEquals("Expanding/scrolling never resizes map tiles", viewport, evaluate(scenario, "JSON.stringify([map.getContainer().clientWidth,map.getContainer().clientHeight])"))
            assertEquals(camera, evaluate(scenario, "JSON.stringify(map.getCenter())"))
            compose.onNodeWithTag("station-list").performScrollToIndex(0)
            compose.waitUntil(40_000) { compose.onNodeWithTag("pull-refresh").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription] == "Idle" }
            val beforeRefresh = context.getSharedPreferences("openfuel-live-v2", Context.MODE_PRIVATE).getLong("saved-at", 0)
            // A successful GET refresh updates the saved snapshot; no test price is submitted.
            compose.onNodeWithTag("station-list").performTouchInput { swipe(start = Offset(center.x, 30f), end = Offset(center.x, height*.7f), durationMillis = 500) }
            compose.waitUntil(40_000) { context.getSharedPreferences("openfuel-live-v2", Context.MODE_PRIVATE).getLong("saved-at", 0) > beforeRefresh }
            compose.onNodeWithTag("show-stations").assertDoesNotExist()
            assertEquals(camera, evaluate(scenario, "JSON.stringify(map.getCenter())"))
            screenshot("android-uncluttered-list")
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
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            compose.onNodeWithText("Find fuel around you").assertExists()
            // No base map (and no tile request) sits behind the first-arrival question.
            Thread.sleep(1_500)
            assertFalse("Map loaded before an area was known", mapView(scenario))
            compose.onNodeWithText("Choose a city").performClick()
            compose.onNodeWithText("Choose your area").assertExists()
            compose.onNodeWithText("Edmonton · chosen city").assertExists()
            screenshot("android-first-arrival")
            assertFalse(mapView(scenario))
            compose.onNodeWithTag("city-Edmonton").performClick()
            compose.waitUntil(20_000) { evaluate(scenario, "typeof window.setStations") == "\"function\"" }
            assertEquals("true", evaluate(scenario, "Math.abs(map.getCenter().lat-53.546)<0.01"))
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
            // The credit names OpenStreetMap on either base map (OpenFreeMap or the raster fallback).
            compose.onNodeWithText("© OpenStreetMap contributors", substring = true).assertExists()
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
