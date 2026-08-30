package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonSetRegistry
import com.dominik.cardvault.model.CollectionCard

/** Official/source rarity is distinct from foil finish. UNKNOWN stays UNKNOWN. */
internal enum class CardRarityType {
    COMMON, UNCOMMON, RARE, DOUBLE_RARE, ILLUSTRATION_RARE,
    ULTRA_RARE, SPECIAL_ILLUSTRATION_RARE, HYPER_RARE,
    FULL_GOLD, SPECIAL_MEGA_RARITY, UNKNOWN,
}

internal val CardRarityType.label: String
    get() = when (this) {
        CardRarityType.COMMON -> "Häufig"
        CardRarityType.UNCOMMON -> "Ungewöhnlich"
        CardRarityType.RARE -> "Selten"
        CardRarityType.DOUBLE_RARE -> "Doppelselten"
        CardRarityType.ILLUSTRATION_RARE -> "Illustration Rare"
        CardRarityType.ULTRA_RARE -> "Ultra Rare"
        CardRarityType.SPECIAL_ILLUSTRATION_RARE -> "Special Illustration Rare"
        CardRarityType.HYPER_RARE -> "Hyper Rare"
        CardRarityType.FULL_GOLD -> "Full Gold"
        CardRarityType.SPECIAL_MEGA_RARITY -> "Spezielle Mega-Rarität"
        CardRarityType.UNKNOWN -> "Rarität nicht bestätigt"
    }

internal data class CardRarityResolution(
    val type: CardRarityType,
    val aboveMainSet: Boolean,
    /** 1 only when metadata is explicit; no visual guess is promoted to truth. */
    val metadataConfidence: Float,
)

/**
 * A central, conservative bridge from source-declared rarity metadata to UI.
 * It never turns a high collector number or an artwork colour into a rarity.
 */
internal object CardRarityResolver {
    fun resolve(card: CollectionCard?): CardRarityResolution = card?.let {
        fromMetadata(it.rarity, it.number, mainSetSize = it.number.substringAfter('/', "").toIntOrNull())
    } ?: CardRarityResolution(CardRarityType.UNKNOWN, false, 0f)

    fun resolve(sourceRarity: String?, collectorNumber: String, set: PokemonSet): CardRarityResolution =
        fromMetadata(sourceRarity, collectorNumber, set.printedTotal)

    fun fromMetadata(sourceRarity: String?, collectorNumber: String, mainSetSize: Int?): CardRarityResolution {
        val raw = sourceRarity.orEmpty().trim()
        val normalized = raw.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        val type = when {
            normalized.isBlank() || normalized == "unknown" -> CardRarityType.UNKNOWN
            normalized.contains("special illustration") || normalized.contains("illustration special") || normalized.contains("besondere illustration") || normalized == "sir" -> CardRarityType.SPECIAL_ILLUSTRATION_RARE
            normalized.contains("illustration rare") || normalized.contains("selten illustration") || normalized == "ir" -> CardRarityType.ILLUSTRATION_RARE
            normalized.contains("hyper rare") || normalized.contains("rare hyper") || normalized.contains("hyper selten") -> CardRarityType.HYPER_RARE
            normalized.contains("full gold") || normalized.contains("gold rare") || normalized.contains("gold selten") -> CardRarityType.FULL_GOLD
            normalized.contains("ultra rare") || normalized.contains("rare ultra") || normalized.contains("ultra selten") -> CardRarityType.ULTRA_RARE
            normalized.contains("double rare") || normalized.contains("rare double") || normalized.contains("doppelt selten") -> CardRarityType.DOUBLE_RARE
            normalized.contains("mega") && (normalized.contains("rare") || normalized.contains("selten")) -> CardRarityType.SPECIAL_MEGA_RARITY
            normalized == "rare" || normalized == "selten" || normalized.contains("rare holo") -> CardRarityType.RARE
            normalized.contains("uncommon") || normalized.contains("ungewöhnlich") -> CardRarityType.UNCOMMON
            normalized.contains("common") || normalized.contains("häufig") -> CardRarityType.COMMON
            else -> CardRarityType.UNKNOWN
        }
        val number = collectorNumber.substringBefore('/').filter(Char::isDigit).toIntOrNull()
        val aboveMain = number != null && mainSetSize != null && mainSetSize > 0 && number > mainSetSize
        return CardRarityResolution(type, aboveMain, if (type == CardRarityType.UNKNOWN) 0f else 1f)
    }

    fun aboveMainSet(collectorNumber: String, set: PokemonSet): Boolean =
        PokemonSetRegistry.isAboveMainSet(collectorNumber, set)

    /** A tiny positive-only confidence contribution; never a classification source. */
    fun symbolCorroborates(type: CardRarityType, evidence: RaritySymbolEvidence): Boolean = when (type) {
        CardRarityType.ILLUSTRATION_RARE,
        CardRarityType.SPECIAL_ILLUSTRATION_RARE,
        CardRarityType.HYPER_RARE,
        CardRarityType.FULL_GOLD -> evidence.color == SymbolColor.GOLD && evidence.confidence >= .16f
        CardRarityType.ULTRA_RARE -> evidence.color == SymbolColor.SILVER && evidence.confidence >= .16f
        CardRarityType.DOUBLE_RARE, CardRarityType.RARE -> evidence.color == SymbolColor.BLACK && evidence.confidence >= .16f
        else -> false
    }
}

/** Low-cost scanner-side visual evidence; it is never sufficient to assign rarity. */
internal data class RaritySymbolEvidence(val color: SymbolColor, val starCount: Int?, val confidence: Float)
internal enum class SymbolColor { BLACK, SILVER, GOLD, SPECIAL_MULTICOLOR, OTHER, UNKNOWN }
