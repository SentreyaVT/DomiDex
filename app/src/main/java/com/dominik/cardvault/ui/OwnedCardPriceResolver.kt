package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.data.catalog.isQuarantinedCardmarketIdentity
import com.dominik.cardvault.model.CatalogSearchResult
import com.dominik.cardvault.model.CollectionCard
import com.dominik.cardvault.model.CollectionRepository

/** A source product identifies one printing, not every editable finish/edition of its card. */
internal fun CatalogSearchResult.matchesManualPrinting(finish: String, variant: String): Boolean =
    !this.finish.isNullOrBlank() && !this.variant.isNullOrBlank() &&
        this.finish.trim().equals(finish.trim(), true) && this.variant.trim().equals(variant.trim(), true)

/** Read-only lookup shared by every owned card, without a set/name-based substitute. */
internal suspend fun ownedCardPriceProducts(
    card: CollectionCard,
    repository: CollectionRepository,
    catalog: PokemonVisualCatalog,
    availableSourceCards: List<PokemonVisualCard>,
): List<CatalogSearchResult> {
    card.externalProductId?.let { productId ->
        if (isQuarantinedCardmarketIdentity(card.sourceCardId ?: card.id, productId)) return emptyList()
        return repository.catalogProducts(listOf(productId))
            .filter { it.hasMarketProduct && it.productId == productId }
            .map { it.copy(trendPriceEur = it.realTrendFor(card.finish)) }
    }
    val setId = card.sourceSetId?.takeIf(String::isNotBlank) ?: return emptyList()
    val cardId = card.sourceCardId?.takeIf(String::isNotBlank) ?: return emptyList()
    val source = availableSourceCards.singleOrNull { referenceMatchesSourceCard(card, it) }
        ?: catalog.findExact(setId, card.number).singleOrNull {
            it.id.equals(cardId, true) && referenceMatchesSourceCard(card, it)
        }
        ?: return emptyList()
    val detailed = catalog.withDeclaredPrintVariants(source)
    return binderReferenceCardmarketProducts(
        card, detailed, listOf(detailed), loadVisualCardmarketCandidates(detailed, repository),
    )
}
