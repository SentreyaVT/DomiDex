package com.dominik.cardvault.ui

/**
 * Compact, screen-local history for one scanner session. The live session is advanced linearly:
 * each update mutates its private indexes in O(1) and returns a fresh view for Compose state.
 * Explicit summaries copy the indexes so [withSkipped] remains a detached snapshot.
 */
internal class ScanAnalysisSession private constructor(
    private val index: ScanAnalysisIndex,
    private val limits: ScanAnalysisSessionLimits,
    val analyzed: Int,
    val unique: Int,
    val duplicates: Int,
    val review: Int,
    val skipped: Int,
    val added: Int,
) {
    constructor(limits: ScanAnalysisSessionLimits = ScanAnalysisSessionLimits()) :
        this(
            ScanAnalysisIndex(SessionIdentityBloom(limits.identityBloomBitCount, limits.identityBloomHashCount)),
            limits, 0, 0, 0, 0, 0, 0,
        )

    internal val trackedScanCount: Int get() = index.outcomes.size
    internal val trackedIdentityCount: Int get() = index.scansByIdentity.size
    internal val trackedAddedScanCount: Int get() = index.addedScanIds.size
    internal val identityGuardBytes: Int get() = index.identityGuard.byteSize

    /** Keep the tiny live queue exact even when a session runs far beyond the history horizon. */
    fun retainExactScanIds(scanIds: Collection<String>): ScanAnalysisSession {
        val retained = scanIds.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .take(limits.maxTrackedScanIds)
            .toCollection(LinkedHashSet())
        if (retained == index.retainedScanIds) return this
        index.retainedScanIds.clear()
        index.retainedScanIds.addAll(retained)
        trimRecentHistory()
        return view()
    }

    fun recordTerminal(
        scanId: String,
        stage: ScanPipelineStage,
        identityKey: String? = null,
    ): ScanAnalysisSession {
        if (scanId in index.outcomes) return this
        return when (stage) {
            ScanPipelineStage.MATCHED, ScanPipelineStage.SAVED ->
                identityKey?.takeIf(String::isNotBlank)?.let { registerIdentity(scanId, it).session }
                    ?: recordOutcome(scanId, ScanSessionOutcome.REVIEW)
            ScanPipelineStage.AMBIGUOUS, ScanPipelineStage.MANUAL_REVIEW ->
                recordOutcome(scanId, ScanSessionOutcome.REVIEW)
            ScanPipelineStage.FAILED ->
                recordOutcome(scanId, ScanSessionOutcome.SKIPPED)
            else -> this
        }
    }

    /**
     * The fixed Bloom guard cannot produce a false negative. A saturated or colliding guard may
     * produce a false-positive warning, which remains explicitly overridable by the collector.
     */
    fun isDuplicate(scanId: String, identityKey: String): Boolean {
        val key = identityKey.trim()
        if (key.isEmpty()) return false
        if (index.identityByScan[scanId] == key) {
            return index.outcomes[scanId] == ScanSessionOutcome.DUPLICATE
        }
        return index.identityGuard.mightContain(key)
    }

    fun registerIdentity(scanId: String, identityKey: String): ScanIdentityRegistration {
        val key = identityKey.trim()
        if (key.isEmpty()) return ScanIdentityRegistration(this, isDuplicate = false)

        val previousKey = index.identityByScan[scanId]
        if (previousKey == key) {
            return ScanIdentityRegistration(this, isDuplicate(scanId, key))
        }

        var nextUnique = unique
        var nextDuplicates = duplicates
        var nextReview = review
        var nextSkipped = skipped
        var nextAnalyzed = analyzed

        // Reclassification releases the recent exact mapping, but never deletes from the Bloom
        // guard. The old print was still observed in this session, so later scans keep warning.
        previousKey?.let { oldKey ->
            val oldScans = index.scansByIdentity.getValue(oldKey)
            oldScans.remove(scanId)
            if (oldScans.isEmpty()) {
                index.scansByIdentity.remove(oldKey)
            }
            index.identityByScan.remove(scanId)
        }

        index.outcomes.remove(scanId)?.let { previousOutcome ->
            when (previousOutcome) {
                ScanSessionOutcome.UNIQUE -> nextUnique -= 1
                ScanSessionOutcome.DUPLICATE -> nextDuplicates -= 1
                ScanSessionOutcome.REVIEW -> nextReview -= 1
                ScanSessionOutcome.SKIPPED -> nextSkipped -= 1
            }
        } ?: run { nextAnalyzed += 1 }

        val scansForIdentity = index.scansByIdentity.getOrPut(key) { linkedSetOf() }
        val duplicate = index.identityGuard.mightContain(key)
        index.identityGuard.add(key)
        scansForIdentity.add(scanId)
        index.identityByScan[scanId] = key
        val nextOutcome = if (duplicate) ScanSessionOutcome.DUPLICATE else ScanSessionOutcome.UNIQUE
        index.outcomes[scanId] = nextOutcome
        if (duplicate) nextDuplicates += 1 else nextUnique += 1
        trimRecentHistory()

        return ScanIdentityRegistration(
            session = view(
                analyzed = nextAnalyzed,
                unique = nextUnique,
                duplicates = nextDuplicates,
                review = nextReview,
                skipped = nextSkipped,
            ),
            isDuplicate = duplicate,
        )
    }

    fun recordAdded(scanId: String): ScanAnalysisSession {
        if (scanId.isBlank() || !index.addedScanIds.add(scanId)) return this
        while (index.addedScanIds.size > limits.maxTrackedAddedScanIds) {
            index.addedScanIds.iterator().run { next(); remove() }
        }
        return view(added = added + 1)
    }

    /** Explicitly discard one result while keeping lifetime totals internally consistent. */
    fun recordSkipped(scanId: String): ScanAnalysisSession {
        if (scanId.isBlank() || index.outcomes[scanId] == ScanSessionOutcome.SKIPPED) return this

        var nextAnalyzed = analyzed
        var nextUnique = unique
        var nextDuplicates = duplicates
        var nextReview = review
        index.outcomes.remove(scanId)?.let { previousOutcome ->
            when (previousOutcome) {
                ScanSessionOutcome.UNIQUE -> nextUnique -= 1
                ScanSessionOutcome.DUPLICATE -> nextDuplicates -= 1
                ScanSessionOutcome.REVIEW -> nextReview -= 1
                ScanSessionOutcome.SKIPPED -> Unit
            }
        } ?: run { nextAnalyzed += 1 }

        index.identityByScan.remove(scanId)?.let { identityKey ->
            index.scansByIdentity[identityKey]?.let { scanIds ->
                scanIds.remove(scanId)
                if (scanIds.isEmpty()) index.scansByIdentity.remove(identityKey)
            }
        }
        index.outcomes[scanId] = ScanSessionOutcome.SKIPPED
        trimRecentHistory()
        return view(
            analyzed = nextAnalyzed,
            unique = nextUnique,
            duplicates = nextDuplicates,
            review = nextReview,
            skipped = skipped + 1,
        )
    }

    fun wasAdded(scanId: String): Boolean = scanId in index.addedScanIds

    /** Preview used only when the collector explicitly closes while work is still running. */
    fun withSkipped(scanIds: Collection<String>): ScanAnalysisSession {
        var snapshot = ScanAnalysisSession(
            index = index.snapshot(),
            limits = limits,
            analyzed = analyzed,
            unique = unique,
            duplicates = duplicates,
            review = review,
            skipped = skipped,
            added = added,
        )
        scanIds.forEach { scanId ->
            snapshot = snapshot.recordTerminal(scanId, ScanPipelineStage.FAILED)
        }
        return snapshot
    }

    private fun recordOutcome(scanId: String, outcome: ScanSessionOutcome): ScanAnalysisSession {
        index.outcomes[scanId] = outcome
        trimRecentHistory()
        return view(
            analyzed = analyzed + 1,
            review = review + if (outcome == ScanSessionOutcome.REVIEW) 1 else 0,
            skipped = skipped + if (outcome == ScanSessionOutcome.SKIPPED) 1 else 0,
        )
    }

    private fun view(
        analyzed: Int = this.analyzed,
        unique: Int = this.unique,
        duplicates: Int = this.duplicates,
        review: Int = this.review,
        skipped: Int = this.skipped,
        added: Int = this.added,
    ) = ScanAnalysisSession(index, limits, analyzed, unique, duplicates, review, skipped, added)

    /**
     * Lifetime counters and the fixed Bloom guard deliberately survive exact-index eviction. Recent
     * scan IDs retain callback idempotence and reclassification; identities of arbitrarily old scans
     * remain duplicate-warning-positive without growing RAM. Over extremely long sessions the Bloom
     * filter can only become more conservative (false positives), never forget a real duplicate.
     */
    private fun trimRecentHistory() {
        while (index.outcomes.size > limits.maxTrackedScanIds) {
            val iterator = index.outcomes.entries.iterator()
            var oldestScanId: String? = null
            while (iterator.hasNext()) {
                val candidate = iterator.next().key
                if (candidate !in index.retainedScanIds) {
                    iterator.remove()
                    oldestScanId = candidate
                    break
                }
            }
            if (oldestScanId == null) break
            index.identityByScan.remove(oldestScanId)?.let { identityKey ->
                index.scansByIdentity[identityKey]?.let { scanIds ->
                    scanIds.remove(oldestScanId)
                    if (scanIds.isEmpty()) {
                        index.scansByIdentity.remove(identityKey)
                    }
                }
            }
        }
    }
}

internal data class ScanAnalysisSessionLimits(
    val maxTrackedScanIds: Int = 2_048,
    val maxTrackedAddedScanIds: Int = 2_048,
    val identityBloomBitCount: Int = 8 * 1_024 * 1_024,
    val identityBloomHashCount: Int = 7,
) {
    init {
        require(maxTrackedScanIds > 0)
        require(maxTrackedAddedScanIds > 0)
        require(identityBloomBitCount >= 64)
        require(identityBloomHashCount in 1..16)
    }
}

private class ScanAnalysisIndex(
    val identityGuard: SessionIdentityBloom,
    val outcomes: MutableMap<String, ScanSessionOutcome> = LinkedHashMap(),
    val identityByScan: MutableMap<String, String> = HashMap(),
    val scansByIdentity: MutableMap<String, LinkedHashSet<String>> = HashMap(),
    val addedScanIds: MutableSet<String> = LinkedHashSet(),
    val retainedScanIds: MutableSet<String> = LinkedHashSet(),
) {
    fun snapshot() = ScanAnalysisIndex(
        identityGuard = identityGuard.snapshot(),
        outcomes = LinkedHashMap(outcomes),
        identityByScan = HashMap(identityByScan),
        scansByIdentity = scansByIdentity.mapValuesTo(HashMap()) { (_, scanIds) -> LinkedHashSet(scanIds) },
        addedScanIds = LinkedHashSet(addedScanIds),
        retainedScanIds = LinkedHashSet(retainedScanIds),
    )
}

/** Fixed-size, session-local membership guard using double-hashed FNV/mix64 positions. */
private class SessionIdentityBloom(
    private val bitCount: Int,
    private val hashCount: Int,
    private val words: LongArray = LongArray((bitCount + 63) / 64),
) {
    val byteSize: Int get() = words.size * Long.SIZE_BYTES

    fun mightContain(value: String): Boolean {
        val (first, second) = hashes(value)
        repeat(hashCount) { index ->
            val bit = java.lang.Long.remainderUnsigned(first + index.toLong() * second, bitCount.toLong()).toInt()
            if (words[bit ushr 6] and (1L shl (bit and 63)) == 0L) return false
        }
        return true
    }

    fun add(value: String) {
        val (first, second) = hashes(value)
        repeat(hashCount) { index ->
            val bit = java.lang.Long.remainderUnsigned(first + index.toLong() * second, bitCount.toLong()).toInt()
            words[bit ushr 6] = words[bit ushr 6] or (1L shl (bit and 63))
        }
    }

    fun snapshot() = SessionIdentityBloom(bitCount, hashCount, words.copyOf())

    private fun hashes(value: String): Pair<Long, Long> {
        var first = -0x340d631b7bdddcdbL
        value.forEach { character ->
            first = (first xor character.code.toLong()) * 0x100000001b3L
        }
        var second = first xor -0x61c8864680b583ebL
        second = (second xor (second ushr 30)) * -0x40a7b892e31b1a47L
        second = (second xor (second ushr 27)) * -0x6b2fb644ecceee15L
        second = (second xor (second ushr 31)) or 1L
        return first to second
    }
}

internal data class ScanIdentityRegistration(
    val session: ScanAnalysisSession,
    val isDuplicate: Boolean,
)

internal enum class ScanSessionOutcome { UNIQUE, DUPLICATE, REVIEW, SKIPPED }
