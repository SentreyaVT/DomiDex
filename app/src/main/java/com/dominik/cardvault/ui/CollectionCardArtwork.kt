package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonCardArtwork
import com.dominik.cardvault.data.catalog.resolvePokemonCardArtwork
import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CollectionCard

/** Display-only repair. Never writes collection, price, possession or sync state. */
internal val CollectionCard.displayImageUrl: String?
    get() {
        // A user's English/Japanese card must not acquire a German-language image.
        if (language != CardLanguage.GERMAN) return imageUrl
        val primary = imageUrl.orEmpty()
        return resolvePokemonCardArtwork(
            sourceSetId = sourceSetId,
            sourceCardId = sourceCardId,
            sourceLocalId = null,
            primary = PokemonCardArtwork(primary, primary),
        ).largeImageUrl.takeIf(String::isNotBlank) ?: imageUrl
    }
