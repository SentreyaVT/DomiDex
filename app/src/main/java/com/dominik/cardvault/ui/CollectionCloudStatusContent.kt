package com.dominik.cardvault.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.dominik.cardvault.data.sync.CollectionCloudState
import com.dominik.cardvault.data.sync.CollectionCloudStatus

@Composable
internal fun CollectionCloudStatusContent(state: CollectionCloudState) {
    Text(when (state.status) {
        CollectionCloudStatus.SIGNED_OUT -> "Lokal gespeichert · nicht angemeldet"
        CollectionCloudStatus.CHECKING -> "Angemeldet · Cloud-Freigabe wird geprüft"
        CollectionCloudStatus.NO_ACCESS -> "Angemeldet · noch keine Cloud-Freigabe. Deine Daten bleiben lokal erhalten."
        CollectionCloudStatus.LOCAL_ONLY -> "Lokal gespeichert · Cloud-Verbindung nicht bestätigt"
        CollectionCloudStatus.PENDING -> "Änderungen gespeichert · Synchronisation ausstehend"
        CollectionCloudStatus.ERROR -> "Lokal gespeichert · Synchronisation benötigt Aufmerksamkeit"
        CollectionCloudStatus.ACTIVE -> "Cloud-Verbindung aktiv · keine ausstehenden Änderungen"
    }, style = MaterialTheme.typography.bodySmall)
}
