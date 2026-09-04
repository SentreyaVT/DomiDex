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
)

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
    )

    fun idFor(tcgDexId: String): String? = byTcgDexId[tcgDexId.lowercase(Locale.ROOT)]
}

/** A separately collectible printing of the same numbered card. */
data class PokemonPrintVariant(val id: String, val finish: String, val variant: String)

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
            card.copy(
                marketSearchName = english?.name,
                smallImageUrl = card.smallImageUrl.ifBlank { english?.smallImageUrl.orEmpty() },
                largeImageUrl = card.largeImageUrl.ifBlank { english?.largeImageUrl.orEmpty() },
            )
        }
}

/** Never add English-only sets or replace the German display name/count/identity. */
internal fun fillMissingSetLogos(german: List<PokemonSet>, english: List<PokemonSet>): List<PokemonSet> {
    val byId = english.associateBy(PokemonSet::id)
    return german.map { set ->
        if (set.logoUrl.isNullOrBlank()) set.copy(logoUrl = byId[set.id]?.logoUrl) else set
    }
}

private fun cardImageUrl(value: String, quality: String): String {
    val base = value.trim().trimEnd('/')
    if (base.isEmpty()) return ""
    return if (base.substringBefore('?').substringAfterLast('/').contains('.')) base else "$base/$quality.webp"
}

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
                val english = if (german.any { it.logoUrl.isNullOrBlank() }) catalogOrDefault(emptyList()) {
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
                        read("$germanBaseUrl/sets/${set.id}") { reader ->
                            parseSetCards(reader, set, hasGermanLocalizedName = true)
                        }
                    }
                }
                val englishLoad = async(Dispatchers.IO) {
                    catalogOrDefault<List<PokemonVisualCard>?>(null) {
                        read("$englishBaseUrl/sets/${set.id}") { reader ->
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
                    .sortedWith(compareBy({ it.number.toIntOrNull() ?: Int.MAX_VALUE }, { it.number }))
                CatalogSetCards(cards, cards.groupBy { collectorIdentity(it.number) })
            }
        }

    /**
     * The set index deliberately stays small.  A card detail supplies the
     * source-declared Normal/Holo/Reverse printings for master-set slots.
     */
    suspend fun withDeclaredPrintVariants(card: PokemonVisualCard): PokemonVisualCard {
        val detail = metadataCache.details.getOrLoad(card.id) {
            withContext(Dispatchers.IO) {
                read("$germanBaseUrl/cards/${card.id}") { reader -> parseCardDetail(reader) }
            }
        }
        return card.copy(
            rarity = detail.rarity ?: card.rarity,
            printVariants = detail.printVariants.ifEmpty { card.printVariants },
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
                        primary.id,
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
        val matchingSet = PokemonSetRegistry.resolve(code, sets()) ?: return@withContext emptyList()
        val sourceCollectorKey = collectorIdentityForSourceLookup(collectorNumber, matchingSet.id)
        // The set payload intentionally stays lightweight and can omit rarity.
        // Only the one or few exact number candidates are expanded, off the UI
        // thread, to obtain source-declared rarity/finish metadata.
        catalogOrDefault(emptyList()) { setCards(matchingSet).byCollectorNumber[sourceCollectorKey].orEmpty() }
            .map { candidate -> catalogOrDefault(candidate) { withDeclaredPrintVariants(candidate) } }
    }

    /**
     * Safe fallback when glare hides only the printed three-letter set code. The denominator is
     * still physical lower-border evidence and is used solely to reduce the source catalogue.
     * Name/artwork scoring must disambiguate the returned cards; this method never declares a
     * match and never searches sets whose official printed total differs.
     */
    suspend fun findByPrintedTotalAndCollectorNumber(collectorNumber: String): List<PokemonVisualCard> = coroutineScope {
        val parts = Regex("(\\d{1,4})\\s*/\\s*(\\d{1,4})").find(collectorNumber)
            ?: return@coroutineScope emptyList()
        val number = parts.groupValues[1].toIntOrNull() ?: return@coroutineScope emptyList()
        val printedTotal = parts.groupValues[2].toIntOrNull() ?: return@coroutineScope emptyList()
        val lookupSlots = Semaphore(permits = 4)
        setsMatchingPrintedTotal(sets(), printedTotal).map { set ->
            async(Dispatchers.IO) {
                lookupSlots.withPermit {
                    catalogOrDefault(emptyList()) { cards(set) }
                        .filter { it.number.toIntOrNull() == number }
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
        var id = ""; var name = ""; var total = 0; var official = 0; var logo: String? = null; var symbol: String? = null; var releaseDate = ""; var abbreviation: String? = null
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
            "cardCount" -> { reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) { "total" -> total = reader.nextInt(); "official" -> official = reader.nextInt(); else -> reader.skipValue() }; reader.endObject() }
            else -> reader.skipValue()
        }
        reader.endObject()
        return if (id.isBlank() || name.isBlank()) null else PokemonSet(id, name, "Deutsch · TCGdex", official, total.coerceAtLeast(official), releaseDate, logo ?: symbol, abbreviation)
    }

    private fun parseSetCards(
        reader: JsonReader,
        set: PokemonSet,
        hasGermanLocalizedName: Boolean,
    ): List<PokemonVisualCard> {
        val cards = mutableListOf<PokemonVisualCard>()
        var abbreviation = set.ptcgoCode
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
            else -> reader.skipValue()
        }
        reader.endObject()
        val detailedSet = set.copy(ptcgoCode = abbreviation?.takeIf(String::isNotBlank) ?: set.ptcgoCode)
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
        return if (id.isBlank() || name.isBlank()) null else PokemonVisualCard(
            id, name, number, rarity, lowImage, highImage, set,
            listOf(PokemonPrintVariant("normal", "Normal", "Standard")),
            hasGermanLocalizedName = hasGermanLocalizedName,
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
                var id = ""; var type = ""; var size = ""
                variantReader.beginObject(); while (variantReader.hasNext()) when (variantReader.nextName()) {
                    "variantId" -> id = variantReader.nextString()
                    "type" -> type = variantReader.nextString()
                    "size" -> size = variantReader.nextString()
                    else -> variantReader.skipValue()
                }; variantReader.endObject()
                val finish = when {
                    type.contains("reverse", true) -> "Reverse Holo"
                    type.contains("holo", true) -> "Holo"
                    type.equals("normal", true) -> "Normal"
                    else -> type.ifBlank { "Normal" }
                }
                if (id.isNotBlank()) result += PokemonPrintVariant(id, finish, size.takeIf { it.isNotBlank() } ?: finish)
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
