package com.dominik.cardvault

// Additive complete-model and sustained-observer proof, including pre-fix RED/control.
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
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PrivateBinderFirstPositiveInstrumentedTest {
    private lateinit var db: CardVaultDatabase
    private lateinit var repository: RoomCollectionRepository
    private val owner = MutableStateFlow(A)
    private val historyQueries = AtomicInteger()
    private var syncRequests = 0
    private val dao get() = db.cardVaultDao()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java)
            .setQueryCallback(RoomDatabase.QueryCallback { statement, _ ->
                val sql = statement.replace(Regex("\\s+"), " ").trim()
                // This identifies the actual whole-owner historical materialization, not
                // observeCollection's legitimate per-product latest-price scalar lookup.
                if (sql.startsWith("SELECT p.cardId, p.effectiveAtEpochMillis, p.trendValueMinor, p.holoTrendValueMinor FROM raw_price_snapshots p WHERE p.source =")) {
                    historyQueries.incrementAndGet()
                }
            }, Executor { it.run() }).build()
        repository = RoomCollectionRepository(db, owner, onSyncNeeded = { syncRequests++ })
    }

    @After fun tearDown() { db.close() }

    @Test fun privateDetailsRetainCompleteCardsWithoutMaterializingWholeOwnerHistory() = runBlocking<Unit>(Dispatchers.IO) {
        withTimeout(30_000L) {
            val f = seed()
            val before = snapshot()
            historyQueries.set(0)
            val details = repository.privateBinderContents(BINDER).first()
            val privateHistoryQueries = historyQueries.get()
            val expected = initialExpected(f)
            assertEquals("Independent complete models, including saved choice and placement fields", expected, details)
            assertEquals(before, snapshot())
            historyQueries.set(0)
            val portfolio = repository.portfolio.first()
            assertTrue("Positive control: actual full portfolio must trigger the counter", historyQueries.get() > 0)
            assertEquals(expected.associate { it.card.entryId to it.card }, portfolio.cards.associateBy { it.entryId })
            assertEquals(before, snapshot())
            assertEquals(0, syncRequests)
            // Only this assertion should be RED on old product; value/control checks precede it.
            assertEquals("Private details need first-positive bases, not all retained owner-history rows", 0, privateHistoryQueries)
        }
    }

    @Test fun sustainedPrivateDetailsTrackPriceLinkCatalogOwnerPlacementAndSelectionChanges() = runBlocking<Unit>(Dispatchers.IO) {
        withTimeout(90_000L) {
            val f = seed()
            var normal = expectedCard(f.normal, "Holo", "Holo", 3, 4.0, 300.0, 3_000L, choice())
            var reverse = expectedCard(f.reverse, "Reverse Holo", "Reverse Holo", 2, 5.0, 0.0, 3_000L, null)
            var normalEntry = f.normal
            var reversePlacement = f.reversePlacement
            var savedChoice = requireNotNull(dao.priceSelection(A, NORMAL_SYNC))
            withFeed { feed ->
                suspend fun expect(cards: List<PrivateBinderCard>) {
                    val before = snapshot()
                    feed.awaitExactly(cards)
                    assertEquals("Reading must not modify any persisted business field", before, snapshot())
                    assertEquals(0, syncRequests)
                }
                fun mine() = listOf(place(f.normalPlacement, normal), place(reversePlacement, reverse))
                expect(mine())

                // Only raw_price_snapshots: same-source/date replacement changes both bases.
                dao.insertRawPricesReplacing(listOf(price(P1, 2_000L, 200L, 250L)))
                normal = normal.copy(changePercent = 100.0)
                reverse = reverse.copy(changePercent = 100.0)
                expect(mine())

                // Insert an earlier normal-only positive; reverse must retain its own later base.
                dao.insertRawPrices(listOf(price(P1, 500L, 50L, 0L)))
                normal = normal.copy(changePercent = 700.0)
                expect(mine())

                // Latest true trends disappear; the saved normal choice becomes active again.
                dao.insertRawPricesReplacing(listOf(price(P1, 3_000L, null, 0L)))
                normal = normal.copy(currentValueEur = 0.0, changePercent = 0.0, priceUpdatedAtEpochMillis = null)
                reverse = reverse.copy(currentValueEur = 0.0, changePercent = 0.0, priceUpdatedAtEpochMillis = null)
                expect(mine())
                assertEquals(choice(), normal.activePriceSelection)
                assertEquals(savedChoice, dao.priceSelection(A, NORMAL_SYNC))

                // Only sidecar table: explicit choice survives without synthesizing trend/history.
                val updatedChoice = choice().copy(amountMinor = 600L, selectedAt = 6_000L)
                savedChoice = savedChoice.copy(amountMinor = 600L, selectedAt = 6_000L, updatedAtEpochMillis = 6_000L)
                dao.upsertPriceSelection(savedChoice)
                normal = normal.copy(priceSelection = updatedChoice)
                expect(mine())
                assertEquals(6.0, requireNotNull(normal.effectiveValueEur), 0.0)

                dao.insertRawPricesReplacing(listOf(price(P1, 3_000L, 500L, 1_000L)))
                normal = normal.copy(currentValueEur = 5.0, changePercent = 900.0, priceUpdatedAtEpochMillis = 3_000L)
                reverse = reverse.copy(currentValueEur = 10.0, changePercent = 300.0, priceUpdatedAtEpochMillis = 3_000L)
                expect(mine())
                assertNull(normal.activePriceSelection)
                assertEquals(updatedChoice, normal.priceSelection)
                assertEquals(savedChoice, dao.priceSelection(A, NORMAL_SYNC))

                // Only catalog_cards: remove the price link without touching entries or prices.
                dao.upsertCatalogCards(listOf(f.product1.copy(externalProductId = 44L)))
                normal = normal.copy(currentValueEur = 0.0, changePercent = 0.0, priceUpdatedAtEpochMillis = null)
                reverse = reverse.copy(currentValueEur = 0.0, changePercent = 0.0, priceUpdatedAtEpochMillis = null)
                expect(mine())
                // Catalog-only re-link to another existing product and its different first dates.
                dao.upsertCatalogCards(listOf(f.product3.copy(externalProductId = 42L)))
                normal = normal.copy(currentValueEur = 3.0, changePercent = 1_100.0, priceUpdatedAtEpochMillis = 3_500L)
                reverse = reverse.copy(currentValueEur = 4.0, changePercent = 700.0, priceUpdatedAtEpochMillis = 3_500L)
                expect(mine())

                // Only collection_entries: normal switches product, reverse stays on product3.
                normalEntry = normalEntry.copy(externalProductId = 43L)
                dao.updateCollectionEntry(normalEntry)
                normal = normal.copy(externalProductId = 43L, currentValueEur = 7.0,
                    changePercent = 0.0, priceUpdatedAtEpochMillis = 1_000L)
                expect(mine())
                assertNull(normal.activePriceSelection) // saved product42 is not product43
                assertEquals(updatedChoice, normal.priceSelection)

                normalEntry = normalEntry.copy(externalProductSource = "OTHER_PRICE_SOURCE")
                dao.updateCollectionEntry(normalEntry)
                normal = normal.copy(currentValueEur = 0.0, changePercent = 0.0,
                    priceUpdatedAtEpochMillis = null, priceSelection = null)
                expect(mine())
                assertEquals(savedChoice, dao.priceSelection(A, NORMAL_SYNC))
                normalEntry = normalEntry.copy(externalProductSource = SOURCE)
                dao.updateCollectionEntry(normalEntry)
                normal = normal.copy(currentValueEur = 7.0, changePercent = 0.0,
                    priceUpdatedAtEpochMillis = 1_000L, priceSelection = updatedChoice)
                expect(mine())

                // Only visual catalog metadata: do not replace values/identity with market product text.
                dao.upsertCatalogCards(listOf(f.visual.copy(name = "Charizard · Detail", imageUrl = "https://example.invalid/changed.png")))
                normal = normal.copy(name = "Charizard · Detail", accent = 0xFFF06445,
                    imageUrl = "https://example.invalid/changed.png")
                reverse = reverse.copy(name = "Charizard · Detail", accent = 0xFFF06445,
                    imageUrl = "https://example.invalid/changed.png")
                expect(mine())

                // Only placement table: cards/prices unchanged while slot + effect update.
                reversePlacement = reversePlacement.copy(slotPosition = 9L, holoEffectId = "galaxy", holoIntensity = .6f)
                dao.updatePrivateBinderPlacement(reversePlacement)
                expect(mine())

                normalEntry = normalEntry.copy(deletedAtEpochMillis = 9_000L)
                dao.updateCollectionEntry(normalEntry)
                expect(listOf(place(reversePlacement, reverse)))
                normalEntry = normalEntry.copy(deletedAtEpochMillis = null)
                dao.updateCollectionEntry(normalEntry)
                expect(mine())

                // Same live subscription: account owner changes without any database mutation.
                owner.value = B
                val other = expectedCard(f.other, "Normal", "Standard", 1, 7.0, 0.0, 1_000L, null)
                    .copy(externalProductId = 43L, name = "Charizard · Detail", accent = 0xFFF06445,
                        imageUrl = "https://example.invalid/changed.png")
                expect(listOf(place(f.otherPlacement, other)))
                owner.value = A
                expect(mine())
                assertEquals(savedChoice, dao.priceSelection(A, NORMAL_SYNC))
                assertEquals(normalEntry, dao.getCollectionEntry(normalEntry.id, A))
                assertEquals(f.reverse, dao.getCollectionEntry(f.reverse.id, A))
                assertEquals(f.other, dao.getCollectionEntry(f.other.id, B))
                assertEquals(reversePlacement, dao.privateBinderPlacement(reversePlacement.id, A))
                assertTrue(dao.pendingSyncOutbox(A).isEmpty())
                assertTrue(dao.pendingPrivateBinderSyncOutbox(A).isEmpty())
                assertTrue(dao.pendingSyncOutbox(B).isEmpty())
                assertTrue(dao.pendingPrivateBinderSyncOutbox(B).isEmpty())
            }
        }
    }

    private suspend fun withFeed(block: suspend (Feed) -> Unit) = coroutineScope {
        val channel = Channel<List<PrivateBinderCard>>(32)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.privateBinderContents(BINDER).collect { channel.send(it) }
        }
        try { block(Feed(channel)) }
        finally {
            withContext(NonCancellable) {
                job.cancel()
                try { withTimeout(5_000L) { job.join() } }
                finally { channel.close() }
            }
        }
    }

    private class Feed(private val channel: Channel<List<PrivateBinderCard>>) {
        suspend fun awaitExactly(expected: List<PrivateBinderCard>) = withTimeout(10_000L) {
            // Combine/Room may emit queued prior states. Do not require one emission/transaction.
            while (true) if (channel.receive() == expected) return@withTimeout
        }
    }

    private fun initialExpected(f: Fixture) = listOf(
        place(f.normalPlacement, expectedCard(f.normal, "Holo", "Holo", 3, 4.0, 300.0, 3_000L, choice())),
        place(f.reversePlacement, expectedCard(f.reverse, "Reverse Holo", "Reverse Holo", 2, 5.0, 0.0, 3_000L, null)),
    )

    /** Independent full constructor; no production projection/mapping helper or DAO output. */
    private fun expectedCard(entry: CollectionEntryEntity, finish: String, variant: String,
        quantity: Int, current: Double, change: Double, at: Long?, selected: CardPriceSelection?) = CollectionCard(
        entryId = entry.id, id = VISUAL, name = "Mew · Detail", set = "Detail Set", number = "090/088",
        language = CardLanguage.ENGLISH, finish = finish, variant = variant, condition = "Excellent",
        quantity = quantity, currentValueEur = current, changePercent = change, priceUpdatedAtEpochMillis = at,
        notes = "Notiz λ · Sammlung", acquisitionPriceEur = 12.34, acquisitionDateEpochDay = 20_001L,
        accent = 0xFFCE77B7, binderId = "detail-set-binder", externalProductId = 42L,
        imageUrl = "https://example.invalid/detail.png", rarity = "SPECIAL_ILLUSTRATION_RARE",
        effectOverride = "anniversary30", marketSearchName = null, marketExpansionId = null,
        sourceSetId = "detail-set", sourceCardId = "detail-set-090", priceSelection = selected,
    )

    private fun place(p: PrivateBinderPlacementEntity, c: CollectionCard) =
        PrivateBinderCard(p.id, p.binderId, p.slotPosition, c, p.holoEffectId, p.holoIntensity)

    private data class Fixture(val visual: CatalogCardEntity, val product1: CatalogCardEntity,
        val product3: CatalogCardEntity, val normal: CollectionEntryEntity, val reverse: CollectionEntryEntity,
        val other: CollectionEntryEntity, val normalPlacement: PrivateBinderPlacementEntity,
        val reversePlacement: PrivateBinderPlacementEntity, val otherPlacement: PrivateBinderPlacementEntity)

    private suspend fun seed(): Fixture = db.withTransaction {
        val visual = CatalogCardEntity(VISUAL, "POKEMON", "detail-set", "Detail Set", "Mew · Detail",
            "090/088", "ENGLISH", "Normal", "Standard", 100L, externalSource = "TCGDEX_DE",
            imageUrl = "https://example.invalid/detail.png", rarity = "SPECIAL_ILLUSTRATION_RARE",
            effectOverride = "catalog-effect-not-entry-override")
        fun product(id: String, external: Long) = CatalogCardEntity(id, "POKEMON", "market-set", "Market Set",
            "Different market product", "", "UNKNOWN", "UNKNOWN", "Cardmarket-Produkt", 100L,
            externalSource = SOURCE, externalProductId = external)
        val p1 = product(P1, 42L)
        val p2 = product(P2, 43L)
        val p3 = product(P3, 45L)
        dao.insertCatalogCards(listOf(visual, p1, p2, p3))
        dao.insertRawPrices(listOf(price(P1, 1_000L, null, 0L), price(P1, 2_000L, 100L, -1L),
            price(P1, 3_000L, 400L, 500L), price(P2, 1_000L, 700L, 900L),
            price(P3, 700L, 25L, 50L), price(P3, 3_500L, 300L, 400L),
            price(P1, 100L, 99_999L, 88_888L).copy(source = "IGNORED_SOURCE")))
        suspend fun entry(owner: String, sync: String, finish: String, variant: String, quantity: Int, product: Long): CollectionEntryEntity {
            val e = CollectionEntryEntity(cardId = VISUAL, quantity = quantity, condition = "Excellent",
                acquisitionPriceMinor = 1_234L, acquisitionCurrency = "EUR", acquisitionDateEpochDay = 20_001L,
                notes = "Notiz λ · Sammlung", frontPhotoUri = "fixture://front", backPhotoUri = null,
                createdAtEpochMillis = 100L, updatedAtEpochMillis = 100L,
                externalProductSource = SOURCE, externalProductId = product, binderId = "detail-set-binder",
                ownerUid = owner, syncId = sync, syncState = SYNC_CLEAN, remoteRevision = 1L,
                finishOverride = finish, variantOverride = variant, effectOverride = "anniversary30",
                sourceSetId = "detail-set", sourceCardId = "detail-set-090")
            return e.copy(id = dao.insertCollectionEntry(e))
        }
        val normal = entry(A, NORMAL_SYNC, "Holo", "Holo", 3, 42L)
        val reverse = entry(A, "reverse", "Reverse Holo", "Reverse Holo", 2, 42L)
        val other = entry(B, "foreign", "Normal", "Standard", 1, 43L)
        listOf(A, B).forEach { owner -> dao.insertPrivateBinder(PrivateBinderEntity(BINDER, owner,
            "Synthetic private detail", createdAtEpochMillis = 100L, updatedAtEpochMillis = 100L)) }
        suspend fun placement(id: String, entry: CollectionEntryEntity, slot: Long): PrivateBinderPlacementEntity {
            val p = PrivateBinderPlacementEntity(id, entry.ownerUid, BINDER, entry.id, slot,
                "placement-only-effect", .75f, 100L, 100L, SYNC_CLEAN, 1L)
            dao.insertPrivateBinderPlacement(p)
            return p
        }
        val pn = placement("normal-placement", normal, 0L)
        val pr = placement("reverse-placement", reverse, 1L)
        val po = placement("foreign-placement", other, 2L)
        // Existing confirmed source snapshot, now hidden by a later true trend. No remote write.
        val selected = choice()
        dao.upsertPriceSelection(CollectionPriceSelectionEntity(normal.id, A, NORMAL_SYNC, false,
            selected.sourceSetId, selected.sourceCardId, selected.collectorNumber, selected.finish,
            selected.variant, selected.language.name, selected.productId, selected.metric.wireValue,
            selected.amountMinor, selected.currency, selected.sourceAt, selected.selectedAt,
            2_000L, SYNC_CLEAN, 1L))
        Fixture(visual, p1, p3, normal, reverse, other, pn, pr, po)
    }

    private fun choice() = CardPriceSelection("detail-set", "detail-set-090", "090/088", "Holo", "Holo",
        CardLanguage.ENGLISH, 42L, CardPriceMetric.NORMAL_AVG_SELL, 532L, "EUR", 1_000L, 2_000L)
    private fun price(id: String, at: Long, normal: Long?, reverse: Long?) = RawPriceSnapshotEntity(
        cardId = id, source = SOURCE, currency = "EUR", lowValueMinor = 400L, averageValueMinor = 532L,
        trendValueMinor = normal, marketValueMinor = normal, holoTrendValueMinor = reverse,
        fetchedAtEpochMillis = at, effectiveAtEpochMillis = at,
    )

    /** Every typed column including SQLite sequences; Room's transient invalidation table is not persisted business data. */
    private fun snapshot(): Map<String, String> {
        val sql = db.openHelper.readableDatabase
        val names = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('room_master_table','android_metadata') ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertEquals("Current full persisted table inventory, including SQLite sequence", 15, names.size)
        return names.associateWith { table ->
            require(table.matches(Regex("[A-Za-z0-9_]+")))
            val columns = mutableListOf<String>()
            val primary = sortedMapOf<Int, String>()
            sql.query("PRAGMA table_info(`$table`)").use { c -> while (c.moveToNext()) {
                columns += c.getString(1)
                if (c.getInt(5) > 0) primary[c.getInt(5)] = c.getString(1)
            } }
            val order = (primary.values.toList().ifEmpty { columns }).joinToString(",") { "`$it`" }
            val digest = MessageDigest.getInstance("SHA-256")
            sql.query("SELECT * FROM `$table` ORDER BY $order").use { c -> while (c.moveToNext()) {
                repeat(c.columnCount) { i ->
                    val type = c.getType(i)
                    val bytes = when (type) {
                        Cursor.FIELD_TYPE_NULL -> byteArrayOf()
                        Cursor.FIELD_TYPE_INTEGER -> ByteBuffer.allocate(8).putLong(c.getLong(i)).array()
                        Cursor.FIELD_TYPE_FLOAT -> ByteBuffer.allocate(8).putDouble(c.getDouble(i)).array()
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> c.getString(i).toByteArray(Charsets.UTF_8)
                    }
                    digest.update(type.toByte())
                    digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                    digest.update(bytes)
                }
            } }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    companion object {
        private const val A = "private-detail-owner-a"
        private const val B = "private-detail-owner-b"
        private const val BINDER = "private-detail-binder"
        private const val VISUAL = "private-detail-visual"
        private const val P1 = "private-detail-product1"
        private const val P2 = "private-detail-product2"
        private const val P3 = "private-detail-product3"
        private const val NORMAL_SYNC = "normal"
        private const val SOURCE = CardmarketSyncContract.SOURCE
    }
}
