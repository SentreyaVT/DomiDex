package com.dominik.cardvault.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.dominik.cardvault.model.BinderTarget
import com.dominik.cardvault.model.CatalogSearchResult
import com.dominik.cardvault.model.CollectionCard
import com.dominik.cardvault.model.CollectionRepository
import com.dominik.cardvault.model.PrivateBinderCard
import com.dominik.cardvault.model.PrivateBinderSummary
import com.dominik.cardvault.ui.theme.CardVaultShapes
import com.dominik.cardvault.ui.theme.CardVaultSpacing
import com.dominik.cardvault.ui.theme.MewIce
import com.dominik.cardvault.ui.theme.MewPlum
import com.dominik.cardvault.ui.theme.MewPlumDeep
import com.dominik.cardvault.ui.theme.MewRose
import com.dominik.cardvault.ui.theme.MewRoseSoft
import com.dominik.cardvault.ui.theme.VaultError
import com.dominik.cardvault.ui.theme.VaultNightRaised
import com.dominik.cardvault.ui.theme.VaultOutline
import com.dominik.cardvault.ui.theme.VaultOutlineSoft
import com.dominik.cardvault.ui.theme.VaultPositive
import com.dominik.cardvault.ui.theme.VaultText
import com.dominik.cardvault.ui.theme.VaultTextFaint
import com.dominik.cardvault.ui.theme.VaultTextMuted
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val PRIVATE_BINDER_SLOTS_PER_PAGE = 9

internal fun privateBinderPageForPosition(position: Long): Int =
    (position.coerceAtLeast(0L) / PRIVATE_BINDER_SLOTS_PER_PAGE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

internal fun privateBinderSlotForPosition(position: Long): Int =
    (position.coerceAtLeast(0L) % PRIVATE_BINDER_SLOTS_PER_PAGE).toInt()

internal fun privateBinderPosition(page: Int, slot: Int): Long =
    page.coerceAtLeast(0).toLong() * PRIVATE_BINDER_SLOTS_PER_PAGE + slot.coerceIn(0, PRIVATE_BINDER_SLOTS_PER_PAGE - 1)

internal fun privateBinderPageCount(positions: Collection<Long>): Int =
    (positions.maxOrNull()?.let(::privateBinderPageForPosition)?.plus(1) ?: 1).coerceAtLeast(1)

internal fun privateBinderNextAvailablePosition(occupied: Set<Long>, preferredPage: Int): Long {
    val pageStart = privateBinderPosition(preferredPage, 0)
    (0 until PRIVATE_BINDER_SLOTS_PER_PAGE)
        .map { pageStart + it }
        .firstOrNull { it !in occupied }
        ?.let { return it }
    // A full visible page grows forward. Do not unexpectedly jump the collector back to an
    // unrelated older gap; those gaps remain valid explicit drag targets.
    var candidate = pageStart + PRIVATE_BINDER_SLOTS_PER_PAGE
    while (candidate in occupied) candidate++
    return candidate
}

internal data class PrivateBinderDragDestination(val page: Int, val slot: Int, val position: Long)

/** Maps a real long-press drag to the 3x3 grid; one position beyond the last page grows the binder. */
internal fun privateBinderDragDestination(
    startPosition: Long,
    dragX: Float,
    dragY: Float,
    slotWidth: Float,
    slotHeight: Float,
    currentPageCount: Int,
): PrivateBinderDragDestination {
    val startPage = privateBinderPageForPosition(startPosition)
    val startSlot = privateBinderSlotForPosition(startPosition)
    val startColumn = startSlot % 3
    val startRow = startSlot / 3
    val columnShift = if (slotWidth > 0f) (dragX / slotWidth).roundToInt() else 0
    val rowShift = if (slotHeight > 0f) (dragY / slotHeight).roundToInt() else 0
    val rawColumn = startColumn + columnShift
    val pageDelta = Math.floorDiv(rawColumn, 3)
    val maximumPage = currentPageCount.coerceAtLeast(1)
    val rawTargetPage = startPage + pageDelta
    val targetPage = rawTargetPage.coerceIn(0, maximumPage)
    val targetColumn = when {
        rawTargetPage < 0 -> 0
        rawTargetPage > maximumPage -> 2
        else -> Math.floorMod(rawColumn, 3)
    }
    val targetRow = (startRow + rowShift).coerceIn(0, 2)
    val targetSlot = targetRow * 3 + targetColumn
    return PrivateBinderDragDestination(targetPage, targetSlot, privateBinderPosition(targetPage, targetSlot))
}

@Composable
internal fun BinderTargetPicker(
    setName: String,
    privateBinders: List<PrivateBinderSummary>,
    selectedPrivateBinderId: String?,
    onSelectedPrivateBinderId: (String?) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedPrivate = privateBinders.firstOrNull { it.id == selectedPrivateBinderId }
    val awaitingSelectedPrivate = selectedPrivateBinderId != null && selectedPrivate == null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("Ziel-Binder", color = MewIce, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                DomiLineIcon(
                    if (selectedPrivate == null && !awaitingSelectedPrivate) DomiIconKind.BINDER else DomiIconKind.COLLECTION,
                    if (selectedPrivate == null && !awaitingSelectedPrivate) MewIce else MewRose,
                    Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    selectedPrivate?.let { "Privat · ${it.name}" }
                        ?: if (awaitingSelectedPrivate) "Privater Binder wird geladen …"
                        else "Set-Binder · ${setName.ifBlank { "Passendes Set" }}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(.92f).heightIn(max = 360.dp),
            ) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Set-Binder", fontWeight = FontWeight.SemiBold)
                            Text(setName.ifBlank { "Passendes Set der Karte" }, color = VaultTextMuted, fontSize = 11.sp)
                        }
                    },
                    onClick = { onSelectedPrivateBinderId(null); expanded = false },
                )
                privateBinders.forEach { binder ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(binder.name, fontWeight = FontWeight.SemiBold)
                                Text("Privater Binder · ${binder.uniqueCards} Karten", color = VaultTextMuted, fontSize = 11.sp)
                            }
                        },
                        onClick = { onSelectedPrivateBinderId(binder.id); expanded = false },
                    )
                }
            }
        }
        Text(
            if (awaitingSelectedPrivate) "Das gespeicherte Binderziel wird geprüft."
            else if (selectedPrivate == null) "Die Karte wird an ihrer echten Set-Position einsortiert."
            else "Die Karte wird im gewählten privaten Binder angezeigt; weitere private Zuordnungen bleiben erhalten.",
            color = VaultTextMuted,
            fontSize = 10.sp,
        )
    }
}

internal fun binderTarget(privateBinderId: String?): BinderTarget =
    privateBinderId?.let(BinderTarget::PrivateBinder) ?: BinderTarget.MatchingSetBinder

internal fun binderUiError(error: Throwable, fallback: String): String =
    (error as? IllegalArgumentException)?.message?.takeIf(String::isNotBlank) ?: fallback

@Composable
internal fun ReferenceBinderTargetDialog(
    card: CollectionCard,
    product: CatalogSearchResult,
    privateBinders: List<PrivateBinderSummary>,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (BinderTarget) -> Unit,
) {
    var selectedPrivateBinderId by rememberSaveable(card.id, product.productId) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Zum Binder hinzufügen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${card.name} · ${card.number}", color = VaultText, fontWeight = FontWeight.Bold)
                Text(
                    product.trendPriceEur?.let { "Cardmarket-Trendpreis: ${privateBinderEuro(it)}" }
                        ?: product.confirmedPriceSelection?.let { "Gewählter Vergleichswert: ${privateBinderEuro(it.valueEur)} · ${it.metric.label}" }
                        ?: "Preis nicht verfügbar",
                    color = if (product.trendPriceEur != null) MewIce else VaultTextMuted,
                    fontWeight = FontWeight.SemiBold,
                )
                BinderTargetPicker(
                    setName = card.set,
                    privateBinders = privateBinders,
                    selectedPrivateBinderId = selectedPrivateBinderId,
                    onSelectedPrivateBinderId = { selectedPrivateBinderId = it },
                    enabled = !saving,
                )
                error?.let { Text(it, color = VaultError, fontSize = 11.sp) }
            }
        },
        confirmButton = {
            Button(enabled = !saving, onClick = { onConfirm(binderTarget(selectedPrivateBinderId)) }) {
                if (saving) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp) else Text("Hinzufügen")
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
internal fun BinderOverviewControls(
    sort: BinderSortOption,
    filter: BinderFilterOption,
    onSortChanged: (BinderSortOption) -> Unit,
    onFilterChanged: (BinderFilterOption) -> Unit,
    showFilter: Boolean = true,
    filterOptions: List<BinderFilterOption> = BinderFilterOption.entries,
) {
    var sortOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { sortOpen = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                DomiLineIcon(DomiIconKind.SYNC, MaterialTheme.colorScheme.primary, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(sort.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                BinderSortOption.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, color = if (option == sort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                        onClick = { onSortChanged(option); sortOpen = false },
                    )
                }
            }
        }
        if (showFilter) Box(Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { filterOpen = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                DomiLineIcon(DomiIconKind.SEARCH, MaterialTheme.colorScheme.secondary, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(filter.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                filterOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, color = if (option == filter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                        onClick = { onFilterChanged(option); filterOpen = false },
                    )
                }
            }
        }
    }
}

@Composable
internal fun PrivateBinderShelfCard(
    binder: PrivateBinderSummary,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DomiGlassCard(modifier.fillMaxWidth(), highlighted = selected, onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            DomiPrivateBinderCover(
                title = binder.name,
                themeId = binder.coverStyle,
                modifier = Modifier.size(94.dp, 122.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Text("Privat", color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
                Text(binder.name, color = VaultText, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                binder.description?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = VaultTextMuted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text("${binder.ownedCards} Karten · unbegrenzte Seiten", color = VaultTextMuted, fontSize = 12.sp, lineHeight = 17.sp)
                Text(
                    if (binder.marketValueEur > 0.0) "Binderwert ${privateBinderEuro(binder.marketValueEur)}" else "Binderwert: Preisstand wird geprüft",
                    color = if (binder.marketValueEur > 0.0) VaultText else VaultTextMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Privaten Binder öffnen", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    DomiLineIcon(DomiIconKind.CHEVRON, MaterialTheme.colorScheme.primary, Modifier.size(14.dp))
                }
            }
        }
    }
}

/** Shared premium private-binder cover used by shelf, editor, selector and opening preview. */
@Composable
internal fun DomiPrivateBinderCover(
    title: String,
    themeId: String?,
    modifier: Modifier = Modifier,
    openingProgress: Float = 0f,
    animated: Boolean = false,
) {
    PrivateBinderCoverVisual(
        themeId = themeId,
        modifier = modifier
            .graphicsLayer {
                rotationY = -openingProgress.coerceIn(0f, 1f) * 8f
                scaleX = 1f + openingProgress.coerceIn(0f, 1f) * .025f
                shadowElevation = 8.dp.toPx()
            }
            .clip(RoundedCornerShape(11.dp))
            .border(1.dp, MewRose.copy(alpha = .72f), RoundedCornerShape(11.dp))
            .padding(7.dp),
        preferences = PrivateBinderVisualPreferences(
            intensity = .72f,
            quality = if (animated) PrivateBinderVisualQuality.MEDIUM else PrivateBinderVisualQuality.LOW,
            reduceMotion = !animated,
        ),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().border(1.dp, MewIce.copy(alpha = .22f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            DomiDexBrandMark(size = minOf(maxWidth, maxHeight) * .48f)
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 7.dp),
                color = Color.Black.copy(alpha = .48f),
                shape = RoundedCornerShape(99.dp),
                border = BorderStroke(1.dp, MewRoseSoft),
            ) {
                Text("PRIVATE", color = MewRose, fontWeight = FontWeight.Black, fontSize = 6.sp, lineHeight = 8.sp, letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Text(
                title,
                color = VaultText,
                fontSize = 7.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 7.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
internal fun PrivateBinderOpeningOverlay(title: String, themeId: String?, stage: BinderPresentationStage) {
    val context = LocalContext.current
    val effectsPreferenceEnabled = remember(context) {
        context.getSharedPreferences("cardvault_binders", Context.MODE_PRIVATE)
            .getBoolean(PRIVATE_BINDER_EFFECTS_PREFERENCE, true)
    }
    val motionEnabled = effectsPreferenceEnabled && rememberDomiMotionEnabled()
    val openTarget = if (stage in setOf(BinderPresentationStage.OPENING, BinderPresentationStage.READY)) 1f else 0f
    val open by animateFloatAsState(openTarget, tween(220), label = "private-binder-open")
    Box(
        Modifier.fillMaxSize().background(Color(0xD9020307)).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DomiPrivateBinderCover(
                title = title,
                themeId = themeId,
                modifier = Modifier.size(154.dp, 200.dp),
                openingProgress = open,
                animated = motionEnabled,
            )
            Text(title, color = VaultText, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Privater Binder wird geöffnet", color = MewRose, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun PrivateBinderEditorDialog(
    initial: PrivateBinderSummary? = null,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String?, coverThemeId: String) -> Unit,
) {
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var description by rememberSaveable(initial?.id) { mutableStateOf(initial?.description.orEmpty()) }
    var coverThemeId by rememberSaveable(initial?.id) {
        mutableStateOf(PrivateBinderVisualEngine.coverTheme(initial?.coverStyle).id)
    }
    var coverSelectorOpen by rememberSaveable(initial?.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val effectsPreferenceEnabled = remember(context) {
        context.getSharedPreferences("cardvault_binders", Context.MODE_PRIVATE)
            .getBoolean(PRIVATE_BINDER_EFFECTS_PREFERENCE, true)
    }
    val animationsEnabled = effectsPreferenceEnabled && rememberDomiMotionEnabled()
    if (coverSelectorOpen) {
        PrivateBinderCoverSelectorDialog(
            initialThemeId = coverThemeId,
            animationsEnabled = animationsEnabled,
            onDismiss = { coverSelectorOpen = false },
            onConfirm = { selected -> coverThemeId = selected; coverSelectorOpen = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (initial == null) "Privaten Binder erstellen" else "Privaten Binder bearbeiten") },
        text = {
            Column(
                Modifier.heightIn(max = 510.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Dein freier DomiDex-Binder mit 3×3-Plätzen und beliebig vielen Seiten.", color = VaultTextMuted, fontSize = 12.sp)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    enabled = !saving,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardVaultShapes.field,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(500) },
                    enabled = !saving,
                    label = { Text("Beschreibung (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardVaultShapes.field,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DomiPrivateBinderCover(
                        title = name.ifBlank { "Dein Binder" },
                        themeId = coverThemeId,
                        modifier = Modifier.size(68.dp, 88.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Cover-Design", color = VaultText, fontWeight = FontWeight.SemiBold)
                        Text(
                            PrivateBinderVisualEngine.coverTheme(coverThemeId).displayName,
                            color = MewIce,
                            fontSize = 11.sp,
                        )
                        OutlinedButton(
                            onClick = { coverSelectorOpen = true },
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Cover-Design auswählen" },
                        ) { Text("Design auswählen") }
                    }
                }
                error?.let { Text(it, color = VaultError, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank() && !saving, onClick = { onSave(name.trim(), description.trim().ifBlank { null }, coverThemeId) }) {
                if (saving) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp) else Text("Speichern")
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
internal fun PrivateBinderReaderScreen(
    binder: PrivateBinderSummary,
    collectionCards: List<CollectionCard>,
    repository: CollectionRepository,
    onBack: () -> Unit,
    onBinderChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val visualPreferences = remember(context) { context.getSharedPreferences("cardvault_binders", Context.MODE_PRIVATE) }
    val placements by repository.privateBinderContents(binder.id).collectAsState(initial = emptyList())
    val setBinderVisibility by repository.setBinderVisibility.collectAsState(initial = emptyMap())
    var page by rememberSaveable(binder.id) { mutableIntStateOf(0) }
    var displayedMinimumPageCount by rememberSaveable(binder.id) { mutableIntStateOf(binder.minimumPageCount) }
    var addPickerOpen by rememberSaveable(binder.id) { mutableStateOf(false) }
    var addPickerTargetPosition by rememberSaveable(binder.id) { mutableStateOf<Long?>(null) }
    var editorOpen by rememberSaveable(binder.id) { mutableStateOf(false) }
    var deleteBinderConfirm by rememberSaveable(binder.id) { mutableStateOf(false) }
    var detailPlacementId by rememberSaveable(binder.id) { mutableStateOf<String?>(null) }
    var priceDetailsEntryId by rememberSaveable(binder.id) { mutableStateOf<Long?>(null) }
    var holoEditorPlacementId by rememberSaveable(binder.id) { mutableStateOf<String?>(null) }
    var movingPlacementId by rememberSaveable(binder.id) { mutableStateOf<String?>(null) }
    var removalCandidate by remember { mutableStateOf<PrivateBinderCard?>(null) }
    var working by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var visualEffectsEnabled by rememberSaveable {
        mutableStateOf(visualPreferences.getBoolean(PRIVATE_BINDER_EFFECTS_PREFERENCE, true))
    }
    val animationsEnabled = visualEffectsEnabled && rememberDomiMotionEnabled()
    val scope = rememberCoroutineScope()
    val occupied = remember(placements) { placements.associateBy(PrivateBinderCard::position) }
    val calculatedPages = privateBinderPageCount(occupied.keys)
    val pageCount = maxOf(displayedMinimumPageCount, binder.minimumPageCount, calculatedPages)
    if (page !in 0 until pageCount) page = (pageCount - 1).coerceAtLeast(0)
    val selectedDetail = placements.firstOrNull { it.placementId == detailPlacementId }
    val moving = placements.firstOrNull { it.placementId == movingPlacementId }
    val movingSlotOnPage = moving?.position
        ?.takeIf { privateBinderPageForPosition(it) == page }
        ?.let(::privateBinderSlotForPosition)
    val pricedCards = placements.map(PrivateBinderCard::card).filter { it.effectiveValueEur != null }
    val binderCardCount = placements.sumOf { it.card.quantity }
    val pricedCardCount = pricedCards.sumOf(CollectionCard::quantity)
    val binderValue = pricedCards.sumOf { (it.effectiveValueEur ?: 0.0) * it.quantity }
    val alternativeCardCount = pricedCards.filter { it.usesPriceSelection }.sumOf(CollectionCard::quantity)

    LaunchedEffect(binder.id) {
        runCatching { repository.markPrivateBinderOpened(binder.id) }
    }
    LaunchedEffect(binder.minimumPageCount) {
        displayedMinimumPageCount = maxOf(displayedMinimumPageCount, binder.minimumPageCount)
    }
    fun moveCard(placementId: String, targetPosition: Long) {
        if (working) return
        working = true
        operationError = null
        scope.launch {
            try {
                repository.movePrivateBinderCard(binder.id, placementId, targetPosition)
                val targetPage = privateBinderPageForPosition(targetPosition)
                val requiredPages = maxOf(displayedMinimumPageCount, targetPage + 1)
                if (requiredPages > binder.minimumPageCount) {
                    repository.setPrivateBinderMinimumPageCount(binder.id, requiredPages)
                }
                displayedMinimumPageCount = requiredPages
                page = targetPage
                movingPlacementId = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                operationError = binderUiError(error, "Die Karte konnte nicht verschoben werden.")
            } finally {
                working = false
            }
        }
    }
    BackHandler {
        when {
            movingPlacementId != null -> movingPlacementId = null
            else -> onBack()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(CardVaultSpacing.screenCompact),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                onClick = onBack,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.semantics { contentDescription = "Zur Binderübersicht" },
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { DomiLineIcon(DomiIconKind.BACK, MaterialTheme.colorScheme.onSurface, Modifier.size(19.dp)) }
            }
            Column(Modifier.weight(1f)) {
                Text(binder.name, color = VaultText, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Privater Binder · Seite ${page + 1} von $pageCount", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp)
            }
            IconButton(onClick = { editorOpen = true }, enabled = !working, modifier = Modifier.semantics { contentDescription = "Privaten Binder bearbeiten" }) {
                DomiLineIcon(DomiIconKind.MORE, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(19.dp))
            }
            PrivateBinderSharingAction(binder, enabled = !working)
            TextButton(onClick = { operationError = null; deleteBinderConfirm = true }, enabled = !working) {
                Text("Löschen", color = VaultError, fontSize = 10.sp)
            }
        }

        DomiGlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("$binderCardCount Karten · ${if (pricedCards.isEmpty()) "Kein Marktwert verfügbar" else privateBinderEuro(binderValue)}", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp)
                    Text(if (alternativeCardCount > 0) "Sammlungswert · $pricedCardCount/$binderCardCount bepreist · $alternativeCardCount Vergleichswerte" else "Cardmarket-Trendwert · $pricedCardCount/$binderCardCount bepreist", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
                    moving?.let { Text("${it.card.name} gewählt · Zielplatz antippen", color = MaterialTheme.colorScheme.tertiary, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold) }
                        ?: Text("Karte lange drücken und ziehen – auch über den Seitenrand.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
                }
                FilterChip(
                    selected = visualEffectsEnabled,
                    onClick = {
                        val enabled = !visualEffectsEnabled
                        visualEffectsEnabled = enabled
                        visualPreferences.edit().putBoolean(PRIVATE_BINDER_EFFECTS_PREFERENCE, enabled).apply()
                    },
                    enabled = !working,
                    label = { Text(if (visualEffectsEnabled) "Animation an" else "Animation aus", fontSize = 9.sp, lineHeight = 13.sp) },
                    modifier = Modifier.semantics { contentDescription = "Animierte Binder-Effekte" },
                )
                Button(onClick = {
                    operationError = null
                    addPickerTargetPosition = null
                    addPickerOpen = true
                }, enabled = !working, shape = RoundedCornerShape(16.dp)) {
                    DomiLineIcon(DomiIconKind.ADD, MaterialTheme.colorScheme.onPrimary, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Karte")
                }
            }
        }

        operationError?.let { Text(it, color = VaultError, fontSize = 11.sp) }

        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                .padding(10.dp),
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { row ->
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .zIndex(if (movingSlotOnPage?.div(3) == row) 1f else 0f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        repeat(3) { column ->
                            val slot = row * 3 + column
                            val position = privateBinderPosition(page, slot)
                            val placement = occupied[position]
                            val selectedForMove = placement?.placementId == movingPlacementId
                            PrivateBinderSlot(
                                placement = placement,
                                selectedForMove = selectedForMove,
                                moveTargetActive = movingPlacementId != null && !selectedForMove,
                                animationsEnabled = animationsEnabled,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .zIndex(if (selectedForMove) 1f else 0f),
                                onClick = {
                                    when {
                                        movingPlacementId != null -> {
                                            val movingId = movingPlacementId ?: return@PrivateBinderSlot
                                            moveCard(movingId, position)
                                        }
                                        placement != null -> detailPlacementId = placement.placementId
                                        else -> {
                                            operationError = null
                                            addPickerTargetPosition = position
                                            addPickerOpen = true
                                        }
                                    }
                                },
                                onLongClick = placement?.let { { movingPlacementId = it.placementId } },
                                onDragStart = placement?.let { { movingPlacementId = it.placementId } },
                                onDragFinished = placement?.let { dragged -> { dragX, dragY, width, height ->
                                    val destination = privateBinderDragDestination(
                                        startPosition = dragged.position,
                                        dragX = dragX,
                                        dragY = dragY,
                                        slotWidth = width,
                                        slotHeight = height,
                                        currentPageCount = pageCount,
                                    )
                                    moveCard(dragged.placementId, destination.position)
                                } },
                                onDragCancelled = { movingPlacementId = null },
                            )
                        }
                    }
                }
            }
            if (placements.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                            DomiLineIcon(DomiIconKind.BINDER, MaterialTheme.colorScheme.onSecondaryContainer, Modifier.size(27.dp))
                        }
                    }
                    Text("Füge deine Lieblingskarten hinzu.", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text("Tippe auf einen freien Platz oder oben auf Karte.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
                }
            }
            Text("DOMIDEX", color = VaultText.copy(alpha = .05f), fontWeight = FontWeight.Black, fontSize = 38.sp, letterSpacing = 4.sp, modifier = Modifier.align(Alignment.Center))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { page-- }, enabled = page > 0 && !working) { Text("‹ Zurück") }
            Text("${page + 1} / $pageCount", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (page == pageCount - 1) TextButton(
                    enabled = !working,
                    onClick = {
                        val previousPageCount = pageCount
                        val requestedPageCount = pageCount + 1
                        displayedMinimumPageCount = requestedPageCount
                        page = previousPageCount
                        working = true
                        operationError = null
                        scope.launch {
                            try {
                                repository.setPrivateBinderMinimumPageCount(binder.id, requestedPageCount)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                displayedMinimumPageCount = maxOf(binder.minimumPageCount, calculatedPages)
                                page = (displayedMinimumPageCount - 1).coerceAtLeast(0)
                                operationError = binderUiError(error, "Die neue Binderseite konnte nicht gespeichert werden.")
                            } finally {
                                working = false
                            }
                        }
                    },
                ) { Text("+ Seite") }
                Button(onClick = { page++ }, enabled = page < pageCount - 1 && !working) { Text("Weiter ›") }
            }
        }
    }

    if (addPickerOpen) {
        PrivateBinderCollectionPicker(
            binder = binder,
            collectionCards = collectionCards,
            assignedEntryIds = placements.mapTo(mutableSetOf()) { it.card.entryId },
            working = working,
            error = operationError,
            onDismiss = {
                if (!working) {
                    addPickerOpen = false
                    addPickerTargetPosition = null
                    operationError = null
                }
            },
            onSelected = { card ->
                working = true
                operationError = null
                val target = addPickerTargetPosition
                    ?.takeIf { it !in occupied }
                    ?: privateBinderNextAvailablePosition(occupied.keys, page)
                scope.launch {
                    try {
                        repository.assignToPrivateBinder(binder.id, card.entryId, target)
                        val requiredPages = maxOf(displayedMinimumPageCount, privateBinderPageForPosition(target) + 1)
                        if (requiredPages > binder.minimumPageCount) {
                            repository.setPrivateBinderMinimumPageCount(binder.id, requiredPages)
                        }
                        displayedMinimumPageCount = requiredPages
                        page = privateBinderPageForPosition(target)
                        addPickerOpen = false
                        addPickerTargetPosition = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        operationError = binderUiError(error, "Die Karte konnte nicht zugeordnet werden.")
                    } finally {
                        working = false
                    }
                }
            },
        )
    }
    selectedDetail?.let { placement ->
        PrivateBinderCardDialog(
            placement = placement,
            visibleInSetBinder = setBinderVisibility[placement.card.entryId] != false,
            animationsEnabled = animationsEnabled,
            onDismiss = { detailPlacementId = null },
            onPriceDetails = { detailPlacementId = null; priceDetailsEntryId = placement.card.entryId },
            onHoloEffect = {
                detailPlacementId = null
                operationError = null
                holoEditorPlacementId = placement.placementId
            },
            onMove = { detailPlacementId = null; movingPlacementId = placement.placementId },
            onRemove = { detailPlacementId = null; operationError = null; removalCandidate = placement },
            onSetBinderVisibilityChanged = { visible ->
                detailPlacementId = null
                if (!working) scope.launch {
                    working = true
                    operationError = null
                    try {
                        repository.setVisibleInSetBinder(placement.card.entryId, visible)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        operationError = binderUiError(error, "Die Set-Binder-Sichtbarkeit konnte nicht gespeichert werden.")
                    } finally {
                        working = false
                    }
                }
            },
        )
    }
    placements.firstOrNull { it.card.entryId == priceDetailsEntryId }?.card?.let { card ->
        CardPreviewDialog(card, repository, onDismiss = { priceDetailsEntryId = null }, onEdit = null)
    }
    placements.firstOrNull { it.placementId == holoEditorPlacementId }?.let { placement ->
        PrivateBinderHoloSelectorDialog(
            placementId = placement.placementId,
            card = placement.card,
            initialEffectId = placement.holoEffectId,
            initialIntensity = placement.holoIntensity,
            animationsEnabled = animationsEnabled,
            saving = working,
            error = operationError,
            onDismiss = {
                if (!working) {
                    holoEditorPlacementId = null
                    operationError = null
                }
            },
            onConfirm = { effectId, intensity ->
                if (!working) {
                    working = true
                    operationError = null
                    scope.launch {
                        try {
                            repository.setPrivateBinderPlacementHolo(
                                binderId = binder.id,
                                placementId = placement.placementId,
                                holoEffectId = effectId,
                                holoIntensity = intensity,
                            )
                            holoEditorPlacementId = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            operationError = binderUiError(error, "Der Holo-Effekt konnte nicht gespeichert werden.")
                        } finally {
                            working = false
                        }
                    }
                }
            },
        )
    }
    removalCandidate?.let { placement ->
        AlertDialog(
            onDismissRequest = { if (!working) removalCandidate = null },
            title = { Text("Nur aus diesem Binder entfernen?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${placement.card.name} bleibt vollständig in deiner Sammlung und in allen anderen Bindern erhalten.")
                operationError?.let { Text(it, color = VaultError, fontSize = 11.sp) }
            } },
            confirmButton = {
                TextButton(enabled = !working, onClick = {
                    working = true
                    scope.launch {
                        try {
                            repository.unassignFromPrivateBinder(binder.id, placement.card.entryId)
                            removalCandidate = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            operationError = binderUiError(error, "Die Karte konnte nicht entfernt werden.")
                        } finally {
                            working = false
                        }
                    }
                }) { Text("Aus Binder entfernen", color = VaultError) }
            },
            dismissButton = { TextButton(enabled = !working, onClick = { removalCandidate = null }) { Text("Abbrechen") } },
        )
    }
    if (editorOpen) {
        PrivateBinderEditorDialog(
            initial = binder,
            saving = working,
            error = operationError,
            onDismiss = { editorOpen = false; operationError = null },
            onSave = { name, description, coverThemeId ->
                working = true
                operationError = null
                scope.launch {
                    try {
                        repository.updatePrivateBinder(binder.id, name, description, coverThemeId)
                        editorOpen = false
                        onBinderChanged()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        operationError = binderUiError(error, "Der Binder konnte nicht gespeichert werden.")
                    } finally {
                        working = false
                    }
                }
            },
        )
    }
    if (deleteBinderConfirm) {
        AlertDialog(
            onDismissRequest = { if (!working) deleteBinderConfirm = false },
            title = { Text("Privaten Binder löschen?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Nur der private Binder und seine Anordnung werden gelöscht. Deine Karten und ihre Preise bleiben in der Sammlung erhalten.")
                operationError?.let { Text(it, color = VaultError, fontSize = 11.sp) }
            } },
            confirmButton = {
                TextButton(enabled = !working, onClick = {
                    working = true
                    operationError = null
                    scope.launch {
                        try {
                            repository.deletePrivateBinder(binder.id)
                            deleteBinderConfirm = false
                            onBack()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            operationError = binderUiError(error, "Der Binder konnte nicht gelöscht werden.")
                        } finally {
                            working = false
                        }
                    }
                }) { Text("Binder löschen", color = VaultError) }
            },
            dismissButton = { TextButton(enabled = !working, onClick = { deleteBinderConfirm = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun PrivateBinderSlot(
    placement: PrivateBinderCard?,
    selectedForMove: Boolean,
    moveTargetActive: Boolean,
    animationsEnabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onDragStart: (() -> Unit)?,
    onDragFinished: ((dragX: Float, dragY: Float, width: Float, height: Float) -> Unit)?,
    onDragCancelled: () -> Unit,
) {
    var dragOffset by remember(placement?.placementId) { mutableStateOf(Offset.Zero) }
    val tiltState = rememberPrivateBinderTiltState()
    LaunchedEffect(selectedForMove, placement?.placementId) {
        if (selectedForMove || placement == null) tiltState.reset()
    }
    val emphasis by animateFloatAsState(
        targetValue = if (selectedForMove) .93f else 1f,
        animationSpec = tween(140),
        label = "private-binder-slot",
    )
    val outline = when {
        selectedForMove -> MewRose
        moveTargetActive -> MewRose.copy(alpha = .64f)
        placement != null -> Color.Transparent
        else -> VaultOutlineSoft
    }
    Box(
        modifier
            .graphicsLayer {
                scaleX = emphasis
                scaleY = emphasis
                translationX = dragOffset.x
                translationY = dragOffset.y
            }
            .clip(RoundedCornerShape(8.dp))
            .background(if (placement == null) Color(0xFF0B0D14) else Color(placement.card.accent), RoundedCornerShape(8.dp))
            .border(1.dp, outline, RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                if (placement != null && onDragFinished != null) Modifier.pointerInput(placement.placementId) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            tiltState.reset()
                            dragOffset = Offset.Zero
                            onDragStart?.invoke()
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount
                        },
                        onDragEnd = {
                            val finalOffset = dragOffset
                            dragOffset = Offset.Zero
                            if (abs(finalOffset.x) >= size.width * .18f || abs(finalOffset.y) >= size.height * .18f) {
                                onDragFinished(finalOffset.x, finalOffset.y, size.width.toFloat(), size.height.toFloat())
                            }
                        },
                        onDragCancel = {
                            dragOffset = Offset.Zero
                            onDragCancelled()
                        },
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        placement?.card?.let { card ->
            BoxWithConstraints(
                Modifier.fillMaxSize().padding(bottom = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                val cardRatio = 63f / 88f
                val cardWidth = minOf(maxWidth, maxHeight * cardRatio)
                Box(
                    Modifier
                        .width(cardWidth)
                        .aspectRatio(cardRatio)
                        .privateBinderTouchTilt(
                            state = tiltState,
                            enabled = animationsEnabled && !selectedForMove,
                            maxTiltDegrees = 3.5f,
                        )
                        .clip(RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!card.imageUrl.isNullOrBlank()) {
                        AsyncImage(card.imageUrl, card.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    } else {
                        Text(card.name.take(1), color = VaultText, fontSize = 32.sp, fontWeight = FontWeight.Black)
                    }
                    PrivateBinderStaticHoloLayer(
                        effectId = placement.holoEffectId,
                        intensity = placement.holoIntensity,
                        modifier = Modifier.fillMaxSize(),
                        tiltState = tiltState,
                    )
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .70f)).padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(card.name, color = VaultText, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${card.number.substringBefore('/')} · ${card.finish}", color = MewIce, fontSize = 7.sp, maxLines = 1)
            }
        } ?: Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DomiLineIcon(DomiIconKind.ADD, if (moveTargetActive) MewRose else VaultTextFaint, Modifier.size(19.dp))
            Text(if (moveTargetActive) "Hier ablegen" else "Freier Platz", color = if (moveTargetActive) MewRose else VaultTextFaint, fontSize = 8.sp, textAlign = TextAlign.Center)
        }
        if (selectedForMove) Surface(
            Modifier.align(Alignment.TopEnd).padding(4.dp),
            color = MewPlumDeep.copy(alpha = .94f),
            border = BorderStroke(1.dp, MewRose.copy(alpha = .5f)),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("Verschieben", color = VaultText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun PrivateBinderCollectionPicker(
    binder: PrivateBinderSummary,
    collectionCards: List<CollectionCard>,
    assignedEntryIds: Set<Long>,
    working: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSelected: (CollectionCard) -> Unit,
) {
    var query by rememberSaveable(binder.id) { mutableStateOf("") }
    val available = remember(collectionCards, assignedEntryIds, query) {
        collectionCards.asSequence()
            .filterNot { it.entryId in assignedEntryIds }
            .filter { query.isBlank() || it.name.contains(query, true) || it.set.contains(query, true) || it.number.contains(query, true) }
            .sortedWith(compareBy<CollectionCard>({ it.set.lowercase(Locale.ROOT) }, { it.number }, { it.name.lowercase(Locale.ROOT) }))
            .toList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Karte in ${binder.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Vorhandene Sammlungskarte zuordnen. Die Kartenmenge und dein Portfolio bleiben unverändert.", color = VaultTextMuted, fontSize = 11.sp)
                OutlinedTextField(query, { query = it }, enabled = !working, label = { Text("Sammlung durchsuchen") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = VaultError, fontSize = 11.sp) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (available.isEmpty()) item {
                        Text(
                            if (collectionCards.isEmpty()) "Deine Sammlung ist noch leer." else "Keine weitere passende Karte verfügbar.",
                            color = VaultTextMuted,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                    items(available, key = CollectionCard::entryId) { card ->
                        OutlinedCard(
                            onClick = { if (!working) onSelected(card) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised),
                            border = BorderStroke(1.dp, VaultOutline),
                        ) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                if (!card.imageUrl.isNullOrBlank()) AsyncImage(card.imageUrl, card.name, Modifier.size(44.dp, 61.dp), contentScale = ContentScale.Fit)
                                else Box(Modifier.size(44.dp, 61.dp).background(MewPlumDeep, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { Text(card.name.take(1), fontWeight = FontWeight.Black) }
                                Column(Modifier.weight(1f)) {
                                    Text(card.name, color = VaultText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${card.set} · ${card.number}", color = VaultTextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${card.finish} · ${card.effectiveValueEur?.let(::privateBinderEuro) ?: "Preis nicht verfügbar"}" + if (card.usesPriceSelection) " · Vergleichswert" else "", color = MewIce, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("Schließen") } },
    )
}

@Composable
private fun PrivateBinderCardDialog(
    placement: PrivateBinderCard,
    visibleInSetBinder: Boolean,
    animationsEnabled: Boolean,
    onDismiss: () -> Unit,
    onPriceDetails: () -> Unit,
    onHoloEffect: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
    onSetBinderVisibilityChanged: (Boolean) -> Unit,
) {
    val card = placement.card
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card.name) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                item {
                    PrivateBinderInteractiveCardVisual(
                        card = card,
                        effectId = placement.holoEffectId,
                        intensity = placement.holoIntensity,
                        animationsEnabled = animationsEnabled,
                        modifier = Modifier.height(230.dp),
                    )
                }
                item { Text("${card.set} · ${card.number}", color = VaultTextMuted, fontSize = 12.sp) }
                item { Text("${card.finish} · ${card.variant}", color = MewIce, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                item {
                    Text(
                        card.effectiveValueEur?.let { "${card.valuationLabel}: ${privateBinderEuro(it)}" } ?: "Preis nicht verfügbar",
                        color = if (card.effectiveValueEur != null) VaultPositive else VaultTextMuted,
                        fontWeight = FontWeight.Bold,
                    )
                }
                item {
                    OutlinedButton(onClick = onPriceDetails, modifier = Modifier.fillMaxWidth()) { Text("Preisdetails & Vergleichswerte") }
                }
                item {
                    OutlinedButton(onClick = onHoloEffect, modifier = Modifier.fillMaxWidth()) {
                        DomiLineIcon(DomiIconKind.CARD, MewRose, Modifier.size(16.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(
                            placement.holoEffectId
                                ?.let { PrivateBinderVisualEngine.holoEffect(it).displayName }
                                ?.let { "Holo-Effekt · $it" }
                                ?: "Holo-Effekt wählen",
                        )
                    }
                }
                item {
                    OutlinedButton(onClick = { onSetBinderVisibilityChanged(!visibleInSetBinder) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (visibleInSetBinder) "Aus Set-Binder ausblenden" else "Auch im Set-Binder anzeigen")
                    }
                }
                item { Text("Binderplatz ${placement.position + 1}", color = VaultTextFaint, fontSize = 10.sp) }
            }
        },
        confirmButton = { Button(onClick = onMove) { Text("Verschieben") } },
        dismissButton = {
            Row {
                TextButton(onClick = onRemove) { Text("Aus Binder", color = VaultError) }
                TextButton(onClick = onDismiss) { Text("Schließen") }
            }
        },
    )
}

private fun privateBinderEuro(value: Double): String = NumberFormat.getCurrencyInstance(Locale.GERMANY).format(value)
