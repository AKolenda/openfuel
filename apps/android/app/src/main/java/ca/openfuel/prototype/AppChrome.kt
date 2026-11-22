// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// The Android palette: near-black ink with an amber accent, like a pump's price sign. Amber is a fill under ink
// text; [AmberInk] is the accent for text and icons on white.
internal val Ink = Color(0xFF17191E)
internal val Muted = Color(0xFF6B6F76)
internal val Pale = Color(0xFFF2F3F5)
internal val Rule = Color(0xFFE6E7EA)
internal val Amber = Color(0xFFFFB020)
internal val AmberInk = Color(0xFF8F5300)
internal val AmberSoft = Color(0xFFFFF3D9)
/** A price that is missing, or a station not in the numbered list. */
internal val Faint = Color(0xFFB4B6BB)

/** The menu sheet on screen, if any. */
internal enum class Menu { SETTINGS, ALERTS, ABOUT, DETAIL, PRICE, NEW_STATION, CORRECTION }

/** Where the station sheet rests. */
internal enum class Detent { COLLAPSED, HALF, FULL }

/**
 * What the map and its controls need to know about each other. Positions are pixels from the top of
 * native-root, below the status bar.
 */
@Stable internal class MapChrome {
    /** Where the station sheet last settled. */
    var detent by mutableStateOf(Detent.HALF)
    /** The sheet covers the map, including while it is being dragged. */
    var mapCovered by mutableStateOf(false)
    /** The top of the station sheet at [Detent.HALF]. */
    var halfTopPx by mutableIntStateOf(0)
    /** The bottom of the top controls, including the search row while it is open. */
    var topControlsBottomPx by mutableIntStateOf(0)
    /** The bottom of the top controls' first row, which is always there. The map centres itself below it. */
    var mapTopPx by mutableIntStateOf(0)
}

/** The map's visible centre after a pan or zoom, and the short side of the visible map in metres when known. */
internal data class MapMove(val center: SearchPoint, val shortSideMetres: Double = Double.NaN)
