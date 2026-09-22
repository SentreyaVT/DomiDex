package com.dominik.cardvault.ui

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One terminal outcome even when cancellation, a watchdog and native callbacks race. */
internal class ScanTaskCompletion<T>(private val onComplete: (T) -> Unit) {
    private val finished = AtomicBoolean(false)
    val isFinished: Boolean get() = finished.get()

    fun complete(value: T): Boolean {
        if (!finished.compareAndSet(false, true)) return false
        onComplete(value)
        return true
    }
}

/**
 * A cancelled ML Kit task may still read its input. Keep only those leased resources alive until
 * its completion callback, while releasing pending crops immediately. Identity (not equality)
 * matters because normalisation can return the same bitmap for multiple roles.
 */
internal class ScanTaskResources<T : Any>(private val dispose: (T) -> Unit) {
    private val owned = IdentityHashMap<T, Int>()
    private var closed = false

    @Synchronized fun own(resource: T): T {
        if (closed) dispose(resource) else if (!owned.containsKey(resource)) owned[resource] = 0
        return resource
    }

    @Synchronized fun lease(resource: T): Boolean {
        if (closed || !owned.containsKey(resource)) return false
        owned[resource] = owned.getValue(resource) + 1
        return true
    }

    @Synchronized fun releaseLease(resource: T) {
        val count = owned[resource] ?: return
        check(count > 0) { "Scanner resource lease released twice" }
        owned[resource] = count - 1
        if (closed && count == 1) {
            owned.remove(resource)
            dispose(resource)
        }
    }

    @Synchronized fun release(resource: T) {
        val count = owned[resource] ?: return
        check(count == 0) { "Cannot release an in-flight scanner input" }
        owned.remove(resource)
        dispose(resource)
    }

    @Synchronized fun transfer(resource: T) {
        val count = owned[resource] ?: return
        check(count == 0) { "Cannot transfer an in-flight scanner input" }
        owned.remove(resource)
    }

    /** Called after the preprocessing task stops, so no unleased resource is still being read. */
    @Synchronized fun close(retain: T? = null) {
        closed = true
        if (retain != null) owned.remove(retain)
        val releasable = owned.entries.filter { it.value == 0 }.map { it.key }
        releasable.forEach { owned.remove(it); dispose(it) }
    }
}
