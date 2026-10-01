package com.dominik.cardvault.data.catalog

import java.util.Locale

/**
 * Source-verified gaps in the 2026-09-22 TCGdex catalogue. These are app-canonical
 * identities, not a claim that TCGdex already publishes their detail endpoints.
 * Evidence and image checks: docs/catalog-30th-supplement-20260922.md.
 */
private data class ThirtyYearsSupplementEntry(
    val id: String,
    val setId: String,
    val localId: String,
    val name: String,
    val englishName: String,
    val image: String,
    val rarity: String,
    val cardmarketProductId: Long? = null,
    val pokemonCenterPrinting: Boolean = false,
)

private const val supplementalHoloVariantId = "supplement-holo"

private fun anniversaryPromo(
    number: Int,
    name: String,
    englishName: String,
    cardmarketProductId: Long? = null,
    englishImage: Boolean = false,
    pokemonCenterPrinting: Boolean = false,
): ThirtyYearsSupplementEntry {
    val localId = number.toString().padStart(3, '0')
    return ThirtyYearsSupplementEntry(
        id = "mep-$localId",
        setId = "mep",
        localId = localId,
        name = name,
        englishName = englishName,
        image = if (englishImage) {
            "https://www.serebii.net/card/megapromos/$number.jpg"
        } else {
            "https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/MEP/MEP_DE_$number.png"
        },
        rarity = "Promo",
        cardmarketProductId = cardmarketProductId,
        pokemonCenterPrinting = pokemonCenterPrinting,
    )
}

private val thirtyYearsSupplement = listOf(
    // Each colour has its own printed collector identity, not a finish of 158/128.
    // The official numbered Cardmarket overview confirms each colour/product ID;
    // their guide prices can still be absent. See catalog-30th-sar-prices-20260923.md.
    ThirtyYearsSupplementEntry("30th-rgb-r", "30th", "R/RGB", "Mew", "Mew",
        "https://www.serebii.net/card/30thcelebration/rgb1.jpg", "RGB Rare",
        verifiedCardmarketProductId("30th-rgb-r")),
    ThirtyYearsSupplementEntry("30th-rgb-g", "30th", "G/RGB", "Mew", "Mew",
        "https://www.serebii.net/card/30thcelebration/rgb2.jpg", "RGB Rare",
        verifiedCardmarketProductId("30th-rgb-g")),
    ThirtyYearsSupplementEntry("30th-rgb-b", "30th", "B/RGB", "Mew", "Mew",
        "https://www.serebii.net/card/30thcelebration/rgb3.jpg", "RGB Rare",
        verifiedCardmarketProductId("30th-rgb-b")),
    anniversaryPromo(94, "Alola-Kokowei", "Alolan Exeggutor", 895_609L),
    anniversaryPromo(95, "Lucario", "Lucario", 895_610L),
    anniversaryPromo(96, "Lavados", "Moltres", 895_606L, englishImage = true),
    anniversaryPromo(97, "Arktos", "Articuno", 895_607L, englishImage = true),
    anniversaryPromo(98, "Zapdos", "Zapdos", 895_608L, englishImage = true),
    // Multiple Cardmarket products share these exact names/attacks. Their
    // numbered identities are confirmed, but their product/printing mapping is not.
    anniversaryPromo(99, "Quajutsu-ex", "Greninja ex"),
    anniversaryPromo(100, "Feelinara-ex", "Sylveon ex"),
    anniversaryPromo(101, "Nidorina", "Nidorina", pokemonCenterPrinting = true),
    anniversaryPromo(102, "Victini", "Victini", 895_613L),
    anniversaryPromo(103, "Zeraora", "Zeraora", 895_614L),
    anniversaryPromo(104, "Mewtu", "Mewtwo", 895_620L, englishImage = true),
    anniversaryPromo(105, "Mew", "Mew", 895_621L, englishImage = true),
    anniversaryPromo(106, "Ditto", "Ditto", 895_619L, englishImage = true),
    anniversaryPromo(107, "Pikachu-ex", "Pikachu ex", 895_615L),
    anniversaryPromo(108, "Psiana-ex", "Espeon ex", 895_616L),
    anniversaryPromo(109, "Pikachu-ex", "Pikachu ex", 895_617L),
    anniversaryPromo(110, "Nachtara-ex", "Umbreon ex", 895_618L),
)

private fun ThirtyYearsSupplementEntry.asCard(set: PokemonSet): PokemonVisualCard = PokemonVisualCard(
    id = id,
    name = name,
    number = localId,
    rarity = rarity,
    smallImageUrl = image,
    largeImageUrl = image,
    set = set,
    printVariants = buildList {
        add(PokemonPrintVariant(supplementalHoloVariantId, "Holo", "Holo", cardmarketProductId))
        if (pokemonCenterPrinting) {
            add(PokemonPrintVariant("supplement-pokemon-center", "Holo", "Standard · Stempel: Pokémon-Center"))
        }
    },
    marketSearchName = englishName,
    hasGermanLocalizedName = true,
    sourceLocalId = localId,
)

internal fun supplementalThirtyYearsCards(set: PokemonSet): List<PokemonVisualCard> =
    thirtyYearsSupplement.filter { it.setId.equals(set.id, ignoreCase = true) }.map { it.asCard(set) }

private fun supplementalCollectorIdentity(value: String): String {
    val normalized = value.trim().uppercase(Locale.ROOT)
    return normalized.toIntOrNull()?.toString() ?: normalized
}

/** Upstream identities/metadata win; verified art fills only an absent image. */
internal fun mergeThirtyYearsCatalogSupplement(
    set: PokemonSet,
    cards: List<PokemonVisualCard>,
): List<PokemonVisualCard> {
    val supplement = supplementalThirtyYearsCards(set)
    if (supplement.isEmpty()) return cards
    val byNumber = supplement.associateBy { supplementalCollectorIdentity(it.sourceLocalId ?: it.number) }
    val sourceIds = cards.mapTo(hashSetOf(), PokemonVisualCard::id)
    val sourceNumbers = cards.mapTo(hashSetOf()) { supplementalCollectorIdentity(it.sourceLocalId ?: it.number) }
    return cards.map { card ->
        val fallback = byNumber[supplementalCollectorIdentity(card.sourceLocalId ?: card.number)]
        if (fallback == null) card else card.copy(
            smallImageUrl = card.smallImageUrl.ifBlank { fallback.smallImageUrl },
            largeImageUrl = card.largeImageUrl.ifBlank { fallback.largeImageUrl },
        )
    } + supplement.filterNot {
        it.id in sourceIds || supplementalCollectorIdentity(it.sourceLocalId ?: it.number) in sourceNumbers
    }
}

/**
 * Avoid a known-absent detail request only for a locally generated card. When
 * TCGdex later publishes mep-094 etc., its untagged card takes the normal detail
 * path so new upstream finish/stamp information remains authoritative.
 */
internal fun supplementalThirtyYearsCardDetail(card: PokemonVisualCard): PokemonVisualCard? {
    if (card.printVariants.none { it.id == supplementalHoloVariantId }) return null
    val entry = thirtyYearsSupplement.firstOrNull {
        it.id == card.id && it.setId.equals(card.set.id, ignoreCase = true)
    } ?: return null
    return entry.asCard(card.set)
}
