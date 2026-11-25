package com.dominik.cardvault.ui

/**
 * Thread-confined gate for low-cost live preview recognition. It accepts only a small number of
 * stable, complete card frames and deliberately remembers the last accepted card fingerprint.
 * The gate has no catalogue, camera or persistence dependency, so a preview result can never
 * bypass the existing scanner queue or collector confirmation.
 */
internal class LivePreviewFrameGate(
    private val readyFramesToProcess: Int = 3,
    private val missingFramesToRearm: Int = 3,
    private val minimumIntervalMillis: Long = 900L,
    private val minimumNewCardDifference: Float = 8f,
) {
    init {
        require(readyFramesToProcess > 0)
        require(missingFramesToRearm > 0)
        require(minimumIntervalMillis >= 0L)
    }

    private var lastSequence = Long.MIN_VALUE
    private var readyFrames = 0
    private var missingFrames = 0
    private var inFlight = false
    private var lastAcceptedAt = Long.MIN_VALUE
    private var acceptedFingerprint: IntArray? = null

    /** Returns true exactly once for a stable, new presentation that may be sampled. */
    fun shouldProcess(
        eligible: Boolean,
        fingerprint: IntArray?,
        frameSequence: Long,
        nowMillis: Long,
    ): Boolean {
        if (frameSequence <= lastSequence) return false
        lastSequence = frameSequence
        if (!eligible || fingerprint == null || fingerprint.isEmpty()) {
            readyFrames = 0
            missingFrames += 1
            if (missingFrames >= missingFramesToRearm) acceptedFingerprint = null
            return false
        }
        missingFrames = 0
        if (inFlight) return false
        val prior = acceptedFingerprint
        if (prior != null && !isDistinctCardPresentation(prior, fingerprint, minimumNewCardDifference)) {
            readyFrames = 0
            return false
        }
        readyFrames += 1
        if (readyFrames < readyFramesToProcess ||
            (lastAcceptedAt != Long.MIN_VALUE && nowMillis - lastAcceptedAt < minimumIntervalMillis)
        ) return false
        readyFrames = 0
        inFlight = true
        acceptedFingerprint = fingerprint.copyOf()
        lastAcceptedAt = nowMillis
        return true
    }

    /** Releases the single preview slot after the frame was saved or rejected. */
    fun complete(success: Boolean) {
        inFlight = false
        if (!success) acceptedFingerprint = null
    }

    fun pause() {
        readyFrames = 0
        inFlight = false
    }
}

/** Uniform luminance shifts should not cause a held card to be recognized repeatedly. */
internal fun isDistinctCardPresentation(
    previous: IntArray,
    current: IntArray,
    minimumAverageDifference: Float,
): Boolean {
    if (previous.size != current.size || previous.isEmpty()) return true
    val baseline = previous.indices.sumOf { index -> kotlin.math.abs(previous[index] - current[index]) }
        .toFloat() / previous.size
    return baseline >= minimumAverageDifference
}
