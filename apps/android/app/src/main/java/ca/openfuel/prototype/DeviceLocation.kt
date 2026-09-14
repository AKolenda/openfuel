// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** A foreground, bounded one-shot fix; no tracking or background service. */
suspend fun currentSearchPoint(context: Context): SearchPoint? {
    if (!hasLocationPermission(context)) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
        runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
    }
    if (providers.isEmpty()) return null
    val recent = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .filter { System.currentTimeMillis() - it.time in 0..120_000 && it.accuracy <= 3000 }
        .minByOrNull { it.accuracy }
    fun Location.point() = SearchPoint(latitude, longitude, if (accuracy > 500) "Your approximate location" else "Your location", SearchSource.DEVICE)
    if (recent != null) return recent.point()
    return withTimeoutOrNull(18_000) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (continuation.isActive) {
                        manager.removeUpdates(this)
                        continuation.resume(location.point())
                    }
                }
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
                @Deprecated("Required on older Android")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            var registered = false
            providers.forEach { provider ->
                runCatching { manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper()) }
                    .onSuccess { registered = true }
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            if (!registered && continuation.isActive) continuation.resume(null)
        }
    }
}
