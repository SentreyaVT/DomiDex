package com.dominik.cardvault.data.catalog

import com.dominik.cardvault.data.WachsendesChaosMasterSet
import com.squareup.moshi.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
)

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
class PokemonVisualCatalog {
    val isLicensedSourceConfigured: Boolean get() = true
    private val germanBaseUrl = "https://api.tcgdex.net/v2/de"
    private val englishBaseUrl = "https://api.tcgdex.net/v2/en"

    suspend fun sets(query: String = ""): List<PokemonSet> = withContext(Dispatchers.IO) {
        val result = read("$germanBaseUrl/sets") { reader ->
            buildList { readArray(reader) { parseGermanSet(it)?.let(::add) } }
        }
        result.sortedWith(compareByDescending(::catalogSetRecencyKey).thenByDescending { it.releaseDate })
            .filter { query.isBlank() || it.name.contains(query, true) || it.series.contains(query, true) }
    }

    suspend fun cards(set: PokemonSet, query: String = ""): List<PokemonVisualCard> = withContext(Dispatchers.IO) {
        val result = read("$germanBaseUrl/sets/${set.id}") { reader ->
            parseGermanSetCards(reader, set)
        }
        // Cardmarket's compact price catalogue commonly uses the English card
        // name. TCGdex exposes the same card ids in both locales, so enrich the
        // German UI card with that source-backed lookup name instead of guessing
        // translations ourselves.
        val englishNames = runCatching {
            read("$englishBaseUrl/sets/${set.id}") { reader ->
                parseGermanSetCards(reader, set).associate { it.id to it.name }
            }
        }.getOrDefault(emptyMap())
        result.map { card -> card.copy(marketSearchName = englishNames[card.id]) }
            .sortedWith(compareBy({ it.number.toIntOrNull() ?: Int.MAX_VALUE }, { it.number }))
            .filter { query.isBlank() || it.name.contains(query, true) || it.number.contains(query, true) }
    }

    /**
     * The set index deliberately stays small.  A card detail supplies the
     * source-declared Normal/Holo/Reverse printings for master-set slots.
     */
    suspend fun withDeclaredPrintVariants(card: PokemonVisualCard): PokemonVisualCard = withContext(Dispatchers.IO) {
        val detail = read("$germanBaseUrl/cards/${card.id}") { reader -> parseCardDetail(reader) }
        card.copy(
            rarity = detail.rarity ?: card.rarity,
            printVariants = detail.printVariants.ifEmpty { card.printVariants },
        )
    }

    suspend fun findExact(setIdentifier: String, collectorNumber: String): List<PokemonVisualCard> = withContext(Dispatchers.IO) {
        val code = PokemonSetRegistry.normalize(setIdentifier)
        val number = Regex("\\d{1,4}").find(collectorNumber)?.value?.toIntOrNull() ?: return@withContext emptyList()
        PokemonSetRegistry.legacyOfflineSet(code)?.let { set ->
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
        // The set payload intentionally stays lightweight and can omit rarity.
        // Only the one or few exact number candidates are expanded, off the UI
        // thread, to obtain source-declared rarity/finish metadata.
        buildList {
            cards(matchingSet)
                .filter { it.number.toIntOrNull() == number }
                .forEach { candidate -> add(withDeclaredPrintVariants(candidate)) }
        }
    }

    private fun <T> read(url: String, block: (JsonReader) -> T): T {
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

    private fun parseGermanSetCards(reader: JsonReader, set: PokemonSet): List<PokemonVisualCard> {
        val cards = mutableListOf<PokemonVisualCard>()
        reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
            "cards" -> readArray(reader) { parseGermanCardBrief(it, set)?.let(cards::add) }
            else -> reader.skipValue()
        }
        reader.endObject()
        return cards
    }

    private fun parseGermanCardBrief(reader: JsonReader, set: PokemonSet): PokemonVisualCard? {
        var id = ""; var name = ""; var number = ""; var rarity: String? = null; var image = ""
        reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
            "id" -> id = reader.nextString(); "name" -> name = reader.nextString(); "localId" -> number = reader.nextString()
            "rarity" -> rarity = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else reader.nextString()
            "image" -> image = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); "" } else reader.nextString()
            else -> reader.skipValue()
        }
        reader.endObject()
        val lowImage = image.takeIf { it.isNotBlank() }?.plus("/low.webp").orEmpty()
        val highImage = image.takeIf { it.isNotBlank() }?.plus("/high.webp").orEmpty()
        return if (id.isBlank() || name.isBlank()) null else PokemonVisualCard(
            id, name, number, rarity, lowImage, highImage, set,
            listOf(PokemonPrintVariant("normal", "Normal", "Standard")),
        )
    }

    private data class CardDetail(val rarity: String?, val printVariants: List<PokemonPrintVariant>)

    private fun parseCardDetail(reader: JsonReader): CardDetail {
        val result = mutableListOf<PokemonPrintVariant>()
        var rarity: String? = null
        reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
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
        return CardDetail(rarity, result.distinctBy { it.id })
    }

    /** TCGdex returns a logo asset base path; Coil needs the actual image file. */
    private fun assetImageUrl(value: String): String =
        if (value.substringAfterLast('/').contains('.')) value else "$value.png"
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
