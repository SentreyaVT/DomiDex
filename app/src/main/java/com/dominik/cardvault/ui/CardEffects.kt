package com.dominik.cardvault.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import com.dominik.cardvault.model.CollectionCard
import com.dominik.cardvault.ui.theme.MewIce
import com.dominik.cardvault.ui.theme.MewPlum
import com.dominik.cardvault.ui.theme.MewRose
import com.dominik.cardvault.ui.theme.VaultGold

private fun effectStops(config: CardEffectConfig, alphaScale: Float): List<Color> = when (config.profile) {
    CardEffectProfile.ILLUSTRATION_RARE -> listOf(Color.Transparent, VaultGold.copy(alpha = .42f * alphaScale), MewRose.copy(alpha = .22f * alphaScale), MewIce.copy(alpha = .16f * alphaScale), Color.Transparent)
    CardEffectProfile.SPECIAL_ILLUSTRATION_RARE -> listOf(Color.Transparent, VaultGold.copy(alpha = .50f * alphaScale), MewIce.copy(alpha = .30f * alphaScale), MewRose.copy(alpha = .34f * alphaScale), VaultGold.copy(alpha = .32f * alphaScale), Color.Transparent)
    CardEffectProfile.HYPER_RARE, CardEffectProfile.FULL_GOLD -> listOf(Color.Transparent, VaultGold.copy(alpha = .58f * alphaScale), Color(0xFFFFF0A0).copy(alpha = .30f * alphaScale), MewRose.copy(alpha = .16f * alphaScale), Color.Transparent)
    CardEffectProfile.ULTRA_RARE -> listOf(Color.Transparent, MewIce.copy(alpha = .48f * alphaScale), Color(0xFFDDE7F2).copy(alpha = .34f * alphaScale), MewPlum.copy(alpha = .20f * alphaScale), Color.Transparent)
    CardEffectProfile.DOUBLE_RARE -> listOf(Color.Transparent, MewIce.copy(alpha = .34f * alphaScale), MewRose.copy(alpha = .34f * alphaScale), MewPlum.copy(alpha = .22f * alphaScale), Color.Transparent)
    else -> listOf(Color.Transparent, MewIce.copy(alpha = .36f * alphaScale), MewRose.copy(alpha = .40f * alphaScale), VaultGold.copy(alpha = .28f * alphaScale), Color.Transparent)
}

internal enum class CardEffectProfile {
    NONE, HOLO, REVERSE_HOLO, DOUBLE_RARE, ILLUSTRATION_RARE,
    ULTRA_RARE, SPECIAL_ILLUSTRATION_RARE, HYPER_RARE, FULL_GOLD,
    EX, FULL_ART, SPECIAL,
}
internal enum class CardEffectZone { ARTWORK, BODY, BORDER, FULL_CARD }
internal data class CardEffectConfig(
    val profile: CardEffectProfile,
    val zones: Set<CardEffectZone>,
    val intensity: Float,
    val parallax: Float = 1f,
    val sparkleCount: Int = 0,
    val metallic: Boolean = false,
)

internal val CardEffectProfile.label: String
    get() = when (this) {
        CardEffectProfile.NONE -> "Kein Effekt"
        CardEffectProfile.HOLO -> "Holo"
        CardEffectProfile.REVERSE_HOLO -> "Reverse Holo"
        CardEffectProfile.DOUBLE_RARE -> "Doppelselten"
        CardEffectProfile.ILLUSTRATION_RARE -> "Illustration Rare · Goldstern"
        CardEffectProfile.ULTRA_RARE -> "Ultra Rare · Silbersterne"
        CardEffectProfile.SPECIAL_ILLUSTRATION_RARE -> "Special Illustration Rare · Goldsterne"
        CardEffectProfile.HYPER_RARE -> "Hyper Rare · Goldsterne"
        CardEffectProfile.FULL_GOLD -> "Full Gold"
        CardEffectProfile.EX -> "EX"
        CardEffectProfile.FULL_ART -> "Full Art"
        CardEffectProfile.SPECIAL -> "Special"
    }

/**
 * A single metadata-only effect authority. It deliberately has no preference,
 * Firebase, image or Binder dependency: the physical print stays the source of
 * truth everywhere DomiDex renders it.
 */
internal object CardEffectResolver {
    fun resolve(card: CollectionCard?): CardEffectConfig {
        effectProfileFromOverride(card?.effectOverride)?.let(::configFor)?.let { return it }
        val rarity = CardRarityResolver.resolve(card).type
        val profile = when (rarity) {
            CardRarityType.ILLUSTRATION_RARE -> CardEffectProfile.ILLUSTRATION_RARE
            CardRarityType.SPECIAL_ILLUSTRATION_RARE -> CardEffectProfile.SPECIAL_ILLUSTRATION_RARE
            CardRarityType.HYPER_RARE -> CardEffectProfile.HYPER_RARE
            CardRarityType.ULTRA_RARE -> CardEffectProfile.ULTRA_RARE
            CardRarityType.DOUBLE_RARE -> CardEffectProfile.DOUBLE_RARE
            CardRarityType.FULL_GOLD -> CardEffectProfile.FULL_GOLD
            CardRarityType.SPECIAL_MEGA_RARITY -> CardEffectProfile.SPECIAL
            else -> when {
            card == null -> CardEffectProfile.NONE
            card.variant.contains("full art", true) || card.variant.contains("fullart", true) -> CardEffectProfile.FULL_ART
            card.variant.contains("special", true) || card.variant.contains("illustration rare", true) || card.variant.contains("sir", true) -> CardEffectProfile.SPECIAL
            card.variant.contains(" ex", true) || card.variant.startsWith("ex", true) || card.variant.contains(" gx", true) || card.variant.contains(" v", true) || card.finish.equals("EX", true) -> CardEffectProfile.EX
            card.finish.contains("reverse", true) -> CardEffectProfile.REVERSE_HOLO
            card.finish.contains("holo", true) -> CardEffectProfile.HOLO
            else -> CardEffectProfile.NONE
            }
        }
        return configFor(profile)
    }

    /** User-selected visual treatment; physical finish and rarity remain untouched. */
    internal fun effectProfileFromOverride(value: String?): CardEffectProfile? = when (value?.uppercase()) {
        "NONE" -> CardEffectProfile.NONE
        "HOLO" -> CardEffectProfile.HOLO
        "REVERSE_HOLO" -> CardEffectProfile.REVERSE_HOLO
        "DOUBLE_RARE" -> CardEffectProfile.DOUBLE_RARE
        "ILLUSTRATION_RARE" -> CardEffectProfile.ILLUSTRATION_RARE
        "ULTRA_RARE" -> CardEffectProfile.ULTRA_RARE
        "SPECIAL_ILLUSTRATION_RARE" -> CardEffectProfile.SPECIAL_ILLUSTRATION_RARE
        "HYPER_RARE" -> CardEffectProfile.HYPER_RARE
        "FULL_GOLD" -> CardEffectProfile.FULL_GOLD
        "EX" -> CardEffectProfile.EX
        "FULL_ART" -> CardEffectProfile.FULL_ART
        "SPECIAL" -> CardEffectProfile.SPECIAL
        else -> null
    }

    internal fun configFor(profile: CardEffectProfile): CardEffectConfig = when (profile) {
            CardEffectProfile.NONE -> CardEffectConfig(profile, emptySet(), 0f)
            CardEffectProfile.HOLO -> CardEffectConfig(profile, setOf(CardEffectZone.ARTWORK, CardEffectZone.BORDER), .50f)
            CardEffectProfile.REVERSE_HOLO -> CardEffectConfig(profile, setOf(CardEffectZone.BODY), .48f)
            CardEffectProfile.DOUBLE_RARE -> CardEffectConfig(profile, setOf(CardEffectZone.FULL_CARD), .62f, parallax = 1.15f, metallic = true)
            CardEffectProfile.ILLUSTRATION_RARE -> CardEffectConfig(profile, setOf(CardEffectZone.FULL_CARD), .60f, parallax = .92f, sparkleCount = 2, metallic = true)
            CardEffectProfile.ULTRA_RARE -> CardEffectConfig(profile, setOf(CardEffectZone.FULL_CARD), .67f, parallax = 1.20f, sparkleCount = 3, metallic = true)
            CardEffectProfile.SPECIAL_ILLUSTRATION_RARE -> CardEffectConfig(profile, setOf(CardEffectZone.FULL_CARD), .78f, parallax = 1.45f, sparkleCount = 6, metallic = true)
            CardEffectProfile.HYPER_RARE -> CardEffectConfig(profile, setOf(CardEffectZone.FULL_CARD), .82f, parallax = 1.30f, sparkleCount = 4, metallic = true)
            CardEffectProfile.FULL_GOLD -> CardEffectConfig(profile, setOf(CardEffectZone.FULL_CARD), .74f, parallax = 1.10f, sparkleCount = 2, metallic = true)
            CardEffectProfile.EX -> CardEffectConfig(profile, setOf(CardEffectZone.FULL_CARD), .72f)
            CardEffectProfile.FULL_ART -> CardEffectConfig(profile, setOf(CardEffectZone.FULL_CARD), .55f)
            CardEffectProfile.SPECIAL -> CardEffectConfig(profile, setOf(CardEffectZone.FULL_CARD), .82f)
    }
}

/**
 * Cheap thumbnail/favourite treatment. It deliberately reuses the same effect
 * resolution as the large card but starts no infinite animation and performs
 * no image work, keeping Binder and Home scrolling predictable.
 */
@Composable
internal fun StaticCardEffectLayer(config: CardEffectConfig, modifier: Modifier = Modifier) {
    if (config.profile == CardEffectProfile.NONE) return
    Canvas(modifier.fillMaxSize()) {
        val full = Rect(0f, 0f, size.width, size.height)
        val artwork = Rect(size.width * .08f, size.height * .10f, size.width * .92f, size.height * .54f)
        val body = Rect(size.width * .06f, size.height * .49f, size.width * .94f, size.height * .92f)
        config.zones.forEach { zone ->
            val bounds = when (zone) {
                CardEffectZone.ARTWORK -> artwork
                CardEffectZone.BODY -> body
                CardEffectZone.BORDER, CardEffectZone.FULL_CARD -> full
            }
            clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
                drawRect(
                    Brush.linearGradient(
                        effectStops(config, .54f * config.intensity),
                        Offset(bounds.left, bounds.top), Offset(bounds.right, bounds.bottom),
                    ),
                )
            }
        }
        // The artwork supplies its own physical edge. A synthetic cyan perimeter becomes
        // distracting while the card tilts, so only the actual foil regions are rendered.
    }
}

/** Runtime-only spectral overlay. Its position is driven directly by touch/sensor tilt. */
@Composable
internal fun HoloEffectLayer(config: CardEffectConfig, tiltX: Float, tiltY: Float, interacting: Boolean, modifier: Modifier = Modifier) {
    if (config.profile == CardEffectProfile.NONE) return
    // Alpha/color stops depend solely on the profile, not on a frame. Keeping
    // them remembered prevents transient list allocation during a finger drag.
    val spectralStops = remember(config.profile, config.intensity) { effectStops(config, config.intensity) }
    val depthStops = remember(config.profile, config.intensity) {
        listOf(Color.Transparent, MewPlum.copy(alpha = .24f * config.intensity), Color.Transparent)
    }
    Canvas(modifier.fillMaxSize()) {
        // The resting card stays optically still. Spectral movement is earned by a
        // real tilt/drag, which keeps lists calm and respects reduced-motion users.
        val motionX = (if (interacting) tiltX else 0f) * config.parallax
        val motionY = (if (interacting) tiltY else 0f) * config.parallax
        config.zones.forEach { zoneKind ->
            val zone = when (zoneKind) {
                CardEffectZone.ARTWORK -> Rect(size.width * .08f, size.height * .10f, size.width * .92f, size.height * .54f)
                CardEffectZone.BODY -> Rect(size.width * .06f, size.height * .49f, size.width * .94f, size.height * .92f)
                CardEffectZone.BORDER -> Rect(0f, 0f, size.width, size.height)
                CardEffectZone.FULL_CARD -> Rect(0f, 0f, size.width, size.height)
            }
            clipRect(zone.left, zone.top, zone.right, zone.bottom) {
                val start = Offset(zone.left + zone.width * (motionX - .65f), zone.top + zone.height * (motionY - .55f))
                val end = Offset(zone.right + zone.width * (motionX + .25f), zone.bottom + zone.height * (motionY + .45f))
                drawRect(Brush.linearGradient(spectralStops, start, end))
                if (config.sparkleCount > 0 || config.profile == CardEffectProfile.EX || config.profile == CardEffectProfile.SPECIAL) {
                    drawRect(Brush.linearGradient(depthStops, Offset(zone.left, zone.top + zone.height * (.35f + motionY * .25f)), Offset(zone.right, zone.top + zone.height * (.65f + motionY * .25f))))
                    repeat(config.sparkleCount.takeIf { it > 0 } ?: if (config.profile == CardEffectProfile.SPECIAL) 8 else 4) { index ->
                        val x = zone.left + zone.width * ((index * .23f + .18f + motionX * .14f) % 1f)
                        val y = zone.top + zone.height * ((index * .37f + .16f + motionY * .10f) % 1f)
                        drawCircle(if (config.profile in setOf(CardEffectProfile.ILLUSTRATION_RARE, CardEffectProfile.SPECIAL_ILLUSTRATION_RARE, CardEffectProfile.HYPER_RARE, CardEffectProfile.FULL_GOLD)) VaultGold else MewIce, radius = if (config.profile == CardEffectProfile.SPECIAL_ILLUSTRATION_RARE) 2.2f else 1.6f, center = Offset(x, y), alpha = .35f * config.intensity)
                    }
                }
            }
        }
        // Do not draw a synthetic perimeter; it exposes bright seams at 3D tilt angles.
    }
}
