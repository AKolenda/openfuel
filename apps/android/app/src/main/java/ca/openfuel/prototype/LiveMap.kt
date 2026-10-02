// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
internal val mapFiles = mapOf(
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

/**
 * Bundled map code shares one compositor for tiles and geographically anchored logos.
 * [padding] is the part of the map covered by controls; the map centres itself in the rest. [visible] is false while
 * the map is covered, so it stops drawing. A tapped chip calls [onSelect] with its station and every station whose chip
 * lies under it, front first.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun LiveMap(stations: List<Station>, grade: Grade, bestId: String?, center: SearchPoint, currentLocation: SearchPoint?, centerRequest: Int, brandLogos: Map<String, Bitmap>, onMove: (MapMove) -> Unit,
            onBaseMap: (String) -> Unit = {}, modifier: Modifier = Modifier, padding: () -> PaddingValues = { PaddingValues() }, visible: () -> Boolean = { true },
            onSelect: (Station, List<Station>) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val select by rememberUpdatedState(onSelect)
    val inset by rememberUpdatedState(padding)
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
                moved = { lat, lon, side -> post { if (FuelCore.validStationPoint(lat, lon)) moved(MapMove(SearchPoint(lat, lon, "Map area", SearchSource.MAP), side)) } },
                // Ids that went stale between the tap and this post are dropped; a stale front chip selects nothing.
                selected = { ids -> post { currentStations[ids.first()]?.let { front -> select(front, ids.mapNotNull(currentStations::get)) } } },
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
    // The padding moves the camera, so it is sent before the first setArea (this effect starts first) and again only
    // when a control's size changes, never as the station sheet moves. CSS px are dp.
    LaunchedEffect(ready) {
        if (ready) snapshotFlow { inset().let { it.calculateTopPadding().value to it.calculateBottomPadding().value } }
            .collect { (top, bottom) -> view?.evaluateJavascript("window.setPadding&&window.setPadding($top,$bottom);", null) }
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
    // While TalkBack explores by touch, the page's invisible station buttons take touches so a touched chip is read out.
    val accessibility = remember { context.getSystemService(AccessibilityManager::class.java) }
    var exploring by remember { mutableStateOf(accessibility?.isTouchExplorationEnabled == true) }
    DisposableEffect(accessibility) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { exploring = it }
        accessibility?.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility?.removeTouchExplorationStateChangeListener(listener) }
    }
    LaunchedEffect(ready, exploring) {
        if (ready) view?.evaluateJavascript("window.setTouchExploration&&window.setTouchExploration($exploring);", null)
    }
    // A GPS update moves only the location dot; it does not rebuild every station marker.
    LaunchedEffect(ready, currentLocation) {
        if (ready) view?.evaluateJavascript("window.setLocation(" +
            (currentLocation?.let { JSONObject().put("lat", it.latitude).put("lon", it.longitude) } ?: JSONObject.NULL) + ");", null)
    }
    val web = view
    if (web == null) Box(modifier.background(Color(MAP_BACKGROUND)))
    // An INVISIBLE WebView skips its drawing and Chromium pauses the page's frames; its scripts still run.
    else AndroidView(factory = { web }, modifier = modifier, update = { it.visibility = if (visible()) View.VISIBLE else View.INVISIBLE })
}

/**
 * The map page's calls. [moved] gets the visible centre and the short side of the visible map in metres, or NaN when
 * unknown. [selected] gets 1 to 12 station ids, the tapped one first.
 */
internal class MapBridge(private val ready: () -> Unit, private val moved: (Double, Double, Double) -> Unit, private val selected: (List<String>) -> Unit,
                         private val base: (String) -> Unit) {
    @JavascriptInterface fun ready() = ready.invoke()
    /** The Leaflet page, which does not report the size of its view. */
    @JavascriptInterface fun moved(latitude: Double, longitude: Double) = moved.invoke(latitude, longitude, Double.NaN)
    @JavascriptInterface fun movedView(latitude: Double, longitude: Double, shortSideMetres: Double) =
        moved.invoke(latitude, longitude, shortSideMetres.takeIf { it.isFinite() && it > 0 } ?: Double.NaN)
    /** The Leaflet page selects one station. */
    @JavascriptInterface fun selected(id: String) { if (id.length in 1..120) selected.invoke(listOf(id)) }
    /** A JSON array of the tapped station's id and those under it. Anything else is ignored. */
    @JavascriptInterface fun selectedStack(json: String) {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return
        if (array.length() !in 1..12) return
        val ids = (0 until array.length()).map { (array.opt(it) as? String)?.takeIf { id -> id.length in 1..120 } ?: return }
        selected.invoke(ids)
    }
    @JavascriptInterface fun base(name: String) { if (name == "openfreemap" || name == "openstreetmap") base.invoke(name) }
}
