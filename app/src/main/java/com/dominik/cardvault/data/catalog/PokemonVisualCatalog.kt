package com.dominik.cardvault.data.catalog

import com.dominik.cardvault.data.WachsendesChaosMasterSet
import com.squareup.moshi.JsonReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.BufferedSource
import okio.buffer
import okio.source
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class PokemonSet(
    val id: String,
    val name: String,
    val series: String,
    val printedTotal: Int,
    val total: Int,
    val releaseDate: String,
    val logoUrl: String?,
    val ptcgoCode: String?,
    /** Cardmarket's stable expansion id when it has been source-verified. */
    val cardmarketExpansionId: String? = CardmarketExpansionRegistry.idFor(id),
    /** Stable upstream series identity; `tcgp` denotes the digital Pocket catalogue. */
    val seriesId: String? = null,
)

internal fun PokemonSet.isPokemonPocketSet(): Boolean = seriesId.equals("tcgp", ignoreCase = true)

/** A virtual subset can have no physical denominator while still containing real cards. */
internal fun PokemonSet.displayCardCount(): Int = printedTotal.takeIf { it > 0 } ?: total

/**
 * Cardmarket's public product/price downloads expose an expansion id but not
 * the expansion name or collector number. Keep only verified bridges here;
 * an unknown bridge deliberately remains unresolved instead of guessing.
 */
private object CardmarketExpansionRegistry {
    private val byTcgDexId = mapOf(
        // Cardmarket: Mega Evolution / MEG, source-verified against the public
        // catalogue's 188 corresponding English printings.
        "me01" to "6209",
        // Cardmarket: Perfect Order / POR, source-verified against the public
        // catalogue's Rowlet, Dartrix and Decidueye ex printings.
        "me03" to "6443",
        // Cardmarket expansion 6601 contains the 158-card 30th Celebration
        // main set plus the 30-card Classic Collection. The stable TCGdex
        // source ids remain separate even though Cardmarket groups both.
        "30th" to "6601",
        "30th-c" to "6601",
    )

    fun idFor(tcgDexId: String): String? = byTcgDexId[tcgDexId.lowercase(Locale.ROOT)]
}

/**
 * Exact Cardmarket identities verified against the public expansion-6601
 * product catalogue and the cards' English attack/ability signatures.
 * These maintained exact bridges never depend on inferred name/product ordering.
 */
private object CardmarketProductRegistry {
    private val bySourceCardId: Map<String, Long> = buildMap {
        // Pikachu Rare 1/30-30/30 occupy source collector numbers 023-052.
        (23..52).forEach { number -> put("30th-${number.toString().padStart(3, '0')}", 907_607L + number) }
        put("30th-065", 907_672L) // Mew — Psychic
        put("30th-121", 907_728L) // Lugia — Elemental Blast
        put("30th-c-003", 907_949L) // Metagross δ
        put("30th-c-010", 907_951L) // Uxie
        put("30th-c-011", 907_952L) // Crobat G
        put("30th-c-014", 907_939L) // Base Set Pikachu
        put("30th-c-021", 907_956L) // N — one-character title needs an exact bridge
        put("30th-c-022", 907_950L) // Palkia LV.X — avoids the main-set namesake
        put("30th-c-029", 907_945L) // Aquapolis Lugia
    }

    fun idFor(sourceCardId: String): Long? = bySourceCardId[sourceCardId.lowercase(Locale.ROOT)]
}

internal fun verifiedCardmarketProductId(sourceCardId: String): Long? =
    CardmarketProductRegistry.idFor(sourceCardId)

private fun attachVerifiedCardmarketProduct(
    sourceCardId: String,
    variants: List<PokemonPrintVariant>,
): List<PokemonPrintVariant> {
    val productId = verifiedCardmarketProductId(sourceCardId) ?: return variants
    return variants.map { variant ->
        // A regular product bridge is not evidence for a stamp, foil or special edition.
        val ordinary = variant.variant in setOf("Standard", "Normal", "Holo", "Reverse Holo")
        if (variant.cardmarketProductId == null && ordinary) variant.copy(cardmarketProductId = productId) else variant
    }
}

/** A separately collectible printing of the same numbered card. */
data class PokemonPrintVariant(
    val id: String, val finish: String, val variant: String,
    /** Exact public source link, never inferred from a title or product order. */
    val cardmarketProductId: Long? = null,
)

data class PokemonVisualCard(
    val id: String,
    val name: String,
    val number: String,
    val rarity: String?,
    val smallImageUrl: String,
    val largeImageUrl: String,
    val set: PokemonSet,
    // The source lists actual available printings. A missing variant payload is
    // intentionally treated as one normal printing, never as a guessed holo.
    val printVariants: List<PokemonPrintVariant> = listOf(PokemonPrintVariant("normal", "Normal", "Standard")),
    /** Same source card in Cardmarket's commonly used English naming. */
    val marketSearchName: String? = null,
    /** False only when German metadata omitted this printing and source-backed English identity filled the gap. */
    val hasGermanLocalizedName: Boolean = true,
    /** Upstream slot within a subset when the physical card keeps a historic collector number. */
    val sourceLocalId: String? = null,
)

/** Every source set with the printed denominator remains eligible; none is cut off by list order. */
internal fun setsMatchingPrintedTotal(sets: List<PokemonSet>, printedTotal: Int): List<PokemonSet> =
    sets.filter { it.printedTotal == printedTotal }

/** German identities win; the same source id can supply a missing image, never a guessed card. */
internal fun mergeLocalizedSetCards(
    germanCards: List<PokemonVisualCard>,
    englishCards: List<PokemonVisualCard>,
): List<PokemonVisualCard> {
    val germanIds = germanCards.mapTo(hashSetOf(), PokemonVisualCard::id)
    val englishById = englishCards.associateBy(PokemonVisualCard::id)
    return (germanCards + englishCards.filterNot { it.id in germanIds })
        .distinctBy(PokemonVisualCard::id)
        .map { card ->
            val english = englishById[card.id]
            val officialFallback = officialPokemonCardImageUrl(card.set.id, card.sourceLocalId ?: card.number).orEmpty()
            card.copy(
                marketSearchName = english?.name,
                smallImageUrl = availableLocalizedImage(card.smallImageUrl, english?.smallImageUrl, officialFallback),
                largeImageUrl = availableLocalizedImage(card.largeImageUrl, english?.largeImageUrl, officialFallback),
            )
        }
}

/** Exact broken source URLs, confirmed by the dated full-catalog audit (2026-09-22).
 * The fallback comes from the same source card ID, never another card or a guessed number.
 * Do not replace a subsequently corrected upstream URL.
 */
private val unavailableGermanImageBases = setOf(
    "https://assets.tcgdex.net/de/bw/bw2/9",
    "https://assets.tcgdex.net/de/swsh/swsh12/047",
    "https://assets.tcgdex.net/de/sm/sm1/94",
    "https://assets.tcgdex.net/de/swsh/swsh10/162",
    "https://assets.tcgdex.net/de/sv/sv02/269",
)

private fun availableLocalizedImage(primary: String, english: String?, official: String): String {
    val unavailable = primary.substringBeforeLast('/') in unavailableGermanImageBases
    return if (primary.isBlank() || unavailable) english.orEmpty().ifBlank { official.ifBlank { primary } } else primary
}

/** Never add English-only sets or replace the German display name/count/identity. */
internal fun fillMissingSetLogos(german: List<PokemonSet>, english: List<PokemonSet>): List<PokemonSet> {
    val byId = english.associateBy(PokemonSet::id)
    return german.map { set ->
        if (set.needsLocalizedLogoFallback()) {
            val englishLogo = byId[set.id]?.takeUnless { it.needsLocalizedLogoFallback() }?.logoUrl
            set.copy(logoUrl = englishLogo ?: officialPokemonSetLogoUrl(set.id) ?: set.logoUrl)
        } else set
    }
}

private fun PokemonSet.needsLocalizedLogoFallback(): Boolean = logoUrl.isNullOrBlank() ||
    logoUrl.startsWith("https://assets.tcgdex.net/univ/") && logoUrl.substringAfterLast('/').startsWith("symbol.")

internal fun officialPokemonSetLogoUrl(setId: String): String? = when (setId.lowercase(Locale.ROOT)) {
    "30th", "30th-c" -> "https://www.pokemon.com/static-assets/content-assets/cms2-de-de/img/trading-card-game/series/me_series/30th/30th_logo_169_de.png"
    else -> null
}

/** Official remote fallback; no card bitmap is bundled into the APK. */
internal fun officialPokemonCardImageUrl(setId: String, localId: String): String? {
    val number = localId.filter(Char::isDigit).toIntOrNull() ?: return null
    return when (setId.lowercase(Locale.ROOT)) {
        "30th" -> number.takeIf { it in 1..158 }?.let {
            "https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/30TH/30TH_DE_$it.png"
        }
        "30th-c" -> number.takeIf { it in 1..30 }?.let {
            "https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/30TH-C/30TH-C_DE_$it.png"
        }
        else -> null
    }
}

/**
 * The Classic Collection source uses virtual positions 001-030, while the
 * cards themselves retain their historic collector numbers. These aliases
 * were verified against the official German card images.
 */
private val classic30thLocalIdByPrintedCollector = mapOf(
    "4/102" to "1", "5/109" to "2", "11/113" to "3", "11/101" to "4",
    "18/132" to "5", "19/109" to "6", "25/111" to "7", "33/181" to "8",
    "41/122" to "9", "43/146" to "10", "47/127" to "11", "50/185" to "12",
    "57/111" to "13", "58/102" to "14", "69/132" to "15", "85/124" to "16",
    "89/149" to "17", "94/102" to "18", "99/102" to "19", "100/102" to "20",
    "101/101" to "21", "106/106" to "22", "106/160" to "23", "106/105" to "24",
    "108/115" to "25", "269/264" to "26", "123/172" to "27", "138/202" to "28",
    "149/147" to "29", "203/193" to "30",
)

private val classic30thPrintedCollectorByLocalId =
    classic30thLocalIdByPrintedCollector.entries.associate { (printed, local) -> local to printed }

internal fun classic30thLocalIdForPrintedCollector(value: String): String? {
    val match = Regex("^\\s*0*(\\d{1,4})\\s*/\\s*0*(\\d{1,4})\\s*$").matchEntire(value) ?: return null
    val key = "${match.groupValues[1].toInt()}/${match.groupValues[2].toInt()}"
    return classic30thLocalIdByPrintedCollector[key]
}

internal fun classic30thPrintedCollectorForLocalId(value: String): String? {
    val local = value.filter(Char::isDigit).toIntOrNull()?.toString() ?: return null
    return classic30thPrintedCollectorByLocalId[local]
}

private fun cardImageUrl(value: String, quality: String): String {
    val base = value.trim().trimEnd('/')
    if (base.isEmpty()) return ""
    return if (base.substringBefore('?').substringAfterLast('/').contains('.')) base else "$base/$quality.webp"
}

/** Encode exactly one dynamic path segment, including an already encoded percent sign. */
internal fun encodeCatalogPathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

/** Maps only source-declared collectible printings to separate binder slots. */
internal fun pokemonPrintVariantForSourceKey(key: String): PokemonPrintVariant? = when (key.lowercase()) {
    "normal", "unlimited" -> PokemonPrintVariant(key, "Normal", "Standard")
    "holofoil", "unlimitedholofoil" -> PokemonPrintVariant(key, "Holo", "Holo")
    "reverseholofoil" -> PokemonPrintVariant(key, "Reverse Holo", "Reverse Holo")
    "1stedition" -> PokemonPrintVariant(key, "Normal", "1. Edition")
    "1steditionholofoil" -> PokemonPrintVariant(key, "Holo", "1. Edition Holo")
    else -> null // no speculative slot for non-collectible/unknown source keys
}

/**
 * German-first card identity adapter. TCGdex publishes a multilingual, MIT-licensed
 * card database; Cardmarket remains the separate, explicit source for prices.
 * No images or card text are bundled into the APK: the app only requests the
 * German metadata needed by the set the collector is actively browsing/scanning.
 */
class PokemonVisualCatalog internal constructor(
    private val metadataCache: PokemonCatalogMetadataCache,
    private val sourceOverride: ((String) -> BufferedSource)? = null,
) {
    constructor() : this(sharedMetadataCache)

    private companion object {
        val sharedMetadataCache = PokemonCatalogMetadataCache()
    }

    val isLicensedSourceConfigured: Boolean get() = true
    private val germanBaseUrl = "https://api.tcgdex.net/v2/de"
    private val englishBaseUrl = "https://api.tcgdex.net/v2/en"

    /** Only the small set index is warmed; card metadata stays demand-loaded. */
    suspend fun warmUp() { sets() }

    /** An explicit refresh also clears cached metadata used by other catalogue screens. */
    fun invalidate() { metadataCache.invalidate() }

    suspend fun sets(query: String = ""): List<PokemonSet> {
        val result = metadataCache.sets.getOrLoad("de") {
            withContext(Dispatchers.IO) {
                val german = read("$germanBaseUrl/sets") { reader ->
                    buildList { readArray(reader) { parseGermanSet(it)?.let(::add) } }
                }
                val english = if (german.any { it.needsLocalizedLogoFallback() }) catalogOrDefault(emptyList()) {
                    read("$englishBaseUrl/sets") { reader ->
                        buildList { readArray(reader) { parseGermanSet(it)?.let(::add) } }
                    }
                } else emptyList()
                fillMissingSetLogos(german, english)
                    .sortedWith(compareByDescending(::catalogSetRecencyKey).thenByDescending { it.releaseDate })
            }.also { loaded ->
                PokemonSetRegistry.registerSourceSets(loaded)
            }
        }
        return if (query.isBlank()) result
        else result.filter { it.name.contains(query, true) || it.series.contains(query, true) }
    }

    suspend fun cards(set: PokemonSet, query: String = ""): List<PokemonVisualCard> {
        val result = setCards(set).cards
        return if (query.isBlank()) result
        else result.filter { it.name.contains(query, true) || it.number.contains(query, true) }
    }

    private suspend fun setCards(set: PokemonSet): CatalogSetCards =
        metadataCache.cards.getOrLoad(set.id) {
            withContext(Dispatchers.IO) {
                val germanLoad = async(Dispatchers.IO) {
                    catalogOrDefault<List<PokemonVisualCard>?>(null) {
                        read("$germanBaseUrl/sets/${encodeCatalogPathSegment(set.id)}") { reader ->
                            parseSetCards(reader, set, hasGermanLocalizedName = true)
                        }
                    }
                }
                val englishLoad = async(Dispatchers.IO) {
                    catalogOrDefault<List<PokemonVisualCard>?>(null) {
                        read("$englishBaseUrl/sets/${encodeCatalogPathSegment(set.id)}") { reader ->
                            parseSetCards(reader, set, hasGermanLocalizedName = false)
                        }
                    }
                }
                val germanCards = germanLoad.await()
                val englishCards = englishLoad.await()
                check(germanCards != null || englishCards != null) { "Set-Katalog momentan nicht erreichbar" }
                // Preserve German-first identities and the source-backed English naming bridge.
                PokemonSetRegistry.registerSourceSets((germanCards.orEmpty() + englishCards.orEmpty()).map(PokemonVisualCard::set))
                val cards = mergeLocalizedSetCards(germanCards.orEmpty(), englishCards.orEmpty())
                    .sortedWith(compareBy({ (it.sourceLocalId ?: it.number).toIntOrNull() ?: Int.MAX_VALUE }, { it.sourceLocalId ?: it.number }))
                CatalogSetCards(cards, cards.groupBy { collectorIdentity(it.sourceLocalId ?: it.number) })
            }
        }

    /**
     * The set index deliberately stays small.  A card detail supplies the
     * source-declared Normal/Holo/Reverse printings for master-set slots.
     */
    suspend fun withDeclaredPrintVariants(card: PokemonVisualCard): PokemonVisualCard {
        val detail = metadataCache.details.getOrLoad(card.id) {
            withContext(Dispatchers.IO) {
                val encodedId = encodeCatalogPathSegment(card.id)
                catalogOrDefault<CatalogCardDetail?>(null) {
                    read("$germanBaseUrl/cards/$encodedId") { reader -> parseCardDetail(reader) }
                } ?: read("$englishBaseUrl/cards/$encodedId") { reader ->
                    // English detail data may fill a missing source payload, but
                    // it must not replace an already localized German rarity.
                    parseCardDetail(reader).let { fallback ->
                        fallback.copy(rarity = card.rarity ?: fallback.rarity)
                    }
                }
            }
        }
        return card.copy(
            rarity = detail.rarity ?: card.rarity,
            printVariants = attachVerifiedCardmarketProduct(
                card.id,
                detail.printVariants.ifEmpty { card.printVariants },
            ),
            smallImageUrl = card.smallImageUrl.ifBlank { cardImageUrl(detail.image.orEmpty(), "low") },
            largeImageUrl = card.largeImageUrl.ifBlank { cardImageUrl(detail.image.orEmpty(), "high") },
        )
    }

    suspend fun findExact(setIdentifier: String, collectorNumber: String): List<PokemonVisualCard> = withContext(Dispatchers.IO) {
        val code = PokemonSetRegistry.normalize(setIdentifier)
        val collectorKey = collectorIdentity(collectorNumber)
        if (collectorKey.isBlank()) return@withContext emptyList()
        PokemonSetRegistry.legacyOfflineSet(code)?.let { set ->
            val number = Regex("\\d{1,4}").find(collectorNumber)?.value?.toIntOrNull() ?: return@withContext emptyList()
            return@withContext WachsendesChaosMasterSet.cards.asSequence()
                .filter { Regex("\\d{1,4}").find(it.number)?.value?.toIntOrNull() == number }
                .groupBy { it.number }
                .values
                .map { printings ->
                    val primary = printings.first()
                    PokemonVisualCard(
                        primary.sourceCardId,
                        primary.name,
                        number.toString().padStart(3, '0'),
                        null,
                        primary.imageUrl,
                        primary.imageUrl,
                        set,
                        printings.map { printing ->
                            PokemonPrintVariant(printing.variant.lowercase().replace(' ', '-'), printing.finish, printing.variant)
                        }.distinctBy { it.id },
                    )
                }
                .toList()
        }
        val resolvedSets = PokemonSetRegistry.resolveAll(code, sets())
        if (resolvedSets.isEmpty()) return@withContext emptyList()
        val denominator = Regex("(\\d{1,4})\\s*/\\s*(\\d{1,4})").find(collectorNumber)
            ?.groupValues?.get(2)?.toIntOrNull()
        // 30C is shared by the main set and Classic Collection. If OCR loses
        // the denominator, the same numerator can represent two different
        // physical cards. Reject that incomplete identity instead of silently
        // preferring the main set; an explicit source-set selection is exact.
        if (denominator == null && resolvedSets.size > 1) return@withContext emptyList()
        val matchingSets = if (denominator == null) resolvedSets else resolvedSets.filter { set ->
            set.printedTotal == denominator ||
                (set.id.equals("30th-c", ignoreCase = true) && classic30thLocalIdForPrintedCollector(collectorNumber) != null)
        }
        if (matchingSets.isEmpty()) return@withContext emptyList()
        // The set payload intentionally stays lightweight and can omit rarity.
        // Only the one or few exact number candidates are expanded, off the UI
        // thread, to obtain source-declared rarity/finish metadata.
        matchingSets.flatMap { matchingSet ->
            val sourceCollectorKey = collectorIdentityForSourceLookup(collectorNumber, matchingSet.id)
            catalogOrDefault(emptyList()) { setCards(matchingSet).byCollectorNumber[sourceCollectorKey].orEmpty() }
                .map { candidate -> catalogOrDefault(candidate) { withDeclaredPrintVariants(candidate) } }
        }.distinctBy(PokemonVisualCard::id)
    }

    /**
     * Safe fallback when glare hides only the printed three-letter set code. The denominator is
     * still physical lower-border evidence and is used solely to reduce the source catalogue.
     * Name/artwork scoring must disambiguate the returned cards; this method never declares a
     * match. Classic Collection is the one explicit exception to ordinary set totals: its source
     * catalogue is numbered 001-030 while each physical card retains its historic denominator.
     */
    suspend fun findByPrintedTotalAndCollectorNumber(collectorNumber: String): List<PokemonVisualCard> = coroutineScope {
        val parts = Regex("(\\d{1,4})\\s*/\\s*(\\d{1,4})").find(collectorNumber)
            ?: return@coroutineScope emptyList()
        if (parts.groupValues[1].toIntOrNull() == null) return@coroutineScope emptyList()
        val printedTotal = parts.groupValues[2].toIntOrNull() ?: return@coroutineScope emptyList()
        val allSets = sets()
        val matchingSets = buildList {
            if (classic30thLocalIdForPrintedCollector(collectorNumber) != null) {
                addAll(allSets.filter { it.id.equals("30th-c", ignoreCase = true) })
            }
            // Keep the validated Classic alias ahead of the potentially many historic sets that
            // share this denominator. Candidate reduction is capped later; this guarantees that
            // the anniversary printing reaches name/artwork verification instead of being cut off.
            addAll(setsMatchingPrintedTotal(allSets, printedTotal))
        }.distinctBy { it.id.lowercase(Locale.ROOT) }
        val lookupSlots = Semaphore(permits = 4)
        matchingSets.map { set ->
            async(Dispatchers.IO) {
                lookupSlots.withPermit {
                    val sourceCollectorKey = collectorIdentityForSourceLookup(collectorNumber, set.id)
                    catalogOrDefault(emptyList()) { setCards(set).byCollectorNumber[sourceCollectorKey].orEmpty() }
                        .map { candidate -> catalogOrDefault(candidate) { withDeclaredPrintVariants(candidate) } }
                }
            }
        }.awaitAll().flatten()
    }

    private fun <T> read(url: String, block: (JsonReader) -> T): T {
        sourceOverride?.let { return JsonReader.of(it(url)).use(block) }
        val connection = (java.net.URL(url).openConnection() as HttpURLConnection).apply {
            useCaches = false
            connectTimeout = 12_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "DomiDex/1.0 private Android app")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
        }
        try {
            if (connection.responseCode !in 200..299) error("Katalogdienst: HTTP ${connection.responseCode}")
            return JsonReader.of(connection.inputStream.source().buffer()).use(block)
        } finally { connection.disconnect() }
    }

    private fun readArray(reader: JsonReader, item: (JsonReader) -> Unit) {
        reader.beginArray(); while (reader.hasNext()) item(reader); reader.endArray()
    }

    private fun parseGermanSet(reader: JsonReader): PokemonSet? {
        var id = ""; var name = ""; var total = 0; var official = 0; var logo: String? = null; var symbol: String? = null; var releaseDate = ""; var abbreviation: String? = null; var seriesId: String? = null; var seriesName: String? = null
        reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
            "id" -> id = reader.nextString(); "name" -> name = reader.nextString()
            "logo" -> logo = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else assetImageUrl(reader.nextString())
            // Older German TCGdex records often expose only a set symbol. It is
            // still the official visual identifier and should never leave the
            // picker with an empty icon.
            "symbol" -> symbol = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else assetImageUrl(reader.nextString())
            "releaseDate" -> releaseDate = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); "" } else reader.nextString()
            "abbreviation" -> {
                reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
                    "official" -> abbreviation = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else reader.nextString()
                    else -> reader.skipValue()
                }; reader.endObject()
            }
            "serie" -> {
                reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
                    "id" -> seriesId = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else reader.nextString()
                    "name" -> seriesName = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else reader.nextString()
                    else -> reader.skipValue()
                }; reader.endObject()
            }
            "cardCount" -> { reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) { "total" -> total = reader.nextInt(); "official" -> official = reader.nextInt(); else -> reader.skipValue() }; reader.endObject() }
            else -> reader.skipValue()
        }
        reader.endObject()
        return if (id.isBlank() || name.isBlank()) null else PokemonSet(
            id, name, seriesName?.takeIf(String::isNotBlank) ?: "Deutsch · TCGdex",
            official, total.coerceAtLeast(official), releaseDate,
            logo ?: symbol ?: officialPokemonSetLogoUrl(id), abbreviation,
            seriesId = seriesId,
        )
    }

    private fun parseSetCards(
        reader: JsonReader,
        set: PokemonSet,
        hasGermanLocalizedName: Boolean,
    ): List<PokemonVisualCard> {
        val cards = mutableListOf<PokemonVisualCard>()
        var abbreviation = set.ptcgoCode
        var seriesId = set.seriesId
        reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
            "cards" -> readArray(reader) {
                parseCardBrief(it, set, hasGermanLocalizedName)?.let(cards::add)
            }
            // The lightweight /sets index intentionally omits this field. The
            // set detail is authoritative and lets the scanner learn every
            // currently published code without accepting arbitrary OCR text.
            "abbreviation" -> {
                reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
                    "official" -> abbreviation = if (reader.peek() == JsonReader.Token.NULL) {
                        reader.nextNull<Unit>(); null
                    } else reader.nextString()
                    else -> reader.skipValue()
                }; reader.endObject()
            }
            "serie" -> {
                reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
                    "id" -> seriesId = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else reader.nextString()
                    else -> reader.skipValue()
                }; reader.endObject()
            }
            else -> reader.skipValue()
        }
        reader.endObject()
        val detailedSet = set.copy(
            ptcgoCode = abbreviation?.takeIf(String::isNotBlank) ?: set.ptcgoCode,
            seriesId = seriesId?.takeIf(String::isNotBlank) ?: set.seriesId,
        )
        return cards.map { it.copy(set = detailedSet) }
    }

    private fun parseCardBrief(
        reader: JsonReader,
        set: PokemonSet,
        hasGermanLocalizedName: Boolean,
    ): PokemonVisualCard? {
        var id = ""; var name = ""; var number = ""; var rarity: String? = null; var image = ""
        reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
            "id" -> id = reader.nextString(); "name" -> name = reader.nextString(); "localId" -> number = reader.nextString()
            "rarity" -> rarity = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else reader.nextString()
            "image" -> image = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); "" } else reader.nextString()
            else -> reader.skipValue()
        }
        reader.endObject()
        val lowImage = cardImageUrl(image, "low")
        val highImage = cardImageUrl(image, "high")
        val fallbackImage = officialPokemonCardImageUrl(set.id, number).orEmpty()
        val printedCollector = if (set.id.equals("30th-c", ignoreCase = true)) {
            classic30thPrintedCollectorForLocalId(number) ?: number
        } else number
        val defaultVariants = attachVerifiedCardmarketProduct(
            id,
            listOf(PokemonPrintVariant("normal", "Normal", "Standard")),
        )
        return if (id.isBlank() || name.isBlank()) null else PokemonVisualCard(
            id, name, printedCollector, rarity, lowImage.ifBlank { fallbackImage }, highImage.ifBlank { fallbackImage }, set,
            defaultVariants,
            hasGermanLocalizedName = hasGermanLocalizedName,
            sourceLocalId = number,
        )
    }

    private fun parseCardDetail(reader: JsonReader): CatalogCardDetail {
        val result = mutableListOf<PokemonPrintVariant>()
        var rarity: String? = null
        var image: String? = null
        reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
            "image" -> image = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else reader.nextString()
            "rarity" -> rarity = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else reader.nextString()
            "variants_detailed" -> readArray(reader) { variantReader ->
                var id = ""; var type = ""; var subtype = ""; var size = ""; var foil = ""
                val stamps = mutableListOf<String>()
                var cardmarketProductId: Long? = null
                variantReader.beginObject(); while (variantReader.hasNext()) when (variantReader.nextName()) {
                    "variantId" -> id = variantReader.nextString()
                    "type" -> type = variantReader.nextString()
                    "subtype" -> subtype = if (variantReader.peek() == JsonReader.Token.NULL) { variantReader.nextNull<Unit>(); "" } else variantReader.nextString()
                    "size" -> size = variantReader.nextString()
                    "foil" -> foil = if (variantReader.peek() == JsonReader.Token.NULL) { variantReader.nextNull<Unit>(); "" } else variantReader.nextString()
                    "stamp" -> when (variantReader.peek()) {
                        JsonReader.Token.BEGIN_ARRAY -> {
                            variantReader.beginArray()
                            while (variantReader.hasNext()) {
                                if (variantReader.peek() == JsonReader.Token.STRING) stamps += variantReader.nextString()
                                else variantReader.skipValue()
                            }
                            variantReader.endArray()
                        }
                        JsonReader.Token.STRING -> stamps += variantReader.nextString()
                        else -> variantReader.skipValue()
                    }
                    "thirdParty" -> if (variantReader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                        variantReader.beginObject()
                        while (variantReader.hasNext()) {
                            if (variantReader.nextName() == "cardmarket" && variantReader.peek() == JsonReader.Token.NUMBER) {
                                cardmarketProductId = variantReader.nextLong().takeIf { it > 0L }
                            } else variantReader.skipValue()
                        }
                        variantReader.endObject()
                    } else variantReader.skipValue()
                    else -> variantReader.skipValue()
                }; variantReader.endObject()
                val finish = when {
                    type.contains("reverse", true) -> "Reverse Holo"
                    type.contains("holo", true) -> "Holo"
                    type.equals("normal", true) -> "Normal"
                    else -> type.ifBlank { "Normal" }
                }
                val normalizedStamps = stamps.map(String::trim).filter(String::isNotEmpty)
                    .distinctBy { it.lowercase(Locale.ROOT) }.sortedBy { it.lowercase(Locale.ROOT) }
                val sourceVariant = buildList {
                    add(size.takeIf(String::isNotBlank) ?: finish)
                    subtype.trim().takeIf(String::isNotEmpty)?.let { add("Untertyp: $it") }
                    normalizedStamps.takeIf { it.isNotEmpty() }?.let { add("Stempel: ${it.joinToString(" + ")}") }
                    foil.trim().takeIf(String::isNotEmpty)?.let { add("Folie: $it") }
                }.joinToString(" · ")
                if (id.isNotBlank()) result += PokemonPrintVariant(id, finish, sourceVariant, cardmarketProductId)
            }
            else -> reader.skipValue()
        }; reader.endObject()
        return CatalogCardDetail(rarity, result.distinctBy { it.id }, image)
    }

    /** TCGdex returns a logo asset base path; Coil needs the actual image file. */
    private fun assetImageUrl(value: String): String? = value.trim().trimEnd('/').takeIf(String::isNotEmpty)?.let {
        if (it.substringBefore('?').substringAfterLast('/').contains('.')) it else "$it.png"
    }

    private fun collectorIdentity(value: String): String {
        val primary = value.substringBefore('/').uppercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        val prefix = primary.takeWhile(Char::isLetter)
        val digits = primary.dropWhile(Char::isLetter).trimStart('0').ifBlank { "0" }
        return prefix + digits
    }
}

/** Optional metadata failures keep the existing fallback, but cancellation stops the lookup. */
private suspend fun <T> catalogOrDefault(default: T, load: suspend () -> T): T = try {
    load()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    default
}

/**
 * TCGdex stores promo local ids as digits (for example `173`) while the physical card and OCR use
 * the registered prefix (`SVP173`). Strip that prefix only after the requested source set itself
 * has been resolved to the corresponding promo catalogue; arbitrary letters remain significant.
 */
internal fun collectorIdentityForSourceLookup(value: String, resolvedSourceSetId: String): String {
    if (PokemonSetRegistry.normalize(resolvedSourceSetId) == "30thc") {
        classic30thLocalIdForPrintedCollector(value)?.let { return it }
    }
    val primary = value.substringBefore('/').uppercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    val prefix = primary.takeWhile(Char::isLetter)
    val digits = primary.dropWhile(Char::isLetter).trimStart('0').ifBlank { if (primary.any(Char::isDigit)) "0" else "" }
    val promoSet = PokemonSetRegistry.promoSetIdForCollectorNumber(primary)
    return if (
        promoSet != null &&
        PokemonSetRegistry.normalize(promoSet) == PokemonSetRegistry.normalize(resolvedSourceSetId)
    ) digits else prefix + digits
}

/**
 * The index endpoint omits dates for many older records. Keep the set chooser
 * current-first with the source's stable era/id chronology, while a typed search
 * always retains access to the complete German catalogue.
 */
internal fun catalogSetRecencyKey(set: PokemonSet): Double {
    val id = set.id.lowercase(Locale.ROOT)
    val era = when {
        id.startsWith("30th") -> 10
        id.startsWith("me") -> 9
        id.startsWith("sv") -> 8
        id.startsWith("swsh") -> 7
        id.startsWith("sm") -> 6
        id.startsWith("xy") -> 5
        id.startsWith("bw") -> 4
        id.startsWith("hgss") || id.startsWith("col") -> 3
        id.startsWith("dp") || id.startsWith("pl") -> 2
        id.startsWith("ex") || id.startsWith("pop") -> 1
        else -> 0
    }
    val number = Regex("\\d+(?:\\.\\d+)?").find(id)?.value?.toDoubleOrNull() ?: 0.0
    return era * 10_000.0 + number
}
