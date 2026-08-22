package com.dominik.cardvault.data.catalog

import com.squareup.moshi.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.net.HttpURLConnection

data class PokemonSet(val id: String, val name: String, val series: String, val printedTotal: Int, val releaseDate: String, val logoUrl: String?)
data class PokemonVisualCard(val id: String, val name: String, val number: String, val rarity: String?, val smallImageUrl: String, val largeImageUrl: String, val set: PokemonSet)

/** Visual identity adapter only. Cardmarket remains the explicit source for portfolio prices. */
class PokemonVisualCatalog {
    suspend fun sets(query: String = ""): List<PokemonSet> = withContext(Dispatchers.IO) {
        val result = read("https://raw.githubusercontent.com/PokemonTCG/pokemon-tcg-data/master/sets/en.json") { reader ->
            buildList { readArray(reader) { parseSet(it)?.let(::add) } }
        }
        result.sortedByDescending { it.releaseDate }.filter { query.isBlank() || it.name.contains(query, true) || it.series.contains(query, true) }
    }

    suspend fun cards(set: PokemonSet, query: String = ""): List<PokemonVisualCard> = withContext(Dispatchers.IO) {
        val result = read("https://raw.githubusercontent.com/PokemonTCG/pokemon-tcg-data/master/cards/en/${set.id}.json") { reader ->
            buildList { readArray(reader) { parseCard(it, set)?.let(::add) } }
        }
        result.sortedWith(compareBy({ it.number.toIntOrNull() ?: Int.MAX_VALUE }, { it.number })).filter { query.isBlank() || it.name.contains(query, true) || it.number.contains(query, true) }
    }

    private fun <T> read(url: String, block: (JsonReader) -> T): T {
        val connection = (java.net.URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "CardVault/1.0 private Android app")
        }
        try {
            if (connection.responseCode !in 200..299) error("Katalogdienst: HTTP ${connection.responseCode}")
            return JsonReader.of(connection.inputStream.source().buffer()).use(block)
        } finally { connection.disconnect() }
    }

    private fun readDataArray(reader: JsonReader, item: (JsonReader) -> Unit) {
        reader.beginObject(); while (reader.hasNext()) if (reader.nextName() == "data") {
            reader.beginArray(); while (reader.hasNext()) item(reader); reader.endArray()
        } else reader.skipValue(); reader.endObject()
    }

    private fun readArray(reader: JsonReader, item: (JsonReader) -> Unit) {
        reader.beginArray(); while (reader.hasNext()) item(reader); reader.endArray()
    }

    private fun parseSet(reader: JsonReader): PokemonSet? {
        var id = ""; var name = ""; var series = ""; var total = 0; var date = ""; var logo: String? = null
        reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
            "id" -> id = reader.nextString(); "name" -> name = reader.nextString(); "series" -> series = reader.nextString()
            "printedTotal" -> total = reader.nextInt(); "releaseDate" -> date = reader.nextString()
            "images" -> { reader.beginObject(); while (reader.hasNext()) if (reader.nextName() == "logo") logo = reader.nextString() else reader.skipValue(); reader.endObject() }
            else -> reader.skipValue()
        }; reader.endObject(); return if (id.isBlank() || name.isBlank()) null else PokemonSet(id, name, series, total, date, logo)
    }

    private fun parseCard(reader: JsonReader, set: PokemonSet): PokemonVisualCard? {
        var id = ""; var name = ""; var number = ""; var rarity: String? = null; var small = ""; var large = ""
        reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
            "id" -> id = reader.nextString(); "name" -> name = reader.nextString(); "number" -> number = reader.nextString()
            "rarity" -> rarity = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else reader.nextString()
            "images" -> { reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) { "small" -> small = reader.nextString(); "large" -> large = reader.nextString(); else -> reader.skipValue() }; reader.endObject() }
            else -> reader.skipValue()
        }; reader.endObject(); return if (id.isBlank() || small.isBlank()) null else PokemonVisualCard(id, name, number, rarity, small, large, set)
    }
}
