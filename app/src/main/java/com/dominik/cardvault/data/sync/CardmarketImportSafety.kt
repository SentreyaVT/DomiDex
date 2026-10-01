package com.dominik.cardvault.data.sync

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Technical per-file safety limit, not a claimed provider maximum. A rejected
// download never replaces the previous valid catalog/price snapshot.
internal const val CARDMARKET_MAX_DOWNLOAD_BYTES = 128L * 1024 * 1024

internal object CardmarketImportGuard {
    // Both WorkManager and immediate UI imports use this process-wide gate.
    // Waiters get their own result; they never report another failed run as success.
    private val mutex = Mutex()
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}

internal fun copyCardmarketDownload(
    input: InputStream,
    output: OutputStream,
    contentLength: Long,
    maxBytes: Long = CARDMARKET_MAX_DOWNLOAD_BYTES,
): Long {
    require(maxBytes > 0) { "Downloadgrenze muss positiv sein." }
    if (contentLength > maxBytes) throw IOException("Cardmarket-Download überschreitet die sichere Dateigröße.")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        // Read at most one byte beyond the budget to distinguish exact size from
        // excess, without writing any excess bytes to disk.
        val remaining = maxBytes - total
        val requested = if (remaining >= buffer.size) buffer.size else remaining.toInt() + 1
        val read = input.read(buffer, 0, requested)
        if (read == -1) break
        if (read > maxBytes - total) throw IOException("Cardmarket-Download überschreitet die sichere Dateigröße.")
        output.write(buffer, 0, read)
        total += read
    }
    if (contentLength >= 0 && contentLength != total) throw IOException("Cardmarket-Download wurde nicht vollständig übertragen.")
    return total
}

internal suspend fun <T> withCardmarketDownloadFiles(
    cacheDir: File,
    createFile: (String) -> File = { suffix -> File.createTempFile("cardvault-", suffix, cacheDir) },
    block: suspend (File, File) -> T,
): T {
    var products: File? = null
    var prices: File? = null
    try {
        products = createFile("products.json")
        prices = createFile("prices.json")
        return block(products, prices)
    } finally {
        // The caller's coroutineScope has joined both downloads before this
        // cleanup, including the case where only one parallel download failed.
        products?.delete()
        prices?.delete()
    }
}

internal data class PreparedCardmarketImport(
    val products: List<CardmarketProduct>,
    val prices: List<CardmarketPrice>,
    val sourceCreatedAt: String,
    val effectiveAtEpochMillis: Long,
)

internal fun prepareCardmarketImport(
    products: ParsedDownload<CardmarketProduct>,
    prices: ParsedDownload<CardmarketPrice>,
): PreparedCardmarketImport {
    require(products.records.isNotEmpty() && prices.records.isNotEmpty()) {
        "Offizieller Download enthält keine Datensätze."
    }
    val sourceCreatedAt = requireNotNull(prices.createdAt) { "Der Cardmarket-Preisguide enthält keinen gültigen Quellzeitpunkt." }
    val effectiveAt = requireNotNull(parseCardmarketSourceTime(sourceCreatedAt)) {
        "Der Cardmarket-Preisguide enthält keinen gültigen Quellzeitpunkt."
    }
    val productIds = products.records.asSequence().map { it.id }.toHashSet()
    val matchedPrices = prices.records.filter { it.productId in productIds }
    // Necessary minimum only: a join is not proof that every card has a price.
    require(matchedPrices.isNotEmpty()) { "Cardmarket-Produktkatalog und Preisguide enthalten keine gemeinsamen Produkte." }
    return PreparedCardmarketImport(
        products.records,
        matchedPrices,
        sourceCreatedAt,
        effectiveAt,
    )
}

private val cardmarketSourceTimeFormats = listOf(
    DateTimeFormatter.ISO_OFFSET_DATE_TIME,
    // Existing captured official downloads use offsets such as +0200.
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssZ").withResolverStyle(ResolverStyle.STRICT),
)

private fun parseCardmarketSourceTime(value: String): Long? = cardmarketSourceTimeFormats.firstNotNullOfOrNull { format ->
    runCatching { OffsetDateTime.parse(value, format).toInstant().toEpochMilli() }.getOrNull()
}
