package com.dominik.cardvault.ui

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dominik.cardvault.ui.theme.CardVaultShapes
import com.dominik.cardvault.ui.theme.CardVaultSpacing
import com.dominik.cardvault.ui.theme.DomiDexAlpha
import com.dominik.cardvault.ui.theme.MewIce
import com.dominik.cardvault.ui.theme.MewPlum
import com.dominik.cardvault.ui.theme.MewPlumDeep
import com.dominik.cardvault.ui.theme.MewRose
import com.dominik.cardvault.ui.theme.VaultGlass
import com.dominik.cardvault.ui.theme.VaultNight
import com.dominik.cardvault.ui.theme.VaultNightRaised
import com.dominik.cardvault.ui.theme.VaultOutline
import com.dominik.cardvault.ui.theme.VaultOutlineSoft
import com.dominik.cardvault.ui.theme.VaultPositive
import com.dominik.cardvault.ui.theme.VaultText
import com.dominik.cardvault.ui.theme.VaultTextMuted
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Presentation-only icon vocabulary. No external icon pack or franchise art is required. */
internal enum class DomiIconKind {
    HOME, SCAN, BINDER, COLLECTION, SETTINGS, SEARCH, ADD, MARKET, CARD,
    PROFILE, BACK, MORE, INFO, SHIELD, SYNC, CHECK, CHEVRON, LIGHTNING,
}

/** Honors the operating system's reduced-motion/disabled-animation setting. */
@Composable
internal fun rememberDomiMotionEnabled(): Boolean = remember { ValueAnimator.areAnimatorsEnabled() }

/** Static, GPU-cheap DomiDex background. It deliberately contains no infinite animation. */
@Composable
internal fun DomiDexBackdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to VaultNight,
                    .46f to Color(0xFF070711),
                    1f to Color(0xFF05040C),
                ),
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(MewPlum.copy(alpha = .12f), Color.Transparent),
                    center = Offset(size.width * .88f, size.height * .14f),
                    radius = size.minDimension * .62f,
                ),
                radius = size.minDimension * .62f,
                center = Offset(size.width * .88f, size.height * .14f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(MewIce.copy(alpha = .045f), Color.Transparent),
                    center = Offset(size.width * .04f, size.height * .78f),
                    radius = size.minDimension * .48f,
                ),
                radius = size.minDimension * .48f,
                center = Offset(size.width * .04f, size.height * .78f),
            )
            // Deterministic micro-points give depth without a particle animation.
            repeat(16) { index ->
                val x = ((index * 47) % 97) / 97f * size.width
                val y = ((index * 71) % 101) / 101f * size.height
                drawCircle(
                    color = if (index % 3 == 0) MewIce else MewRose,
                    radius = if (index % 4 == 0) 1.45f else .8f,
                    center = Offset(x, y),
                    alpha = if (index % 4 == 0) .18f else .10f,
                )
            }
        }
        content()
    }
}

@Composable
internal fun DomiDexBrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 54.dp,
    showWordmark: Boolean = false,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                fun hex(radius: Float): Path = Path().apply {
                    repeat(6) { index ->
                        val angle = -PI / 2 + index * PI / 3
                        val point = Offset(
                            center.x + cos(angle).toFloat() * radius,
                            center.y + sin(angle).toFloat() * radius,
                        )
                        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                    }
                    close()
                }
                drawPath(hex(this.size.minDimension * .46f), MewPlum.copy(alpha = .18f))
                drawPath(hex(this.size.minDimension * .43f), MewRose, style = Stroke(2.2.dp.toPx(), join = StrokeJoin.Round))
                drawPath(hex(this.size.minDimension * .31f), MewIce.copy(alpha = .42f), style = Stroke(1.dp.toPx(), join = StrokeJoin.Round))
            }
            Text("D", color = VaultText, fontSize = (size.value * .43f).sp, fontWeight = FontWeight.Black)
        }
        if (showWordmark) {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text("DomiDex", color = VaultText, style = MaterialTheme.typography.titleLarge)
                Text("DEINE KARTEN. DEIN BINDER.", color = MewRose, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.15.sp)
            }
        }
    }
}

@Composable
internal fun DomiGlassCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val clickableModifier = if (onClick == null) Modifier else Modifier.clickable(
        interactionSource = interaction,
        indication = null,
        onClick = onClick,
    )
    Surface(
        modifier = modifier.then(clickableModifier),
        shape = CardVaultShapes.card,
        color = if (highlighted) Color(0xE6161128) else VaultGlass,
        contentColor = VaultText,
        border = BorderStroke(
            1.dp,
            if (highlighted) MewRose.copy(alpha = DomiDexAlpha.RestingBorder) else VaultOutlineSoft,
        ),
        shadowElevation = if (highlighted) 7.dp else 1.dp,
    ) { content() }
}

@Composable
internal fun DomiScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (eyebrow != null) Text(
                eyebrow.uppercase(),
                color = MewRose,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.3.sp,
            )
            Text(title, color = VaultText, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) Text(subtitle, color = VaultTextMuted, style = MaterialTheme.typography.bodyMedium)
        }
        trailing?.invoke()
    }
}

@Composable
internal fun DomiSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), Modifier.weight(1f), color = VaultText, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .75.sp)
        if (action != null && onAction != null) Text(
            action,
            Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onAction),
            color = MewRose,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun DomiPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: DomiIconKind? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        shape = CardVaultShapes.cardCompact,
        color = if (selected) MewPlumDeep.copy(alpha = .78f) else VaultNightRaised,
        border = BorderStroke(1.dp, if (selected) MewRose.copy(alpha = .72f) else VaultOutlineSoft),
    ) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) DomiLineIcon(icon, if (selected) MewRose else VaultTextMuted, Modifier.size(15.dp))
            Text(label, color = if (selected) VaultText else VaultTextMuted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
internal fun DomiSettingRow(
    icon: DomiIconKind,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = CardVaultSpacing.card, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(34.dp).background(MewPlumDeep.copy(alpha = .52f), RoundedCornerShape(10.dp)).border(1.dp, MewPlum.copy(alpha = .25f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { DomiLineIcon(icon, MewRose, Modifier.size(17.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = VaultText, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, color = VaultTextMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) Text(value, color = VaultTextMuted, fontSize = 11.sp)
        DomiLineIcon(DomiIconKind.CHEVRON, VaultTextMuted, Modifier.size(15.dp))
    }
}

@Composable
internal fun DomiLineIcon(
    kind: DomiIconKind,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Canvas(modifier.semantics { if (contentDescription != null) this.contentDescription = contentDescription }) {
        val w = size.width
        val h = size.height
        val strokeWidth = size.minDimension * .085f
        val stroke = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(a: Offset, b: Offset) = drawLine(tint, a, b, strokeWidth, StrokeCap.Round)
        fun rect(left: Float, top: Float, right: Float, bottom: Float, radius: Float = w * .12f) =
            drawRoundRect(tint, Offset(left, top), Size(right - left, bottom - top), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius), style = stroke)

        when (kind) {
            DomiIconKind.HOME -> {
                val p = Path().apply { moveTo(w * .14f, h * .48f); lineTo(w * .5f, h * .17f); lineTo(w * .86f, h * .48f); moveTo(w * .25f, h * .43f); lineTo(w * .25f, h * .84f); lineTo(w * .75f, h * .84f); lineTo(w * .75f, h * .43f) }
                drawPath(p, tint, style = stroke)
            }
            DomiIconKind.SCAN -> {
                line(Offset(w * .14f, h * .34f), Offset(w * .14f, h * .14f)); line(Offset(w * .14f, h * .14f), Offset(w * .34f, h * .14f))
                line(Offset(w * .66f, h * .14f), Offset(w * .86f, h * .14f)); line(Offset(w * .86f, h * .14f), Offset(w * .86f, h * .34f))
                line(Offset(w * .14f, h * .66f), Offset(w * .14f, h * .86f)); line(Offset(w * .14f, h * .86f), Offset(w * .34f, h * .86f))
                line(Offset(w * .66f, h * .86f), Offset(w * .86f, h * .86f)); line(Offset(w * .86f, h * .86f), Offset(w * .86f, h * .66f))
                drawCircle(tint, w * .15f, Offset(w * .5f, h * .5f), style = stroke)
            }
            DomiIconKind.BINDER -> {
                rect(w * .18f, h * .12f, w * .78f, h * .88f, w * .08f)
                line(Offset(w * .35f, h * .13f), Offset(w * .35f, h * .87f))
                drawCircle(tint, w * .045f, Offset(w * .28f, h * .38f))
                drawCircle(tint, w * .045f, Offset(w * .28f, h * .62f))
            }
            DomiIconKind.COLLECTION -> {
                rect(w * .12f, h * .18f, w * .46f, h * .78f); rect(w * .54f, h * .18f, w * .88f, h * .78f)
                line(Offset(w * .5f, h * .16f), Offset(w * .5f, h * .84f))
            }
            DomiIconKind.SETTINGS -> {
                drawCircle(tint, w * .16f, Offset(w * .5f, h * .5f), style = stroke)
                repeat(8) { i ->
                    val a = i * PI / 4
                    line(Offset(w * .5f + cos(a).toFloat() * w * .28f, h * .5f + sin(a).toFloat() * h * .28f), Offset(w * .5f + cos(a).toFloat() * w * .39f, h * .5f + sin(a).toFloat() * h * .39f))
                }
            }
            DomiIconKind.SEARCH -> { drawCircle(tint, w * .25f, Offset(w * .43f, h * .42f), style = stroke); line(Offset(w * .62f, h * .62f), Offset(w * .85f, h * .85f)) }
            DomiIconKind.ADD -> { line(Offset(w * .5f, h * .2f), Offset(w * .5f, h * .8f)); line(Offset(w * .2f, h * .5f), Offset(w * .8f, h * .5f)) }
            DomiIconKind.MARKET -> { line(Offset(w * .13f, h * .76f), Offset(w * .36f, h * .52f)); line(Offset(w * .36f, h * .52f), Offset(w * .55f, h * .63f)); line(Offset(w * .55f, h * .63f), Offset(w * .86f, h * .24f)); line(Offset(w * .74f, h * .24f), Offset(w * .86f, h * .24f)); line(Offset(w * .86f, h * .24f), Offset(w * .86f, h * .37f)) }
            DomiIconKind.CARD -> { rect(w * .18f, h * .10f, w * .82f, h * .90f); line(Offset(w * .28f, h * .64f), Offset(w * .72f, h * .64f)) }
            DomiIconKind.PROFILE -> { drawCircle(tint, w * .16f, Offset(w * .5f, h * .32f), style = stroke); drawArc(tint, 205f, 130f, false, Offset(w * .18f, h * .45f), Size(w * .64f, h * .48f), style = stroke) }
            DomiIconKind.BACK -> { line(Offset(w * .72f, h * .18f), Offset(w * .28f, h * .5f)); line(Offset(w * .28f, h * .5f), Offset(w * .72f, h * .82f)) }
            DomiIconKind.MORE -> { repeat(3) { drawCircle(tint, w * .065f, Offset(w * (.25f + it * .25f), h * .5f)) } }
            DomiIconKind.INFO -> { drawCircle(tint, w * .38f, Offset(w * .5f, h * .5f), style = stroke); line(Offset(w * .5f, h * .44f), Offset(w * .5f, h * .70f)); drawCircle(tint, w * .045f, Offset(w * .5f, h * .29f)) }
            DomiIconKind.SHIELD -> { val p = Path().apply { moveTo(w * .5f, h * .08f); lineTo(w * .82f, h * .22f); lineTo(w * .77f, h * .65f); quadraticTo(w * .68f, h * .84f, w * .5f, h * .92f); quadraticTo(w * .32f, h * .84f, w * .23f, h * .65f); lineTo(w * .18f, h * .22f); close() }; drawPath(p, tint, style = stroke) }
            DomiIconKind.SYNC -> { drawArc(tint, 205f, 230f, false, Offset(w * .16f, h * .16f), Size(w * .68f, h * .68f), style = stroke); line(Offset(w * .69f, h * .16f), Offset(w * .84f, h * .18f)); line(Offset(w * .84f, h * .18f), Offset(w * .82f, h * .34f)) }
            DomiIconKind.CHECK -> { line(Offset(w * .16f, h * .52f), Offset(w * .41f, h * .76f)); line(Offset(w * .41f, h * .76f), Offset(w * .86f, h * .24f)) }
            DomiIconKind.CHEVRON -> { line(Offset(w * .35f, h * .22f), Offset(w * .65f, h * .5f)); line(Offset(w * .65f, h * .5f), Offset(w * .35f, h * .78f)) }
            DomiIconKind.LIGHTNING -> {
                val p = Path().apply { moveTo(w * .58f, h * .08f); lineTo(w * .22f, h * .55f); lineTo(w * .48f, h * .55f); lineTo(w * .40f, h * .92f); lineTo(w * .79f, h * .42f); lineTo(w * .53f, h * .42f); close() }
                drawPath(p, tint, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
internal fun DomiEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    DomiGlassCard(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DomiDexBrandMark(size = 48.dp)
            Text(title, color = VaultText, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(body, color = VaultTextMuted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(2.dp))
                Surface(
                    Modifier.clickable(onClick = onAction),
                    color = MewPlumDeep,
                    shape = CardVaultShapes.pill,
                    border = BorderStroke(1.dp, MewRose.copy(alpha = .48f)),
                ) { Text(actionLabel, Modifier.padding(horizontal = 18.dp, vertical = 9.dp), color = VaultText, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
