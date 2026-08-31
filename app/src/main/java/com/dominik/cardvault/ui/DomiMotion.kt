package com.dominik.cardvault.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import coil3.compose.AsyncImage
import com.dominik.cardvault.ui.theme.MewIce
import com.dominik.cardvault.ui.theme.MewPlum
import com.dominik.cardvault.ui.theme.MewRose
import com.dominik.cardvault.ui.theme.VaultGold
import com.dominik.cardvault.ui.theme.VaultNightRaised
import com.dominik.cardvault.ui.theme.VaultText
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Shared motion timings. Short interactions stay direct; only ambient effects are slow. */
internal object DomiMotion {
    const val UltraFast = 100
    const val Fast = 160
    const val Normal = 220
    const val Slow = 420
    const val Idle = 3_800
}

internal enum class DomiStatusTone { SUCCESS, INFO, WARNING, ERROR }

/**
 * Screen-local feedback surface.  It animates only while it is on screen and
 * owns no application state, so it cannot cause a screen-wide rebuild.
 */
@Composable
internal fun DomiStatusBanner(
    message: String?,
    tone: DomiStatusTone,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    accentColor: Color? = null,
) {
    val color = accentColor ?: when (tone) {
        DomiStatusTone.SUCCESS -> MewIce
        DomiStatusTone.INFO -> MewPlum
        DomiStatusTone.WARNING -> VaultGold
        DomiStatusTone.ERROR -> MewRose
    }
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically(tween(DomiMotion.Fast)) { -it / 2 } + fadeIn(tween(DomiMotion.Fast)),
        exit = slideOutVertically(tween(DomiMotion.Fast)) { -it / 2 } + fadeOut(tween(DomiMotion.Fast)),
        modifier = modifier,
    ) {
        Surface(
            color = VaultNightRaised.copy(alpha = .96f),
            contentColor = VaultText,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 8.dp,
        ) {
            Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message.orEmpty(), Modifier.weight(1f), color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                action?.invoke()
            }
        }
    }
}

/** Small, screen-scoped motion primitives. They never read or modify app data. */
@Composable
internal fun DomiBinderCover(title: String, logoUrl: String?, opening: Boolean, modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "binder-cover-$title")
    val sweep by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(DomiMotion.Idle, easing = LinearEasing), RepeatMode.Restart), label = "cover-sweep")
    val glow by motion.animateFloat(.42f, .82f, infiniteRepeatable(tween(2_400), RepeatMode.Reverse), label = "cover-glow")
    val lift by animateFloatAsState(if (opening) .965f else 1f, tween(DomiMotion.Fast), label = "cover-lift")
    Box(
        modifier.size(82.dp, 108.dp).graphicsLayer { scaleX = lift; scaleY = lift }
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF080916), VaultNightRaised, Color(0xFF190F31))))
            .border(1.dp, MewRose.copy(alpha = glow), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(MewPlum.copy(alpha = .20f + glow * .18f), style = Stroke(2.5f))
            val x = size.width * (sweep * 1.55f - .28f)
            drawRect(Brush.linearGradient(listOf(Color.Transparent, MewRose.copy(alpha = .33f), MewIce.copy(alpha = .22f), Color.Transparent), start = androidx.compose.ui.geometry.Offset(x - size.width * .35f, 0f), end = androidx.compose.ui.geometry.Offset(x, size.height)))
            repeat(3) { index ->
                val angle = (sweep * 2f * PI + index * 2.1).toFloat()
                val px = size.width * .5f + cos(angle) * size.width * .38f
                val py = size.height * .5f + sin(angle) * size.height * .32f
                drawCircle(if (index == 1) MewIce else MewRose, radius = 1.8f, center = androidx.compose.ui.geometry.Offset(px, py), alpha = .38f)
            }
        }
        if (logoUrl != null) AsyncImage(logoUrl, "$title Set-Logo", Modifier.fillMaxSize().padding(10.dp))
        else androidx.compose.material3.Text("D M", color = MewRose, fontWeight = FontWeight.Black, fontSize = 21.sp)
        androidx.compose.material3.Text("DOMIDEX", Modifier.align(Alignment.BottomCenter).padding(4.dp), color = VaultText.copy(alpha = .55f), fontWeight = FontWeight.Black, fontSize = 7.sp, letterSpacing = 1.sp)
    }
}

@Composable
internal fun MasterSetProgressRing(owned: Int, total: Int, modifier: Modifier = Modifier) {
    val target = if (total > 0) (owned.toFloat() / total).coerceIn(0f, 1f) else 0f
    val progress by animateFloatAsState(target, tween(DomiMotion.Slow), label = "master-set-progress")
    Box(modifier.size(62.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx(); val inset = stroke / 2f
            drawArc(MewPlum.copy(alpha = .20f), -90f, 360f, false, androidx.compose.ui.geometry.Offset(inset, inset), androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
            if (progress > 0f) drawArc(Brush.sweepGradient(listOf(MewIce, MewRose, VaultGold, MewIce)), -90f, progress * 360f, false, androidx.compose.ui.geometry.Offset(inset, inset), androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
        }
        androidx.compose.material3.Text("${(progress * 100).toInt()}%", color = VaultText, fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
    }
}
