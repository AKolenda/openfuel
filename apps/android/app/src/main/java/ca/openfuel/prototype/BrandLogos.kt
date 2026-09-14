// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

internal fun allowedBrandLogoUrl(value: String?): String? = value?.takeIf {
    it.length <= 1024 && runCatching {
        val uri = URI(it)
        uri.scheme == "https" && uri.host in setOf("thumb.wikimedia.org", "www.fuel.crs", "www.shell.ca", "www.tempo.crs") &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443) && uri.fragment == null
    }.getOrDefault(false)
}

/** Shared list/map cache. Logos are fetched directly from their catalogued public hosts. */
internal class BrandLogoStore private constructor(context: Context) {
    private val memory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val client = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "brand-logos"), 8L * 1024 * 1024))
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(4)
    private val pending = mutableMapOf<String, Deferred<Bitmap?>>()
    private val failed = mutableMapOf<String, Long>()
    private val lock = Any()

    fun peek(url: String): Bitmap? = memory.get(url)

    suspend fun load(rawUrl: String): Bitmap? {
        val url = allowedBrandLogoUrl(rawUrl) ?: return null
        memory.get(url)?.let { return it }
        val task = synchronized(lock) {
            if ((failed[url] ?: 0L) > System.currentTimeMillis() - 60_000) return null
            pending.getOrPut(url) {
                scope.async(start = CoroutineStart.LAZY) {
                    val result = slots.withPermit { runCatching { download(url) }.getOrNull() }
                    if (result != null) memory.put(url, result)
                    synchronized(lock) {
                        pending.remove(url)
                        if (failed.size > 64) failed.clear()
                        if (result == null) failed[url] = System.currentTimeMillis()
                    }
                    result
                }
            }
        }
        return task.await()
    }

    private fun download(url: String): Bitmap? {
        val request = Request.Builder().url(url).header("Accept", "image/png,image/webp,image/jpeg")
            .header("User-Agent", "OpenFuel-Android/${BuildConfig.VERSION_NAME} (+https://openfuel.ca)").build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > 512 * 1024) return null
            val bytes = body.byteStream().use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > 512 * 1024) return null
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384) return null
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    companion object {
        @Volatile private var instance: BrandLogoStore? = null
        fun get(context: Context): BrandLogoStore = instance ?: synchronized(this) {
            instance ?: BrandLogoStore(context.applicationContext).also { instance = it }
        }
    }
}

@Composable
internal fun rememberBrandLogos(stations: List<Station>): Map<String, Bitmap> {
    val context = LocalContext.current
    val store = remember(context) { BrandLogoStore.get(context) }
    val urls = remember(stations) { stations.mapNotNull { allowedBrandLogoUrl(it.brandLogoUrl) }.distinct().sorted().take(64) }
    val logos by produceState<Map<String, Bitmap>>(emptyMap(), urls) {
        value = urls.mapNotNull { url -> store.peek(url)?.let { url to it } }.toMap()
        coroutineScope {
            urls.forEach { url -> launch { store.load(url)?.let { value = value + (url to it) } } }
        }
    }
    return logos
}
