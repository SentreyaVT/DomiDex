package com.dominik.cardvault.ui

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dominik.cardvault.model.CollectionCard
import com.dominik.cardvault.ui.theme.MewIce
import com.dominik.cardvault.ui.theme.MewPlumDeep
import com.dominik.cardvault.ui.theme.MewRose
import com.dominik.cardvault.ui.theme.VaultError
import com.dominik.cardvault.ui.theme.VaultNightRaised
import com.dominik.cardvault.ui.theme.VaultOutline
import com.dominik.cardvault.ui.theme.VaultPositive
import com.dominik.cardvault.ui.theme.VaultText
import com.dominik.cardvault.ui.theme.VaultTextMuted

internal const val PRIVATE_BINDER_EFFECTS_PREFERENCE = "private_binder_effects_animated"

internal enum class PrivateBinderHoloIntensityPreset(
    val label: String,
    val storedValue: Float,
) {
    SOFT("Sanft", .42f),
    NORMAL("Normal", .68f),
    INTENSE("Intensiv", .92f),
    ;

    companion object {
        fun fromStored(value: Float): PrivateBinderHoloIntensityPreset = when {
            value <= .52f -> SOFT
            value >= .82f -> INTENSE
            else -> NORMAL
        }
    }
}

internal fun privateBinderVisualQuality(isLowRamDevice: Boolean, memoryClassMb: Int): PrivateBinderVisualQuality = when {
    isLowRamDevice || memoryClassMb < 256 -> PrivateBinderVisualQuality.LOW
    memoryClassMb < 512 -> PrivateBinderVisualQuality.MEDIUM
    else -> PrivateBinderVisualQuality.HIGH
}

@Composable
internal fun rememberPrivateBinderVisualQuality(): PrivateBinderVisualQuality {
    val context = LocalContext.current
    return remember(context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        privateBinderVisualQuality(
            isLowRamDevice = manager?.isLowRamDevice == true,
            memoryClassMb = manager?.memoryClass ?: 256,
        )
    }
}

@Composable
internal fun PrivateBinderCoverSelectorDialog(
    initialThemeId: String,
    animationsEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selectedId by rememberSaveable(initialThemeId) {
        mutableStateOf(PrivateBinderVisualEngine.coverTheme(initialThemeId).id)
    }
    val selected = PrivateBinderVisualEngine.coverTheme(selectedId)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Binder-Design") },
        text = {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compactHeight = maxHeight < 560.dp
                Column(verticalArrangement = Arrangement.spacedBy(if (compactHeight) 6.dp else 10.dp)) {
                PrivateBinderCoverPreview(
                    theme = selected,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(if (compactHeight) 108.dp else 148.dp)
                        .aspectRatio(.77f),
                    animated = animationsEnabled,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(selected.displayName, color = VaultText, fontWeight = FontWeight.Bold)
                    Text(selected.description, color = VaultTextMuted, fontSize = 10.sp, textAlign = TextAlign.Center)
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(118.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = if (compactHeight) 190.dp else 310.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(PrivateBinderVisualEngine.coverThemes, key = { it.id }) { theme ->
                        val chosen = theme.id == selectedId
                        OutlinedCard(
                            onClick = { selectedId = theme.id },
                            colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised),
                            border = BorderStroke(1.dp, if (chosen) MewRose else VaultOutline),
                            modifier = Modifier.semantics {
                                this.selected = chosen
                                contentDescription = "Cover ${theme.displayName}"
                            },
                        ) {
                            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                PrivateBinderCoverPreview(
                                    theme = theme,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(.77f),
                                    animated = false,
                                )
                                Text(theme.displayName, color = VaultText, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(theme.description, color = VaultTextMuted, fontSize = 8.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selected.id) }) { Text("Übernehmen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun PrivateBinderCoverPreview(
    theme: PrivateBinderCoverTheme,
    modifier: Modifier,
    animated: Boolean,
) {
    DomiPrivateBinderCover(
        title = theme.displayName,
        themeId = theme.id,
        modifier = modifier,
        animated = animated,
    )
}

@Composable
internal fun PrivateBinderHoloSelectorDialog(
    placementId: String,
    card: CollectionCard,
    initialEffectId: String?,
    initialIntensity: Float,
    animationsEnabled: Boolean,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (effectId: String?, intensity: Float) -> Unit,
) {
    var selectedId by rememberSaveable(placementId, initialEffectId) {
        mutableStateOf(PrivateBinderVisualEngine.holoEffect(initialEffectId).id)
    }
    var intensityPreset by rememberSaveable(placementId, initialIntensity) {
        mutableStateOf(PrivateBinderHoloIntensityPreset.fromStored(initialIntensity.takeIf { it > 0f } ?: PrivateBinderHoloIntensityPreset.NORMAL.storedValue))
    }
    val selected = PrivateBinderVisualEngine.holoEffect(selectedId)
    val quality = rememberPrivateBinderVisualQuality()
    val effectiveIntensity = if (selected.id == PrivateBinderVisualEngine.NORMAL_HOLO_ID) 0f else intensityPreset.storedValue
    val largePreferences = PrivateBinderVisualPreferences(
        intensity = effectiveIntensity,
        quality = quality,
        reduceMotion = !animationsEnabled,
    )
    val tiltState = rememberPrivateBinderTiltState()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Holo-Effekt wählen") },
        text = {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val fontScale = LocalDensity.current.fontScale
                val bodyHeight = minOf(maxHeight, 620.dp, LocalConfiguration.current.screenHeightDp.dp * .65f)
                val compactHeight = bodyHeight < 480.dp || fontScale > 1.2f
                val gridColumns = if (fontScale >= 1.5f || maxWidth < 240.dp) 1 else 2
                val previewHeight = if (compactHeight) (bodyHeight * .32f).coerceIn(100.dp, 144.dp)
                    else (bodyHeight * .36f).coerceIn(160.dp, 210.dp)
                val previewWidth = previewHeight * (63f / 88f)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(bodyHeight),
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight) 6.dp else 10.dp),
                ) {
                    // Only the grid below scrolls. A real, separately laid-out header keeps
                    // preview gestures independent from list scrolling and never overlays it.
                    if (compactHeight) {
                        Row(
                            Modifier.fillMaxWidth().height(previewHeight),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PrivateBinderHoloSelectorPreview(card, selected.id, largePreferences, tiltState, animationsEnabled, Modifier.width(previewWidth))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(selected.displayName, color = VaultText, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (fontScale < 1.8f) {
                                    Text(
                                        if (animationsEnabled) "Karte berühren – das Licht folgt deiner Bewegung."
                                        else "Ruhige Vorschau · Bewegung deaktiviert.",
                                        color = VaultTextMuted, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    } else {
                        PrivateBinderHoloSelectorPreview(card, selected.id, largePreferences, tiltState, animationsEnabled, Modifier.align(Alignment.CenterHorizontally).width(previewWidth))
                        Text(
                            if (animationsEnabled) "Karte berühren – das Licht folgt deiner Bewegung."
                            else "Ruhige Vorschau · Kartenbewegung ist deaktiviert.",
                            color = VaultTextMuted, fontSize = 10.sp, lineHeight = 13.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${selected.displayName} · ${selected.description}",
                            color = VaultText, fontSize = 10.sp, lineHeight = 14.sp,
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PrivateBinderHoloIntensityPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = intensityPreset == preset,
                                onClick = { intensityPreset = preset },
                                enabled = !saving && selected.id != PrivateBinderVisualEngine.NORMAL_HOLO_ID,
                                label = { Text(preset.label, fontSize = 9.sp) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    error?.let { Text(it, color = VaultError, fontSize = 10.sp) }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(gridColumns),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .semantics { contentDescription = "Holo-Effekte" },
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(PrivateBinderVisualEngine.holoEffects, key = { it.id }) { effect ->
                                val chosen = effect.id == selectedId
                                OutlinedCard(
                                    onClick = { if (!saving) selectedId = effect.id },
                                    colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised),
                                    border = BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) MewRose else VaultOutline),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .semantics {
                                            this.selected = chosen
                                            contentDescription = "Holo ${effect.displayName}"
                                        },
                                ) {
                                    Column(Modifier.padding(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Box(
                                            Modifier.fillMaxWidth().height(if (compactHeight) 70.dp else 82.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Box(
                                                Modifier
                                                    .fillMaxHeight()
                                                    .aspectRatio(63f / 88f)
                                                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                                    .clip(RoundedCornerShape(7.dp)),
                                            ) {
                                                if (!card.imageUrl.isNullOrBlank()) {
                                                    AsyncImage(card.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                                } else {
                                                    Box(Modifier.fillMaxSize().background(MewPlumDeep))
                                                }
                                                PrivateBinderHoloLayer(
                                                    effect.id,
                                                    Modifier.fillMaxSize(),
                                                    PrivateBinderVisualPreferences(
                                                        intensity = if (effect.pattern == HoloPattern.NONE) 0f else intensityPreset.storedValue,
                                                        quality = PrivateBinderVisualQuality.LOW,
                                                        reduceMotion = true,
                                                    ),
                                                )
                                            }
                                        }
                                        Text(effect.displayName, color = VaultText, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(effect.description, color = VaultTextMuted, fontSize = 7.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    onConfirm(
                        selected.id.takeUnless { it == PrivateBinderVisualEngine.NORMAL_HOLO_ID },
                        effectiveIntensity,
                    )
                },
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = VaultText)
                else Text("Übernehmen")
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun PrivateBinderHoloSelectorPreview(
    card: CollectionCard,
    effectId: String,
    preferences: PrivateBinderVisualPreferences,
    tiltState: PrivateBinderTiltState,
    animationsEnabled: Boolean,
    modifier: Modifier,
) {
    Box(
        modifier.aspectRatio(63f / 88f)
            .privateBinderTouchTilt(tiltState, enabled = animationsEnabled, maxTiltDegrees = preferences.maxTiltDegrees, idleMotionEnabled = false)
            .clip(RoundedCornerShape(12.dp))
            .background(VaultNightRaised)
            .semantics { contentDescription = "Interaktive Holo-Vorschau" },
    ) {
        if (!card.imageUrl.isNullOrBlank()) {
            AsyncImage(card.imageUrl, card.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Box(Modifier.fillMaxSize().background(MewPlumDeep), contentAlignment = Alignment.Center) {
                Text(card.name.take(1), color = VaultText, fontSize = 36.sp, fontWeight = FontWeight.Black)
            }
        }
        PrivateBinderHoloLayer(effectId, Modifier.fillMaxSize(), preferences, tiltState)
    }
}

@Composable
internal fun PrivateBinderInteractiveCardVisual(
    card: CollectionCard,
    effectId: String?,
    intensity: Float,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val quality = rememberPrivateBinderVisualQuality()
    val state = rememberPrivateBinderTiltState()
    val preferences = PrivateBinderVisualPreferences(
        intensity = intensity.coerceIn(0f, 1f),
        quality = quality,
        reduceMotion = !animationsEnabled,
    )
    Box(
        modifier
            .privateBinderTouchTilt(
                state = state,
                enabled = animationsEnabled,
                maxTiltDegrees = preferences.maxTiltDegrees,
                idleMotionEnabled = false,
            )
            .aspectRatio(63f / 88f)
            .clip(RoundedCornerShape(12.dp))
            .background(VaultNightRaised),
    ) {
        if (!card.imageUrl.isNullOrBlank()) AsyncImage(card.imageUrl, card.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Box(Modifier.fillMaxSize().background(MewPlumDeep), contentAlignment = Alignment.Center) {
            Text(card.name.take(1), color = VaultText, fontSize = 54.sp, fontWeight = FontWeight.Black)
        }
        PrivateBinderHoloLayer(effectId, Modifier.fillMaxSize(), preferences, state)
    }
}

@Composable
internal fun PrivateBinderStaticHoloLayer(
    effectId: String?,
    intensity: Float,
    modifier: Modifier = Modifier,
    tiltState: PrivateBinderTiltState? = null,
) {
    PrivateBinderHoloLayer(
        effectId = effectId,
        modifier = modifier,
        preferences = PrivateBinderVisualPreferences(
            // Honor the selected strength in the binder too. The lower primitive budget
            // already keeps compact cards calm; attenuating again hides their shimmer.
            intensity = intensity.coerceIn(0f, 1f),
            quality = PrivateBinderVisualQuality.LOW,
            reduceMotion = true,
        ),
        tiltState = tiltState,
    )
}
