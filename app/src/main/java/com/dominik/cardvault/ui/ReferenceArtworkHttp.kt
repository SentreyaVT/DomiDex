package com.dominik.cardvault.ui

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

/** Existing artwork client/byte contract, retaining ownership through actual callback cleanup.
 * The factory is an internal synthetic-transport seam, not a different production client. */
internal suspend fun downloadReferenceArtwork(url: String, calls: Call.Factory): ByteArray? {
    val callbackCompleted = CompletableDeferred<Unit>()
    var call: Call? = null
    var callbackRegistered = false
    try {
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val request = runCatching {
                    Request.Builder().url(url).header("User-Agent", "DomiDex scanner artwork verification").build()
                }.getOrNull()
                if (request == null) { continuation.resume(null); return@suspendCancellableCoroutine }
                val activeCall = calls.newCall(request)
                call = activeCall
                // Cancellation during factory publication must not start a new callback job.
                if (!continuation.isActive) return@suspendCancellableCoroutine
                callbackRegistered = true
                try {
                    activeCall.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            try {
                                if (continuation.isActive) continuation.resume(null)
                            } finally { callbackCompleted.complete(Unit) }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            try {
                                val encoded = response.use {
                                    val body = response.body
                                    if (!continuation.isActive || !response.isSuccessful || body == null ||
                                        body.contentLength() > MAX_REFERENCE_ARTWORK_BYTES) null
                                    else runCatching { body.byteStream().use(::readBoundedReferenceArtwork) }.getOrNull()
                                }
                                if (continuation.isActive) continuation.resume(encoded)
                            } catch (failure: Throwable) {
                                if (continuation.isActive) continuation.resumeWithException(failure)
                            } finally { callbackCompleted.complete(Unit) }
                        }
                    })
                } catch (failure: Throwable) {
                    // A rejected enqueue has no future callback to wait for.
                    callbackCompleted.complete(Unit)
                    throw failure
                }
            }
        }
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable + Dispatchers.IO) {
            try { call?.cancel() }
            catch (cleanupFailure: Throwable) { cancelled.addSuppressed(cleanupFailure) }
        }
        throw cancelled
    } finally {
        // withContext owns factory/enqueue completion; this additionally owns the callback's
        // bounded reader and Response.use cleanup. The caller's download permit stays held.
        withContext(NonCancellable + Dispatchers.IO) {
            if (callbackRegistered) callbackCompleted.await()
        }
    }
}
