package com.dominik.cardvault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import com.dominik.cardvault.R
import com.dominik.cardvault.data.catalog.PokemonVisualCard

/** One same-card image retry only; no catalogue request, identity change or persistent state. */
@Composable
internal fun CatalogCardArtwork(card: PokemonVisualCard, modifier: Modifier = Modifier) {
    // A disposed card/request cannot advance the next card's fallback state.
    key(card.id, card.smallImageUrl, card.largeImageUrl) {
        val small = card.smallImageUrl.takeIf(String::isNotBlank)
        val large = card.largeImageUrl.takeIf(String::isNotBlank)
        var useLarge by remember { mutableStateOf(small == null) }
        AsyncImage(
            model = if (useLarge) large else small,
            contentDescription = stringResource(R.string.catalog_card_image_description, card.name, card.number),
            modifier = modifier,
            onError = {
                if (!useLarge && large != null && large != small) useLarge = true
            },
        )
    }
}
