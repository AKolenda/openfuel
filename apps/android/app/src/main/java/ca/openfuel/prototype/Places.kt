// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import java.util.Locale
import kotlin.math.max

/** The cities offered before any city was chosen. Their labels are the ones saved areas have always had. */
internal val PRESET_CITIES = listOf(SearchPoint.EDMONTON, SearchPoint(51.0447, -114.0719, "Calgary · chosen city"),
    SearchPoint(49.2827, -123.1207, "Vancouver · chosen city"), SearchPoint(43.6532, -79.3832, "Toronto · chosen city"))

/** Province and territory codes by their English and French names, in lower case. */
private val provinceCodes = mapOf(
    "alberta" to "AB", "british columbia" to "BC", "colombie-britannique" to "BC", "manitoba" to "MB",
    "new brunswick" to "NB", "nouveau-brunswick" to "NB", "newfoundland and labrador" to "NL", "newfoundland" to "NL",
    "terre-neuve-et-labrador" to "NL", "nova scotia" to "NS", "nouvelle-écosse" to "NS", "ontario" to "ON",
    "prince edward island" to "PE", "île-du-prince-édouard" to "PE", "quebec" to "QC", "québec" to "QC",
    "saskatchewan" to "SK", "northwest territories" to "NT", "territoires du nord-ouest" to "NT",
    "nunavut" to "NU", "yukon" to "YT")

/**
 * A short name for a place from the city search: "Lethbridge, Alberta, Canada" becomes "Lethbridge, AB".
 * "Canada" is dropped and a province or territory becomes its code; any other parts after the first are dropped.
 */
internal fun shortPlaceName(name: String): String {
    val parts = name.split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("Canada", ignoreCase = true) }
    val place = parts.firstOrNull() ?: return name.trim()
    val province = parts.drop(1).firstNotNullOfOrNull { provinceCodes[it.lowercase(Locale.ROOT)] }
    return if (province == null) place else "$place, $province"
}

/** The name an area goes by on screen, without the " · chosen city" note older labels carry. */
internal fun SearchPoint.shortLabel(): String = label.substringBefore(" ·")

/**
 * The map was moved far enough from the loaded area to offer a search there: more than a quarter of the
 * visible map's short side, and at least 300 m. When the size of the visible map is unknown, 750 m.
 * The distance is measured from the point the map was centred on, not from its rounded saved cell, so
 * zooming in on the loaded area does not count as a move.
 */
internal fun mapMovedAway(loaded: SearchPoint, move: MapMove?): Boolean {
    if (move == null) return false
    val threshold = if (move.shortSideMetres.isNaN()) 750.0 else max(300.0, move.shortSideMetres / 4)
    return distanceMetres(loaded.latitude, loaded.longitude, move.center.latitude, move.center.longitude) > threshold
}
