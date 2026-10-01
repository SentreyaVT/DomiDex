package com.dominik.cardvault.data.knowledge

import com.dominik.cardvault.auth.canonicalCollectionOwnerUid
import com.dominik.cardvault.data.feedback.ScannerReportKnowledgeContext
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** A short, parallel read. No persistent cache: re-review/deletion must not leave stale hints. */
internal class FirebaseReviewedScanKnowledge(private val auth: FirebaseAuth) {
    fun observeRelevant(context: ScannerReportKnowledgeContext, onUpdate: (ReviewedScanKnowledgeHint?) -> Unit): AutoCloseable {
        val payload = ReviewedScanKnowledgeContract.payload(context) ?: return AutoCloseable { }
        val uid = canonicalCollectionOwnerUid(auth.currentUser) ?: return AutoCloseable { }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val lock = Any()
        var closed = false
        val accountListener = FirebaseAuth.AuthStateListener {
            if (canonicalCollectionOwnerUid(it.currentUser) != uid) synchronized(lock) {
                closed = true
                onUpdate(null)
                scope.cancel()
            }
        }
        auth.addAuthStateListener(accountListener)
        scope.launch {
            val hint = try {
                withTimeoutOrNull(ReviewedScanKnowledgeContract.TIMEOUT_MILLIS) {
                    val user = auth.currentUser?.takeIf { canonicalCollectionOwnerUid(it) == uid } ?: return@withTimeoutOrNull null
                    val token = user.getIdToken(false).awaitReviewedAuthTask()
                    if (!ReviewedScanKnowledgeContract.allowedAccount(uid, canonicalCollectionOwnerUid(auth.currentUser), token.claims)) return@withTimeoutOrNull null
                    val bearer = token.token?.takeIf(String::isNotBlank) ?: return@withTimeoutOrNull null
                    val request = Request.Builder().url(ReviewedScanKnowledgeContract.ENDPOINT)
                        .header("Authorization", "Bearer $bearer").header("Accept", "application/json")
                        .header("Cache-Control", "no-store")
                        .post(payload.toRequestBody("application/json".toMediaType())).build()
                    client.newCall(request).awaitReviewedHint(context)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { null }
            synchronized(lock) {
                if (!closed && canonicalCollectionOwnerUid(auth.currentUser) == uid) onUpdate(hint)
            }
        }
        return AutoCloseable {
            synchronized(lock) { closed = true; scope.cancel() }
            auth.removeAuthStateListener(accountListener)
        }
    }

    private companion object {
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .callTimeout(ReviewedScanKnowledgeContract.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).build()
    }
}

internal suspend fun Call.awaitReviewedHint(context: ScannerReportKnowledgeContext): ReviewedScanKnowledgeHint? =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.success(null))
            }
            override fun onResponse(call: Call, response: Response) {
                val hint = response.use {
                    if (it.code != 200) return@use null
                    val body = it.body ?: return@use null
                    runCatching {
                        val source = body.source()
                        if (source.request(ReviewedScanKnowledgeContract.MAX_RESPONSE_BYTES + 1)) null
                        else ReviewedScanKnowledgeContract.parseResponse(context, source.readByteArray().toString(Charsets.UTF_8))
                    }.getOrNull()
                }
                if (continuation.isActive) continuation.resumeWith(Result.success(hint))
            }
        })
    }

private suspend fun <T> Task<T>.awaitReviewedAuthTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (continuation.isActive) {
            if (task.isSuccessful) continuation.resumeWith(Result.success(task.result))
            else continuation.resumeWith(Result.failure(task.exception ?: IOException("Authentication unavailable")))
        }
    }
}
