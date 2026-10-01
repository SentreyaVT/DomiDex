package com.dominik.cardvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dominik.cardvault.ui.theme.VaultError
import com.dominik.cardvault.ui.theme.VaultText
import com.dominik.cardvault.ui.theme.VaultTextMuted

/** First-run Google entry; local use and every camera/research/photo consent remain separate. */
@Composable
internal fun DomiDexWelcome(
    saving: Boolean,
    saveFailed: Boolean,
    onContinue: () -> Unit,
    onPrivacy: () -> Unit,
    onGoogleSignIn: () -> Unit,
    signingIn: Boolean = false,
    loginMessage: String? = null,
    alreadySignedIn: Boolean = false,
) {
    DomiDexBackdrop {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 20.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    DomiDexBrandMark(size = 48.dp, showWordmark = true)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Schön, dass du da bist.", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.titleMedium)
                        Text("Deine Sammlung.\nDein DomiDex.", color = VaultText, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                        Text("Melde dich direkt an und starte mit deinem freigeschalteten Konto. Du kannst DomiDex auch zunächst lokal nutzen.", color = VaultTextMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                    DomiGlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            WelcomeBenefit(DomiIconKind.BINDER, "Binder", "Entdecke deine Sets oder gestalte private Binder nach deinem Stil.", MaterialTheme.colorScheme.primary)
                            WelcomeBenefit(DomiIconKind.SCAN, "Scanner", "Fotografiere eine Karte. Prüfe den Treffer, bevor du sie hinzufügst.", MaterialTheme.colorScheme.tertiary)
                            WelcomeBenefit(DomiIconKind.COLLECTION, "Sammlung", "Behalte deine Karten, Varianten und Mengen im Blick – auch lokal.", MaterialTheme.colorScheme.secondary)
                            WelcomeBenefit(DomiIconKind.MARKET, "Preise", "Sieh verfügbare Cardmarket-Trendpreise. Fehlende Preise werden nicht geschätzt.", MaterialTheme.colorScheme.primary)
                        }
                    }
                    Surface(color = MaterialTheme.colorScheme.tertiary.copy(alpha = .07f), shape = RoundedCornerShape(20.dp)) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            DomiLineIcon(DomiIconKind.SHIELD, MaterialTheme.colorScheme.tertiary, Modifier.size(22.dp))
                            Text("Du entscheidest: Die Anmeldung aktiviert keine Kamera- oder ScanLab-Freigabe. Freiwillige Fotouploads bleiben separat. Lokales Fortfahren erteilt keine Einwilligung.", modifier = Modifier.weight(1f), color = VaultTextMuted, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
                        }
                    }
                }
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (saveFailed) Text(
                        "Deine Startauswahl konnte nicht gespeichert werden. Bitte versuche es erneut.",
                        color = VaultError, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    loginMessage?.let { Text(it, color = VaultTextMuted,
                        modifier = Modifier.testTag("welcome-login-message").semantics { liveRegion = LiveRegionMode.Polite }) }
                    Button(onClick = onGoogleSignIn, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("welcome-google"), shape = RoundedCornerShape(18.dp)) {
                        Text(when {
                            signingIn -> "Google-Anmeldung läuft …"
                            saving -> "Auswahl wird gespeichert …"
                            alreadySignedIn -> "Mit deinem Konto fortfahren"
                            else -> "Mit Google anmelden"
                        }, fontWeight = FontWeight.SemiBold)
                    }
                    if (!alreadySignedIn) TextButton(onClick = onContinue, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("welcome-continue")) {
                        Text("Vorerst lokal nutzen")
                    }
                    TextButton(onClick = onPrivacy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Datenschutz lesen", color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

@Composable
private fun WelcomeBenefit(icon: DomiIconKind, title: String, description: String, accent: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Surface(color = accent.copy(alpha = .12f), shape = RoundedCornerShape(14.dp)) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                DomiLineIcon(icon, accent, Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = VaultText, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(description, color = VaultTextMuted, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
        }
    }
}
