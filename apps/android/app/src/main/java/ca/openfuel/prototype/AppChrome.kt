// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

internal val Forest = Color(DesignTokens.GREEN)
internal val Ink = Color(DesignTokens.INK)
internal val Muted = Color(DesignTokens.MUTED)
internal val Pale = Color(DesignTokens.SOFT)
internal val Rule = Color(DesignTokens.LINE)

/** The menu sheet on screen, if any. */
internal enum class Menu { SETTINGS, ABOUT, DETAIL, PRICE, NEW_STATION, CORRECTION }

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
    /** The bottom of the search card and area row. */
    var topControlsBottomPx by mutableIntStateOf(0)
}

/** The map's visible centre after a pan or zoom, and the short side of the visible map in metres when known. */
internal data class MapMove(val center: SearchPoint, val shortSideMetres: Double = Double.NaN)
