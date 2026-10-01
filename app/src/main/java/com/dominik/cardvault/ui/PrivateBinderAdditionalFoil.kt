package com.dominik.cardvault.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.floor

/** Stable catalogue membership, including no implicit fallback for unknown IDs. */
internal fun privateBinderUsesAdditionalFoil(effectId: String): Boolean = when (effectId) {
    "oil_slick", "liquid_metal", "hyper_wave", "fire", "ice", "void", "holo_spark" -> true
    else -> false
}

/**
 * Each material is made from its own filled, locally shaded shapes. Cached paths and
 * fixed layouts keep the artwork still; the existing rendered tilt supplies all optics.
 * No card-sized tint, Screen wash, texture, clock, or changing random placement is used.
 */
internal fun DrawScope.drawPrivateBinderAdditionalFoil(
    effectId: String,
    plan: PrivateBinderRenderPlan,
    optics: PrivateBinderFoilOptics,
) {
    if (!privateBinderUsesAdditionalFoil(effectId) || !plan.intensity.isFinite() ||
        plan.intensity <= 0f || optics.grooveAlpha <= 0f ||
        size.width <= 0f || size.height <= 0f) return
    val detail = when {
        plan.primitiveBudget <= 3 -> 0
        plan.primitiveBudget <= 7 -> 1
        else -> 2
    }
    val exposure = (optics.grooveAlpha + plan.intensity * .18f).coerceIn(0f, .98f)
    clipRect {
        val material = AdditionalFoilPainter(this, optics, exposure, detail)
        when (effectId) {
            "oil_slick" -> material.oil()
            "liquid_metal" -> material.chrome()
            "hyper_wave" -> material.waves()
            "fire" -> material.fire()
            "ice" -> material.ice()
            "void" -> material.void()
            "holo_spark" -> material.sparks()
        }
    }
}

private class AdditionalFoilPainter(
    private val canvas: DrawScope,
    private val optics: PrivateBinderFoilOptics,
    private val exposure: Float,
    private val detail: Int,
) {
    private val shapes = AdditionalFoilGeometry
    private val spectra = List(5) { phase ->
        Brush.linearGradient(
            colors = List(13) { sample ->
                additionalSpectrum(sample / 12f * 4.8f + phase * .84f + optics.hueShift)
            },
            start = Offset(-12f + optics.normalizedTiltY * 22f, -20f),
            end = Offset(112f, 120f + optics.normalizedTiltX * 24f),
        )
    }
    private val highlight = Brush.radialGradient(
        colors = listOf(Color(0xFFF5F9EC), Color(0x008CDFF8)),
        center = Offset(32f + optics.normalizedTiltY * 30f, 25f + optics.normalizedTiltX * 21f),
        radius = 66f,
    )

    fun oil() {
        // Unequal pools with broad interference lobes, not concentric wire contours.
        for ((index, motif) in shapes.pools.take(6 + detail * 2).withIndex()) {
            val spectrum = spectra[index % spectra.size]
            val alpha = opacity(motif)
            at(motif) {
                drawPath(shapes.pool, Color(0xFF351148), alpha = alpha * .20f)
                softBody(shapes.poolBand, spectrum, alpha * .67f, 2.4f)
                drawPath(shapes.poolInnerBand, spectra[(index + 2) % spectra.size], alpha = alpha * .55f)
                drawPath(shapes.poolSheen, spectrum, alpha = alpha * .36f)
                drawPath(shapes.poolSheen, highlight, alpha = alpha * .19f)
            }
        }
        droplets(shapes.oilDroplets, 5 + detail * 2, .50f)
    }

    fun chrome() {
        // Mercury-like islands: a dark belly, broad silver reflection, and chromatic lip.
        val chrome = Brush.linearGradient(
            0f to Color(0xFF252E59), .20f to Color(0xFFB8E8F0),
            .34f to Color(0xFF4D7199), .45f to Color(0xFF101D3D),
            .58f to lerp(Color(0xFFD8F3F0), additionalSpectrum(optics.hueShift + 2f), .30f),
            .71f to Color(0xFFF0F7EB), .87f to Color(0xFF6878A8), 1f to Color(0xFF382249),
            start = Offset(-10f + optics.normalizedTiltY * 34f, -4f),
            end = Offset(100f, 105f + optics.normalizedTiltX * 24f),
        )
        for ((index, motif) in shapes.mercury.take(7 + detail * 2).withIndex()) {
            val alpha = opacity(motif)
            at(motif) {
                softBody(shapes.mercuryBody, spectra[index % spectra.size], alpha * .29f, 2.2f)
                drawPath(shapes.mercuryBody, chrome, alpha = alpha * .66f)
                drawPath(shapes.mercuryFold, spectra[(index + 1) % spectra.size], alpha = alpha * .48f)
                drawPath(shapes.mercuryLight, highlight, alpha = alpha * .49f)
            }
        }
        droplets(shapes.chromeDroplets, 5 + detail * 2, .72f)
    }

    fun waves() {
        // Wide curled foil strips have a coloured front, a darker folded back, and
        // tapered ends. The artwork is fully open between these separated ribbons.
        for ((index, motif) in shapes.ribbons.take(3 + detail).withIndex()) {
            val alpha = opacity(motif)
            at(motif) {
                softBody(shapes.ribbon, spectra[index % spectra.size], alpha * .60f, 2.8f)
                drawPath(shapes.ribbonFold, Color(0xFF391A75), alpha = alpha * .38f)
                drawPath(shapes.ribbonFold, spectra[(index + 3) % spectra.size], alpha = alpha * .48f)
                drawPath(shapes.ribbonCrest, spectra[(index + 1) % spectra.size], alpha = alpha * .67f)
                drawPath(shapes.ribbonCrest, highlight, alpha = alpha * .30f)
            }
        }
        droplets(shapes.waveDroplets, 4 + detail * 2, .57f)
    }

    fun fire() {
        val flame = Brush.linearGradient(
            colors = listOf(
                lerp(Color(0xFFEA3988), additionalSpectrum(optics.hueShift + 5f), .27f),
                Color(0xFFFF7439),
                lerp(Color(0xFFFFD34A), additionalSpectrum(optics.hueShift + 1f), .24f),
                Color(0xFF9D2478),
            ),
            start = Offset(18f + optics.normalizedTiltY * 20f, 8f),
            end = Offset(86f, 111f + optics.normalizedTiltX * 16f),
        )
        for ((index, motif) in shapes.flames.take(6 + detail * 2).withIndex()) {
            val alpha = opacity(motif)
            at(motif) {
                softBody(shapes.flame, flame, alpha * .57f, 2.9f)
                drawPath(shapes.flameCore, spectra[(index + 1) % spectra.size], alpha = alpha * .50f)
                drawPath(shapes.flameCore, highlight, alpha = alpha * .27f)
                drawPath(shapes.flameTongue, flame, alpha = alpha * .58f)
            }
        }
        // Small tapered embers form a scattered second scale without a glitter blanket.
        for ((index, motif) in shapes.embers.take(9 + detail * 3).withIndex()) {
            at(motif) {
                drawPath(shapes.ember, spectra[index % spectra.size], alpha = exposure * .77f)
            }
        }
    }

    fun ice() {
        val blue = Brush.linearGradient(
            colors = listOf(Color(0xFF248CC0), Color(0xFF9CF6EC),
                lerp(Color(0xFFB9C8FF), additionalSpectrum(optics.hueShift + 3.4f), .43f),
                Color(0xFF506DD4)),
            start = Offset(-8f + optics.normalizedTiltY * 30f, -10f),
            end = Offset(110f, 112f + optics.normalizedTiltX * 28f),
        )
        for ((index, motif) in shapes.ice.take(7 + detail * 2).withIndex()) {
            val alpha = opacity(motif)
            at(motif) {
                softBody(shapes.iceShard, blue, alpha * .48f, 1.8f)
                drawPath(shapes.iceFace, spectra[(index + 2) % spectra.size], alpha = alpha * .51f)
                drawPath(shapes.iceShadow, Color(0xFF32419B), alpha = alpha * .28f)
                drawPath(shapes.iceHighlight, highlight, alpha = alpha * .60f)
                drawPath(shapes.iceSplinter, spectra[index % spectra.size], alpha = alpha * .67f)
            }
        }
        droplets(shapes.iceDroplets, 5 + detail * 2, .45f, angular = true)
    }

    fun void() {
        // Offset eclipse silhouettes leave their centres open. Broad dark lobes and
        // iridescent corona crescents produce depth without dimming the whole card.
        for ((index, motif) in shapes.eclipses.take(4 + detail).withIndex()) {
            val alpha = opacity(motif)
            at(motif) {
                drawPath(shapes.eclipseShadow, Color(0xFF100B2D), alpha = alpha * .45f)
                softBody(shapes.eclipseCorona, spectra[(index + 3) % spectra.size], alpha * .72f, 3.2f)
                drawPath(shapes.eclipseInner, Color(0xFF4B207F), alpha = alpha * .28f)
                drawPath(shapes.eclipseCrest, spectra[index % spectra.size], alpha = alpha * .72f)
                drawPath(shapes.eclipseCrest, highlight, alpha = alpha * .22f)
            }
        }
        droplets(shapes.voidDroplets, 6 + detail * 2, .62f, angular = true)
    }

    fun sparks() {
        for ((index, motif) in shapes.sparks.take(8 + detail * 2).withIndex()) {
            val alpha = opacity(motif)
            at(motif) {
                // A solid angular burst and offset facets replace the old cross wires.
                softBody(shapes.spark, spectra[index % spectra.size], alpha * .80f, 2.3f)
                drawPath(shapes.sparkFacet, spectra[(index + 2) % spectra.size], alpha = alpha * .62f)
                drawPath(shapes.sparkCore, highlight, alpha = alpha * .67f)
                drawPath(shapes.sparkSatellite, spectra[(index + 1) % spectra.size], alpha = alpha * .86f)
            }
        }
    }

    private fun opacity(motif: AdditionalFoilMotif): Float {
        val reflection = (1f - abs(motif.x - optics.reflectionCenterX) * 1.5f).coerceIn(0f, 1f)
        return exposure * (.80f + reflection * .20f)
    }

    private fun at(motif: AdditionalFoilMotif, draw: DrawScope.() -> Unit) {
        val width = canvas.size.width * motif.width / 100f
        canvas.withTransform({
            translate(motif.x * canvas.size.width + optics.normalizedTiltY * canvas.size.width * .008f,
                motif.y * canvas.size.height + optics.normalizedTiltX * canvas.size.height * .006f)
            rotate(motif.angle, Offset.Zero)
            scale(width, width * motif.aspect, Offset.Zero)
            translate(-50f, -50f)
        }, draw)
    }

    private fun DrawScope.softBody(path: Path, brush: Brush, alpha: Float, shoulder: Float) {
        // The tinted shoulders are restricted to a shape's edge and never whiten art.
        drawPath(path, brush, alpha = alpha * .10f, style = Stroke(shoulder * 2f, join = StrokeJoin.Round))
        drawPath(path, Color(0xFF261D51), alpha = alpha * .16f,
            style = Stroke(shoulder * .70f, join = StrokeJoin.Round))
        drawPath(path, brush, alpha = alpha)
    }

    private fun droplets(
        layout: List<AdditionalFoilMotif>,
        count: Int,
        alpha: Float,
        angular: Boolean = false,
    ) {
        for ((index, motif) in layout.take(count).withIndex()) {
            at(motif) {
                drawPath(if (angular) shapes.smallShard else shapes.drop,
                    spectra[index % spectra.size], alpha = opacity(motif) * alpha)
            }
        }
    }
}

private data class AdditionalFoilMotif(
    val x: Float,
    val y: Float,
    val width: Float,
    val angle: Float = 0f,
    val aspect: Float = 1f,
)

private val AdditionalFoilSpectrum = listOf(
    Color(0xFFFF568C), Color(0xFFFFD242), Color(0xFF71EFA0),
    Color(0xFF28D9F3), Color(0xFF8872FF), Color(0xFFF26AE6),
)

private fun additionalSpectrum(phase: Float): Color {
    val wrapped = ((phase % 6f) + 6f) % 6f
    val first = floor(wrapped).toInt()
    return lerp(AdditionalFoilSpectrum[first], AdditionalFoilSpectrum[(first + 1) % 6], wrapped - first)
}

/** Normalized 100-unit paths are constructed once and remain read-only during rendering. */
private object AdditionalFoilGeometry {
    val pools = listOf(
        AdditionalFoilMotif(.18f, .16f, .40f, -24f, .82f),
        AdditionalFoilMotif(.80f, .36f, .47f, 25f, .92f),
        AdditionalFoilMotif(.30f, .66f, .44f, -37f, .85f),
        AdditionalFoilMotif(.75f, .87f, .39f, 19f, .77f),
        AdditionalFoilMotif(.09f, .91f, .29f, -8f, .82f),
        AdditionalFoilMotif(.72f, .085f, .33f, 14f, .72f),
        AdditionalFoilMotif(.15f, .40f, .28f, 32f, .78f),
        AdditionalFoilMotif(.59f, .56f, .30f, -30f, .78f),
        AdditionalFoilMotif(.93f, .65f, .28f, -10f, .90f),
        AdditionalFoilMotif(.44f, .27f, .23f, 45f, .69f),
    )
    val mercury = listOf(
        AdditionalFoilMotif(.17f, .13f, .35f, -30f, .80f),
        AdditionalFoilMotif(.79f, .30f, .36f, 47f, 1.10f),
        AdditionalFoilMotif(.28f, .55f, .34f, -42f, 1.25f),
        AdditionalFoilMotif(.68f, .79f, .40f, 32f, .96f),
        AdditionalFoilMotif(.10f, .86f, .28f, 9f, 1.03f),
        AdditionalFoilMotif(.86f, .92f, .25f, -43f, .76f),
        AdditionalFoilMotif(.68f, .06f, .26f, 58f, .95f),
        AdditionalFoilMotif(.08f, .32f, .21f, 44f, 1.08f),
        AdditionalFoilMotif(.91f, .57f, .24f, -60f, .83f),
        AdditionalFoilMotif(.47f, .33f, .21f, 15f, .80f),
        AdditionalFoilMotif(.39f, .92f, .22f, -19f, .89f),
    )
    val ribbons = listOf(
        AdditionalFoilMotif(.44f, .17f, 1.02f, -10f, .56f),
        AdditionalFoilMotif(.57f, .53f, 1.05f, 15f, .57f),
        AdditionalFoilMotif(.42f, .88f, 1.02f, -12f, .53f),
        AdditionalFoilMotif(.52f, .35f, .82f, -14f, .43f),
        AdditionalFoilMotif(.45f, .70f, .91f, 18f, .43f),
    )
    val flames = listOf(
        AdditionalFoilMotif(.12f, .87f, .29f, -16f, 1.38f),
        AdditionalFoilMotif(.78f, .65f, .29f, 15f, 1.44f),
        AdditionalFoilMotif(.27f, .38f, .27f, -9f, 1.36f),
        AdditionalFoilMotif(.81f, .13f, .25f, 22f, 1.24f),
        AdditionalFoilMotif(.46f, .93f, .22f, 11f, 1.38f),
        AdditionalFoilMotif(.09f, .11f, .20f, -16f, 1.27f),
        AdditionalFoilMotif(.58f, .45f, .21f, 19f, 1.30f),
        AdditionalFoilMotif(.92f, .91f, .19f, 9f, 1.35f),
        AdditionalFoilMotif(.10f, .60f, .17f, -24f, 1.38f),
        AdditionalFoilMotif(.50f, .15f, .17f, 14f, 1.32f),
    )
    val ice = listOf(
        AdditionalFoilMotif(.10f, .13f, .28f, -29f, 1.24f),
        AdditionalFoilMotif(.79f, .32f, .26f, 36f, 1.60f),
        AdditionalFoilMotif(.24f, .60f, .32f, -24f, 1.45f),
        AdditionalFoilMotif(.77f, .88f, .29f, 17f, 1.27f),
        AdditionalFoilMotif(.09f, .92f, .20f, -32f, 1.39f),
        AdditionalFoilMotif(.72f, .07f, .24f, 62f, 1.02f),
        AdditionalFoilMotif(.91f, .62f, .22f, -18f, 1.18f),
        AdditionalFoilMotif(.46f, .36f, .20f, 16f, 1.33f),
        AdditionalFoilMotif(.49f, .78f, .18f, -36f, 1.21f),
        AdditionalFoilMotif(.06f, .39f, .19f, 10f, 1.29f),
        AdditionalFoilMotif(.44f, .08f, .17f, -8f, 1.10f),
    )
    val eclipses = listOf(
        AdditionalFoilMotif(.24f, .17f, .46f, -32f),
        AdditionalFoilMotif(.79f, .49f, .52f, 24f),
        AdditionalFoilMotif(.22f, .76f, .47f, -12f),
        AdditionalFoilMotif(.81f, .92f, .29f, 34f),
        AdditionalFoilMotif(.83f, .10f, .25f, 96f),
        AdditionalFoilMotif(.17f, .42f, .24f, -50f),
    )
    val sparks = listOf(
        AdditionalFoilMotif(.15f, .13f, .23f, -13f),
        AdditionalFoilMotif(.80f, .33f, .25f, 16f),
        AdditionalFoilMotif(.29f, .58f, .25f, -30f),
        AdditionalFoilMotif(.72f, .82f, .27f, 13f),
        AdditionalFoilMotif(.12f, .90f, .18f, 28f),
        AdditionalFoilMotif(.81f, .09f, .18f, -9f),
        AdditionalFoilMotif(.12f, .37f, .17f, 37f),
        AdditionalFoilMotif(.92f, .64f, .19f, -23f),
        AdditionalFoilMotif(.53f, .19f, .17f, 19f),
        AdditionalFoilMotif(.51f, .43f, .16f, -29f),
        AdditionalFoilMotif(.41f, .87f, .16f, 13f),
        AdditionalFoilMotif(.90f, .92f, .15f, -6f),
    )
    private fun accents(seed: Int, width: Float, aspect: Float = 1f) = List(15) { index ->
        AdditionalFoilMotif(
            .055f + ((index * 37 + seed * 13) % 89) / 100f,
            .045f + ((index * 53 + seed * 17) % 91) / 100f,
            width * (.70f + (index % 3) * .19f),
            (index * 67 + seed * 11).toFloat(), aspect,
        )
    }
    val oilDroplets = accents(2, .075f, .72f)
    val chromeDroplets = accents(4, .059f)
    val waveDroplets = accents(8, .052f, .62f)
    val embers = accents(5, .039f, 1.60f)
    val iceDroplets = accents(6, .047f, 1.30f)
    val voidDroplets = accents(9, .036f)

    val pool = Path().apply {
        moveTo(8f, 41f)
        cubicTo(-2f, 14f, 27f, 3f, 49f, 16f)
        cubicTo(70f, 28f, 89f, 5f, 97f, 33f)
        cubicTo(110f, 63f, 81f, 92f, 61f, 84f)
        cubicTo(37f, 75f, 18f, 108f, 5f, 77f)
        cubicTo(-2f, 60f, 15f, 56f, 8f, 41f)
        close()
    }
    val poolBand = Path().apply {
        fillType = PathFillType.EvenOdd
        addPath(pool)
        moveTo(18f, 38f)
        cubicTo(12f, 23f, 31f, 15f, 48f, 26f)
        cubicTo(68f, 41f, 86f, 21f, 88f, 39f)
        cubicTo(92f, 62f, 77f, 75f, 58f, 68f)
        cubicTo(39f, 62f, 22f, 88f, 16f, 73f)
        cubicTo(9f, 59f, 25f, 51f, 18f, 38f)
        close()
    }
    val poolInnerBand = Path().apply {
        moveTo(23f, 38f)
        cubicTo(38f, 25f, 45f, 40f, 59f, 43f)
        cubicTo(71f, 45f, 85f, 35f, 82f, 51f)
        cubicTo(80f, 67f, 62f, 61f, 54f, 59f)
        cubicTo(39f, 56f, 29f, 72f, 25f, 68f)
        cubicTo(20f, 59f, 44f, 49f, 38f, 44f)
        cubicTo(34f, 39f, 23f, 46f, 23f, 38f)
        close()
    }
    val poolSheen = Path().apply {
        moveTo(12f, 30f); cubicTo(19f, 13f, 34f, 14f, 48f, 23f)
        cubicTo(32f, 19f, 25f, 27f, 24f, 37f)
        cubicTo(22f, 48f, 15f, 49f, 12f, 30f); close()
    }
    val mercuryBody = Path().apply {
        moveTo(25f, 8f); cubicTo(54f, -10f, 64f, 13f, 60f, 29f)
        cubicTo(53f, 44f, 96f, 38f, 96f, 66f)
        cubicTo(100f, 89f, 73f, 101f, 59f, 90f)
        cubicTo(45f, 80f, 58f, 65f, 36f, 65f)
        cubicTo(10f, 68f, 0f, 53f, 9f, 33f)
        cubicTo(17f, 24f, 12f, 16f, 25f, 8f); close()
    }
    val mercuryFold = Path().apply {
        moveTo(44f, 37f); cubicTo(55f, 56f, 87f, 41f, 87f, 65f)
        cubicTo(86f, 78f, 78f, 85f, 66f, 81f)
        cubicTo(82f, 76f, 79f, 65f, 64f, 62f)
        cubicTo(52f, 58f, 42f, 58f, 44f, 37f); close()
    }
    val mercuryLight = Path().apply {
        moveTo(21f, 20f); cubicTo(35f, 7f, 52f, 11f, 52f, 23f)
        cubicTo(38f, 18f, 31f, 30f, 24f, 43f)
        cubicTo(16f, 59f, 12f, 42f, 21f, 20f); close()
    }
    val ribbon = Path().apply {
        moveTo(-6f, 72f); cubicTo(12f, 71f, 11f, 20f, 32f, 15f)
        cubicTo(53f, 11f, 48f, 79f, 68f, 78f)
        cubicTo(86f, 78f, 82f, 30f, 105f, 28f)
        cubicTo(88f, 41f, 91f, 95f, 68f, 96f)
        cubicTo(39f, 97f, 46f, 29f, 30f, 32f)
        cubicTo(16f, 35f, 20f, 81f, -6f, 72f); close()
    }
    val ribbonFold = Path().apply {
        moveTo(33f, 16f); cubicTo(52f, 20f, 48f, 70f, 63f, 77f)
        lineTo(70f, 95f); cubicTo(42f, 97f, 44f, 37f, 33f, 16f); close()
    }
    val ribbonCrest = Path().apply {
        moveTo(0f, 72f); cubicTo(18f, 67f, 13f, 24f, 33f, 18f)
        cubicTo(42f, 16f, 45f, 30f, 45f, 41f)
        cubicTo(37f, 21f, 29f, 25f, 23f, 46f)
        cubicTo(18f, 64f, 14f, 76f, 0f, 72f); close()
        moveTo(68f, 81f); cubicTo(88f, 82f, 85f, 37f, 101f, 30f)
        cubicTo(89f, 46f, 89f, 89f, 72f, 91f)
        cubicTo(66f, 91f, 61f, 85f, 58f, 77f)
        cubicTo(62f, 80f, 65f, 82f, 68f, 81f); close()
    }
    val flame = Path().apply {
        moveTo(48f, 1f); cubicTo(70f, 28f, 44f, 39f, 59f, 51f)
        cubicTo(69f, 40f, 66f, 32f, 74f, 24f)
        cubicTo(74f, 48f, 97f, 53f, 91f, 75f)
        cubicTo(86f, 99f, 60f, 101f, 47f, 96f)
        cubicTo(15f, 100f, 1f, 77f, 15f, 53f)
        cubicTo(17f, 71f, 32f, 66f, 29f, 52f)
        cubicTo(17f, 30f, 49f, 20f, 48f, 1f); close()
    }
    val flameCore = Path().apply {
        moveTo(53f, 42f); cubicTo(70f, 63f, 60f, 69f, 68f, 77f)
        cubicTo(77f, 67f, 81f, 80f, 71f, 88f)
        cubicTo(49f, 105f, 26f, 82f, 39f, 64f)
        cubicTo(38f, 83f, 52f, 78f, 48f, 66f)
        cubicTo(44f, 57f, 55f, 51f, 53f, 42f); close()
    }
    val flameTongue = Path().apply {
        moveTo(10f, 39f); cubicTo(1f, 28f, 22f, 16f, 19f, 6f)
        cubicTo(31f, 17f, 19f, 25f, 22f, 32f)
        cubicTo(22f, 40f, 16f, 46f, 10f, 39f); close()
    }
    val ember = Path().apply {
        moveTo(57f, 2f); cubicTo(87f, 49f, 79f, 89f, 46f, 98f)
        cubicTo(14f, 83f, 33f, 48f, 57f, 2f); close()
    }
    val iceShard = polygon(43f, 1f, 75f, 20f, 91f, 65f, 60f, 99f, 22f, 86f, 4f, 47f)
    val iceFace = polygon(43f, 1f, 49f, 50f, 22f, 86f, 4f, 47f)
    val iceShadow = polygon(49f, 50f, 75f, 20f, 91f, 65f, 60f, 99f)
    val iceHighlight = polygon(43f, 1f, 57f, 16f, 60f, 62f, 49f, 50f, 36f, 66f)
    val iceSplinter = polygon(84f, 44f, 101f, 28f, 110f, 72f, 93f, 87f)
    val eclipseShadow = Path().apply {
        moveTo(77f, 8f); cubicTo(18f, -19f, -18f, 49f, 14f, 83f)
        cubicTo(32f, 108f, 62f, 105f, 82f, 86f)
        cubicTo(38f, 103f, 11f, 51f, 42f, 24f)
        cubicTo(54f, 12f, 66f, 11f, 77f, 8f); close()
    }
    val eclipseCorona = Path().apply {
        moveTo(72f, 4f); cubicTo(20f, -10f, -4f, 36f, 10f, 67f)
        cubicTo(21f, 91f, 51f, 102f, 75f, 88f)
        cubicTo(42f, 94f, 18f, 71f, 21f, 44f)
        cubicTo(23f, 18f, 47f, 8f, 72f, 4f); close()
    }
    val eclipseInner = Path().apply {
        moveTo(67f, 13f); cubicTo(34f, 13f, 18f, 38f, 28f, 64f)
        cubicTo(36f, 82f, 54f, 88f, 76f, 85f)
        cubicTo(50f, 77f, 37f, 64f, 37f, 43f)
        cubicTo(37f, 26f, 49f, 20f, 67f, 13f); close()
    }
    val eclipseCrest = Path().apply {
        moveTo(57f, 4f); cubicTo(31f, 6f, 10f, 23f, 12f, 47f)
        cubicTo(15f, 28f, 26f, 17f, 44f, 13f)
        cubicTo(50f, 11f, 54f, 7f, 57f, 4f); close()
        moveTo(83f, 80f); cubicTo(95f, 68f, 99f, 43f, 90f, 29f)
        cubicTo(104f, 43f, 105f, 70f, 83f, 80f); close()
    }
    val spark = polygon(49f, 0f, 59f, 30f, 83f, 13f, 71f, 42f, 100f, 48f,
        69f, 59f, 84f, 88f, 57f, 73f, 49f, 100f, 40f, 68f,
        12f, 83f, 29f, 56f, 0f, 45f, 33f, 37f, 20f, 13f, 43f, 29f)
    val sparkFacet = polygon(49f, 0f, 49f, 45f, 100f, 48f, 57f, 55f,
        84f, 88f, 49f, 61f, 49f, 100f, 40f, 55f, 12f, 83f, 40f, 46f)
    val sparkCore = polygon(49f, 28f, 56f, 43f, 73f, 48f, 55f, 56f,
        49f, 74f, 42f, 56f, 25f, 47f, 42f, 42f)
    val sparkSatellite = Path().apply {
        addPath(polygon(91f, 2f, 98f, 10f, 91f, 23f, 83f, 13f))
        addPath(polygon(5f, 89f, 15f, 91f, 13f, 103f, 3f, 100f))
    }
    val drop = Path().apply {
        moveTo(23f, 16f); cubicTo(53f, -4f, 94f, 27f, 87f, 62f)
        cubicTo(87f, 94f, 42f, 104f, 17f, 80f)
        cubicTo(0f, 63f, 4f, 34f, 23f, 16f); close()
    }
    val smallShard = polygon(43f, 0f, 88f, 35f, 65f, 96f, 9f, 69f)

    private fun polygon(vararg points: Float) = Path().apply {
        moveTo(points[0], points[1])
        for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
        close()
    }
}
