// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val OSM_STYLE = """{
 "version":8,
 "sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"minzoom":0,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},
 "layers":[{"id":"osm","type":"raster","source":"osm"}]
}"""

/** Native OpenGL MapLibre map. Cached on-demand tiles only; prefetch and offline downloads disabled. */
@Suppress("DEPRECATION")
@Composable
fun LiveMap(stations: List<Station>, grade: Grade, bestId: String?, center: SearchPoint, currentLocation: SearchPoint?, centerRequest: Int, brandLogos: Map<String, Bitmap>, onMove: (SearchPoint) -> Unit,
            modifier: Modifier = Modifier, onSelect: (Station) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val select by rememberUpdatedState(onSelect)
    val moved by rememberUpdatedState(onMove)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var ready by remember { mutableStateOf(false) }
    val stationsById = remember(stations) { stations.associateBy { it.id } }
    val currentStations by rememberUpdatedState(stationsById)
    val markerImages = remember { mutableMapOf<String, Bitmap>() }
    val overlay = remember { StationMarkerOverlay(context) }
    val view = remember {
        MapLibre.getInstance(context.applicationContext)
        MapNetworking.configure(context.applicationContext)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { loaded ->
                loaded.uiSettings.isAttributionEnabled = false // Visible, clickable Compose attribution above the sheet.
                loaded.uiSettings.isLogoEnabled = false
                loaded.uiSettings.isCompassEnabled = false
                loaded.setPrefetchZoomDelta(0)
                loaded.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(center.latitude, center.longitude), if (center == SearchPoint.CANADA_OVERVIEW) 2.6 else 12.5))
                loaded.setStyle(Style.Builder().fromJson(OSM_STYLE)) { style ->
                    style.addSource(GeoJsonSource("location", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                    style.addLayer(CircleLayer("your-location", "location").withProperties(circleRadius(8f), circleColor("#267BCE"), circleStrokeWidth(3f), circleStrokeColor("#FFFFFF")))
                    ready = true
                }
                overlay.map = loaded
                loaded.addOnCameraMoveListener { overlay.postInvalidateOnAnimation() }
                loaded.addOnCameraIdleListener {
                    overlay.postInvalidateOnAnimation()
                    loaded.cameraPosition.target?.wrap()?.let { moved(SearchPoint(it.latitude,it.longitude,"Map area", SearchSource.MAP)) }
                }
                loaded.addOnMapClickListener { coordinate ->
                    val stationId = overlay.stationAt(loaded.projection.toScreenLocation(coordinate))
                    val station = stationId?.let { currentStations[it] }
                    station?.let(select)
                    station != null
                }
                map = loaded
            }
        }
    }
    DisposableEffect(view, lifecycle) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); view.onPause(); view.onStop(); view.onDestroy() }
    }
    LaunchedEffect(map, ready, center.latitude, center.longitude, centerRequest) {
        if (ready) map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(center.latitude, center.longitude), if (center == SearchPoint.CANADA_OVERVIEW) 2.6 else 12.5))
    }
    val pins = remember(stations, grade, bestId, brandLogos.keys) {
        stations.mapNotNull { station ->
            val lat = station.latitude ?: return@mapNotNull null
            val lon = station.longitude ?: return@mapNotNull null
            val logoUrl = station.brandLogoUrl?.takeIf { brandLogos.containsKey(it) }
            val marker = MarkerArt(station.brandKey, logoUrl, station.name.take(1).uppercase(), station.price(grade), station.id == bestId)
            StationPin(station.id, lat, lon, marker)
        }
    }
    LaunchedEffect(map, ready, pins) { withContext(Dispatchers.Main.immediate) {
        if (map == null) return@withContext
        if (!ready) return@withContext
        val needed = pins.map { it.art }.distinctBy { it.imageId }
        val missing = needed.filter { it.imageId !in markerImages }
        val images = withContext(Dispatchers.Default) {
            missing.associate { art -> art.imageId to drawMarker(art, brandLogos[art.logoUrl]) }
        }
        markerImages.putAll(images)
        markerImages.keys.retainAll(needed.map { it.imageId }.toSet())
        overlay.update(pins, markerImages)
    } }
    // A new GPS fix only updates the blue dot; station marker art stays intact.
    LaunchedEffect(map, ready, currentLocation?.latitude, currentLocation?.longitude) {
        if (!ready) return@LaunchedEffect
        val features = currentLocation?.let { listOf(Feature.fromGeometry(Point.fromLngLat(it.longitude,it.latitude))) } ?: emptyList()
        map?.style?.getSourceAs<GeoJsonSource>("location")?.setGeoJson(FeatureCollection.fromFeatures(features))
    }
    AndroidView(factory = {
        FrameLayout(context).apply {
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(overlay, FrameLayout.LayoutParams(-1, -1))
        }
    }, modifier = modifier)
}

/** One lightweight Canvas keeps marker art independent of GPU sprite-atlas bugs.
 * It consumes no gestures; the map underneath pans/zooms and supplies the hit-tested tap. */
private class StationMarkerOverlay(context: android.content.Context) : View(context) {
    var map: MapLibreMap? = null
    private data class Target(val id: String, val coordinate: LatLng, val image: Bitmap, val bounds: RectF = RectF(), var visible: Boolean = false)
    private var targets: List<Target> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scale = resources.displayMetrics.density / 2f
    private val minimumTarget = 44 * resources.displayMetrics.density

    fun update(value: List<StationPin>, art: Map<String, Bitmap>) {
        targets = value.asReversed().mapNotNull { pin -> art[pin.art.imageId]?.let {
            Target(pin.id, LatLng(pin.latitude, pin.longitude), it)
        } }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val projection = map?.projection ?: return
        // Draw higher-ranked stations last so their markers and taps win overlaps.
        for (target in targets) {
            val image = target.image
            val position = projection.toScreenLocation(target.coordinate)
            val halfWidth = image.width * scale / 2f
            val halfHeight = image.height * scale / 2f
            val bounds = target.bounds
            bounds.set(position.x - halfWidth, position.y - halfHeight, position.x + halfWidth, position.y + halfHeight)
            target.visible = bounds.right >= 0 && bounds.left <= width && bounds.bottom >= 0 && bounds.top <= height
            if (!target.visible) continue
            canvas.drawBitmap(image, null, bounds, paint)
        }
    }

    fun stationAt(point: android.graphics.PointF): String? = targets.asReversed().firstOrNull {
        // At least a 44dp target even for a small unknown-price marker.
        val bounds = it.bounds
        val halfWidth = maxOf(bounds.width(), minimumTarget) / 2
        val halfHeight = maxOf(bounds.height(), minimumTarget) / 2
        it.visible && kotlin.math.abs(point.x - bounds.centerX()) <= halfWidth && kotlin.math.abs(point.y - bounds.centerY()) <= halfHeight
    }?.id
}

private data class StationPin(val id: String, val latitude: Double, val longitude: Double, val art: MarkerArt)
private data class MarkerArt(val brandKey: String?, val logoUrl: String?, val initial: String, val price: Int?, val best: Boolean) {
    val imageId: String = "pin-${brandKey ?: initial.firstOrNull()?.code}-${logoUrl?.hashCode() ?: 0}-${price ?: 0}-$best"
}

/** One small premultiplied bitmap per unique visible brand/price, rendered off the UI thread. */
private fun drawMarker(art: MarkerArt, logo: Bitmap?): Bitmap {
    val scale = 2f
    val width = (if (art.price == null) 42 else 93) * scale
    val height = 40 * scale
    val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888).apply { density = 320 }
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val rect = RectF(1f, 1f, width - 1, height - 1)
    canvas.drawRoundRect(rect, 13 * scale, 13 * scale, paint)
    paint.style = Paint.Style.STROKE; paint.strokeWidth = (if (art.best) 2 else 1) * scale; paint.color = 0xFF245745.toInt()
    canvas.drawRoundRect(rect, 13 * scale, 13 * scale, paint)
    paint.style = Paint.Style.FILL
    if (logo != null) {
        val factor = minOf(29 * scale / logo.width, 29 * scale / logo.height)
        val left = 21 * scale - logo.width * factor / 2
        val top = height / 2 - logo.height * factor / 2
        paint.isFilterBitmap = true
        canvas.drawBitmap(logo, null, RectF(left, top, left + logo.width * factor, top + logo.height * factor), paint)
    } else {
        paint.textSize = 18 * scale; paint.textAlign = Paint.Align.CENTER; paint.isFakeBoldText = true
        canvas.drawText(art.initial.ifBlank { "•" }, 21 * scale, (height - paint.ascent() - paint.descent()) / 2, paint)
    }
    art.price?.let { price ->
        paint.textSize = 14 * scale; paint.textAlign = Paint.Align.CENTER; paint.isFakeBoldText = true
        canvas.drawText(FuelCore.priceText(price), 65 * scale, (height - paint.ascent() - paint.descent()) / 2, paint)
    }
    return bitmap
}

private object MapNetworking {
    private var configured = false
    @Synchronized fun configure(context: android.content.Context) {
        if (configured) return
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder()
            .cache(Cache(File(context.applicationContext.cacheDir, "map-tiles"), 64L * 1024 * 1024))
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "OpenFuel/${BuildConfig.VERSION_NAME} (+https://openfuel.ca)").build()) }.build())
        configured = true
    }
}
