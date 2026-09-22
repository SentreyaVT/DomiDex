package com.dominik.cardvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dominik.cardvault.data.backup.LocalDataStartupState

@Composable
internal fun LocalDataStartupContent(state: LocalDataStartupState, retry: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state is LocalDataStartupState.Failed) {
                    Text("Lokale Daten konnten nicht vorbereitet werden", style = MaterialTheme.typography.titleLarge)
                    Text("DomiDex hat den Start sicher angehalten. Ein Backup-Import oder lokale Einstellungen konnten nicht vollständig geprüft werden. Deine Sammlung wird nicht zurückgesetzt; der Start wird erst nach erfolgreicher Prüfung fortgesetzt.")
                    Text("Versuche es erneut. Bleibt die Meldung bestehen, kontaktiere den Support. Bitte lösche keine App-Daten.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = retry, modifier = Modifier.testTag("startup-retry")) { Text("Erneut versuchen") }
                } else {
                    CircularProgressIndicator()
                    Text("Lokale Daten werden vorbereitet …")
                }
            }
        }
    }
}
