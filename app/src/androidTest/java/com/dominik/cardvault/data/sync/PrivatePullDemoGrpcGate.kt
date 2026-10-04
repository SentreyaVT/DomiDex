package com.dominik.cardvault.data.sync

import com.google.firebase.firestore.remote.GrpcCallProvider
import com.google.firebase.firestore.util.Supplier
import com.google.firestore.v1.BatchGetDocumentsRequest
import com.google.firestore.v1.CommitRequest
import com.google.firestore.v1.ListenRequest
import com.google.firestore.v1.ListenResponse
import com.google.firestore.v1.TargetChange
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
import java.util.concurrent.Executor
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
    @Volatile private var knowledgeRevocationAudit: KnowledgeRevocationAudit? = null
    @Volatile private var sessionAccessAudit: SessionAccessAudit? = null
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

    /** Read-only wire observation. It never pauses, fabricates, changes or swallows an SDK message. */
    fun observeKnowledgeRevocation(): KnowledgeRevocationAudit = synchronized(gateLock) {
        check(!closed.get() && knowledgeRevocationAudit == null && armed == null && captured == null)
        KnowledgeRevocationAudit("$DATABASE/documents/scanKnowledge/${"6".repeat(64)}")
            .also { knowledgeRevocationAudit = it }
    }

    /** Independent fixed-path audit for the additive data-change scenario; still observation only. */
    fun observeKnowledgeRevocationAfterDataChange(): KnowledgeRevocationAudit = synchronized(gateLock) {
        check(!closed.get() && knowledgeRevocationAudit == null && armed == null && captured == null)
        KnowledgeRevocationAudit("$DATABASE/documents/scanKnowledge/${"8".repeat(64)}")
            .also { knowledgeRevocationAudit = it }
    }

    data class KnowledgeRevocationSnapshot(
        val documentPath: String,
        val targetId: Int?,
        val adds: Int,
        val current: Boolean,
        val ready: Boolean,
        val denied: Int,
        val deniedAfterReady: Boolean,
        val error: String?,
    )

    class KnowledgeRevocationAudit internal constructor(private val documentPath: String) {
        private val lock = Any()
        private var stream: Any? = null
        private var targetId: Int? = null
        private var adds = 0
        private var current = false
        private var ready = false
        private var denied = 0
        private var deniedAfterReady = false
        private var error: String? = null

        // Called only at the existing delegate.sendMessage boundary for actual SDK requests.
        internal fun outbound(call: Any, request: ListenRequest) = synchronized(lock) {
            if (request.hasAddTarget() && request.addTarget.documents.documentsList.contains(documentPath)) {
                adds++
                if (request.database != DATABASE || request.addTarget.documents.documentsList != listOf(documentPath) ||
                    request.addTarget.targetId <= 0 || adds != 1) {
                    error = "unexpected-exact-knowledge-target-registration"
                } else {
                    stream = call
                    targetId = request.addTarget.targetId
                }
            }
            if (stream === call && targetId != null && request.removeTarget == targetId && denied == 0) {
                error = "client-removed-target-before-server-permission-denial"
            }
        }

        // Called only by the forwarding listener for an actual delegate response. In particular,
        // the local foreign-database safety self-test and gRPC onClose cannot satisfy this witness.
        internal fun inbound(call: Any, response: ListenResponse) = synchronized(lock) {
            if (stream !== call ||
                response.responseTypeCase != ListenResponse.ResponseTypeCase.TARGET_CHANGE) return@synchronized
            val observedTarget = targetId ?: return@synchronized
            val change = response.targetChange
            if (observedTarget !in change.targetIdsList) return@synchronized
            if (change.targetIdsList != listOf(observedTarget)) {
                error = "unexpected-shared-target-change"
                return@synchronized
            }
            when (change.targetChangeType) {
                TargetChange.TargetChangeType.CURRENT -> current = true
                TargetChange.TargetChangeType.REMOVE -> {
                    if (change.cause.code != Status.Code.PERMISSION_DENIED.value()) {
                        error = "unexpected-target-removal-cause"
                    } else {
                        denied++
                        deniedAfterReady = ready && current && error == null
                    }
                }
                else -> Unit
            }
        }

        fun markReadyForHostRevocation() = synchronized(lock) {
            check(targetId != null && adds == 1 && current && !ready && denied == 0 && error == null)
            ready = true
        }

        fun snapshot(): KnowledgeRevocationSnapshot = synchronized(lock) {
            KnowledgeRevocationSnapshot(documentPath, targetId, adds, current, ready, denied, deniedAfterReady, error)
        }
    }

    /** Passive only: temporary entitlement gets may come/go BEFORE sealing the live targets. */
    fun observeSessionAccess(ownerUid: String = "sdk-proof-collection-knowledge-session-access"): SessionAccessAudit = synchronized(gateLock) {
        check(!closed.get() && sessionAccessAudit == null && knowledgeRevocationAudit == null &&
            armed == null && captured == null)
        check(ownerUid in setOf("sdk-proof-collection-knowledge-session-access", "sdk-proof-collection-knowledge-deletion-workflow"))
        SessionAccessAudit(ownerUid).also { sessionAccessAudit = it }
    }

    data class SessionAccessSnapshot(
        val sealed: Boolean, val accessTarget: Int?, val knowledgeTarget: Int?,
        val disabledDocuments: Int, val knowledgeDenials: Int, val error: String?,
    )

    class SessionAccessAudit internal constructor(ownerUid: String) {
        private data class Key(val call: Any, val id: Int)
        private data class Watch(val path: String, var current: Boolean = false)
        private val lock = Any()
        private val accessPath = "$DATABASE/documents/accountAccess/$ownerUid"
        private val knowledgePath = "$DATABASE/documents/scanKnowledge/${"a".repeat(64)}"
        private val watches = mutableMapOf<Key, Watch>()
        private val currentTargets = CountDownLatch(1)
        private val disabledTarget = CountDownLatch(1)
        private var operations = 0
        private var sealed = false
        private var accessKey: Key? = null
        private var knowledgeKey: Key? = null
        private var disabledDocuments = 0
        private var knowledgeDenials = 0
        private var error: String? = null

        internal fun outbound(call: Any, request: ListenRequest) = synchronized(lock) {
            if (request.hasAddTarget()) {
                val paths = request.addTarget.documents.documentsList
                val path = paths.firstOrNull { it == accessPath || it == knowledgePath }
                if (path != null) {
                    operations++
                    val key = Key(call, request.addTarget.targetId)
                    if (operations > 32 || request.database != DATABASE || paths != listOf(path) ||
                        key.id <= 0 || watches.containsKey(key) || sealed) {
                        error = "unexpected-session-access-add"
                    } else watches[key] = Watch(path)
                }
            }
            val key = Key(call, request.removeTarget)
            if (watches.containsKey(key)) {
                if (sealed && key == accessKey) error = "sealed-access-target-client-removed"
                watches.remove(key)
            }
        }

        internal fun inbound(call: Any, response: ListenResponse) = synchronized(lock) {
            when (response.responseTypeCase) {
                ListenResponse.ResponseTypeCase.TARGET_CHANGE -> {
                    val change = response.targetChange
                    change.targetIdsList.forEach { id ->
                        val key = Key(call, id)
                        val watch = watches[key]
                        when (change.targetChangeType) {
                            TargetChange.TargetChangeType.CURRENT -> watch?.current = true
                            TargetChange.TargetChangeType.REMOVE -> {
                                if (sealed && key == accessKey) error = "sealed-access-target-server-removed"
                                if (sealed && key == knowledgeKey && change.cause.code == 7) knowledgeDenials++
                                watches.remove(key)
                            }
                            TargetChange.TargetChangeType.RESET -> {
                                watch?.current = false
                                if (sealed && (key == accessKey || key == knowledgeKey)) error = "sealed-target-reset"
                            }
                            else -> Unit
                        }
                    }
                }
                ListenResponse.ResponseTypeCase.DOCUMENT_CHANGE -> {
                    val change = response.documentChange
                    if (sealed && change.document.name == accessPath &&
                        accessKey?.let { it.call === call && it.id in change.targetIdsList } == true) {
                        val fields = change.document.fieldsMap
                        if (fields.keys != setOf("disabled", "updatedAt") ||
                            fields["disabled"]?.valueTypeCase != com.google.firestore.v1.Value.ValueTypeCase.BOOLEAN_VALUE ||
                            fields["disabled"]?.booleanValue != true ||
                            fields["updatedAt"]?.valueTypeCase != com.google.firestore.v1.Value.ValueTypeCase.INTEGER_VALUE ||
                            fields["updatedAt"]?.integerValue != 1791115200000L ||
                            checkNotNull(accessKey).id in change.removedTargetIdsList) {
                            error = "unexpected-sealed-access-document"
                        } else {
                            disabledDocuments++
                            disabledTarget.countDown()
                        }
                    }
                }
                ListenResponse.ResponseTypeCase.DOCUMENT_DELETE -> {
                    if (sealed && response.documentDelete.document == accessPath) error = "sealed-access-document-deleted"
                }
                ListenResponse.ResponseTypeCase.DOCUMENT_REMOVE -> {
                    if (sealed && response.documentRemove.document == accessPath) error = "sealed-access-document-removed"
                }
                else -> Unit
            }
            if (watches.values.count { it.path == accessPath && it.current } == 1 &&
                watches.values.count { it.path == knowledgePath && it.current } == 1) currentTargets.countDown()
        }

        fun awaitCurrentTargets() {
            check(currentTargets.await(20, TimeUnit.SECONDS)) { "Real access/knowledge CURRENT targets not observed" }
        }
        fun seal() = synchronized(lock) {
            check(!sealed && error == null)
            val access = watches.filterValues { it.path == accessPath }.entries.single()
            val knowledge = watches.filterValues { it.path == knowledgePath }.entries.single()
            check(access.value.current && knowledge.value.current)
            accessKey = access.key; knowledgeKey = knowledge.key; sealed = true
        }
        /** Read-only pre-barrier check; real deletion can later alter Auth and target lifetimes. */
        fun assertCurrentTargetsBeforeWorkflow() = synchronized(lock) {
            check(!sealed && error == null)
            check(watches.filterValues { it.path == accessPath }.values.single().current)
            check(watches.filterValues { it.path == knowledgePath }.values.single().current)
        }
        fun awaitDisabledDocument() {
            check(disabledTarget.await(20, TimeUnit.SECONDS)) { "No real disabled document on sealed access target" }
        }
        fun snapshot(): SessionAccessSnapshot = synchronized(lock) {
            SessionAccessSnapshot(sealed, accessKey?.id, knowledgeKey?.id,
                disabledDocuments, knowledgeDenials, error)
        }
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
        val call = GuardedCall(delegate, LISTEN, Executor { it.run() })
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
        // Firestore 26.5.0 supplies its own AsyncQueue executor in the original CallOptions.
        // Early local closes bypass the delegate and must preserve that same callback contract.
        val callbackExecutor = checkNotNull(options.executor) { "Firestore call executor is required" }
        return GuardedCall(next.newCall(method, options), method.fullMethodName, callbackExecutor)
            .also { calls.add(it) }
    }

    /**
     * Defer real start (and thus CallCredentials/headers) until the FIRST typed RPC
     * names our exact demo database. The global supplier cannot inspect the SDK's
     * original DatabaseInfo; unrelated/default-app RPCs therefore fail locally,
     * rather than being blindly forwarded to the emulator. No headers are logged.
     */
    private inner class GuardedCall<ReqT, RespT>(
        private val delegate: ClientCall<ReqT, RespT>, private val method: String,
        private val callbackExecutor: Executor,
    ) : ClientCall<ReqT, RespT>() {
        private val lock = Any()
        private var listener: Listener<RespT>? = null
        private var headers: Metadata? = null
        private var started = false
        private var finished = false
        private var localClose: Status? = null
        private var localCloseScheduled = false
        private var pendingRequests = 0
        private var suspended = false
        private var pendingHalfClose = false
        private val messages = mutableListOf<ReqT>()

        override fun start(responseListener: Listener<RespT>, requestHeaders: Metadata) = synchronized(lock) {
            check(listener == null)
            listener = responseListener; headers = requestHeaders
            val alreadyClosed = localClose
            if (alreadyClosed != null) scheduleLocalCloseLocked()
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
                    override fun onMessage(message: RespT) {
                        if (method == LISTEN && message is ListenResponse) {
                            knowledgeRevocationAudit?.inbound(this@GuardedCall, message)
                            sessionAccessAudit?.inbound(this@GuardedCall, message)
                        }
                        super.onMessage(message)
                    }
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
            } else {
                if (method == LISTEN && message is ListenRequest) knowledgeRevocationAudit?.outbound(this, message)
                if (method == LISTEN && message is ListenRequest) sessionAccessAudit?.outbound(this, message)
                delegate.sendMessage(message)
            }
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
            scheduleLocalCloseLocked()
        }

        // Called only while holding lock. A call locally closed before start is delivered once
        // when its listener is supplied. Never fall back to the cleanup/caller thread if rejected.
        private fun scheduleLocalCloseLocked() {
            if (localCloseScheduled) return
            val status = localClose ?: return
            val responseListener = listener ?: return
            localCloseScheduled = true
            callbackExecutor.execute { responseListener.onClose(status, Metadata()) }
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
