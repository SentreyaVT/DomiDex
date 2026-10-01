package com.dominik.cardvault.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.floor

internal fun privateBinderUsesElementFoil(effectId: String): Boolean = when (effectId) {
    "element_grass", "element_fire", "element_water", "element_electric", "element_psychic",
    "element_fighting", "element_darkness", "element_metal", "element_dragon",
    "element_colorless", "element_fairy" -> true
    else -> false
}

/**
 * Small, sculpted foil motifs with transparent space between them. The element colours
 * live in the filled faces; their prismatic facets and rims follow the card's tilt.
 * Paths and placements are immutable and shared across frames. No texture atlas or clock.
 */
internal fun DrawScope.drawPrivateBinderElementFoil(
    effectId: String,
    plan: PrivateBinderRenderPlan,
    optics: PrivateBinderFoilOptics,
) {
    val artwork = ElementFoilGeometry.artworks[effectId] ?: return
    if (plan.intensity <= 0f || optics.grooveAlpha <= 0f) return
    val alpha = (optics.grooveAlpha * .96f + plan.intensity * .18f).coerceIn(0f, .97f)
    val count = when {
        plan.primitiveBudget <= 3 -> 12
        plan.primitiveBudget <= 7 -> 16
        else -> 20
    }
    val shiftX = optics.normalizedTiltY * size.width * .009f
    val shiftY = optics.normalizedTiltX * size.height * .007f
    val face = Brush.linearGradient(
        colors = listOf(artwork.shadow, artwork.color, artwork.light, artwork.color),
        start = Offset(0f + optics.normalizedTiltY * 15f, 5f),
        end = Offset(96f, 112f + optics.normalizedTiltX * 15f),
    )
    val prisms = List(6) { band ->
        Brush.linearGradient(
            colors = List(13) { sample ->
                val position = sample / 12f
                val colour = elementFoilSpectrum(position * 5.4f + band * .77f + optics.hueShift)
                lerp(colour, artwork.light, .12f)
            },
            start = Offset(-6f, -5f),
            end = Offset(106f, 102f),
        )
    }
    for (index in 0 until count) {
        val placement = ElementFoilGeometry.placements[index]
        val reflection = (1f - abs(placement.x - optics.reflectionCenterX) * 1.7f).coerceIn(0f, 1f)
        val localAlpha = alpha * (.76f + reflection * .24f)
        val spectrum = prisms[index % prisms.size]
        val scale = size.width * placement.width / 100f
        withTransform({
            translate(placement.x * size.width + shiftX, placement.y * size.height + shiftY)
            rotate(placement.rotation, Offset.Zero)
            scale(scale, scale, Offset.Zero)
            translate(-50f, -50f)
        }) {
            // The dark, narrow lip supplies relief over pale artwork without a backing.
            drawPath(artwork.body, artwork.shadow, alpha = localAlpha * .32f,
                style = Stroke(3.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(artwork.body, face, alpha = localAlpha * .48f)
            drawPath(artwork.facet, spectrum, alpha = localAlpha * .43f)
            drawPath(artwork.highlight, spectrum, alpha = localAlpha * .68f)
            elementFoilEtching(artwork.body, spectrum, localAlpha * .88f, 1.55f)
            elementFoilEtching(artwork.details, spectrum, localAlpha * .96f, 1.8f)
            if (index % 3 == 0) {
                drawCircle(artwork.light, radius = 1.4f,
                    center = artwork.glint, alpha = localAlpha * (.40f + reflection * .38f))
            }
        }

        // Flames and lightning remain complete, separated silhouettes. Their smaller
        // duplicates could touch the parent and read as stacked strokes, so only the
        // sparse isolated glints accompany those two materials.
        if (effectId != "element_fire" && effectId != "element_electric") {
            val companionScale = scale * (.27f + (index % 3) * .065f)
            withTransform({
                translate((placement.x + if (index % 2 == 0) .070f else -.063f) * size.width + shiftX,
                    (placement.y + .051f) * size.height + shiftY)
                rotate(-placement.rotation + 24f, Offset.Zero)
                scale(companionScale, companionScale, Offset.Zero)
                translate(-50f, -50f)
            }) {
                drawPath(artwork.companion, face, alpha = localAlpha * .58f)
                drawPath(artwork.companion, prisms[(index + 2) % prisms.size], alpha = localAlpha * .52f)
            }
        }
    }

    // Sparse engraved dust catches the same reflection; never a white or milky wash.
    for (index in 0 until count + 6) {
        val x = .055f + ((index * 41 + 11) % 89) / 100f
        val y = .055f + ((index * 29 + 7) % 89) / 100f
        val reflection = (1f - abs(x - optics.reflectionCenterX) * 1.7f).coerceIn(0f, 1f)
        val fleckAlpha = alpha * (.33f + reflection * .30f)
        val scale = size.width * (.013f + index % 3 * .006f) / 100f
        withTransform({
            translate(x * size.width + shiftX, y * size.height + shiftY)
            scale(scale, scale, Offset.Zero)
            translate(-50f, -50f)
        }) {
            drawPath(ElementFoilGeometry.glint, prisms[index % prisms.size], alpha = fleckAlpha)
        }
    }
}

private fun DrawScope.elementFoilEtching(path: Path, brush: Brush, alpha: Float, width: Float) {
    drawPath(path, brush, alpha = alpha * .08f,
        style = Stroke(width * 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, brush, alpha = alpha,
        style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private val ElementFoilSpectrum = listOf(
    Color(0xFFFF539A), Color(0xFFFFCD45), Color(0xFF7BE549),
    Color(0xFF36DDF5), Color(0xFF8D7AFF), Color(0xFFE65DEA),
)

private fun elementFoilSpectrum(phase: Float): Color {
    val position = ((phase % 6f) + 6f) % 6f
    val index = floor(position).toInt()
    return lerp(ElementFoilSpectrum[index], ElementFoilSpectrum[(index + 1) % 6], position - index)
}

private data class ElementFoilPlacement(
    val x: Float, val y: Float, val width: Float, val rotation: Float,
)

private data class ElementFoilArtwork(
    val body: Path,
    val facet: Path,
    val highlight: Path,
    val details: Path,
    val companion: Path,
    val shadow: Color,
    val color: Color,
    val light: Color,
    val glint: Offset = Offset(33f, 28f),
)

/** All designs use a 100-unit local canvas, with clear space between the scattered motifs. */
private object ElementFoilGeometry {
    val placements = listOf(
        ElementFoilPlacement(.16f, .14f, .21f, -22f),
        ElementFoilPlacement(.80f, .12f, .16f, 18f),
        ElementFoilPlacement(.86f, .33f, .23f, 12f),
        ElementFoilPlacement(.12f, .43f, .19f, -15f),
        ElementFoilPlacement(.31f, .62f, .22f, 27f),
        ElementFoilPlacement(.76f, .70f, .20f, -17f),
        ElementFoilPlacement(.14f, .86f, .20f, 20f),
        ElementFoilPlacement(.87f, .91f, .16f, -24f),
        ElementFoilPlacement(.54f, .20f, .17f, -8f),
        ElementFoilPlacement(.58f, .85f, .18f, 16f),
        ElementFoilPlacement(.59f, .47f, .16f, 9f),
        ElementFoilPlacement(.38f, .36f, .17f, -27f),
        ElementFoilPlacement(.07f, .67f, .14f, -12f),
        ElementFoilPlacement(.91f, .52f, .15f, 20f),
        ElementFoilPlacement(.35f, .91f, .13f, 32f),
        ElementFoilPlacement(.36f, .07f, .14f, -9f),
        ElementFoilPlacement(.66f, .04f, .11f, 25f),
        ElementFoilPlacement(.63f, .62f, .12f, -18f),
        ElementFoilPlacement(.06f, .26f, .13f, 14f),
        ElementFoilPlacement(.93f, .78f, .13f, -10f),
    )

    val glint = Path().apply {
        moveTo(50f, 0f); cubicTo(54f, 39f, 61f, 46f, 100f, 50f)
        cubicTo(61f, 54f, 54f, 61f, 50f, 100f)
        cubicTo(46f, 61f, 39f, 54f, 0f, 50f)
        cubicTo(39f, 46f, 46f, 39f, 50f, 0f); close()
    }
    private val leaf = Path().apply {
        moveTo(17f, 88f); cubicTo(-2f, 39f, 41f, 8f, 91f, 6f)
        cubicTo(92f, 48f, 80f, 89f, 17f, 88f); close()
    }
    private val flame = Path().apply {
        moveTo(51f, 96f); cubicTo(14f, 101f, 3f, 75f, 20f, 48f)
        cubicTo(21f, 64f, 31f, 64f, 32f, 49f)
        cubicTo(28f, 28f, 53f, 22f, 51f, 2f)
        cubicTo(76f, 25f, 61f, 42f, 73f, 52f)
        cubicTo(81f, 57f, 81f, 44f, 82f, 36f)
        cubicTo(113f, 74f, 81f, 99f, 51f, 96f); close()
    }
    private val drop = Path().apply {
        moveTo(50f, 4f); cubicTo(44f, 29f, 17f, 40f, 17f, 64f)
        cubicTo(17f, 104f, 83f, 104f, 83f, 64f)
        cubicTo(83f, 40f, 60f, 28f, 50f, 4f); close()
    }
    private val bolt = Path().apply {
        moveTo(53f, 2f); lineTo(14f, 53f); lineTo(43f, 49f)
        lineTo(29f, 98f); lineTo(87f, 34f); lineTo(58f, 40f)
        lineTo(77f, 3f); close()
    }
    private val shard = Path().apply {
        moveTo(49f, 4f); lineTo(83f, 37f); lineTo(66f, 86f)
        lineTo(18f, 96f); lineTo(29f, 42f); close()
    }
    private val crescent = Path().apply {
        moveTo(79f, 10f); cubicTo(17f, -14f, -7f, 67f, 40f, 92f)
        cubicTo(61f, 103f, 82f, 92f, 91f, 74f)
        cubicTo(62f, 87f, 38f, 60f, 47f, 36f)
        cubicTo(52f, 21f, 66f, 12f, 79f, 10f); close()
    }
    private val scale = Path().apply {
        moveTo(9f, 26f); cubicTo(21f, 6f, 77f, 6f, 91f, 26f)
        cubicTo(95f, 56f, 73f, 81f, 50f, 96f)
        cubicTo(28f, 81f, 6f, 56f, 9f, 26f); close()
    }
    private val petal = Path().apply {
        moveTo(50f, 94f); cubicTo(8f, 76f, 6f, 24f, 31f, 11f)
        cubicTo(47f, 1f, 50f, 28f, 57f, 18f)
        cubicTo(91f, -4f, 109f, 57f, 50f, 94f); close()
    }
    private val star = Path().apply {
        moveTo(50f, 2f); lineTo(63f, 32f); lineTo(92f, 24f)
        lineTo(77f, 51f); lineTo(97f, 72f); lineTo(65f, 72f)
        lineTo(50f, 99f); lineTo(37f, 73f); lineTo(5f, 76f)
        lineTo(24f, 51f); lineTo(8f, 23f); lineTo(38f, 32f); close()
    }

    val artworks = mapOf(
        "element_grass" to ElementFoilArtwork(
            body = leaf,
            facet = Path().apply {
                moveTo(17f, 88f); cubicTo(41f, 56f, 57f, 37f, 91f, 6f)
                cubicTo(92f, 48f, 80f, 89f, 17f, 88f); close()
            },
            highlight = Path().apply {
                moveTo(18f, 67f); cubicTo(15f, 40f, 40f, 20f, 69f, 14f)
                cubicTo(39f, 31f, 28f, 46f, 18f, 67f); close()
            },
            details = Path().apply {
                moveTo(11f, 96f); cubicTo(37f, 65f, 61f, 34f, 87f, 11f)
                moveTo(37f, 63f); lineTo(30f, 40f); moveTo(51f, 46f); lineTo(49f, 25f)
                moveTo(35f, 66f); lineTo(62f, 68f); moveTo(51f, 47f); lineTo(77f, 44f)
            },
            companion = leaf,
            shadow = Color(0xFF153B36), color = Color(0xFF5BBE47), light = Color(0xFFD4ED7B),
        ),
        "element_fire" to ElementFoilArtwork(
            body = flame,
            facet = Path().apply {
                moveTo(47f, 92f); cubicTo(18f, 75f, 51f, 56f, 47f, 34f)
                cubicTo(72f, 55f, 44f, 67f, 66f, 89f); cubicTo(61f, 96f, 54f, 96f, 47f, 92f); close()
            },
            highlight = Path().apply {
                moveTo(75f, 85f); cubicTo(88f, 72f, 86f, 62f, 83f, 55f)
                cubicTo(102f, 72f, 89f, 91f, 75f, 85f); close()
            },
            details = Path().apply {
                moveTo(28f, 81f); cubicTo(22f, 73f, 23f, 65f, 25f, 61f)
                moveTo(60f, 42f); cubicTo(66f, 32f, 65f, 23f, 59f, 17f)
            },
            companion = flame,
            shadow = Color(0xFF743344), color = Color(0xFFFA6E34), light = Color(0xFFFFD866),
        ),
        "element_water" to ElementFoilArtwork(
            body = drop,
            facet = Path().apply {
                moveTo(50f, 10f); cubicTo(57f, 52f, 94f, 65f, 63f, 89f)
                cubicTo(91f, 84f, 91f, 63f, 78f, 46f); close()
            },
            highlight = Path().apply {
                moveTo(27f, 66f); cubicTo(25f, 49f, 41f, 37f, 45f, 29f)
                cubicTo(42f, 48f, 30f, 57f, 31f, 70f); close()
            },
            details = Path().apply {
                moveTo(6f, 83f); cubicTo(-8f, 105f, 107f, 110f, 100f, 83f)
                moveTo(8f, 91f); cubicTo(26f, 108f, 76f, 109f, 92f, 95f)
                moveTo(53f, 84f); cubicTo(65f, 85f, 72f, 77f, 73f, 68f)
            },
            companion = drop,
            shadow = Color(0xFF21436D), color = Color(0xFF399CDF), light = Color(0xFF97EBEE),
        ),
        "element_electric" to ElementFoilArtwork(
            body = bolt,
            facet = Path().apply {
                moveTo(53f, 2f); lineTo(43f, 49f); lineTo(14f, 53f); close()
                moveTo(87f, 34f); lineTo(43f, 64f); lineTo(29f, 98f); close()
            },
            highlight = Path().apply {
                moveTo(58f, 7f); lineTo(50f, 42f); lineTo(69f, 7f); close()
            },
            details = Path().apply {
                moveTo(18f, 22f); lineTo(8f, 16f); moveTo(90f, 70f); lineTo(99f, 76f)
                moveTo(20f, 75f); lineTo(9f, 83f)
            },
            companion = bolt,
            shadow = Color(0xFF6B4A2D), color = Color(0xFFF2C735), light = Color(0xFFFFED8D),
        ),
        "element_psychic" to ElementFoilArtwork(
            body = Path().apply {
                moveTo(3f, 51f); cubicTo(32f, 14f, 65f, 14f, 97f, 51f)
                cubicTo(66f, 84f, 32f, 84f, 3f, 51f); close()
            },
            facet = Path().apply {
                addOval(Rect(29f, 29f, 72f, 73f))
                moveTo(8f, 24f); cubicTo(30f, 0f, 70f, 2f, 91f, 23f)
                cubicTo(64f, 11f, 33f, 9f, 8f, 24f); close()
                moveTo(9f, 78f); cubicTo(36f, 93f, 67f, 88f, 92f, 77f)
                cubicTo(71f, 101f, 30f, 103f, 9f, 78f); close()
            },
            highlight = Path().apply {
                moveTo(43f, 34f); cubicTo(21f, 45f, 41f, 75f, 53f, 66f)
                cubicTo(39f, 60f, 37f, 47f, 43f, 34f); close()
                addOval(Rect(51f, 35f, 60f, 44f))
            },
            details = Path().apply {
                addOval(Rect(40f, 39f, 60f, 63f))
                moveTo(15f, 49f); cubicTo(29f, 35f, 41f, 30f, 49f, 30f)
                moveTo(52f, 6f); lineTo(52f, 0f); moveTo(50f, 94f); lineTo(50f, 101f)
            },
            companion = glint,
            shadow = Color(0xFF502153), color = Color(0xFFB35BCF), light = Color(0xFFFBA6E2),
        ),
        "element_fighting" to ElementFoilArtwork(
            body = Path().apply {
                moveTo(53f, 7f); lineTo(83f, 29f); lineTo(71f, 47f)
                lineTo(83f, 58f); lineTo(60f, 91f); lineTo(20f, 76f)
                lineTo(31f, 54f); lineTo(19f, 41f); close()
                moveTo(12f, 24f); lineTo(1f, 48f); lineTo(19f, 55f); close()
                moveTo(89f, 67f); lineTo(77f, 96f); lineTo(99f, 89f); close()
            },
            facet = Path().apply {
                moveTo(53f, 7f); lineTo(51f, 54f); lineTo(19f, 41f); close()
                moveTo(51f, 54f); lineTo(83f, 58f); lineTo(60f, 91f); close()
            },
            highlight = Path().apply {
                moveTo(29f, 72f); lineTo(51f, 54f); lineTo(39f, 77f); close()
            },
            details = Path().apply {
                moveTo(53f, 15f); lineTo(51f, 54f); lineTo(68f, 47f)
                moveTo(44f, 87f); lineTo(39f, 95f); moveTo(82f, 18f); lineTo(90f, 9f)
            },
            companion = shard,
            shadow = Color(0xFF663529), color = Color(0xFFC9824A), light = Color(0xFFF1C484),
        ),
        "element_darkness" to ElementFoilArtwork(
            body = crescent,
            facet = Path().apply {
                moveTo(58f, 9f); cubicTo(14f, 20f, 15f, 78f, 56f, 88f)
                cubicTo(4f, 75f, 8f, 19f, 58f, 9f); close()
            },
            highlight = Path().apply {
                moveTo(30f, 22f); cubicTo(14f, 44f, 18f, 71f, 31f, 80f)
                cubicTo(7f, 65f, 11f, 34f, 30f, 22f); close()
            },
            details = Path().apply {
                moveTo(56f, 46f); cubicTo(84f, 20f, 99f, 43f, 76f, 53f)
                cubicTo(58f, 60f, 62f, 69f, 79f, 64f)
                addOval(Rect(76f, 25f, 80f, 29f))
            },
            companion = crescent,
            shadow = Color(0xFF191D3D), color = Color(0xFF645C9E), light = Color(0xFF92BDCF),
        ),
        "element_metal" to ElementFoilArtwork(
            body = Path().apply {
                moveTo(25f, 10f); lineTo(77f, 10f); lineTo(94f, 30f)
                lineTo(82f, 81f); lineTo(28f, 95f); lineTo(7f, 70f); close()
            },
            facet = Path().apply {
                moveTo(25f, 10f); lineTo(35f, 29f); lineTo(20f, 66f); lineTo(7f, 70f); close()
                moveTo(20f, 66f); lineTo(75f, 64f); lineTo(82f, 81f); lineTo(28f, 95f); close()
            },
            highlight = Path().apply {
                moveTo(35f, 29f); lineTo(77f, 10f); lineTo(94f, 30f); lineTo(75f, 64f); close()
            },
            details = Path().apply {
                moveTo(35f, 29f); lineTo(75f, 32f); lineTo(75f, 64f); lineTo(20f, 66f)
                addOval(Rect(31f, 44f, 35f, 48f)); addOval(Rect(62f, 47f, 66f, 51f))
                moveTo(35f, 78f); lineTo(64f, 72f)
            },
            companion = shard,
            shadow = Color(0xFF384559), color = Color(0xFF98AEB9), light = Color(0xFFDEF0EA),
        ),
        "element_dragon" to ElementFoilArtwork(
            body = scale,
            facet = Path().apply {
                moveTo(50f, 17f); lineTo(50f, 96f)
                cubicTo(73f, 81f, 95f, 56f, 91f, 26f); close()
            },
            highlight = Path().apply {
                moveTo(15f, 27f); cubicTo(31f, 10f, 69f, 11f, 86f, 27f)
                cubicTo(57f, 22f, 46f, 25f, 50f, 90f)
                cubicTo(45f, 44f, 43f, 23f, 15f, 27f); close()
            },
            details = Path().apply {
                moveTo(17f, 40f); cubicTo(21f, 61f, 33f, 75f, 45f, 85f)
                moveTo(79f, 40f); cubicTo(73f, 57f, 65f, 68f, 56f, 75f)
            },
            companion = scale,
            shadow = Color(0xFF554333), color = Color(0xFFCAA649), light = Color(0xFFB5E5A4),
        ),
        "element_colorless" to ElementFoilArtwork(
            body = star,
            facet = Path().apply {
                moveTo(50f, 2f); lineTo(50f, 51f); lineTo(92f, 24f); lineTo(63f, 32f); close()
                moveTo(50f, 51f); lineTo(97f, 72f); lineTo(65f, 72f); lineTo(50f, 99f); close()
                moveTo(50f, 51f); lineTo(8f, 23f); lineTo(38f, 32f); close()
            },
            highlight = Path().apply {
                moveTo(50f, 17f); lineTo(54f, 47f); lineTo(81f, 65f)
                lineTo(49f, 56f); lineTo(19f, 66f); lineTo(45f, 47f); close()
            },
            details = Path().apply {
                moveTo(50f, 5f); lineTo(50f, 51f); lineTo(10f, 74f)
                moveTo(50f, 51f); lineTo(89f, 26f)
            },
            companion = star,
            shadow = Color(0xFF505579), color = Color(0xFFB7BED1), light = Color(0xFFE4EBF3),
        ),
        "element_fairy" to ElementFoilArtwork(
            body = petal,
            facet = Path().apply {
                moveTo(50f, 94f); cubicTo(45f, 71f, 57f, 43f, 73f, 23f)
                cubicTo(82f, 16f, 87f, 26f, 87f, 37f)
                cubicTo(88f, 55f, 70f, 77f, 50f, 94f); close()
            },
            highlight = Path().apply {
                moveTo(24f, 45f); cubicTo(17f, 23f, 31f, 8f, 40f, 25f)
                cubicTo(28f, 20f, 26f, 35f, 24f, 45f); close()
            },
            details = Path().apply {
                moveTo(47f, 98f); cubicTo(40f, 110f, 15f, 102f, 15f, 85f)
                cubicTo(12f, 71f, -1f, 67f, 1f, 83f)
                moveTo(48f, 85f); cubicTo(34f, 63f, 40f, 44f, 47f, 34f)
            },
            companion = petal,
            shadow = Color(0xFF713F6A), color = Color(0xFFE989B7), light = Color(0xFFFFCEDB),
        ),
    )
}
