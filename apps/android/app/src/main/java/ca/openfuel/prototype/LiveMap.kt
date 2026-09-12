// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.File
import java.util.concurrent.TimeUnit

private const val OSM_STYLE = """{
 "version":8,
 "sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"minzoom":0,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},
 "layers":[{"id":"osm","type":"raster","source":"osm"}]
}"""

/** Native OpenGL MapLibre map. Cached on-demand tiles only; prefetch and offline downloads disabled. */
@Suppress("DEPRECATION")
@Composable
fun LiveMap(stations: List<Station>, grade: Grade, bestId: String?, center: SearchPoint, currentLocation: SearchPoint?, centerRequest: Int, onMove: (SearchPoint) -> Unit,
            modifier: Modifier = Modifier, onSelect: (Station) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val select by rememberUpdatedState(onSelect)
    val moved by rememberUpdatedState(onMove)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var ready by remember { mutableStateOf(false) }
    val currentStations by rememberUpdatedState(stations)
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
                    style.addSource(GeoJsonSource("stations", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                    // Keep stations visible and tappable while price images enter the sprite atlas.
                    style.addLayer(CircleLayer("station-dots", "stations").withProperties(circleRadius(9f), circleColor("#245745"), circleStrokeWidth(2f), circleStrokeColor("#FFFFFF")))
                    style.addLayer(SymbolLayer("station-pins", "stations").withProperties(iconImage(get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true)))
                    style.addSource(GeoJsonSource("location", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                    style.addLayer(CircleLayer("your-location", "location").withProperties(circleRadius(8f), circleColor("#267BCE"), circleStrokeWidth(3f), circleStrokeColor("#FFFFFF")))
                    ready = true
                }
                loaded.addOnCameraIdleListener {
                    loaded.cameraPosition.target?.let { moved(SearchPoint(it.latitude,it.longitude,"Map area")) }
                }
                loaded.addOnMapClickListener { coordinate ->
                    val feature = loaded.queryRenderedFeatures(loaded.projection.toScreenLocation(coordinate), "station-pins", "station-dots").firstOrNull()
                    val station = feature?.getStringProperty("station_id")?.let { id -> currentStations.find { it.id == id } }
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
    LaunchedEffect(map, ready, center, centerRequest) {
        if (ready) map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(center.latitude, center.longitude), if (center == SearchPoint.CANADA_OVERVIEW) 2.6 else 12.5))
    }
    LaunchedEffect(map, ready, stations, grade, bestId, currentLocation) {
        val loaded = map ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val style = loaded.style ?: return@LaunchedEffect
        val features = mutableListOf<Feature>()
        val registeredIcons = mutableSetOf<String>()
        stations.forEach { station ->
            val lat = station.latitude ?: return@forEach; val lon = station.longitude ?: return@forEach
            val label = station.price(grade)?.let(FuelCore::priceText) ?: "?"
            val density = context.resources.displayMetrics.density
            val width = (if (label == "?") 38 else 76) * density
            val height = 38 * density
            val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val best = station.id == bestId
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (best) 0xFF245745.toInt() else android.graphics.Color.WHITE }
            canvas.drawRoundRect(RectF(1f,1f,width-1,height-1), height/2,height/2,paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = density; paint.color = 0xFF6A8175.toInt()
            canvas.drawRoundRect(RectF(1f,1f,width-1,height-1),height/2,height/2,paint)
            paint.style = Paint.Style.FILL; paint.color = if (best) android.graphics.Color.WHITE else 0xFF245745.toInt()
            paint.textSize = 16*density; paint.textAlign = Paint.Align.CENTER; paint.isFakeBoldText = true
            canvas.drawText(label,width/2,(height-paint.ascent()-paint.descent())/2,paint)
            val iconId = "price-${station.price(grade) ?: "unknown"}-$best"
            if (registeredIcons.add(iconId)) style.addImage(iconId, bitmap)
            features += Feature.fromGeometry(Point.fromLngLat(lon,lat)).apply {
                addStringProperty("station_id", station.id); addStringProperty("icon", iconId)
            }
        }
        style.getSourceAs<GeoJsonSource>("stations")?.setGeoJson(FeatureCollection.fromFeatures(features))
        val locationFeatures = currentLocation?.let { listOf(Feature.fromGeometry(Point.fromLngLat(it.longitude,it.latitude))) } ?: emptyList()
        style.getSourceAs<GeoJsonSource>("location")?.setGeoJson(FeatureCollection.fromFeatures(locationFeatures))
    }
    AndroidView(factory = { view }, modifier = modifier)
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
