package com.dominik.cardvault.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Explicit membership: adding a catalogue entry never silently changes its renderer. */
internal fun privateBinderUsesPremiumFoil(effectId: String): Boolean = when (effectId) {
    "rainbow", "linear", "diagonal", "galaxy", "nebula", "aurora", "crystal", "prism", "diamond" -> true
    else -> false
}

internal data class PrivateBinderPremiumFoilBudget(
    val bands: Int,
    val cloudSamples: Int,
    val facets: Int,
    val glints: Int,
)

/** Fixed ceilings also apply to malformed/custom render plans; there is no per-pixel work. */
internal fun privateBinderPremiumFoilBudget(primitiveBudget: Int): PrivateBinderPremiumFoilBudget = when {
    primitiveBudget <= 3 -> PrivateBinderPremiumFoilBudget(3, 18, 6, 4)
    primitiveBudget <= 7 -> PrivateBinderPremiumFoilBudget(4, 26, 9, 6)
    else -> PrivateBinderPremiumFoilBudget(5, 34, 12, 8)
}

/**
 * Transparent, filled optical materials. Geometry lives in a 100 x 100 card space and
 * every path has a fixed sample count. Only the existing rendered tilt supplies motion;
 * no clock, random source, sensor, bitmap, or persisted state is introduced here.
 */
internal fun DrawScope.drawPrivateBinderPremiumFoil(
    effectId: String,
    plan: PrivateBinderRenderPlan,
    optics: PrivateBinderFoilOptics,
) {
    if (!privateBinderUsesPremiumFoil(effectId) || !plan.intensity.isFinite() || plan.intensity <= 0f ||
        optics.grooveAlpha <= 0f || size.width <= 0f || size.height <= 0f) return
    val budget = privateBinderPremiumFoilBudget(plan.primitiveBudget)
    val strength = optics.grooveAlpha.coerceIn(0f, 1f)
    clipRect {
        withTransform({ scale(size.width / 100f, size.height / 100f, Offset.Zero) }) {
            val material = PremiumFoilPainter(this, optics, strength, budget)
            when (effectId) {
                "rainbow" -> material.rainbow()
                "linear" -> material.linear()
                "diagonal" -> material.diagonal()
                "galaxy" -> material.galaxy()
                "nebula" -> material.nebula()
                "aurora" -> material.aurora()
                "crystal" -> material.crystal()
                "prism" -> material.prism()
                "diamond" -> material.diamond()
            }
        }
    }
}

private class PremiumFoilPainter(
    private val canvas: DrawScope,
    private val optics: PrivateBinderFoilOptics,
    private val strength: Float,
    private val budget: PrivateBinderPremiumFoilBudget,
) {
    private val shiftX = optics.normalizedTiltY * 3f
    private val shiftY = optics.normalizedTiltX * 2f
    private val reflectionX = optics.reflectionCenterX * 100f
    private val phase = optics.hueShift

    fun rainbow() {
        // Wide bowed diffraction ribbons: filled colour with transparent shoulders.
        repeat(budget.bands) { index ->
            val baseline = 13f + index * 79f / (budget.bands - 1)
            val points = curvePoints { t ->
                Offset(-10f + t * 120f + shiftX,
                    baseline - sin(t * PI.toFloat()) * 16f + sin(t * PI.toFloat() * 2f) * 3f + shiftY)
            }
            val brush = spectrumBrush(index * .45f, Offset(-8f, baseline - 20f), Offset(108f, baseline + 12f))
            featheredRibbon(points, 7.5f + (index % 2) * 1.2f, brush, .80f)
            ribbonReflection(points, 5f, .22f)
        }
        scatteredGlints(11, budget.glints, .75f)
    }

    fun linear() {
        // Brushed satin lamellae. Their wide blue/silver bodies remain visible at rest;
        // a compact spectral highlight travels inside each band with the card angle.
        repeat(budget.bands) { index ->
            val y = 13f + index * 82f / (budget.bands - 1) + shiftY
            val points = listOf(Offset(-12f, y + 5f), Offset(112f, y - 5f))
            val brush = Brush.linearGradient(
                0f to Color.Transparent,
                .18f to Color(0xFF7775FF).copy(alpha = .36f),
                .43f to Color(0xFF46CFFF).copy(alpha = .80f),
                .55f to Color(0xFFD5F6FF).copy(alpha = .88f),
                .68f to Color(0xFFB497FF).copy(alpha = .52f),
                1f to Color.Transparent,
                start = Offset(49f, y - 8f), end = Offset(51f, y + 8f),
            )
            featheredRibbon(points, 8f, brush, .91f)
            val band = ribbonPath(points, 7f)
            canvas.clipPath(band) {
                drawCircle(
                    Brush.radialGradient(listOf(spectrum(index + phase).copy(alpha = .56f), Color.Transparent),
                        center = Offset(reflectionX + index * 4f - 6f, y), radius = 25f),
                    radius = 25f, center = Offset(reflectionX + index * 4f - 6f, y), alpha = strength,
                )
                repeat(6) { groove ->
                    val offset = -5f + groove * 1.8f
                    drawLine(Color(0xFFE0F6FF), Offset(-10f, y + 5f + offset),
                        Offset(110f, y - 5f + offset), strokeWidth = .14f,
                        alpha = strength * .18f, blendMode = BlendMode.Screen)
                }
            }
        }
        scatteredGlints(23, budget.glints / 2, .62f)
    }

    fun diagonal() {
        // Broad oblique foil panels, with different colour on their two refracting faces.
        repeat(budget.bands + 1) { index ->
            val x = -28f + index * 156f / budget.bands + shiftX
            val points = listOf(Offset(x - 33f, 109f + shiftY), Offset(x + 33f, -9f + shiftY))
            val brush = Brush.linearGradient(
                0f to Color.Transparent,
                .22f to spectrum(index * .8f + phase).copy(alpha = .60f),
                .48f to spectrum(index * .8f + phase + 1.5f).copy(alpha = .88f),
                .58f to Color(0xFFC3FAFF).copy(alpha = .56f),
                .75f to spectrum(index * .8f + phase + 3.2f).copy(alpha = .68f),
                1f to Color.Transparent,
                start = Offset(x - 11f, 44f), end = Offset(x + 11f, 56f),
            )
            featheredRibbon(points, 9f, brush, .92f)
            ribbonReflection(points, 6f, .24f)
        }
        scatteredGlints(41, budget.glints, .66f)
    }

    fun galaxy() {
        // Two dusty spiral arms with coloured volume, a small luminous nucleus, and
        // discrete stars. Dust samples are deterministic and capped at 34.
        val origin = Offset(49f + shiftX, 43f + shiftY)
        repeat(budget.cloudSamples) { index ->
            val arm = index % 2
            val t = (index / 2f + 1f) / (budget.cloudSamples / 2f)
            val angle = .3f + t * 5.5f + arm * PI.toFloat() + phase * .045f
            val radius = 5f + t * 49f
            val location = origin + Offset(cos(angle) * radius, sin(angle) * radius * .74f)
            val colour = lerp(Color(0xFF8051EF), if (arm == 0) Color(0xFFEC67EF) else Color(0xFF45DFFF),
                (.3f + .5f * sin(t * 5f + phase)).coerceIn(0f, 1f))
            glow(location, 5.8f + t * 5.2f, colour, .36f)
            if (index % 3 == 0) glow(location + Offset(1.2f, -1.1f), 3.3f, Color(0xFF8FE9FF), .28f)
        }
        glow(origin, 12f, Color(0xFFB980FF), .43f)
        glow(origin, 4.4f, Color(0xFFF2D7FF), .50f)
        repeat(budget.cloudSamples * 2) { index ->
            val x = 4f + fraction(index * 47 + 13) * 92f
            val y = 5f + fraction(index * 71 + 31) * 90f
            val colour = if (index % 3 == 0) Color(0xFFB7A4FF) else Color(0xFFC3EDFF)
            canvas.drawCircle(colour, radius = .12f + index % 3 * .075f,
                center = Offset(x + shiftX * .5f, y + shiftY * .5f), alpha = strength * (.42f + index % 4 * .12f))
        }
        scatteredGlints(67, budget.glints, .91f)
    }

    fun nebula() {
        // Irregular overlapping gas pockets, with clear space between them. No card-wide
        // tint or white fog: saturated inner colour fades completely at each cloud edge.
        repeat(budget.cloudSamples) { index ->
            val cluster = index % 3
            val t = index / budget.cloudSamples.toFloat()
            val angle = index * 2.39996f
            val radius = 3f + fraction(index * 37 + 7) * 15f
            val base = when (cluster) {
                0 -> Offset(22f, 24f)
                1 -> Offset(74f, 47f)
                else -> Offset(31f, 83f)
            }
            val location = base + Offset(cos(angle) * radius * 1.15f + shiftX, sin(angle) * radius * .65f + shiftY)
            val colour = when (cluster) {
                0 -> lerp(Color(0xFF6D6EFF), Color(0xFFA24AF3), t)
                1 -> lerp(Color(0xFFD64AE7), Color(0xFFFF82C1), t * .7f)
                else -> lerp(Color(0xFF26D8E8), Color(0xFF697FFF), t)
            }
            glow(location, 10f + fraction(index * 19) * 10f, colour, .31f)
            if (index % 4 == 0) glow(location + Offset(-2f, 1f), 5.8f,
                lerp(colour, Color(0xFFD3D4FF), .22f), .31f)
        }
        repeat(budget.bands - 1) { index ->
            val points = curvePoints { t ->
                Offset(-7f + t * 114f + shiftX, 24f + index * 33f + sin(t * 7f + index) * 8f + shiftY)
            }
            featheredRibbon(points, 2.7f, spectrumBrush(phase + 3f + index,
                Offset(0f, 12f), Offset(100f, 98f)), .23f)
        }
        scatteredGlints(89, budget.glints / 2, .63f)
    }

    fun aurora() {
        // Wide folded curtains, not wave outlines. The bright fold is inside a soft,
        // translucent green/cyan body, fading to violet near the bottom.
        repeat(budget.bands) { index ->
            val baseX = 9f + index * 82f / (budget.bands - 1)
            val points = curvePoints { t ->
                Offset(baseX + sin(t * 6f + index * .6f) * 10f + shiftX, -9f + t * 118f + shiftY)
            }
            val brush = Brush.linearGradient(
                colors = listOf(Color(0xFF8CF7B9), Color(0xFF27EDBC), Color(0xFF35CBE4), Color(0xFFAB71FF), Color(0xFFE992EE)),
                start = Offset(baseX, -12f + optics.normalizedTiltX * 16f), end = Offset(baseX + 12f, 113f),
            )
            featheredRibbon(points, 8.5f, brush, .67f)
            val foldPoints = points.map { Offset(it.x - 2.5f, it.y) }
            featheredRibbon(foldPoints, 2.5f, brush, .39f)
            ribbonReflection(points, 6f, .19f)
        }
        scatteredGlints(103, budget.glints / 2, .55f)
    }

    fun crystal() {
        // Asymmetric, filled splinters with three separate refracting planes. Only
        // selected internal cuts catch light; there is no repeated wireframe overlay.
        repeat(budget.facets) { index ->
            val column = index % 3
            val row = index / 3
            val rows = (budget.facets + 2) / 3
            val x = 15f + column * 35f + sin(index * 2.7f) * 5f + shiftX
            val y = 13f + row * 78f / (rows - 1).coerceAtLeast(1) + cos(index * 1.9f) * 5f + shiftY
            val lean = if (index % 2 == 0) 1f else -1f
            val a = Offset(x - 4f * lean, y - 17f)
            val b = Offset(x + 13f, y - 2f)
            val c = Offset(x + 4f * lean, y + 19f)
            val d = Offset(x - 12f, y + 5f)
            val middle = Offset(x + lean * 2f, y)
            val light = reflection(x)
            facet(listOf(a, b, middle), Color(0xFF87DFFF), Color(0xFF9994FF), .39f + light * .11f)
            facet(listOf(b, c, middle), Color(0xFFB9C7FF), Color(0xFF48BCE9), .26f + light * .13f)
            facet(listOf(c, d, a, middle), Color(0xFF78B9F4), Color(0xFFA996FA), .17f + light * .10f)
            softEdge(a, middle, Color(0xFFCCFBFF), .24f + light * .2f)
            if (index % 3 == 0) glint(a, .95f, .76f)
        }
    }

    fun prism() {
        // A compact cut-glass prism splits light into broad translucent fan faces.
        // The fan has coloured volume and fading ends, not outlined triangle spokes.
        val origin = Offset(35f + shiftX, 36f + shiftY)
        val fanCount = budget.bands + 2
        repeat(fanCount) { index ->
            val angle = -.40f + index * 1.95f / fanCount
            val nextAngle = angle + 1.7f / fanCount
            val edgeA = origin + Offset(cos(angle) * 111f, sin(angle) * 100f)
            val edgeB = origin + Offset(cos(nextAngle) * 111f, sin(nextAngle) * 100f)
            val colour = spectrum(index * .76f + phase)
            val path = polygon(listOf(origin, edgeA, edgeB))
            val brush = Brush.radialGradient(
                0f to colour.copy(alpha = .20f),
                .23f to colour.copy(alpha = .64f),
                .62f to colour.copy(alpha = .44f),
                1f to Color.Transparent,
                center = origin, radius = 112f,
            )
            canvas.drawPath(path, brush, alpha = strength * .64f)
            // Soft colour bleeding is local to each face edge.
            canvas.drawPath(path, brush, alpha = strength * .035f, style = Stroke(3f))
        }
        val a = Offset(13f + shiftX, 16f + shiftY)
        val b = Offset(65f + shiftX, 27f + shiftY)
        val c = Offset(29f + shiftX, 70f + shiftY)
        facet(listOf(a, b, origin), Color(0xFF8CEDFF), Color(0xFF9775FC), .47f)
        facet(listOf(a, c, origin), Color(0xFFDE92FF), Color(0xFF55AFFF), .35f)
        facet(listOf(b, c, origin), Color(0xFFFFD36A), Color(0xFFFD7ECF), .30f)
        softEdge(a, origin, Color(0xFFC4FAFF), .46f)
        softEdge(b, origin, Color(0xFFFFE3AF), .42f)
        glow(origin, 9f, Color(0xFFACEFFF), .31f)
        glint(a, 1.1f, .86f)
        glint(b, .8f, .76f)
        glint(c, .75f, .72f)
    }

    fun diamond() {
        // Small brilliant-cut gems with alternating triangular faces and a raised table.
        // Most of the card stays transparent between the gems; no pale atlas underneath.
        repeat(budget.facets) { index ->
            val column = index % 3
            val row = index / 3
            val rows = (budget.facets + 2) / 3
            val x = 16f + column * 34f + (if (row % 2 == 0) -3f else 3f) + shiftX
            val y = 13f + row * 75f / (rows - 1).coerceAtLeast(1) + shiftY
            val width = 10f + index % 3 * 1.3f
            val height = width * .91f
            val top = Offset(x, y - height)
            val right = Offset(x + width, y)
            val bottom = Offset(x, y + height)
            val left = Offset(x - width, y)
            val innerTop = Offset(x, y - height * .35f)
            val innerRight = Offset(x + width * .36f, y)
            val innerBottom = Offset(x, y + height * .35f)
            val innerLeft = Offset(x - width * .36f, y)
            val light = reflection(x + (index % 2) * 13f)
            val tint = lerp(Color(0xFF59D1EE), spectrum(phase + index * .45f), .42f)
            // A restrained coloured underside is confined to the gem silhouette. It
            // gives pale artwork local cut-glass contrast without any all-card veil.
            canvas.drawPath(polygon(listOf(top, right, bottom, left)),
                Brush.linearGradient(listOf(Color(0xFF7974B4).copy(alpha = .24f),
                    Color(0xFF55CFE0).copy(alpha = .08f), Color(0xFF716296).copy(alpha = .22f)),
                    start = top, end = bottom), alpha = strength * .55f)
            facet(listOf(top, right, innerRight, innerTop), Color(0xFFB2F1FF), tint, .38f + light * .26f)
            facet(listOf(right, bottom, innerBottom, innerRight), Color(0xFF6DBBDC), Color(0xFF8669CF), .30f + (1f - light) * .17f)
            facet(listOf(bottom, left, innerLeft, innerBottom), Color(0xFFAB86E9), Color(0xFF45BDD1), .37f + light * .14f)
            facet(listOf(left, top, innerTop, innerLeft), Color(0xFF69BEDB), Color(0xFFA5E5F4), .32f + (1f - light) * .18f)
            facet(listOf(innerTop, innerRight, innerBottom, innerLeft), Color(0xFF92DEFB), tint, .31f + light * .18f)
            softEdge(top, innerTop, Color(0xFFF4FCFF), .32f + light * .32f)
            if (index % 2 == 0) glint(top, .7f + light * .42f, .64f + light * .25f)
        }
    }

    private fun featheredRibbon(points: List<Offset>, halfWidth: Float, brush: Brush, exposure: Float) {
        // Twelve low-opacity filled shoulders approximate a smooth cosine falloff.
        // Preserve the original core transmittance exactly: smoothing adds neither
        // brightness nor a pale wash. No blur filter or per-pixel sampling is needed.
        val amount = strength * exposure
        val coreTransmission = (1f - amount * .065f) * (1f - amount * .16f) *
            (1f - amount * .24f) * (1f - amount * .26f)
        for (index in FoilShoulderWeights.indices) {
            val width = halfWidth * 1.26f * (1f - index / 12f)
            val alpha = 1f - coreTransmission.pow(FoilShoulderWeights[index])
            canvas.drawPath(ribbonPath(points, width), brush, alpha = alpha)
        }
    }

    private fun ribbonReflection(points: List<Offset>, halfWidth: Float, exposure: Float) {
        val path = ribbonPath(points, halfWidth)
        val location = Offset(reflectionX, 49f - optics.normalizedTiltX * 24f)
        val brush = Brush.radialGradient(
            listOf(Color(0xFFDCF8FF).copy(alpha = exposure), Color.Transparent), location, 37f)
        canvas.drawPath(path, brush, alpha = strength, blendMode = BlendMode.Screen)
    }

    private fun glow(location: Offset, radius: Float, colour: Color, exposure: Float) {
        val brush = Brush.radialGradient(
            0f to colour.copy(alpha = exposure),
            .28f to colour.copy(alpha = exposure * .77f),
            .65f to colour.copy(alpha = exposure * .27f),
            1f to Color.Transparent,
            center = location, radius = radius,
        )
        canvas.drawCircle(brush, radius = radius, center = location, alpha = strength)
    }

    private fun facet(vertices: List<Offset>, bright: Color, shade: Color, exposure: Float) {
        val path = polygon(vertices)
        val a = vertices.first()
        val b = vertices[vertices.size / 2]
        val highlight = lerp(bright, spectrum(phase + a.x * .018f), .13f)
        val brush = Brush.linearGradient(
            listOf(highlight.copy(alpha = .91f), shade.copy(alpha = .48f), shade.copy(alpha = .06f)),
            start = a, end = b,
        )
        canvas.drawPath(path, brush, alpha = strength * exposure)
        canvas.drawPath(path, brush, alpha = strength * exposure * .065f, style = Stroke(1.25f))
    }

    private fun softEdge(a: Offset, b: Offset, colour: Color, exposure: Float) {
        canvas.drawLine(colour, a, b, strokeWidth = 1.4f, alpha = strength * exposure * .08f, cap = StrokeCap.Round)
        canvas.drawLine(colour, a, b, strokeWidth = .56f, alpha = strength * exposure * .22f, cap = StrokeCap.Round)
        canvas.drawLine(colour, a, b, strokeWidth = .18f, alpha = strength * exposure * .66f, cap = StrokeCap.Round)
    }

    private fun scatteredGlints(seed: Int, count: Int, exposure: Float) {
        repeat(count) { index ->
            val x = 7f + fraction(seed + index * 47) * 86f + shiftX
            val y = 7f + fraction(seed * 3 + index * 61) * 86f + shiftY
            glint(Offset(x, y), .50f + fraction(index * 37 + seed) * .75f,
                exposure * (.6f + reflection(x) * .4f))
        }
    }

    private fun glint(location: Offset, radius: Float, exposure: Float) {
        val colour = lerp(spectrum(phase + location.y * .04f), Color(0xFFEDF9FF), .72f)
        glow(location, radius * 3f, colour, exposure * .23f)
        val path = polygon(listOf(
            location + Offset(0f, -radius), location + Offset(radius * .20f, -radius * .17f),
            location + Offset(radius, 0f), location + Offset(radius * .20f, radius * .17f),
            location + Offset(0f, radius), location + Offset(-radius * .20f, radius * .17f),
            location + Offset(-radius, 0f), location + Offset(-radius * .20f, -radius * .17f),
        ))
        canvas.drawPath(path, colour, alpha = strength * exposure, blendMode = BlendMode.Screen)
        canvas.drawCircle(Color.White, radius = radius * .14f, center = location, alpha = strength * exposure * .8f)
    }

    private fun spectrumBrush(offset: Float, start: Offset, end: Offset): Brush = Brush.linearGradient(
        colors = List(13) { spectrum(it * .5f + offset + phase) }, start = start, end = end,
    )

    private fun reflection(x: Float): Float = (1f - abs(x - reflectionX) / 68f).coerceIn(0f, 1f)
}

private fun curvePoints(position: (Float) -> Offset): List<Offset> = List(25) { position(it / 24f) }

private fun ribbonPath(points: List<Offset>, halfWidth: Float): Path {
    val shoulders = points.mapIndexed { index, point ->
        val before = points[(index - 1).coerceAtLeast(0)]
        val after = points[(index + 1).coerceAtMost(points.lastIndex)]
        val tangent = after - before
        val length = sqrt(tangent.x * tangent.x + tangent.y * tangent.y).coerceAtLeast(.001f)
        val normal = Offset(-tangent.y / length, tangent.x / length) * halfWidth
        Pair(point + normal, point - normal)
    }
    return polygon(shoulders.map { it.first } + shoulders.asReversed().map { it.second })
}

private fun polygon(vertices: List<Offset>): Path = Path().apply {
    moveTo(vertices.first().x, vertices.first().y)
    vertices.drop(1).forEach { lineTo(it.x, it.y) }
    close()
}

private val PremiumSpectrum = listOf(
    Color(0xFFFF467A), Color(0xFFFFCE46), Color(0xFF55E58B),
    Color(0xFF35DDF1), Color(0xFF8273F5), Color(0xFFEC64DE),
)

/** Precomputed and normalized once; the 12-pass feather budget never grows with size. */
private val FoilShoulderWeights: List<Float> = List(12) { sin((it + .5f) * PI.toFloat() / 12f) }
    .let { weights -> val total = weights.sum(); weights.map { it / total } }

private fun spectrum(phase: Float): Color {
    val safePhase = phase.takeIf(Float::isFinite) ?: 0f
    val position = ((safePhase % 6f) + 6f) % 6f
    val index = position.toInt()
    return lerp(PremiumSpectrum[index], PremiumSpectrum[(index + 1) % 6], position - index)
}

private fun fraction(value: Int): Float = ((value % 101 + 101) % 101) / 101f
