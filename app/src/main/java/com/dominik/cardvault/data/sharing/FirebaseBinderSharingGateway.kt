package com.dominik.cardvault.data.sharing

import android.content.Context
import com.dominik.cardvault.auth.canonicalCollectionOwnerUid
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

/** Session and transport seams keep cancellation/owner tests independent of Firebase and live HTTP. */
internal interface BinderSharingSession {
    val currentUid: String?
    suspend fun token(expectedUid: String): String
    fun observeAccount(changed: (String?) -> Unit): () -> Unit
}
internal data class BinderSharingResponse(val status: Int, val body: String)
internal fun interface BinderSharingTransport {
    suspend fun send(method: String, url: String, token: String, body: String?): BinderSharingResponse
}

internal class FirebaseBinderSharingGateway internal constructor(
    private val session: BinderSharingSession,
    private val transport: BinderSharingTransport,
    private val timeoutMillis: Long = 15_000,
    private val readiness: BinderSharingReadiness = BinderSharingReadiness { _, _ -> },
) : BinderSharingGateway {
    constructor(context: Context) : this(
        FirebaseSharingSession(FirebaseAuth.getInstance()), OkHttpSharingTransport,
        readiness = PrivateBinderSharingReadiness(context.applicationContext),
    )

    override suspend fun list(expectedUid: String, binderId: String): List<BinderShareSummary> =
        perform(expectedUid, "GET", BinderSharingContract.listUrl(binderId), null, retryMissingAfter = {
            // A new local binder may not exist remotely yet. Only this 404 case
            // waits for sync; existing links remain immediately manageable.
            readiness.awaitReady(expectedUid, binderId)
        }) {
            BinderSharingContract.parseList(it.status, it.body)
        }

    override suspend fun create(expectedUid: String, binderId: String, options: BinderShareOptions, displayName: String?, confirmed: Boolean): CreatedBinderShare =
        perform(expectedUid, "POST", BinderSharingContract.ENDPOINT, BinderSharingContract.createPayload(binderId, options, displayName, confirmed), prepare = {
            readiness.awaitReady(expectedUid, binderId)
        }) {
            BinderSharingContract.parseCreated(it.status, it.body, options)
        }

    override suspend fun revoke(expectedUid: String, shareId: String) =
        perform(expectedUid, "DELETE", BinderSharingContract.ENDPOINT, BinderSharingContract.revokePayload(shareId)) {
            BinderSharingContract.parseRevoked(it.status, it.body)
        }

    private suspend fun <T> perform(
        expectedUid: String, method: String, url: String, body: String?,
        prepare: suspend () -> Unit = {}, retryMissingAfter: (suspend () -> Unit)? = null,
        parse: (BinderSharingResponse) -> T,
    ): T {
        try {
            return coroutineScope {
                BinderSharingContract.requireAccount(expectedUid, session.currentUid)
                // Also covers the bounded sync wait, including away-and-back account changes.
                val removeListener = session.observeAccount { uid ->
                    if (uid != expectedUid) cancel(AccountChangedSharingCancellation())
                }
                try {
                    currentCoroutineContext().ensureActive()
                    BinderSharingContract.requireAccount(expectedUid, session.currentUid)
                    prepare()
                    currentCoroutineContext().ensureActive()
                    BinderSharingContract.requireAccount(expectedUid, session.currentUid)
                    suspend fun request(allowMissing: Boolean): ParsedSharingResult<T>? = withTimeout(timeoutMillis) {
                        currentCoroutineContext().ensureActive()
                        BinderSharingContract.requireAccount(expectedUid, session.currentUid)
                        val token = session.token(expectedUid)
                        currentCoroutineContext().ensureActive()
                        BinderSharingContract.requireAccount(expectedUid, session.currentUid)
                        if (token.isBlank()) throw BinderSharingException("Die Anmeldung konnte nicht bestätigt werden. Bitte erneut anmelden.")
                        val response = transport.send(method, url, token, body)
                        currentCoroutineContext().ensureActive()
                        BinderSharingContract.requireAccount(expectedUid, session.currentUid)
                        if (allowMissing && method == "GET" && response.status == 404) return@withTimeout null
                        val result = parse(response)
                        currentCoroutineContext().ensureActive()
                        BinderSharingContract.requireAccount(expectedUid, session.currentUid)
                        ParsedSharingResult(result)
                    }
                    val first = request(allowMissing = retryMissingAfter != null)
                    if (first != null) first.value else {
                        requireNotNull(retryMissingAfter).invoke()
                        currentCoroutineContext().ensureActive()
                        BinderSharingContract.requireAccount(expectedUid, session.currentUid)
                        // Only GET retries, once. An ambiguous POST is never replayed.
                        requireNotNull(request(allowMissing = false)).value
                    }
                } finally { removeListener() }
            }
        } catch (_: AccountChangedSharingCancellation) {
            currentCoroutineContext().ensureActive()
            throw BinderSharingContract.accountFailure()
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw BinderSharingContract.unknownOutcome()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BinderSharingException) {
            throw failure
        } catch (_: Exception) {
            throw BinderSharingContract.unknownOutcome()
        }
    }
}

private data class ParsedSharingResult<T>(val value: T)

private class AccountChangedSharingCancellation : CancellationException("Binder sharing account changed")

private class FirebaseSharingSession(private val auth: FirebaseAuth) : BinderSharingSession {
    override val currentUid: String? get() = canonicalCollectionOwnerUid(auth.currentUser?.takeUnless { it.isAnonymous })
    override suspend fun token(expectedUid: String): String {
        val user = auth.currentUser ?: throw BinderSharingContract.accountFailure()
        BinderSharingContract.requireAccount(expectedUid, canonicalCollectionOwnerUid(user.takeUnless { it.isAnonymous }))
        val token = user.getIdToken(true).awaitSharingTask().token
        BinderSharingContract.requireAccount(expectedUid, currentUid)
        if (user.uid != expectedUid) throw BinderSharingContract.accountFailure()
        return token?.takeIf(String::isNotBlank) ?: throw BinderSharingException("Die Anmeldung konnte nicht bestätigt werden. Bitte erneut anmelden.")
    }
    override fun observeAccount(changed: (String?) -> Unit): () -> Unit {
        val listener = FirebaseAuth.AuthStateListener { changed(currentUid) }
        auth.addAuthStateListener(listener)
        return { auth.removeAuthStateListener(listener) }
    }
}

private object OkHttpSharingTransport : BinderSharingTransport {
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()

    override suspend fun send(method: String, url: String, token: String, body: String?): BinderSharingResponse {
        val requestBody = body?.toRequestBody("application/json; charset=utf-8".toMediaType())?.let { delegate ->
            object : RequestBody() {
                override fun contentType() = delegate.contentType()
                override fun contentLength() = delegate.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
            }
        }
        val request = Request.Builder().url(url).method(method, requestBody)
            .header("Authorization", "Bearer $token").header("Accept", "application/json")
            .header("Cache-Control", "no-store").build()
        return client.newCall(request).awaitSharingResponse()
    }
}

private suspend fun Call.awaitSharingResponse(): BinderSharingResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(BinderSharingContract.unknownOutcome())
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    if (!continuation.isActive) return
                    val body = if (response.code == 200 || response.code == 201) {
                        val content = response.body ?: throw BinderSharingContract.unknownOutcome()
                        if (content.contentType()?.subtype != "json") throw BinderSharingContract.unknownOutcome()
                        content.byteStream().use { BinderSharingContract.readBody(it, content.contentLength()) }
                    } else "" // Never disclose server errors, headers, credentials or redirects.
                    if (continuation.isActive) continuation.resumeWith(Result.success(BinderSharingResponse(response.code, body)))
                }
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resumeWithException(BinderSharingContract.unknownOutcome())
            }
        }
    })
}

private suspend fun <T> Task<T>.awaitSharingTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        when {
            task.isCanceled -> continuation.cancel()
            task.isSuccessful -> continuation.resumeWith(Result.success(task.result))
            else -> continuation.resumeWithException(BinderSharingException("Die Anmeldung konnte nicht bestätigt werden. Bitte erneut anmelden."))
        }
    }
}
