package com.dominik.cardvault.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dominik.cardvault.ui.theme.CardVaultShapes
import com.dominik.cardvault.ui.theme.MewIce
import com.dominik.cardvault.ui.theme.MewPlum
import com.dominik.cardvault.ui.theme.MewPlumDeep
import com.dominik.cardvault.ui.theme.MewRose
import com.dominik.cardvault.ui.theme.VaultGlassHigh
import com.dominik.cardvault.ui.theme.VaultNight
import com.dominik.cardvault.ui.theme.VaultNightRaised
import com.dominik.cardvault.ui.theme.VaultOutlineSoft
import com.dominik.cardvault.ui.theme.VaultScrim
import com.dominik.cardvault.ui.theme.VaultText
import com.dominik.cardvault.ui.theme.VaultTextMuted
import com.dominik.cardvault.model.CollectionCard

/**
 * Presentation-only Binder sequence. Repository and navigation state never
 * depend on these values; reduced motion may safely jump straight to READY.
 */
internal enum class BinderPresentationStage {
    IDLE,
    SELECTED,
    APPROACHING,
    UNLATCHING,
    OPENING,
    READY,
    PAGE_TURNING,
    CARD_INSERTING,
    CLOSING,
    RETURNING,
}

internal data class BinderPresentationBeat(val stage: BinderPresentationStage, val durationMillis: Long)

internal fun binderPresentationBeats(motionEnabled: Boolean): List<BinderPresentationBeat> =
    if (!motionEnabled) listOf(BinderPresentationBeat(BinderPresentationStage.READY, 0L))
    else listOf(
        BinderPresentationBeat(BinderPresentationStage.SELECTED, 80L),
        BinderPresentationBeat(BinderPresentationStage.APPROACHING, 150L),
        BinderPresentationBeat(BinderPresentationStage.UNLATCHING, 150L),
        BinderPresentationBeat(BinderPresentationStage.OPENING, 300L),
        BinderPresentationBeat(BinderPresentationStage.READY, 180L),
    )

@Composable
internal fun DomiBinderShelfCard(
    title: String,
    owned: Int,
    total: Int?,
    cardCount: Int,
    marketValue: String,
    logoUrl: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val knownTotal = total?.takeIf { it > 0 }
    DomiGlassCard(modifier.fillMaxWidth(), highlighted = selected, onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DomiPhysicalBinder(
                title = title,
                openingProgress = 0f,
                modifier = Modifier.size(94.dp, 122.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, color = VaultText, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (logoUrl != null) AsyncImage(logoUrl, "$title Set-Logo", Modifier.size(76.dp, 28.dp))
                Text("$cardCount Karten im Binder", color = VaultTextMuted, fontSize = 11.sp)
                Text(
                    if (knownTotal != null) "$owned / $knownTotal Sammelplätze" else "Slotzahl nach Laden des Sets verfügbar",
                    color = MewIce, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(marketValue, color = VaultText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                if (knownTotal != null) Box(Modifier.fillMaxWidth().height(4.dp).background(VaultOutlineSoft, RoundedCornerShape(99.dp))) {
                    Box(
                        Modifier
                            .fillMaxWidth((owned.toFloat() / knownTotal).coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(Brush.horizontalGradient(listOf(MewPlum, MewRose, MewIce)), RoundedCornerShape(99.dp)),
                    )
                }
                Text("Binder öffnen", color = MewRose, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            if (knownTotal != null) MasterSetProgressRing(owned, knownTotal)
        }
    }
}

@Composable
internal fun BinderOpeningOverlay(
    title: String,
    stage: BinderPresentationStage,
    modifier: Modifier = Modifier,
) {
    val approachingTarget = when (stage) {
        BinderPresentationStage.IDLE, BinderPresentationStage.SELECTED, BinderPresentationStage.RETURNING -> .72f
        else -> 1f
    }
    val openingTarget = when (stage) {
        BinderPresentationStage.OPENING -> .72f
        BinderPresentationStage.READY, BinderPresentationStage.PAGE_TURNING, BinderPresentationStage.CARD_INSERTING -> 1f
        BinderPresentationStage.CLOSING -> .24f
        else -> 0f
    }
    val approach by animateFloatAsState(approachingTarget, spring(dampingRatio = .82f, stiffness = 300f), label = "binder-approach")
    val opening by animateFloatAsState(openingTarget, tween(if (stage == BinderPresentationStage.READY) 190 else 300), label = "binder-open")
    val latch by animateFloatAsState(
        if (stage in setOf(BinderPresentationStage.UNLATCHING, BinderPresentationStage.OPENING, BinderPresentationStage.READY, BinderPresentationStage.PAGE_TURNING, BinderPresentationStage.CARD_INSERTING, BinderPresentationStage.CLOSING)) 1f else 0f,
        tween(150),
        label = "binder-latch",
    )
    val message = when (stage) {
        BinderPresentationStage.SELECTED -> "Binder ausgewählt"
        BinderPresentationStage.APPROACHING -> "Binder wird geöffnet"
        BinderPresentationStage.UNLATCHING -> "Verschluss lösen"
        BinderPresentationStage.OPENING -> "Seiten vorbereiten"
        BinderPresentationStage.READY -> "Bereit zum Blättern"
        BinderPresentationStage.PAGE_TURNING -> "Seite wird umgeblättert"
        BinderPresentationStage.CARD_INSERTING -> "Karte wird einsortiert"
        BinderPresentationStage.CLOSING -> "Binder wird geschlossen"
        BinderPresentationStage.RETURNING -> "Zurück zu deinen Bindern"
        BinderPresentationStage.IDLE -> ""
    }
    Box(
        modifier
            .fillMaxSize()
            .background(VaultScrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(listOf(MewRose.copy(alpha = .20f), MewPlum.copy(alpha = .06f), Color.Transparent)),
                size.minDimension * .62f,
                Offset(size.width * .5f, size.height * .47f),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            DomiPhysicalBinder(
                title = title,
                openingProgress = opening,
                latchProgress = latch,
                modifier = Modifier
                    .size(270.dp, 210.dp)
                    .graphicsLayer {
                        scaleX = .76f + approach * .24f
                        scaleY = .76f + approach * .24f
                        translationY = (1f - approach) * 50.dp.toPx()
                    },
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, color = VaultText, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(message, color = MewRose, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .7.sp)
            }
        }
    }
}

/** Original DomiDex binder: geometric, code-native and independent from card artwork. */
@Composable
internal fun DomiPhysicalBinder(
    title: String,
    openingProgress: Float,
    modifier: Modifier = Modifier,
    latchProgress: Float = 0f,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        // Page block becomes visible only while opening.
        Box(
            Modifier
                .fillMaxSize(.78f)
                .offset(x = 9.dp)
                .graphicsLayer { alpha = openingProgress.coerceIn(0f, 1f) }
                .background(Color(0xFFB8BAC3), RoundedCornerShape(7.dp))
                .border(1.dp, Color.White.copy(alpha = .35f), RoundedCornerShape(7.dp)),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                repeat(8) { index ->
                    val x = size.width * (.12f + (index % 4) * .2f)
                    val y = size.height * (.18f + (index / 4) * .42f)
                    drawRoundRect(Color(0xFF1B1924), Offset(x, y), Size(size.width * .14f, size.height * .30f), androidx.compose.ui.geometry.CornerRadius(5f))
                }
                drawLine(Color.Black.copy(alpha = .35f), Offset(size.width * .08f, 0f), Offset(size.width * .08f, size.height), 2.dp.toPx())
            }
        }

        // Back cover / spine.
        Box(
            Modifier
                .fillMaxSize(.88f)
                .offset(x = 8.dp)
                .background(Brush.linearGradient(listOf(Color(0xFF111524), Color(0xFF070912))), RoundedCornerShape(10.dp))
                .border(1.dp, MewPlum.copy(alpha = .45f), RoundedCornerShape(10.dp)),
        )

        // Front cover rotates around its left spine.
        Box(
            Modifier
                .fillMaxSize(.88f)
                .graphicsLayer {
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f)
                    rotationY = -112f * openingProgress.coerceIn(0f, 1f)
                    cameraDistance = 18f * density
                    shadowElevation = 10.dp.toPx()
                }
                .background(Brush.linearGradient(listOf(Color(0xFF15192B), Color(0xFF090A12), Color(0xFF19102B))), RoundedCornerShape(10.dp))
                .border(1.dp, MewRose.copy(alpha = .52f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                drawRoundRect(MewPlum.copy(alpha = .24f), style = Stroke(1.5.dp.toPx()))
                drawRoundRect(MewIce.copy(alpha = .14f), Offset(size.width * .08f, size.height * .08f), Size(size.width * .84f, size.height * .84f), style = Stroke(1.dp.toPx()))
                val center = Offset(size.width * .5f, size.height * .44f)
                val radius = size.minDimension * .22f
                val diamond = Path().apply {
                    moveTo(center.x, center.y - radius)
                    lineTo(center.x + radius, center.y)
                    lineTo(center.x, center.y + radius)
                    lineTo(center.x - radius, center.y)
                    close()
                }
                drawPath(diamond, MewPlumDeep)
                drawPath(diamond, MewRose, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
            }
            Text("D", color = VaultText, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text(
                title.uppercase(),
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 8.dp),
                color = VaultTextMuted,
                fontSize = 6.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                letterSpacing = .7.sp,
            )
        }

        // The strap moves away before the cover opens.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .offset(x = (-2).dp + 20.dp * latchProgress)
                .size(20.dp, 58.dp)
                .graphicsLayer { alpha = 1f - openingProgress }
                .background(Brush.horizontalGradient(listOf(Color(0xFF222637), Color(0xFF0A0C13))), RoundedCornerShape(6.dp))
                .border(1.dp, MewIce.copy(alpha = .24f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { Canvas(Modifier.size(7.dp)) { drawCircle(MewRose.copy(alpha = .72f), size.minDimension / 2f) } }
    }
}

@Composable
internal fun BinderInsertionOverlay(card: CollectionCard, modifier: Modifier = Modifier) {
    val motionEnabled = rememberDomiMotionEnabled()
    var settled by remember(card.id, card.entryId) { mutableStateOf(!motionEnabled) }
    LaunchedEffect(card.id, card.entryId, motionEnabled) { settled = true }
    val travel by animateFloatAsState(if (settled) 0f else -92f, tween(300), label = "binder-insert-travel")
    val scale by animateFloatAsState(if (settled) 1f else .82f, tween(300), label = "binder-insert-scale")
    Box(modifier.fillMaxSize().background(VaultScrim.copy(alpha = .78f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(
                Modifier.size(186.dp, 258.dp).background(Color(0xFF090A10), RoundedCornerShape(18.dp)).border(2.dp, MewRose.copy(alpha = .58f), RoundedCornerShape(18.dp)).padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxSize().background(Color(0xFF171821), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    if (card.displayImageUrl != null) AsyncImage(
                        card.displayImageUrl,
                        card.name,
                        Modifier.fillMaxSize().graphicsLayer { translationY = travel; scaleX = scale; scaleY = scale },
                    ) else DomiDexBrandMark(size = 58.dp)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DomiLineIcon(DomiIconKind.CHECK, MewIce, Modifier.size(24.dp))
                Text("Karte sitzt im Binder", color = VaultText, style = MaterialTheme.typography.titleLarge)
                Text(card.name, color = VaultTextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
