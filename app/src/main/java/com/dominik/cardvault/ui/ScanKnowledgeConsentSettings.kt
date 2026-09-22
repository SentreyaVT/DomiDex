package com.dominik.cardvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dominik.cardvault.auth.canonicalCollectionOwnerUid
import com.dominik.cardvault.data.knowledge.ScanKnowledgeConsent
import com.dominik.cardvault.data.knowledge.ScanKnowledgeScheduler
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ScanKnowledgeConsentSettings() {
    val context = LocalContext.current.applicationContext
    val auth = remember { FirebaseAuth.getInstance() }
    val consent = remember(context) { ScanKnowledgeConsent(context) }
    var uid by remember { mutableStateOf(canonicalCollectionOwnerUid(auth.currentUser)) }
    var enabled by remember(uid) { mutableStateOf(consent.isEnabled(uid)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { uid = canonicalCollectionOwnerUid(it.currentUser) }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    ScanKnowledgeConsentContent(enabled, uid != null && !saving, { selected ->
        val owner = uid ?: return@ScanKnowledgeConsentContent
        saving = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(canonicalCollectionOwnerUid(auth.currentUser) == owner) { "Bitte erneut versuchen: Das Konto hat sich geändert." }
                    consent.setEnabled(owner, selected)
                }
                if (uid == owner) enabled = consent.isEnabled(owner)
                if (selected) ScanKnowledgeScheduler.schedule(context, owner)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Die Auswahl konnte nicht gespeichert werden." }
            finally { saving = false }
        }
    })
    if (uid == null) Text("Melde dich an, um diese freiwillige Auswahl für dein Konto zu treffen.", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun ScanKnowledgeConsentContent(enabled: Boolean, editable: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Scannerwissen freiwillig teilen", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(enabled, onChange, enabled = editable, modifier = Modifier.testTag("scan-knowledge-consent"))
        }
        Text(
            "Optional werden technische Erkennungsdaten wie Set/Kartennummer, OCR-Text, Bildmerkmale, Kandidaten und deine Kartenkorrektur mit deinem Konto an Firebase übertragen. Das hilft, die Erkennung zu verbessern. Keine Fotos werden dadurch hochgeladen.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Standardmäßig aus. Ausschalten stoppt neue Übertragungen und entfernt noch nicht gesendete Erkennungsdaten auf diesem Gerät. Bereits übertragene Daten werden damit nicht gelöscht. ScanLab-Fotos bleiben separat: nur über ‚An ScanLab schicken‘ mit eigener Zustimmung.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
