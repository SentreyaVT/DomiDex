package com.dominik.cardvault

import android.database.Cursor
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.*
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import com.dominik.cardvault.model.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Public repository read proofs on isolated Room; no retention/import/auth/network or app DB. */
@RunWith(AndroidJUnit4::class)
class PriceHistoryWindowRepositoryInstrumentedTest {
    private lateinit var db: CardVaultDatabase
    private val dao get() = db.cardVaultDao()
    private val owner = MutableStateFlow(A)
    private val window = MutableStateFlow(MARCH)
    private val legacyQueries = AtomicInteger()
    private val compactQueries = AtomicInteger()
    private val syncRequests = AtomicInteger()
    private lateinit var repository: RoomCollectionRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java)
            .setQueryCallback(RoomDatabase.QueryCallback { statement, _ ->
                val sql = statement.trim().replace(Regex("\\s+"), " ")
                if (sql.startsWith("WITH owner_rows AS (")) compactQueries.incrementAndGet()
                if (sql.startsWith("SELECT p.cardId, p.effectiveAtEpochMillis, p.trendValueMinor, p.holoTrendValueMinor FROM raw_price_snapshots p WHERE p.source =")) legacyQueries.incrementAndGet()
            }, Executor { it.run() }).build()
        repository = RoomCollectionRepository(db, owner, onSyncNeeded = { syncRequests.incrementAndGet() },
            historyWindowStart = window)
    }

    @After fun tearDown() = db.close()

    @Test fun compactWindowKeepsPriorStateInclusiveObservationsAndIndependentFinishBases() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) { checkReadPaths(2, safe = true) }
    }

    @Test fun unsafeQuantityUsesRealChronologicalFallbackWithTheSameWindowContract() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000L) { checkReadPaths(1_000_001, safe = false) }
    }

    private suspend fun checkReadPaths(quantity: Int, safe: Boolean) {
        val fixture = seed(quantity)
        val before = snapshot()
        val guard = dao.observeOwnedPortfolioDeltas(A, SOURCE).first()
        assertEquals(if (safe) 1 else 0, guard.first().isSafe)
        if (!safe) assertEquals(listOf(OwnedPortfolioDeltaProjection(0, 0, null, 0L)), guard)
        legacyQueries.set(0); compactQueries.set(0)
        val actual = repository.portfolio.first()
        assertPortfolio(fixture, A, MARCH, actual)
        assertTrue("Actual repository compact/guard query", compactQueries.get() > 0)
        if (safe) assertEquals("Safe path must not materialize legacy history", 0, legacyQueries.get())
        else assertTrue("Unsafe sentinel must cause a genuine DAO history read", legacyQueries.get() > 0)
        assertEquals(19.0, actual.history.first().value, 0.0) // 3*400 reverse + old-only carry 700.
        assertEquals(listOf(100.0, 100.0), listOf(actual.cards.single { it.entryId == fixture.normal.id }.changePercent,
            actual.cards.single { it.entryId == fixture.reverse.id }.changePercent))
        val bases = dao.observeOwnedFirstPositivePrices(A, SOURCE, MARCH).first().associateBy { it.cardId }
        assertEquals(OwnedFirstPositivePriceProjection(product(42), 500L, 400L), bases[product(42)])
        assertEquals(OwnedFirstPositivePriceProjection(product(43), null, null), bases[product(43)])
        assertEquals(listOf(point(APRIL, 5.0), point(MAY, 10.0)),
            repository.priceHistory(actual.cards.single { it.entryId == fixture.normal.id }).first())
        assertEquals(listOf(point(MARCH, 4.0), point(MAY, 8.0)),
            repository.priceHistory(actual.cards.single { it.entryId == fixture.reverse.id }).first())
        assertSideViews(fixture, A, MARCH)
        assertEquals(before, snapshot())
        assertEquals(0, syncRequests.get())
    }

    @Test fun liveWindowAndOwnerChangesRecomputeWithoutSyntheticPointsOrPersistedWrites() = runBlocking(Dispatchers.IO) {
        withTimeout(45_000L) {
            val fixture = seed(2)
            val before = snapshot()
            val feed = Channel<PortfolioSummary>(Channel.UNLIMITED)
            val privateFeed = Channel<List<PrivateBinderCard>>(Channel.UNLIMITED)
            val collectors = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) { repository.portfolio.collect { feed.send(it) } },
                launch(start = CoroutineStart.UNDISPATCHED) { repository.privateBinderContents(PRIVATE).collect { privateFeed.send(it) } },
            )
            try {
                suspend fun expect(who: String, start: Long) {
                    withTimeout(10_000L) {
                        val cards = expectedCards(fixture, who, start)
                        val history = expectedHistory(fixture, who, start)
                        while (true) {
                            val actual = feed.receive()
                            if (actual.cards == cards && actual.history == history) {
                                assertPortfolio(fixture, who, start, actual)
                                break
                            }
                        }
                        val details = expectedPrivate(cards)
                        while (privateFeed.receive() != details) { /* Drain queued prior states, no resubscribe. */ }
                    }
                    assertSideViews(fixture, who, start)
                    assertEquals(before, snapshot())
                    assertEquals(0, syncRequests.get())
                }
                expect(A, MARCH)
                window.value = MARCH + 1L // Between real timestamps: no synthetic boundary point.
                expect(A, MARCH + 1L)
                assertEquals(listOf(point(MAY, 8.0)), repository.priceHistory(expectedCards(fixture, A, MARCH + 1L)
                    .single { it.entryId == fixture.reverse.id }).first())
                window.value = JUNE // No observations in window; today's prices and saved choice still exist.
                expect(A, JUNE)
                owner.value = B
                expect(B, JUNE)
                window.value = MARCH
                expect(B, MARCH)
                owner.value = A
                expect(A, MARCH)
                owner.value = GUEST_OWNER_UID
                expect(GUEST_OWNER_UID, MARCH)
            } finally {
                withContext(NonCancellable) { collectors.forEach { it.cancel() }; withTimeout(5_000L) { collectors.joinAll() } }
                feed.close(); privateFeed.close()
            }
        }
    }

    private fun assertPortfolio(f: Fixture, who: String, start: Long, actual: PortfolioSummary) {
        val cards = expectedCards(f, who, start)
        assertEquals("All current fields and saved alternative remain exact", cards, actual.cards)
        assertEquals("Only real timestamps; earlier states still contribute", expectedHistory(f, who, start), actual.history)
        assertEquals(cards.sumOf { (it.effectiveValueEur ?: 0.0) * it.quantity }, actual.totalValue, 0.0)
        assertEquals(if (who == A) 1 else 0, actual.alternativePricedCards)
        if (who == A) {
            assertEquals(choice(), actual.cards.single { it.entryId == f.alternative.id }.activePriceSelection)
            assertEquals(7.0, actual.cards.single { it.entryId == f.carry.id }.currentValueEur, 0.0)
        }
    }

    private suspend fun assertSideViews(f: Fixture, who: String, start: Long) {
        val cards = expectedCards(f, who, start)
        assertEquals(expectedPrivate(cards), repository.privateBinderContents(PRIVATE).first())
        val binders = repository.binders.first()
        val privateBinders = repository.privateBinders.first()
        if (cards.isEmpty()) { assertTrue(binders.isEmpty()); assertTrue(privateBinders.isEmpty()); return }
        val total = cards.sumOf { (it.effectiveValueEur ?: 0.0) * it.quantity }
        // Private summaries accumulate in placement order, not the collection's DESC order.
        // Keep Double operation order exact instead of weakening equality with a tolerance.
        val privateTotal = expectedPrivate(cards).sumOf { (it.card.effectiveValueEur ?: 0.0) * it.card.quantity }
        assertEquals(total, binders.single().marketValueEur, 0.0)
        assertEquals(privateTotal, privateBinders.single().marketValueEur, 0.0)
        assertEquals(cards.sumOf { it.quantity }, binders.single().ownedCards)
        assertEquals(cards.sumOf { it.quantity }, privateBinders.single().ownedCards)
        assertEquals(if (who == A) 1 else 0, binders.single().alternativePricedCards)
        assertEquals(if (who == A) 1 else 0, privateBinders.single().alternativePricedCards)
        if (who == A) assertEquals(choice().amountMinor, dao.priceSelection(A, f.alternative.syncId)!!.amountMinor)
    }

    private data class Fixture(val entries: List<CollectionEntryEntity>) {
        val normal get() = entries[0]
        val reverse get() = entries[1]
        val carry get() = entries[2]
        val alternative get() = entries[3]
    }

    private fun expectedCards(f: Fixture, who: String, start: Long) = f.entries.filter { it.ownerUid == who }
        .sortedByDescending { it.updatedAtEpochMillis }.map { entry ->
            val p = entry.externalProductId!!
            val reverse = entry.finishOverride == "Reverse Holo"
            val value = when (p) { 42L -> if (reverse) 8.0 else 10.0; 43L -> 7.0; 44L -> 0.0; else -> 12.0 }
            val change = if (p == 42L && start <= (if (reverse) MARCH else APRIL)) 100.0 else 0.0
            CollectionCard(entry.id, product(p), "Window $p", "Window set", "001", CardLanguage.GERMAN,
                entry.finishOverride!!, "Standard", "Near Mint", entry.quantity, value, change,
                when (p) { 42L -> MAY; 43L -> JANUARY; 44L -> null; else -> FOREIGN }, "kept ${entry.syncId}",
                1.23, 20_000L, 0xFF6C78A8, binderId = BINDER, externalProductId = p,
                imageUrl = "fixture://image/$p", sourceSetId = "window", sourceCardId = "window-$p",
                priceSelection = if (p == 44L) choice() else null)
        }

    private fun expectedPrivate(cards: List<CollectionCard>) = cards.sortedBy { it.entryId }.map {
        PrivateBinderCard("place-${it.entryId}", PRIVATE, it.entryId, it, "kept-effect", 0.5f)
    }

    private fun expectedHistory(f: Fixture, who: String, start: Long): List<PortfolioPoint> {
        val q = f.normal.quantity.toLong()
        val events = when (who) {
            A -> listOf(MARCH to 1_900L, APRIL to (q * 500L + 700L), MAY to (q * 1_000L + 3_100L))
            B -> listOf(FOREIGN to 4_800L)
            else -> emptyList()
        }
        return events.filter { it.first >= start }.map { (at, minor) -> PortfolioPoint(
            Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.ofPattern("MMM")), minor / 100.0) }
    }

    private suspend fun seed(quantity: Int): Fixture = db.withTransaction {
        dao.insertCatalogCards((42L..45L).map { p -> CatalogCardEntity(product(p), "POKEMON", "window", "Window set",
            "Window $p", "001", "GERMAN", "Normal", "Standard", 1L, SOURCE, p, "fixture://image/$p") })
        dao.insertRawPrices(listOf(price(42, JANUARY, 100, 200), price(42, FEBRUARY, 200, 300),
            price(42, MARCH, 0, 400), price(42, APRIL, 500, null), price(42, MAY, 1_000, 800),
            price(43, JANUARY, 700, null), price(44, JANUARY, 100, null), price(44, FEBRUARY, null, null),
            price(45, JANUARY, 900, null), price(45, FOREIGN, 1_200, null),
            price(42, APRIL, 999_999, 999_999).copy(source = "OTHER_SOURCE")))
        val entries = mutableListOf<CollectionEntryEntity>()
        suspend fun entry(who: String, p: Long, finish: String, count: Int) {
            val ordinal = entries.size + 1L
            val e = CollectionEntryEntity(cardId = product(p), quantity = count, condition = "Near Mint",
                acquisitionPriceMinor = 123L, acquisitionCurrency = "EUR", acquisitionDateEpochDay = 20_000L,
                notes = "kept entry-$ordinal", frontPhotoUri = "fixture://front", backPhotoUri = "fixture://back",
                createdAtEpochMillis = 1L, updatedAtEpochMillis = 100L + ordinal, externalProductSource = SOURCE,
                externalProductId = p, binderId = BINDER, ownerUid = who, syncId = "entry-$ordinal", syncState = SYNC_PENDING,
                finishOverride = finish, variantOverride = "Standard", sourceSetId = "window", sourceCardId = "window-$p")
            entries += e.copy(id = dao.insertCollectionEntry(e))
        }
        entry(A, 42, "Normal", quantity); entry(A, 42, "Reverse Holo", 3)
        entry(A, 43, "Normal", 1); entry(A, 44, "Normal", 1); entry(B, 45, "Normal", 4)
        listOf(A, B).forEach { dao.insertPrivateBinder(PrivateBinderEntity(PRIVATE, it, "Private window",
            createdAtEpochMillis = 1L, updatedAtEpochMillis = 2L)) }
        entries.forEach { e -> dao.insertPrivateBinderPlacement(PrivateBinderPlacementEntity("place-${e.id}", e.ownerUid,
            PRIVATE, e.id, e.id, "kept-effect", 0.5f, 1L, 2L)) }
        val selected = choice()
        dao.upsertPriceSelection(CollectionPriceSelectionEntity(entries[3].id, A, entries[3].syncId, false,
            selected.sourceSetId, selected.sourceCardId, selected.collectorNumber, selected.finish, selected.variant,
            selected.language.name, selected.productId, selected.metric.wireValue, selected.amountMinor, selected.currency,
            selected.sourceAt, selected.selectedAt, 3L, SYNC_PENDING, 7L))
        dao.upsertSyncOutbox(CollectionSyncOutboxEntity("kept-outbox", "kept-mutation", A, entries[0].syncId,
            "UPSERT", 4L, SYNC_PENDING, 5L, "kept-error"))
        dao.putMetadata(AppMetadataEntity("kept-receipt", "not-a-read-checkpoint"))
        Fixture(entries)
    }

    /** All persisted typed cells including sqlite_sequence; transient Room trigger state excluded. */
    private fun snapshot(): Map<String, String> {
        val sql = db.openHelper.readableDatabase
        val names = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('room_master_table','android_metadata') ORDER BY name")
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(15, names.size)
        return names.associateWith { table ->
            check(table.matches(Regex("[a-z_]+")))
            val digest = MessageDigest.getInstance("SHA-256")
            sql.query("SELECT * FROM `$table` ORDER BY rowid").use { c -> while (c.moveToNext()) {
                repeat(c.columnCount) { i ->
                    val type = c.getType(i)
                    val bytes = when (type) {
                        Cursor.FIELD_TYPE_NULL -> byteArrayOf()
                        Cursor.FIELD_TYPE_INTEGER -> ByteBuffer.allocate(8).putLong(c.getLong(i)).array()
                        Cursor.FIELD_TYPE_FLOAT -> ByteBuffer.allocate(8).putDouble(c.getDouble(i)).array()
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> c.getString(i).toByteArray(Charsets.UTF_8)
                    }
                    digest.update(type.toByte()); digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
                }
            } }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private fun price(p: Long, at: Long, normal: Long?, reverse: Long?) = RawPriceSnapshotEntity(cardId = product(p),
        source = SOURCE, currency = "EUR", lowValueMinor = 111L, averageValueMinor = 532L, trendValueMinor = normal,
        marketValueMinor = normal, holoTrendValueMinor = reverse, fetchedAtEpochMillis = at, effectiveAtEpochMillis = at)
    private fun choice() = CardPriceSelection("window", "window-44", "001", "Normal", "Standard", CardLanguage.GERMAN,
        44L, CardPriceMetric.NORMAL_AVG_SELL, 532L, "EUR", FEBRUARY, MARCH)
    private fun point(at: Long, value: Double) = CardPricePoint(at, value, "EUR", SOURCE)
    private fun product(id: Long) = "window-product-$id"

    companion object {
        private const val A = "window-owner-a"
        private const val B = "window-owner-b"
        private const val PRIVATE = "window-private"
        private const val BINDER = "window-set-binder"
        private const val SOURCE = CardmarketSyncContract.SOURCE
        private val JANUARY = Instant.parse("2023-01-01T00:00:00Z").toEpochMilli()
        private val FEBRUARY = Instant.parse("2023-02-01T00:00:00Z").toEpochMilli()
        private val MARCH = Instant.parse("2023-03-01T00:00:00Z").toEpochMilli()
        private val APRIL = Instant.parse("2023-04-01T00:00:00Z").toEpochMilli()
        private val FOREIGN = Instant.parse("2023-04-15T00:00:00Z").toEpochMilli()
        private val MAY = Instant.parse("2023-05-01T00:00:00Z").toEpochMilli()
        private val JUNE = Instant.parse("2023-06-01T00:00:00Z").toEpochMilli()
    }
}
