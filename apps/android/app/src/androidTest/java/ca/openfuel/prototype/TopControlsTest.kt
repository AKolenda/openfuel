// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.content.Context
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/** Exercises the controls without a map or network; injected city searches never contact production. */
@RunWith(AndroidJUnit4::class)
class TopControlsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val calgary = PRESET_CITIES[1]
    private val area = mutableStateOf(calgary)
    private val browse = mutableStateOf<MapMove?>(null)
    private val syncing = mutableStateOf(false)
    private val offline = mutableStateOf(false)
    private val areaRequest = mutableIntStateOf(0)
    private val chrome = MapChrome()
    @Volatile private var keyboardVisible = false

    @Before fun clearRecents() {
        context.getSharedPreferences("openfuel-live-v2", Context.MODE_PRIVATE).edit().remove("recent-areas").commit()
    }

    @OptIn(ExperimentalLayoutApi::class)
    private fun show(fontScale: Float? = null, search: suspend (String) -> List<SearchPoint> = { emptyList() },
                     onSearchArea: (SearchPoint) -> Unit = { area.value = it }, onLocation: () -> Unit = {}) {
        compose.setContent {
            val ime = WindowInsets.isImeVisible
            SideEffect { keyboardVisible = ime }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                MaterialTheme {
                    val query = androidx.compose.runtime.remember { mutableStateOf("") }
                    val saved = androidx.compose.runtime.remember { mutableStateOf(false) }
                    Box(Modifier.fillMaxSize().statusBarsPadding().semantics { testTagsAsResourceId = true }) {
                        Box(Modifier.width(360.dp)) {
                            TopControls(chrome, query.value, { query.value = it }, area.value, { browse.value }, syncing.value,
                                offline.value, false, areaRequest.intValue, search, saved.value, { saved.value = it }, {}, onLocation,
                                onSearchArea, { area.value = it })
                        }
                    }
                }
            }
        }
    }

    private fun waitForTag(tag: String, present: Boolean = true) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() == present }
    }

    private fun openCitySearch() {
        compose.onNodeWithTag("search-area").performClick()
        compose.onNodeWithTag("area-choose-city").performScrollTo().performClick()
        waitForTag("city-query")
    }

    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    @Test fun areaDropdownShowsSelectionAndPersistsChosenCities() {
        show()
        compose.onNodeWithTag("search-area")
            .assertContentDescriptionEquals("Area: Calgary")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
        compose.onNodeWithTag("search-area").performClick()
        compose.onNodeWithTag("area-menu").assertIsDisplayed()
        compose.onNodeWithTag("area-map").assertIsNotEnabled()
        compose.onNodeWithTag("city-Calgary").assertIsSelected()
        compose.onNodeWithTag("city-Edmonton").performClick()
        waitForTag("area-menu", false)
        compose.onNodeWithTag("search-area").assertContentDescriptionEquals("Area: Edmonton")
        assertEquals(listOf("Edmonton"), RecentAreas(context).load().map { it.shortLabel() })
        compose.onNodeWithTag("search-area").performClick()
        compose.onNodeWithText("Recent").assertExists()
        compose.onNodeWithTag("city-Edmonton").assertIsSelected()
        compose.onNodeWithTag("city-Toronto").assertDoesNotExist()
        back()
        waitForTag("area-menu", false)
        compose.onNodeWithTag("search-area").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    }

    @Test fun citySearchCancelsClearedQueriesAndImeChoosesFirstResult() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val cancelled = Collections.synchronizedList(mutableListOf<String>())
        val result = SearchPoint(49.6956, -112.8451, "Lethbridge, AB")
        show(search = { query ->
            calls.add(query)
            if (query == "le") try { awaitCancellation() } finally { cancelled.add(query) }
            listOf(result) + (1..6).map { SearchPoint(50.0 + it, -110.0, "Other $it") }
        })
        openCitySearch()
        compose.onNodeWithTag("city-query").assertIsFocused().performTextInput("le")
        compose.waitUntil(5_000) { calls.contains("le") }
        compose.onNodeWithTag("city-query").performTextClearance()
        compose.waitUntil(5_000) { cancelled.contains("le") }
        compose.onNodeWithTag("city-Edmonton").assertExists()
        compose.onNodeWithTag("city-query").performTextInput("leth")
        waitForTag("city-Lethbridge, AB")
        compose.onNodeWithText("5 cities").assertExists()
        compose.onNodeWithTag("city-Other 5").assertDoesNotExist()
        assertEquals(listOf("le", "leth"), calls.toList())
        compose.onNodeWithTag("city-query").performImeAction()
        waitForTag("city-query", false)
        compose.onNodeWithTag("search-area").assertContentDescriptionEquals("Area: Lethbridge, AB")
        assertEquals(result.forStorage(), RecentAreas(context).load().single())
    }

    @Test fun cityFailureCanRetryAndEmptyResultsDoNotSelectAnything() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        show(search = { query ->
            calls.add(query)
            if (calls.size == 1) throw java.io.IOException("test unavailable")
            emptyList()
        })
        openCitySearch()
        compose.onNodeWithTag("city-query").performTextInput("nowhere")
        waitForTag("city-retry")
        compose.onNodeWithText("City search unavailable").assertExists()
        compose.onNodeWithTag("city-retry").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No cities found").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, calls.size)
        compose.onNodeWithTag("city-query").performImeAction()
        compose.waitUntil(5_000) { calls.size == 3 }
        compose.onNodeWithTag("city-query").assertExists()
        assertEquals(calgary, area.value)
        compose.onNodeWithTag("city-close").performClick()
        waitForTag("city-query", false)
    }

    @Test fun citySuggestionsStayBelowTheirFieldWhenKeyboardIsVisible() {
        show(fontScale = 1.3f)
        openCitySearch()
        compose.waitUntil(5_000) { keyboardVisible }
        compose.waitForIdle()
        withWindowBounds { bounds ->
            compose.waitUntil(5_000) { bounds("city-query") != null && bounds("city-results") != null }
            val field = bounds("city-query")!!
            val results = bounds("city-results")!!
            assertTrue("City results $results cover their field $field", results.top >= field.bottom)
            compose.onNodeWithTag("city-Edmonton").assertIsDisplayed()
        }
    }

    @Test fun areaDropdownStaysBelowChipAndScrollsToCitySearch() {
        show(fontScale = 1.3f)
        withWindowBounds { bounds ->
            // A focusable menu hides the underlying window from accessibility until it closes.
            compose.waitUntil(5_000) { bounds("search-area") != null }
            val chip = bounds("search-area")!!
            compose.onNodeWithTag("search-area").performClick()
            compose.waitForIdle()
            compose.waitUntil(5_000) { bounds("area-menu") != null }
            val menu = bounds("area-menu")!!
            assertTrue("Area menu $menu covers its chip $chip", menu.top >= chip.bottom)
            compose.onNodeWithTag("area-choose-city").performScrollTo().assertIsDisplayed().performClick()
            waitForTag("city-query")
            compose.onNodeWithTag("city-query").assertIsFocused()
        }
    }

    /** Compose boundsInWindow has a separate origin for each Popup; use actual Android screen bounds. */
    private fun withWindowBounds(block: ((String) -> android.graphics.Rect?) -> Unit) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val oldInfo = automation.serviceInfo
        val info = automation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = info
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?, tag: String): android.graphics.Rect? {
            if (node == null) return null
            if (node.viewIdResourceName == tag) return android.graphics.Rect().also { node.getBoundsInScreen(it) }
            return (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it), tag) }
        }
        fun bounds(tag: String) = automation.windows.firstNotNullOfOrNull { find(it.root, tag) }
        try { block(::bounds) } finally { automation.serviceInfo = oldInfo }
    }

    @Test fun backHidesKeyboardThenLeavesCitySearch() {
        show()
        compose.runOnIdle { areaRequest.intValue++ }
        waitForTag("city-query")
        // Wait for the actual IME animation before testing platform Back dispatch.
        compose.waitUntil(5_000) { keyboardVisible }
        back()
        compose.onNodeWithTag("city-query").assertExists()
        compose.waitUntil(5_000) { !keyboardVisible }
        back()
        waitForTag("city-query", false)
        compose.onNodeWithTag("search-area").assertIsDisplayed()
    }

    @Test fun searchHereFitsBesideCompactChipAndTracksLoadingAndFullSheet() {
        var loads = 0
        show(fontScale = 1.3f, onSearchArea = { loads++; area.value = it; browse.value = null; syncing.value = true })
        compose.onNodeWithTag("search-map-area").assertDoesNotExist()
        compose.runOnIdle { browse.value = MapMove(calgary.copy(longitude = calgary.longitude + .04), 4_000.0) }
        waitForTag("search-map-area")
        val chip = compose.onNodeWithTag("search-area").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("search-map-area").fetchSemanticsNode().boundsInRoot
        val saved = compose.onNodeWithTag("saved-filter").fetchSemanticsNode().boundsInRoot
        assertTrue("Area chip and search pill overlap", chip.right <= search.left)
        assertTrue("Search pill $search and saved toggle $saved overlap", search.right <= saved.left)
        compose.onNodeWithTag("search-map-area").performClick().assertIsNotEnabled()
        compose.onNodeWithText("Searching…").assertExists()
        assertEquals(1, loads)
        assertEquals(SearchSource.MAP, area.value.source)
        compose.runOnIdle { syncing.value = false }
        waitForTag("search-map-area", false)
        compose.runOnIdle { offline.value = true }
        waitForTag("search-map-area")
        compose.onNodeWithText("Retry").assertExists()
        compose.runOnIdle { chrome.detent = Detent.FULL }
        waitForTag("search-map-area", false)
    }

    @Test fun stationSearchHasOneLabelAndSearchKeyClearsFocus() {
        show(fontScale = 1.3f)
        compose.onAllNodesWithContentDescription(context.getString(R.string.search), useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("station-search").performTextInput("Shell")
        compose.onNodeWithTag("open-settings").assertDoesNotExist()
        compose.onNodeWithTag("clear-search").assertIsDisplayed()
        compose.onNodeWithTag("station-search").performImeAction()
        compose.onNodeWithTag("station-search").assertIsNotFocused()
        compose.onNodeWithTag("clear-search").performClick()
        compose.onNodeWithTag("station-search").assertTextEquals("")
        compose.onNodeWithTag("open-settings").assertIsDisplayed()
        compose.onNodeWithTag("saved-filter").performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Saved stations only"))
    }
}
