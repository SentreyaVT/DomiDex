package com.dominik.cardvault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.dominik.cardvault.data.sharing.*
import com.dominik.cardvault.ui.theme.*
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect

@Composable
internal fun SharedBinderScreen(
    token: String?, gateway: SharedBinderGateway, visible: Boolean, onClose: () -> Unit,
    pollMillis: Long = 15_000,
) = key(token, gateway) {
    var page by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var selectedSlot by remember { mutableStateOf<Int?>(null) }
    var state by remember { mutableStateOf(SharedBinderViewState(loading = true)) }
    var requestVersion by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(token, page, reload, visible, lifecycle) {
        val version = ++requestVersion
        selectedSlot = null
        state = SharedBinderViewState(loading = visible && token != null)
        if (token == null || !visible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                observeSharedBinderPage(gateway, token, page, pollMillis).collect {
                    if (version == requestVersion) {
                        state = it
                        if (it.binder == null) selectedSlot = null
                    }
                }
            } catch (cancelled: CancellationException) {
                if (version == requestVersion) {
                    state = SharedBinderViewState()
                    selectedSlot = null
                }
                throw cancelled
            }
        }
    }
    val binder = state.binder.takeIf { visible }
    val selected = binder?.cards?.firstOrNull { it.slot == selectedSlot }
    BackHandler { if (selectedSlot != null) selectedSlot = null else onClose() }
    DomiDexBackdrop(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DomiDex", color = MewIce, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Geteilter Binder · Nur ansehen", color = VaultTextMuted, fontSize = 11.sp)
                }
                TextButton(onClick = onClose) { Text("Schließen") }
            }
            when {
                token == null -> SharedBinderError(SharedBinderContract.unavailable().message.orEmpty(), null)
                state.error != null -> SharedBinderError(state.error.orEmpty()) { reload++ }
                binder == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (visible) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("Binder wird geladen …", color = VaultTextMuted)
                    }
                }
                else -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrivateBinderCoverVisual(
                            binder.coverStyle,
                            Modifier.size(48.dp, 62.dp).clip(RoundedCornerShape(7.dp)),
                            PrivateBinderVisualPreferences(reduceMotion = true, quality = PrivateBinderVisualQuality.LOW),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DomiDexBrandMark(size = 30.dp) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(binder.title, color = VaultText, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            binder.displayName?.let { Text("Geteilt von $it", color = MewIce, fontSize = 11.sp, maxLines = 1) }
                            binder.total?.let { total ->
                                Text(
                                    "Binderwert: ${if (total.complete && total.amountEur != null) sharedEuro(total.amountEur) else "Nicht vollständig verfügbar"}",
                                    color = VaultTextMuted, fontSize = 11.sp,
                                )
                            }
                        }
                    }
                    Column(
                        Modifier.weight(1f).fillMaxWidth().background(Color(0xFF080A12), RoundedCornerShape(14.dp))
                            .border(1.dp, MewIce.copy(alpha = .30f), RoundedCornerShape(14.dp)).padding(8.dp)
                            .testTag("shared-binder-grid"),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        repeat(3) { row ->
                            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                repeat(3) { column ->
                                    val slot = page * 9 + row * 3 + column
                                    val card = binder.cards.firstOrNull { it.slot == slot }
                                    SharedBinderSlot(card, slot in binder.unavailableSlots, gateway, Modifier.weight(1f).fillMaxHeight()) {
                                        selectedSlot = slot
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedButton(onClick = { selectedSlot = null; page-- }, enabled = page > 0, modifier = Modifier.testTag("shared-page-previous")) { Text("Zurück") }
                        Text("Seite ${binder.page + 1} / ${binder.pageCount}", color = VaultTextMuted, fontSize = 12.sp)
                        OutlinedButton(onClick = { selectedSlot = null; page++ }, enabled = page + 1 < binder.pageCount, modifier = Modifier.testTag("shared-page-next")) { Text("Weiter") }
                    }
                }
            }
        }
    }
    selected?.let { card ->
        AlertDialog(
            onDismissRequest = { selectedSlot = null },
            title = { Text(card.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SharedBinderCardVisual(card, gateway, Modifier.height(280.dp), interactive = true)
                    Text("${card.setName} · ${card.number}", color = VaultTextMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
                    Text(card.finish, color = MewIce, fontSize = 12.sp)
                    card.price?.let { price ->
                        Text("Cardmarket: ${price.amountEur?.let(::sharedEuro) ?: "Preis nicht verfügbar"}", color = VaultText)
                        price.asOf?.let { Text("Preisstand: $it", color = VaultTextMuted, fontSize = 10.sp) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedSlot = null }) { Text("Großansicht schließen") } },
            modifier = Modifier.testTag("shared-card-detail"),
        )
    }
}

@Composable
private fun SharedBinderError(message: String, retry: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Freigabe nicht verfügbar", color = VaultText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(message, color = VaultTextMuted, modifier = Modifier.testTag("shared-binder-error"))
        retry?.let { OutlinedButton(onClick = it) { Text("Erneut versuchen") } }
    }
}

@Composable
private fun SharedBinderSlot(card: SharedBinderCard?, unavailable: Boolean, gateway: SharedBinderGateway, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(7.dp)).background(VaultNightRaised)
            .then(if (card == null) Modifier else Modifier.clickable(onClick = onClick).semantics { contentDescription = "${card.name}, Nummer ${card.number}, groß ansehen" }),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (card == null) {
            Box(Modifier.fillMaxSize().border(1.dp, VaultOutlineSoft, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                Text(if (unavailable) "Kartendaten gerade nicht verfügbar" else "Freier Platz", color = VaultTextFaint, fontSize = 9.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(5.dp))
            }
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                SharedBinderCardVisual(card, gateway, Modifier.width(minOf(maxWidth, maxHeight * 63f / 88f)), interactive = false)
            }
            Text(card.name, color = VaultText, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 3.dp))
            card.price?.let { Text(it.amountEur?.let(::sharedEuro) ?: "Preis nicht verfügbar", color = MewIce, fontSize = 9.sp, maxLines = 1) }
            Spacer(Modifier.height(3.dp))
        }
    }
}

/** Existing native cover/foil/tilt renderers with separately fetched, memory-only public artwork. */
@Composable
private fun SharedBinderCardVisual(card: SharedBinderCard, gateway: SharedBinderGateway, modifier: Modifier, interactive: Boolean) {
    var bytes by remember(card.imageUrl, gateway) { mutableStateOf<ByteArray?>(null) }
    var imageFailed by remember(card.imageUrl, gateway) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(card.imageUrl, gateway, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            bytes = null
            imageFailed = false
            val url = card.imageUrl ?: return@repeatOnLifecycle
            try {
                try { bytes = gateway.image(url) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { imageFailed = true }
                awaitCancellation()
            } finally { bytes = null }
        }
    }
    val context = LocalContext.current
    val imageRequest = remember(bytes, context) {
        bytes?.let {
            ImageRequest.Builder(context).data(it).size(630, 880)
                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
        }
    }
    val tilt = rememberPrivateBinderTiltState()
    val motionEnabled = interactive && rememberDomiMotionEnabled()
    Box(
        modifier.aspectRatio(63f / 88f).privateBinderTouchTilt(tilt, enabled = motionEnabled, maxTiltDegrees = 7f)
            .clip(RoundedCornerShape(7.dp)).background(VaultNightRaised),
        contentAlignment = Alignment.Center,
    ) {
        if (imageRequest != null) AsyncImage(imageRequest, card.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Text(
            if (imageFailed) "Bild nicht verfügbar" else if (card.imageUrl == null) card.name else "Bild lädt …",
            color = VaultTextMuted, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(5.dp),
        )
        if (interactive) PrivateBinderHoloLayer(
            card.holoEffectId, Modifier.fillMaxSize(),
            PrivateBinderVisualPreferences(intensity = card.holoIntensity, reduceMotion = !motionEnabled), tilt,
        ) else PrivateBinderStaticHoloLayer(card.holoEffectId, card.holoIntensity, Modifier.fillMaxSize())
    }
}

private fun sharedEuro(amount: Double): String = NumberFormat.getCurrencyInstance(Locale.GERMANY).format(amount)
