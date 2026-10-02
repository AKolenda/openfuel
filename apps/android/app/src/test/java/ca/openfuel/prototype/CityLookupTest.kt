// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class CityLookupTest {
    @Test fun cancellingCitySearchCancelsTheHttpCallWithoutWaitingForAResponse() = runBlocking {
        val call = PendingCall()
        val request = launch(start = CoroutineStart.UNDISPATCHED) { call.awaitCityResponse() }
        assertTrue(call.isExecuted())
        assertFalse(call.isCanceled())
        request.cancelAndJoin()
        assertTrue(call.isCanceled())
        // The real callback can still arrive after cancellation, without resuming the old search.
        call.callback!!.onFailure(call, java.io.IOException("Canceled"))
        assertTrue(request.isCancelled)
    }

    private class PendingCall : Call {
        var callback: Callback? = null
        private var cancelled = false
        override fun request(): Request = Request.Builder().url("https://example.invalid/geocode").build()
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isCanceled() = cancelled
        override fun isExecuted() = callback != null
        override fun execute(): Response = error("Only async requests are supported")
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = PendingCall()
    }
}
