// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** City lookups can be superseded while their socket is waiting for a response. */
internal class CityLookup private constructor(context: Context) {
    private val client = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "city-http"), 2L * 1024 * 1024))
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun search(base: String, query: String): JSONObject {
        val request = Request.Builder()
            .url(base + "/geocode?q=" + java.net.URLEncoder.encode(query.take(100), "UTF-8"))
            .header("Accept", "application/json")
            .header("User-Agent", "OpenFuel-Android/${BuildConfig.VERSION_NAME} (+https://openfuel.ca)")
            .build()
        return client.newCall(request).awaitCityResponse()
    }

    companion object {
        @Volatile private var instance: CityLookup? = null
        fun get(context: Context): CityLookup = instance ?: synchronized(this) {
            instance ?: CityLookup(context.applicationContext).also { instance = it }
        }
    }
}

/** Cancellation closes the actual HTTP call, rather than waiting for its blocking read timeout. */
internal suspend fun Call.awaitCityResponse(): JSONObject = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) { continuation.resumeWith(Result.failure(error)) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resumeWith(runCatching {
                response.use {
                    val body = it.body ?: throw IOException("Empty city search response.")
                    val text = body.charStream().use { reader ->
                        val result = StringBuilder()
                        val buffer = CharArray(8192)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count == -1) break
                            if (result.length + count > 2_000_000) throw IOException("Unexpectedly large server response.")
                            result.append(buffer, 0, count)
                        }
                        result.toString()
                    }
                    if (!it.isSuccessful) {
                        serviceLimit(it.code, text)?.let { limit -> throw limit }
                        throw IOException("City search unavailable (${it.code}).")
                    }
                    JSONObject(text)
                }
            })
        }
    })
}
