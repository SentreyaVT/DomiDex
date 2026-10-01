package com.dominik.cardvault.data.sync

internal enum class PrivateDependencyReadAllowance {
    Allowed,
    AlreadyAttempted,
    BudgetExhausted,
}

/**
 * One owner's single, sequential FULL-pull invocation. Never share across runs or auth sessions.
 * Counts logical document read units, NOT billed Firestore reads, SDK retries or push transactions.
 * Before each query use nextPageSize; immediately after await (before applying any result) call
 * recordPageResult. No dependency reservation may interleave that sequential query/record pair.
 * A failed query ends its caller's run; it must not silently continue with an unaccounted query.
 */
internal class PrivateFullPullReadBudget(
    val ownerUid: String,
    private val limit: Int = 1_000,
) {
    init {
        require(validFullPullOwner(ownerUid))
        require(limit in 1..MAX_LOGICAL_READS)
    }

    private data class Dependency(val type: PrivateRemoteDependencyType, val id: String)
    private val attemptedDependencies = mutableSetOf<Dependency>()
    var remainingReads: Int = limit
        private set
    val usedReads: Int get() = limit - remainingReads
    val attemptedDependencyCount: Int get() = attemptedDependencies.size

    /** Zero means stop before constructing another network request, even between empty phases. */
    fun nextPageSize(maxPageSize: Int): Int {
        require(maxPageSize > 0)
        return minOf(maxPageSize, remainingReads)
    }

    /** Charge the whole fetched page, including documents not yet applied at an early stop. */
    fun recordPageResult(requestedSize: Int, returnedDocuments: Int) {
        require(requestedSize in 1..remainingReads)
        require(returnedDocuments in 0..requestedSize)
        remainingReads -= maxOf(1, returnedDocuments)
    }

    /**
     * Call BEFORE a point read, and do not refund absent/blocked documents. AlreadyAttempted
     * permits the FULL scan's ordinary deferred progression; BudgetExhausted does NOT: preserve
     * the bookmark before the current document and resume in a fresh bounded invocation instead.
     * Always recheck Room first, since another local worker may have supplied the parent meanwhile.
     */
    fun reserveDependency(type: PrivateRemoteDependencyType, documentId: String): PrivateDependencyReadAllowance {
        require(documentId.matches(REMOTE_ID))
        val dependency = Dependency(type, documentId)
        if (dependency in attemptedDependencies) return PrivateDependencyReadAllowance.AlreadyAttempted
        if (remainingReads == 0) return PrivateDependencyReadAllowance.BudgetExhausted
        attemptedDependencies.add(dependency)
        remainingReads--
        return PrivateDependencyReadAllowance.Allowed
    }

    private companion object {
        const val MAX_LOGICAL_READS = 10_000
        val REMOTE_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}

internal sealed interface PrivateFullPullFlightResult<out T> {
    data class Ran<T>(val value: T) : PrivateFullPullFlightResult<T>
    data object OwnerBusy : PrivateFullPullFlightResult<Nothing>
    data object CapacityReached : PrivateFullPullFlightResult<Nothing>
}

/**
 * Process-local, non-waiting coalescing ONLY for FULL pulls, from initial bookmark read through
 * final bookmark write. Install one shared instance, never one per worker/run. Do not wrap push
 * or priority-sharing work. No cross-process exclusion, persisted lease, queue or historical cache.
 * OwnerBusy means an existing pull owns progress; CapacityReached is distinct so its caller can
 * retain a bounded retry rather than assuming that this particular owner is already being pulled.
 */
internal class PrivateFullPullSingleFlight(private val maxConcurrentOwners: Int = 8) {
    init { require(maxConcurrentOwners in 1..64) }

    private val monitor = Any()
    private val activeOwners = mutableMapOf<String, Any>()
    val activeOwnerCount: Int get() = synchronized(monitor) { activeOwners.size }

    suspend fun <T> tryRun(ownerUid: String, block: suspend () -> T): PrivateFullPullFlightResult<T> {
        require(validFullPullOwner(ownerUid))
        val token = Any()
        val skipped = synchronized(monitor) {
            when {
                ownerUid in activeOwners -> PrivateFullPullFlightResult.OwnerBusy
                activeOwners.size >= maxConcurrentOwners -> PrivateFullPullFlightResult.CapacityReached
                else -> {
                    activeOwners[ownerUid] = token
                    null
                }
            }
        }
        if (skipped != null) return skipped
        try {
            return PrivateFullPullFlightResult.Ran(block())
        } finally {
            // No suspension: cancellation and errors cannot leave a retained owner entry.
            synchronized(monitor) {
                if (activeOwners[ownerUid] === token) activeOwners.remove(ownerUid)
            }
        }
    }
}

private fun validFullPullOwner(owner: String): Boolean = owner.isNotBlank() && owner.length <= 128 && '\u0000' !in owner
