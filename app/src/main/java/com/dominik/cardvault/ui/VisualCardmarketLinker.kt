package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonPrintVariant
import com.dominik.cardvault.data.catalog.PokemonSetRegistry
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.model.CatalogSearchResult
import com.dominik.cardvault.model.CollectionCard
import java.text.Normalizer

/**
 * Shared TCGdex -> Cardmarket bridge used by both manual set selection and the scanner.
 *
 * Cardmarket's compact public catalogue deliberately has no collector number.  A product is
 * therefore linkable only after a source-backed expansion has been established and the English
 * TCGdex printing name plus stable print order agree.  No name-only cross-set fallback is used.
 */
internal fun visualCardmarketProducts(
    card: PokemonVisualCard,
    cardsInSet: List<PokemonVisualCard>,
    candidates: List<CatalogSearchResult>,
    resolvedExpansionId: String? = null,
): List<CatalogSearchResult> {
    val expansionId = resolvedExpansionId ?: card.set.cardmarketExpansionId ?: return emptyList()
    val searchName = card.marketSearchName ?: card.name
    val samePrintingCandidates = candidates
        .asSequence()
        .filter { it.expansionId == expansionId && cardmarketNameMatches(it.name, searchName) }
        .distinctBy { it.productId }
        .sortedBy { it.productId }
        .toList()
    if (samePrintingCandidates.isEmpty()) return emptyList()

    val sameNamePrintings = cardsInSet
        .filter { canonicalCardName(it.marketSearchName ?: it.name) == canonicalCardName(searchName) }
        .sortedWith(compareBy({ canonicalCollectorNumber(it.number).toIntOrNull() ?: Int.MAX_VALUE }, { it.number }))
    val printedIndex = sameNamePrintings.indexOfFirst { it.id == card.id }
    return samePrintingCandidates.getOrNull(printedIndex.coerceAtLeast(0))?.copy(
        sourceSetId = card.set.id.lowercase(),
        sourceCardId = card.id.lowercase(),
        visualSetName = card.set.name,
        collectorNumber = card.number,
        imageUrl = card.largeImageUrl,
        marketSearchName = card.marketSearchName,
    )?.let(::listOf).orEmpty()
}

/** Resolves a grey Binder slot only through its stable source identity and verified printing. */
internal fun binderReferenceCardmarketProducts(
    reference: CollectionCard,
    sourceCard: PokemonVisualCard,
    cardsInSet: List<PokemonVisualCard>,
    candidates: List<CatalogSearchResult>,
): List<CatalogSearchResult> {
    if (reference.sourceSetId != sourceCard.set.id.lowercase() || reference.sourceCardId != sourceCard.id.lowercase()) return emptyList()
    return visualCardmarketProducts(sourceCard, cardsInSet, candidates, reference.marketExpansionId).map { product ->
        product.copy(
            name = reference.name,
            visualSetName = reference.set,
            collectorNumber = reference.number,
            finish = reference.finish,
            variant = reference.variant,
            trendPriceEur = product.realTrendFor(reference.finish),
            sourceSetId = reference.sourceSetId,
            sourceCardId = reference.sourceCardId,
        )
    }
}

/**
 * For sets without a maintained source bridge, accept an expansion only when several distinct
 * TCGdex printings independently agree and the best result clearly beats the runner-up.
 */
internal fun resolveCardmarketExpansionId(
    cards: List<PokemonVisualCard>,
    candidatesByMarketName: Map<String, List<CatalogSearchResult>>,
): String? {
    cards.firstOrNull()?.set?.cardmarketExpansionId?.let { return it }
    val matches = cards.asSequence().flatMap { card ->
        val name = card.marketSearchName ?: card.name
        candidatesByMarketName[name].orEmpty().asSequence()
            .filter { cardmarketNameMatches(it.name, name) }
            .map { candidate -> card.id to candidate.expansionId }
    }.groupBy({ it.second }, { it.first }).mapValues { (_, ids) -> ids.distinct().size }
    val ranked = matches.entries.sortedByDescending { it.value }
    val best = ranked.firstOrNull() ?: return null
    val requiredEvidence = minOf(12, maxOf(3, cards.size / 10))
    val second = ranked.getOrNull(1)?.value ?: 0
    return best.key.takeIf { best.value >= requiredEvidence && best.value >= second + 2 }
}

/** Cardmarket may append attacks/printing information, but the actual base name must agree. */
internal fun cardmarketNameMatches(cardmarketName: String, sourceName: String): Boolean {
    val source = sourceName.trim()
    val product = cardmarketName.substringBefore('[').trim()
    return canonicalCardName(product) == canonicalCardName(source) ||
        product.startsWith("$source (", ignoreCase = true) ||
        product.startsWith("$source -", ignoreCase = true) ||
        product.startsWith("$source:", ignoreCase = true)
}

/** Builds one exact physical printing without ever filling a missing source price. */
internal fun scannerCatalogResult(
    card: PokemonVisualCard,
    printing: PokemonPrintVariant,
    marketProduct: CatalogSearchResult?,
): CatalogSearchResult {
    // TCGdex identifies the card once. Finish and variant stay independent identity dimensions
    // in the collection entry, so manual selection and scanner capture deduplicate cross-path.
    val sourceCardId = card.id.lowercase()
    val sourceFields = marketProduct?.copy(
        name = card.name,
        visualSetName = card.set.name,
        collectorNumber = card.number,
        imageUrl = card.largeImageUrl,
        language = if (card.hasGermanLocalizedName) "GERMAN" else "SOURCE_FALLBACK",
        finish = printing.finish,
        variant = printing.variant,
        rarity = card.rarity,
        trendPriceEur = marketProduct.realTrendFor(printing.finish),
        mainSetSize = card.set.printedTotal,
        setCode = PokemonSetRegistry.metadata(card.set).setCode,
        marketSearchName = card.marketSearchName,
        sourceSetId = card.set.id.lowercase(),
        sourceCardId = sourceCardId,
    )
    if (sourceFields != null) return sourceFields

    val localId = -(("${card.set.id}|${card.id}|${card.number}|${printing.id}".hashCode().toLong() and 0x7fff_ffffL) + 1L)
    return CatalogSearchResult(
        productId = localId,
        name = card.name,
        expansionId = card.set.id,
        trendPriceEur = null,
        currency = null,
        visualSetName = card.set.name,
        collectorNumber = card.number,
        imageUrl = card.largeImageUrl,
        language = if (card.hasGermanLocalizedName) "GERMAN" else "SOURCE_FALLBACK",
        finish = printing.finish,
        variant = printing.variant,
        rarity = card.rarity,
        hasMarketProduct = false,
        mainSetSize = card.set.printedTotal,
        setCode = PokemonSetRegistry.metadata(card.set).setCode,
        marketSearchName = card.marketSearchName,
        sourceSetId = card.set.id.lowercase(),
        sourceCardId = sourceCardId,
    )
}

/** Normal and Holo are distinct Cardmarket source observations; neither is fabricated. */
internal fun CatalogSearchResult.realTrendFor(finish: String): Double? =
    if (finish.contains("holo", ignoreCase = true)) holoTrendPriceEur else trendPriceEur

private fun canonicalCardName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .lowercase()
    .replace("ß", "ss")
    .filter(Char::isLetterOrDigit)

private fun canonicalCollectorNumber(value: String): String = value
    .substringBefore('/')
    .filter(Char::isLetterOrDigit)
    .trimStart('0')
    .ifBlank { "0" }
