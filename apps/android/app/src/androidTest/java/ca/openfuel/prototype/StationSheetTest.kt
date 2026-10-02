// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ViewConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * The station sheet's stops, drags, flings and list hand-over, and the controls in its header.
 * Production-safe: only GET requests. Flings use fast swipes and slow drags pause before release, so the release
 * speed, which picks the stop, does not depend on timing.
 */
@RunWith(AndroidJUnit4::class)
class StationSheetTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val full get() = context.getString(R.string.sheet_expanded)
    private val half get() = context.getString(R.string.sheet_half_open)
    private val collapsed get() = context.getString(R.string.sheet_collapsed)

    /** Opens the app on Calgary's stations, with [favorites] saved. */
    private fun launch(favorites: Set<String> = emptySet()): ActivityScenario<MainActivity> {
        ensureCalgaryStations()
        context.getSharedPreferences("openfuel-prototype", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("location-intro-seen", true).putStringSet("favorites", favorites).commit()
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("station-${firstStation()}").fetchSemanticsNodes().isNotEmpty() }
        return scenario
    }

    /** Gesture checks can reuse an actual saved snapshot; NativeScreensTest separately verifies a fresh GET. */
    private fun ensureCalgaryStations() {
        val repository = StationRepository(context)
        val area = SearchPoint(51.0447, -114.0719, "Calgary · chosen city", SearchSource.CITY)
        val saved = repository.initial()
        if (saved.stations.isEmpty() || !saved.point.sameCell(area)) runBlocking { repository.refresh(area) }
    }

    private fun firstStation() = StationRepository(context).initial().stations.minBy { it.distanceMetres }.id
    private fun handle() = compose.onNodeWithTag("station-sheet-handle", useUnmergedTree = true)
    private fun header() = compose.onNodeWithTag("station-sheet-header")
    private fun list() = compose.onNodeWithTag("station-list")
    private fun stop(): String? { compose.waitForIdle(); return handle().fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) }
    private fun sheetTop(): Float {
        compose.waitForIdle()
        return handle().fetchSemanticsNode().boundsInRoot.top
    }
    private fun listScroll() = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    /** Answers "null" until the map WebView exists. */
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

    @Test fun flingsMoveOneStopAndNeverResizeTheMap() {
        launch().use { scenario ->
            compose.waitUntil(20_000) { evaluate(scenario, "typeof window.setStations") == "\"function\"" }
            val viewport = evaluate(scenario, "JSON.stringify([map.getContainer().clientWidth,map.getContainer().clientHeight])")
            assertEquals(half, stop())
            header().performTouchInput { swipeUp(durationMillis = 150) }
            assertEquals(full, stop())
            // From FULL a fling down stops at HALF, never skipping to COLLAPSED.
            header().performTouchInput { swipeDown(durationMillis = 150) }
            assertEquals(half, stop())
            header().performTouchInput { swipeDown(durationMillis = 150) }
            assertEquals(collapsed, stop())
            // Nothing lies below COLLAPSED: the header stays on screen.
            header().performTouchInput { swipeDown(durationMillis = 150) }
            assertEquals(collapsed, stop())
            handle().assertIsDisplayed()
            header().performTouchInput { swipeUp(durationMillis = 150) }
            assertEquals(half, stop())
            // Sideways swipes on the header never move the sheet.
            val before = sheetTop()
            header().performTouchInput { swipeLeft(durationMillis = 150) }
            assertEquals(half, stop())
            assertEquals(before, sheetTop(), 1f)
            header().performTouchInput { swipeRight(durationMillis = 150) }
            assertEquals(half, stop())
            assertEquals(before, sheetTop(), 1f)
            assertEquals("Moving the sheet never resizes the map", viewport, evaluate(scenario, "JSON.stringify([map.getContainer().clientWidth,map.getContainer().clientHeight])"))
        }
    }

    @Test fun heldDragFollowsTheFingerAndSlowReleaseSettlesOnTheNearestStop() {
        launch().use {
            val halfTop = sheetTop()
            handle().performClick()
            assertEquals(full, stop())
            val fullTop = sheetTop()
            handle().performClick()
            assertEquals(half, stop())
            // Once the finger passes the touch slop, the sheet moves with it pixel for pixel.
            val slop = ViewConfiguration.get(context).scaledTouchSlop
            // Inject through the fixed root: the header's local origin changes while it follows the finger.
            val surface = compose.onNodeWithTag("native-root")
            val start = header().fetchSemanticsNode().boundsInRoot.center - surface.fetchSemanticsNode().boundsInRoot.topLeft
            surface.performTouchInput { down(start) }
            repeat(6) { index ->
                surface.performTouchInput { moveBy(Offset(0f, -50f)) }
                assertEquals("Held move ${index + 1}", halfTop - ((index + 1) * 50f - slop), sheetTop(), 2f)
            }
            assertEquals(halfTop - (300f - slop), sheetTop(), 2f)
            surface.performTouchInput { moveBy(Offset(0f, 200f)) }
            assertEquals(halfTop - (100f - slop), sheetTop(), 2f)
            // Released after a pause, the sheet goes back to the nearest stop.
            surface.performTouchInput { advanceEventTime(200); up() }
            assertEquals(half, stop())
            assertEquals(halfTop, sheetTop(), 1f)
            // Past the middle, the nearest stop is FULL.
            val distance = halfTop - fullTop
            header().performTouchInput {
                down(center)
                repeat(20) { moveBy(Offset(0f, -(distance * .6f + slop) / 20), delayMillis = 30) }
                advanceEventTime(200); up()
            }
            assertEquals(full, stop())
            assertEquals(fullTop, sheetTop(), 1f)
        }
    }

    @Test fun listRaisesTheSheetBeforeItScrollsAndLowersItFromTheTop() {
        launch().use {
            assertEquals(half, stop())
            val halfTop = sheetTop()
            handle().performClick()
            val fullTop = sheetTop()
            handle().performClick()
            assertEquals(half, stop())
            // From HALF a fast swipe up on a row raises the sheet to FULL, and the list still starts at its first row.
            // The swipe stays within the distance between the stops, which is shorter than 500 px on small screens.
            val distance = minOf(500f, halfTop - fullTop)
            list().performTouchInput { swipe(Offset(centerX, 48.dp.toPx()), Offset(centerX, 48.dp.toPx() - distance), durationMillis = 150) }
            assertEquals(full, stop())
            assertEquals(0f, listScroll())
            assertEquals(fullTop, sheetTop(), 1f)
            // Once at FULL, the next swipe scrolls the list.
            list().performTouchInput { swipeUp(durationMillis = 300) }
            assertEquals(full, stop())
            assertTrue(listScroll() > 0f)
            assertEquals(fullTop, sheetTop(), 1f)
            // Down again, the list scrolls back first while the sheet stays.
            list().performScrollToIndex(10)
            list().performTouchInput { swipe(center, center + Offset(0f, 300f), durationMillis = 300) }
            assertEquals(full, stop())
            assertTrue(listScroll() > 0f)
            assertEquals(fullTop, sheetTop(), 1f)
            // A fling that ends at the list's top leaves the sheet where it is.
            list().performTouchInput { swipeDown(durationMillis = 100) }
            assertEquals(full, stop())
            assertEquals(fullTop, sheetTop(), 1f)
            // With the list at its top, a swipe down lowers the sheet one stop.
            list().performScrollToIndex(0)
            list().performTouchInput { swipe(Offset(centerX, 48.dp.toPx()), Offset(centerX, 48.dp.toPx() + minOf(450f, distance)), durationMillis = 150) }
            assertEquals(half, stop())
        }
    }

    @Test fun heldListDragHandsEveryDeltaToTheSheet() {
        launch().use {
            val surface = compose.onNodeWithTag("native-root")
            val slop = ViewConfiguration.get(context).scaledTouchSlop
            fun startInList() = Offset(list().fetchSemanticsNode().boundsInRoot.center.x,
                list().fetchSemanticsNode().boundsInRoot.top + 24 * context.resources.displayMetrics.density) -
                surface.fetchSemanticsNode().boundsInRoot.topLeft
            val halfTop = sheetTop()
            handle().performClick()
            assertEquals(full, stop())
            val fullTop = sheetTop()
            handle().performClick()
            assertEquals(half, stop())
            val upwardStart = startInList()
            surface.performTouchInput { down(upwardStart) }
            repeat(6) { index ->
                surface.performTouchInput { moveBy(Offset(0f, -50f)) }
                assertEquals("List-to-sheet move ${index + 1}", halfTop - ((index + 1) * 50f - slop), sheetTop(), 2f)
                assertEquals(0f, listScroll())
            }
            val heldTop = sheetTop()
            surface.performTouchInput { advanceEventTime(200); up() }
            // With zero release velocity the nearest stop wins; the distance between stops varies by screen.
            assertEquals(if (heldTop - fullTop < halfTop - heldTop) full else half, stop())
            if (stop() == half) handle().performClick()
            assertEquals(full, stop())
            val start = startInList()
            surface.performTouchInput { down(start) }
            repeat(3) { index ->
                surface.performTouchInput { moveBy(Offset(0f, 40f)) }
                assertEquals("Top-of-list move ${index + 1}", fullTop + ((index + 1) * 40f - slop), sheetTop(), 2f)
            }
            surface.performTouchInput { advanceEventTime(200); up() }
            assertEquals(full, stop())
        }
    }

    @Test fun randomSwipesNeverLoseTheSheet() {
        launch().use {
            val random = Random(7)
            val root = compose.onNodeWithTag("native-root").fetchSemanticsNode().boundsInRoot
            repeat(30) {
                // Always begin on the visible header and cross touch slop. Tiny random movements can become
                // row taps, opening a detail modal and making later swipes test that modal instead of the sheet.
                val distance = random.nextInt(80, 700) * if (random.nextBoolean()) 1f else -1f
                header().performTouchInput {
                    swipe(center, center + Offset(0f, distance), durationMillis = random.nextLong(60, 600))
                }
                compose.waitForIdle()
                val bounds = handle().fetchSemanticsNode().boundsInRoot
                assertTrue("Swipe $it left the handle at $bounds", bounds.top >= root.top && bounds.bottom <= root.bottom)
                assertTrue(stop() in listOf(full, half, collapsed))
                compose.onNodeWithText(context.getString(R.string.open_maps)).assertDoesNotExist()
            }
        }
    }

    @Test fun headerTapsBackAndActionsMoveTheSheet() {
        launch().use { scenario ->
            val title = context.getString(R.string.sheet_title, context.getString(R.string.regular))
            compose.onNodeWithText(title).performTouchInput { click() }
            assertEquals(full, stop())
            compose.onNodeWithText(title).performTouchInput { click() }
            assertEquals(half, stop())
            header().performTouchInput { swipeDown(durationMillis = 150) }
            assertEquals(collapsed, stop())
            handle().performTouchInput { click() }
            assertEquals(half, stop())
            // Back at FULL goes to HALF and the app stays open.
            handle().performClick()
            assertEquals(full, stop())
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(5_000) { stop() == half }
            assertEquals(half, stop())
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            // TalkBack reaches the other two stops through the handle's actions.
            val actions = handle().fetchSemanticsNode().config[SemanticsActions.CustomActions]
            assertEquals(listOf(context.getString(R.string.sheet_move_full), context.getString(R.string.sheet_move_collapsed)), actions.map { it.label })
            compose.runOnUiThread { actions.last().action() }
            assertEquals(collapsed, stop())
            // The sheet keeps its stop when the activity is recreated, as on rotation.
            scenario.recreate()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("station-sheet-handle", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(collapsed, stop())
            handle().performClick()
            assertEquals(half, stop())
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(5_000) { scenario.state != Lifecycle.State.RESUMED }
        }
    }

    @Test fun locationTracksTheSheetAndFullKeepsTheTopControlsUsable() {
        launch().use {
            val gap = 28 * context.resources.displayMetrics.density
            fun assertLocationGap() {
                val location = compose.onNodeWithTag("use-location").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals(gap, sheetTop() - location.bottom, 2f)
            }
            assertLocationGap()
            val surface = compose.onNodeWithTag("native-root")
            val start = header().fetchSemanticsNode().boundsInRoot.center - surface.fetchSemanticsNode().boundsInRoot.topLeft
            surface.performTouchInput { down(start); repeat(4) { moveBy(Offset(0f, -50f)) } }
            assertLocationGap()
            surface.performTouchInput { advanceEventTime(200); up() }
            if (stop() != full) handle().performClick()
            assertEquals(full, stop())
            compose.onNodeWithTag("use-location").assertDoesNotExist()
            compose.onNodeWithTag("map-credit").assertDoesNotExist()
            compose.onNodeWithTag("search-area").assertIsDisplayed()
            compose.onNodeWithTag("saved-filter").assertIsDisplayed()
            val areaBottom = compose.onNodeWithTag("map-filter-row").fetchSemanticsNode().boundsInRoot.bottom
            assertTrue("FULL leaves the area controls above it", areaBottom < sheetTop())
            val collapse = handle().fetchSemanticsNode().config[SemanticsActions.CustomActions]
                .first { it.label == context.getString(R.string.sheet_move_collapsed) }
            compose.runOnUiThread { collapse.action() }
            assertEquals(collapsed, stop())
            assertLocationGap()
        }
    }

    @Test fun shortLandscapeDropsHalfWithoutHidingTheHeader() {
        launch().use { scenario ->
            try {
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(20_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                compose.waitUntil(5_000) { stop() in listOf(full, collapsed) }
                val actions = handle().fetchSemanticsNode().config[SemanticsActions.CustomActions]
                assertEquals(1, actions.size)
                assertFalse(actions.any { it.label == context.getString(R.string.sheet_move_half) })
                val before = stop()
                handle().performClick()
                assertEquals(if (before == full) collapsed else full, stop())
                val root = compose.onNodeWithTag("native-root").fetchSemanticsNode().boundsInRoot
                val bounds = handle().fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.top >= root.top && bounds.bottom <= root.bottom)
            } finally {
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                compose.waitUntil(20_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            }
        }
    }

    @Test fun collapsedSheetDoesNotPaintRowsThroughTheNavigationInset() {
        launch().use { scenario ->
            header().performTouchInput { swipeDown(durationMillis = 150) }
            assertEquals(collapsed, stop())
            val root = compose.onNodeWithTag("native-root")
            val rootBounds = root.fetchSemanticsNode().boundsInRoot
            val sheetBounds = header().fetchSemanticsNode().boundsInRoot
            var insetHeight = 0
            scenario.onActivity { insetHeight = it.window.decorView.rootWindowInsets
                .getInsets(android.view.WindowInsets.Type.navigationBars()).bottom }
            assertTrue("This gesture-navigation check needs a bottom inset", insetHeight > 0)
            val pixels = root.captureToImage().toPixelMap()
            val top = pixels.height - insetHeight
            val left = (sheetBounds.left - rootBounds.left).toInt()
            val right = (sheetBounds.right - rootBounds.left).toInt()
            // Inspect the top quarter of the actual reserved strip, away from Android's gesture pill. In the
            // regression, the first row's border and tinted background painted here while the header was closed.
            for (y in top until top + (insetHeight / 4).coerceAtLeast(1)) {
                for (x in left until right) {
                    assertEquals("Station content leaked into navigation inset at ($x,$y)", Color.White, pixels[x, y])
                }
            }
        }
    }

    @Test fun rowsHeaderControlsAndSortMenuStillWork() {
        launch().use {
            val station = firstStation()
            // Rows open their details at HALF and at FULL.
            compose.onNodeWithTag("station-$station").performTouchInput { click() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.open_maps)).fetchSemanticsNodes().isNotEmpty() }
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.open_maps)).fetchSemanticsNodes().isEmpty() }
            assertEquals(half, stop())
            handle().performClick()
            assertEquals(full, stop())
            compose.onNodeWithTag("station-$station").performTouchInput { click() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.open_maps)).fetchSemanticsNodes().isNotEmpty() }
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.open_maps)).fetchSemanticsNodes().isEmpty() }
            assertEquals(full, stop())
            compose.onNodeWithTag("layout-cards").performTouchInput { click() }
            compose.onNodeWithTag("layout-cards").assertIsSelected()
            assertEquals(full, stop())
            // Sort opens a menu under its button; picking an order closes it.
            compose.onNodeWithTag("open-sort").performTouchInput { click() }
            compose.onNodeWithTag("sort-best").assertIsSelected()
            compose.onNodeWithTag("sort-nearest").performTouchInput { click() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("sort-nearest").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("open-sort").assertTextEquals(context.getString(R.string.nearest))
            assertEquals(full, stop())
        }
    }

    @Test fun savedOnlyListHasItsOwnTitleCountAndEmptyState() {
        launch(favorites = setOf(firstStationOrNull() ?: "none")).use {
            compose.onNodeWithTag("saved-filter").performTouchInput { click() }
            compose.onNodeWithText(context.getString(R.string.saved)).assertExists()
            compose.onNodeWithText(context.resources.getQuantityString(R.plurals.sheet_station_count, 1, 1)).assertExists()
        }
        launch().use {
            compose.onNodeWithTag("saved-filter").performTouchInput { click() }
            compose.onNodeWithText(context.getString(R.string.sheet_no_saved)).assertExists()
            compose.onNodeWithTag("show-all").performTouchInput { click() }
            compose.onNodeWithText(context.getString(R.string.sheet_title, context.getString(R.string.regular))).assertExists()
        }
    }

    private fun firstStationOrNull() = runCatching {
        ensureCalgaryStations()
        firstStation()
    }.getOrNull()
}
