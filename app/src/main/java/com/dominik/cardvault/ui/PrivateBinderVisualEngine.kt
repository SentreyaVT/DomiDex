package com.dominik.cardvault.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.dominik.cardvault.R
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * Presentation-only visual catalogue for private binders.
 *
 * IDs are stable persistence candidates, but this engine intentionally has no dependency on
 * Room, Firebase or binder entities. Unknown IDs always resolve to a safe, non-animated visual.
 */
internal object PrivateBinderVisualEngine {
    const val DEFAULT_COVER_ID = "nebula"
    const val NORMAL_HOLO_ID = "none"

    val coverThemes: List<PrivateBinderCoverTheme> = listOf(
        cover("classic_rainbow", "Classic Rainbow", "Klassischer Farbverlauf mit ruhigem Glanz.", CoverPattern.RAYS, 0xFF160918, 0xFF67256C, 0xFF2CBED0, 0xFFFFC85C),
        cover("aurora", "Aurora", "Weiche Polarlichtwellen in Violett und Cyan.", CoverPattern.WAVES, 0xFF07101C, 0xFF173C55, 0xFF47E8C2, 0xFFBB66FF),
        cover("galaxy", "Galaxy", "Tiefe Galaxie mit feinen Umlaufbahnen.", CoverPattern.ORBITS, 0xFF040511, 0xFF24105A, 0xFF6542D8, 0xFFE66DFF),
        cover("crystal", "Crystal", "Kühle, klare Kristallfacetten.", CoverPattern.FACETS, 0xFF071016, 0xFF264351, 0xFF8DDAEC, 0xFFE7FCFF),
        cover("nebula", "DomiDex Nebula", "DomiDex-Violett mit kosmischem Cyan-Akzent.", CoverPattern.ORBITS, 0xFF070913, 0xFF34105A, 0xFFB337FF, 0xFF42E8F5),
        cover("golden_shine", "Golden Shine", "Warmer Goldglanz mit geometrischer Tiefe.", CoverPattern.DIAMONDS, 0xFF100C03, 0xFF5B4310, 0xFFD6A72E, 0xFFFFED9A),
        cover("void", "Void", "Minimaler Schwarzton mit subtilen Facetten.", CoverPattern.FACETS, 0xFF020203, 0xFF0E1015, 0xFF323743, 0xFF777F8D),
        cover("prism", "Prism", "Prismatische Linien mit klaren Lichtkanten.", CoverPattern.DIAMONDS, 0xFF090515, 0xFF371A60, 0xFFB448E2, 0xFF5BE5F2),
        cover("bubble", "Bubble", "Leichte, runde Lichtbahnen mit Tiefe.", CoverPattern.ORBITS, 0xFF07101A, 0xFF17405B, 0xFF62C9F1, 0xFFD0B8FF),
        cover("flame", "Flame", "Warme Energie mit ruhigen Strahlen.", CoverPattern.RAYS, 0xFF140402, 0xFF651A0B, 0xFFFF6D24, 0xFFFFDA75),
        cover("ocean", "Ocean", "Dunkle Meereswellen und Cyanlicht.", CoverPattern.WAVES, 0xFF020B16, 0xFF07335B, 0xFF168ED0, 0xFF6DE7FF),
        cover("forest", "Forest", "Tiefe Grüntöne mit organischen Bahnen.", CoverPattern.WAVES, 0xFF03100C, 0xFF0E4938, 0xFF35DB99, 0xFFB9FFD9),
        cover("stardust", "Stardust", "Kosmischer Staub auf dunklem Violett.", CoverPattern.ORBITS, 0xFF05040E, 0xFF18113C, 0xFF5C4AC8, 0xFFEF78EE),
        cover("electric", "Electric", "Präzise Cyan-Schaltlinien mit Energiepunkt.", CoverPattern.CIRCUIT, 0xFF031016, 0xFF073A48, 0xFF18CAD9, 0xFFA5FBFF),
        cover("chroma_shift", "Chroma Shift", "Sanfter Wechsel zwischen Magenta und Cyan.", CoverPattern.WAVES, 0xFF0A0614, 0xFF42205E, 0xFFE251B4, 0xFF41E2ED),
        cover("pixel", "Pixel", "Klares Raster im modernen Retro-Stil.", CoverPattern.GRID, 0xFF090514, 0xFF28104B, 0xFFD53DDA, 0xFF42E6EF),
        cover("liquid_metal", "Liquid Metal", "Fließendes Silber auf dunklem Graphit.", CoverPattern.WAVES, 0xFF060708, 0xFF242932, 0xFF778394, 0xFFE4ECF5),
        cover("sunburst", "Sunburst", "Gebündelte Sonnenstrahlen in warmem Gold.", CoverPattern.RAYS, 0xFF140802, 0xFF63320B, 0xFFFFA51F, 0xFFFFF0A6),
        cover("dragon_scale", "Dragon Scale", "Geschichtete Rauten mit kräftigem Kontrast.", CoverPattern.DIAMONDS, 0xFF080B12, 0xFF243044, 0xFF6750A8, 0xFFB6D1E8),
        cover("cosmic_dust", "Cosmic Dust", "Feiner Weltraumglanz in tiefem Nachtblau.", CoverPattern.ORBITS, 0xFF03050D, 0xFF151D3B, 0xFF5668C9, 0xFFB869DF),
    )

    val holoEffects: List<PrivateBinderHoloEffect> = listOf(
        holo(NORMAL_HOLO_ID, "Kein Effekt", "Keine zusätzliche Holo-Überlagerung.", HoloPattern.NONE, 0x00000000, 0x00000000, 0x00000000),
        holo("classic_holo", "Classic Holo", "Klassischer, heller Karten-Schimmer.", HoloPattern.SHEEN, 0xFFFFFFFF, 0xFFB9E7FF, 0xFFE4D0FF),
        holo("rainbow", "Rainbow", "Weiche Regenbogenreflexe mit sanften Lichtflächen.", HoloPattern.SPECTRUM, 0xFFFF6688, 0xFF63E5FF, 0xFFD778FF),
        holo("linear", "Linear", "Satinierter Glanz mit breiten, weichen Lichtbändern.", HoloPattern.SHEEN, 0xFFFFFFFF, 0xFF7DDFFF, 0xFFC68CFF),
        holo("diagonal", "Diagonal", "Schräge Prismenflächen mit irisierenden Reflexen.", HoloPattern.DIAGONAL, 0xFF57E8FF, 0xFFC25CFF, 0xFFFF75B7),
        holo("galaxy", "Galaxy", "Kosmische Spiralwolken mit kleinen Sternreflexen.", HoloPattern.STARS, 0xFF7F68FF, 0xFFDD69FF, 0xFF64DFFF),
        holo("nebula", "Nebula", "Weicher, kosmischer Farbnebel.", HoloPattern.WAVE, 0xFF746BFF, 0xFFDD69FF, 0xFF58DDEB),
        holo("aurora", "Aurora", "Fließendes Polarlicht in Grün und Violett.", HoloPattern.WAVE, 0xFF69F7C5, 0xFF67B8FF, 0xFFE879FF),
        holo("crystal", "Crystal", "Kühle transparente Kristallflächen.", HoloPattern.FACETS, 0xFF9FF8FF, 0xFFC5B4FF, 0xFFFFFFFF),
        holo("prism", "Prism", "Gefächerte Prismenflächen mit gebrochenem Farblicht.", HoloPattern.FACETS, 0xFF57E8FF, 0xFFD15DFF, 0xFFFFD06B),
        holo("diamond", "Diamond", "Klarer, facettierter Diamantglanz.", HoloPattern.FACETS, 0xFFFFFFFF, 0xFF8FDFFF, 0xFFD9C5FF),
        holo("stardust", "Stardust", "Dezent verteilte Lichtpartikel.", HoloPattern.STARS, 0xFFFFFFFF, 0xFFA7C7FF, 0xFFD7A5FF),
        holo("bubble", "Bubble", "Runde, luftige Lichtpunkte.", HoloPattern.CONFETTI, 0xFFA7F5FF, 0xFFD0B5FF, 0xFFFFFFFF),
        holo("oil_slick", "Oil Slick", "Organische Farbinseln mit irisierenden Lichtreflexen.", HoloPattern.SPECTRUM, 0xFF4BE2C5, 0xFF6D55CE, 0xFFEF5FAE),
        holo("liquid_metal", "Liquid Metal", "Flüssige Chromformen mit farbigen Spiegelreflexen.", HoloPattern.SHEEN, 0xFFFFFFFF, 0xFF8593A8, 0xFFDCE5EF),
        holo("hyper_wave", "Hyper Wave", "Kräftigere, gebündelte Energiewellen.", HoloPattern.WAVE, 0xFF66FFF0, 0xFF8272FF, 0xFFFF7BD4),
        holo("fire", "Fire", "Einzelne Flammenzungen und schimmernde Glutpartikel.", HoloPattern.DIAGONAL, 0xFFFFB14C, 0xFFFF5D4F, 0xFFFFE7A1),
        holo("ice", "Ice", "Gefüllte Eiskristalle mit kühler Lichtbrechung.", HoloPattern.FACETS, 0xFFFFFFFF, 0xFF70DBFF, 0xFFA5BFFF),
        holo("pixel", "Pixel", "Kompakte digitale Lichtpunkte.", HoloPattern.CONFETTI, 0xFF5CEBFF, 0xFFB66AFF, 0xFFFFFFFF),
        holo("void", "Void", "Dunkle Finsternis-Motive mit irisierender Korona.", HoloPattern.SHEEN, 0xFF3B4252, 0xFF705A96, 0xFF8BA4B8),
        holo("holo_spark", "Holo Spark", "Gezielte helle Energieblitze.", HoloPattern.BURST, 0xFFFFFFFF, 0xFF9F72FF, 0xFF5BE7FF),
        holo("element_grass", "Element · Pflanze", "Blätter, grüne Lichtadern und Naturfunken.", HoloPattern.WAVE, 0xFF57F28E, 0xFFB8FF66, 0xFFFFFFFF),
        holo("element_fire", "Element · Feuer", "Klare Flammenmotive mit leuchtenden Innenflächen.", HoloPattern.DIAGONAL, 0xFFFF4C32, 0xFFFFB21F, 0xFFFFF0A3),
        holo("element_water", "Element · Wasser", "Wellen, Tropfen und klare Cyan-Reflexe.", HoloPattern.WAVE, 0xFF38D9FF, 0xFF287CFF, 0xFFFFFFFF),
        holo("element_electric", "Element · Elektro", "Einzelne gefüllte Blitze mit farbigen Lichtreflexen.", HoloPattern.BURST, 0xFFFFF26A, 0xFF55E9FF, 0xFFFFFFFF),
        holo("element_psychic", "Element · Psycho", "Mystische Augenmotive mit prismatischer Aura.", HoloPattern.WAVE, 0xFFC85CFF, 0xFF6E62FF, 0xFFFFA7F2),
        holo("element_fighting", "Element · Kampf", "Kraftvolle Einschlagringe und glühende Bruchlinien.", HoloPattern.BURST, 0xFFFF8B38, 0xFFE33F2F, 0xFFFFD67A),
        holo("element_darkness", "Element · Finsternis", "Tiefe Indigo-Bögen mit dunkler Energie.", HoloPattern.SHEEN, 0xFF24113F, 0xFF7B3EC8, 0xFFFF527D),
        holo("element_metal", "Element · Metall", "Chromfacetten und kühle Stahlreflexe.", HoloPattern.FACETS, 0xFFFFFFFF, 0xFFAEC5DB, 0xFFFFD9A6),
        holo("element_dragon", "Element · Drache", "Prismatische Schuppen und kräftige Energiespuren.", HoloPattern.FACETS, 0xFF44E4D5, 0xFFFFC857, 0xFFB65CFF),
        holo("element_colorless", "Element · Farblos", "Perlmuttsterne und neutrale Silberringe.", HoloPattern.STARS, 0xFFFFFFFF, 0xFFD9E6F2, 0xFFFFE4B8),
        holo("element_fairy", "Element · Fee", "Pastellfunken und sanfter magischer Glitzer.", HoloPattern.CONFETTI, 0xFFFF8FDF, 0xFF8FE9FF, 0xFFFFFFFF),
        // Append only: the existing twenty core and eleven element atlas cells stay unchanged.
        holo("anniversary_30", "30 Jahre · Prismengold", "Irisierende Jubiläumsblasen, 30-Symbole, Pokéballringe und eine goldene Plakette. Schimmert nur mit deiner Kartenbewegung.", HoloPattern.ANNIVERSARY, 0xFFFFC95C, 0xFFFFE6A2, 0xFFC48DFF),
    )

    const val CORE_HOLO_MATERIAL_COUNT = 20

    fun coverTheme(id: String?): PrivateBinderCoverTheme =
        coverThemes.firstOrNull { it.id == normalizeCoverId(id) }
            ?: coverThemes.first { it.id == DEFAULT_COVER_ID }

    fun holoEffect(id: String?): PrivateBinderHoloEffect =
        holoEffects.firstOrNull { it.id == normalizeHoloId(id) }
            ?: holoEffects.first { it.id == NORMAL_HOLO_ID }

    fun renderPlan(preferences: PrivateBinderVisualPreferences): PrivateBinderRenderPlan {
        val intensity = preferences.intensity.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val animate = !preferences.reduceMotion && intensity > 0f
        return PrivateBinderRenderPlan(
            intensity = intensity,
            animate = animate,
            primitiveBudget = preferences.quality.primitiveBudget,
            durationMillis = preferences.quality.durationMillis,
            maxTiltDegrees = if (preferences.reduceMotion) 0f else preferences.maxTiltDegrees
                .takeIf(Float::isFinite)?.coerceIn(0f, MAX_TILT_DEGREES) ?: 0f,
        )
    }

    private fun normalizeCoverId(id: String?): String? = when (val normalized = id?.trim()?.lowercase()) {
        "domidex", "default", "domidex_nebula" -> DEFAULT_COVER_ID
        else -> normalized
    }

    private fun normalizeHoloId(id: String?): String? = when (val normalized = id?.trim()?.lowercase()) {
        "normal" -> NORMAL_HOLO_ID
        else -> normalized
    }

    private fun cover(id: String, name: String, description: String, pattern: CoverPattern, vararg colors: Long) =
        PrivateBinderCoverTheme(id, name, description, pattern, colors.map(::Color))

    private fun holo(id: String, name: String, description: String, pattern: HoloPattern, vararg colors: Long) =
        PrivateBinderHoloEffect(id, name, description, pattern, colors.map(::Color))

    const val MAX_TILT_DEGREES = 8f
}

internal enum class PrivateBinderVisualQuality(
    val primitiveBudget: Int,
    val durationMillis: Int,
) {
    HIGH(10, 3_200),
    MEDIUM(7, 3_800),
    LOW(3, 4_600),
}

internal data class PrivateBinderVisualPreferences(
    val intensity: Float = .68f,
    val quality: PrivateBinderVisualQuality = PrivateBinderVisualQuality.MEDIUM,
    val reduceMotion: Boolean = false,
    val maxTiltDegrees: Float = 7f,
)

internal data class PrivateBinderRenderPlan(
    val intensity: Float,
    val animate: Boolean,
    val primitiveBudget: Int,
    val durationMillis: Int,
    val maxTiltDegrees: Float,
)

internal data class PrivateBinderCoverTheme(
    val id: String,
    val displayName: String,
    val description: String,
    val pattern: CoverPattern,
    val colors: List<Color>,
)

internal data class PrivateBinderHoloEffect(
    val id: String,
    val displayName: String,
    val description: String,
    val pattern: HoloPattern,
    val colors: List<Color>,
)

internal enum class CoverPattern { ORBITS, FACETS, CIRCUIT, WAVES, RAYS, DIAMONDS, GRID }
internal enum class HoloPattern { NONE, SHEEN, SPECTRUM, DIAGONAL, WAVE, STARS, FACETS, BURST, CONFETTI, ANNIVERSARY }

internal data class PrivateBinderHoloAtlasCell(val elementAtlas: Boolean, val index: Int) {
    val columns: Int get() = if (elementAtlas) 4 else 5
    val rows: Int get() = if (elementAtlas) 3 else 4
}

/** New procedural materials never borrow an unrelated/empty atlas cell. */
internal fun privateBinderHoloAtlasCell(effectId: String): PrivateBinderHoloAtlasCell? {
    if (effectId == "anniversary_30" || effectId == PrivateBinderVisualEngine.NORMAL_HOLO_ID) return null
    val index = PrivateBinderVisualEngine.holoEffects.indexOfFirst { it.id == effectId } - 1
    if (index !in 0 until PrivateBinderVisualEngine.CORE_HOLO_MATERIAL_COUNT + 11) return null
    val element = index >= PrivateBinderVisualEngine.CORE_HOLO_MATERIAL_COUNT
    return PrivateBinderHoloAtlasCell(element, if (element) index - PrivateBinderVisualEngine.CORE_HOLO_MATERIAL_COUNT else index)
}

private val RainbowFoilPalette = listOf(
    Color(0xFFFF315F),
    Color(0xFFFFCA28),
    Color(0xFF36EF8D),
    Color(0xFF20DFFF),
    Color(0xFF6366FF),
    Color(0xFFE348FF),
)

internal data class PrivateBinderTilt(val rotationX: Float, val rotationY: Float)

/** Presentation-only optics. No clock, sensor, random seed, or persisted state. */
internal data class PrivateBinderFoilOptics(
    val materialAlpha: Float,
    val grooveAlpha: Float,
    val specularAlpha: Float,
    val normalizedTiltX: Float,
    val normalizedTiltY: Float,
    val reflectionCenterX: Float,
    val hueShift: Float,
)

internal fun privateBinderFoilOptics(intensity: Float, tilt: PrivateBinderTilt): PrivateBinderFoilOptics {
    val strength = intensity.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val squared = strength * strength
    fun normalize(angle: Float): Float =
        (angle.takeIf(Float::isFinite) ?: 0f).coerceIn(
            -PrivateBinderVisualEngine.MAX_TILT_DEGREES,
            PrivateBinderVisualEngine.MAX_TILT_DEGREES,
        ) / PrivateBinderVisualEngine.MAX_TILT_DEGREES
    val x = normalize(tilt.rotationX)
    val y = normalize(tilt.rotationY)
    return PrivateBinderFoilOptics(
        // Keep the reference material subtle. Intensity mainly strengthens the fine optics,
        // not an opaque texture over the artwork (especially important for bright cards).
        materialAlpha = .09f * strength + .13f * squared,
        grooveAlpha = .48f * strength + .44f * squared,
        specularAlpha = .13f * strength + .30f * squared,
        normalizedTiltX = x,
        normalizedTiltY = y,
        reflectionCenterX = .48f + y * .28f - x * .08f,
        hueShift = y * 2.80f + x * 1.30f,
    )
}

@Stable
internal class PrivateBinderTiltState internal constructor() {
    internal var targetRotationX by mutableFloatStateOf(0f)
        private set
    internal var targetRotationY by mutableFloatStateOf(0f)
        private set
    internal var pressed by mutableStateOf(false)
        private set
    private var animatedX: State<Float> = mutableFloatStateOf(0f)
    private var animatedY: State<Float> = mutableFloatStateOf(0f)
    private var sharedMotionPhase: State<Float> = mutableFloatStateOf(.5f)

    val rotationX: Float get() = animatedX.value
    val rotationY: Float get() = animatedY.value
    val motionPhase: Float get() = sharedMotionPhase.value

    internal fun moveTo(tilt: PrivateBinderTilt) {
        pressed = true
        targetRotationX = tilt.rotationX
        targetRotationY = tilt.rotationY
    }

    internal fun reset() {
        pressed = false
        targetRotationX = 0f
        targetRotationY = 0f
    }

    internal fun bind(animatedX: State<Float>, animatedY: State<Float>, motionPhase: State<Float>) {
        this.animatedX = animatedX
        this.animatedY = animatedY
        this.sharedMotionPhase = motionPhase
    }
}

@Composable
internal fun rememberPrivateBinderTiltState(): PrivateBinderTiltState = remember { PrivateBinderTiltState() }

internal fun privateBinderTouchTilt(
    position: Offset,
    size: IntSize,
    maxTiltDegrees: Float,
): PrivateBinderTilt {
    if (size.width <= 0 || size.height <= 0 || !maxTiltDegrees.isFinite() || maxTiltDegrees <= 0f) {
        return PrivateBinderTilt(0f, 0f)
    }
    val limited = maxTiltDegrees.coerceAtMost(PrivateBinderVisualEngine.MAX_TILT_DEGREES)
    val horizontal = ((position.x / size.width) * 2f - 1f).coerceIn(-1f, 1f)
    val vertical = ((position.y / size.height) * 2f - 1f).coerceIn(-1f, 1f)
    return PrivateBinderTilt(
        rotationX = if (vertical == 0f) 0f else -vertical * limited,
        rotationY = if (horizontal == 0f) 0f else horizontal * limited,
    )
}

/** Small, bounded pointer tilt with a spring return. Reduced-motion callers pass enabled=false. */
internal fun Modifier.privateBinderTouchTilt(
    state: PrivateBinderTiltState,
    enabled: Boolean = true,
    maxTiltDegrees: Float = 3.5f,
    idleMotionEnabled: Boolean = false,
): Modifier = composed {
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }
    val activeSpec = when {
        !enabled || maxTiltDegrees <= 0f -> snap<Float>()
        state.pressed -> tween(durationMillis = 55)
        else -> spring(dampingRatio = .72f, stiffness = 360f)
    }
    val animatedX = animateFloatAsState(state.targetRotationX, activeSpec, label = "private-binder-tilt-x")
    val animatedY = animateFloatAsState(state.targetRotationY, activeSpec, label = "private-binder-tilt-y")
    val idlePhase: State<Float>
    if (enabled && idleMotionEnabled && maxTiltDegrees > 0f) {
        val idleTransition = rememberInfiniteTransition(label = "private-binder-idle-card-motion")
        idlePhase = idleTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(3_800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "private-binder-shared-motion-phase",
        )
    } else {
        idlePhase = remember { mutableFloatStateOf(.5f) }
    }
    val renderedX = remember(state, animatedX, idlePhase) {
        derivedStateOf { animatedX.value + if (state.pressed) 0f else (idlePhase.value - .5f) * 1.50f }
    }
    val renderedY = remember(state, animatedY, idlePhase) {
        derivedStateOf { animatedY.value + if (state.pressed) 0f else (idlePhase.value - .5f) * 3.00f }
    }
    state.bind(renderedX, renderedY, idlePhase)
    LaunchedEffect(enabled, maxTiltDegrees) {
        if (!enabled || maxTiltDegrees <= 0f) state.reset()
    }
    this
        .onSizeChanged { measuredSize = it }
        .then(
            if (!enabled || maxTiltDegrees <= 0f) Modifier
            else Modifier.pointerInput(state, maxTiltDegrees) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    state.moveTo(privateBinderTouchTilt(down.position, measuredSize, maxTiltDegrees))
                    do {
                        val event = awaitPointerEvent()
                        event.changes.firstOrNull { it.pressed }?.let { active ->
                            state.moveTo(privateBinderTouchTilt(active.position, measuredSize, maxTiltDegrees))
                        }
                    } while (event.changes.any { it.pressed })
                    state.reset()
                }
            }
        )
        .graphicsLayer {
            rotationX = state.rotationX
            rotationY = state.rotationY
            cameraDistance = 24f * density
            // Keep the card image and Screen-blended foil in one GPU layer. Without this,
            // some Android renderers blend the foil against transparency instead of the card.
            compositingStrategy = CompositingStrategy.Offscreen
        }
}

/** Convenience overload for a self-contained tilted surface. */
internal fun Modifier.privateBinderTouchTilt(
    enabled: Boolean = true,
    maxTiltDegrees: Float = 3.5f,
    idleMotionEnabled: Boolean = false,
): Modifier = composed {
    privateBinderTouchTilt(rememberPrivateBinderTiltState(), enabled, maxTiltDegrees, idleMotionEnabled)
}

@Composable
internal fun PrivateBinderCoverVisual(
    themeId: String?,
    modifier: Modifier = Modifier,
    preferences: PrivateBinderVisualPreferences = PrivateBinderVisualPreferences(),
    tiltState: PrivateBinderTiltState? = null,
    touchTiltEnabled: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val theme = PrivateBinderVisualEngine.coverTheme(themeId)
    val atlas = ImageBitmap.imageResource(R.drawable.private_binder_cover_atlas)
    val themeIndex = remember(theme.id) {
        PrivateBinderVisualEngine.coverThemes.indexOfFirst { it.id == theme.id }.coerceAtLeast(0)
    }
    val plan = PrivateBinderVisualEngine.renderPlan(preferences)
    val phase = rememberPrivateBinderVisualPhase(plan)
    val localTiltState = rememberPrivateBinderTiltState()
    val effectiveTiltState = tiltState ?: localTiltState
    Box(
        modifier.privateBinderTouchTilt(
            state = effectiveTiltState,
            enabled = touchTiltEnabled && plan.maxTiltDegrees > 0f,
            maxTiltDegrees = plan.maxTiltDegrees,
        ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val motion = (phase - .5f) * .08f
            val columns = 5
            val rows = 4
            val cellWidth = atlas.width / columns
            val cellHeight = atlas.height / rows
            val column = themeIndex % columns
            val row = themeIndex / columns
            // The generated atlas has deliberately thin black gutters. Crop a small inset so
            // every preview and real binder uses only the corresponding material surface.
            val sourceInset = (min(cellWidth, cellHeight) * .025f).roundToInt().coerceAtLeast(2)
            drawImage(
                image = atlas,
                srcOffset = IntOffset(column * cellWidth + sourceInset, row * cellHeight + sourceInset),
                srcSize = IntSize(cellWidth - sourceInset * 2, cellHeight - sourceInset * 2),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)),
            )
            // A restrained moving reflection keeps the material alive without replacing the
            // theme artwork. It is intentionally slow and disappears when motion is reduced.
            val sheenCenter = size.width * (.22f + phase * .56f)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = .10f * plan.intensity), Color.Transparent),
                    start = Offset(sheenCenter - size.width * .32f, size.height),
                    end = Offset(sheenCenter + size.width * .18f, 0f),
                ),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(theme.colors.last().copy(alpha = .18f * plan.intensity), Color.Transparent),
                    center = Offset(size.width * (.72f + motion), size.height * (.24f - motion)),
                    radius = size.minDimension * .68f,
                ),
                radius = size.minDimension * .68f,
                center = Offset(size.width * (.72f + motion), size.height * (.24f - motion)),
            )
            // Binder shell: visible spine, layered edge and soft vignette. Keeping this inside
            // the shared renderer makes the chooser a true preview of the actual binder.
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = .78f),
                    .38f to Color.Black.copy(alpha = .34f),
                    1f to Color.Transparent,
                    endX = size.width * .16f,
                ),
                size = androidx.compose.ui.geometry.Size(size.width * .16f, size.height),
            )
            drawLine(Color.White.copy(alpha = .30f), Offset(size.width * .035f, 0f), Offset(size.width * .035f, size.height), strokeWidth = (size.width * .010f).coerceAtLeast(1f))
            drawLine(Color.Black.copy(alpha = .62f), Offset(size.width * .105f, 0f), Offset(size.width * .105f, size.height), strokeWidth = (size.width * .015f).coerceAtLeast(1f))
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = .30f), Color.Black.copy(alpha = .32f)),
                    startX = size.width * .91f,
                    endX = size.width,
                ),
                topLeft = Offset(size.width * .91f, 0f),
                size = androidx.compose.ui.geometry.Size(size.width * .09f, size.height),
            )
            drawRect(
                brush = Brush.radialGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = .34f)),
                    center = center,
                    radius = size.maxDimension * .72f,
                ),
            )
        }
        content()
    }
}

@Composable
internal fun PrivateBinderHoloLayer(
    effectId: String?,
    modifier: Modifier = Modifier,
    preferences: PrivateBinderVisualPreferences = PrivateBinderVisualPreferences(),
    tiltState: PrivateBinderTiltState? = null,
) {
    val effect = PrivateBinderVisualEngine.holoEffect(effectId)
    if (effect.pattern == HoloPattern.NONE) return
    val material = remember(effect.id) {
        if (privateBinderUsesPremiumFoil(effect.id) || privateBinderUsesElementFoil(effect.id) ||
            privateBinderUsesAdditionalFoil(effect.id)) null else privateBinderHoloAtlasCell(effect.id)
    }
    val atlas = if (material == null) null else ImageBitmap.imageResource(
        if (material.elementAtlas) R.drawable.private_binder_element_holo_atlas
        else R.drawable.private_binder_holo_atlas,
    )
    val plan = PrivateBinderVisualEngine.renderPlan(preferences)
    if (plan.intensity <= 0f) return
    // Read exactly the rendered angles used by the card's graphicsLayer, including its
    // release spring. Foil never consumes an animation phase or runs a clock of its own.
    val tilt = PrivateBinderTilt(
        rotationX = tiltState?.rotationX ?: 0f,
        rotationY = tiltState?.rotationY ?: 0f,
    )
    Canvas(modifier) {
        drawHoloPattern(effect, plan, tilt, atlas, material)
    }
}

@Composable
private fun rememberPrivateBinderVisualPhase(plan: PrivateBinderRenderPlan): Float {
    if (!plan.animate) return .5f
    val transition = rememberInfiniteTransition(label = "private-binder-visual")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(plan.durationMillis),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "private-binder-visual-phase",
    )
    return phase
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCoverPattern(
    theme: PrivateBinderCoverTheme,
    plan: PrivateBinderRenderPlan,
    phase: Float,
) {
    val accent = theme.colors.last().copy(alpha = .08f + .10f * plan.intensity)
    val secondary = theme.colors.getOrElse(theme.colors.lastIndex - 1) { theme.colors.last() }.copy(alpha = .06f + .07f * plan.intensity)
    val stroke = (size.minDimension * .004f).coerceAtLeast(1f)
    when (theme.pattern) {
        CoverPattern.ORBITS -> repeat(min(3, plan.primitiveBudget)) { index ->
            val radius = size.minDimension * (.18f + index * .13f + phase * .008f)
            drawCircle(accent, radius, Offset(size.width * .52f, size.height * .46f), style = Stroke(stroke))
        }
        CoverPattern.GRID, CoverPattern.CIRCUIT -> {
            repeat(min(plan.primitiveBudget, 8)) { index ->
                val fraction = (index + 1f) / (min(plan.primitiveBudget, 8) + 1f)
                drawLine(accent, Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height), stroke)
                if (index % 2 == 0) drawLine(secondary, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction), stroke)
            }
            if (theme.pattern == CoverPattern.CIRCUIT) {
                repeat(min(4, plan.primitiveBudget)) { index ->
                    val x = size.width * (.18f + index * .18f)
                    val y = size.height * (.28f + (index % 2) * .38f)
                    drawCircle(secondary, stroke * 2.2f, Offset(x, y))
                }
            }
        }
        CoverPattern.RAYS -> repeat(min(9, plan.primitiveBudget)) { index ->
            val edgeX = size.width * index / min(8f, plan.primitiveBudget.toFloat())
            drawLine(accent, center, Offset(edgeX, if (index % 2 == 0) 0f else size.height), stroke, StrokeCap.Round)
        }
        CoverPattern.DIAMONDS, CoverPattern.FACETS -> repeat(min(4, plan.primitiveBudget)) { index ->
            val insetX = size.width * (.10f + index * .07f)
            val insetY = size.height * (.10f + index * .07f)
            val path = Path().apply {
                moveTo(size.width / 2f, insetY)
                lineTo(size.width - insetX, size.height / 2f)
                lineTo(size.width / 2f, size.height - insetY)
                lineTo(insetX, size.height / 2f)
                close()
            }
            drawPath(path, if (index % 2 == 0) accent else secondary, style = Stroke(stroke))
        }
        CoverPattern.WAVES -> repeat(min(4, plan.primitiveBudget)) { index ->
            val base = size.height * (.18f + index * .20f)
            val amplitude = size.height * (.025f + phase * .01f)
            val path = Path().apply {
                moveTo(0f, base)
                cubicTo(size.width * .25f, base - amplitude, size.width * .75f, base + amplitude, size.width, base)
            }
            drawPath(path, if (index % 2 == 0) accent else secondary, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHoloPattern(
    effect: PrivateBinderHoloEffect,
    plan: PrivateBinderRenderPlan,
    tilt: PrivateBinderTilt,
    atlas: ImageBitmap?,
    material: PrivateBinderHoloAtlasCell?,
) {
    val optics = privateBinderFoilOptics(plan.intensity, tilt)
    if (privateBinderUsesElementFoil(effect.id)) {
        drawPrivateBinderElementFoil(effect.id, plan, optics)
        return
    }
    if (privateBinderUsesAdditionalFoil(effect.id)) {
        drawPrivateBinderAdditionalFoil(effect.id, plan, optics)
        return
    }
    if (privateBinderUsesPremiumFoil(effect.id)) {
        drawPrivateBinderPremiumFoil(effect.id, plan, optics)
        return
    }
    val tiltTravel = optics.normalizedTiltY * size.width * .16f
    val verticalTravel = optics.normalizedTiltX * size.height * .08f

    // Each effect owns a genuinely different light-map from the reference-driven atlas.
    // Pure black in the source is neutral under Screen blending, so card artwork and text
    // remain readable while only the luminous foil structure appears above the card.
    if (atlas != null && material != null) {
    val cellWidth = atlas.width / material.columns
    val cellHeight = atlas.height / material.rows
    val column = material.index % material.columns
    val row = material.index / material.columns
    val sourceInset = (min(cellWidth, cellHeight) * .025f).roundToInt().coerceAtLeast(2)
    // The atlas is deliberately rendered beyond every edge. This margin covers the complete
    // strengthened 3D tilt range, so no transparent seam can enter at an outer card corner.
    val overscan = size.minDimension * .16f
    val materialShiftX = tiltTravel * .60f
    val materialShiftY = verticalTravel * .62f
    drawImage(
        image = atlas,
        srcOffset = IntOffset(column * cellWidth + sourceInset, row * cellHeight + sourceInset),
        srcSize = IntSize(cellWidth - sourceInset * 2, cellHeight - sourceInset * 2),
        dstOffset = IntOffset(
            (-overscan + materialShiftX).roundToInt(),
            (-overscan + materialShiftY).roundToInt(),
        ),
        dstSize = IntSize(
            (size.width + overscan * 2f).roundToInt().coerceAtLeast(1),
            (size.height + overscan * 2f).roundToInt().coerceAtLeast(1),
        ),
        // Cut-crystal atlases contain bright faces as well as edges. Attenuate that dense
        // base independently; their saturated edge/groove highlights keep full strength.
        alpha = optics.materialAlpha * if (effect.pattern == HoloPattern.FACETS) .75f else 1f,
        blendMode = BlendMode.Screen,
    )
    }

    drawHoloMaterialGeometry(effect, plan, optics)
}

/**
 * Every material owns its optical structure. There is deliberately no universal rainbow
 * stripe layer: leaves, lightning, scales, stars and metal must stay visually distinct.
 * A broad spectral brush is clipped to each material's own geometry, never a full-card wash.
 * Its endpoints always span the card; no narrow full-surface gradient clamp edges are drawn.
 * All paths have a fixed segment count and are bounded by the existing quality budget.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHoloMaterialGeometry(
    effect: PrivateBinderHoloEffect,
    plan: PrivateBinderRenderPlan,
    optics: PrivateBinderFoilOptics,
) {
    val count = plan.primitiveBudget
    val stroke = (size.minDimension * .006f).coerceIn(1f, 3.2f)
    val shiftX = optics.normalizedTiltY * .035f
    val shiftY = optics.normalizedTiltX * .035f
    fun point(x: Float, y: Float) = Offset((x + shiftX) * size.width, (y + shiftY) * size.height)
    fun Path.move(x: Float, y: Float) { val p = point(x, y); moveTo(p.x, p.y) }
    fun Path.line(x: Float, y: Float) { val p = point(x, y); lineTo(p.x, p.y) }
    fun Path.curve(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        val a = point(x1, y1); val b = point(x2, y2); val c = point(x3, y3)
        cubicTo(a.x, a.y, b.x, b.y, c.x, c.y)
    }
    // The motif stays unique, but every foil edge refracts the complete spectrum. A solid
    // color per path cannot show rainbow shimmer along a leaf, wave or crystal edge.
    // Both color and the traveling glint depend only on the card's rendered tilt.
    val spectralBrush = Brush.linearGradient(
        colors = List(25) { sample ->
            val fraction = sample / 24f
            val spectrum = rainbowFoilColor(fraction * 6f + optics.hueShift)
            val material = effect.colors[(sample / 8).coerceAtMost(effect.colors.lastIndex)]
            val glint = (1f - abs(fraction - optics.reflectionCenterX) * 2.8f).coerceIn(0f, 1f)
            lerp(spectrum, material, .12f).copy(alpha = .72f + glint * .28f)
        },
        start = Offset(-size.width * .10f, -size.height * .10f),
        end = Offset(size.width * 1.10f, size.height * 1.10f),
    )
    fun trace(path: Path, index: Int, weight: Float = 1f, opacity: Float = 1f) {
        val lineWidth = stroke * weight
        val glint = (1f - abs((index + .5f) / count - optics.reflectionCenterX) * 2f).coerceIn(0f, 1f)
        val alpha = optics.grooveAlpha * opacity * (.90f + glint * .10f)
        // Feather only the foil's own contours, never blur the card. Rounded joins avoid
        // sharp miter spikes on facets. Graduated shoulders replace the hard bright wire
        // without lowering the total colored light or blurring any of the actual artwork.
        drawPath(path, spectralBrush, alpha = alpha * .03f,
            style = Stroke(lineWidth * 4.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, spectralBrush, alpha = alpha * .10f,
            style = Stroke(lineWidth * 3.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, spectralBrush, alpha = alpha * .22f,
            style = Stroke(lineWidth * 2.25f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, spectralBrush, alpha = alpha * .37f,
            style = Stroke(lineWidth * 1.35f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, spectralBrush, alpha = alpha * .63f,
            style = Stroke(lineWidth * .60f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    fun flake(path: Path) {
        drawPath(path, spectralBrush, alpha = optics.grooveAlpha * .05f,
            style = Stroke(stroke * 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, spectralBrush, alpha = optics.grooveAlpha * .16f,
            style = Stroke(stroke * 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, spectralBrush, alpha = optics.grooveAlpha * .84f)
    }
    fun sparkle(path: Path, x: Float, y: Float, radius: Float, cross: Boolean = false) {
        if (cross) {
            path.move(x - radius, y); path.line(x + radius, y)
            path.move(x, y - radius * .8f); path.line(x, y + radius * .8f)
        } else {
            path.move(x, y - radius); path.line(x + radius * .22f, y - radius * .16f)
            path.line(x + radius, y); path.line(x + radius * .22f, y + radius * .16f)
            path.line(x, y + radius); path.line(x - radius * .22f, y + radius * .16f)
            path.line(x - radius, y); path.line(x - radius * .22f, y - radius * .16f)
            path.close()
        }
    }
    if (effect.id == "anniversary_30") {
        drawPrivateBinderAnniversaryFoil(plan, optics)
        return
    }
    repeat(count) { index ->
        val t = (index + .5f) / count
        val scatteredX = .10f + ((index * 37) % 89) / 111f
        val scatteredY = .10f + ((index * 53) % 83) / 104f
        val path = Path()
        when (effect.id) {
            "classic_holo" -> {
                // Small, irregular foil flakes; not a full-card diffraction stripe.
                repeat(3) { flake ->
                    val x = wrapPrivateBinderVisualCoordinate(scatteredX + flake * .19f, .92f) + .03f
                    val y = wrapPrivateBinderVisualCoordinate(scatteredY + flake * .23f, .92f) + .03f
                    sparkle(path, x, y, .010f + (index % 3) * .003f)
                }
                flake(path)
            }
            "rainbow" -> {
                // Bowed spectral diffraction arcs, unlike linear or diagonal etching.
                val y = .04f + t * .95f
                path.move(-.08f, y + .16f)
                path.curve(.20f, y - .18f, .70f, y - .18f, 1.08f, y + .10f)
                trace(path, index, 1.5f)
            }
            "linear" -> {
                val y = -.04f + t * 1.12f
                repeat(3) { line ->
                    val offset = line * .009f
                    path.move(-.06f, y + .09f + offset)
                    path.line(1.06f, y - .09f + offset)
                }
                trace(path, index, .70f)
            }
            "diagonal" -> {
                val x = -.35f + t * 1.70f
                path.move(x - .34f, 1.06f); path.line(x + .34f, -.06f)
                path.move(x - .31f, 1.06f); path.line(x + .37f, -.06f)
                trace(path, index, 1.15f)
            }
            "galaxy" -> {
                // Spiral-arm constellations; star dust below is dispersed instead.
                val angle = index * 2.39996f + optics.hueShift * .10f
                val radius = .10f + t * .40f
                val x = .50f + cos(angle) * radius
                val y = .47f + sin(angle) * radius * .75f
                path.move(.50f + cos(angle - .65f) * radius * .60f, .47f + sin(angle - .65f) * radius * .45f)
                path.curve(.50f + cos(angle - .30f) * radius, .47f + sin(angle - .30f) * radius * .75f, x, y, x + .008f, y)
                sparkle(path, x, y, .017f, cross = true)
                trace(path, index, .85f)
            }
            "nebula" -> {
                val y = .08f + t * .84f
                path.move(.05f, y + .08f)
                path.curve(.45f, y - .19f, .15f, y + .17f, .54f, y)
                path.curve(.90f, y - .20f, .63f, y + .15f, .96f, y - .06f)
                trace(path, index, 1.10f)
            }
            "aurora" -> {
                val x = .04f + t * .92f
                path.move(x - .07f, -.03f)
                path.curve(x + .20f, .20f, x - .22f, .49f, x + .02f, .77f)
                path.curve(x + .10f, .90f, x + .05f, .99f, x - .01f, 1.03f)
                trace(path, index, 1.4f)
            }
            "hyper_wave" -> {
                val y = -.04f + t * 1.12f
                path.move(-.07f, y)
                path.curve(.28f, y + .24f, .64f, y - .24f, 1.07f, y)
                path.move(-.07f, y + .022f)
                path.curve(.28f, y + .262f, .64f, y - .218f, 1.07f, y + .022f)
                trace(path, index, 1.25f)
            }
            "crystal" -> {
                val x = .04f + (index % 3) * .34f
                val y = .06f + (index / 3) * .29f
                path.move(x, y + .14f); path.line(x + .20f, y)
                path.line(x + .34f, y + .25f); path.line(x, y + .14f)
                path.line(x + .06f, y + .33f); path.line(x + .34f, y + .25f)
                trace(path, index, .95f)
            }
            "prism" -> {
                val x = .04f + t * .92f
                path.move(optics.reflectionCenterX, .43f)
                path.line(x - .17f, 1.02f); path.line(x + .15f, 1.02f)
                path.close()
                trace(path, index, 1.15f)
            }
            "diamond" -> {
                val x = .22f + (index % 2) * .52f
                val y = .08f + (index / 2) * .24f
                path.move(x, y); path.line(x + .18f, y + .11f)
                path.line(x, y + .23f); path.line(x - .18f, y + .11f); path.close()
                path.move(x - .18f, y + .11f); path.line(x + .18f, y + .11f)
                path.move(x, y); path.line(x, y + .23f)
                trace(path, index, 1.05f)
            }
            "stardust" -> {
                sparkle(path, scatteredX, scatteredY, .025f + (index % 3) * .009f)
                sparkle(path, 1f - scatteredX, wrapPrivateBinderVisualCoordinate(scatteredY + .37f, .85f) + .06f, .009f)
                flake(path)
            }
            "bubble" -> {
                val radius = .03f + (index % 4) * .013f
                val bubbleCenter = point(scatteredX, scatteredY)
                val bubbleRadius = size.width * radius
                val ring = Path().apply { addOval(Rect(bubbleCenter, bubbleRadius)) }
                trace(ring, index)
                path.move(scatteredX - radius * .50f, scatteredY - radius * .45f)
                path.curve(scatteredX - radius * .10f, scatteredY - radius * .70f, scatteredX + radius * .20f, scatteredY - radius * .70f, scatteredX + radius * .45f, scatteredY - radius * .42f)
                trace(path, index + 1, .75f)
            }
            "oil_slick" -> {
                val r = .05f + t * .43f
                path.move(.51f - r, .43f)
                path.curve(.25f - r * .40f, .05f, .95f, .28f + r, .55f + r, .52f)
                path.curve(.78f + r * .40f, 1.01f, -.05f, .76f - r, .51f - r, .43f)
                trace(path, index, 1.35f)
            }
            "liquid_metal" -> {
                val x = -.10f + t * 1.20f
                path.move(x, -.04f)
                path.curve(x + .65f, .18f, x - .63f, .58f, x + .10f, 1.04f)
                trace(path, index, if (index % 3 == 0) 1.8f else .75f)
            }
            "fire", "element_fire" -> {
                val x = .08f + t * .84f
                val tip = .16f + (index % 3) * .14f
                path.move(x - .09f, .97f)
                path.curve(x - .20f, .70f, x + .15f, .61f, x, tip)
                path.curve(x + .26f, .59f, x + .02f, .69f, x + .11f, .98f)
                if (effect.id == "element_fire") {
                    path.move(x, .92f); path.curve(x - .07f, .76f, x + .10f, .67f, x + .03f, .50f)
                }
                trace(path, index, 1.25f)
            }
            "ice" -> {
                val x = .05f + t * .90f
                path.move(x - .10f, .98f); path.line(x + .12f, .12f)
                repeat(3) { branch ->
                    val y = .28f + branch * .22f
                    val middle = x + .12f - (y - .12f) * .255f
                    path.move(middle, y); path.line(middle - .16f, y - .08f)
                    path.move(middle, y); path.line(middle + .14f, y - .10f)
                }
                trace(path, index, .90f)
            }
            "pixel" -> {
                repeat(3) { pixel ->
                    val x = ((index * 3 + pixel * 5) % 13) / 14f + .035f
                    val y = ((index * 7 + pixel * 3) % 17) / 18f + .03f
                    path.move(x, y); path.line(x + .027f, y)
                    path.line(x + .027f, y + .019f); path.line(x, y + .019f); path.close()
                }
                flake(path)
            }
            "void" -> {
                val radius = .10f + t * .42f
                path.move(.52f - radius, .52f)
                path.curve(.45f - radius, .14f, .70f + radius, .25f, .52f + radius, .56f)
                path.move(.48f + radius * .7f, .68f)
                path.curve(.44f, .92f, .25f - radius, .70f, .52f - radius, .52f)
                trace(path, index, .9f, .8f)
            }
            "holo_spark" -> {
                val r = .032f + (index % 3) * .02f
                sparkle(path, scatteredX, scatteredY, r, cross = true)
                path.move(scatteredX - r * .55f, scatteredY - r * .40f)
                path.line(scatteredX + r * .55f, scatteredY + r * .40f)
                path.move(scatteredX + r * .55f, scatteredY - r * .40f)
                path.line(scatteredX - r * .55f, scatteredY + r * .40f)
                trace(path, index, 1.25f)
            }
            "element_grass" -> {
                val x = .18f + (index % 3) * .30f
                val y = .13f + (index / 3) * .30f
                path.move(x - .06f, y + .20f)
                path.curve(x - .18f, y + .03f, x + .10f, y + .02f, x + .13f, y - .02f)
                path.curve(x + .16f, y + .15f, x + .05f, y + .22f, x - .06f, y + .20f)
                path.move(x - .06f, y + .20f); path.line(x + .13f, y - .02f)
                repeat(2) { vein ->
                    val vx = x + vein * .06f; val vy = y + .13f - vein * .065f
                    path.move(vx - .075f, vy - .02f); path.line(vx, vy); path.line(vx + .07f, vy + .015f)
                }
                trace(path, index, .9f)
            }
            "element_water" -> {
                val r = .08f + t * .50f
                val cx = .50f + optics.normalizedTiltY * .08f
                val cy = .53f + optics.normalizedTiltX * .08f
                path.move(cx - r, cy)
                path.curve(cx - r, cy - r * .34f, cx + r, cy - r * .34f, cx + r, cy)
                path.curve(cx + r, cy + r * .34f, cx - r, cy + r * .34f, cx - r, cy)
                trace(path, index, 1.1f)
            }
            "element_electric" -> {
                val x = .08f + t * .84f
                path.move(x + .07f, .04f); path.line(x - .09f, .39f)
                path.line(x + .075f, .34f); path.line(x - .09f, .72f)
                path.line(x + .06f, .66f); path.line(x - .06f, .97f)
                trace(path, index, 1.35f)
            }
            "element_psychic" -> {
                val r = .08f + t * .43f
                path.move(.50f, .50f - r)
                path.curve(.50f + r * 1.30f, .50f - r, .50f + r * 1.30f, .50f + r, .50f, .50f + r)
                path.curve(.50f - r * 1.30f, .50f + r, .50f - r * 1.30f, .50f - r, .50f, .50f - r)
                trace(path, index, 1.2f)
            }
            "element_fighting" -> {
                val x = .04f + t * .92f
                path.move(x - .12f, .02f); path.line(x + .045f, .27f)
                path.line(x - .035f, .46f); path.line(x + .10f, .71f); path.line(x - .02f, .98f)
                path.move(x + .045f, .27f); path.line(x + .18f, .34f)
                path.move(x - .035f, .46f); path.line(x - .18f, .60f)
                trace(path, index, 1.3f)
            }
            "element_darkness" -> {
                val y = .04f + t * .91f
                path.move(-.02f, y + .08f); path.line(.24f, y - .055f)
                path.line(.47f, y + .08f); path.line(.73f, y - .065f); path.line(1.02f, y + .075f)
                trace(path, index, 1.1f)
            }
            "element_metal" -> {
                val y = .04f + t * .88f
                path.move(.06f, y + .08f); path.line(.29f, y)
                path.line(.94f, y); path.line(.72f, y + .08f); path.close()
                path.move(.17f, y + .04f); path.line(.82f, y + .04f)
                trace(path, index, .8f)
            }
            "element_dragon" -> {
                val y = .07f + (index / 2) * .21f
                val start = .03f + (index % 2) * .47f
                repeat(3) { scale ->
                    val x = start + scale * .155f
                    path.move(x, y)
                    path.curve(x - .01f, y + .055f, x + .035f, y + .09f, x + .073f, y + .115f)
                    path.curve(x + .12f, y + .09f, x + .16f, y + .055f, x + .15f, y)
                }
                trace(path, index, 1.05f)
            }
            "element_colorless" -> {
                // Quiet six-point starbursts, distinct from rainbow dust and spark crosses.
                val r = .025f + (index % 2) * .013f
                repeat(6) { ray ->
                    val angle = ray * 1.047198f
                    path.move(scatteredX + cos(angle) * r * .35f, scatteredY + sin(angle) * r * .25f)
                    path.line(scatteredX + cos(angle) * r, scatteredY + sin(angle) * r * .72f)
                }
                trace(path, index, .9f)
            }
            "element_fairy" -> {
                val x = scatteredX; val y = scatteredY; val r = .035f
                if (index % 2 == 0) {
                    path.move(x, y + r)
                    path.curve(x - r * 2f, y, x - r, y - r, x, y - r * .30f)
                    path.curve(x + r, y - r, x + r * 2f, y, x, y + r)
                } else {
                    sparkle(path, x, y, r)
                }
                trace(path, index, 1.15f)
            }
        }
    }
}

private fun rainbowFoilColor(phase: Float): Color {
    val position = wrapPrivateBinderVisualCoordinate(phase, RainbowFoilPalette.size.toFloat())
    val first = position.toInt()
    return lerp(RainbowFoilPalette[first], RainbowFoilPalette[(first + 1) % RainbowFoilPalette.size], position - first)
}

private fun wrapPrivateBinderVisualCoordinate(value: Float, bound: Float): Float {
    if (bound <= 0f || !value.isFinite()) return 0f
    val remainder = value % bound
    return if (remainder < 0f) remainder + bound else remainder
}
