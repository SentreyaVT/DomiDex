package com.dominik.cardvault.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dominik.cardvault.data.backup.LocalDataStartupPhase
import com.dominik.cardvault.data.backup.LocalDataStartupState
import com.dominik.cardvault.ui.theme.MewRose
import com.dominik.cardvault.ui.theme.VaultText
import com.dominik.cardvault.ui.theme.VaultTextMuted

@Composable
internal fun LocalDataStartupContent(
    state: LocalDataStartupState,
    motionEnabled: Boolean = rememberStartupMotionEnabled(),
    retry: () -> Unit,
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val brandAlpha by animateFloatAsState(
        targetValue = if (!motionEnabled || entered) 1f else .72f,
        animationSpec = if (motionEnabled) tween(180) else snap(),
        label = "startup-brand-reveal",
    )
    DomiDexBackdrop {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column(
                    Modifier.graphicsLayer { alpha = brandAlpha },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DomiDexBrandMark(size = 88.dp)
                    Text("DomiDex", color = VaultText, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Text("DEINE KARTEN. DEIN BINDER.", color = MewRose, fontSize = 10.sp, letterSpacing = 1.6.sp)
                }
                if (state is LocalDataStartupState.Failed) {
                    DomiGlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("Lokale Daten konnten nicht vorbereitet werden", color = VaultText, style = MaterialTheme.typography.titleLarge)
                            Text("DomiDex hat den Start sicher angehalten. Ein Backup-Import oder lokale Einstellungen konnten nicht vollständig geprüft werden. Deine Sammlung wird nicht zurückgesetzt; der Start wird erst nach erfolgreicher Prüfung fortgesetzt.", color = VaultTextMuted)
                            Text("Versuche es erneut. Bleibt die Meldung bestehen, kontaktiere den Support. Bitte lösche keine App-Daten.", color = VaultTextMuted, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = retry, modifier = Modifier.testTag("startup-retry")) { Text("Erneut versuchen") }
                        }
                    }
                } else {
                    if (motionEnabled) CircularProgressIndicator(
                        Modifier.size(24.dp).testTag("startup-animated-progress"), color = MewRose, strokeWidth = 2.dp,
                    ) else DomiLineIcon(DomiIconKind.SYNC, MewRose, Modifier.size(24.dp).testTag("startup-static-status"))
                    Text(
                        when ((state as? LocalDataStartupState.Preparing)?.phase) {
                            LocalDataStartupPhase.LOCAL_RECOVERY -> "Lokale Daten werden geprüft …"
                            LocalDataStartupPhase.COLLECTION -> "Sammlung wird vorbereitet …"
                            LocalDataStartupPhase.SYNC_SESSION -> "Synchronisierung wird vorbereitet …"
                            null -> "Lokale Daten werden vorbereitet …"
                        },
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = VaultText, textAlign = TextAlign.Center,
                    )
                    Text("Deine Sammlung bleibt bei dir.", color = VaultTextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
