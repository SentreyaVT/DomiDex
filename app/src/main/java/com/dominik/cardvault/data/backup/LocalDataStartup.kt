package com.dominik.cardvault.data.backup

import kotlinx.coroutines.CancellationException

internal sealed interface LocalDataStartupState {
    data object Loading : LocalDataStartupState
    data class Preparing(val phase: LocalDataStartupPhase) : LocalDataStartupState
    data object Ready : LocalDataStartupState
    data class Failed(val failureType: String) : LocalDataStartupState
}

internal enum class LocalDataStartupPhase { LOCAL_RECOVERY, COLLECTION, SYNC_SESSION }

/** Recovery must finish before any collection observer, seed or synchronization is started. */
internal suspend fun prepareLocalDataStartup(
    recover: suspend () -> Unit,
    seed: suspend () -> Unit,
    startCollectionSync: suspend () -> Unit,
    onPhaseChanged: (LocalDataStartupPhase) -> Unit = {},
): LocalDataStartupState = try {
    onPhaseChanged(LocalDataStartupPhase.LOCAL_RECOVERY)
    recover()
    onPhaseChanged(LocalDataStartupPhase.COLLECTION)
    seed()
    onPhaseChanged(LocalDataStartupPhase.SYNC_SESSION)
    startCollectionSync()
    LocalDataStartupState.Ready
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    LocalDataStartupState.Failed(failure.javaClass.simpleName)
}
