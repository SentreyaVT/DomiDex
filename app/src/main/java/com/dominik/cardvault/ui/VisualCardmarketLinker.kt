package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonPrintVariant
import com.dominik.cardvault.data.catalog.PokemonSetRegistry
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.data.catalog.isPokemonPocketSet
import com.dominik.cardvault.data.catalog.isQuarantinedCardmarketIdentity
import com.dominik.cardvault.model.CatalogSearchResult
import com.dominik.cardvault.model.CollectionCard
import com.dominik.cardvault.model.CollectionRepository
import java.text.Normalizer

/**
 * Shared TCGdex -> Cardmarket bridge used by both manual set selection and the scanner.
 *
 * Cardmarket's compact public catalogue deliberately has no collector number.  A product is
 * therefore linkable only through an exact source/maintained product identity. Equal names,
 * expansion membership and numeric product order do not prove a collector/printing identity.
 */
@Suppress("UNUSED_PARAMETER") // Keep existing callers; these hints cannot prove product identity.
internal fun visualCardmarketProducts(
    card: PokemonVisualCard,
    cardsInSet: List<PokemonVisualCard>,
    candidates: List<CatalogSearchResult>,
    resolvedExpansionId: String? = null,
): List<CatalogSearchResult> {
    // TCG Pocket is a digital catalogue. Even strong name/expansion evidence or a
    // stale third-party id must never turn one of its cards into a physical price.
    if (card.set.isPokemonPocketSet()) return emptyList()
    val declaredIds = card.printVariants.mapNotNull { it.cardmarketProductId }.distinct()
    if (declaredIds.isNotEmpty()) {
        // A source-declared product is stronger than a guessed expansion or name/order.
        // Preserve every declared physical printing: Normal and Reverse may share
        // one product id, while stamps/foils may have separate product ids.
        return card.printVariants.mapNotNull { printing ->
            val productId = printing.cardmarketProductId ?: return@mapNotNull null
            if (isQuarantinedCardmarketIdentity(card.id, productId)) return@mapNotNull null
            val product = candidates.firstOrNull { it.hasMarketProduct && it.productId == productId }
                ?: return@mapNotNull null
            product.copy(
                sourceSetId = card.set.id.lowercase(), sourceCardId = card.id.lowercase(),
                visualSetName = card.set.name, collectorNumber = card.number,
                imageUrl = card.largeImageUrl, marketSearchName = card.marketSearchName,
                finish = printing.finish, variant = printing.variant,
                trendPriceEur = product.realTrendFor(printing.finish),
            )
        }.distinctBy { "${it.productId}|${it.finish}|${it.variant}" }
    }
    return emptyList()
}

/** Select one exact source printing without falling through to another special product. */
internal fun visualCardmarketProductForPrinting(
    printing: PokemonPrintVariant,
    products: List<CatalogSearchResult>,
): CatalogSearchResult? {
    val productId = printing.cardmarketProductId ?: return null
    return products.firstOrNull { product ->
        product.productId == productId &&
            product.finish.equals(printing.finish, ignoreCase = true) &&
            product.variant.equals(printing.variant, ignoreCase = true)
    }
}

/** The normal Binder printing is the default; a priced stamp never replaces it. */
internal fun preferredVisualCardmarketProduct(
    card: PokemonVisualCard,
    products: List<CatalogSearchResult>,
): CatalogSearchResult? {
    val preferred = card.binderPrintVariants().firstOrNull() ?: return null
    return visualCardmarketProductForPrinting(preferred, products)
}

/** Resolves a grey Binder slot only through its stable source identity and verified printing. */
internal fun binderReferenceCardmarketProducts(
    reference: CollectionCard,
    sourceCard: PokemonVisualCard,
    cardsInSet: List<PokemonVisualCard>,
    candidates: List<CatalogSearchResult>,
): List<CatalogSearchResult> {
    if (reference.sourceSetId != sourceCard.set.id.lowercase() || reference.sourceCardId != sourceCard.id.lowercase()) return emptyList()
    val matchingPrintings = sourceCard.printVariants.filter {
        it.finish.equals(reference.finish, true) && it.variant.equals(reference.variant, true)
    }
    val declaredSource = if (sourceCard.printVariants.any { it.cardmarketProductId != null }) {
        if (matchingPrintings.none { it.cardmarketProductId != null }) return emptyList()
        sourceCard.copy(printVariants = matchingPrintings)
    } else sourceCard
    return visualCardmarketProducts(declaredSource, cardsInSet, candidates, reference.marketExpansionId).map { product ->
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

/** Use the existing local price guide by exact product ID, even when titles differ. */
internal suspend fun loadVisualCardmarketCandidates(
    card: PokemonVisualCard, repository: CollectionRepository,
): List<CatalogSearchResult> {
    if (card.set.isPokemonPocketSet()) return emptyList()
    val ids = card.printVariants.mapNotNull { it.cardmarketProductId }
        .filterNot { isQuarantinedCardmarketIdentity(card.id, it) }.distinct()
    return if (ids.isNotEmpty()) repository.catalogProducts(ids)
    else emptyList()
}

/**
 * A maintained expansion bridge or one unambiguous expansion from exact declared product IDs.
 * Name-voting cannot establish physical set membership and is deliberately excluded.
 */
internal fun resolveCardmarketExpansionId(
    cards: List<PokemonVisualCard>,
    candidatesByMarketName: Map<String, List<CatalogSearchResult>>,
): String? {
    if (cards.any { it.set.isPokemonPocketSet() }) return null
    cards.firstOrNull()?.set?.cardmarketExpansionId?.let { return it }
    val declaredIds = cards.flatMap { it.printVariants }.mapNotNull { it.cardmarketProductId }.toSet()
    return candidatesByMarketName.values.asSequence().flatten()
        .filter { it.hasMarketProduct && it.productId in declaredIds }
        .map { it.expansionId }.filter { it.isNotBlank() && it != "unknown" }
        .distinct().toList().singleOrNull()
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

/** Pokémon Holo is the regular product; only Reverse Holo uses the alternate price field. */
internal fun CatalogSearchResult.realTrendFor(finish: String): Double? =
    (if (finish.contains("reverse", ignoreCase = true)) holoTrendPriceEur else trendPriceEur)
        ?.takeIf { it.isFinite() && it > 0.0 }

private fun canonicalCardName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .lowercase()
    .replace("ß", "ss")
    .filter(Char::isLetterOrDigit)
