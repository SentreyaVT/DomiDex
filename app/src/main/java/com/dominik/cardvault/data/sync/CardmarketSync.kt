package com.dominik.cardvault.data.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.dominik.cardvault.data.local.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import okio.buffer
import okio.source
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object CardmarketSyncContract {
    const val SOURCE = "CARDMARKET_PUBLIC_DOWNLOAD"
    const val PRODUCT_URL = "https://downloads.s3.cardmarket.com/productCatalog/productList/products_singles_6.json"
    const val PRICE_URL = "https://downloads.s3.cardmarket.com/productCatalog/priceGuide/price_guide_6.json"
    const val UNIQUE_PERIODIC = "cardmarket-pokemon-daily"
    const val UNIQUE_MANUAL = "cardmarket-pokemon-manual"
    const val STALE_AFTER_MILLIS = 24 * 60 * 60 * 1000L
}

class CardmarketImporter(private val context: Context, private val database: CardVaultDatabase) {
    suspend fun import(): Result<Unit> = runCatching {
        val dao = database.cardVaultDao(); val now = System.currentTimeMillis(); val previous = dao.syncState(CardmarketSyncContract.SOURCE)
        dao.putSyncState((previous ?: emptyState()).copy(status = "RUNNING", lastAttemptAtEpochMillis = now, errorMessage = null))
        // The public Cardmarket catalog and price guide are independent downloads.
        // Fetch both concurrently so opening DomiDex or pressing "Jetzt
        // aktualisieren" is gated by the slower file instead of their combined
        // download time. Parsing and the single Room transaction remain ordered
        // to retain an atomic, matching catalog/price snapshot.
        val (productFile, priceFile) = coroutineScope {
            val products = async(Dispatchers.IO) { download(CardmarketSyncContract.PRODUCT_URL, "products.json") }
            val prices = async(Dispatchers.IO) { download(CardmarketSyncContract.PRICE_URL, "prices.json") }
            products.await() to prices.await()
        }
        try {
            val products = productFile.source().buffer().use(CardmarketParsers::products)
            val prices = priceFile.source().buffer().use(CardmarketParsers::prices)
            require(products.records.isNotEmpty() && prices.records.isNotEmpty()) { "Offizieller Download enthält keine Datensätze." }
            val effectiveAt = parseTimestamp(prices.createdAt) ?: now
            val productIds = products.records.asSequence().map { it.id }.toHashSet()
            val matchedPrices = prices.records.filter { it.productId in productIds }
            database.withTransaction {
                products.records.chunked(1000).forEach { chunk -> dao.upsertCatalogCards(chunk.map { product ->
                    CatalogCardEntity(id = "cm-pokemon-${product.id}", tcg = "POKEMON", setId = product.expansionId?.toString() ?: "unknown", setName = product.expansionId?.let { "Cardmarket-Erweiterung $it" } ?: "Ohne Erweiterungsangabe", name = product.name, collectorNumber = "", language = "UNKNOWN", finish = "UNKNOWN", variant = "Cardmarket-Produkt", createdAtEpochMillis = now, externalSource = CardmarketSyncContract.SOURCE, externalProductId = product.id)
                }) }
                matchedPrices.chunked(1000).forEach { chunk -> dao.insertRawPricesReplacing(chunk.map { price ->
                    // DomiDex deliberately exposes one market figure only: the
                    // Cardmarket trend price. Average/low fields remain stored for
                    // source integrity, but never become a silently substituted UI
                    // price or portfolio value.
                    RawPriceSnapshotEntity(cardId = "cm-pokemon-${price.productId}", source = CardmarketSyncContract.SOURCE, currency = "EUR", lowValueMinor = price.lowMinor, averageValueMinor = price.averageMinor, trendValueMinor = price.trendMinor, marketValueMinor = price.trendMinor, average1DayValueMinor = price.average1Minor, average7DayValueMinor = price.average7Minor, average30DayValueMinor = price.average30Minor, holoAverageValueMinor = price.holoAverageMinor, holoLowValueMinor = price.holoLowMinor, holoTrendValueMinor = price.holoTrendMinor, fetchedAtEpochMillis = now, effectiveAtEpochMillis = effectiveAt)
                }) }
                dao.putSyncState(CatalogSyncStateEntity(CardmarketSyncContract.SOURCE, "SUCCESS", now, now, prices.createdAt ?: products.createdAt, products.records.size, matchedPrices.size, null))
            }
        } finally { productFile.delete(); priceFile.delete() }
    }.onFailure { error ->
        val dao = database.cardVaultDao(); val previous = dao.syncState(CardmarketSyncContract.SOURCE)
        dao.putSyncState((previous ?: emptyState()).copy(status = "ERROR", lastAttemptAtEpochMillis = System.currentTimeMillis(), errorMessage = error.message?.take(300) ?: "Unbekannter Importfehler"))
    }

    private suspend fun download(address: String, name: String): File = withContext(Dispatchers.IO) {
        val target = File.createTempFile("cardvault-", name, context.cacheDir)
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.useCaches = false
        connection.connectTimeout = 20_000; connection.readTimeout = 90_000
        connection.setRequestProperty("User-Agent", "DomiDex/1.0 private Android app")
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("Pragma", "no-cache")
        try { require(connection.responseCode in 200..299) { "Download fehlgeschlagen (HTTP ${connection.responseCode})." }; connection.inputStream.use { input -> target.outputStream().use(input::copyTo) } } finally { connection.disconnect() }
        target
    }

    private fun parseTimestamp(value: String?): Long? = runCatching { value?.let { OffsetDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")).toInstant().toEpochMilli() } }.getOrNull()
    private fun emptyState() = CatalogSyncStateEntity(CardmarketSyncContract.SOURCE, "IDLE", null, null, null, 0, 0, null)
}

/** DomiDex uses only the declared Cardmarket trend, never an average/low fallback. */
internal fun CardmarketPrice.guideValueMinor(): Long? = trendMinor

class CardmarketSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = CardmarketImporter(applicationContext, CardVaultDatabase.get(applicationContext)).import().fold({ Result.success() }, { Result.failure() })
}

class CardmarketSyncScheduler(private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)
    fun schedule() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        workManager.enqueueUniquePeriodicWork(CardmarketSyncContract.UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<CardmarketSyncWorker>(24, TimeUnit.HOURS).setInitialDelay(24, TimeUnit.HOURS).setConstraints(constraints).build())
        val state = CardVaultDatabase.get(context).cardVaultDao()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val last = state.syncState(CardmarketSyncContract.SOURCE)?.lastSuccessAtEpochMillis
            if (last == null || System.currentTimeMillis() - last >= CardmarketSyncContract.STALE_AFTER_MILLIS) refresh()
        }
    }
    /**
     * The dashboard action is intentionally immediate. Enqueuing it behind WorkManager's
     * persisted work can leave the visible catalog stale even though the user pressed refresh.
     * Periodic background updates still use WorkManager above.
     */
    fun refresh() {
        CoroutineScope(Dispatchers.IO).launch {
            // Ignore a second tap while the large public files are already being
            // fetched. Parallel duplicate imports compete for bandwidth and delay
            // the one result that can actually update the UI.
            if (!immediateRefreshMutex.tryLock()) return@launch
            try {
                CardmarketImporter(context.applicationContext, CardVaultDatabase.get(context)).import()
            } finally {
                immediateRefreshMutex.unlock()
            }
        }
    }

    private companion object {
        val immediateRefreshMutex = Mutex()
    }
}
