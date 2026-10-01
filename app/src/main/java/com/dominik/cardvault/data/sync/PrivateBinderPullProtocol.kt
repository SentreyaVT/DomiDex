package com.dominik.cardvault.data.sync

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/** Pure local protocol. It performs no Firebase, Room, clock, authentication or network work. */
internal const val PRIVATE_PULL_PAGE_SIZE = 200
internal const val PRIVATE_PULL_RUN_ROWS = 1_000
private val privatePullDocumentId = Regex("[A-Za-z0-9._-]{1,128}")

internal data class PrivatePullStamp(val seconds: Long, val nanoseconds: Int) : Comparable<PrivatePullStamp> {
    init {
        // Firestore Timestamp's representable range, NOT a comparison with the device clock.
        require(seconds in -62_135_596_800L..253_402_300_799L)
        require(nanoseconds in 0..999_999_999)
    }

    override fun compareTo(other: PrivatePullStamp): Int =
        seconds.compareTo(other.seconds).takeIf { it != 0 } ?: nanoseconds.compareTo(other.nanoseconds)
}

internal data class PrivatePullCursor(val stamp: PrivatePullStamp, val documentId: String) : Comparable<PrivatePullCursor> {
    init { require(privatePullDocumentId.matches(documentId)) }
    override fun compareTo(other: PrivatePullCursor): Int =
        stamp.compareTo(other.stamp).takeIf { it != 0 } ?: documentId.compareTo(other.documentId)
}

internal enum class PrivatePullCollection(val cloudPath: String) {
    BINDERS("privateBinders"), PLACEMENTS("privateBinderPlacements"),
    LAYOUTS("collectionLayouts"), PRICE_SELECTIONS("collectionPriceSelections"),
}

internal data class PrivatePullScope(val ownerUid: String, val collection: PrivatePullCollection) {
    init { require(ownerUid.isNotBlank() && ownerUid.length <= 128 && '\u0000' !in ownerUid) }
}

/** Runtime generation must change on every account transition, including A -> B -> A. */
internal data class PrivatePullSessionLease(val ownerUid: String, val generation: Long) {
    init { require(ownerUid.isNotBlank() && generation >= 0) }
}

internal sealed interface PrivatePullPhase {
    data object NeedsBootstrapFence : PrivatePullPhase
    data class Bootstrap(val lowerFence: PrivatePullStamp?, val afterId: String?) : PrivatePullPhase {
        init { require(afterId == null || privatePullDocumentId.matches(afterId)) }
    }
    data class Idle(val lowerInclusive: PrivatePullStamp?) : PrivatePullPhase
    data class Delta(
        val lowerInclusive: PrivatePullStamp?,
        val upper: PrivatePullCursor,
        val after: PrivatePullCursor?,
    ) : PrivatePullPhase {
        init {
            require(lowerInclusive == null || lowerInclusive <= upper.stamp)
            require(after == null || (after <= upper && (lowerInclusive == null || after.stamp >= lowerInclusive)))
        }
    }
}

internal data class PrivatePullState(
    val scope: PrivatePullScope,
    val revision: Long = 0,
    val phase: PrivatePullPhase = PrivatePullPhase.NeedsBootstrapFence,
    val epoch: String = UUID.randomUUID().toString(),
) {
    init { require(revision >= 0 && privatePullDocumentId.matches(epoch)) }
    internal fun advance(phase: PrivatePullPhase = this.phase): PrivatePullState =
        copy(revision = Math.addExact(revision, 1L), phase = phase)
}

/** Keep the original raw value, including corrupt values: recovery must also use compare-and-set. */
internal data class PrivatePullCheckpoint(val raw: String?, val state: PrivatePullState, val recovered: Boolean)

internal sealed interface PrivatePullPlan {
    data object BudgetExhausted : PrivatePullPlan
    data class CaptureFence(val state: PrivatePullState) : PrivatePullPlan
    sealed interface Page : PrivatePullPlan {
        val state: PrivatePullState
        val limit: Int
    }
    data class BootstrapPage(override val state: PrivatePullState, override val limit: Int) : Page
    data class DeltaPage(override val state: PrivatePullState, override val limit: Int) : Page
}

/** The caller shares a single remaining-row budget across all four groups and rechecks. */
internal fun privatePullPlan(state: PrivatePullState, remainingRows: Int): PrivatePullPlan {
    require(remainingRows in 0..PRIVATE_PULL_RUN_ROWS)
    if (remainingRows == 0) return PrivatePullPlan.BudgetExhausted
    val limit = minOf(remainingRows, PRIVATE_PULL_PAGE_SIZE)
    return when (state.phase) {
        PrivatePullPhase.NeedsBootstrapFence, is PrivatePullPhase.Idle -> PrivatePullPlan.CaptureFence(state)
        is PrivatePullPhase.Bootstrap -> PrivatePullPlan.BootstrapPage(state, limit)
        is PrivatePullPhase.Delta -> PrivatePullPlan.DeltaPage(state, limit)
    }
}

/**
 * CONFIRMED_SERVER is an adapter assertion, not something this pure protocol can establish.
 * Source.SERVER alone is insufficient (local pending-write overlays still exist). The adapter
 * must reject malformed/untrusted timestamps; Android's public SDK has no server readTime.
 * Device-clock plausibility alone must never upgrade an untrusted result to trusted.
 */
internal enum class PrivatePullReadTrust { CONFIRMED_SERVER, UNTRUSTED }
internal data class PrivatePullFenceRead(val upper: PrivatePullCursor?, val trust: PrivatePullReadTrust)

/** A null stamp represents a confirmed legacy missing field, and is permitted ONLY in bootstrap. */
internal data class PrivatePullDocument<T>(
    val documentId: String,
    val stamp: PrivatePullStamp?,
    val payload: T,
    val hasPendingWrites: Boolean = false,
) {
    init { require(privatePullDocumentId.matches(documentId)) }
    fun cursor(): PrivatePullCursor = PrivatePullCursor(requireNotNull(stamp), documentId)
}

internal data class PrivatePullPage<T>(
    val request: PrivatePullPlan.Page,
    val documents: List<PrivatePullDocument<T>>,
    val trust: PrivatePullReadTrust,
)

internal fun privatePullAfterFence(state: PrivatePullState, read: PrivatePullFenceRead): PrivatePullState {
    require(read.trust == PrivatePullReadTrust.CONFIRMED_SERVER) { "untrusted_fence" }
    val phase = when (val current = state.phase) {
        PrivatePullPhase.NeedsBootstrapFence -> PrivatePullPhase.Bootstrap(read.upper?.stamp, null)
        is PrivatePullPhase.Idle -> {
            val upper = read.upper
            if (upper == null) current // Never replace a known floor with device time or zero.
            else {
                require(current.lowerInclusive == null || upper.stamp >= current.lowerInclusive) { "fence_behind_checkpoint" }
                PrivatePullPhase.Delta(current.lowerInclusive, upper, null)
            }
        }
        else -> error("fence_not_expected")
    }
    return state.advance(phase)
}

/** Validate the entire page before any local side effect. A failed read has no transition. */
internal fun <T> privatePullAfterPage(state: PrivatePullState, page: PrivatePullPage<T>): PrivatePullState {
    require(page.trust == PrivatePullReadTrust.CONFIRMED_SERVER) { "untrusted_page" }
    require(page.request.state == state) { "stale_page" }
    require(page.request.limit in 1..PRIVATE_PULL_PAGE_SIZE)
    require(page.documents.size <= page.request.limit) { "oversized_page" }
    require(page.documents.none { it.hasPendingWrites }) { "pending_write_overlay" }
    val phase = when (val current = state.phase) {
        is PrivatePullPhase.Bootstrap -> {
            require(page.request is PrivatePullPlan.BootstrapPage)
            var previous = current.afterId
            for (document in page.documents) {
                require(previous == null || document.documentId > previous) { "unordered_bootstrap_page" }
                previous = document.documentId
            }
            if (page.documents.size < page.request.limit) PrivatePullPhase.Idle(current.lowerFence)
            else current.copy(afterId = previous)
        }
        is PrivatePullPhase.Delta -> {
            require(page.request is PrivatePullPlan.DeltaPage)
            var previous = current.after
            for (document in page.documents) {
                val cursor = document.cursor()
                require(current.lowerInclusive == null || cursor.stamp >= current.lowerInclusive) { "below_lower_fence" }
                require(cursor <= current.upper) { "above_upper_fence" }
                require(previous == null || cursor > previous) { "unordered_delta_page" }
                previous = cursor
            }
            if (page.documents.size < page.request.limit || previous == current.upper) {
                // The next pass starts at this TIMESTAMP inclusively, not after its last ID.
                PrivatePullPhase.Idle(current.upper.stamp)
            } else current.copy(after = previous)
        }
        else -> error("page_not_expected")
    }
    return state.advance(phase)
}

internal enum class PrivatePullDeferReason { LOCAL_MUTATION, BINDER_DEPENDENCY, COLLECTION_DEPENDENCY }
internal sealed interface PrivatePullApplyDecision {
    /** Includes idempotently current or explicitly, permanently invalid payloads; not network failures. */
    data object Complete : PrivatePullApplyDecision
    data class Defer(val reason: PrivatePullDeferReason, val dependencyId: String? = null) : PrivatePullApplyDecision {
        init {
            require(dependencyId == null || privatePullDocumentId.matches(dependencyId))
            require((reason == PrivatePullDeferReason.LOCAL_MUTATION) == (dependencyId == null))
        }
    }
}

/** Minimal local metadata, never a remote payload or an authorization grant. */
internal data class PrivatePullDeferredMarker(
    val scope: PrivatePullScope,
    val documentId: String,
    val version: Long,
    val reason: PrivatePullDeferReason,
    val dependencyId: String? = null,
    val epoch: String = UUID.randomUUID().toString(),
) {
    init {
        require(privatePullDocumentId.matches(documentId) && version >= 0)
        require(privatePullDocumentId.matches(epoch))
        PrivatePullApplyDecision.Defer(reason, dependencyId)
    }
}

internal enum class PrivatePullCommitResult { COMMITTED, STALE_CHECKPOINT, STALE_SESSION, STALE_MARKER }
internal interface PrivatePullTransaction {
    suspend fun putDeferred(marker: PrivatePullDeferredMarker)
    suspend fun removeDeferred(documentId: String)
}

/**
 * Adapter contract: read the exact raw checkpoint and validate the current owner AND generation
 * before invoking changes. When supplied, compare the entire expectedMarker as well. Execute
 * changes (including the caller's local apply callback), marker writes and the returned state
 * write in ONE local transaction; validate the lease again before commit. Any exception or stale
 * lease rolls EVERYTHING back. A stale guard must not invoke changes. Never perform network work
 * inside this transaction. The returned state must have the same scope, epoch and revision + 1.
 * Validate recovery using decode(expected.raw, scope, expected.state.epoch), never a second UUID.
 * A real Room adapter needs its own transaction tests; a fake cannot prove Room integration.
 */
internal interface PrivatePullStorePort {
    suspend fun compareAndCommit(
        expected: PrivatePullCheckpoint,
        lease: PrivatePullSessionLease,
        expectedMarker: PrivatePullDeferredMarker? = null,
        changes: suspend PrivatePullTransaction.() -> PrivatePullState,
    ): PrivatePullCommitResult
}

internal suspend fun privatePullCommitFence(
    store: PrivatePullStorePort,
    expected: PrivatePullCheckpoint,
    lease: PrivatePullSessionLease,
    read: PrivatePullFenceRead,
): PrivatePullCommitResult {
    val next = privatePullAfterFence(expected.state, read)
    require(lease.ownerUid == expected.state.scope.ownerUid)
    return store.compareAndCommit(expected, lease) { next }
}

internal suspend fun <T> privatePullCommitPage(
    store: PrivatePullStorePort,
    expected: PrivatePullCheckpoint,
    lease: PrivatePullSessionLease,
    page: PrivatePullPage<T>,
    apply: suspend (PrivatePullDocument<T>) -> PrivatePullApplyDecision,
): PrivatePullCommitResult {
    val stablePage = page.copy(documents = page.documents.toList())
    val next = privatePullAfterPage(expected.state, stablePage)
    require(lease.ownerUid == expected.state.scope.ownerUid)
    return store.compareAndCommit(expected, lease) {
        for (document in stablePage.documents) {
            when (val result = apply(document)) {
                PrivatePullApplyDecision.Complete -> removeDeferred(document.documentId)
                is PrivatePullApplyDecision.Defer -> putDeferred(PrivatePullDeferredMarker(
                    next.scope, document.documentId, next.revision, result.reason, result.dependencyId, next.epoch,
                ))
            }
        }
        next
    }
}

/** A recheck never moves the page cursor. An ACK alone must never remove a marker. */
internal suspend fun <T> privatePullCommitRecheck(
    store: PrivatePullStorePort,
    expected: PrivatePullCheckpoint,
    lease: PrivatePullSessionLease,
    marker: PrivatePullDeferredMarker,
    document: PrivatePullDocument<T>,
    trust: PrivatePullReadTrust,
    apply: suspend (PrivatePullDocument<T>) -> PrivatePullApplyDecision,
): PrivatePullCommitResult {
    require(marker.scope == expected.state.scope && marker.documentId == document.documentId)
    require(lease.ownerUid == marker.scope.ownerUid)
    require(trust == PrivatePullReadTrust.CONFIRMED_SERVER && !document.hasPendingWrites)
    val next = expected.state.advance()
    return store.compareAndCommit(expected, lease, marker) {
        when (val result = apply(document)) {
            PrivatePullApplyDecision.Complete -> removeDeferred(document.documentId)
            is PrivatePullApplyDecision.Defer -> putDeferred(marker.copy(
                version = next.revision, reason = result.reason, dependencyId = result.dependencyId, epoch = next.epoch,
            ))
        }
        next
    }
}

/** Strict, versioned, clock-free metadata codec. No schema/database migration is needed. */
internal object PrivatePullCodec {
    private fun owner(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun decodeOwner(value: String): String = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    private fun stamp(seconds: String, nanos: String): PrivatePullStamp? = when {
        seconds.isEmpty() && nanos.isEmpty() -> null
        else -> PrivatePullStamp(seconds.toLong(), nanos.toInt())
    }
    private fun cursor(seconds: String, nanos: String, id: String): PrivatePullCursor? = when {
        seconds.isEmpty() && nanos.isEmpty() && id.isEmpty() -> null
        else -> PrivatePullCursor(requireNotNull(stamp(seconds, nanos)), id)
    }

    fun encode(state: PrivatePullState): String {
        var floor: PrivatePullStamp? = null
        var upper: PrivatePullCursor? = null
        var after: PrivatePullCursor? = null
        var afterId: String? = null
        val phase = when (val value = state.phase) {
            PrivatePullPhase.NeedsBootstrapFence -> "fence"
            is PrivatePullPhase.Bootstrap -> { floor = value.lowerFence; afterId = value.afterId; "bootstrap" }
            is PrivatePullPhase.Idle -> { floor = value.lowerInclusive; "idle" }
            is PrivatePullPhase.Delta -> {
                floor = value.lowerInclusive; upper = value.upper; after = value.after; afterId = value.after?.documentId; "delta"
            }
        }
        return listOf("2", owner(state.scope.ownerUid), state.scope.collection.name, state.revision, phase,
            floor?.seconds ?: "", floor?.nanoseconds ?: "", upper?.stamp?.seconds ?: "",
            upper?.stamp?.nanoseconds ?: "", upper?.documentId ?: "", after?.stamp?.seconds ?: "",
            after?.stamp?.nanoseconds ?: "", afterId ?: "", state.epoch).joinToString("|")
    }

    fun decode(
        raw: String?,
        scope: PrivatePullScope,
        recoveryEpoch: String = UUID.randomUUID().toString(),
    ): PrivatePullCheckpoint {
        require(privatePullDocumentId.matches(recoveryEpoch))
        if (raw == null) return PrivatePullCheckpoint(null, PrivatePullState(scope, epoch = recoveryEpoch), recovered = false)
        val state = runCatching {
            require(raw.length <= 1_024)
            val p = raw.split('|')
            require(p.size == 14 && p[0] == "2")
            require(PrivatePullScope(decodeOwner(p[1]), PrivatePullCollection.valueOf(p[2])) == scope)
            val revision = p[3].toLong()
            require(revision < Long.MAX_VALUE) // Exhausted or poisoned state gets CAS-protected bootstrap repair.
            val floor = stamp(p[5], p[6])
            val phase = when (p[4]) {
                "fence" -> PrivatePullPhase.NeedsBootstrapFence
                "bootstrap" -> PrivatePullPhase.Bootstrap(floor, p[12].ifEmpty { null })
                "idle" -> PrivatePullPhase.Idle(floor)
                "delta" -> PrivatePullPhase.Delta(floor, requireNotNull(cursor(p[7], p[8], p[9])), cursor(p[10], p[11], p[12]))
                else -> error("unknown_phase")
            }
            PrivatePullState(scope, revision, phase, epoch = p[13]).also { require(encode(it) == raw) }
        }.getOrNull()
        return PrivatePullCheckpoint(raw, state ?: PrivatePullState(scope, epoch = recoveryEpoch), recovered = state == null)
    }

    fun encodeMarker(marker: PrivatePullDeferredMarker): String = listOf(
        "2", owner(marker.scope.ownerUid), marker.scope.collection.name, marker.documentId,
        marker.version, marker.reason.name, marker.dependencyId ?: "", marker.epoch,
    ).joinToString("|")

    fun decodeMarker(raw: String, scope: PrivatePullScope): PrivatePullDeferredMarker? = runCatching {
        require(raw.length <= 1_024)
        val p = raw.split('|')
        require(p.size == 8 && p[0] == "2")
        require(PrivatePullScope(decodeOwner(p[1]), PrivatePullCollection.valueOf(p[2])) == scope)
        PrivatePullDeferredMarker(scope, p[3], p[4].toLong(), PrivatePullDeferReason.valueOf(p[5]), p[6].ifEmpty { null }, p[7])
            .also { require(encodeMarker(it) == raw) }
    }.getOrNull()
}
