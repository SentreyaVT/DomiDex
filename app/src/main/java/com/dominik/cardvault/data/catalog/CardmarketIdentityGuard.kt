package com.dominik.cardvault.data.catalog

/**
 * Source-declared IDs can be wrong. Quarantine only reviewed exact card/product pairs;
 * this never guesses a replacement from the product's name, order or current price.
 * Reviewed 2026-09-22 against the frozen TCGdex details and Cardmarket product catalog.
 * Mirror: DomiDex-Web/lib/cardmarket-identity.ts and diagnostics/cardmarket-source-quarantine.json.
 */
internal fun isQuarantinedCardmarketIdentity(sourceCardId: String?, productId: Long?): Boolean {
    val identity = sourceCardId?.trim()?.lowercase() ?: return false
    val stem = when (productId) {
        670816L -> "swsh11-201" // Giratina VSTAR cannot use the Giratina V (130) product.
        781858L -> "sv03-062" // Palafin cannot use the Frogadier (057) Cosmos product.
        else -> return false
    }
    val setId = stem.substringBeforeLast('-')
    val source = identity.removePrefix("tcgdex-$setId-")
    return source == stem || source.startsWith("$stem-")
}

/** Same exact guard inside existing Room price joins. No stored identities/quantities are changed. */
internal const val SAFE_COLLECTION_PRICE_LINK_SQL = """
    NOT (
      (COALESCE(e.externalProductId,c.externalProductId,0)=670816 AND (
        LOWER(TRIM(COALESCE(NULLIF(TRIM(e.sourceCardId),''),c.id))) IN ('swsh11-201','tcgdex-swsh11-swsh11-201') OR
        LOWER(TRIM(COALESCE(NULLIF(TRIM(e.sourceCardId),''),c.id))) GLOB 'swsh11-201-*' OR
        LOWER(TRIM(COALESCE(NULLIF(TRIM(e.sourceCardId),''),c.id))) GLOB 'tcgdex-swsh11-swsh11-201-*'
      )) OR
      (COALESCE(e.externalProductId,c.externalProductId,0)=781858 AND (
        LOWER(TRIM(COALESCE(NULLIF(TRIM(e.sourceCardId),''),c.id))) IN ('sv03-062','tcgdex-sv03-sv03-062') OR
        LOWER(TRIM(COALESCE(NULLIF(TRIM(e.sourceCardId),''),c.id))) GLOB 'sv03-062-*' OR
        LOWER(TRIM(COALESCE(NULLIF(TRIM(e.sourceCardId),''),c.id))) GLOB 'tcgdex-sv03-sv03-062-*'
      ))
    )
"""
