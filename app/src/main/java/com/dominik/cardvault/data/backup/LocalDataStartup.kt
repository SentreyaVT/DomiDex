package com.dominik.cardvault.data.backup

import kotlinx.coroutines.CancellationException

internal sealed interface LocalDataStartupState {
    data object Loading : LocalDataStartupState
    data object Ready : LocalDataStartupState
    data class Failed(val failureType: String) : LocalDataStartupState
}

/** Recovery must finish before any collection observer, seed or synchronization is started. */
internal suspend fun prepareLocalDataStartup(
    recover: suspend () -> Unit,
    seed: suspend () -> Unit,
    startCollectionSync: suspend () -> Unit,
): LocalDataStartupState = try {
    recover()
    seed()
    startCollectionSync()
    LocalDataStartupState.Ready
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    LocalDataStartupState.Failed(failure.javaClass.simpleName)
}
