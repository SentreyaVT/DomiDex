package com.dominik.cardvault.data.sync

internal enum class PrivateFullPullRunResult { COMPLETE, MORE_DOCUMENTS }

/** The adapter owns the snapshot, its metadata, the Room transaction and the auth-session guard. */
internal class PrivateFullPullRow(val id: String, val apply: suspend () -> PrivateRemoteApplyResult)

/** Only the existing four-phase FULL scan. No timestamps, delta cursors, SDK objects or cloud schema. */
internal interface PrivateFullPullPort {
    suspend fun readPhase(): Int
    suspend fun readCursor(phase: Int): String?
    suspend fun queryPage(phase: Int, after: String?, limit: Int): List<PrivateFullPullRow>
    suspend fun resolveDependency(dependency: PrivateRemoteApplyResult.RetryDependency)
    suspend fun saveCursor(phase: Int, documentId: String)
    /** Atomically clear this phase's old cursor and persist phase + 1. */
    suspend fun finishPhase(phase: Int)
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
    var phase = port.readPhase().coerceIn(0, PRIVATE_FULL_PULL_PHASE_COUNT)
    while (phase < PRIVATE_FULL_PULL_PHASE_COUNT) {
        var cursor = port.readCursor(phase)?.takeIf(String::isNotBlank)
        while (true) {
            val pageSize = budget.nextPageSize(maxPageSize)
            if (pageSize == 0) return PrivateFullPullRunResult.MORE_DOCUMENTS
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
                            // This parent has NOT been attempted. Resume before this document;
                            // previous documents' durable bookmarks remain valid.
                            return PrivateFullPullRunResult.MORE_DOCUMENTS
                        }
                    }
                }
                // Full-scan deferrals/invalid rows are revisited next cycle. They are not a delta
                // high-watermark and do not themselves request an immediate orphan retry loop.
                if (document.id != cursor) port.saveCursor(phase, document.id)
                cursor = document.id
            }
            if (documents.size < pageSize) {
                port.finishPhase(phase)
                phase++
                break
            }
        }
    }
    // In particular, phase 3's empty read may consume the last unit. It still completes, and a
    // persisted phase 4 after process death must reset without requiring another network request.
    port.resetCycle()
    return PrivateFullPullRunResult.COMPLETE
}

private const val PRIVATE_FULL_PULL_PHASE_COUNT = 4
