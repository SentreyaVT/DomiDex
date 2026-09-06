package com.dominik.cardvault.ui

import com.dominik.cardvault.data.WachsendesChaosMasterSet
import com.dominik.cardvault.data.catalog.PokemonCatalogMetadataCache
import com.dominik.cardvault.data.catalog.PokemonPrintVariant
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.data.sync.CardmarketParsers
import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CatalogSearchResult
import com.dominik.cardvault.model.CollectionCard
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Offline diagnostic over a separately downloaded, dated public-source snapshot.
 * A green JUnit result means the audit ran and wrote every row, NOT that every card passed.
 * Never opens Room, a device, Firebase, or the network. Missing details are report failures.
 * Run with DOMIDEX_AUDIT_DIR pointing to another snapshot to override the dated default.
 */
class AllBinderPriceAuditTest {
    private val root = File(System.getProperty("domidex.audit.dir")
        ?: System.getenv("DOMIDEX_AUDIT_DIR")
        ?: "C:/Users/Dominik/AppData/Local/Temp/domidex-all-binder-audit-20260906")

    private data class CardAudit(
        val card: PokemonVisualCard,
        val detailStatus: String,
        val rawVariantCount: Int,
        val issues: List<String>,
        val manualIssues: List<String>,
    )

    @Test fun auditEverySourceCardAndActualBinderSlotWithoutChangingACollection() = runBlocking {
        assumeTrue("Optional dated public snapshot is absent: $root", root.isDirectory)
        val started = Instant.now().toString()
        val required = listOf("sets-de.json", "cards-de.json", "cards-en.json", "products.json", "prices.json")
        required.forEach { assertTrue("Snapshot input missing: $it", File(root, it).isFile) }
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            val file = when {
                url.endsWith("/de/sets") -> File(root, "sets-de.json")
                url.contains("/de/cards/") || url.contains("/en/cards/") -> {
                    val cardId = URLDecoder.decode(url.substringAfterLast('/'), StandardCharsets.UTF_8.name())
                    File(root, "cards/$cardId.json")
                }
                // This snapshot has no English set logos; the production optional fallback handles it.
                else -> throw IllegalStateException("No snapshot response for $url")
            }
            file.source().buffer()
        }
        val sets = catalog.sets()
        assertTrue("No valid German source sets", sets.isNotEmpty())
        val pocketFile = File(root, "series-tcgp.json")
        val pocketSetIds = if (pocketFile.isFile) rows(objectRow(readJson(pocketFile))["sets"])
            .map { it.text("id") }.toSet() else emptySet()
        val orderedSetIds = sets.map { it.id }.sortedByDescending(String::length)
        val setsById = sets.associateBy { it.id }
        val germanRows = rows(readJson(File(root, "cards-de.json")))
        val englishRows = rows(readJson(File(root, "cards-en.json")))
        val german = germanRows.associateBy { it.text("id") }
        val english = englishRows.associateBy { it.text("id") }
        val allBriefIds = (german.keys + english.keys).sorted()
        val excludedIds = mutableListOf<String>()
        val cardsBySet = linkedMapOf<String, MutableList<PokemonVisualCard>>()
        for (id in allBriefIds) {
            val setId = orderedSetIds.firstOrNull { id.startsWith("$it-") }
            if (setId == null) { excludedIds += id; continue }
            val de = german[id]
            val en = english[id]
            val row = de ?: en!!
            val sourceSet = setsById.getValue(setId).copy(seriesId = "tcgp".takeIf { setId in pocketSetIds })
            val card = PokemonVisualCard(
                id = id, name = row.text("name"), number = row.text("localId"),
                rarity = row["rarity"] as? String, smallImageUrl = "", largeImageUrl = "",
                set = sourceSet, marketSearchName = en?.text("name"),
                hasGermanLocalizedName = de != null,
            )
            cardsBySet.getOrPut(setId) { mutableListOf() } += card
        }
        val productsDownload = File(root, "products.json").source().buffer().use(CardmarketParsers::products)
        val pricesDownload = File(root, "prices.json").source().buffer().use(CardmarketParsers::prices)
        val prices = pricesDownload.records.associateBy { it.productId }
        val products = productsDownload.records.associate { product ->
            val price = prices[product.id]
            product.id to CatalogSearchResult(
                productId = product.id, name = product.name,
                expansionId = product.expansionId?.toString() ?: "unknown",
                trendPriceEur = price?.trendMinor?.div(100.0), currency = if (price == null) null else "EUR",
                finish = "UNKNOWN", variant = "Cardmarket-Produkt",
                holoTrendPriceEur = price?.holoTrendMinor?.takeUnless { it == 0L }?.div(100.0),
            )
        }
        val productsByExpansion = products.values.groupBy { it.expansionId }
        val setReports = mutableListOf<Map<String, Any?>>()
        val allSlotStatuses = linkedMapOf<String, Int>()
        val allCardIssues = linkedMapOf<String, Int>()
        var visitedCards = 0
        var visitedSlots = 0
        var sourcePrintingCount = 0
        var binderPrintingCount = 0
        var detailSuccesses = 0
        var independentlyVerifiedPricedSlots = 0
        val slotFile = File(root, "binder-price-audit-slots.csv")
        val cardFile = File(root, "binder-price-audit-cards.csv")

        slotFile.bufferedWriter(Charsets.UTF_8).use { slotWriter ->
            cardFile.bufferedWriter(Charsets.UTF_8).use { cardWriter ->
                slotWriter.write(csv(listOf("setId", "setName", "slotIndex", "slotId", "number", "finish", "variant",
                    "sourceSetId", "sourceCardId", "detailStatus", "declaredProductIds", "actualProductIds",
                    "mappingStatus", "sourceMatch", "printingMatch", "priceFieldMatch", "regularPriceAvailability",
                    "reversePriceAvailability", "displayedPriceEur", "independentlyVerifiedPriced", "issues")))
                cardWriter.write(csv(listOf("setId", "cardId", "name", "number", "hasGermanName", "detailStatus",
                    "rawVariants", "parsedSourcePrintings", "binderPrintings", "declaredProductIds",
                    "manualSelectionIssues", "issues")))
                for (set in sets) {
                    val sourceCards = cardsBySet[set.id].orEmpty()
                    val setCardIssues = linkedMapOf<String, Int>()
                    val audits = sourceCards.map { source ->
                        visitedCards++
                        val issues = mutableListOf<String>()
                        if (source.name.isBlank()) issues += "SOURCE_NAME_MISSING"
                        if (source.number.isBlank()) issues += "SOURCE_NUMBER_MISSING"
                        val file = File(root, "cards/${source.id}.json")
                        var rawVariants = 0
                        var status = "DETAIL_OK"
                        val detailed = if (!file.isFile) {
                            status = "DETAIL_MISSING"
                            source
                        } else try {
                            val raw = objectRow(readJson(file))
                            if (raw.text("id") != source.id) issues += "DETAIL_CARD_ID_MISMATCH"
                            val rawSet = objectRow(raw["set"])
                            if (rawSet.isNotEmpty() && rawSet.text("id") != source.set.id) issues += "DETAIL_SET_ID_MISMATCH"
                            if (raw.text("localId").isNotBlank() && raw.text("localId") != source.number) issues += "DETAIL_NUMBER_MISMATCH"
                            rawVariants = rows(raw["variants_detailed"]).size
                            if (rawVariants == 0) issues += "SOURCE_VARIANTS_MISSING"
                            catalog.withDeclaredPrintVariants(source).also { detailSuccesses++ }
                        } catch (error: Exception) {
                            status = "DETAIL_PARSE_ERROR"
                            issues += "DETAIL_EXCEPTION_${error.javaClass.simpleName}"
                            source
                        }
                        if (status != "DETAIL_OK") issues += status
                        val printings = detailed.binderPrintVariants()
                        val ids = detailed.printVariants.mapNotNull { it.cardmarketProductId }.distinct()
                        if (ids.isEmpty()) issues += "DECLARED_PRODUCT_ID_MISSING"
                        if (ids.any { it !in products }) issues += "DECLARED_PRODUCT_NOT_IN_SNAPSHOT"
                        val candidates = candidates(detailed, products, productsByExpansion)
                        val linked = visualCardmarketProducts(detailed, sourceCards, candidates)
                        val manualIssues = mutableListOf<String>()
                        // Exercise even an unpriced visual selection; it must retain its exact source identity.
                        val manualProducts: List<CatalogSearchResult?> = if (linked.isEmpty()) listOf(null) else linked
                        manualProducts.forEach { product ->
                            val selected = catalogResultForVisualCard(detailed, product)
                            if (selected.sourceSetId != detailed.set.id.lowercase() || selected.sourceCardId != detailed.id.lowercase() ||
                                selected.collectorNumber != detailed.number || selected.name != detailed.name ||
                                selected.visualSetName != detailed.set.name) manualIssues += "MANUAL_SOURCE_IDENTITY_MISMATCH"
                            // A manually selected special printing intentionally lives outside
                            // the canonical Binder wall, but must retain its exact source variant.
                            if (detailed.printVariants.none { it.finish == selected.finish && it.variant == selected.variant }) {
                                manualIssues += "MANUAL_PRINTING_MISMATCH"
                            }
                            if (product != null && selected.productId != product.productId) manualIssues += "MANUAL_PRODUCT_MISMATCH"
                            if (product != null && selected.trendPriceEur != product.realTrendFor(selected.finish.orEmpty())) manualIssues += "MANUAL_PRICE_FIELD_MISMATCH"
                        }
                        (issues + manualIssues).distinct().forEach { increment(setCardIssues, it); increment(allCardIssues, it) }
                        sourcePrintingCount += detailed.printVariants.size
                        binderPrintingCount += printings.size
                        cardWriter.write(csv(listOf(set.id, detailed.id, detailed.name, detailed.number,
                            detailed.hasGermanLocalizedName, status, rawVariants, detailed.printVariants.size, printings.size,
                            ids.joinToString("|"), manualIssues.distinct().joinToString("|"), issues.distinct().joinToString("|"))))
                        CardAudit(detailed, status, rawVariants, issues, manualIssues.distinct())
                    }
                    val byId = audits.associateBy { it.card.id.lowercase() }
                    val detailedCards = audits.map { it.card }
                    val references = detailedCards.flatMap { card -> card.binderPrintVariants().map { reference(card, it) } }
                    // The actual special binder branch can replace all canonical references with source-less rows.
                    // cardCapacityFor uses the fixed 194-slot master for this source set, even if details failed.
                    val capacity = if (set.id == "me04") WachsendesChaosMasterSet.cards.size else maxOf(set.total, references.size)
                    val arranged = arrangeBinderCards(set.name, emptyList(), capacity, references)
                    val slotStatuses = linkedMapOf<String, Int>()
                    val slotIssues = linkedMapOf<String, Int>()
                    var verifiedInSet = 0
                    var slotsInSet = 0
                    for ((index, slot) in arranged.withIndex()) {
                        if (slot == null) continue // An empty layout cell is not a collectible printing.
                        visitedSlots++
                        slotsInSet++
                        val issues = mutableListOf<String>()
                        val audit = byId[slot.sourceCardId]
                        var mappingStatus = "BINDER_SOURCE_MISSING"
                        var expectedIds = emptyList<Long>()
                        var matches = emptyList<CatalogSearchResult>()
                        var sourceMatch: Boolean? = null
                        var printingMatch: Boolean? = null
                        var priceFieldMatch: Boolean? = null
                        if (slot.sourceSetId == null || slot.sourceCardId == null) {
                            issues += "BINDER_SOURCE_MISSING"
                        } else if (audit == null) {
                            mappingStatus = "BINDER_SOURCE_CARD_MISSING"
                            issues += mappingStatus
                        } else {
                            val source = audit.card
                            // Another printing's missing product does not invalidate this printing's exact link.
                            issues += audit.issues.filterNot { it == "DECLARED_PRODUCT_NOT_IN_SNAPSHOT" }
                            issues += audit.manualIssues
                            val printing = source.printVariants.filter {
                                it.finish.equals(slot.finish, true) && it.variant.equals(slot.variant, true)
                            }
                            if (printing.isEmpty()) issues += "SOURCE_PRINTING_MISSING"
                            expectedIds = printing.mapNotNull { it.cardmarketProductId }.distinct()
                            if (expectedIds.isEmpty()) issues += "SLOT_DECLARED_PRODUCT_ID_MISSING"
                            if (expectedIds.any { it !in products }) issues += "SLOT_PRODUCT_MISSING"
                            matches = binderReferenceCardmarketProducts(slot, source, detailedCards,
                                candidates(source, products, productsByExpansion))
                            mappingStatus = when {
                                expectedIds.isEmpty() -> "UNVERIFIED_SOURCE_LINK"
                                matches.isEmpty() -> "MAPPING_EMPTY"
                                matches.size > 1 -> "MAPPING_AMBIGUOUS"
                                matches.single().productId !in expectedIds -> "WRONG_PRODUCT"
                                else -> "EXACT_DECLARED_PRODUCT"
                            }
                            if (mappingStatus != "EXACT_DECLARED_PRODUCT") issues += mappingStatus
                            sourceMatch = matches.isNotEmpty() && matches.all {
                                it.sourceSetId == source.set.id.lowercase() && it.sourceCardId == source.id.lowercase() &&
                                    it.collectorNumber == slot.number && slot.sourceSetId == source.set.id.lowercase()
                            }
                            printingMatch = matches.isNotEmpty() && matches.all { it.finish == slot.finish && it.variant == slot.variant }
                            priceFieldMatch = matches.isNotEmpty() && matches.all { match ->
                                val rawPrice = prices[match.productId]
                                val expected = if (slot.finish.contains("reverse", true))
                                    rawPrice?.holoTrendMinor?.takeUnless { it == 0L }?.div(100.0)
                                else rawPrice?.trendMinor?.div(100.0)
                                match.trendPriceEur == expected && match.trendPriceEur == products[match.productId]?.realTrendFor(slot.finish)
                            }
                            if (matches.isNotEmpty() && sourceMatch != true) issues += "SOURCE_IDENTITY_MISMATCH"
                            if (matches.isNotEmpty() && printingMatch != true) issues += "PRINTING_MISMATCH"
                            if (matches.isNotEmpty() && priceFieldMatch != true) issues += "PRICE_FIELD_MISMATCH"
                            if (matches.any { it.productId !in prices }) issues += "PRICE_GUIDE_RECORD_MISSING"
                            if (matches.any { it.trendPriceEur == null }) issues += "PRICE_UNAVAILABLE_FOR_FINISH"
                        }
                        val verified = mappingStatus == "EXACT_DECLARED_PRODUCT" && issues.isEmpty() &&
                            matches.singleOrNull()?.trendPriceEur != null && sourceMatch == true && printingMatch == true && priceFieldMatch == true
                        if (verified) { verifiedInSet++; independentlyVerifiedPricedSlots++ }
                        increment(slotStatuses, mappingStatus)
                        increment(allSlotStatuses, mappingStatus)
                        issues.distinct().forEach { increment(slotIssues, it) }
                        val priceIds = (expectedIds + matches.map { it.productId }).distinct()
                        fun availability(reverse: Boolean) = priceIds.joinToString("|") { id ->
                            val price = prices[id]
                            val value = if (reverse) price?.holoTrendMinor else price?.trendMinor
                            "$id:${when { price == null -> "NO_RECORD"; value == null -> "NULL"; value == 0L -> "ZERO"; else -> "${value / 100.0}" }}"
                        }
                        slotWriter.write(csv(listOf(set.id, set.name, index + 1, slot.id, slot.number, slot.finish, slot.variant,
                            slot.sourceSetId, slot.sourceCardId, audit?.detailStatus, expectedIds.joinToString("|"),
                            matches.joinToString("|") { it.productId.toString() }, mappingStatus, sourceMatch, printingMatch,
                            priceFieldMatch, availability(false), availability(true),
                            matches.joinToString("|") { "${it.productId}:${it.trendPriceEur}" }, verified, issues.distinct().joinToString("|"))))
                    }
                    setReports += linkedMapOf(
                        "setId" to set.id, "setName" to set.name, "isPocketSourceSet" to (set.id in pocketSetIds),
                        "declaredPrintedTotal" to set.printedTotal,
                        "declaredTotal" to set.total, "scopedCards" to audits.size,
                        "englishFallbackCards" to audits.count { !it.card.hasGermanLocalizedName },
                        "detailsLoaded" to audits.count { it.detailStatus == "DETAIL_OK" },
                        "rawSourceVariants" to audits.sumOf { it.rawVariantCount },
                        "parsedSourcePrintingsIncludingFallback" to audits.sumOf { it.card.printVariants.size },
                        "canonicalBinderPrintings" to references.size, "actualBinderSlots" to slotsInSet,
                        "emptyLayoutCells" to arranged.count { it == null },
                        "independentlyVerifiedPricedSlots" to verifiedInSet,
                        "emptySourceSet" to audits.isEmpty(), "cardIssues" to setCardIssues,
                        "slotMappingStatuses" to slotStatuses, "slotIssues" to slotIssues,
                    )
                }
            }
        }
        val summaryFile = File(root, "download-summary.json")
        val downloadSummary = if (summaryFile.isFile) runCatching { readJson(summaryFile) }.getOrElse { it.toString() } else null
        val report = linkedMapOf<String, Any?>(
            "diagnosticOnly" to true, "auditExecutedToCompletion" to true,
            "allCataloguePricesProven" to false, "startedAt" to started, "finishedAt" to Instant.now().toString(),
            "snapshotDirectory" to root.absolutePath,
            "scope" to "German set index; DE+EN brief union assigned by longest exact setId- prefix; actual production binder slots",
            "limitations" to listOf("Full-download snapshot, not a device-local database or live UI test",
                "Source-less fallback mappings are never counted as independently verified",
                "Without a declared product ID only maintained expansion candidates are supplied; Room search and inferred expansion fallback are not exercised",
                "Missing or invalid detail files remain explicit gaps, including during a partial download",
                "Brief-union inventory does not separately verify each live set-detail endpoint",
                "A successful JUnit run certifies report iteration and output only"),
            "setCount" to sets.size, "rawGermanBriefCount" to germanRows.size, "rawEnglishBriefCount" to englishRows.size,
            "pocketClassificationLoaded" to pocketFile.isFile,
            "includedPocketSetIds" to sets.map { it.id }.filter { it in pocketSetIds },
            "scopedUniqueCards" to cardsBySet.values.sumOf { it.size }, "visitedCards" to visitedCards,
            "excludedOutsideGermanSetIds" to excludedIds.size, "excludedCardIds" to excludedIds,
            "detailsLoaded" to detailSuccesses, "detailsNotLoaded" to visitedCards - detailSuccesses,
            "parsedSourcePrintingsIncludingFallback" to sourcePrintingCount,
            "canonicalBinderPrintings" to binderPrintingCount, "visitedActualBinderSlots" to visitedSlots,
            "independentlyVerifiedPricedSlots" to independentlyVerifiedPricedSlots,
            "slotMappingStatuses" to allSlotStatuses, "cardIssues" to allCardIssues,
            "cardmarketProductCreatedAt" to productsDownload.createdAt, "cardmarketPriceCreatedAt" to pricesDownload.createdAt,
            "rawCardmarketProducts" to productsDownload.records.size, "uniqueCardmarketProducts" to products.size,
            "rawCardmarketPrices" to pricesDownload.records.size, "uniqueCardmarketPrices" to prices.size,
            "downloadSummaryAtAuditEnd" to downloadSummary, "sets" to setReports,
            "slotCsv" to slotFile.absolutePath, "cardCsv" to cardFile.absolutePath,
        )
        val reportFile = File(root, "binder-price-audit-report.json")
        reportFile.writeText(Moshi.Builder().build().adapter(Any::class.java).indent("  ").toJson(report), Charsets.UTF_8)
        assertEquals("Every scoped card must be visited", cardsBySet.values.sumOf { it.size }, visitedCards)
        assertEquals("Every actual printing must have one CSV row", visitedSlots + 1, slotFile.useLines { it.count() })
        assertEquals("Every source card must have one CSV row", visitedCards + 1, cardFile.useLines { it.count() })
        assertTrue("Diagnostic report was not written", reportFile.length() > 0L)
        println("DIAGNOSTIC ONLY: cards=$visitedCards slots=$visitedSlots independentlyVerifiedPriced=$independentlyVerifiedPricedSlots report=${reportFile.absolutePath}")
    }

    private fun candidates(card: PokemonVisualCard, products: Map<Long, CatalogSearchResult>,
        byExpansion: Map<String, List<CatalogSearchResult>>): List<CatalogSearchResult> {
        val ids = card.printVariants.mapNotNull { it.cardmarketProductId }.distinct()
        return if (ids.isNotEmpty()) ids.mapNotNull(products::get)
        else card.set.cardmarketExpansionId?.let { byExpansion[it] }.orEmpty()
    }

    /** Same stable fields as the private asBinderReferences helper; no owned collection entries. */
    private fun reference(card: PokemonVisualCard, printing: PokemonPrintVariant) = CollectionCard(
        entryId = 0L, id = "${card.id}-${printing.id}", name = card.name, set = card.set.name, number = card.number,
        language = CardLanguage.GERMAN, finish = printing.finish, variant = printing.variant,
        condition = "Nicht im Besitz", quantity = 0, currentValueEur = 0.0, changePercent = 0.0,
        priceUpdatedAtEpochMillis = null, notes = "", acquisitionPriceEur = null, acquisitionDateEpochDay = null,
        accent = 0xFF24263A, imageUrl = card.largeImageUrl, rarity = card.rarity ?: "UNKNOWN",
        marketSearchName = card.marketSearchName, marketExpansionId = card.set.cardmarketExpansionId,
        sourceSetId = card.set.id.lowercase(), sourceCardId = card.id.lowercase(),
    )

    private fun readJson(file: File): Any? = file.source().buffer().use { JsonReader.of(it).readJsonValue() }
    @Suppress("UNCHECKED_CAST") private fun objectRow(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()
    private fun rows(value: Any?): List<Map<String, Any?>> = (value as? List<*>)?.map(::objectRow).orEmpty()
    private fun Map<String, Any?>.text(key: String): String = get(key) as? String ?: ""
    private fun increment(counts: MutableMap<String, Int>, key: String) { counts[key] = (counts[key] ?: 0) + 1 }
    private fun csv(values: List<Any?>): String = values.joinToString(",") {
        // Keep one physical line per record so report completeness can be checked without a CSV library.
        "\"${it?.toString().orEmpty().replace("\r", " ").replace("\n", " ").replace("\"", "\"\"")}\""
    } + "\n"
}
