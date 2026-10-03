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
            assertEquals(3, dao.observeOwnedRawPriceHistory(A, SOURCE).first().size)
            assertTrue("Positive control: explicitly requested legacy history must trigger the counter", historyQueries.get() > 0)
            historyQueries.set(0)
            val portfolio = repository.portfolio.first()
            val portfolioHistoryQueries = historyQueries.get()
            assertEquals(expected.associate { it.card.entryId to it.card }, portfolio.cards.associateBy { it.entryId })
            assertEquals(before, snapshot())
            assertEquals(0, syncRequests)
            // Only this assertion should be RED on old product; value/control checks precede it.
            assertEquals("Private details need first-positive bases, not all retained owner-history rows", 0, privateHistoryQueries)
            assertEquals("Safe full portfolio uses compact history, independently of private details", 0, portfolioHistoryQueries)
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

    @Test fun safePortfolioRetainsCompleteModelWithoutWholeHistoryTransport() = runBlocking<Unit>(Dispatchers.IO) {
        withTimeout(30_000L) {
            val f = seed()
            val before = snapshot()
            historyQueries.set(0)
            val legacyRows = dao.observeOwnedRawPriceHistory(A, SOURCE).first()
            assertEquals(3, legacyRows.size)
            assertTrue("Counter control: explicitly requesting the retained history is observed", historyQueries.get() > 0)
            historyQueries.set(0)
            val actual = repository.portfolio.first()
            val actualHistoryQueries = historyQueries.get()
            val month = java.time.Instant.ofEpochMilli(1_000L).atZone(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM"))
            val expected = PortfolioSummary(initialExpected(f).map { it.card },
                listOf(PortfolioPoint(month, 0.0), PortfolioPoint(month, 3.0), PortfolioPoint(month, 22.0)))
            assertEquals("Independent complete cards, without inventing a SQL tie order",
                expected.cards.associateBy { it.entryId }, actual.cards.associateBy { it.entryId })
            assertEquals(expected.cards.size, actual.cards.size)
            assertEquals("Exact ordered history before transport assertion", expected.history, actual.history)
            assertEquals(before, snapshot())
            assertEquals(0, syncRequests)
            // This is deliberately the final assertion and the only expected pre-fix failure.
            assertEquals("Safe portfolio must not materialize the complete owner history", 0, actualHistoryQueries)
        }
    }

    @Test fun sustainedPortfolioRetainsCompleteModelsAcrossBoundsLinksOwnersAndInvalidPrices() = runBlocking<Unit>(Dispatchers.IO) {
        withTimeout(120_000L) {
            val f = seed()
            // Characterize only the platform's literal Unicode LIKE behavior. This reads
            // neither fixture data nor a production DAO/mapping/pricing result.
            val unicodeSqlReverse = db.openHelper.readableDatabase
                .query("SELECT lower('reverſe') LIKE '%reverse%'").use { c ->
                    assertEquals(1, c.columnCount)
                    assertEquals(1, c.count)
                    assertTrue(c.moveToFirst())
                    assertEquals(Cursor.FIELD_TYPE_INTEGER, c.getType(0))
                    val value = c.getLong(0)
                    assertTrue("Literal Unicode characterization must be exactly boolean", value == 0L || value == 1L)
                    assertFalse(c.moveToNext())
                    value == 1L
                }
            android.util.Log.i("DomiDexDataPerf", "literalUnicodeReverseLike=$unicodeSqlReverse")
            val oracle = PortfolioOracle(f, unicodeSqlReverse)
            val channel = Channel<PortfolioSummary>(64)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.portfolio.collect { channel.send(it) }
            }
            try {
                suspend fun expect(label: String) {
                    val before = snapshot()
                    val expected = oracle.expected(owner.value)
                    var lastObserved: PortfolioSummary? = null
                    try {
                        withTimeout(10_000L) {
                            while (true) {
                                val actual = channel.receive()
                                lastObserved = actual
                                // Equal update timestamps have no defined row tie order. Compare every
                                // card field by stable entry identity, but retain every history position.
                                if (actual.cards.associateBy { it.entryId } == expected.cards.associateBy { it.entryId } &&
                                    actual.cards.size == expected.cards.size && actual.history == expected.history) break
                            }
                        }
                    } catch (failure: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        throw AssertionError("$label: unchanged10s model deadline; expected=$expected; last=$lastObserved", failure)
                    }
                    assertEquals("$label: no persisted business-data write during observation", before, snapshot())
                    assertEquals(0, syncRequests)
                }
                suspend fun replace(p: RawPriceSnapshotEntity) {
                    dao.insertRawPricesReplacing(listOf(p))
                    oracle.prices.removeAll { it.cardId == p.cardId && it.source == p.source &&
                        it.effectiveAtEpochMillis == p.effectiveAtEpochMillis }
                    oracle.prices += p
                }
                suspend fun normal(e: CollectionEntryEntity) {
                    dao.updateCollectionEntry(e)
                    oracle.normal = e
                }
                suspend fun reverse(e: CollectionEntryEntity) {
                    dao.updateCollectionEntry(e)
                    oracle.reverse = e
                }
                expect("initial full model")
                replace(price(P1, 2_000L, 200L, 250L))
                expect("same-date correction changes first positives")
                replace(price(P1, 500L, 50L, 0L))
                expect("earlier normal-only positive and extra timestamp")
                replace(price(P1, 3_000L, null, Long.MIN_VALUE))
                expect("latest null/negative reset, never carry the previous positive")
                replace(price(P1, 3_000L, 100_000_000L, 100_000_000L))
                normal(oracle.normal.copy(quantity = 1_000_000))
                reverse(oracle.reverse.copy(quantity = -1_000_000))
                expect("inclusive price and signed-quantity bounds")
                replace(price(P1, 3_000L, 100_000_001L, 100_000_001L))
                expect("one above either price bound enters unchanged fallback")
                replace(price(P1, 3_000L, Long.MAX_VALUE, Long.MAX_VALUE))
                normal(oracle.normal.copy(quantity = Int.MAX_VALUE))
                reverse(oracle.reverse.copy(quantity = 0))
                expect("Long wrapping and Int maximum, fallback remains active")
                replace(price(P1, 3_000L, 600L, 700L))
                expect("price-only invalidation while the unsafe sentinel is unchanged")
                normal(oracle.normal.copy(quantity = Int.MIN_VALUE))
                expect("Int minimum fallback retains wrapping arithmetic")
                normal(oracle.normal.copy(quantity = 1_000_001))
                expect("quantity just above bound")
                normal(oracle.normal.copy(quantity = -1_000_001))
                expect("quantity just below bound")
                normal(oracle.normal.copy(quantity = 3))
                reverse(oracle.reverse.copy(quantity = 2))
                expect("unsafe to safe without replacing subscription")
                reverse(oracle.reverse.copy(finishOverride = "reverſe"))
                expect("Unicode finish preserves characterized SQL and independent Kotlin semantics via fallback")
                reverse(oracle.reverse.copy(finishOverride = "rEvErSe Holo"))
                expect("ASCII mixed-case reverse is safe")
                normal(oracle.normal.copy(externalProductId = 43L))
                expect("asynchronous product timestamps with carry forward")
                normal(oracle.normal.copy(externalProductSource = "OTHER_PRICE_SOURCE"))
                expect("price source change removes link and saved selection from model")
                normal(oracle.normal.copy(externalProductSource = SOURCE))
                expect("price source restoration")
                dao.upsertCatalogCards(listOf(f.product1.copy(externalProductId = 44L)))
                oracle.links.remove(42L)
                oracle.links[44L] = P1
                expect("catalog-only unlink drops that product's timestamps")
                dao.upsertCatalogCards(listOf(f.product3.copy(externalProductId = 42L)))
                oracle.links.remove(45L)
                oracle.links[42L] = P3
                expect("catalog-only relink adds different asynchronous timestamps")
                val saved = requireNotNull(dao.priceSelection(A, NORMAL_SYNC))
                oracle.selection = choice().copy(amountMinor = 600L, selectedAt = 6_000L)
                dao.upsertPriceSelection(saved.copy(amountMinor = 600L, selectedAt = 6_000L, updatedAtEpochMillis = 6_000L))
                expect("selection-table-only invalidation retains hidden explicit choice")
                owner.value = B
                expect("foreign owner isolated")
                owner.value = "private-detail-empty-owner"
                expect("empty owner")
                owner.value = A
                expect("owner restoration")
                normal(oracle.normal.copy(deletedAtEpochMillis = 9_000L))
                expect("normal tombstone")
                reverse(oracle.reverse.copy(deletedAtEpochMillis = 9_000L))
                expect("all rows tombstoned leaves no timeline")
                normal(oracle.normal.copy(deletedAtEpochMillis = null))
                reverse(oracle.reverse.copy(deletedAtEpochMillis = null))
                expect("restored rows")
                normal(oracle.normal.copy(externalProductId = 670816L, sourceCardId = "swsh11-201"))
                expect("known quarantine remains fallback with complete metadata")
                normal(oracle.normal.copy(externalProductId = 43L, sourceCardId = "detail-set-090"))
                expect("quarantine to safe restoration")
                assertTrue(dao.pendingSyncOutbox(A).isEmpty())
                assertTrue(dao.pendingPrivateBinderSyncOutbox(A).isEmpty())
                assertTrue(dao.pendingSyncOutbox(B).isEmpty())
                assertTrue(dao.pendingPrivateBinderSyncOutbox(B).isEmpty())
            } finally {
                withContext(NonCancellable) {
                    collector.cancel()
                    try { withTimeout(5_000L) { collector.join() } }
                    finally { channel.close() }
                }
            }
        }
    }

    /** Fixture-only independent oracle. It does not query the DAO or invoke production mapping,
     * link/quarantine helpers, the chronological sweep, or the proposed aggregate adapter. */
    private inner class PortfolioOracle(f: Fixture, private val unicodeSqlReverse: Boolean) {
        var normal = f.normal
        var reverse = f.reverse
        private val other = f.other
        var selection = choice()
        val links = mutableMapOf(42L to P1, 43L to P2, 45L to P3)
        val prices = mutableListOf(price(P1, 1_000L, null, 0L), price(P1, 2_000L, 100L, -1L),
            price(P1, 3_000L, 400L, 500L), price(P2, 1_000L, 700L, 900L),
            price(P3, 700L, 25L, 50L), price(P3, 3_500L, 300L, 400L),
            price(P1, 100L, 99_999L, 88_888L).copy(source = "IGNORED_SOURCE"))

        private fun quarantined(e: CollectionEntryEntity) =
            e.externalProductId == 670816L && e.sourceCardId == "swsh11-201"
        private fun product(e: CollectionEntryEntity): String? = when {
            quarantined(e) -> null
            e.externalProductSource == SOURCE -> links[e.externalProductId] ?: VISUAL
            else -> VISUAL
        }
        private fun sourcePrices(e: CollectionEntryEntity) = prices.filter {
            it.source == SOURCE && it.cardId == product(e)
        }.sortedBy { it.effectiveAtEpochMillis }
        private fun normalized(p: RawPriceSnapshotEntity?, reverse: Boolean): Long =
            (if (reverse) p?.holoTrendValueMinor else p?.trendValueMinor)?.takeIf { it > 0L } ?: 0L

        fun expected(currentOwner: String): PortfolioSummary {
            val entries = listOf(normal, reverse, other).filter {
                it.ownerUid == currentOwner && it.deletedAtEpochMillis == null
            }
            val cards = entries.map { e ->
                val finish = requireNotNull(e.finishOverride)
                val history = sourcePrices(e)
                val reverseInKotlin = finish.contains("reverse", ignoreCase = true)
                val asciiLower = finish.map { if (it in 'A'..'Z') (it.code + 32).toChar() else it }.joinToString("")
                // ICU-capable Android SQLite may fold long-s for LIKE. Only this exact
                // fixture literal uses engine characterization; all ASCII cases stay explicit.
                val reverseInSql = if (finish == "reverſe") unicodeSqlReverse else asciiLower.contains("reverse")
                val latest = normalized(history.lastOrNull(), reverseInSql)
                val first = history.asSequence().map { normalized(it, reverseInKotlin) }.firstOrNull { it > 0L }
                val change = if (first != null && latest > 0L) (latest - first) * 100.0 / first else 0.0
                expectedCard(e, finish, requireNotNull(e.variantOverride), e.quantity, latest / 100.0,
                    change, history.lastOrNull()?.effectiveAtEpochMillis?.takeIf { latest > 0L },
                    selection.takeIf { e.id == normal.id && e.ownerUid == A && e.externalProductSource == SOURCE })
                    .copy(externalProductId = e.externalProductId, sourceCardId = e.sourceCardId, sourceSetId = e.sourceSetId)
            }
            val products = entries.mapNotNull(::product).toSet()
            val dates = prices.filter { it.source == SOURCE && it.cardId in products }
                .map { it.effectiveAtEpochMillis }.distinct().sorted()
            val timeline = dates.map { at ->
                // BigInteger last-as-of oracle, then explicit low 64 bits. Deliberately not
                // the event-delta algorithm or SQL SUM: also catches fallback Long wrapping.
                val exact = entries.fold(java.math.BigInteger.ZERO) { total, e ->
                    val p = sourcePrices(e).lastOrNull { it.effectiveAtEpochMillis <= at }
                    val value = normalized(p, requireNotNull(e.finishOverride).contains("reverse", ignoreCase = true))
                    total + java.math.BigInteger.valueOf(value).multiply(java.math.BigInteger.valueOf(e.quantity.toLong()))
                }
                val month = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM"))
                PortfolioPoint(month, exact.toLong() / 100.0)
            }
            return PortfolioSummary(cards, timeline)
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
