package com.dominik.cardvault.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Import result boundary. Failure reporting remains separate from the atomic import. */
internal suspend fun cardmarketImportResult(
    operation: suspend () -> Unit,
    reportFailure: suspend (Throwable) -> Unit,
): Result<Unit> = runCatching {
    operation()
}.onFailure { error ->
    if (error is CancellationException) throw error
    try {
        reportFailure(error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (reportingError: Throwable) {
        // A cancelled database callback may expose a non-cancellation cleanup error.
        currentCoroutineContext().ensureActive()
        // Reporting cannot replace the failed import or turn it into success.
        // Guard against the same exception being rethrown by a reporting boundary.
        if (reportingError !== error) error.addSuppressed(reportingError)
    }
}
