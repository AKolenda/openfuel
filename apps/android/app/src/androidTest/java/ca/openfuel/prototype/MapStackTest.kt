// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production-safe: only GET requests. A tap on a map chip also offers the stations whose chips it hides. */
@RunWith(AndroidJUnit4::class)
class MapStackTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val stackChip = SemanticsMatcher("a station in the stack row") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("stack-") == true }

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

    /**
     * Draws only [stations], all at the map's centre (the last one in front, as it is drawn last), and taps the brand
     * centre there. The tap follows the drawing in one task, so a station update from the app cannot come between.
     */
    private fun tapStationsAtCentre(scenario: ActivityScenario<MainActivity>, stations: List<Station>, accessible: Boolean = false) {
        val payload = JSONArray(stations.map { JSONObject().put("id", it.id).put("name", it.name).put("price", JSONObject.NULL).put("logo", JSONObject.NULL).put("best", false) })
        evaluate(scenario, """(()=>{window.stackTap=null;const c=map.getCenter(),stations=$payload.map(s=>({...s,lat:c.lat,lon:c.lng}));
            window.setStations({logos:{},stations}).then(()=>{if(document.querySelectorAll('.station-labels button').length!==stations.length)return;
             const r=map.getContainer().getBoundingClientRect(),p=map.project(c);
             if($accessible)document.querySelector('.station-labels button:last-child').click();
             else map.getCanvasContainer().dispatchEvent(new MouseEvent('click',{bubbles:true,clientX:r.left+p.x,clientY:r.top+p.y}));
             window.stackTap='tapped';});return true;})()""")
        compose.waitUntil(5_000) { evaluate(scenario, "window.stackTap") == "\"tapped\"" }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.open_maps)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun stackedChipsAreOfferedInTheFrontStationsDetails() {
        runBlocking { StationRepository(context).refresh(SearchPoint(51.0447, -114.0719, "Calgary · chosen city", SearchSource.CITY)) }
        context.getSharedPreferences("openfuel-prototype", Context.MODE_PRIVATE).edit().clear().putBoolean("location-intro-seen", true).commit()
        // Two loaded stations near the centre, told apart by their addresses (a list row prefixes its distance).
        val near = StationRepository(context).initial().stations.filter { it.address.isNotBlank() }.sortedBy { it.distanceMetres }
        val back = near.first()
        val front = near.first { it.address != back.address && it.name != back.name }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            compose.waitUntil(20_000) { evaluate(scenario, "typeof window.setStations") == "\"function\"" }
            compose.waitUntil(40_000) { evaluate(scenario, "document.querySelectorAll('.station-labels button').length").toInt() > 0 }
            tapStationsAtCentre(scenario, listOf(back, front))
            compose.onNodeWithText(front.address).assertExists()
            compose.onNodeWithText(context.getString(R.string.also_at_this_spot)).assertExists()
            compose.onNodeWithTag("stack-${back.id}").assertIsDisplayed()
            compose.onNodeWithTag("stack-${front.id}").assertDoesNotExist()
            // The row lists the whole stack but the station shown, so the tapped one stays one tap away.
            compose.onNodeWithTag("stack-${back.id}").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(back.address).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(front.address).assertDoesNotExist()
            compose.onNodeWithTag("stack-${front.id}").assertIsDisplayed()
            compose.onNodeWithTag("stack-${back.id}").assertDoesNotExist()

            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.open_maps)).fetchSemanticsNodes().isEmpty() }
            // TalkBack invokes the invisible button's click handler instead of the canvas hit test.
            tapStationsAtCentre(scenario, listOf(back, front), accessible = true)
            compose.onNodeWithText(front.address).assertExists()
            compose.onNodeWithTag("stack-${back.id}").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.open_maps)).fetchSemanticsNodes().isEmpty() }
            tapStationsAtCentre(scenario, listOf(front))
            compose.onNodeWithText(front.address).assertExists()
            compose.onAllNodes(stackChip).assertCountEquals(0)
            compose.onNodeWithText(context.getString(R.string.also_at_this_spot)).assertDoesNotExist()
        }
    }
}
