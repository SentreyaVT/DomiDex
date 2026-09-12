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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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
        holo("rainbow", "Rainbow", "Sanftes vollständiges Farbspektrum.", HoloPattern.SPECTRUM, 0xFFFF6688, 0xFF63E5FF, 0xFFD778FF),
        holo("linear", "Linear", "Geradliniger Lichtzug über der Karte.", HoloPattern.SHEEN, 0xFFFFFFFF, 0xFF7DDFFF, 0xFFC68CFF),
        holo("diagonal", "Diagonal", "Feine diagonale Holo-Lichtlinien.", HoloPattern.DIAGONAL, 0xFF57E8FF, 0xFFC25CFF, 0xFFFF75B7),
        holo("galaxy", "Galaxy", "Sternpunkte in kosmischem Violett.", HoloPattern.STARS, 0xFF7F68FF, 0xFFDD69FF, 0xFF64DFFF),
        holo("nebula", "Nebula", "Weicher, kosmischer Farbnebel.", HoloPattern.WAVE, 0xFF746BFF, 0xFFDD69FF, 0xFF58DDEB),
        holo("aurora", "Aurora", "Fließendes Polarlicht in Grün und Violett.", HoloPattern.WAVE, 0xFF69F7C5, 0xFF67B8FF, 0xFFE879FF),
        holo("crystal", "Crystal", "Kühle transparente Kristallflächen.", HoloPattern.FACETS, 0xFF9FF8FF, 0xFFC5B4FF, 0xFFFFFFFF),
        holo("prism", "Prism", "Prismatische Farbkanten mit Tiefe.", HoloPattern.FACETS, 0xFF57E8FF, 0xFFD15DFF, 0xFFFFD06B),
        holo("diamond", "Diamond", "Klarer, facettierter Diamantglanz.", HoloPattern.FACETS, 0xFFFFFFFF, 0xFF8FDFFF, 0xFFD9C5FF),
        holo("stardust", "Stardust", "Dezent verteilte Lichtpartikel.", HoloPattern.STARS, 0xFFFFFFFF, 0xFFA7C7FF, 0xFFD7A5FF),
        holo("bubble", "Bubble", "Runde, luftige Lichtpunkte.", HoloPattern.CONFETTI, 0xFFA7F5FF, 0xFFD0B5FF, 0xFFFFFFFF),
        holo("oil_slick", "Oil Slick", "Dunkler irisierender Farbfluss.", HoloPattern.SPECTRUM, 0xFF4BE2C5, 0xFF6D55CE, 0xFFEF5FAE),
        holo("liquid_metal", "Liquid Metal", "Fließender metallischer Schimmer.", HoloPattern.SHEEN, 0xFFFFFFFF, 0xFF8593A8, 0xFFDCE5EF),
        holo("hyper_wave", "Hyper Wave", "Kräftigere, gebündelte Energiewellen.", HoloPattern.WAVE, 0xFF66FFF0, 0xFF8272FF, 0xFFFF7BD4),
        holo("fire", "Fire", "Warmer Holozug in Orange und Rot.", HoloPattern.DIAGONAL, 0xFFFFB14C, 0xFFFF5D4F, 0xFFFFE7A1),
        holo("ice", "Ice", "Kühler Holozug in Eisblau.", HoloPattern.FACETS, 0xFFFFFFFF, 0xFF70DBFF, 0xFFA5BFFF),
        holo("pixel", "Pixel", "Kompakte digitale Lichtpunkte.", HoloPattern.CONFETTI, 0xFF5CEBFF, 0xFFB66AFF, 0xFFFFFFFF),
        holo("void", "Void", "Sehr dunkler, zurückhaltender Schimmer.", HoloPattern.SHEEN, 0xFF3B4252, 0xFF705A96, 0xFF8BA4B8),
        holo("holo_spark", "Holo Spark", "Gezielte helle Energieblitze.", HoloPattern.BURST, 0xFFFFFFFF, 0xFF9F72FF, 0xFF5BE7FF),
        holo("element_grass", "Element · Pflanze", "Blätter, grüne Lichtadern und Naturfunken.", HoloPattern.WAVE, 0xFF57F28E, 0xFFB8FF66, 0xFFFFFFFF),
        holo("element_fire", "Element · Feuer", "Flammenbänder, Glut und warme Energiespuren.", HoloPattern.DIAGONAL, 0xFFFF4C32, 0xFFFFB21F, 0xFFFFF0A3),
        holo("element_water", "Element · Wasser", "Wellen, Tropfen und klare Cyan-Reflexe.", HoloPattern.WAVE, 0xFF38D9FF, 0xFF287CFF, 0xFFFFFFFF),
        holo("element_electric", "Element · Elektro", "Blitze und helle gelb-cyanfarbene Funken.", HoloPattern.BURST, 0xFFFFF26A, 0xFF55E9FF, 0xFFFFFFFF),
        holo("element_psychic", "Element · Psycho", "Violette Aura-Ringe und mentale Lichtwellen.", HoloPattern.WAVE, 0xFFC85CFF, 0xFF6E62FF, 0xFFFFA7F2),
        holo("element_fighting", "Element · Kampf", "Kraftvolle Einschlagringe und glühende Bruchlinien.", HoloPattern.BURST, 0xFFFF8B38, 0xFFE33F2F, 0xFFFFD67A),
        holo("element_darkness", "Element · Finsternis", "Tiefe Indigo-Bögen mit dunkler Energie.", HoloPattern.SHEEN, 0xFF24113F, 0xFF7B3EC8, 0xFFFF527D),
        holo("element_metal", "Element · Metall", "Chromfacetten und kühle Stahlreflexe.", HoloPattern.FACETS, 0xFFFFFFFF, 0xFFAEC5DB, 0xFFFFD9A6),
        holo("element_dragon", "Element · Drache", "Prismatische Schuppen und kräftige Energiespuren.", HoloPattern.FACETS, 0xFF44E4D5, 0xFFFFC857, 0xFFB65CFF),
        holo("element_colorless", "Element · Farblos", "Perlmuttsterne und neutrale Silberringe.", HoloPattern.STARS, 0xFFFFFFFF, 0xFFD9E6F2, 0xFFFFE4B8),
        holo("element_fairy", "Element · Fee", "Pastellfunken und sanfter magischer Glitzer.", HoloPattern.CONFETTI, 0xFFFF8FDF, 0xFF8FE9FF, 0xFFFFFFFF),
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
internal enum class HoloPattern { NONE, SHEEN, SPECTRUM, DIAGONAL, WAVE, STARS, FACETS, BURST, CONFETTI }

private val RainbowFoilPalette = listOf(
    Color(0xFFFF5F7E),
    Color(0xFFFFD65C),
    Color(0xFF72F59B),
    Color(0xFF53E8FF),
    Color(0xFF718BFF),
    Color(0xFFD875FF),
)

internal data class PrivateBinderTilt(val rotationX: Float, val rotationY: Float)

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
    val catalogMaterialIndex = remember(effect.id) {
        (PrivateBinderVisualEngine.holoEffects.indexOfFirst { it.id == effect.id } - 1).coerceAtLeast(0)
    }
    val isElementMaterial = catalogMaterialIndex >= PrivateBinderVisualEngine.CORE_HOLO_MATERIAL_COUNT
    val atlas = ImageBitmap.imageResource(
        if (isElementMaterial) R.drawable.private_binder_element_holo_atlas
        else R.drawable.private_binder_holo_atlas,
    )
    val materialIndex = if (isElementMaterial) {
        catalogMaterialIndex - PrivateBinderVisualEngine.CORE_HOLO_MATERIAL_COUNT
    } else catalogMaterialIndex
    val atlasColumns = if (isElementMaterial) 4 else 5
    val atlasRows = if (isElementMaterial) 3 else 4
    val plan = PrivateBinderVisualEngine.renderPlan(preferences)
    if (plan.intensity <= 0f) return
    // Foil has no timeline of its own. Static cards remain completely still; interactive
    // cards supply the exact same phase that moves the card surface itself.
    val phase = tiltState?.motionPhase ?: .5f
    val tilt = PrivateBinderTilt(
        rotationX = tiltState?.rotationX ?: 0f,
        rotationY = tiltState?.rotationY ?: 0f,
    )
    Canvas(modifier) {
        drawHoloPattern(effect, plan, phase, tilt, atlas, materialIndex, atlasColumns, atlasRows)
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
    phase: Float,
    tilt: PrivateBinderTilt,
    atlas: ImageBitmap,
    materialIndex: Int,
    atlasColumns: Int,
    atlasRows: Int,
) {
    val intensityCurve = plan.intensity * plan.intensity
    val alpha = (
        .04f + plan.intensity * .13f + intensityCurve * .18f
    ).coerceAtMost(.31f)
    val colors = effect.colors.map { it.copy(alpha = alpha) }
    val tiltTravel = if (PrivateBinderVisualEngine.MAX_TILT_DEGREES > 0f) {
        (tilt.rotationY / PrivateBinderVisualEngine.MAX_TILT_DEGREES) * size.width * .16f
    } else 0f
    val verticalTravel = (tilt.rotationX / PrivateBinderVisualEngine.MAX_TILT_DEGREES) * size.height * .08f
    val sharedMotion = (phase - .5f) * 2f
    val travel = sharedMotion * size.width * .10f + tiltTravel

    // Each effect owns a genuinely different light-map from the reference-driven atlas.
    // Pure black in the source is neutral under Screen blending, so card artwork and text
    // remain readable while only the luminous foil structure appears above the card.
    val cellWidth = atlas.width / atlasColumns
    val cellHeight = atlas.height / atlasRows
    val column = materialIndex % atlasColumns
    val row = materialIndex / atlasColumns
    val sourceInset = (min(cellWidth, cellHeight) * .025f).roundToInt().coerceAtLeast(2)
    // The atlas is deliberately rendered beyond every edge. This margin covers the complete
    // strengthened 3D tilt range, so no transparent seam can enter at an outer card corner.
    val overscan = size.minDimension * .16f
    val materialShiftX = sharedMotion * size.width * .08f + tiltTravel * .70f
    val materialShiftY = sharedMotion * size.height * .025f + verticalTravel * .62f
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
        alpha = (
            .05f + plan.intensity * .20f + intensityCurve * .33f
        ).coerceAtMost(.58f),
        blendMode = BlendMode.Screen,
    )

    // No full-card color wash: only the material, its reflections and fine foil grooves are
    // allowed above the artwork. This avoids the milky veil that broad translucent layers
    // produce on bright cards.
    val gleamCenter = size.width * (.50f + sharedMotion * .30f) + tiltTravel
    val gleamHalfWidth = size.width * (.10f + plan.intensity * .035f)
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                effect.colors.first().copy(
                    alpha = (.025f + plan.intensity * .05f + intensityCurve * .11f)
                        .coerceAtMost(.18f),
                ),
                Color.White.copy(
                    alpha = (.06f + plan.intensity * .10f + intensityCurve * .12f)
                        .coerceAtMost(.28f),
                ),
                effect.colors.last().copy(
                    alpha = (.025f + plan.intensity * .05f + intensityCurve * .11f)
                        .coerceAtMost(.18f),
                ),
                Color.Transparent,
            ),
            start = Offset(gleamCenter - gleamHalfWidth, -size.height * .10f + verticalTravel),
            end = Offset(gleamCenter + gleamHalfWidth, size.height * 1.10f + verticalTravel),
        ),
        blendMode = BlendMode.Screen,
    )
    when (effect.pattern) {
        HoloPattern.NONE -> Unit
        HoloPattern.SHEEN, HoloPattern.SPECTRUM -> {
            val patternCenter = size.width * (.5f + sharedMotion * .38f) + tiltTravel
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent) + colors + Color.Transparent,
                    start = Offset(patternCenter - size.width * .24f, size.height + verticalTravel),
                    end = Offset(patternCenter + size.width * .24f, verticalTravel),
                ),
                blendMode = BlendMode.Screen,
            )
        }
        HoloPattern.DIAGONAL -> repeat(min(plan.primitiveBudget, 7)) { index ->
            val x = size.width * (index / 6f) + travel
            drawLine(colors[index % colors.size], Offset(x - size.height * .35f, size.height), Offset(x + size.height * .35f, 0f), size.minDimension * .035f, StrokeCap.Round, blendMode = BlendMode.Screen)
        }
        HoloPattern.WAVE -> repeat(min(plan.primitiveBudget, 5)) { index ->
            val y = size.height * (.16f + index * .18f) + verticalTravel
            val path = Path().apply {
                moveTo(-size.width * .08f + travel * .16f, y)
                cubicTo(
                    size.width * .28f + travel * .10f,
                    y - size.height * (.03f + phase * .02f),
                    size.width * .70f + travel * .10f,
                    y + size.height * .05f,
                    size.width * 1.08f + travel * .16f,
                    y,
                )
            }
            drawPath(path, colors[index % colors.size], style = Stroke(size.minDimension * .018f, cap = StrokeCap.Round), blendMode = BlendMode.Screen)
        }
        HoloPattern.STARS, HoloPattern.CONFETTI -> repeat(plan.primitiveBudget) { index ->
            val baseX = size.width * (((index * 37) % 97) / 96f)
            val baseY = size.height * (((index * 61) % 89) / 88f)
            val x = wrapPrivateBinderVisualCoordinate(baseX + travel * .22f, size.width)
            val y = wrapPrivateBinderVisualCoordinate(baseY + verticalTravel + (phase - .5f) * size.height * .04f, size.height)
            val radius = size.minDimension * if (effect.pattern == HoloPattern.STARS) .012f else .018f
            drawCircle(colors[index % colors.size], radius, Offset(x, y), blendMode = BlendMode.Screen)
        }
        HoloPattern.FACETS -> repeat(min(plan.primitiveBudget, 5)) { index ->
            val x = size.width * (.12f + index * .19f) + travel * .14f
            val path = Path().apply {
                moveTo(x, size.height * .12f + verticalTravel)
                lineTo(x + size.width * .18f, size.height * .48f + verticalTravel)
                lineTo(x - size.width * .05f, size.height * .86f + verticalTravel)
                close()
            }
            drawPath(path, colors[index % colors.size], blendMode = BlendMode.Screen)
        }
        HoloPattern.BURST -> repeat(min(plan.primitiveBudget, 10)) { index ->
            val edge = Offset(size.width * (index / 9f), if (index % 2 == 0) 0f else size.height)
            val reactiveCenter = Offset(
                center.x + tiltTravel * .44f,
                center.y + verticalTravel * .70f,
            )
            drawLine(colors[index % colors.size], reactiveCenter, edge, size.minDimension * .009f, blendMode = BlendMode.Screen)
        }
    }

    // Fine foil grooves produce the characteristic moving rainbow shimmer. Their hue and
    // position use the same phase/tilt as the card, so the reflection never drifts away from
    // the physical card motion. Thin, low-alpha lines keep the artwork clearly readable.
    val rainbowLineCount = min(plan.primitiveBudget + 6, 14)
    val rainbowStroke = (size.minDimension * .0052f).coerceIn(.8f, 2.5f)
    val rainbowAlpha = (
        .035f + plan.intensity * .07f + intensityCurve * .30f
    ).coerceAtMost(.405f)
    val tiltHueShift =
        (tilt.rotationY + tilt.rotationX * .45f) / PrivateBinderVisualEngine.MAX_TILT_DEGREES
    val hueMotion = phase * RainbowFoilPalette.size + tiltHueShift * 1.35f
    val lineTravel = sharedMotion * size.height * .045f + verticalTravel * .72f
    repeat(rainbowLineCount) { index ->
        val fraction = (index + 1f) / (rainbowLineCount + 1f)
        val y = size.height * fraction + lineTravel
        val colorPosition = hueMotion + index * .62f
        val wrappedColor = wrapPrivateBinderVisualCoordinate(
            colorPosition,
            RainbowFoilPalette.size.toFloat(),
        )
        val startIndex = wrappedColor.toInt().coerceIn(RainbowFoilPalette.indices)
        val endIndex = (startIndex + 1) % RainbowFoilPalette.size
        val shimmerColor = lerp(
            RainbowFoilPalette[startIndex],
            RainbowFoilPalette[endIndex],
            wrappedColor - startIndex,
        ).copy(alpha = rainbowAlpha)
        drawLine(
            color = shimmerColor,
            start = Offset(-size.width * .08f + tiltTravel * .14f, y + size.height * .055f),
            end = Offset(size.width * 1.08f + tiltTravel * .14f, y - size.height * .055f),
            strokeWidth = rainbowStroke,
            cap = StrokeCap.Round,
            blendMode = BlendMode.SrcOver,
        )
    }
}

private fun wrapPrivateBinderVisualCoordinate(value: Float, bound: Float): Float {
    if (bound <= 0f || !value.isFinite()) return 0f
    val remainder = value % bound
    return if (remainder < 0f) remainder + bound else remainder
}
