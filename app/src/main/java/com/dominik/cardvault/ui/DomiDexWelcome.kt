package com.dominik.cardvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dominik.cardvault.ui.theme.MewRose
import com.dominik.cardvault.ui.theme.VaultError
import com.dominik.cardvault.ui.theme.VaultText
import com.dominik.cardvault.ui.theme.VaultTextMuted

/** One readable page. Continuing grants no account, camera, research or photo-upload permission. */
@Composable
internal fun DomiDexWelcome(
    saving: Boolean,
    saveFailed: Boolean,
    onContinue: () -> Unit,
    onPrivacy: () -> Unit,
) {
    DomiDexBackdrop {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    DomiDexBrandMark(size = 58.dp, showWordmark = true)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Deine Sammlung.\nDein DomiDex.", color = VaultText, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                        Text("Willkommen! Alles für deine Pokémon-Karten – an einem Ort.", color = VaultTextMuted)
                    }
                    DomiGlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            WelcomeBenefit(DomiIconKind.BINDER, "Binder", "Entdecke deine Sets oder gestalte private Binder nach deinem Stil.")
                            WelcomeBenefit(DomiIconKind.SCAN, "Scanner", "Fotografiere eine Karte. Prüfe den Treffer, bevor du sie hinzufügst.")
                            WelcomeBenefit(DomiIconKind.COLLECTION, "Sammlung", "Behalte deine Karten, Varianten und Mengen im Blick – auch lokal.")
                            WelcomeBenefit(DomiIconKind.MARKET, "Preise", "Sieh verfügbare Cardmarket-Trendpreise. Fehlende Preise werden nicht geschätzt.")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DomiLineIcon(DomiIconKind.SHIELD, MewRose, Modifier.size(22.dp))
                        Text("Du entscheidest: Kamera, Anmeldung und freiwillige Uploads werden separat abgefragt. „Jetzt entdecken“ erteilt keine Einwilligung.", color = VaultTextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (saveFailed) Text(
                        "Deine Startauswahl konnte nicht gespeichert werden. Bitte versuche es erneut.",
                        color = VaultError, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Button(onClick = onContinue, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("welcome-continue")) {
                        Text(if (saving) "Auswahl wird gespeichert …" else "Jetzt entdecken")
                    }
                    TextButton(onClick = onPrivacy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Datenschutz lesen", color = MewRose) }
                }
            }
        }
    }
}

@Composable
private fun WelcomeBenefit(icon: DomiIconKind, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        DomiLineIcon(icon, MewRose, Modifier.size(26.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = VaultText, fontWeight = FontWeight.SemiBold)
            Text(description, color = VaultTextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
