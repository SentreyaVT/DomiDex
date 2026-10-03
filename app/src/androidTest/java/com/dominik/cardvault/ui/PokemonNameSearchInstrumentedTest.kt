package com.dominik.cardvault.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.R
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.catalog.PokemonCatalogMetadataCache
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.RawPriceSnapshotEntity
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import com.dominik.cardvault.model.CatalogSearchResult
import com.dominik.cardvault.model.CollectionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real picker/AsyncImage and Room, with synthetic source JSON and owned local PNGs only. */
@RunWith(AndroidJUnit4::class)
class PokemonNameSearchInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: CardVaultDatabase
    private lateinit var repository: CollectionRepository
    private lateinit var fixture: Fixture
    private lateinit var imageRoot: File
    private var ownsImageRoot = false
    private var contentStarted = false
    private val showPicker = mutableStateOf(true)
    private val marketNameSearches = AtomicInteger()
    private val productRequests = CopyOnWriteArrayList<List<Long>>()
    private val selection = AtomicReference<CatalogSearchResult?>()

    @Before fun setUp() {
        imageRoot = File(context.cacheDir, "pokemon-name-search-${UUID.randomUUID()}").canonicalFile
        check(imageRoot.parentFile == context.cacheDir.canonicalFile && !imageRoot.exists())
        check(imageRoot.mkdir())
        ownsImageRoot = true
        val red = createImage("glurak.png", Color.rgb(220, 36, 48))
        val green = createImage("bisaflor.png", Color.rgb(32, 180, 76))
        fixture = Fixture(red, green)
        database = Room.inMemoryDatabaseBuilder(context, CardVaultDatabase::class.java).build()
        val local = RoomCollectionRepository(database, MutableStateFlow("synthetic-name-search-owner"))
        repository = object : CollectionRepository by local {
            override suspend fun searchCatalog(query: String, limit: Int): List<CatalogSearchResult> {
                marketNameSearches.incrementAndGet()
                error("German visual search must not call the English price-product name search")
            }
            override suspend fun catalogProducts(productIds: List<Long>): List<CatalogSearchResult> {
                productRequests += productIds.toList()
                return local.catalogProducts(productIds)
            }
        }
        runBlocking {
            seedMarketProduct(EXACT_PRODUCT, "Charizard", 123L)
            seedMarketProduct(WRONG_PRODUCT, "Charizard", 99_999L)
            seedMarketProduct(UNLINKED_NAMESAKE, "Venusaur", 88_888L)
        }
    }

    @After fun tearDown() {
        try {
            if (contentStarted) {
                compose.runOnIdle { showPicker.value = false }
                compose.waitForIdle()
            }
            if (::database.isInitialized) database.close()
        } finally {
            if (ownsImageRoot) {
                check(imageRoot.parentFile == context.cacheDir.canonicalFile)
                val files = checkNotNull(imageRoot.listFiles())
                check(files.all { it.isFile && it.name in setOf("glurak.png", "bisaflor.png") && it.canonicalFile.parentFile == imageRoot })
                files.forEach { check(it.delete()) }
                check(imageRoot.delete())
            }
        }
    }

    @Test fun germanNameSearchShowsImageAndRetainsExactProductIdentity() {
        openSearch()
        search("Glurak")
        awaitResult(GLURAK)
        result(GLURAK).assert(hasText("Glurak")).assert(hasText(SET_A_NAME))
            .assert(hasText("#162", substring = true))
        assertRealImage("Glurak", "162", Color.rgb(220, 36, 48))
        compose.onNodeWithText("Charizard").assertDoesNotExist()
        compose.onNodeWithText("Cardmarket-Preis", substring = true).assertDoesNotExist()
        result(GLURAK).performClick()
        awaitAddButton()
        compose.onNodeWithText(context.getString(R.string.action_back)).performClick()
        compose.onNodeWithTag(INPUT).assertTextContains("Glurak")
        awaitResult(GLURAK)
        result(GLURAK).performClick()
        addSelectedCard()
        val selected = requireNotNull(selection.get())
        assertEquals("Glurak", selected.name)
        assertEquals(SET_A, selected.sourceSetId)
        assertEquals(GLURAK, selected.sourceCardId)
        assertEquals("162", selected.collectorNumber)
        assertEquals(SET_A_NAME, selected.visualSetName)
        assertEquals(fixture.redImage, selected.imageUrl)
        assertEquals("Holo", selected.finish)
        assertEquals("Standard", selected.variant)
        assertTrue(selected.hasMarketProduct)
        assertEquals(EXACT_PRODUCT, selected.productId)
        assertEquals(1.23, requireNotNull(selected.trendPriceEur), 0.0)
        assertTrue(productRequests.isNotEmpty())
        assertTrue(productRequests.all { it == listOf(EXACT_PRODUCT) })
        assertSourceOnly()
    }

    @Test fun unlinkedGermanCardDoesNotBorrowEnglishNamesakePrice() {
        openSearch()
        search("Bisaflor")
        awaitResult(BISAFLOR)
        result(BISAFLOR).assert(hasText("Bisaflor")).assert(hasText(SET_B_NAME))
        assertRealImage("Bisaflor", "003", Color.rgb(32, 180, 76))
        compose.onNodeWithText("Venusaur").assertDoesNotExist()
        result(BISAFLOR).performClick()
        addSelectedCard()
        val selected = requireNotNull(selection.get())
        assertEquals("Bisaflor", selected.name)
        assertEquals(SET_B, selected.sourceSetId)
        assertEquals(BISAFLOR, selected.sourceCardId)
        assertEquals("003", selected.collectorNumber)
        assertEquals(fixture.greenImage, selected.imageUrl)
        assertFalse(selected.hasMarketProduct)
        assertEquals(0L, selected.productId)
        assertNull(selected.trendPriceEur)
        assertTrue("No undeclared product may be requested", productRequests.isEmpty())
        assertSourceOnly()
    }

    @Test fun paginationKeepsDistinctSetsAndSecretCollectorNumbers() {
        fixture.paged.set(true)
        openSearch()
        search("Glurak")
        awaitResult(GLURAK)
        result(GLURAK).assert(hasText("#162", substring = true)).assert(hasText(SET_A_NAME))
        scrollTo(hasText("Mehr Karten laden"))
        compose.onNodeWithText("Mehr Karten laden").performClick()
        compose.waitUntil(10_000) { fixture.searchRequests.contains("Glurak:2") }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Mehr Karten laden").fetchSemanticsNodes().isEmpty() }
        scrollTo(hasTestTag("pokemon-search-result-$OTHER_GLURAK"))
        result(OTHER_GLURAK).assertIsDisplayed().assert(hasText("Glurak"))
            .assert(hasText(SET_B_NAME)).assert(hasText("#162", substring = true))
        compose.onNodeWithText("Mehr Karten laden").assertDoesNotExist()
        scrollTo(hasTestTag("pokemon-search-result-$GLURAK"))
        result(GLURAK).assertIsDisplayed().assert(hasText(SET_A_NAME))
        assertEquals(listOf("Glurak:1", "Glurak:2"), fixture.searchRequests.toList())
        assertTrue(productRequests.isEmpty())
        assertSourceOnly()
    }

    @Test fun failedAndShortQueriesClearStaleResultsAndAllowRetry() {
        openSearch()
        search("Bisaflor")
        awaitResult(BISAFLOR)
        fixture.failNextGlurak.set(true)
        search("Glurak")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(SEARCH_ERROR).fetchSemanticsNodes().isNotEmpty() }
        result(BISAFLOR).assertDoesNotExist()
        result(GLURAK).assertDoesNotExist()
        compose.onNodeWithText("Charizard").assertDoesNotExist()
        compose.onNodeWithText("Erneut versuchen").performClick()
        awaitResult(GLURAK)
        compose.onNodeWithText(SEARCH_ERROR).assertDoesNotExist()
        val count = fixture.searchRequests.size
        search("G")
        compose.onNodeWithText("Mindestens zwei Zeichen", substring = true).assertExists()
        result(GLURAK).assertDoesNotExist()
        result(BISAFLOR).assertDoesNotExist()
        search("")
        compose.onNodeWithText("Mindestens zwei Zeichen", substring = true).assertExists()
        result(GLURAK).assertDoesNotExist()
        compose.waitForIdle()
        assertEquals("Short/blank input must not start another source query", count, fixture.searchRequests.size)
        assertEquals(listOf("Bisaflor:1", "Glurak:1", "Glurak:1"), fixture.searchRequests.toList())
        assertTrue(productRequests.isEmpty())
        assertSourceOnly()
    }

    private fun openSearch() {
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache(), fixture::read)
        compose.setContent { MaterialTheme {
            if (showPicker.value) CatalogProductPicker(repository, onDismiss = {}, visualCatalog = catalog) { selection.set(it) }
        } }
        contentStarted = true
        compose.waitUntil(10_000) { compose.onAllNodesWithText(SET_A_NAME).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pokémon suchen").performClick()
    }

    private fun search(query: String) { compose.onNodeWithTag(INPUT).performTextReplacement(query) }
    private fun result(id: String) = compose.onNodeWithTag("pokemon-search-result-$id")
    private fun awaitResult(id: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("pokemon-search-result-$id").fetchSemanticsNodes().isNotEmpty()
    }
    private fun scrollTo(matcher: SemanticsMatcher) { compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher) }
    private fun addButton() = compose.onNode(hasText("Zum Binder hinzufügen", substring = true) and hasClickAction())
    private fun awaitAddButton() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Zum Binder hinzufügen", substring = true) and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun addSelectedCard() {
        awaitAddButton()
        addButton().performScrollTo().performClick()
        compose.waitUntil(10_000) { selection.get() != null }
    }

    /** Real AsyncImage decoding of a local PNG, not merely an image-looking semantics label. */
    private fun assertRealImage(name: String, number: String, color: Int) {
        val description = context.getString(R.string.catalog_card_image_description, name, number)
        val image = compose.onNodeWithContentDescription(description, useUnmergedTree = true)
        image.assertExists().performScrollTo().assertIsDisplayed()
        compose.waitUntil(10_000) {
            val pixels = image.captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            kotlin.math.abs(center.red - Color.red(color) / 255f) < .03f &&
                kotlin.math.abs(center.green - Color.green(color) / 255f) < .03f &&
                kotlin.math.abs(center.blue - Color.blue(color) / 255f) < .03f
        }
    }

    private fun assertSourceOnly() {
        assertEquals(0, marketNameSearches.get())
        assertTrue("No unexpected or English source fallback: ${fixture.unexpected}", fixture.unexpected.isEmpty())
    }

    private fun createImage(name: String, color: Int): String {
        val file = File(imageRoot, name)
        check(file.createNewFile())
        val bitmap = Bitmap.createBitmap(72, 101, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        return Uri.fromFile(file).toString()
    }

    private suspend fun seedMarketProduct(product: Long, name: String, price: Long) {
        val dao = database.cardVaultDao()
        dao.insertCatalogCard(CatalogCardEntity("cm-pokemon-$product", "POKEMON", "synthetic-market-set", "English market set",
            name, "", "UNKNOWN", "UNKNOWN", "Cardmarket-Produkt", 1L,
            externalSource = CardmarketSyncContract.SOURCE, externalProductId = product))
        dao.insertRawPrices(listOf(RawPriceSnapshotEntity(cardId = "cm-pokemon-$product", source = CardmarketSyncContract.SOURCE,
            currency = "EUR", lowValueMinor = null, averageValueMinor = null, trendValueMinor = price,
            marketValueMinor = price, holoTrendValueMinor = null, fetchedAtEpochMillis = 1L, effectiveAtEpochMillis = 1L)))
    }

    private class Fixture(val redImage: String, val greenImage: String) {
        val paged = AtomicBoolean()
        val failNextGlurak = AtomicBoolean()
        val searchRequests = CopyOnWriteArrayList<String>()
        val unexpected = CopyOnWriteArrayList<String>()

        fun read(raw: String): BufferedSource {
            val url = raw.toHttpUrl()
            fun reject(): Nothing { unexpected += raw; error("Unexpected synthetic catalogue request: $raw") }
            if (url.host != "api.tcgdex.net" || url.scheme != "https") reject()
            val json = when (url.encodedPath) {
                "/v2/de/sets" -> {
                    if (url.query != null) reject()
                    JSONArray().put(set(SET_A, SET_A_NAME)).put(set(SET_B, SET_B_NAME)).toString()
                }
                "/v2/de/series/tcgp" -> {
                    if (url.query != null) reject()
                    """{"id":"tcgp","sets":[{"id":"A1","name":"Pocket"}]}"""
                }
                "/v2/de/cards" -> {
                    if (url.queryParameterNames != setOf("name", "pagination:page", "pagination:itemsPerPage") ||
                        url.queryParameter("pagination:itemsPerPage") != "40") reject()
                    val name = url.queryParameter("name")?.takeIf { it.startsWith("like:") }?.removePrefix("like:") ?: reject()
                    val page = url.queryParameter("pagination:page")?.toIntOrNull() ?: reject()
                    searchRequests += "$name:$page"
                    if (name == "Glurak" && failNextGlurak.compareAndSet(true, false)) throw IOException("Synthetic source unavailable")
                    when {
                        name == "Bisaflor" && page == 1 -> JSONArray().put(card(BISAFLOR, "003", "Bisaflor", greenImage))
                        name == "Glurak" && page == 1 -> JSONArray().put(card(GLURAK, "162", "Glurak", redImage)).apply {
                            if (paged.get()) repeat(39) { index ->
                                val number = (index + 1).toString().padStart(3, '0')
                                put(card("$SET_A-$number", number, "Glurak", redImage))
                            }
                        }
                        name == "Glurak" && page == 2 && paged.get() -> JSONArray().put(card(OTHER_GLURAK, "162", "Glurak", redImage))
                        else -> reject()
                    }.toString()
                }
                "/v2/de/cards/$GLURAK" -> detail(EXACT_PRODUCT)
                "/v2/de/cards/$BISAFLOR" -> detail(null)
                else -> reject()
            }
            return Buffer().writeUtf8(json)
        }

        private fun set(id: String, name: String) = JSONObject().put("id", id).put("name", name)
            .put("logo", redImage).put("serie", JSONObject().put("id", "sv").put("name", "Deutsche Testserie"))
            .put("cardCount", JSONObject().put("official", 100).put("total", 200))
        private fun card(id: String, local: String, name: String, image: String) = JSONObject()
            .put("id", id).put("localId", local).put("name", name).put("image", image)
        private fun detail(product: Long?): String {
            val variant = JSONObject().put("variantId", "holo").put("type", "Holo").put("size", "Standard")
            if (product != null) variant.put("thirdParty", JSONObject().put("cardmarket", product))
            return JSONObject().put("rarity", "Rare").put("variants_detailed", JSONArray().put(variant)).toString()
        }
    }

    private companion object {
        const val INPUT = "pokemon-search-input"
        const val SET_A = "search-a"
        const val SET_B = "search-b"
        const val SET_A_NAME = "Deutsches Testset A"
        const val SET_B_NAME = "Deutsches Testset B"
        const val GLURAK = "search-a-162"
        const val OTHER_GLURAK = "search-b-162"
        const val BISAFLOR = "search-b-003"
        const val EXACT_PRODUCT = 900_001L
        const val WRONG_PRODUCT = 900_002L
        const val UNLINKED_NAMESAKE = 900_003L
        const val SEARCH_ERROR = "Suche konnte nicht geladen werden. Bitte erneut versuchen."
    }
}
