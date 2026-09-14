// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import java.net.URLEncoder
import java.util.Locale

enum class Grade { REGULAR, PREMIUM, DIESEL }
enum class SortMode { BEST, PRICE, NEAREST }
enum class MapProvider { GOOGLE, ASK }
enum class ProposalKind { NEW_STATION, CORRECTION, TEMPORARY_CLOSURE, PERMANENT_CLOSURE }

data class Station(
    val id: String, val name: String, val brand: String, val address: String,
    val distanceMetres: Int, val minutes: Int, val x: Float, val y: Float,
    val open: Boolean?, val prices: Map<Grade, Int>, val ages: Map<Grade, Int>,
    val memberDiscount: Int = 0,
    val latitude: Double? = null, val longitude: Double? = null,
    val priceSources: Map<Grade, String> = emptyMap(),
    val brandKey: String? = null,
    val brandLogoUrl: String? = null
) {
    fun price(grade: Grade, members: Boolean = false): Int? = prices[grade]?.minus(if (members) memberDiscount else 0)
    fun age(grade: Grade): Int = ages[grade] ?: Int.MAX_VALUE
}

data class Filters(val radiusMetres: Int = 10000, val fresh: Boolean = false, val open: Boolean = false, val members: Boolean = false)

data class StationProposal(
    val id: String, val kind: ProposalKind, val stationId: String?, val name: String,
    val latitude: Double?, val longitude: Double?, val note: String,
    val state: String = "pending_review"
)

object FuelCore {
    // Integer thousandths of CAD/litre avoid floating-point price rounding.
    fun priceText(value: Int): String = "${value / 10}.${value % 10}"
    fun parsePrice(input: String): Int? {
        val match = Regex("^(\\d{1,3})(?:\\.(\\d))?$").matchEntire(input.trim()) ?: return null
        val value = match.groupValues[1].toInt() * 10 + (match.groupValues[2].ifEmpty { "0" }).toInt()
        return value.takeIf { it in 500..3999 }
    }
    fun distanceText(metres: Int): String = String.format(Locale.CANADA, "%.1f km", metres / 1000.0)
    fun validStationPoint(lat: Double?, lon: Double?): Boolean =
        lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    fun visible(stations: List<Station>, grade: Grade, sort: SortMode, filters: Filters,
                query: String = "", savedOnly: Boolean = false, favorites: Set<String> = emptySet()): List<Station> {
        val q = query.trim().lowercase(Locale.ROOT)
        val selected = stations.filter { s ->
            s.distanceMetres <= filters.radiusMetres &&
                (!filters.fresh || s.age(grade) <= 60) && (!filters.open || s.open == true) &&
                (!savedOnly || s.id in favorites) &&
                (q.isEmpty() || "${s.name} ${s.address}".lowercase(Locale.ROOT).contains(q))
        }
        return when (sort) {
            SortMode.BEST -> selected.sortedWith(compareBy<Station> { it.age(grade) > 60 }.thenBy { it.price(grade, filters.members) ?: Int.MAX_VALUE }.thenBy { it.distanceMetres }.thenBy { it.id })
            SortMode.PRICE -> selected.sortedWith(compareBy<Station> { it.price(grade, filters.members) ?: Int.MAX_VALUE }.thenBy { it.id })
            SortMode.NEAREST -> selected.sortedWith(compareBy<Station> { it.distanceMetres }.thenBy { it.id })
        }
    }
    // Samples must never use their fictional address as a real destination.
    fun sampleMapUrl(station: Station): String = "https://www.google.com/maps/search/?api=1&query=" +
        URLEncoder.encode("${station.brand} Canada", "UTF-8")
    fun directionsUrl(station: Station): String {
        require(validStationPoint(station.latitude, station.longitude)) { "Station coordinates are unavailable." }
        return "https://www.google.com/maps/dir/?api=1&destination=${station.latitude},${station.longitude}&travelmode=driving"
    }
    fun updatePrice(station: Station, grade: Grade, value: Int): Station {
        require(value in 500..3999)
        return station.copy(prices = station.prices + (grade to value), ages = station.ages + (grade to 0), priceSources = station.priceSources + (grade to "Community · unverified"))
    }
    // No vote-count shortcut: publication belongs to a future authenticated moderator service.
    fun newProposal(id: String, kind: ProposalKind, station: Station?, name: String, lat: Double?, lon: Double?, note: String): StationProposal {
        require(name.trim().length in 2..80) { "Enter a station name (2–80 characters)." }
        require(note.length <= 500) { "Keep the note under 500 characters." }
        require(kind != ProposalKind.NEW_STATION || validStationPoint(lat, lon)) { "Enter valid station coordinates." }
        require(kind == ProposalKind.NEW_STATION || station != null) { "Select an existing station." }
        return StationProposal(id, kind, station?.id, name.trim(), lat, lon, note.trim())
    }
}

object SampleData { fun stations(): List<Station> = GeneratedSamples.stations() }

/** Sample illustration camera. x/y are SVG WORLD units, never latitude/longitude. */
data class MapCamera(val cx:Double,val cy:Double,val scale:Double) {
 companion object {
  fun fit(width:Double,height:Double,sheetHeight:Double,stations:List<Station>):MapCamera {
   if(stations.isEmpty())return MapCamera(600.0,500.0,1.0)
   val minX=stations.minOf{it.x}.toDouble()-36;val maxX=stations.maxOf{it.x}.toDouble()+36
   val minY=stations.minOf{it.y}.toDouble()-45;val maxY=stations.maxOf{it.y}.toDouble()+45
   val left=42.0;val right=width-43;val top=154.0;val bottom=maxOf(226.0,height-sheetHeight-34)
   val scale=maxOf(.27,minOf(.83,maxOf(150.0,right-left)/maxOf(380.0,maxX-minX),maxOf(150.0,bottom-top)/maxOf(400.0,maxY-minY)))
   return MapCamera((minX+maxX)/2+(width/2-(left+right)/2)/scale,(minY+maxY)/2+(height/2-(top+bottom)/2)/scale,scale)
  }
 }
}
