// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.content.Context
import android.net.http.HttpResponseCache
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.math.round

class PrototypeApiException(message: String) : IOException(message)
/** OpenFuel's database reached its free daily allowance; live answers return at about [resetsAt]. */
class ServiceLimitException(val resetsAt: Instant, message: String) : IOException(message)
enum class SearchSource { OVERVIEW, CITY, MAP, DEVICE }
data class SearchPoint(val latitude: Double, val longitude: Double, val label: String, val source: SearchSource = SearchSource.CITY) {
    fun forStorage() = copy(latitude = round(latitude * 100) / 100, longitude = round(longitude * 100) / 100)
    /** Both points round to the same saved 0.01° area (about 1 km). */
    fun sameCell(other: SearchPoint): Boolean {
        val a = forStorage(); val b = other.forStorage()
        return a.latitude == b.latitude && a.longitude == b.longitude
    }
    companion object {
        val CANADA_OVERVIEW = SearchPoint(55.0, -104.0, "Choose an area", SearchSource.OVERVIEW)
        val EDMONTON = SearchPoint(53.5461, -113.4938, "Edmonton · chosen city")
    }
}

/** Only real station snapshots enter this cache. A random installation ID is not authentication. */
class StationRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("openfuel-live-v2", Context.MODE_PRIVATE)
    // The last loaded stations. The saved area and "saved-at" stay in the preferences.
    private val snapshotFile = AtomicFile(File(context.noBackupFilesDir, "stations-v3.json"))
    private val base = BuildConfig.API_BASE_URL.trimEnd('/')
    val configured = !URL(base).host.endsWith(".invalid")
    // Read when a report is sent, so creating a repository never waits for the preferences file.
    private val clientId by lazy {
        prefs.getString("client-id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("client-id", it).apply() }
    }
    data class Snapshot(val stations: List<Station>, val cached: Boolean, val point: SearchPoint = SearchPoint.CANADA_OVERVIEW)
    /** What the first frame needs: the saved snapshot, and the saved area's fresh stations, already requested. */
    class Startup(val snapshot: Snapshot, val fresh: Deferred<List<Station>>?)

    /** The saved search area, read without parsing its stations. */
    fun savedArea(): SearchPoint? = runCatching {
        val storedLat = prefs.getString("latitude", null)?.toDoubleOrNull() ?: return null
        val storedLon = prefs.getString("longitude", null)?.toDoubleOrNull() ?: return null
        val source = runCatching { SearchSource.valueOf(prefs.getString("area-source", null) ?: "MAP") }.getOrDefault(SearchSource.MAP)
        val savedLabel = prefs.getString("area-label", null)?.take(100) ?: "Saved search area"
        SearchPoint(storedLat, storedLon, if (source == SearchSource.DEVICE) "Last location area" else savedLabel, source).forStorage()
            .takeIf { FuelCore.validStationPoint(it.latitude, it.longitude) }
    }.getOrNull()

    fun initial(): Snapshot {
        val point = savedArea() ?: return Snapshot(emptyList(), false)
        return runCatching {
            if (!prefs.contains("snapshot-latitude") && prefs.contains("stations")) prefs.edit()
                .putString("snapshot-latitude", point.latitude.toString()).putString("snapshot-longitude", point.longitude.toString()).apply()
            // Migrate old snapshots that retained an unnecessarily precise search coordinate.
            rememberArea(point)
            // The snapshot file names its own area and time. Earlier versions kept the snapshot in preferences.
            val file = synchronized(snapshotLock) { runCatching { JSONObject(String(snapshotFile.readFully(), Charsets.UTF_8)) }.getOrNull() }
            val body = file ?: prefs.getString("stations", null)?.let { JSONObject(it) } ?: return Snapshot(emptyList(), false, point)
            val cachedLat = file?.getDouble("latitude") ?: prefs.getString("snapshot-latitude", null)?.toDoubleOrNull() ?: point.latitude
            val cachedLon = file?.getDouble("longitude") ?: prefs.getString("snapshot-longitude", null)?.toDoubleOrNull() ?: point.longitude
            if (round(cachedLat * 100) / 100 != point.latitude || round(cachedLon * 100) / 100 != point.longitude) return Snapshot(emptyList(), false, point)
            val savedAt = file?.getLong("savedAt") ?: prefs.getLong("saved-at", System.currentTimeMillis())
            val elapsed = ((System.currentTimeMillis() - savedAt) / 60_000).coerceIn(0, 1_000_000).toInt()
            val cached = parseStations(body).map { station ->
                station.copy(ages = station.ages.mapValues { (_, age) -> (age.toLong() + elapsed).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() })
            }
            // Move a snapshot from the preferences to the file, including distances that otherwise reveal a finer origin.
            if (file == null) runCatching { cache(cached, point, rememberSearchArea = false) }
            Snapshot(withOwnReports(cached.map { it.copy(distanceMetres = approximateDistanceMetres(point, it.latitude!!, it.longitude!!)) }), true, point)
        }.getOrElse { Snapshot(emptyList(), false, point) }
    }

    fun rememberArea(point: SearchPoint) {
        val saved = point.forStorage()
        prefs.edit().putString("latitude", saved.latitude.toString()).putString("longitude", saved.longitude.toString())
            .putString("area-label", if (saved.source == SearchSource.DEVICE) "Last location area" else saved.label)
            .putString("area-source", saved.source.name).apply()
    }

    suspend fun searchCities(query: String): List<SearchPoint> = withContext(Dispatchers.IO) {
        val body = request("/geocode?q=" + java.net.URLEncoder.encode(query.take(100), "UTF-8"))
        val results = body.getJSONArray("results")
        (0 until minOf(results.length(), 12)).map { i ->
            val place = results.getJSONObject(i)
            SearchPoint(place.getDouble("latitude"), place.getDouble("longitude"), place.getString("name") + " · chosen area")
        }.filter { FuelCore.validStationPoint(it.latitude, it.longitude) }
    }

    /**
     * Starts the /stations request for [point] without waiting for it, or joins the one already running
     * for the same 0.01° cell. [fresh] skips the HTTP cache, for explicit refreshes.
     */
    fun prefetch(point: SearchPoint, fresh: Boolean = false): Deferred<List<Station>> {
        require(FuelCore.validStationPoint(point.latitude, point.longitude))
        val latitude = String.format(Locale.ROOT, "%.3f", point.latitude)
        val longitude = String.format(Locale.ROOT, "%.3f", point.longitude)
        val path = "/stations?lat=$latitude&lon=$longitude&radius=10000"
        val shared = stationRequests.start(point.forStorage().let { it.latitude to it.longitude }) {
            // Shortly after this device's own report, copies the HTTP cache kept from before it are skipped.
            val reported = System.currentTimeMillis() - prefs.getLong("reported-at", 0) in 0..REPORT_BYPASS_MS
            path to parseStations(request(path, noCache = fresh || reported))
        }
        return background.async {
            val (asked, stations) = shared.await()
            // An answer shared with another point in the cell is re-measured from this one. It may
            // predate this device's latest reports, so they are shown over it.
            withOwnReports(if (asked == path) stations else stations.measuredFrom(point))
        }
    }

    suspend fun refresh(point: SearchPoint = initial().point, saveSnapshot: Boolean = true, fresh: Boolean = false): List<Station> {
        val stations = prefetch(point, fresh).await()
        if (saveSnapshot) withContext(cacheDispatcher) { cache(stations, point) }
        return stations
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
        val confirmedAt = System.currentTimeMillis()
        // Kept for OWN_REPORT_MS, so that answers from before it do not hide this report meanwhile.
        synchronized(ownReportsLock) {
            saveOwnReports(ownReports(confirmedAt).filterNot { it.stationId == station.id && it.grade == grade } +
                OwnReport(station.id, grade, price, observedAt, confirmedAt))
        }
        val snapshot = withContext(cacheDispatcher) {
            initial().also { cache(it.stations, it.point, rememberSearchArea = false) }
        }
        prefs.edit().remove("pending-report-key").remove("pending-report-id").putLong("reported-at", confirmedAt).apply()
        snapshot.stations
    }

    /** Shows this device's reports from the last OWN_REPORT_MS over [stations] (see [FuelCore.withOwnReports]). */
    private fun withOwnReports(stations: List<Station>): List<Station> {
        val now = System.currentTimeMillis()
        return FuelCore.withOwnReports(stations, ownReports(now), now)
    }

    /** This device's confirmed reports still within OWN_REPORT_MS. Older ones are removed from storage. */
    private fun ownReports(now: Long): List<OwnReport> = synchronized(ownReportsLock) {
        val text = prefs.getString("own-reports", null) ?: return emptyList()
        val saved = runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { index ->
                val r = array.getJSONObject(index)
                val price = r.getInt("price_milli"); require(price in 500..3999)
                OwnReport(r.getString("station_id"), Grade.valueOf(r.getString("grade")), price, r.getLong("observed_at"), r.getLong("confirmed_at"))
            }
        }.getOrNull()
        val current = saved.orEmpty().filter { it.current(now) }
        if (saved == null || current.size != saved.size) saveOwnReports(current)
        current
    }

    private fun saveOwnReports(reports: List<OwnReport>) {
        if (reports.isEmpty()) { prefs.edit().remove("own-reports").apply(); return }
        val array = JSONArray()
        reports.forEach { r ->
            array.put(JSONObject().put("station_id", r.stationId).put("grade", r.grade.name).put("price_milli", r.price)
                .put("observed_at", r.observedAt).put("confirmed_at", r.confirmedAt))
        }
        prefs.edit().putString("own-reports", array.toString()).apply()
    }

    private fun request(path: String, payload: JSONObject? = null, noCache: Boolean = false): JSONObject {
        if (!configured) throw IOException("This build has no server configured.")
        installHttpCache(appContext)
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "OpenFuel-Android/${BuildConfig.VERSION_NAME} (+https://openfuel.ca)")
            if (noCache) connection.setRequestProperty("Cache-Control", "no-cache")
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
                serviceLimit(status, text)?.let { throw it }
                val detail = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
                throw PrototypeApiException(detail.takeIf { it.isNotBlank() } ?: "Station service unavailable ($status). Please try again.")
            }
            return JSONObject(text)
        } finally { connection.disconnect() }
    }

    /** Saves [stations] as the snapshot of [point]'s area. It writes a file: call it off the UI thread, such as on [cacheDispatcher]. */
    internal fun cache(stations: List<Station>, point: SearchPoint, rememberSearchArea: Boolean = true) {
        val storedPoint = point.forStorage()
        val array = JSONArray()
        stations.forEach { s ->
            val prices = JSONObject(); val ages = JSONObject(); val sources = JSONObject(); val observed = JSONObject()
            s.prices.forEach { (grade, price) -> prices.put(grade.name.lowercase(Locale.ROOT), price) }
            s.ages.forEach { (grade, age) -> ages.put(grade.name.lowercase(Locale.ROOT), age) }
            s.priceSources.forEach { (grade, source) -> sources.put(grade.name.lowercase(Locale.ROOT), source) }
            s.observedAt.forEach { (grade, at) -> observed.put(grade.name.lowercase(Locale.ROOT), Instant.ofEpochMilli(at).toString()) }
            array.put(JSONObject().put("id", s.id).put("name", s.name).put("brand", s.brand).put("address", s.address)
                .put("distanceMetres", approximateDistanceMetres(storedPoint, s.latitude!!, s.longitude!!)).put("latitude", s.latitude).put("longitude", s.longitude)
                .put("brandKey", s.brandKey ?: JSONObject.NULL).put("brandLogoUrl", s.brandLogoUrl ?: JSONObject.NULL)
                .put("open", s.open ?: JSONObject.NULL).put("prices", prices).put("ages", ages).put("observedAt", observed).put("priceSources", sources).put("synthetic", false))
        }
        val savedAt = System.currentTimeMillis()
        val body = JSONObject().put("is_demo", false).put("latitude", storedPoint.latitude).put("longitude", storedPoint.longitude)
            .put("savedAt", savedAt).put("stations", array).toString().toByteArray(Charsets.UTF_8)
        synchronized(snapshotLock) {
            val output = snapshotFile.startWrite()
            try { output.write(body); snapshotFile.finishWrite(output) }
            catch (e: Exception) { snapshotFile.failWrite(output); throw e }
            if (rememberSearchArea) rememberArea(storedPoint)
            // Earlier versions kept the snapshot in the preferences, which are rewritten whole on every change.
            prefs.edit().remove("stations").remove("snapshot-latitude").remove("snapshot-longitude").remove("coarse-snapshot-v3")
                .putLong("saved-at", savedAt).apply()
        }
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
            val prices = s.getJSONObject("prices"); val ages = s.optJSONObject("ages"); val observed = s.optJSONObject("observedAt")
            val gradePrices = Grade.entries.mapNotNull { grade ->
                val key = grade.name.lowercase(Locale.ROOT)
                if (!prices.has(key) || prices.isNull(key)) null else {
                    val price = prices.getInt(key); require(price in 500..3999); grade to price
                }
            }.toMap()
            val gradeAges = gradePrices.keys.associateWith { grade ->
                ages?.optInt(grade.name.lowercase(Locale.ROOT), Int.MAX_VALUE)?.coerceAtLeast(0) ?: Int.MAX_VALUE
            }
            // Report times let this device's own recent reports be compared with the listed prices.
            val gradeObserved = gradePrices.keys.mapNotNull { grade ->
                observed?.optString(grade.name.lowercase(Locale.ROOT))?.let { runCatching { grade to Instant.parse(it).toEpochMilli() }.getOrNull() }
            }.toMap()
            val sources = gradePrices.keys.associateWith { "Community · unverified" }
            Station(s.getString("id"), s.getString("name"), s.optString("brand"), s.optString("address", "Address not listed"),
                s.getInt("distanceMetres").coerceAtLeast(0), 0, 0f, 0f,
                if (s.isNull("open") || !s.has("open")) null else s.getBoolean("open"), gradePrices, gradeAges,
                latitude = lat, longitude = lon, priceSources = sources,
                brandKey = s.optString("brandKey").takeIf { !s.isNull("brandKey") && it.matches(Regex("[a-z0-9-]{1,64}")) },
                brandLogoUrl = s.optString("brandLogoUrl").takeIf { it.startsWith("https://") && it.length <= 1024 },
                observedAt = gradeObserved)
        }
    }

    companion object {
        private const val HTTP_CACHE_BYTES = 4L * 1024 * 1024
        private const val REPORT_BYPASS_MS = 60_000L
        private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val stationRequests = SharedLoads<Pair<Double, Double>, Pair<String, List<Station>>>(background)
        private val ownReportsLock = Any()
        private val snapshotLock = Any()
        /** Runs snapshot writes one at a time, so an earlier load's snapshot never replaces a later one's. */
        @OptIn(ExperimentalCoroutinesApi::class)
        val cacheDispatcher = Dispatchers.IO.limitedParallelism(1)

        /** Lets HttpURLConnection honour the API's Cache-Control: 15 s for stations, a day for city searches. */
        @Synchronized fun installHttpCache(context: Context) {
            if (HttpResponseCache.getInstalled() == null)
                runCatching { HttpResponseCache.install(File(context.cacheDir, "api-http"), HTTP_CACHE_BYTES) }
        }

        fun flushHttpCache() { background.launch { HttpResponseCache.getInstalled()?.flush() } }

        /**
         * Runs off the UI thread as the activity starts: installs the HTTP cache, requests the saved
         * area's fresh stations, then parses the saved snapshot while that request is under way.
         */
        fun startup(context: Context): StateFlow<Startup?> {
            val state = MutableStateFlow<Startup?>(null)
            background.launch {
                state.value = runCatching {
                    installHttpCache(context)
                    val repository = StationRepository(context)
                    val fresh = repository.savedArea()?.let { repository.prefetch(it) }
                    Startup(repository.initial(), fresh)
                }.getOrElse { Startup(Snapshot(emptyList(), false), null) }
            }
            return state
        }
    }
}

internal fun approximateDistanceMetres(point: SearchPoint, latitude: Double, longitude: Double): Int =
    point.forStorage().let { distanceMetres(it.latitude, it.longitude, latitude, longitude) }

/** Distances from [point] as the API measures them, from the request's three-decimal coordinates. */
internal fun List<Station>.measuredFrom(point: SearchPoint): List<Station> {
    val latitude = round(point.latitude * 1000) / 1000
    val longitude = round(point.longitude * 1000) / 1000
    return map { it.copy(distanceMetres = distanceMetres(latitude, longitude, it.latitude!!, it.longitude!!)) }
}

internal fun distanceMetres(fromLatitude: Double, fromLongitude: Double, latitude: Double, longitude: Double): Int {
    val dLat = Math.toRadians(latitude - fromLatitude)
    val dLon = Math.toRadians(longitude - fromLongitude)
    val a = kotlin.math.sin(dLat / 2).let { it * it } + kotlin.math.cos(Math.toRadians(fromLatitude)) *
        kotlin.math.cos(Math.toRadians(latitude)) * kotlin.math.sin(dLon / 2).let { it * it }
    return (12_742_000 * kotlin.math.atan2(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)), kotlin.math.sqrt((1 - a).coerceIn(0.0, 1.0)))).toInt()
}

/**
 * Recognises the daily-limit answers in an error [body]: the Worker's JSON `spending_cap` error (HTTP 503),
 * and Cloudflare's error 1027 once its daily request limit is reached. Cloudflare sends 1027 as JSON when
 * asked for JSON, as this app does, and otherwise as a 429 page. Other failures, including Cloudflare's
 * other 429s such as 1015, give null.
 */
internal fun serviceLimit(status: Int, body: String, now: Instant = Instant.now()): ServiceLimitException? {
    val json = runCatching { JSONObject(body) }.getOrNull()
    val limited = if (json == null) status == 429 else json.optString("error") == "spending_cap" ||
        json.optInt("error_code") == 1027 || json.optString("error_name") == "workers_daily_limit"
    if (!limited) return null
    // Both daily limits reset at midnight UTC.
    val midnight = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
    val reset = json?.optString("resets_at")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: midnight
    return ServiceLimitException(reset, json?.optString("message")?.takeIf { it.isNotBlank() } ?: "OpenFuel's database reached its free daily limit.")
}

/** While a load for a key is running, later callers for that key share it instead of starting another. */
internal class SharedLoads<K, V>(private val scope: CoroutineScope) {
    private val running = HashMap<K, Deferred<V>>()
    fun start(key: K, load: suspend () -> V): Deferred<V> = synchronized(running) {
        running[key] ?: scope.async(start = CoroutineStart.LAZY) { load() }.also { task ->
            running[key] = task
            task.invokeOnCompletion { synchronized(running) { if (running[key] === task) running.remove(key) } }
            task.start()
        }
    }
}
