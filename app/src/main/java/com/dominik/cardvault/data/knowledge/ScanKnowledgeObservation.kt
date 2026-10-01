package com.dominik.cardvault.data.knowledge

/** One exact-document observation; no timers, Firebase access, scoring or cross-owner persistence. */
internal class ScanKnowledgeObservation(
    private val fingerprint: String,
    private val ownerUid: String,
    private val currentOwner: () -> String?,
    private val now: () -> Long,
    private val readCache: () -> ScanKnowledgeRecord?,
    private val writeCache: (ScanKnowledgeRecord) -> Unit,
    private val removeCache: (String) -> Unit,
    private val onUpdate: (ScanKnowledgeRecord?) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var hintInvalidated = false
    private var transportStopped = false
    private var initialAuthEventSeen = false
    private val cleanup = mutableListOf<() -> Unit>()
    private val transportCleanup = mutableListOf<() -> Unit>()

    fun publishCached() = synchronized(lock) {
        if (!activeLocked()) return@synchronized
        val record = readCache()
        val usable = record?.takeIf { isUsableScanKnowledge(it, now(), fingerprint) }
        if (record != null && usable == null) removeCache(fingerprint)
        publishHint(usable)
    }

    fun onSnapshot(exists: Boolean, isFromCache: Boolean, hasPendingWrites: Boolean,
        decode: () -> ScanKnowledgeRecord?) = synchronized(lock) {
        if (!activeLocked() || transportStopped || isFromCache || hasPendingWrites) return@synchronized
        val record = if (exists) try { decode() } catch (_: Exception) { null } else null
        val usable = record?.takeIf { isUsableScanKnowledge(it, now(), fingerprint) }
        if (usable == null) removeCache(fingerprint) else writeCache(usable)
        publishHint(usable)
    }

    fun onError() = publishCached()

    /** End network work; keep the auth watcher until normal close to protect the offline hint. */
    fun onTerminalError() = synchronized(lock) {
        if (!activeLocked() || transportStopped) return@synchronized
        publishCached()
        stopTransportLocked()
    }

    fun onAuthStateEvent() = synchronized(lock) {
        if (!activeLocked()) return@synchronized
        // FirebaseAuth itself is mutable. A delayed A -> B -> A event may already read A again.
        // Every observed event after the initial callback invalidates this generation permanently.
        if (initialAuthEventSeen) closeLocked() else initialAuthEventSeen = true
    }

    fun runIfActive(block: () -> Unit): Boolean = synchronized(lock) {
        if (!activeLocked() || transportStopped) return@synchronized false
        block()
        true
    }

    fun onClose(action: () -> Unit) = synchronized(lock) {
        if (closed) safelyCleanup(action) else cleanup.add(action)
        Unit
    }

    fun onTransportEnd(action: () -> Unit) = synchronized(lock) {
        if (closed || transportStopped) safelyCleanup(action) else transportCleanup.add(action)
        Unit
    }

    override fun close() = synchronized(lock) { closeLocked() }

    private fun activeLocked(): Boolean {
        if (!closed && currentOwner() != ownerUid) closeLocked()
        return !closed
    }

    private fun closeLocked() {
        if (!closed) {
            closed = true
            stopTransportLocked()
            val actions = cleanup.toList()
            cleanup.clear()
            actions.forEach(::safelyCleanup)
        }
        if (!hintInvalidated) publishHint(null)
    }

    private fun stopTransportLocked() {
        if (transportStopped) return
        transportStopped = true
        val actions = transportCleanup.toList()
        transportCleanup.clear()
        actions.forEach(::safelyCleanup)
    }

    private fun publishHint(record: ScanKnowledgeRecord?) {
        hintInvalidated = record == null
        onUpdate(record)
    }

    private fun safelyCleanup(action: () -> Unit) {
        try { action() } catch (_: Exception) { /* Continue removing the other observation resources. */ }
    }
}
