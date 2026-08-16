package com.dominik.cardvault.data.sync

import com.squareup.moshi.JsonReader
import okio.BufferedSource
import java.math.BigDecimal
import java.math.RoundingMode

data class CardmarketProduct(val id: Long, val name: String, val categoryId: Long, val expansionId: Long?, val metaCardId: Long?)
data class CardmarketPrice(val productId: Long, val categoryId: Long, val averageMinor: Long?, val lowMinor: Long?, val trendMinor: Long?, val average1Minor: Long?, val average7Minor: Long?, val average30Minor: Long?, val holoAverageMinor: Long?, val holoLowMinor: Long?, val holoTrendMinor: Long?)
data class ParsedDownload<T>(val version: Int?, val createdAt: String?, val records: List<T>)

object CardmarketParsers {
    fun products(source: BufferedSource): ParsedDownload<CardmarketProduct> = parseRoot(source, "products") { reader ->
        var id: Long? = null; var name = ""; var category = 0L; var expansion: Long? = null; var meta: Long? = null
        reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
            "idProduct" -> id = reader.nextLong(); "name" -> name = reader.nextString(); "idCategory" -> category = reader.nextLong()
            "idExpansion" -> expansion = reader.longOrNull(); "idMetacard" -> meta = reader.longOrNull(); else -> reader.skipValue()
        }; reader.endObject(); id?.let { CardmarketProduct(it, name, category, expansion, meta) }
    }

    fun prices(source: BufferedSource): ParsedDownload<CardmarketPrice> = parseRoot(source, "priceGuides") { reader ->
        var id: Long? = null; var category = 0L
        val values = mutableMapOf<String, Long?>()
        reader.beginObject(); while (reader.hasNext()) { val key = reader.nextName(); when (key) {
            "idProduct" -> id = reader.nextLong(); "idCategory" -> category = reader.nextLong()
            "avg", "low", "trend", "avg1", "avg7", "avg30", "avg-holo", "low-holo", "trend-holo" -> values[key] = reader.moneyMinorOrNull()
            else -> reader.skipValue()
        } }; reader.endObject(); id?.let { CardmarketPrice(it, category, values["avg"], values["low"], values["trend"], values["avg1"], values["avg7"], values["avg30"], values["avg-holo"], values["low-holo"], values["trend-holo"]) }
    }

    private fun <T> parseRoot(source: BufferedSource, arrayName: String, parseItem: (JsonReader) -> T?): ParsedDownload<T> {
        val reader = JsonReader.of(source); var version: Int? = null; var createdAt: String? = null; val records = ArrayList<T>()
        reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
            "version" -> version = reader.intOrNull(); "createdAt" -> createdAt = reader.stringOrNull()
            arrayName -> { reader.beginArray(); while (reader.hasNext()) parseItem(reader)?.let(records::add); reader.endArray() }
            else -> reader.skipValue()
        }; reader.endObject(); return ParsedDownload(version, createdAt, records)
    }

    private fun JsonReader.moneyMinorOrNull(): Long? = when (peek()) { JsonReader.Token.NULL -> { nextNull<Unit>(); null }; else -> BigDecimal(nextString()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }
    private fun JsonReader.longOrNull(): Long? = if (peek() == JsonReader.Token.NULL) { nextNull<Unit>(); null } else nextLong()
    private fun JsonReader.intOrNull(): Int? = if (peek() == JsonReader.Token.NULL) { nextNull<Unit>(); null } else nextInt()
    private fun JsonReader.stringOrNull(): String? = if (peek() == JsonReader.Token.NULL) { nextNull<Unit>(); null } else nextString()
}
