package com.dominik.cardvault.data.catalog

import java.util.Locale

/** Metadata only. Resolving artwork never reads the network or changes a collection. */
internal data class PokemonCardArtwork(val smallImageUrl: String, val largeImageUrl: String)

internal data class PokemonCardArtworkCandidate(
    val sourceSetId: String,
    val sourceCardId: String,
    val sourceLocalId: String?,
    val artwork: PokemonCardArtwork,
)

/** Each replacement and each unavailable URL belongs to this exact source identity. */
internal data class PokemonCardArtworkOverride(
    val sourceSetId: String,
    val sourceCardId: String,
    val sourceLocalId: String,
    val unavailableUrls: Set<String> = emptySet(),
    /** Exact older language-fallback URLs which this audited replacement supersedes. */
    val replaceableUrls: Set<String> = emptySet(),
    val replacement: PokemonCardArtwork? = null,
)

// Exact, decoded source files only. Corrected URLs and user photos still win.
private val verifiedPokemonCardArtworkOverrides =
    auditedPokemonCardArtworkOverrides() + additionalPokemonCardArtworkOverrides()

/** Built once. Duplicate/ambiguous source identities are not a last-writer-wins policy. */
internal class PokemonCardArtworkRegistry(overrides: List<PokemonCardArtworkOverride>) {
    private val byCard: Map<Pair<String, String>, PokemonCardArtworkOverride> = buildMap {
        overrides.forEach { original ->
            val entry = original.copy(
                sourceSetId = original.sourceSetId.lowercase(Locale.ROOT),
                sourceCardId = original.sourceCardId.lowercase(Locale.ROOT),
                sourceLocalId = original.sourceLocalId.lowercase(Locale.ROOT),
                unavailableUrls = original.unavailableUrls.toSet(),
                replaceableUrls = original.replaceableUrls.toSet(),
            )
            require(entry.sourceSetId.isNotBlank() && entry.sourceLocalId.isNotBlank() &&
                entry.sourceCardId.startsWith("${entry.sourceSetId}-") &&
                entry.sourceCardId.length > entry.sourceSetId.length + 1) { "Invalid artwork source identity" }
            require((entry.unavailableUrls + entry.replaceableUrls).none(String::isBlank)) { "Blank artwork URL policy" }
            check(put(entry.sourceSetId to entry.sourceCardId, entry) == null) { "Duplicate artwork source identity" }
        }
    }

    internal fun entry(set: String, card: String): PokemonCardArtworkOverride? =
        byCard[set.lowercase(Locale.ROOT) to card.lowercase(Locale.ROOT)]
}

private val verifiedPokemonCardArtworkRegistry = PokemonCardArtworkRegistry(verifiedPokemonCardArtworkOverrides)

private data class ArtworkIdentity(val set: String, val card: String, val local: String)

private fun artworkIdentity(
    set: String?, card: String?, local: String?, registry: PokemonCardArtworkRegistry,
): ArtworkIdentity? {
    if (set.isNullOrBlank() || card.isNullOrBlank()) return null
    val normalizedSet = set.lowercase(Locale.ROOT)
    val normalizedCard = card.lowercase(Locale.ROOT)
    val prefix = "$normalizedSet-"
    if (!normalizedCard.startsWith(prefix)) return null
    val suffix = normalizedCard.removePrefix(prefix).takeIf(String::isNotBlank) ?: return null
    // Collection storage normalizes source IDs. Never derive an identity from a
    // display name, price product, or the historic collector number of a Classic.
    val explicit = registry.entry(normalizedSet, normalizedCard)
    val normalizedLocal = local?.takeIf(String::isNotBlank)?.lowercase(Locale.ROOT)
        ?: explicit?.sourceLocalId?.lowercase(Locale.ROOT) ?: suffix
    if (normalizedLocal != suffix && normalizedLocal != explicit?.sourceLocalId?.lowercase(Locale.ROOT)) return null
    if (explicit == null && !normalizedLocal.matches(Regex("[a-z0-9]+"))) return null
    return ArtworkIdentity(normalizedSet, normalizedCard, normalizedLocal)
}

internal fun resolvePokemonCardArtwork(
    sourceSetId: String?,
    sourceCardId: String?,
    sourceLocalId: String?,
    primary: PokemonCardArtwork,
    fallback: PokemonCardArtworkCandidate? = null,
    registry: PokemonCardArtworkRegistry = verifiedPokemonCardArtworkRegistry,
): PokemonCardArtwork {
    val identity = artworkIdentity(sourceSetId, sourceCardId, sourceLocalId, registry) ?: return primary
    val override = registry.entry(identity.set, identity.card)?.takeIf { it.sourceLocalId == identity.local }
    val sameCard = fallback?.takeIf {
        artworkIdentity(it.sourceSetId, it.sourceCardId, it.sourceLocalId, registry) == identity
    }?.artwork
    val official = if (identity.local.matches(Regex("[0-9]{1,3}"))) {
        officialPokemonCardImageUrl(identity.set, identity.local).orEmpty()
    } else ""
    fun select(current: String, alternative: String?, verified: String?): String {
        if (current.isNotBlank() && current !in override?.unavailableUrls.orEmpty() &&
            current !in override?.replaceableUrls.orEmpty()) return current
        return sequenceOf(verified, alternative, official)
            .filterNotNull().firstOrNull { it.isNotBlank() && it !in override?.unavailableUrls.orEmpty() }
            ?: current
    }
    return PokemonCardArtwork(
        select(primary.smallImageUrl, sameCard?.smallImageUrl, override?.replacement?.smallImageUrl),
        select(primary.largeImageUrl, sameCard?.largeImageUrl, override?.replacement?.largeImageUrl),
    )
}

internal fun needsPokemonCardArtwork(
    sourceSetId: String?, sourceCardId: String?, sourceLocalId: String?, artwork: PokemonCardArtwork,
    registry: PokemonCardArtworkRegistry = verifiedPokemonCardArtworkRegistry,
): Boolean {
    val identity = artworkIdentity(sourceSetId, sourceCardId, sourceLocalId, registry) ?: return false
    val override = registry.entry(identity.set, identity.card)?.takeIf { it.sourceLocalId == identity.local }
    return artwork.smallImageUrl.isBlank() || artwork.largeImageUrl.isBlank() ||
        artwork.smallImageUrl in override?.unavailableUrls.orEmpty() || artwork.largeImageUrl in override?.unavailableUrls.orEmpty() ||
        artwork.smallImageUrl in override?.replaceableUrls.orEmpty() || artwork.largeImageUrl in override?.replaceableUrls.orEmpty()
}

internal fun PokemonVisualCard.artwork() = PokemonCardArtwork(smallImageUrl, largeImageUrl)

internal fun PokemonVisualCard.artworkCandidate() =
    PokemonCardArtworkCandidate(set.id, id, sourceLocalId, artwork())
