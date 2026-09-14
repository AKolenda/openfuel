// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

/** Bundled map code shares one compositor for tiles and geographically anchored logos. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LiveMap(stations: List<Station>, grade: Grade, bestId: String?, center: SearchPoint, currentLocation: SearchPoint?, centerRequest: Int, brandLogos: Map<String, Bitmap>, onMove: (SearchPoint) -> Unit,
            modifier: Modifier = Modifier, onSelect: (Station) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val select by rememberUpdatedState(onSelect)
    val moved by rememberUpdatedState(onMove)
    val stationIndex = remember(stations) { stations.associateBy { it.id } }
    val currentStations by rememberUpdatedState(stationIndex)
    var ready by remember { mutableStateOf(false) }
    val view = remember {
        WebView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
            tag = "openfuel-map-webview"
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
                    if (uri.scheme == "https" && uri.host == "tile.openstreetmap.org" && (uri.port == -1 || uri.port == 443)) return null
                    return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))
                }
            }
            addJavascriptInterface(MapBridge(
                ready = { post { ready = true } },
                moved = { lat, lon -> post { if (FuelCore.validStationPoint(lat, lon)) moved(SearchPoint(lat, lon, "Map area", SearchSource.MAP)) } },
                selected = { id -> post { currentStations[id]?.let(select) } }
            ), "OpenFuelMap")
            val html = context.assets.open("station-map.html").bufferedReader().use { it.readText() }
                .replace("/* LEAFLET_CSS */", context.assets.open("leaflet.css").bufferedReader().use { it.readText() })
                .replace("/* LEAFLET_JS */", context.assets.open("leaflet.js").bufferedReader().use { it.readText() }.replace("</script", "<\\/script"))
            loadDataWithBaseURL("https://openfuel.ca/_native-map/", html, "text/html", null, "https://openfuel.ca/_native-map/")
        }
    }
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) view.onResume()
            if (event == Lifecycle.Event.ON_PAUSE) view.onPause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); view.removeJavascriptInterface("OpenFuelMap"); view.stopLoading(); view.destroy() }
    }
    // Search results never reset the user's camera or zoom. Only explicit recenter requests do.
    LaunchedEffect(ready, centerRequest) {
        if (ready) view.evaluateJavascript("window.setArea(${center.latitude},${center.longitude},${center.source == SearchSource.OVERVIEW});", null)
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
        view.evaluateJavascript("window.setStations($payload);", null)
    } }
    // A GPS update moves only the location dot; it does not rebuild every station marker.
    LaunchedEffect(ready, currentLocation) {
        if (ready) view.evaluateJavascript("window.setLocation(" +
            (currentLocation?.let { JSONObject().put("lat", it.latitude).put("lon", it.longitude) } ?: JSONObject.NULL) + ");", null)
    }
    AndroidView(factory = { view }, modifier = modifier)
}

internal class MapBridge(private val ready: () -> Unit, private val moved: (Double, Double) -> Unit, private val selected: (String) -> Unit) {
    @JavascriptInterface fun ready() = ready.invoke()
    @JavascriptInterface fun moved(latitude: Double, longitude: Double) = moved.invoke(latitude, longitude)
    @JavascriptInterface fun selected(id: String) { if (id.length <= 120) selected.invoke(id) }
}
