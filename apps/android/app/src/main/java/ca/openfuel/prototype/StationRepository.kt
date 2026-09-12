// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale
import java.util.UUID

class PrototypeApiException(message: String) : IOException(message)
data class SearchPoint(val latitude: Double, val longitude: Double, val label: String) {
    companion object {
        val CANADA_OVERVIEW = SearchPoint(55.0, -104.0, "Choose an area")
        val EDMONTON = SearchPoint(53.5461, -113.4938, "Edmonton · chosen city")
    }
}

/** Only real station snapshots enter this cache. A random installation ID is not authentication. */
class StationRepository(context: Context) {
    private val prefs = context.getSharedPreferences("openfuel-live-v2", Context.MODE_PRIVATE)
    private val base = BuildConfig.API_BASE_URL.trimEnd('/')
    val configured = !URL(base).host.endsWith(".invalid")
    private val clientId = prefs.getString("client-id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("client-id", it).apply()
    }
    data class Snapshot(val stations: List<Station>, val cached: Boolean, val point: SearchPoint = SearchPoint.CANADA_OVERVIEW)

    fun initial(): Snapshot = runCatching {
        val body = prefs.getString("stations", null) ?: return Snapshot(emptyList(), false)
        val elapsed = ((System.currentTimeMillis() - prefs.getLong("saved-at", System.currentTimeMillis())) / 60_000)
            .coerceIn(0, 1_000_000).toInt()
        val point = SearchPoint(prefs.getString("latitude", null)!!.toDouble(), prefs.getString("longitude", null)!!.toDouble(), "Saved search area")
        Snapshot(parseStations(JSONObject(body)).map { station ->
            station.copy(ages = station.ages.mapValues { (_, age) -> (age.toLong() + elapsed).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() })
        }, true, point)
    }.getOrElse { Snapshot(emptyList(), false) }

    suspend fun searchCities(query: String): List<SearchPoint> = withContext(Dispatchers.IO) {
        val body = request("/geocode?q=" + java.net.URLEncoder.encode(query.take(100), "UTF-8"))
        val results = body.getJSONArray("results")
        (0 until minOf(results.length(), 12)).map { i ->
            val place = results.getJSONObject(i)
            SearchPoint(place.getDouble("latitude"), place.getDouble("longitude"), place.getString("name") + " · chosen area")
        }.filter { FuelCore.validStationPoint(it.latitude, it.longitude) }
    }

    suspend fun refresh(point: SearchPoint = initial().point): List<Station> = withContext(Dispatchers.IO) {
        require(FuelCore.validStationPoint(point.latitude, point.longitude))
        val latitude = String.format(Locale.ROOT, "%.3f", point.latitude)
        val longitude = String.format(Locale.ROOT, "%.3f", point.longitude)
        val body = request("/stations?lat=$latitude&lon=$longitude&radius=10000")
        val stations = parseStations(body)
        coroutineContext.ensureActive()
        cache(stations, point)
        stations
    }

    suspend fun report(station: Station, grade: Grade, price: Int): List<Station> = withContext(Dispatchers.IO) {
        require(price in 500..3999 && FuelCore.validStationPoint(station.latitude, station.longitude))
        val requestKey = "${station.id}:${grade.name}:$price"
        val requestId = if (prefs.getString("pending-report-key", null) == requestKey) prefs.getString("pending-report-id", null) else null
        val persistentRequestId = requestId ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("pending-report-key", requestKey).putString("pending-report-id", it).commit()
        }
        val payload = JSONObject().put("station_id", station.id)
            .put("fuel_type", grade.name.lowercase(Locale.ROOT)).put("price_milli", price).put("client_id", clientId)
            .put("request_id", persistentRequestId)
        val body = request("/reports", payload)
        val report = body.optJSONObject("report")
        if (!body.optBoolean("ok") || body.optBoolean("is_demo", true) || report == null || report.optString("id") != persistentRequestId || report.optString("station_id") != station.id ||
            report.optString("fuel_type") != grade.name.lowercase(Locale.ROOT) || report.optInt("price_milli") != price) {
            throw IOException("The server did not confirm this report. Refresh before trying again.")
        }
        val observedAt = runCatching { Instant.parse(report.getString("observed_at")).toEpochMilli() }.getOrNull()
            ?: throw IOException("The server did not confirm when this price was reported.")
        val age = ((System.currentTimeMillis() - observedAt) / 60_000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val snapshot = initial()
        val updated = snapshot.stations.map {
            if (it.id == station.id) FuelCore.updatePrice(it, grade, price).copy(ages = it.ages + (grade to age)) else it
        }
        cache(updated, snapshot.point)
        prefs.edit().remove("pending-report-key").remove("pending-report-id").apply()
        updated
    }

    private fun request(path: String, payload: JSONObject? = null): JSONObject {
        if (!configured) throw IOException("This build has no server configured.")
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "OpenFuel-Android/${BuildConfig.VERSION_NAME} (+https://openfuel.ca)")
            if (payload != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { reader ->
                    val result = StringBuilder(); val buffer = CharArray(8192)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count == -1) break
                        if (result.length + count > 2_000_000) throw IOException("Unexpectedly large server response.")
                        result.append(buffer, 0, count)
                    }; result.toString()
                }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("message") }.getOrDefault("")
                throw PrototypeApiException(detail.takeIf { it.isNotBlank() } ?: "Station service unavailable ($status). Please try again.")
            }
            return JSONObject(text)
        } finally { connection.disconnect() }
    }

    private fun cache(stations: List<Station>, point: SearchPoint) {
        val array = JSONArray()
        stations.forEach { s ->
            val prices = JSONObject(); val ages = JSONObject(); val sources = JSONObject()
            s.prices.forEach { (grade, price) -> prices.put(grade.name.lowercase(Locale.ROOT), price) }
            s.ages.forEach { (grade, age) -> ages.put(grade.name.lowercase(Locale.ROOT), age) }
            s.priceSources.forEach { (grade, source) -> sources.put(grade.name.lowercase(Locale.ROOT), source) }
            array.put(JSONObject().put("id", s.id).put("name", s.name).put("brand", s.brand).put("address", s.address)
                .put("distanceMetres", s.distanceMetres).put("latitude", s.latitude).put("longitude", s.longitude)
                .put("open", s.open ?: JSONObject.NULL).put("prices", prices).put("ages", ages).put("priceSources", sources).put("synthetic", false))
        }
        prefs.edit().putString("stations", JSONObject().put("is_demo", false).put("stations", array).toString())
            .putString("latitude", point.latitude.toString()).putString("longitude", point.longitude.toString())
            .putLong("saved-at", System.currentTimeMillis()).apply()
    }

    internal fun parseStations(body: JSONObject): List<Station> {
        require(body.has("is_demo") && !body.getBoolean("is_demo")) { "Sample stations are not displayed in the live app." }
        val array = body.getJSONArray("stations")
        require(array.length() in 0..1000) { "Unexpected station response." }
        return (0 until array.length()).map { index ->
            val s = array.getJSONObject(index)
            require(!s.optBoolean("synthetic", true)) { "Sample station rejected." }
            val lat = s.getDouble("latitude"); val lon = s.getDouble("longitude")
            require(FuelCore.validStationPoint(lat, lon)) { "Invalid station coordinates." }
            val prices = s.getJSONObject("prices"); val ages = s.optJSONObject("ages")
            val gradePrices = Grade.entries.mapNotNull { grade ->
                val key = grade.name.lowercase(Locale.ROOT)
                if (!prices.has(key) || prices.isNull(key)) null else {
                    val price = prices.getInt(key); require(price in 500..3999); grade to price
                }
            }.toMap()
            val gradeAges = gradePrices.keys.associateWith { grade ->
                ages?.optInt(grade.name.lowercase(Locale.ROOT), Int.MAX_VALUE)?.coerceAtLeast(0) ?: Int.MAX_VALUE
            }
            val sources = gradePrices.keys.associateWith { "Community · unverified" }
            Station(s.getString("id"), s.getString("name"), s.optString("brand"), s.optString("address", "Address not listed"),
                s.getInt("distanceMetres").coerceAtLeast(0), 0, 0f, 0f,
                if (s.isNull("open") || !s.has("open")) null else s.getBoolean("open"), gradePrices, gradeAges,
                latitude = lat, longitude = lon, priceSources = sources)
        }
    }
}
