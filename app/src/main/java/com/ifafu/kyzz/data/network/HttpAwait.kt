package com.ifafu.kyzz.data.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/** Consume and close the response in the callback, including cancellation races. */
internal suspend fun Call.awaitSuccessfulText(): String? = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    val body = if (it.isSuccessful) it.body?.string() else null
                    if (continuation.isActive) continuation.resumeWith(Result.success(body))
                }
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }
        }
    })
}
