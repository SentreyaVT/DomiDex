package com.dominik.cardvault.data.catalog

/**
 * Official German corrections for Mega-Entwicklung – Delta-Herrschaft (DLR).
 *
 * TCGdex remains the transport and artwork source.  This narrowly scoped layer
 * protects the user-facing catalogue from known upstream translation mistakes
 * and supplies the official set metadata used by scanner, master-set and
 * binder screens.  It never invents a Cardmarket bridge or a print variant.
 *
 * Source of truth: the official German DLR checklist, published 2026-11-06.
 */
internal object DeltaHerrschaftOfficial {
    const val sourceSetId = "me06"
    const val printedCode = "DLR"
    const val germanSetName = "Mega-Entwicklung – Delta-Herrschaft"
    const val releaseDate = "2026-11-06"
    const val mainSetSize = 103
    const val totalCards = 141

    /**
     * Official checklist spellings which differ from the current German TCGdex
     * payload.  All other names continue to come from the German source feed.
     */
    private val correctedNames = mapOf(
        "094" to "Legendäre Lavabröhe",
        "095" to "Legendäre Lavabröhe",
        "132" to "Sprudelnde Wasser-Energie",
        "133" to "Wachsende Pflanzen-Energie",
        "134" to "Nitro-Feuer-Energie",
    )

    fun isSet(setId: String): Boolean = setId.equals(sourceSetId, ignoreCase = true)

    fun officialize(set: PokemonSet): PokemonSet = if (!isSet(set.id)) set else set.copy(
        name = germanSetName,
        printedTotal = mainSetSize,
        total = totalCards,
        releaseDate = releaseDate,
        ptcgoCode = printedCode,
    )

    fun officialize(card: PokemonVisualCard): PokemonVisualCard {
        if (!isSet(card.set.id)) return card
        val localId = card.sourceLocalId ?: card.number.substringBefore('/')
        val number = localId.toIntOrNull() ?: return card.copy(set = officialize(card.set))
        val officialRarity = when (number) {
            in 104..117 -> "Illustration Rare"
            in 118..134 -> "Ultra Rare"
            in 135..140 -> "Special Illustration Rare"
            141 -> "Mega Hyper Rare"
            else -> card.rarity
        }
        return card.copy(
            name = correctedNames[localId.padStart(3, '0')] ?: card.name,
            rarity = officialRarity,
            set = officialize(card.set),
        )
    }

    /**
     * Never cache a partial DLR catalogue as if it were a usable master set.
     * A provider outage must surface as a refresh error instead of silently
     * making owned cards or master-set slots disappear.
     */
    fun requireComplete(cards: List<PokemonVisualCard>): List<PokemonVisualCard> {
        if (cards.none { isSet(it.set.id) }) return cards
        val actual = cards.asSequence()
            .filter { isSet(it.set.id) }
            .mapNotNull { (it.sourceLocalId ?: it.number.substringBefore('/')).toIntOrNull() }
            .toSet()
        val expected = (1..totalCards).toSet()
        require(actual == expected) {
            "DLR-Katalog ist unvollständig (${actual.size}/$totalCards Karten); vorhandener Katalog bleibt aktiv."
        }
        val missingArtwork = cards.asSequence()
            .filter { isSet(it.set.id) }
            .filter { it.smallImageUrl.isBlank() || it.largeImageUrl.isBlank() }
            .map { it.sourceLocalId ?: it.number }
            .toList()
        require(missingArtwork.isEmpty()) {
            "DLR-Katalog enthält Karten ohne geprüfte Bildquelle (${missingArtwork.joinToString()}); vorhandener Katalog bleibt aktiv."
        }
        return cards
    }
}
