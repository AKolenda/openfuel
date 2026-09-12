// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrototypeSyncTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realParserRejectsSampleGeographyAndRetainsUnknownPrices() {
        val repository = StationRepository(ApplicationProvider.getApplicationContext<Context>())
        val body = JSONObject("""{"is_demo":false,"stations":[{"id":"osm-node-test","name":"Test station","brand":"","address":"Test address","latitude":53.54,"longitude":-113.49,"distanceMetres":100,"prices":{"regular":null,"premium":null,"diesel":null},"synthetic":false}]}""")
        val station = repository.parseStations(body).single()
        assertNull(station.price(Grade.REGULAR))
        assertNull(station.open)
        assertEquals(1, FuelCore.visible(listOf(station),Grade.REGULAR,SortMode.BEST,Filters()).size)
        body.put("is_demo",true)
        assertTrue(runCatching { repository.parseStations(body) }.isFailure)
    }

    /** Never writes to production: this test is gated to the explicit isolated emulator server. */
    @Test fun localNativeReportReachesDatabaseAndSurvivesReload() {
        assumeTrue(BuildConfig.API_BASE_URL.startsWith("http://10.0.2.2:"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("openfuel-prototype",Context.MODE_PRIVATE).edit().clear().putBoolean("location-intro-seen",true).commit()
        val repository = StationRepository(context)
        val before = runBlocking { repository.refresh(SearchPoint.EDMONTON) }
        val station = FuelCore.visible(before,Grade.REGULAR,SortMode.BEST,Filters()).first()
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use {
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("station-${station.id}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("station-${station.id}").performClick()
            compose.onNodeWithText(context.getString(R.string.report_price)).performClick()
            compose.onNodeWithText(context.getString(R.string.price_label)).performTextReplacement("142.9")
            compose.onNodeWithTag("submit-report").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("submit-report").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Community report · unverified").assertExists()
        }
        val after = runBlocking { repository.refresh(SearchPoint.EDMONTON) }
        assertEquals(1429, after.first { it.id == station.id }.price(Grade.REGULAR))
        assertEquals(1429, StationRepository(context).initial().stations.first { it.id == station.id }.price(Grade.REGULAR))
    }
}
