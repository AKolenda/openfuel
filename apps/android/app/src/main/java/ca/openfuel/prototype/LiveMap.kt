// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections
import java.util.Locale

// The base map is OpenFreeMap, with OpenStreetMap raster tiles when WebGL or OpenFreeMap is unavailable.
private val tileHosts = listOf("tiles.openfreemap.org", "tile.openstreetmap.org")
/** Matches the map page's background, so nothing flashes before the WebView paints. */
internal const val MAP_BACKGROUND = 0xFFEDF1EA

// The bundled map page and its code are served from the APK as separate same-origin files, never from the
// network, so Chromium can compile the large scripts off the UI thread and code-cache them between launches.
// The page draws with MapLibre; Leaflet, base-map.js and station-map-leaflet.js load only without WebGL.
private const val MAP_HOST = "openfuel.ca"
private const val MAP_PATH = "/_native-map/"
private val mapFiles = mapOf(
    "station-map.html" to "text/html", "station-map.js" to "text/javascript", "map-style.js" to "text/javascript",
    "maplibre-gl.js" to "text/javascript", "maplibre-gl.css" to "text/css", "station-map-leaflet.js" to "text/javascript",
    "leaflet.js" to "text/javascript", "base-map.js" to "text/javascript", "leaflet.css" to "text/css")

/** Called on a WebView background thread. Paths outside the bundled map files return null. */
private fun mapFile(context: Context, path: String?): WebResourceResponse? {
    if (path == null || !path.startsWith(MAP_PATH)) return null
    val name = path.removePrefix(MAP_PATH)
    val type = mapFiles[name] ?: return null
    fun asset(file: String): InputStream = context.assets.open(file)
    val body = when (name) {
        // The page's CSP allows the same tile hosts that shouldInterceptRequest lets through, for images and,
        // since MapLibre fetches raster tiles as well as vector tiles, for connections.
        "station-map.html" -> asset(name).bufferedReader().use { it.readText() }
            .replace("MAP_TILE_ORIGIN", tileHosts.joinToString(" ") { "https://$it" }).byteInputStream()
        // The shared style JSON, wrapped as a script without being copied into a string.
        "map-style.js" -> SequenceInputStream(Collections.enumeration(listOf(
            "const mapStyle=".byteInputStream(), asset("openfuel-style.json"), ";".byteInputStream())))
        else -> asset(name)
    }
    return WebResourceResponse(type, "UTF-8", body)
}

/** Bundled map code shares one compositor for tiles and geographically anchored logos. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LiveMap(stations: List<Station>, grade: Grade, bestId: String?, center: SearchPoint, currentLocation: SearchPoint?, centerRequest: Int, brandLogos: Map<String, Bitmap>, onMove: (SearchPoint) -> Unit,
            onBaseMap: (String) -> Unit = {}, modifier: Modifier = Modifier, onSelect: (Station) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val select by rememberUpdatedState(onSelect)
    val moved by rememberUpdatedState(onMove)
    val baseMap by rememberUpdatedState(onBaseMap)
    val stationIndex = remember(stations) { stations.associateBy { it.id } }
    val currentStations by rememberUpdatedState(stationIndex)
    val start by rememberUpdatedState(center)
    var ready by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<WebView?>(null) }
    // The WebView is created only after the first frame has been drawn, so saved stations appear first.
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        view = WebView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
            tag = "openfuel-map-webview"
            setBackgroundColor(MAP_BACKGROUND.toInt())
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.setGeolocationEnabled(false) // Foreground permissions and GPS belong to the native app.
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString += " OpenFuel/${BuildConfig.VERSION_NAME} (+https://openfuel.ca)"
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val uri = request.url
                    if (uri.scheme == "https" && uri.host in tileHosts && (uri.port == -1 || uri.port == 443)) return null
                    if (uri.scheme == "https" && uri.host == MAP_HOST && uri.port == -1 && request.method == "GET")
                        mapFile(context, uri.path)?.let { return it }
                    return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))
                }
            }
            addJavascriptInterface(MapBridge(
                ready = { post { ready = true } },
                moved = { lat, lon -> post { if (FuelCore.validStationPoint(lat, lon)) moved(SearchPoint(lat, lon, "Map area", SearchSource.MAP)) } },
                selected = { id -> post { currentStations[id]?.let(select) } },
                base = { name -> post { baseMap(name) } }
            ), "OpenFuelMap")
            // The page opens on the search area, so its first tiles are that area's rather than Canada's.
            val area = start.takeIf { it.source != SearchSource.OVERVIEW }
                ?.let { String.format(Locale.ROOT, "#%.5f,%.5f", it.latitude, it.longitude) }.orEmpty()
            loadUrl("https://$MAP_HOST${MAP_PATH}station-map.html$area")
        }
    }
    DisposableEffect(view, lifecycle) {
        val web = view
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) web?.onResume()
            if (event == Lifecycle.Event.ON_PAUSE) web?.onPause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); web?.run { removeJavascriptInterface("OpenFuelMap"); stopLoading(); destroy() } }
    }
    // Search results never reset the user's camera or zoom. Only explicit recenter requests do.
    LaunchedEffect(ready, centerRequest) {
        if (ready) view?.evaluateJavascript("window.setArea(${center.latitude},${center.longitude},${center.source == SearchSource.OVERVIEW});", null)
    }
    var encodedLogos by remember { mutableStateOf(JSONObject()) }
    var encodedCache by remember { mutableStateOf<Map<String, Pair<Bitmap, String>>>(emptyMap()) }
    LaunchedEffect(brandLogos) { withContext(Dispatchers.Main.immediate) {
        val previous = encodedCache
        val next = withContext(Dispatchers.Default) {
            brandLogos.mapValues { (url, bitmap) ->
                previous[url]?.takeIf { it.first === bitmap } ?: run {
                    val output = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    bitmap to ("data:image/png;base64," + android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP))
                }
            }
        }
        encodedCache = next
        encodedLogos = JSONObject().apply { next.forEach { (url, value) -> put(url, value.second) } }
    } }
    LaunchedEffect(ready, stations, grade, bestId, encodedLogos) { withContext(Dispatchers.Main.immediate) {
        if (!ready) return@withContext
        // Serializing the station snapshot never blocks a list-scroll frame.
        val payload = withContext(Dispatchers.Default) {
            JSONObject().put("logos", encodedLogos).put("stations", JSONArray().apply {
                stations.forEach { station -> if (FuelCore.validStationPoint(station.latitude, station.longitude)) put(JSONObject()
                    .put("id", station.id).put("name", station.name).put("lat", station.latitude).put("lon", station.longitude)
                    .put("price", station.price(grade) ?: JSONObject.NULL).put("logo", station.brandLogoUrl ?: JSONObject.NULL).put("best", station.id == bestId)) }
            }).toString()
        }
        view?.evaluateJavascript("window.setStations($payload);", null)
    } }
    // A GPS update moves only the location dot; it does not rebuild every station marker.
    LaunchedEffect(ready, currentLocation) {
        if (ready) view?.evaluateJavascript("window.setLocation(" +
            (currentLocation?.let { JSONObject().put("lat", it.latitude).put("lon", it.longitude) } ?: JSONObject.NULL) + ");", null)
    }
    val web = view
    if (web == null) Box(modifier.background(Color(MAP_BACKGROUND)))
    else AndroidView(factory = { web }, modifier = modifier)
}

internal class MapBridge(private val ready: () -> Unit, private val moved: (Double, Double) -> Unit, private val selected: (String) -> Unit,
                         private val base: (String) -> Unit) {
    @JavascriptInterface fun ready() = ready.invoke()
    @JavascriptInterface fun moved(latitude: Double, longitude: Double) = moved.invoke(latitude, longitude)
    @JavascriptInterface fun selected(id: String) { if (id.length <= 120) selected.invoke(id) }
    @JavascriptInterface fun base(name: String) { if (name == "openfreemap" || name == "openstreetmap") base.invoke(name) }
}
