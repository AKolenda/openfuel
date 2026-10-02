// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated menu UI: no activity startup, location or network requests. */
class MenuAnimationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun closeSlidesOutBeforeItsActionAndIgnoresRepeatedTaps() {
        val menu = mutableStateOf<Menu?>(Menu.SETTINGS)
        var actions = 0
        compose.setContent {
            MaterialTheme {
                MenuHost({ menu.value }, { null }, { menu.value = null }) { _, _, close ->
                    Button(onClick = { close { actions++ } }) { Text("Close test menu") }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Close test menu").performClick().performClick()
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { assertEquals(0, actions); assertEquals(Menu.SETTINGS, menu.value) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertNull(menu.value); assertEquals(1, actions) }
        compose.onNodeWithText("Close test menu").assertDoesNotExist()
    }

    @Test fun leavingTheHostDuringCloseDoesNotRunItsDeferredAction() {
        val mounted = mutableStateOf(true)
        var actions = 0
        compose.setContent {
            MaterialTheme {
                if (mounted.value) MenuHost({ Menu.SETTINGS }, { null }, {}) { _, _, close ->
                    Button(onClick = { close { actions++ } }) { Text("Close test menu") }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Close test menu").performClick()
        compose.runOnUiThread { mounted.value = false }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun switchingStationDetailsKeepsEachTransitionBoundToItsStation() {
        val station = mutableStateOf("first station")
        compose.setContent {
            MaterialTheme {
                MenuHost({ Menu.DETAIL }, { station.value }, {}) { _, id, _ -> Text(id.orEmpty()) }
            }
        }
        compose.onNodeWithText("first station").assertIsDisplayed()
        compose.runOnIdle { station.value = "second station" }
        compose.waitForIdle()
        compose.onNodeWithText("second station").assertIsDisplayed()
        compose.onNodeWithText("first station").assertDoesNotExist()
    }
}
