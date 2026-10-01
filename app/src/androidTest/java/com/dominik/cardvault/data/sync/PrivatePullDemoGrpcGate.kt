package com.dominik.cardvault.data.sync

import com.google.firebase.firestore.remote.GrpcCallProvider
import com.google.firebase.firestore.util.Supplier
import com.google.firestore.v1.BatchGetDocumentsRequest
import com.google.firestore.v1.CommitRequest
import com.google.firestore.v1.ListenRequest
import com.google.firestore.v1.WriteRequest
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCallListener
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.okhttp.OkHttpChannelBuilder
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Test-only Firestore 26.5.0 hook. No production reader, transport, or SDK file is modified. */
internal class PrivatePullDemoGrpcGate : ClientInterceptor, AutoCloseable {
    enum class Kind { TRANSACTION_COMMIT, OPTIMISTIC_WRITE }

    private val hook = GrpcCallProvider::class.java.getDeclaredField("overrideChannelBuilderSupplier")
    private val calls = ConcurrentHashMap.newKeySet<GuardedCall<*, *>>()
    private val closed = AtomicBoolean(false)
    private val gateLock = Any()
    private var armed: Pair<Kind, String>? = null
    private var captured: GuardedCall<*, *>? = null
    private var held = CountDownLatch(1)
    private val previous: Any?
    private val supplier = Supplier<ManagedChannelBuilder<*>> {
        check(!closed.get()) { "Demo gRPC gate is closed" }
        // Never inherit or discover a real Firebase endpoint from the environment.
        OkHttpChannelBuilder.forAddress(HOST, FIRESTORE_PORT).usePlaintext().intercept(this)
    }

    init {
        val version = Class.forName("com.google.firebase.firestore.BuildConfig")
            .getDeclaredField("VERSION_NAME").get(null)
        check(version == "26.5.0") { "Unreviewed Firestore SDK version" }
        check(Modifier.isStatic(hook.modifiers) && hook.type == Supplier::class.java) { "Unreviewed gRPC hook signature" }
        hook.isAccessible = true
        previous = hook.get(null)
        check(previous == null) { "Another gRPC hook is already installed" }
        hook.set(null, supplier)
    }

    fun arm(kind: Kind, documentPath: String) = synchronized(gateLock) {
        check(!closed.get() && armed == null && captured == null)
        check(documentPath.startsWith("$DATABASE/documents/users/sdk-proof-"))
        held = CountDownLatch(1)
        armed = kind to documentPath
    }

    fun awaitHeld() { check(held.await(15, TimeUnit.SECONDS)) { "Expected SDK write did not reach the controlled gate" } }

    fun release() {
        val call = synchronized(gateLock) { captured.also { captured = null } }
        checkNotNull(call) { "No controlled write to release" }.releaseMessages()
    }

    /** Deterministic negative transport check: no delegate start, headers, or message for another DB. */
    fun verifyForeignDatabaseFailsBeforeStart() {
        var starts = 0
        var sends = 0
        var status: Status? = null
        val delegate = object : ClientCall<ListenRequest, Any>() {
            override fun start(listener: Listener<Any>, headers: Metadata) { starts++ }
            override fun request(numMessages: Int) = Unit
            override fun cancel(message: String?, cause: Throwable?) = Unit
            override fun halfClose() = Unit
            override fun sendMessage(message: ListenRequest) { sends++ }
        }
        val call = GuardedCall(delegate, LISTEN)
        call.start(object : ClientCall.Listener<Any>() {
            override fun onClose(value: Status, trailers: Metadata) { status = value }
        }, Metadata())
        call.request(1)
        call.sendMessage(ListenRequest.newBuilder().setDatabase("projects/not-the-demo/databases/(default)").build())
        check(status?.code == Status.Code.PERMISSION_DENIED && starts == 0 && sends == 0) {
            "Foreign database reached delegate transport"
        }
    }

    private fun claim(method: String, message: Any?): Boolean = synchronized(gateLock) {
        val expected = armed ?: return@synchronized false
        val matches = when (expected.first) {
            Kind.TRANSACTION_COMMIT -> method == COMMIT && message is CommitRequest &&
                message.writesList.any { it.update.name == expected.second }
            Kind.OPTIMISTIC_WRITE -> method == WRITE && message is WriteRequest &&
                message.writesList.any { it.update.name == expected.second }
        }
        if (matches) armed = null
        matches
    }

    private fun capture(call: GuardedCall<*, *>) = synchronized(gateLock) {
        if (closed.get()) call.cancel("Demo test cleanup", null)
        else { captured = call; held.countDown() }
    }

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>, options: CallOptions, next: Channel,
    ): ClientCall<ReqT, RespT> {
        check(next.authority() == "$HOST:$FIRESTORE_PORT") { "Unexpected gRPC destination" }
        return GuardedCall(next.newCall(method, options), method.fullMethodName).also { calls.add(it) }
    }

    /**
     * Defer real start (and thus CallCredentials/headers) until the FIRST typed RPC
     * names our exact demo database. The global supplier cannot inspect the SDK's
     * original DatabaseInfo; unrelated/default-app RPCs therefore fail locally,
     * rather than being blindly forwarded to the emulator. No headers are logged.
     */
    private inner class GuardedCall<ReqT, RespT>(
        private val delegate: ClientCall<ReqT, RespT>, private val method: String,
    ) : ClientCall<ReqT, RespT>() {
        private val lock = Any()
        private var listener: Listener<RespT>? = null
        private var headers: Metadata? = null
        private var started = false
        private var finished = false
        private var localClose: Status? = null
        private var pendingRequests = 0
        private var suspended = false
        private var pendingHalfClose = false
        private val messages = mutableListOf<ReqT>()

        override fun start(responseListener: Listener<RespT>, requestHeaders: Metadata) = synchronized(lock) {
            check(listener == null)
            listener = responseListener; headers = requestHeaders
            val alreadyClosed = localClose
            if (alreadyClosed != null) responseListener.onClose(alreadyClosed, Metadata())
            else if (closed.get()) finishLocally(Status.CANCELLED.withDescription("Demo test cleanup"))
        }

        override fun request(numMessages: Int) = synchronized(lock) {
            if (!finished) {
                if (started) delegate.request(numMessages)
                else pendingRequests = Math.addExact(pendingRequests, numMessages)
            }
        }

        override fun sendMessage(message: ReqT) = synchronized(lock) {
            if (finished) return@synchronized
            if (closed.get()) { cancel("Demo test cleanup", null); return@synchronized }
            if (!started) {
                val database = when {
                    method == COMMIT && message is CommitRequest -> message.database
                    method == WRITE && message is WriteRequest -> message.database
                    method == LISTEN && message is ListenRequest -> message.database
                    method == BATCH_GET && message is BatchGetDocumentsRequest -> message.database
                    else -> null
                }
                if (database != DATABASE) {
                    finishLocally(Status.PERMISSION_DENIED.withDescription("Only the fixed demo database is permitted"))
                    return@synchronized
                }
                started = true
                delegate.start(object : ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(checkNotNull(listener)) {
                    override fun onClose(status: Status, trailers: Metadata) {
                        synchronized(lock) { finished = true; messages.clear(); calls.remove(this@GuardedCall) }
                        super.onClose(status, trailers)
                    }
                }, checkNotNull(headers))
                if (pendingRequests > 0) delegate.request(pendingRequests)
                pendingRequests = 0
            }
            if (suspended || claim(method, message)) {
                check(messages.size < 8) { "Unexpected unbounded SDK write buffering" }
                messages.add(message)
                if (!suspended) { suspended = true; capture(this) }
            } else delegate.sendMessage(message)
        }

        override fun halfClose() = synchronized(lock) {
            if (!finished) {
                if (started && !suspended) delegate.halfClose() else pendingHalfClose = true
            }
        }

        fun releaseMessages() = synchronized(lock) {
            check(started && suspended && !finished && !closed.get())
            suspended = false
            messages.forEach(delegate::sendMessage); messages.clear()
            if (pendingHalfClose) { pendingHalfClose = false; delegate.halfClose() }
        }

        override fun cancel(message: String?, cause: Throwable?) = synchronized(lock) {
            if (!finished) {
                messages.clear()
                if (started) delegate.cancel(message, cause)
                else finishLocally(Status.CANCELLED.withDescription("Demo call cancelled"))
            }
        }

        override fun isReady(): Boolean = synchronized(lock) { started && !finished && !suspended && delegate.isReady }

        private fun finishLocally(status: Status) {
            if (finished) return
            finished = true; messages.clear(); calls.remove(this)
            localClose = status
            listener?.onClose(status, Metadata())
        }
    }

    /** Called before terminating test Firestore instances; no held mutation is released on failure. */
    fun cancelCalls() {
        closed.set(true)
        synchronized(gateLock) { armed = null; captured = null }
        calls.toList().forEach { it.cancel("Demo test cleanup", null) }
    }

    override fun close() {
        cancelCalls()
        val ours = hook.get(null) === supplier
        try { hook.set(null, previous) } finally { check(hook.get(null) === previous) { "gRPC hook restoration failed" } }
        check(ours) { "gRPC hook changed unexpectedly during isolated test" }
    }

    companion object {
        const val PROJECT = "demo-domidex-privacy"
        const val DATABASE = "projects/$PROJECT/databases/(default)"
        const val HOST = "127.0.0.1"
        const val FIRESTORE_PORT = 8187
        const val AUTH_PORT = 9097
        private const val COMMIT = "google.firestore.v1.Firestore/Commit"
        private const val WRITE = "google.firestore.v1.Firestore/Write"
        private const val LISTEN = "google.firestore.v1.Firestore/Listen"
        private const val BATCH_GET = "google.firestore.v1.Firestore/BatchGetDocuments"
    }
}
