package com.dominik.cardvault.data.sync

internal enum class PrivateFullPullRunResult { COMPLETE, MORE_DOCUMENTS }

/** EOF bits record completed group sweeps, not groups which must be skipped forever. */
internal data class PrivateFullPullSchedule(val nextGroup: Int = 0, val seenEofMask: Int = 0) {
    init {
        require(nextGroup in 0 until PRIVATE_FULL_PULL_PHASE_COUNT)
        require(seenEofMask in 0..PRIVATE_FULL_PULL_ALL_EOF)
    }

    val isComplete: Boolean get() = seenEofMask == PRIVATE_FULL_PULL_ALL_EOF

    fun afterGroup(group: Int, reachedEof: Boolean): PrivateFullPullSchedule {
        require(group in 0 until PRIVATE_FULL_PULL_PHASE_COUNT)
        check(!isComplete) { "Completed FULL-pull schedule must be reset first" }
        return PrivateFullPullSchedule((group + 1) % PRIVATE_FULL_PULL_PHASE_COUNT,
            if (reachedEof) seenEofMask or (1 shl group) else seenEofMask)
    }
}

/** Reuses the existing local phase value; never guesses completion from corrupt metadata. */
internal object PrivateFullPullScheduleCodec {
    fun decode(raw: String?): PrivateFullPullSchedule {
        if (raw == null) return PrivateFullPullSchedule()
        if (raw in listOf("0", "1", "2", "3", "4")) {
            val phase = raw.toInt()
            return if (phase == PRIVATE_FULL_PULL_PHASE_COUNT) PrivateFullPullSchedule(0, PRIVATE_FULL_PULL_ALL_EOF)
            else PrivateFullPullSchedule(phase, (1 shl phase) - 1)
        }
        val match = Regex("rr1\\|([0-3])\\|(0|[1-9]|1[0-5])").matchEntire(raw)
            ?: throw IllegalStateException("Invalid FULL-pull scheduling metadata")
        return PrivateFullPullSchedule(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    fun encode(value: PrivateFullPullSchedule): String = "rr1|${value.nextGroup}|${value.seenEofMask}"
}

/** The adapter owns the snapshot, its metadata, the Room transaction and the auth-session guard. */
internal class PrivateFullPullRow(val id: String, val apply: suspend () -> PrivateRemoteApplyResult)

/** Only the existing four-group FULL scan. No timestamps, delta cursors, SDK objects or cloud schema. */
internal interface PrivateFullPullPort {
    suspend fun readSchedule(): PrivateFullPullSchedule
    suspend fun readCursor(phase: Int): String?
    suspend fun queryPage(phase: Int, after: String?, limit: Int): List<PrivateFullPullRow>
    suspend fun resolveDependency(dependency: PrivateRemoteApplyResult.RetryDependency)
    suspend fun saveCursor(phase: Int, documentId: String)
    /** Atomically persist rotation/EOF evidence and, ONLY at EOF, clear this group's cursor. */
    suspend fun advanceGroup(group: Int, reachedEof: Boolean): PrivateFullPullSchedule
    /** Clear scheduling evidence, never the independently progressing group cursors. */
    suspend fun resetCycle()
}

/**
 * Production control flow shared with offline tests. Each callback may throw/cancel: neither is
 * swallowed or converted to success. The caller installs owner single-flight around the whole run.
 */
internal suspend fun runPrivateFullPull(
    port: PrivateFullPullPort,
    budget: PrivateFullPullReadBudget,
    maxPageSize: Int = 200,
): PrivateFullPullRunResult {
    require(maxPageSize > 0)
    var schedule = port.readSchedule()
    // A group is not re-scanned twice in one invocation after EOF, but is eligible again in the
    // next invocation even while another group keeps growing. Persisted EOF bits only govern
    // completion: each group has reached EOF at least once since the previous completion.
    var finishedThisRun = 0
    while (!schedule.isComplete) {
        val pageSize = budget.nextPageSize(maxPageSize)
        if (pageSize == 0) return PrivateFullPullRunResult.MORE_DOCUMENTS
        var phase = schedule.nextGroup
        while ((finishedThisRun and (1 shl phase)) != 0) phase = (phase + 1) % PRIVATE_FULL_PULL_PHASE_COUNT
        var cursor = port.readCursor(phase)?.takeIf(String::isNotBlank)
        val documents = port.queryPage(phase, cursor, pageSize)
        budget.recordPageResult(pageSize, documents.size)
        for (document in documents) {
            var result = document.apply()
            while (result is PrivateRemoteApplyResult.RetryDependency) {
                when (budget.reserveDependency(result.type, result.id)) {
                    PrivateDependencyReadAllowance.Allowed -> {
                        port.resolveDependency(result)
                        result = document.apply()
                    }
                    PrivateDependencyReadAllowance.AlreadyAttempted -> break
                    PrivateDependencyReadAllowance.BudgetExhausted -> {
                        // Charge the whole fetched page, retain the bookmark BEFORE this child,
                        // but let a later group run first next time. This is not an EOF or skip.
                        port.advanceGroup(phase, reachedEof = false)
                        return PrivateFullPullRunResult.MORE_DOCUMENTS
                    }
                }
            }
            // Deferrals/invalid rows are revisited in the group's next sweep. They are not a
            // delta watermark and do not themselves request an immediate orphan retry loop.
            if (document.id != cursor) port.saveCursor(phase, document.id)
            cursor = document.id
        }
        val reachedEof = documents.size < pageSize
        schedule = port.advanceGroup(phase, reachedEof)
        if (reachedEof) finishedThisRun = finishedThisRun or (1 shl phase)
    }
    // Completion is not a simultaneous cloud snapshot or a claim that all cursors are empty:
    // an earlier finished group may already have begun another sweep in a later invocation.
    // A durable complete mask (including legacy phase 4) resets with no extra network request.
    port.resetCycle()
    return PrivateFullPullRunResult.COMPLETE
}

private const val PRIVATE_FULL_PULL_PHASE_COUNT = 4
private const val PRIVATE_FULL_PULL_ALL_EOF = (1 shl PRIVATE_FULL_PULL_PHASE_COUNT) - 1
