package com.dominik.cardvault

import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.RawPriceSnapshotEntity
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import com.dominik.cardvault.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Counts actual SQLite queries, not a timing threshold affected by emulator scheduling/JIT. */
@RunWith(AndroidJUnit4::class)
class BinderDataLoadingPerformanceInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private lateinit var repository: RoomCollectionRepository
    private val owner = MutableStateFlow("binder-load-owner")
    private val historyQueries = AtomicInteger()
    private val historySql = AtomicReference<String>()
    private val source = CardmarketSyncContract.SOURCE

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java,
        ).setQueryCallback(RoomDatabase.QueryCallback { sql, _ ->
            val normalized = sql.replace(Regex("\\s+"), " ").trim()
            if (Regex("^SELECT .+ FROM raw_price_snapshots p WHERE p.source =", RegexOption.IGNORE_CASE)
                    .containsMatchIn(normalized)) {
                historyQueries.incrementAndGet()
                historySql.set(normalized)
            }
        }, Executor { it.run() }).build()
        repository = RoomCollectionRepository(database, owner)
    }

    @After fun tearDown() = database.close()

    @Test fun summariesDoNotLoad1920HistoricalRowsAndKeepIdenticalValuations() = runBlocking<Unit> {
        val privateBinder = seed()
        historyQueries.set(0)
        val portfolioStart = System.nanoTime()
        val portfolio = repository.portfolio.first()
        val portfolioMillis = (System.nanoTime() - portfolioStart) / 1_000_000.0
        val portfolioHistoryQueries = historyQueries.get()
        assertTrue("The counter must detect the real portfolio history query", portfolioHistoryQueries > 0)
        assertEquals(HISTORY_POINTS, portfolio.history.size)

        historyQueries.set(0)
        val summaryStart = System.nanoTime()
        val binders = repository.binders.first()
        val private = repository.privateBinders.first().single()
        val summaryMillis = (System.nanoTime() - summaryStart) / 1_000_000.0
        assertEquals("Binder summaries must not subscribe to the full history table", 0, historyQueries.get())
        assertEquals(SET_COUNT, binders.size)
        assertEquals(portfolio.totalValue, binders.sumOf { it.marketValueEur }, .0001)
        assertEquals(portfolio.totalValue, private.marketValueEur, .0001)
        assertEquals(portfolio.totalCards, private.ownedCards)
        assertEquals(portfolio.alternativePricedCards, private.alternativePricedCards)
        assertEquals(portfolio.trendPricedCards, private.trendPricedCards)
        val byBinder = portfolio.cards.groupBy(CollectionCard::binderId)
        binders.forEach { binder ->
            val cards = byBinder.getValue(binder.id)
            assertEquals(cards.sumOf { (it.effectiveValueEur ?: 0.0) * it.quantity }, binder.marketValueEur, .0001)
            assertEquals(cards.filter { it.usesPriceSelection }.sumOf { it.quantity }, binder.alternativePricedCards)
            assertEquals(cards.filter { it.trendValueEur != null }.sumOf { it.quantity }, binder.trendPricedCards)
            assertEquals(cards.mapNotNull { it.priceUpdatedAtEpochMillis ?: it.activePriceSelection?.sourceAt }.maxOrNull(),
                binder.lastPriceUpdatedAtEpochMillis)
        }

        // Binder details retain every card field, including its historical change percentage.
        val details = repository.privateBinderContents(privateBinder).first().map { it.card }.associateBy { it.entryId }
        assertEquals(portfolio.cards.associateBy { it.entryId }, details)
        Log.i("DomiDexDataPerf", "cards=${SET_COUNT * CARDS_PER_SET}, historyRows=${SET_COUNT * CARDS_PER_SET * HISTORY_POINTS}, " +
            "portfolioHistoryQueries=$portfolioHistoryQueries, summaryHistoryQueries=0, " +
            "portfolioMs=$portfolioMillis, twoSummaryFlowsMs=$summaryMillis")
    }

    @Test fun lightweightSummariesRefreshOnClearTrendArrivalAndOwnerSwitch() = runBlocking {
        val privateBinder = seed()
        val selected = repository.portfolio.first().cards.single { it.usesPriceSelection }
        repository.clearPriceSelection(selected.entryId)
        assertEquals(0, repository.binders.first().sumOf { it.alternativePricedCards })
        assertEquals(0, repository.privateBinders.first().single().alternativePricedCards)
        val previousTotal = repository.binders.first().sumOf { it.marketValueEur }
        database.cardVaultDao().insertRawPrices(listOf(price(0, HISTORY_POINTS).copy(
            trendValueMinor = 999L, marketValueMinor = 999L,
        )))
        assertEquals(previousTotal + 9.99, repository.binders.first().sumOf { it.marketValueEur }, .0001)
        assertEquals(repository.portfolio.first().totalValue, repository.privateBinders.first().single().marketValueEur, .0001)
        owner.value = "other-owner"
        assertTrue(repository.binders.first().isEmpty())
        assertTrue(repository.privateBinders.first().isEmpty())
        assertTrue(repository.privateBinderContents(privateBinder).first().isEmpty())
        owner.value = "binder-load-owner"
        assertEquals(SET_COUNT, repository.binders.first().size)
        assertEquals(SET_COUNT * CARDS_PER_SET, repository.privateBinderContents(privateBinder).first().size)
    }

    @Test fun annualHistoryKeepsEveryValueAndOwnerBoundaryWithSameDateCorrections() = runBlocking<Unit> {
        val privateBinder = seed(historyPoints = 365, intervalMillis = DAY_MILLIS)
        val expected = (0 until SET_COUNT * CARDS_PER_SET).associateWith { index ->
            (0 until 365).map { sample -> price(index, sample, DAY_MILLIS) }.toMutableList()
        }
        // Null/zero/negative values cannot become a synthetic price or the change-percent base.
        listOf(
            Triple(1, 0, null), Triple(1, 1, 0L),
            Triple(2, 0, 0L), Triple(2, 1, -1L), Triple(2, 2, null),
            Triple(3, 364, null), Triple(4, 180, 123_456L),
        ).forEach { (index, sample, value) ->
            val original = expected.getValue(index)[sample]
            val corrected = if (index % 7 == 1) original.copy(holoTrendValueMinor = value)
                else original.copy(trendValueMinor = value, marketValueMinor = value)
            expected.getValue(index)[sample] = corrected
            database.cardVaultDao().insertRawPricesReplacing(listOf(corrected))
        }
        // An unowned product/date must not add a date to this owner's history.
        database.cardVaultDao().insertCatalogCard(CatalogCardEntity(
            "cm-pokemon-99999", "POKEMON", "unowned", "Unowned", "Unowned", "",
            "UNKNOWN", "UNKNOWN", "Cardmarket-Produkt", 1_000L, source, 99_999L,
        ))
        database.cardVaultDao().insertRawPrices(listOf(price(5, 365, DAY_MILLIS).copy(cardId = "cm-pokemon-99999")))

        val started = System.nanoTime()
        val portfolio = repository.portfolio.first()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000.0
        assertEquals(365, portfolio.history.size)
        fun value(index: Int, row: RawPriceSnapshotEntity): Long? =
            (if (index % 7 == 1) row.holoTrendValueMinor else row.trendValueMinor)?.takeIf { it > 0L }
        portfolio.history.forEachIndexed { sample, point ->
            val sum = expected.entries.sumOf { (index, rows) -> value(index, rows[sample]) ?: 0L }
            assertEquals("History date $sample", sum / 100.0, point.value, .000001)
        }
        portfolio.cards.forEach { card ->
            val index = card.name.removePrefix("Karte ").toInt()
            val rows = expected.getValue(index)
            val first = rows.firstNotNullOfOrNull { value(index, it) }
            val last = value(index, rows.last())
            val change = if (first != null && last != null) (last - first) * 100.0 / first else 0.0
            assertEquals("Change for $index", change, card.changePercent, .000001)
            assertEquals("Latest trend for $index", (last ?: 0L) / 100.0, card.currentValueEur, .000001)
        }
        assertEquals(portfolio.cards.associateBy { it.entryId },
            repository.privateBinderContents(privateBinder).first().associate { it.card.entryId to it.card })
        val sql = historySql.get()
        assertNotNull("The actual history SELECT must be observed", sql)
        Log.i("DomiDexDataPerf", "annualHistoryRows=${48 * 365}, dates=365, portfolioMs=$elapsedMillis, historySql=$sql")
        owner.value = "other-owner"
        val other = repository.portfolio.first()
        assertTrue(other.cards.isEmpty())
        assertTrue(other.history.isEmpty())
        owner.value = "binder-load-owner"
        assertEquals(portfolio, repository.portfolio.first())
    }

    @Test fun portfolioSelectsOnlyTheFourHistoryFieldsItConsumes() = runBlocking {
        seed()
        repository.portfolio.first()
        val sql = requireNotNull(historySql.get()) { "Actual history query not observed" }
        val columns = sql.substringAfter("SELECT ").substringBefore(" FROM ").split(',').map(String::trim)
        assertEquals(listOf("p.cardId", "p.effectiveAtEpochMillis", "p.trendValueMinor", "p.holoTrendValueMinor"), columns)
    }

    private suspend fun seed(historyPoints: Int = HISTORY_POINTS, intervalMillis: Long = 1_000L): String = database.withTransaction {
        val privateBinder = repository.createPrivateBinder("Ladetest", null, "DOMIDEX")
        repeat(SET_COUNT * CARDS_PER_SET) { index ->
            val productId = 4_000L + index
            val set = "perf-${index / CARDS_PER_SET}"
            val number = (index % CARDS_PER_SET + 1).toString().padStart(3, '0')
            val collector = "$number/004"
            val reverse = index % 7 == 1
            val finish = if (reverse) "Reverse Holo" else "Normal"
            val variant = if (reverse) "Reverse Holo" else "Standard"
            database.cardVaultDao().insertCatalogCard(CatalogCardEntity(
                "cm-pokemon-$productId", "POKEMON", "exp-$index", "Source", "Product $index", "",
                "UNKNOWN", "UNKNOWN", "Cardmarket-Produkt", 1_000L, source, productId,
            ))
            database.cardVaultDao().insertRawPrices((0 until historyPoints).map { price(index, it, intervalMillis) })
            val selection = if (index == 0) CardPriceSelection(set, "$set-$number", collector, finish, variant,
                CardLanguage.GERMAN, productId, CardPriceMetric.NORMAL_AVG_SELL, 500L, "EUR",
                historyPoints * intervalMillis, 100_000L) else null
            val entry = repository.addManualCard(ManualCardInput(
                "Karte $index", "Set ${index / CARDS_PER_SET}", collector, CardLanguage.GERMAN, finish,
                variant, 1, "Near Mint", "", null, null, externalProductId = productId,
                sourceSetId = set, sourceCardId = "$set-$number", priceSelection = selection,
            ), BinderTarget.MatchingSetBinder)
            repository.assignToPrivateBinder(privateBinder, entry.entryId, index.toLong())
        }
        privateBinder
    }

    private fun price(index: Int, sample: Int, intervalMillis: Long = 1_000L): RawPriceSnapshotEntity {
        val trend = (100L + index + sample).takeIf { index != 0 }
        val at = (sample + 1) * intervalMillis
        return RawPriceSnapshotEntity(cardId = "cm-pokemon-${4_000L + index}", source = source,
            currency = "EUR", lowValueMinor = 400L, averageValueMinor = 500L,
            trendValueMinor = trend, marketValueMinor = trend, holoTrendValueMinor = trend?.plus(50L),
            fetchedAtEpochMillis = at, effectiveAtEpochMillis = at)
    }

    companion object {
        private const val SET_COUNT = 12
        private const val CARDS_PER_SET = 4
        private const val HISTORY_POINTS = 40
        private const val DAY_MILLIS = 86_400_000L
    }
}
