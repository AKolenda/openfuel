// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.pow

/** Production-safe: only GET requests. The map centres itself in the part the controls leave visible and stops drawing when covered. */
@RunWith(AndroidJUnit4::class)
class MapFramingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

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

    private fun mapVisibility(scenario: ActivityScenario<MainActivity>): Int {
        var visibility = -1
        scenario.onActivity { visibility = it.window.decorView.findViewWithTag<android.webkit.WebView>("openfuel-map-webview").visibility }
        return visibility
    }

    @Test fun areaSitsBetweenTheControlsAndTheHalfSheetAndCoveredMapStopsDrawing() {
        val city = SearchPoint(51.0447, -114.0719, "Calgary · chosen city", SearchSource.CITY)
        runBlocking { StationRepository(context).refresh(city) }
        context.getSharedPreferences("openfuel-prototype", Context.MODE_PRIVATE).edit().clear().putBoolean("location-intro-seen", true).commit()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            compose.waitUntil(20_000) { evaluate(scenario, "typeof window.setStations") == "\"function\"" }
            compose.waitUntil(40_000) { evaluate(scenario, "document.querySelectorAll('.station-labels button').length").toInt() > 0 }
            val density = context.resources.displayMetrics.density
            val root = compose.onNodeWithTag("native-root").fetchSemanticsNode().boundsInRoot
            val controls = compose.onNodeWithTag("search-area").fetchSemanticsNode().boundsInRoot.bottom - root.top
            val sheet = compose.onNodeWithTag("station-sheet-handle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top - root.top
            // CSS px are dp. The area's point, where the app centred the map, is the middle of the map between the
            // area row and the half-open sheet.
            assertEquals(controls / density, evaluate(scenario, "map.getPadding().top").toFloat(), 8f)
            assertEquals((root.height - sheet) / density, evaluate(scenario, "map.getPadding().bottom").toFloat(), 8f)
            val area = StationRepository(context).initial().point
            assertEquals((controls + sheet) / 2 / density, evaluate(scenario, "map.project([${area.longitude},${area.latitude}]).y").toFloat(), 8f)

            // Capture the real page's bridge calls during synchronous camera changes. Framing and explicit recenter
            // must never masquerade as pans; ordinary moves report a smaller visible distance as we zoom in.
            val movement = JSONObject(JSONArray("[" + evaluate(scenario, """(()=>{
                const bridge=window.OpenFuelMap,padding=map.getPadding(),center=map.getCenter(),zoom=map.getZoom(),moves=[];
                window.OpenFuelMap={movedView:(...args)=>moves.push(args)};
                try{
                    window.setPadding(padding.top+10,padding.bottom+10);
                    window.setArea(center.lat,center.lng,false);
                    const framingMoves=moves.length;
                    window.setPadding(padding.top,padding.bottom);
                    map.jumpTo({center:[center.lng+.01,center.lat]});
                    map.jumpTo({zoom:14});
                    return JSON.stringify({framingMoves,moves});
                }finally{
                    map.jumpTo({center,zoom});window.setPadding(padding.top,padding.bottom);window.OpenFuelMap=bridge;
                }
            })()""") + "]").getString(0))
            assertEquals(0, movement.getInt("framingMoves"))
            val moves = movement.getJSONArray("moves")
            assertEquals(2, moves.length())
            val far = moves.getJSONArray(0).getDouble(2)
            val near = moves.getJSONArray(1).getDouble(2)
            assertTrue("Visible map distance must be finite and positive", far.isFinite() && far > 0 && near.isFinite() && near > 0)
            assertEquals(2.0.pow(2.5), far / near, .0001)

            val framing = evaluate(scenario, "JSON.stringify([map.getCenter(),map.getPadding(),map.getContainer().clientWidth,map.getContainer().clientHeight])")
            assertEquals(View.VISIBLE, mapVisibility(scenario))
            compose.onNodeWithTag("station-sheet-handle", useUnmergedTree = true).performClick()
            compose.waitUntil(5_000) { mapVisibility(scenario) == View.INVISIBLE }
            assertEquals("Covering the map must preserve its camera, padding and size", framing,
                evaluate(scenario, "JSON.stringify([map.getCenter(),map.getPadding(),map.getContainer().clientWidth,map.getContainer().clientHeight])"))
            compose.onNodeWithTag("station-sheet-handle", useUnmergedTree = true).performClick()
            compose.waitUntil(5_000) { mapVisibility(scenario) == View.VISIBLE }
            assertEquals("Uncovering the map must not reframe it", framing,
                evaluate(scenario, "JSON.stringify([map.getCenter(),map.getPadding(),map.getContainer().clientWidth,map.getContainer().clientHeight])"))
        }
    }
}
