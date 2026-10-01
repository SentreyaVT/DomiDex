package com.dominik.cardvault.data.sync

/** Guarded by the owning session lock. A transport hint never grants account access. */
internal class CollectionReconnectGate {
    private var usable = false
    private var usableEpoch = 0L
    private var checkingGeneration: Long? = null
    private var attemptEpoch = 0L

    /** True only on a new usable edge; repeated capabilities do not start new checks. */
    fun observe(isUsable: Boolean): Boolean {
        if (usable == isUsable) return false
        usable = isUsable
        if (!isUsable) return false
        usableEpoch += 1L
        return true
    }

    fun started(generation: Long) {
        checkingGeneration = generation
        attemptEpoch = usableEpoch
    }

    /** Consume at most one follow-up for hints received during this actual attempt. */
    fun consumeAfterTransientFailure(generation: Long): Boolean {
        if (checkingGeneration != generation || !usable || usableEpoch == attemptEpoch) return false
        attemptEpoch = usableEpoch
        return true
    }

    fun clear() {
        checkingGeneration = null
    }
}
