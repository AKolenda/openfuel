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
