package com.dominik.cardvault.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dominik.cardvault.ui.theme.CardVaultSpacing
import com.dominik.cardvault.ui.theme.VaultTextMuted

internal fun privacyPolicyIntent(): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(AppSupportConfig.privacyUrl))

internal fun supportMailIntent(): Intent = settingsMailIntent(
    subject = "DomiDex Support",
    body = "Hallo Dominik,\n\nich habe eine Frage zu DomiDex:\n\n",
)

internal fun accountDeletionMailIntent(): Intent = settingsMailIntent(
    subject = "DomiDex Kontolöschung",
    body = "Hallo Dominik,\n\nich möchte die Löschung meines DomiDex-Kontos und meiner zugeordneten persönlichen Daten anfragen. " +
        "Bitte teile mir die nächsten Schritte zur Prüfung und Löschung mit.\n\n" +
        "Die E-Mail-Adresse meines DomiDex-Kontos ergänze ich selbst:\n\n",
)

private fun settingsMailIntent(subject: String, body: String): Intent = Intent(
    Intent.ACTION_SENDTO,
    Uri.parse("mailto:${AppSupportConfig.email}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"),
).apply {
    putExtra(Intent.EXTRA_SUBJECT, subject)
    putExtra(Intent.EXTRA_TEXT, body)
}

/** Opens an external destination only; it does not send mail or confirm any remote action. */
internal fun launchSettingsIntent(context: Context, intent: Intent): Boolean = try {
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

@Composable
internal fun PrivacySettingsContent() {
    val context = LocalContext.current
    var failed by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Datenschutzerklärung", style = MaterialTheme.typography.titleSmall)
        Text("Auf der öffentlichen DomiDex-Seite findest du die aktuellen Datenschutzinformationen.", color = VaultTextMuted)
        SelectionContainer { Text(AppSupportConfig.privacyUrl, style = MaterialTheme.typography.bodySmall) }
        Text(
            "Pre-Beta-Hinweis: Der Online-Text ist noch nicht vollständig. Fragen zu Kontodaten, Scanner-Berichten und Löschung kannst du direkt an den Datenschutzkontakt richten.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = { failed = !launchSettingsIntent(context, privacyPolicyIntent()) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Datenschutzerklärung öffnen") }
        if (failed) SettingsActionStatus("Der Browser konnte nicht geöffnet werden. Du kannst die Adresse kopieren und selbst aufrufen.", isError = true)
        Text("Datenschutzkontakt: ${AppSupportConfig.displayName}", style = MaterialTheme.typography.bodySmall)
        SelectionContainer { Text(AppSupportConfig.email, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun SupportSettingsContent() {
    val context = LocalContext.current
    var failed by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Support & Kontakt", style = MaterialTheme.typography.titleSmall)
        Text("Bei Fragen zu DomiDex oder deinen Daten erreichst du mich direkt per E-Mail.", color = VaultTextMuted)
        Text(AppSupportConfig.displayName, fontWeight = FontWeight.SemiBold)
        SelectionContainer { Text(AppSupportConfig.email, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(
            onClick = { failed = !launchSettingsIntent(context, supportMailIntent()) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Support kontaktieren") }
        Text("Deine E-Mail-App öffnet sich. Du prüfst und sendest die Nachricht selbst; Konto- und Sammlungsdaten werden nicht automatisch eingefügt.", style = MaterialTheme.typography.bodySmall, color = VaultTextMuted)
        if (failed) SettingsActionStatus("Die E-Mail-App konnte nicht geöffnet werden. Schreibe selbst an ${AppSupportConfig.email}.", isError = true)
    }
}

@Composable
internal fun AccountDeletionRequestDialog(
    onDismiss: () -> Unit,
    openRequest: (() -> Boolean)? = null,
) {
    val context = LocalContext.current
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var requestOpened by rememberSaveable { mutableStateOf<Boolean?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Konto löschen") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Eine vollständige Kontolöschung kann dauerhaft persönliche Daten entfernen. Die App bietet derzeit eine E-Mail-Anfrage; eine automatische Löschfunktion ist noch nicht verfügbar.")
                Text("Bei einer Löschung zu berücksichtigen", fontWeight = FontWeight.SemiBold)
                Text("Dein Login-Konto bei Firebase Auth, deine Sammlung, persönliche Binder- und Portfolio-Daten, Kontoeinstellungen und Einwilligungen sowie dir zuordenbare Scanner-Berichte – soweit vorhanden.")
                Text("Allgemeine Referenzdaten, globale Scannerprofile und das gemeinsame Scanner-Manifest gehören nicht zu deinen persönlichen Kontodaten und bleiben erhalten.", style = MaterialTheme.typography.bodySmall, color = VaultTextMuted)
                Text("Dieser Dialog löscht selbst keine Daten. Auch lokale Daten auf diesem Gerät werden durch das Öffnen oder Senden einer Anfrage nicht automatisch entfernt. Die vollständige Bearbeitung und Bestätigung muss anschließend durch DomiDex erfolgen.")
                SelectionContainer { Text(AppSupportConfig.email, style = MaterialTheme.typography.bodySmall) }
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .testTag("account_deletion_confirmation")
                        .toggleable(value = confirmed, role = Role.Checkbox, onValueChange = { confirmed = it })
                        .padding(vertical = CardVaultSpacing.micro),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = confirmed, onCheckedChange = null)
                    Text("Ich möchte eine Löschanfrage vorbereiten.", modifier = Modifier.weight(1f))
                }
                requestOpened?.let { opened ->
                    SettingsActionStatus(
                        text = if (opened) "E-Mail-App geöffnet. Bitte prüfe den Entwurf und sende ihn selbst. Es wurde noch nichts gelöscht."
                        else "Die E-Mail-App konnte nicht geöffnet werden. Schreibe selbst an ${AppSupportConfig.email}. Es wurde nichts gelöscht.",
                        isError = !opened,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { requestOpened = openRequest?.invoke() ?: launchSettingsIntent(context, accountDeletionMailIntent()) },
                enabled = confirmed,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("E-Mail-Anfrage vorbereiten") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Abbrechen") }
        },
    )
}

@Composable
private fun SettingsActionStatus(text: String, isError: Boolean) {
    Text(
        text,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
