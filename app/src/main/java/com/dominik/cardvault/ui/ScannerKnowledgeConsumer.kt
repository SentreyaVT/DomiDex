package com.dominik.cardvault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.dominik.cardvault.data.knowledge.ScanKnowledgeRecord
import com.dominik.cardvault.data.sync.CollectionCloudState
import com.dominik.cardvault.data.sync.CollectionCloudStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/** Only app-backend Community Knowledge. Pretester Reviewed Knowledge is a separate permission. */
internal interface ScannerKnowledgeSource {
    fun currentOwner(): String?
    fun cached(fingerprint: String): ScanKnowledgeRecord?
    fun observe(fingerprint: String, update: (ScanKnowledgeRecord?) -> Unit): AutoCloseable
}

/**
 * The real ScannerScreen community-hint consumer, one capture per generation.
 * External listeners are NEVER closed under lock: their callbacks have their own locks.
 * Only an observed same-owner NO_ACCESS revokes the community hint. Offline is not denial.
 * Denial stays latched for this consumer's lifetime: this coarse Flow cannot prove a regrant,
 * and missing accountAccess / accessConfirmed alone must not resurrect the cached hint.
 */
internal class ScannerKnowledgeConsumer(private val source: ScannerKnowledgeSource) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val captures = mutableSetOf<Capture>()
    private val deniedOwners = mutableSetOf<String>()

    fun onCloudState(state: CollectionCloudState) {
        val releasing = synchronized(lock) {
            val owner = source.currentOwner()
            if (!closed && owner != null && state.ownerUid == owner &&
                state.status == CollectionCloudStatus.NO_ACCESS) deniedOwners.add(owner)
            captures.filter { it.owner != owner || it.owner in deniedOwners }
                .flatMap { it.invalidateLocked() }
        }
        closeOutsideLock(releasing)
    }

    fun begin(fingerprint: String?): Capture {
        val capture = synchronized(lock) {
            Capture(source.currentOwner()).also {
                if (closed || it.owner == null || it.owner in deniedOwners) it.invalid = true else captures.add(it)
            }
        }
        try {
            if (fingerprint != null && capture.active()) {
                capture.update(source.cached(fingerprint))
                if (capture.active()) capture.attach(source.observe(fingerprint, capture::update))
            }
            return capture
        } catch (failure: Throwable) {
            capture.close()
            throw failure
        }
    }

    inner class Capture internal constructor(internal val owner: String?) : AutoCloseable {
        internal var invalid = false
        private var value: ScanKnowledgeRecord? = null
        private var registration: AutoCloseable? = null

        internal fun active(): Boolean = synchronized(lock) {
            !closed && !invalid && owner != null && source.currentOwner() == owner
        }
        internal fun update(record: ScanKnowledgeRecord?) {
            val releasing = synchronized(lock) {
                if (active()) {
                    value = record
                    emptyList()
                } else {
                    // A genuine auth-close callback can arrive while a different owner
                    // is current. Retire this generation now; merely ignoring its null
                    // would resurrect the old value after a quick A -> B -> A transition.
                    invalidateLocked()
                }
            }
            closeOutsideLock(releasing)
        }
        internal fun attach(handle: AutoCloseable) {
            val release = synchronized(lock) {
                if (active()) { check(registration == null); registration = handle; false } else true
            }
            if (release) closeOutsideLock(listOf(handle))
        }

        /** The value consumed by the actual production Community Knowledge ranker. */
        fun current(): ScanKnowledgeRecord? {
            val (record, releasing) = synchronized(lock) {
                if (active()) value to emptyList() else null to invalidateLocked()
            }
            closeOutsideLock(releasing)
            return record
        }
        internal fun invalidateLocked(): List<AutoCloseable> {
            invalid = true
            value = null
            captures.remove(this)
            return listOfNotNull(registration).also { registration = null }
        }
        override fun close() {
            val releasing = synchronized(lock) { invalidateLocked() }
            closeOutsideLock(releasing)
        }
    }

    override fun close() {
        val releasing = synchronized(lock) {
            closed = true
            captures.toList().flatMap { it.invalidateLocked() }
        }
        closeOutsideLock(releasing)
    }

    private fun closeOutsideLock(registrations: List<AutoCloseable>) {
        registrations.forEach { registration ->
            try { registration.close() } catch (_: Exception) { /* Preserve invalidation and release other generations. */ }
        }
    }
}

/** Used by ScannerScreen itself AND the Compose wiring regression. No second Firebase listener. */
@Composable
internal fun rememberScannerKnowledgeConsumer(
    cloudState: Flow<CollectionCloudState>,
    source: ScannerKnowledgeSource,
): ScannerKnowledgeConsumer {
    val consumer = remember(cloudState, source) { ScannerKnowledgeConsumer(source) }
    LaunchedEffect(consumer, cloudState) { cloudState.collect(consumer::onCloudState) }
    DisposableEffect(consumer) { onDispose { consumer.close() } }
    return consumer
}
