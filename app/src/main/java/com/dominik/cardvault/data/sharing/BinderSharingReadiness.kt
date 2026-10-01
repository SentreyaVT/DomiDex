package com.dominik.cardvault.data.sharing

import android.content.Context
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.sync.BinderSharingSyncScheduler
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

internal fun interface BinderSharingReadiness {
    suspend fun awaitReady(expectedUid: String, binderId: String)
}

/** Waits for the existing outboxes; it never writes collection data or publishes a share. */
internal class PrivateBinderSharingReadiness(
    private val observeState: (String, String) -> Flow<Int>,
    private val requestSync: suspend (String, String) -> Unit,
    private val timeoutMillis: Long = 30_000,
) : BinderSharingReadiness {
    constructor(context: Context) : this(
        CardVaultDatabase.get(context.applicationContext).cardVaultDao()::observePrivateBinderSharingSyncState,
        { ownerUid, binderId ->
            BinderSharingSyncScheduler.schedule(context.applicationContext, ownerUid, binderId)
        },
    )

    override suspend fun awaitReady(expectedUid: String, binderId: String) {
        try {
            withTimeout(timeoutMillis) {
                var requested = false
                observeState(expectedUid, binderId).first { state ->
                    when (state) {
                        -1 -> throw BinderSharingSyncException("Dieser Binder ist nicht mehr verfügbar. Bitte öffne ihn mit seinem Besitzerkonto erneut.")
                        1 -> true
                        else -> {
                            if (!requested) {
                                currentCoroutineContext().ensureActive()
                                requestSync(expectedUid, binderId)
                                requested = true
                            }
                            false
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw BinderSharingSyncException(
                "Der Binder oder seine Karten sind noch nicht synchronisiert. Prüfe die Internetverbindung und tippe danach auf „Freigaben aktualisieren“. Bleibt die Meldung bestehen, prüfe den Sammlungs-Sync in den Einstellungen.",
            )
        }
    }
}
