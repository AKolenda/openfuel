// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** How many chosen cities the area menu remembers. */
internal const val MAX_RECENT_AREAS = 4

/**
 * The cities chosen lately, newest first, kept on this device in the station repository's preferences under
 * "recent-areas". Only chosen cities are kept, at the saved areas' coarse 0.01° precision; device fixes never are.
 */
internal class RecentAreas(context: Context) {
    private val prefs = context.getSharedPreferences("openfuel-live-v2", Context.MODE_PRIVATE)

    fun load(): List<SearchPoint> = decodeRecentAreas(prefs.getString(KEY, null))

    /** Puts [area] first, if it is a chosen city, saves the list and returns it. */
    fun add(area: SearchPoint): List<SearchPoint> {
        val recent = withRecentArea(load(), area)
        prefs.edit().putString(KEY, encodeRecentAreas(recent)).apply()
        return recent
    }

    private companion object { const val KEY = "recent-areas" }
}

/** [recent] with [area] first, once per 0.01° cell and at most [MAX_RECENT_AREAS] long. Other sources than a chosen city are not added. */
internal fun withRecentArea(recent: List<SearchPoint>, area: SearchPoint): List<SearchPoint> {
    if (area.source != SearchSource.CITY) return recent
    val saved = area.forStorage().copy(label = area.label.take(100))
    return (listOf(saved) + recent.filterNot { it.sameCell(saved) }).take(MAX_RECENT_AREAS)
}

internal fun encodeRecentAreas(areas: List<SearchPoint>): String = JSONArray().apply {
    areas.forEach { put(JSONObject().put("latitude", it.latitude).put("longitude", it.longitude).put("label", it.label)) }
}.toString()

/** The saved list, or none when it is missing or unreadable. */
internal fun decodeRecentAreas(text: String?): List<SearchPoint> = runCatching {
    val array = JSONArray(text ?: return emptyList())
    buildList<SearchPoint> {
        for (index in 0 until array.length()) {
            val point = runCatching {
                val area = array.getJSONObject(index)
                SearchPoint(area.getDouble("latitude"), area.getDouble("longitude"), area.getString("label").take(100), SearchSource.CITY)
                    .takeIf { FuelCore.validStationPoint(it.latitude, it.longitude) && it.label.isNotBlank() }?.forStorage()
            }.getOrNull() ?: continue
            if (none { it.sameCell(point) }) add(point)
            if (size == MAX_RECENT_AREAS) break
        }
    }
}.getOrDefault(emptyList())
