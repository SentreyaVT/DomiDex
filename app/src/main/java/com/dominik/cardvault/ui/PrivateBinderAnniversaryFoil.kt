package com.dominik.cardvault.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.floor

/**
 * Reference-inspired anniversary foil, not replacement artwork. Only the fine motifs and
 * a small anniversary plaque are painted. All geometry is cached in 100-unit coordinates;
 * its optical state comes exclusively from the same rendered tilt as the card itself.
 */
internal fun DrawScope.drawPrivateBinderAnniversaryFoil(
    plan: PrivateBinderRenderPlan,
    optics: PrivateBinderFoilOptics,
) {
    if (plan.intensity <= 0f || optics.grooveAlpha <= 0f) return
    val foil = AnniversaryFoilGeometry
    // The etched anniversary motifs need their own exposure: using the subtle atlas
    // opacity made them disappear against bright/full-art cards even at normal strength.
    val foilAlpha = (optics.grooveAlpha * 1.25f + plan.intensity * .30f).coerceIn(0f, .98f)
    val rainbows = List(6) { band ->
        Brush.linearGradient(
            colors = List(13) { sample ->
                val position = sample / 12f
                val glint = (1f - abs(position - optics.reflectionCenterX) * 2f).coerceIn(0f, 1f)
                lerp(anniversarySpectrum(position * 3.7f + band * .85f + optics.hueShift),
                    Color(0xFFFFF0BC), glint * .10f)
            },
            start = Offset(-8f, -5f),
            end = Offset(108f, 105f),
        )
    }
    val gold = Brush.linearGradient(
        colors = listOf(Color(0xFFC38B29), Color(0xFFFFF0AD),
            lerp(Color(0xFFFFD348), anniversarySpectrum(optics.hueShift + 1.4f), .10f),
            Color(0xFFFFEDAD), Color(0xFFD59C38)),
        start = Offset(-20f + optics.normalizedTiltY * 18f, -5f),
        end = Offset(120f, 106f + optics.normalizedTiltX * 18f),
    )
    val motifCount = when {
        plan.primitiveBudget <= 3 -> 12
        plan.primitiveBudget <= 7 -> 15
        else -> 18
    }
    val shiftX = optics.normalizedTiltY * .008f * size.width
    val shiftY = optics.normalizedTiltX * .006f * size.height
    for (index in 0 until motifCount) {
        val motif = foil.motifs[index]
        val rainbow = rainbows[index % rainbows.size]
        val scale = size.width * motif.width / 100f
        val reflection = (1f - abs(motif.x - optics.reflectionCenterX) * 1.5f).coerceIn(0f, 1f)
        val alpha = foilAlpha * (.86f + reflection * .14f)
        withTransform({
            translate(motif.x * size.width + shiftX, motif.y * size.height + shiftY)
            rotate(motif.angle, Offset.Zero)
            scale(scale, scale, Offset.Zero)
            translate(-50f, -50f)
        }) {
            when (motif.kind) {
                AnniversaryMotifKind.FACE -> {
                    anniversarySoftStroke(foil.faceHalo, rainbow, alpha * .65f, 1.6f)
                    anniversarySoftStroke(foil.face, rainbow, alpha, 2.35f)
                    anniversarySoftStroke(foil.faceDetails, rainbow, alpha, 1.95f)
                    anniversaryEmbossedFill(foil.faceThirty, rainbow, alpha)
                }
                AnniversaryMotifKind.BALL -> {
                    anniversarySoftStroke(foil.ball, rainbow, alpha, 2.3f)
                    anniversarySoftStroke(foil.bubbleGlint, rainbow, alpha * .8f, 2.1f)
                }
                AnniversaryMotifKind.BUBBLE -> {
                    anniversarySoftStroke(foil.bubble, rainbow, alpha, 2.25f)
                    anniversarySoftStroke(foil.bubbleGlint, rainbow, alpha, 3.0f)
                    anniversaryEmbossedFill(foil.bubbleThirty, rainbow, alpha * .94f)
                }
            }
        }
    }

    // Small isolated glints, not an all-over glitter texture. Their brightness changes
    // only when the card's angle changes; an idle card has no autonomous animation.
    for (index in 0 until minOf(motifCount + 4, foil.glints.size)) {
        val glint = foil.glints[index]
        val rainbow = rainbows[(index + 2) % rainbows.size]
        val reflection = (1f - abs(glint.x - optics.reflectionCenterX) * 1.8f).coerceIn(0f, 1f)
        val alpha = foilAlpha * (.66f + reflection * .34f)
        val scale = size.width * glint.width / 100f
        withTransform({
            translate(glint.x * size.width + shiftX, glint.y * size.height + shiftY)
            scale(scale, scale, Offset.Zero)
            translate(-50f, -50f)
        }) {
            anniversarySoftStroke(foil.star, rainbow, alpha * .52f, 7f)
            drawPath(foil.star, rainbow, alpha = alpha * .96f)
        }
    }

    // Sparse foil flecks along the perimeter, not a texture or veil across the artwork.
    // Their colours and local glints depend on the very same tilt as the bigger motifs.
    for ((index, fleck) in foil.edgeFlecks.withIndex()) {
        val reflection = (1f - abs(fleck.x - optics.reflectionCenterX) * 1.7f).coerceIn(0f, 1f)
        drawCircle(anniversarySpectrum(index * .71f + optics.hueShift),
            radius = size.width * (.0016f + (index % 3) * .0006f),
            center = Offset(fleck.x * size.width + shiftX, fleck.y * size.height + shiftY),
            alpha = foilAlpha * (.5f + reflection * .4f))
    }

    // A compact sculpted gold/black anniversary seal in the reference's right-hand
    // position. Only this seal has a filled backing; the rest of the foil is transparent.
    val plaqueWidth = size.width * .29f
    val plaqueScale = plaqueWidth / 100f
    withTransform({
        translate(size.width * .678f + shiftX * .35f, size.height * .465f + shiftY * .35f)
        scale(plaqueScale, plaqueScale, Offset.Zero)
    }) {
        drawPath(foil.plaqueEdge, Color(0xFF15100B), alpha = foilAlpha * .96f)
        anniversarySoftStroke(foil.plaqueEdge, gold, foilAlpha * .87f, 1.15f)
        drawPath(foil.plaqueThirty, gold, alpha = foilAlpha)
        withTransform({ translate(63f, 7f); scale(.32f, .32f, Offset.Zero) }) {
            anniversarySoftStroke(foil.face, gold, foilAlpha, 3.6f)
            anniversarySoftStroke(foil.faceDetails, gold, foilAlpha, 3f)
        }
        drawPath(foil.plaqueLabel, gold, alpha = foilAlpha)
        drawPath(foil.plaqueTogether, gold, alpha = foilAlpha)
        drawPath(foil.plaqueForward, gold, alpha = foilAlpha)
    }
}

private fun DrawScope.anniversarySoftStroke(path: Path, brush: Brush, alpha: Float, width: Float) {
    // A local embossed contrast lip keeps rainbow edges visible on pale cards. Soft
    // coloured shoulders stay confined to the motif; no whitening of the actual card.
    drawPath(path, Color(0xFF39204B), alpha = alpha * .37f,
        style = Stroke(width * 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, brush, alpha = alpha * .05f,
        style = Stroke(width * 3.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, brush, alpha = alpha * .23f,
        style = Stroke(width * 2.35f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, brush, alpha = alpha * .97f,
        style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.anniversaryEmbossedFill(path: Path, brush: Brush, alpha: Float) {
    drawPath(path, Color(0xFF39204B), alpha = alpha * .36f,
        style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, brush, alpha = alpha)
}

private val AnniversarySpectrum = listOf(
    Color(0xFFFF65CF), Color(0xFFFFD343), Color(0xFF87ED39),
    Color(0xFF34DCFF), Color(0xFFAB72FF), Color(0xFFED55F4),
)

private fun anniversarySpectrum(phase: Float): Color {
    val wrapped = ((phase % 6f) + 6f) % 6f
    val index = floor(wrapped).toInt()
    return lerp(AnniversarySpectrum[index], AnniversarySpectrum[(index + 1) % 6], wrapped - index)
}

private enum class AnniversaryMotifKind { FACE, BALL, BUBBLE }
private data class AnniversaryMotif(
    val x: Float,
    val y: Float,
    val width: Float,
    val angle: Float = 0f,
    val kind: AnniversaryMotifKind = AnniversaryMotifKind.FACE,
)

/** Read-only paths and font outlines are constructed once, never rebuilt for a tilt frame. */
private object AnniversaryFoilGeometry {
    val motifs = listOf(
        AnniversaryMotif(.18f, .14f, .25f, -16f),
        AnniversaryMotif(.76f, .095f, .12f, -10f, AnniversaryMotifKind.BALL),
        AnniversaryMotif(.85f, .245f, .23f, 12f),
        AnniversaryMotif(.18f, .50f, .27f, -12f),
        AnniversaryMotif(.09f, .375f, .12f, 16f, AnniversaryMotifKind.BALL),
        AnniversaryMotif(.395f, .625f, .22f, 10f),
        AnniversaryMotif(.77f, .745f, .17f, -14f, AnniversaryMotifKind.BUBBLE),
        AnniversaryMotif(.91f, .88f, .14f, 15f, AnniversaryMotifKind.BALL),
        AnniversaryMotif(.10f, .87f, .19f, -17f),
        AnniversaryMotif(.32f, .84f, .17f, -7f, AnniversaryMotifKind.BUBBLE),
        AnniversaryMotif(.595f, .155f, .20f, 10f),
        AnniversaryMotif(.59f, .37f, .105f, -15f, AnniversaryMotifKind.BALL),
        AnniversaryMotif(.335f, .30f, .205f, -9f),
        AnniversaryMotif(.065f, .68f, .15f, 8f, AnniversaryMotifKind.BUBBLE),
        AnniversaryMotif(.525f, .79f, .13f, -6f, AnniversaryMotifKind.BALL),
        AnniversaryMotif(.91f, .055f, .16f, 12f),
        AnniversaryMotif(.93f, .41f, .10f, 20f, AnniversaryMotifKind.BALL),
        AnniversaryMotif(.80f, .94f, .18f, -10f),
    )
    val glints = listOf(
        AnniversaryMotif(.10f, .12f, .047f), AnniversaryMotif(.43f, .16f, .060f),
        AnniversaryMotif(.91f, .36f, .045f), AnniversaryMotif(.25f, .44f, .055f),
        AnniversaryMotif(.58f, .56f, .071f), AnniversaryMotif(.07f, .68f, .040f),
        AnniversaryMotif(.57f, .88f, .045f), AnniversaryMotif(.92f, .64f, .057f),
        AnniversaryMotif(.29f, .71f, .035f), AnniversaryMotif(.61f, .36f, .032f),
        AnniversaryMotif(.07f, .94f, .045f), AnniversaryMotif(.92f, .08f, .037f),
        AnniversaryMotif(.72f, .26f, .025f), AnniversaryMotif(.43f, .45f, .031f),
        AnniversaryMotif(.84f, .80f, .035f), AnniversaryMotif(.43f, .93f, .028f),
        AnniversaryMotif(.63f, .70f, .042f), AnniversaryMotif(.25f, .19f, .024f),
        AnniversaryMotif(.64f, .04f, .035f), AnniversaryMotif(.22f, .96f, .03f),
        AnniversaryMotif(.98f, .70f, .028f), AnniversaryMotif(.02f, .44f, .026f),
    )
    val edgeFlecks = List(48) { index ->
        val along = .035f + (index / 4) / 12f * .93f
        val inset = .015f + (index % 5) * .009f
        when (index % 4) {
            0 -> Offset(inset, along)
            1 -> Offset(1f - inset, along)
            2 -> Offset(along, inset)
            else -> Offset(along, 1f - inset)
        }
    }
    val faceHalo = Path().apply {
        addOval(Rect(1f, 26f, 101f, 108f))
    }
    val face = Path().apply {
        // Round electric-mouse face with unequal pointed ears; no filled silhouette.
        moveTo(31f, 39f)
        cubicTo(27f, 29f, 23f, 10f, 17f, 3f)
        cubicTo(12f, 15f, 17f, 34f, 19f, 45f)
        cubicTo(7f, 55f, 10f, 84f, 29f, 94f)
        cubicTo(49f, 107f, 83f, 98f, 92f, 79f)
        cubicTo(100f, 61f, 87f, 45f, 77f, 41f)
        cubicTo(85f, 29f, 93f, 13f, 96f, 3f)
        cubicTo(84f, 7f, 72f, 22f, 62f, 35f)
        cubicTo(49f, 32f, 41f, 33f, 31f, 39f)
        close()
        // Broken bubble shoulder catches the light without a second complete rim.
        moveTo(13f, 54f)
        cubicTo(2f, 72f, 20f, 104f, 49f, 103f)
        moveTo(81f, 47f)
        cubicTo(105f, 62f, 96f, 87f, 80f, 94f)
    }
    val faceDetails = Path().apply {
        addOval(Rect(30f, 57f, 36f, 66f))
        addOval(Rect(65f, 55f, 71f, 64f))
        addOval(Rect(18f, 67f, 24f, 72f))
        addOval(Rect(78f, 63f, 84f, 69f))
        moveTo(47f, 69f); lineTo(51f, 70f); lineTo(54f, 68f)
        moveTo(19f, 27f); lineTo(24f, 31f)
        moveTo(80f, 23f); lineTo(86f, 27f)
    }
    val faceThirty = textPath("30", 29f, 51f, 95f)
    val bubbleThirty = textPath("30", 32f, 50f, 63f)
    val bubble = Path().apply { addOval(Rect(5f, 5f, 95f, 95f)) }
    val bubbleGlint = Path().apply {
        moveTo(18f, 40f)
        cubicTo(19f, 24f, 31f, 14f, 45f, 13f)
        moveTo(75f, 82f)
        cubicTo(83f, 76f, 87f, 68f, 88f, 58f)
    }
    val ball = Path().apply {
        addOval(Rect(6f, 6f, 94f, 94f))
        addOval(Rect(36f, 36f, 64f, 64f))
        addOval(Rect(42f, 42f, 58f, 58f))
        moveTo(7f, 45f); lineTo(36f, 45f)
        moveTo(7f, 54f); lineTo(36f, 54f)
        moveTo(64f, 45f); lineTo(93f, 45f)
        moveTo(64f, 54f); lineTo(93f, 54f)
    }
    val star = Path().apply {
        moveTo(50f, 0f)
        cubicTo(54f, 40f, 58f, 46f, 100f, 50f)
        cubicTo(58f, 54f, 54f, 60f, 50f, 100f)
        cubicTo(46f, 60f, 42f, 54f, 0f, 50f)
        cubicTo(42f, 46f, 46f, 40f, 50f, 0f)
        close()
    }
    val plaqueEdge = Path().apply {
        moveTo(8f, 42f)
        cubicTo(-3f, 32f, 3f, 11f, 17f, 8f)
        cubicTo(28f, 3f, 42f, 5f, 53f, 10f)
        lineTo(65f, 2f); cubicTo(69f, 0f, 74f, 3f, 75f, 9f)
        lineTo(88f, 4f); cubicTo(98f, 1f, 101f, 12f, 93f, 22f)
        cubicTo(105f, 29f, 102f, 44f, 95f, 52f)
        lineTo(97f, 88f); cubicTo(98f, 99f, 91f, 103f, 80f, 101f)
        lineTo(18f, 103f); cubicTo(7f, 105f, 3f, 99f, 6f, 89f)
        lineTo(5f, 66f); cubicTo(0f, 58f, 1f, 49f, 8f, 42f)
        close()
    }
    val plaqueThirty = textPath("30", 60f, 38f, 59f)
    val plaqueLabel = textPath("J A H R E", 14f, 50f, 74f)
    val plaqueTogether = textPath("GEMEINSAM", 10.5f, 50f, 88f)
    val plaqueForward = textPath("W E I T E R", 10f, 50f, 99f)

    private fun textPath(text: String, textSize: Float, centerX: Float, baseline: Float): Path {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        return android.graphics.Path().also {
            paint.getTextPath(text, 0, text.length, centerX - paint.measureText(text) / 2f, baseline, it)
        }.asComposePath()
    }
}
