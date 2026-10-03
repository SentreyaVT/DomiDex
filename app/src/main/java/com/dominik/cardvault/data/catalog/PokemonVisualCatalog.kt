package com.dominik.cardvault.data.catalog

import com.dominik.cardvault.data.WachsendesChaosMasterSet
import com.squareup.moshi.JsonReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.BufferedSource
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class PokemonSet(
    val id: String,
    val name: String,
    val series: String,
    val printedTotal: Int,
    val total: Int,
    val releaseDate: String,
    val logoUrl: String?,
    val ptcgoCode: String?,
    /** Cardmarket's stable expansion id when it has been source-verified. */
    val cardmarketExpansionId: String? = CardmarketExpansionRegistry.idFor(id),
    /** Stable upstream series identity; `tcgp` denotes the digital Pocket catalogue. */
    val seriesId: String? = null,
)

internal fun PokemonSet.isPokemonPocketSet(): Boolean = seriesId.equals("tcgp", ignoreCase = true)

/** A virtual subset can have no physical denominator while still containing real cards. */
internal fun PokemonSet.displayCardCount(): Int = printedTotal.takeIf { it > 0 } ?: total

/**
 * Cardmarket's public product/price downloads expose an expansion id but not
 * the expansion name or collector number. Keep only verified bridges here;
 * an unknown bridge deliberately remains unresolved instead of guessing.
 */
private object CardmarketExpansionRegistry {
    private val byTcgDexId = mapOf(
        // Cardmarket: Mega Evolution / MEG, source-verified against the public
        // catalogue's 188 corresponding English printings.
        "me01" to "6209",
        // Cardmarket: Perfect Order / POR, source-verified against the public
        // catalogue's Rowlet, Dartrix and Decidueye ex printings.
        "me03" to "6443",
        // Cardmarket expansion 6601 contains the 158-card 30th Celebration
        // main set plus the 30-card Classic Collection. The stable TCGdex
        // source ids remain separate even though Cardmarket groups both.
        "30th" to "6601",
        "30th-c" to "6601",
    )

    fun idFor(tcgDexId: String): String? = byTcgDexId[tcgDexId.lowercase(Locale.ROOT)]
}

/**
 * Exact Cardmarket identities verified against the public expansion-6601
 * product catalogue, English attack/ability signatures, and numbered official pages.
 * These maintained exact bridges never depend on inferred name/product ordering.
 */
private object CardmarketProductRegistry {
    private val bySourceCardId: Map<String, Long> = buildMap {
        // Pikachu Rare 1/30-30/30 occupy source collector numbers 023-052.
        (23..52).forEach { number -> put("30th-${number.toString().padStart(3, '0')}", 907_607L + number) }
        put("30th-065", 907_672L) // Mew — Psychic
        put("30th-121", 907_728L) // Lugia — Elemental Blast
        put("30th-c-003", 907_949L) // Metagross δ
        put("30th-c-010", 907_951L) // Uxie
        put("30th-c-011", 907_952L) // Crobat G
        put("30th-c-014", 907_939L) // Base Set Pikachu
        put("30th-c-021", 907_956L) // N — one-character title needs an exact bridge
        put("30th-c-022", 907_950L) // Palkia LV.X — avoids the main-set namesake
        put("30th-c-029", 907_945L) // Aquapolis Lugia
        // Missing upstream product links, reviewed against both complete published
        // catalogues on 2026-09-22. Exact unique name + attack/ability signatures;
        // no duplicate-artwork pairs or inferred product ordering. Evidence:
        // docs/catalog-30th-cardmarket-prices-20260922.md.
        putAll(mapOf(
            "30th-001" to 907_607L, "30th-003" to 907_609L,
            "30th-004" to 907_610L, "30th-005" to 907_611L,
            "30th-006" to 907_612L, "30th-007" to 907_613L,
            "30th-008" to 907_614L, "30th-009" to 907_615L,
            "30th-010" to 907_736L, "30th-012" to 907_618L,
            "30th-013" to 907_619L, "30th-014" to 907_620L,
            "30th-016" to 907_622L, "30th-019" to 907_625L,
            "30th-020" to 907_627L, "30th-022" to 907_629L,
            "30th-056" to 907_663L, "30th-057" to 907_664L,
            "30th-058" to 907_665L, "30th-059" to 907_666L,
            "30th-063" to 907_670L, "30th-067" to 907_674L,
            "30th-068" to 907_675L, "30th-069" to 907_676L,
            "30th-070" to 907_677L, "30th-072" to 907_679L,
            "30th-074" to 907_681L, "30th-076" to 907_683L,
            "30th-077" to 907_684L, "30th-078" to 907_685L,
            "30th-079" to 907_686L, "30th-080" to 907_687L,
            "30th-081" to 907_688L, "30th-082" to 907_689L,
            "30th-083" to 907_690L, "30th-084" to 907_691L,
            "30th-086" to 907_693L, "30th-088" to 907_695L,
            "30th-091" to 907_698L, "30th-092" to 907_699L,
            "30th-093" to 907_700L, "30th-095" to 907_702L,
            "30th-096" to 907_703L, "30th-097" to 907_704L,
            "30th-098" to 907_705L, "30th-099" to 907_706L,
            "30th-100" to 907_707L, "30th-103" to 907_710L,
            "30th-104" to 907_711L, "30th-105" to 907_712L,
            "30th-106" to 907_713L, "30th-107" to 907_714L,
            "30th-110" to 907_717L, "30th-111" to 907_718L,
            "30th-114" to 907_721L, "30th-115" to 907_722L,
            "30th-116" to 907_723L, "30th-119" to 907_726L,
            "30th-120" to 907_727L, "30th-123" to 907_730L,
            "30th-124" to 907_731L,
            "30th-c-001" to 907_940L, "30th-c-004" to 907_958L,
            "30th-c-006" to 907_947L, "30th-c-007" to 907_943L,
            "30th-c-008" to 907_963L, "30th-c-009" to 907_960L,
            "30th-c-012" to 907_965L, "30th-c-013" to 907_962L,
            "30th-c-015" to 907_942L, "30th-c-016" to 907_957L,
            "30th-c-017" to 907_961L, "30th-c-024" to 907_944L,
            "30th-c-026" to 907_966L, "30th-c-027" to 907_967L,
            "30th-c-028" to 907_964L, "30th-c-030" to 907_968L,
        ))
        // The official Cardmarket overview directly pairs each printed collector
        // label with its product image ID. This resolves duplicate base/SAR/FUR
        // signatures without inferring product order. RGB colours remain separate
        // collector identities. Evidence: docs/catalog-30th-sar-prices-20260923.md.
        putAll(mapOf(
            "30th-002" to 907_608L, "30th-011" to 907_617L,
            "30th-015" to 907_621L, "30th-017" to 907_623L,
            "30th-018" to 907_624L, "30th-021" to 907_628L,
            "30th-053" to 907_660L, "30th-054" to 907_661L,
            "30th-055" to 907_662L, "30th-060" to 907_667L,
            "30th-061" to 907_668L, "30th-062" to 907_669L,
            "30th-064" to 907_671L, "30th-066" to 907_673L,
            "30th-071" to 907_678L, "30th-073" to 907_680L,
            "30th-075" to 907_682L, "30th-085" to 907_692L,
            "30th-087" to 907_694L, "30th-089" to 907_696L,
            "30th-090" to 907_697L, "30th-094" to 907_701L,
            "30th-101" to 907_708L, "30th-102" to 907_709L,
            "30th-108" to 907_715L, "30th-109" to 907_716L,
            "30th-112" to 907_719L, "30th-113" to 907_720L,
            "30th-117" to 907_724L, "30th-118" to 907_725L,
            "30th-122" to 907_729L, "30th-125" to 907_732L,
            "30th-126" to 907_733L, "30th-127" to 907_734L,
            "30th-128" to 907_735L,
            "30th-129" to 907_737L, "30th-130" to 907_738L,
            "30th-131" to 907_739L, "30th-132" to 907_740L,
            "30th-133" to 907_741L, "30th-134" to 907_742L,
            "30th-135" to 907_743L, "30th-136" to 907_744L,
            "30th-137" to 907_745L, "30th-138" to 907_746L,
            "30th-139" to 907_747L, "30th-140" to 907_748L,
            "30th-141" to 907_749L, "30th-142" to 907_750L,
            "30th-143" to 908_352L, "30th-144" to 907_751L,
            "30th-145" to 907_752L, "30th-146" to 907_753L,
            "30th-147" to 907_754L, "30th-148" to 907_755L,
            "30th-149" to 907_756L, "30th-150" to 907_757L,
            "30th-151" to 908_353L, "30th-152" to 908_354L,
            "30th-153" to 907_758L, "30th-154" to 908_355L,
            "30th-155" to 907_759L, "30th-156" to 907_760L,
            "30th-157" to 907_761L, "30th-158" to 907_762L,
            "30th-rgb-r" to 909_509L, "30th-rgb-g" to 909_510L,
            "30th-rgb-b" to 909_511L,
        ))
        // Remaining Classic identities: collector label and product-image ID were
        // read from the same official Cardmarket overview element on 2026-09-25.
        // Historic originals, LEGEND halves and the main set remain distinct.
        // Evidence: docs/catalog-30th-classic-cardmarket-20260925.md.
        putAll(mapOf(
            "30th-c-002" to 907_946L, "30th-c-005" to 907_941L,
            "30th-c-018" to 907_953L, "30th-c-019" to 907_954L,
            "30th-c-020" to 907_955L, "30th-c-023" to 907_959L,
            "30th-c-025" to 907_948L,
        ))
    }

    fun idFor(sourceCardId: String): Long? = bySourceCardId[sourceCardId.lowercase(Locale.ROOT)]
}

internal fun verifiedCardmarketProductId(sourceCardId: String): Long? =
    CardmarketProductRegistry.idFor(sourceCardId)

private fun attachVerifiedCardmarketProduct(
    sourceCardId: String,
    variants: List<PokemonPrintVariant>,
    blockedVariantIds: Set<String> = emptySet(),
): List<PokemonPrintVariant> {
    val productId = verifiedCardmarketProductId(sourceCardId)
    return variants.map { variant ->
        // A missing link can use a reviewed bridge; contradictory evidence cannot.
        if (variant.id in blockedVariantIds) return@map variant.copy(cardmarketProductId = null)
        // A regular product bridge is not evidence for a stamp, foil or special edition.
        // English detail fallback uses lowercase "standard". Preserve its label,
        // finish and edition; only this eligibility comparison is case-insensitive.
        val ordinary = variant.variant.trim().lowercase(Locale.ROOT) in setOf("standard", "normal", "holo", "reverse holo")
        val candidate = if (variant.cardmarketProductId == null && ordinary) productId else variant.cardmarketProductId
        variant.copy(cardmarketProductId = candidate.takeUnless { isQuarantinedCardmarketIdentity(sourceCardId, it) })
    }
}

/** A separately collectible printing of the same numbered card. */
data class PokemonPrintVariant(
    val id: String, val finish: String, val variant: String,
    /** Exact public source link, never inferred from a title or product order. */
    val cardmarketProductId: Long? = null,
    /** Preserve raw provenance when a reused source ID needs a unique local reference ID. */
    val sourceVariantId: String = id,
)

data class PokemonVisualCard(
    val id: String,
    val name: String,
    val number: String,
    val rarity: String?,
    val smallImageUrl: String,
    val largeImageUrl: String,
    val set: PokemonSet,
    // The source lists actual available printings. A missing variant payload is
    // intentionally treated as one normal printing, never as a guessed holo.
    val printVariants: List<PokemonPrintVariant> = listOf(PokemonPrintVariant("normal", "Normal", "Standard")),
    /** Same source card in Cardmarket's commonly used English naming. */
    val marketSearchName: String? = null,
    /** False only when German metadata omitted this printing and source-backed English identity filled the gap. */
    val hasGermanLocalizedName: Boolean = true,
    /** Upstream slot within a subset when the physical card keeps a historic collector number. */
    val sourceLocalId: String? = null,
)

/** A German-name page; continuation follows the raw source page, not filtered cards. */
data class PokemonCardSearchPage(val cards: List<PokemonVisualCard>, val hasMore: Boolean)

/** Every source set with the printed denominator remains eligible; none is cut off by list order. */
internal fun setsMatchingPrintedTotal(sets: List<PokemonSet>, printedTotal: Int): List<PokemonSet> =
    sets.filter { it.printedTotal == printedTotal }

/** German identities win; the same source id can supply a missing image, never a guessed card. */
internal fun mergeLocalizedSetCards(
    germanCards: List<PokemonVisualCard>,
    englishCards: List<PokemonVisualCard>,
): List<PokemonVisualCard> {
    val germanIds = germanCards.mapTo(hashSetOf(), PokemonVisualCard::id)
    val englishById = englishCards.associateBy(PokemonVisualCard::id)
    return (germanCards + englishCards.filterNot { it.id in germanIds })
        .distinctBy(PokemonVisualCard::id)
        .map { card ->
            val english = englishById[card.id]
            val artwork = resolvePokemonCardArtwork(card.set.id, card.id, card.sourceLocalId, card.artwork(), english?.artworkCandidate())
            card.copy(
                marketSearchName = english?.name,
                smallImageUrl = artwork.smallImageUrl,
                largeImageUrl = artwork.largeImageUrl,
            )
        }
}

/** Never add English-only sets or replace the German display name/count/identity. */
internal fun fillMissingSetLogos(german: List<PokemonSet>, english: List<PokemonSet>): List<PokemonSet> {
    val byId = english.associateBy(PokemonSet::id)
    return german.map { set ->
        if (set.needsLocalizedLogoFallback()) {
            val englishLogo = byId[set.id]?.takeUnless { it.needsLocalizedLogoFallback() }?.logoUrl
            set.copy(logoUrl = englishLogo ?: officialPokemonSetLogoUrl(set.id) ?: set.logoUrl)
        } else set
    }
}

private fun PokemonSet.needsLocalizedLogoFallback(): Boolean = logoUrl.isNullOrBlank() ||
    logoUrl.startsWith("https://assets.tcgdex.net/univ/") && logoUrl.substringAfterLast('/').startsWith("symbol.")

internal fun officialPokemonSetLogoUrl(setId: String): String? = when (setId.lowercase(Locale.ROOT)) {
    "30th", "30th-c" -> "https://www.pokemon.com/static-assets/content-assets/cms2-de-de/img/trading-card-game/series/me_series/30th/30th_logo_169_de.png"
    else -> null
}

/** Official remote fallback; no card bitmap is bundled into the APK. */
internal fun officialPokemonCardImageUrl(setId: String, localId: String): String? {
    val number = localId.filter(Char::isDigit).toIntOrNull() ?: return null
    return when (setId.lowercase(Locale.ROOT)) {
        "30th" -> number.takeIf { it in 1..158 }?.let {
            "https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/30TH/30TH_DE_$it.png"
        }
        "30th-c" -> number.takeIf { it in 1..30 }?.let {
            "https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/30TH-C/30TH-C_DE_$it.png"
        }
        else -> null
    }
}

/**
 * The Classic Collection source uses virtual positions 001-030, while the
 * cards themselves retain their historic collector numbers. These aliases
 * were verified against the official German card images.
 */
private val classic30thLocalIdByPrintedCollector = mapOf(
    "4/102" to "1", "5/109" to "2", "11/113" to "3", "11/101" to "4",
    "18/132" to "5", "19/109" to "6", "25/111" to "7", "33/181" to "8",
    "41/122" to "9", "43/146" to "10", "47/127" to "11", "50/185" to "12",
    "57/111" to "13", "58/102" to "14", "69/132" to "15", "85/124" to "16",
    "89/149" to "17", "94/102" to "18", "99/102" to "19", "100/102" to "20",
    "101/101" to "21", "106/106" to "22", "106/160" to "23", "106/105" to "24",
    "108/115" to "25", "269/264" to "26", "123/172" to "27", "138/202" to "28",
    "149/147" to "29", "203/193" to "30",
)

private val classic30thPrintedCollectorByLocalId =
    classic30thLocalIdByPrintedCollector.entries.associate { (printed, local) -> local to printed }

internal fun classic30thLocalIdForPrintedCollector(value: String): String? {
    val match = Regex("^\\s*0*(\\d{1,4})\\s*/\\s*0*(\\d{1,4})\\s*$").matchEntire(value) ?: return null
    val key = "${match.groupValues[1].toInt()}/${match.groupValues[2].toInt()}"
    return classic30thLocalIdByPrintedCollector[key]
}

internal fun classic30thPrintedCollectorForLocalId(value: String): String? {
    val local = value.filter(Char::isDigit).toIntOrNull()?.toString() ?: return null
    return classic30thPrintedCollectorByLocalId[local]
}

private fun cardImageUrl(value: String, quality: String): String {
    val base = value.trim().trimEnd('/')
    if (base.isEmpty()) return ""
    return if (base.substringBefore('?').substringAfterLast('/').contains('.')) base else "$base/$quality.webp"
}

/** Encode exactly one dynamic path segment, including an already encoded percent sign. */
internal fun encodeCatalogPathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

/** Maps only source-declared collectible printings to separate binder slots. */
internal fun pokemonPrintVariantForSourceKey(key: String): PokemonPrintVariant? = when (key.lowercase()) {
    "normal", "unlimited" -> PokemonPrintVariant(key, "Normal", "Standard")
    "holofoil", "unlimitedholofoil" -> PokemonPrintVariant(key, "Holo", "Holo")
    "reverseholofoil" -> PokemonPrintVariant(key, "Reverse Holo", "Reverse Holo")
    "1stedition" -> PokemonPrintVariant(key, "Normal", "1. Edition")
    "1steditionholofoil" -> PokemonPrintVariant(key, "Holo", "1. Edition Holo")
    else -> null // no speculative slot for non-collectible/unknown source keys
}

/**
 * German-first card identity adapter. TCGdex publishes a multilingual, MIT-licensed
 * card database; Cardmarket remains the separate, explicit source for prices.
 * No images or card text are bundled into the APK: the app only requests the
 * German metadata needed by the set the collector is actively browsing/scanning.
 */
class PokemonVisualCatalog private constructor(
    private val metadataCache: PokemonCatalogMetadataCache,
    private val sourceOverride: ((String) -> BufferedSource)?,
    private val httpCalls: okhttp3.Call.Factory?,
) {
    internal constructor(
        metadataCache: PokemonCatalogMetadataCache,
        sourceOverride: ((String) -> BufferedSource)? = null,
    ) : this(metadataCache, sourceOverride, null)

    constructor() : this(sharedMetadataCache)

    internal companion object {
        private val sharedMetadataCache = PokemonCatalogMetadataCache()

        /** Internal synthetic-HTTP seam; existing constructors/trailing-lambda callers are unchanged. */
        internal fun withHttpCalls(cache: PokemonCatalogMetadataCache, calls: okhttp3.Call.Factory) =
            PokemonVisualCatalog(cache, null, calls)
    }

    val isLicensedSourceConfigured: Boolean get() = true
    private val germanBaseUrl = "https://api.tcgdex.net/v2/de"
    private val englishBaseUrl = "https://api.tcgdex.net/v2/en"

    /** Only the small set index is warmed; card metadata stays demand-loaded. */
    suspend fun warmUp() { sets() }

    /** An explicit refresh also clears cached metadata used by other catalogue screens. */
    fun invalidate() { metadataCache.invalidate() }

    suspend fun sets(query: String = ""): List<PokemonSet> {
        val result = metadataCache.sets.getOrLoad("de") {
            withContext(Dispatchers.IO) {
                val german = read("$germanBaseUrl/sets") { reader ->
                    buildList { readArray(reader) { parseGermanSet(it)?.let(::add) } }
                }
                val english = if (german.any { it.needsLocalizedLogoFallback() }) catalogOrDefault(emptyList()) {
                    read("$englishBaseUrl/sets") { reader ->
                        buildList { readArray(reader) { parseGermanSet(it)?.let(::add) } }
                    }
                } else emptyList()
                fillMissingSetLogos(german, english)
                    .map { set ->
                        // The upstream set index currently omits the three letter-numbered
                        // RGB cards and the announced anniversary Black Star promos.
                        val minimum = when (set.id.lowercase(Locale.ROOT)) {
                            "30th" -> 161
                            "mep" -> 105 // 001–088 plus 094–110; do not invent 089–093.
                            else -> set.total
                        }
                        set.copy(total = maxOf(set.total, minimum))
                    }
                    .sortedWith(compareByDescending(::catalogSetRecencyKey).thenByDescending { it.releaseDate })
            }.also { loaded ->
                PokemonSetRegistry.registerSourceSets(loaded)
            }
        }
        return if (query.isBlank()) result
        else result.filter { it.name.contains(query, true) || it.series.contains(query, true) }
    }

    suspend fun cards(set: PokemonSet, query: String = ""): List<PokemonVisualCard> {
        val result = setCards(set).cards
        return if (query.isBlank()) result
        else result.filter { it.name.contains(query, true) || it.number.contains(query, true) }
    }

    /**
     * Searches German briefs, never market-product names. A card is associated only
     * with an indexed set whose exact source ID and local ID agree. Detail/printing
     * data remains demand-loaded by withDeclaredPrintVariants after selection.
     */
    suspend fun searchCards(query: String, page: Int = 1): PokemonCardSearchPage {
        currentCoroutineContext().ensureActive()
        val term = query.trim()
        require(term.length <= 100) { "Card search is limited to 100 characters" }
        require(page > 0) { "Card search page must be positive" }
        if (term.length < 2) return PokemonCardSearchPage(emptyList(), false)
        return metadataCache.searches.getOrLoad(term to page) {
            withContext(Dispatchers.IO) {
                val indexedSets = sets().sortedByDescending { it.id.length }
                // /sets briefs commonly omit serie; use its small authoritative
                // membership index rather than guessing Pocket from set spelling.
                val pocketSetIds = searchPocketSetIds()
                val briefs = read(
                    "$germanBaseUrl/cards?name=${encodeCatalogPathSegment("like:$term")}" +
                        "&pagination:page=$page&pagination:itemsPerPage=40",
                ) { reader ->
                    buildList {
                        readArray(reader) {
                            check(size < 40) { "Card search source exceeded its requested page size" }
                            add(readCardBrief(it))
                        }
                    }
                }
                val cards = briefs.mapNotNull { brief ->
                    // Resolve the longest prefix BEFORE checking localId: a malformed
                    // 30th-c card must never fall back to the shorter 30th identity.
                    val set = indexedSets.firstOrNull { brief.id.startsWith("${it.id}-") }
                        ?: return@mapNotNull null
                    if (set.isPokemonPocketSet() || set.id in pocketSetIds || brief.number.isBlank() ||
                        brief.id != "${set.id}-${brief.number}"
                    ) return@mapNotNull null
                    mapCardBrief(brief, set, hasGermanLocalizedName = true)
                }.distinctBy(PokemonVisualCard::id)
                val supplemental = if (page == 1) indexedSets.filterNot { it.isPokemonPocketSet() || it.id in pocketSetIds }
                    .flatMap(::supplementalThirtyYearsCards)
                    .filter { it.name.contains(term, ignoreCase = true) } else emptyList()
                val supplementalById = supplemental.associateBy(PokemonVisualCard::id)
                val localized = (cards.map { card ->
                    val maintained = supplementalById[card.id]
                    card.copy(
                        smallImageUrl = card.smallImageUrl.ifBlank { maintained?.smallImageUrl.orEmpty() },
                        largeImageUrl = card.largeImageUrl.ifBlank { maintained?.largeImageUrl.orEmpty() },
                    )
                } + supplemental).distinctBy(PokemonVisualCard::id)
                PokemonCardSearchPage(fillSearchImages(localized), hasMore = briefs.size == 40)
            }
        }
    }

    private suspend fun searchPocketSetIds(): Set<String> = metadataCache.searchPocketSets.getOrLoad("tcgp") {
        read("$germanBaseUrl/series/tcgp") { reader ->
            var seriesId: String? = null
            var foundSets = false
            val ids = mutableSetOf<String>()
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "id" -> seriesId = reader.nextString()
                "sets" -> {
                    check(!foundSets) { "Duplicate Pocket set index" }
                    foundSets = true
                    readArray(reader) { setReader ->
                        var id: String? = null
                        setReader.beginObject()
                        while (setReader.hasNext()) when (setReader.nextName()) {
                            "id" -> id = setReader.nextString()
                            else -> setReader.skipValue()
                        }
                        setReader.endObject()
                        check(!id.isNullOrBlank() && ids.add(id!!)) { "Invalid Pocket set identity" }
                    }
                }
                else -> reader.skipValue()
            }
            reader.endObject()
            check(seriesId == "tcgp" && foundSets && ids.isNotEmpty()) { "Pocket set index unavailable" }
            ids.toSet()
        }
    }

    /** One bounded same-ID brief request, not a detail request per result. */
    private suspend fun fillSearchImages(cards: List<PokemonVisualCard>): List<PokemonVisualCard> {
        val resolved = cards.map { card ->
            val artwork = resolvePokemonCardArtwork(card.set.id, card.id, card.sourceLocalId, card.artwork())
            card.copy(smallImageUrl = artwork.smallImageUrl, largeImageUrl = artwork.largeImageUrl)
        }
        val missing = resolved.filter { needsPokemonCardArtwork(it.set.id, it.id, it.sourceLocalId, it.artwork()) }
            .associateBy(PokemonVisualCard::id)
        if (missing.isEmpty()) return resolved
        val images = catalogOrDefault<Map<String, CardBrief>>(emptyMap()) {
            val filter = encodeCatalogPathSegment("eq:${missing.keys.joinToString("|")}")
            read("$englishBaseUrl/cards?id=$filter&pagination:page=1&pagination:itemsPerPage=40") { reader ->
                buildMap {
                    var count = 0
                    readArray(reader) {
                        check(++count <= 40) { "Image source exceeded its requested page size" }
                        val brief = readCardBrief(it)
                        val german = missing[brief.id]
                        if (german != null && brief.number == german.sourceLocalId && brief.image.isNotBlank()) {
                            put(brief.id, brief)
                        }
                    }
                }
            }
        }
        return resolved.map { card ->
            val brief = images[card.id]
            val candidate = brief?.let { PokemonCardArtworkCandidate(card.set.id, it.id, it.number,
                PokemonCardArtwork(cardImageUrl(it.image, "low"), cardImageUrl(it.image, "high"))) }
            val artwork = resolvePokemonCardArtwork(card.set.id, card.id, card.sourceLocalId, card.artwork(), candidate)
            card.copy(
                smallImageUrl = artwork.smallImageUrl,
                largeImageUrl = artwork.largeImageUrl,
            )
        }
    }

    private suspend fun setCards(set: PokemonSet): CatalogSetCards =
        metadataCache.cards.getOrLoad(set.id) {
            withContext(Dispatchers.IO) {
                val germanLoad = async(Dispatchers.IO) {
                    catalogOrDefault<List<PokemonVisualCard>?>(null) {
                        read("$germanBaseUrl/sets/${encodeCatalogPathSegment(set.id)}") { reader ->
                            parseSetCards(reader, set, hasGermanLocalizedName = true)
                        }
                    }
                }
                val englishLoad = async(Dispatchers.IO) {
                    catalogOrDefault<List<PokemonVisualCard>?>(null) {
                        read("$englishBaseUrl/sets/${encodeCatalogPathSegment(set.id)}") { reader ->
                            parseSetCards(reader, set, hasGermanLocalizedName = false)
                        }
                    }
                }
                val germanCards = germanLoad.await()
                val englishCards = englishLoad.await()
                check(germanCards != null || englishCards != null) { "Set-Katalog momentan nicht erreichbar" }
                // Preserve German-first identities and the source-backed English naming bridge.
                PokemonSetRegistry.registerSourceSets((germanCards.orEmpty() + englishCards.orEmpty()).map(PokemonVisualCard::set))
                val cards = mergeThirtyYearsCatalogSupplement(
                    set, mergeLocalizedSetCards(germanCards.orEmpty(), englishCards.orEmpty()),
                )
                    .sortedWith(compareBy({ (it.sourceLocalId ?: it.number).toIntOrNull() ?: Int.MAX_VALUE }, { it.sourceLocalId ?: it.number }))
                CatalogSetCards(cards, cards.groupBy { collectorIdentity(it.sourceLocalId ?: it.number) })
            }
        }

    /**
     * The set index deliberately stays small.  A card detail supplies the
     * source-declared Normal/Holo/Reverse printings for master-set slots.
     */
    suspend fun withDeclaredPrintVariants(card: PokemonVisualCard, loadMissingArtwork: Boolean = false): PokemonVisualCard {
        // Maintained supplemental identities have no upstream detail endpoint yet.
        // Do not make every binder card wait for a pair of known 404 responses.
        supplementalThirtyYearsCardDetail(card)?.let { return it }
        val detail = metadataCache.details.getOrLoad(card.id) {
            withContext(Dispatchers.IO) {
                val encodedId = encodeCatalogPathSegment(card.id)
                catalogOrDefault<CatalogCardDetail?>(null) {
                    read("$germanBaseUrl/cards/$encodedId") { reader -> parseCardDetail(reader, card) }
                } ?: read("$englishBaseUrl/cards/$encodedId") { reader ->
                    // English detail data may fill a missing source payload, but
                    // it must not replace an already localized German rarity.
                    parseCardDetail(reader, card).let { fallback ->
                        fallback.copy(rarity = card.rarity ?: fallback.rarity, fromEnglishSource = true)
                    }
                }
            }
        }
        var artwork = resolvePokemonCardArtwork(card.set.id, card.id, card.sourceLocalId, card.artwork(),
            PokemonCardArtworkCandidate(card.set.id, card.id, card.sourceLocalId,
                PokemonCardArtwork(cardImageUrl(detail.image.orEmpty(), "low"), cardImageUrl(detail.image.orEmpty(), "high"))))
        if (loadMissingArtwork && !detail.fromEnglishSource && needsPokemonCardArtwork(card.set.id, card.id, card.sourceLocalId, artwork)) {
            // Only a selected unresolved card needs this additional detail. Cache
            // metadata separately so a prior caller's good image cannot suppress repair.
            val englishArtwork = catalogOrDefault<PokemonCardArtworkCandidate?>(null) {
                metadataCache.detailArtwork.getOrLoad(card.id) {
                    withContext(Dispatchers.IO) {
                        read("$englishBaseUrl/cards/${encodeCatalogPathSegment(card.id)}") { reader ->
                            val english = parseCardDetail(reader, card)
                            PokemonCardArtworkCandidate(card.set.id, card.id, card.sourceLocalId,
                                PokemonCardArtwork(cardImageUrl(english.image.orEmpty(), "low"), cardImageUrl(english.image.orEmpty(), "high")))
                        }
                    }
                }
            }
            artwork = resolvePokemonCardArtwork(card.set.id, card.id, card.sourceLocalId, artwork, englishArtwork)
        }
        return card.copy(
            rarity = detail.rarity ?: card.rarity,
            printVariants = attachVerifiedCardmarketProduct(
                card.id,
                detail.printVariants.ifEmpty { card.printVariants },
                detail.blockedCardmarketVariantIds,
            ),
            smallImageUrl = artwork.smallImageUrl,
            largeImageUrl = artwork.largeImageUrl,
        )
    }

    suspend fun findExact(setIdentifier: String, collectorNumber: String): List<PokemonVisualCard> = withContext(Dispatchers.IO) {
        val code = PokemonSetRegistry.normalize(setIdentifier)
        val collectorKey = collectorIdentity(collectorNumber)
        if (collectorKey.isBlank()) return@withContext emptyList()
        PokemonSetRegistry.legacyOfflineSet(code)?.let { set ->
            val number = Regex("\\d{1,4}").find(collectorNumber)?.value?.toIntOrNull() ?: return@withContext emptyList()
            return@withContext WachsendesChaosMasterSet.cards.asSequence()
                .filter { Regex("\\d{1,4}").find(it.number)?.value?.toIntOrNull() == number }
                .groupBy { it.number }
                .values
                .map { printings ->
                    val primary = printings.first()
                    PokemonVisualCard(
                        primary.sourceCardId,
                        primary.name,
                        number.toString().padStart(3, '0'),
                        null,
                        primary.imageUrl,
                        primary.imageUrl,
                        set,
                        printings.map { printing ->
                            PokemonPrintVariant(printing.variant.lowercase().replace(' ', '-'), printing.finish, printing.variant)
                        }.distinctBy { it.id },
                    )
                }
                .toList()
        }
        val resolvedSets = PokemonSetRegistry.resolveAll(code, sets())
        if (resolvedSets.isEmpty()) return@withContext emptyList()
        val denominator = Regex("(\\d{1,4})\\s*/\\s*(\\d{1,4})").find(collectorNumber)
            ?.groupValues?.get(2)?.toIntOrNull()
        // 30C is shared by the main set and Classic Collection. If OCR loses
        // the denominator, the same numerator can represent two different
        // physical cards. Reject that incomplete identity instead of silently
        // preferring the main set; an explicit source-set selection is exact.
        val rgbCollector = Regex("(?i)^\\s*[RGB]\\s*/\\s*RGB\\s*$").matches(collectorNumber)
        if (denominator == null && resolvedSets.size > 1 && !rgbCollector) return@withContext emptyList()
        val matchingSets = if (rgbCollector) resolvedSets.filter { it.id.equals("30th", true) }
        else if (denominator == null) resolvedSets else resolvedSets.filter { set ->
            set.printedTotal == denominator ||
                (set.id.equals("30th-c", ignoreCase = true) && classic30thLocalIdForPrintedCollector(collectorNumber) != null)
        }
        if (matchingSets.isEmpty()) return@withContext emptyList()
        // The set payload intentionally stays lightweight and can omit rarity.
        // Only the one or few exact number candidates are expanded, off the UI
        // thread, to obtain source-declared rarity/finish metadata.
        matchingSets.flatMap { matchingSet ->
            val sourceCollectorKey = collectorIdentityForSourceLookup(collectorNumber, matchingSet.id)
            catalogOrDefault(emptyList()) { setCards(matchingSet).byCollectorNumber[sourceCollectorKey].orEmpty() }
                .filter { !it.number.endsWith("/RGB", ignoreCase = true) || rgbCollector }
                .map { candidate -> catalogOrDefault(candidate) { withDeclaredPrintVariants(candidate) } }
        }.distinctBy(PokemonVisualCard::id)
    }

    /**
     * Safe fallback when glare hides only the printed three-letter set code. The denominator is
     * still physical lower-border evidence and is used solely to reduce the source catalogue.
     * Name/artwork scoring must disambiguate the returned cards; this method never declares a
     * match. Classic Collection is the one explicit exception to ordinary set totals: its source
     * catalogue is numbered 001-030 while each physical card retains its historic denominator.
     */
    suspend fun findByPrintedTotalAndCollectorNumber(collectorNumber: String): List<PokemonVisualCard> = coroutineScope {
        val parts = Regex("(\\d{1,4})\\s*/\\s*(\\d{1,4})").find(collectorNumber)
            ?: return@coroutineScope emptyList()
        if (parts.groupValues[1].toIntOrNull() == null) return@coroutineScope emptyList()
        val printedTotal = parts.groupValues[2].toIntOrNull() ?: return@coroutineScope emptyList()
        val allSets = sets()
        val matchingSets = buildList {
            if (classic30thLocalIdForPrintedCollector(collectorNumber) != null) {
                addAll(allSets.filter { it.id.equals("30th-c", ignoreCase = true) })
            }
            // Keep the validated Classic alias ahead of the potentially many historic sets that
            // share this denominator. Candidate reduction is capped later; this guarantees that
            // the anniversary printing reaches name/artwork verification instead of being cut off.
            addAll(setsMatchingPrintedTotal(allSets, printedTotal))
        }.distinctBy { it.id.lowercase(Locale.ROOT) }
        val lookupSlots = Semaphore(permits = 4)
        matchingSets.map { set ->
            async(Dispatchers.IO) {
                lookupSlots.withPermit {
                    val sourceCollectorKey = collectorIdentityForSourceLookup(collectorNumber, set.id)
                    catalogOrDefault(emptyList()) { setCards(set).byCollectorNumber[sourceCollectorKey].orEmpty() }
                        .map { candidate -> catalogOrDefault(candidate) { withDeclaredPrintVariants(candidate) } }
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun <T> read(url: String, block: (JsonReader) -> T): T {
        sourceOverride?.let { return JsonReader.of(it(url)).use(block) }
        return readPokemonCatalogHttp(url, httpCalls, block)
    }

    private fun readArray(reader: JsonReader, item: (JsonReader) -> Unit) {
        reader.beginArray(); while (reader.hasNext()) item(reader); reader.endArray()
    }

    private fun parseGermanSet(reader: JsonReader): PokemonSet? {
        var id = ""; var name = ""; var total = 0; var official = 0; var logo: String? = null; var symbol: String? = null; var releaseDate = ""; var abbreviation: String? = null; var seriesId: String? = null; var seriesName: String? = null
        reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
            "id" -> id = reader.nextString(); "name" -> name = reader.nextString()
            "logo" -> logo = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else assetImageUrl(reader.nextString())
            // Older German TCGdex records often expose only a set symbol. It is
            // still the official visual identifier and should never leave the
            // picker with an empty icon.
            "symbol" -> symbol = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else assetImageUrl(reader.nextString())
            "releaseDate" -> releaseDate = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); "" } else reader.nextString()
            "abbreviation" -> {
                reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
                    "official" -> abbreviation = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else reader.nextString()
                    else -> reader.skipValue()
                }; reader.endObject()
            }
            "serie" -> {
                reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
                    "id" -> seriesId = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else reader.nextString()
                    "name" -> seriesName = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else reader.nextString()
                    else -> reader.skipValue()
                }; reader.endObject()
            }
            "cardCount" -> { reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) { "total" -> total = reader.nextInt(); "official" -> official = reader.nextInt(); else -> reader.skipValue() }; reader.endObject() }
            else -> reader.skipValue()
        }
        reader.endObject()
        return if (id.isBlank() || name.isBlank()) null else PokemonSet(
            id, name, seriesName?.takeIf(String::isNotBlank) ?: "Deutsch · TCGdex",
            official, total.coerceAtLeast(official), releaseDate,
            logo ?: symbol ?: officialPokemonSetLogoUrl(id), abbreviation,
            seriesId = seriesId,
        )
    }

    private fun parseSetCards(
        reader: JsonReader,
        set: PokemonSet,
        hasGermanLocalizedName: Boolean,
    ): List<PokemonVisualCard> {
        val cards = mutableListOf<PokemonVisualCard>()
        var abbreviation = set.ptcgoCode
        var seriesId = set.seriesId
        reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
            "cards" -> readArray(reader) {
                parseCardBrief(it, set, hasGermanLocalizedName)?.let(cards::add)
            }
            // The lightweight /sets index intentionally omits this field. The
            // set detail is authoritative and lets the scanner learn every
            // currently published code without accepting arbitrary OCR text.
            "abbreviation" -> {
                reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
                    "official" -> abbreviation = if (reader.peek() == JsonReader.Token.NULL) {
                        reader.nextNull<Unit>(); null
                    } else reader.nextString()
                    else -> reader.skipValue()
                }; reader.endObject()
            }
            "serie" -> {
                reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
                    "id" -> seriesId = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else reader.nextString()
                    else -> reader.skipValue()
                }; reader.endObject()
            }
            else -> reader.skipValue()
        }
        reader.endObject()
        val detailedSet = set.copy(
            ptcgoCode = abbreviation?.takeIf(String::isNotBlank) ?: set.ptcgoCode,
            seriesId = seriesId?.takeIf(String::isNotBlank) ?: set.seriesId,
        )
        return cards.map { it.copy(set = detailedSet) }
    }

    private fun parseCardBrief(
        reader: JsonReader,
        set: PokemonSet,
        hasGermanLocalizedName: Boolean,
    ): PokemonVisualCard? = mapCardBrief(readCardBrief(reader), set, hasGermanLocalizedName)

    private data class CardBrief(
        val id: String, val name: String, val number: String, val rarity: String?, val image: String,
    )

    private fun readCardBrief(reader: JsonReader): CardBrief {
        var id = ""; var name = ""; var number = ""; var rarity: String? = null; var image = ""
        reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
            "id" -> id = reader.nextString(); "name" -> name = reader.nextString(); "localId" -> number = reader.nextString()
            "rarity" -> rarity = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else reader.nextString()
            "image" -> image = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); "" } else reader.nextString()
            else -> reader.skipValue()
        }
        reader.endObject()
        return CardBrief(id, name, number, rarity, image)
    }

    private fun mapCardBrief(brief: CardBrief, set: PokemonSet, hasGermanLocalizedName: Boolean): PokemonVisualCard? {
        val (id, name, number, rarity, image) = brief
        val lowImage = cardImageUrl(image, "low")
        val highImage = cardImageUrl(image, "high")
        val artwork = resolvePokemonCardArtwork(set.id, id, number, PokemonCardArtwork(lowImage, highImage))
        val printedCollector = if (set.id.equals("30th-c", ignoreCase = true)) {
            classic30thPrintedCollectorForLocalId(number) ?: number
        } else number
        val defaultVariants = attachVerifiedCardmarketProduct(
            id,
            listOf(PokemonPrintVariant("normal", "Normal", "Standard")),
        )
        return if (id.isBlank() || name.isBlank()) null else PokemonVisualCard(
            id, name, printedCollector, rarity, artwork.smallImageUrl, artwork.largeImageUrl, set,
            defaultVariants,
            hasGermanLocalizedName = hasGermanLocalizedName,
            sourceLocalId = number,
        )
    }

    private fun parseCardDetail(reader: JsonReader, expected: PokemonVisualCard): CatalogCardDetail {
        val result = mutableListOf<PokemonPrintVariant>()
        val conflictingPrintings = mutableSetOf<Triple<String, String, String>>()
        val declaredProductsByPrinting = mutableMapOf<Triple<String, String, String>, MutableSet<Long>>()
        var rarity: String? = null
        var image: String? = null
        reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
            // The request path binds legacy/minimal payloads; any supplied source
            // identity must agree before its artwork (or printing data) is accepted.
            "id" -> check(reader.nextString() == expected.id) { "Detail card identity mismatch" }
            "localId" -> check(reader.nextString() == (expected.sourceLocalId ?: expected.id.removePrefix("${expected.set.id}-"))) {
                "Detail local identity mismatch"
            }
            "set" -> {
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "id" -> check(reader.nextString() == expected.set.id) { "Detail set identity mismatch" }
                    else -> reader.skipValue()
                }
                reader.endObject()
            }
            "image" -> image = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else reader.nextString()
            "rarity" -> rarity = if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); null } else reader.nextString()
            "variants_detailed" -> readArray(reader) { variantReader ->
                var id = ""; var type = ""; var subtype = ""; var size = ""; var foil = ""
                val stamps = mutableListOf<String>()
                var cardmarketProductId: Long? = null
                var pricingProductId: Long? = null
                variantReader.beginObject(); while (variantReader.hasNext()) when (variantReader.nextName()) {
                    "variantId" -> id = variantReader.nextString()
                    "type" -> type = variantReader.nextString()
                    "subtype" -> subtype = if (variantReader.peek() == JsonReader.Token.NULL) { variantReader.nextNull<Unit>(); "" } else variantReader.nextString()
                    "size" -> size = variantReader.nextString()
                    "foil" -> foil = if (variantReader.peek() == JsonReader.Token.NULL) { variantReader.nextNull<Unit>(); "" } else variantReader.nextString()
                    "stamp" -> when (variantReader.peek()) {
                        JsonReader.Token.BEGIN_ARRAY -> {
                            variantReader.beginArray()
                            while (variantReader.hasNext()) {
                                if (variantReader.peek() == JsonReader.Token.STRING) stamps += variantReader.nextString()
                                else variantReader.skipValue()
                            }
                            variantReader.endArray()
                        }
                        JsonReader.Token.STRING -> stamps += variantReader.nextString()
                        else -> variantReader.skipValue()
                    }
                    "thirdParty" -> if (variantReader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                        variantReader.beginObject()
                        while (variantReader.hasNext()) {
                            if (variantReader.nextName() == "cardmarket" && variantReader.peek() == JsonReader.Token.NUMBER) {
                                cardmarketProductId = variantReader.nextLong().takeIf { it > 0L }
                            } else variantReader.skipValue()
                        }
                        variantReader.endObject()
                    } else variantReader.skipValue()
                    "pricing" -> pricingProductId = readPrintingPricingProductId(variantReader)
                    else -> variantReader.skipValue()
                }; variantReader.endObject()
                val finish = when {
                    type.contains("reverse", true) -> "Reverse Holo"
                    type.contains("holo", true) -> "Holo"
                    type.equals("normal", true) -> "Normal"
                    else -> type.ifBlank { "Normal" }
                }
                val normalizedStamps = stamps.map(String::trim).filter(String::isNotEmpty)
                    .distinctBy { it.lowercase(Locale.ROOT) }.sortedBy { it.lowercase(Locale.ROOT) }
                val sourceVariant = buildList {
                    add(size.takeIf(String::isNotBlank) ?: finish)
                    subtype.trim().takeIf(String::isNotEmpty)?.let { add("Untertyp: $it") }
                    normalizedStamps.takeIf { it.isNotEmpty() }?.let { add("Stempel: ${it.joinToString(" + ")}") }
                    foil.trim().takeIf(String::isNotEmpty)?.let { add("Folie: $it") }
                }.joinToString(" · ")
                if (id.isNotBlank()) {
                    val declarations = declaredProductsByPrinting.getOrPut(Triple(id, finish, sourceVariant)) { mutableSetOf() }
                    cardmarketProductId?.let(declarations::add)
                    pricingProductId?.let(declarations::add)
                    val conflicting = cardmarketProductId != null && pricingProductId != null && cardmarketProductId != pricingProductId
                    if (conflicting) conflictingPrintings += Triple(id, finish, sourceVariant)
                    result += PokemonPrintVariant(id, finish, sourceVariant, cardmarketProductId.takeUnless { conflicting })
                }
            }
            else -> reader.skipValue()
        }; reader.endObject()
        // Identical raw-ID/finish/descriptor rows may disagree even when each
        // row's own thirdParty/pricing IDs agree. Never let source order pick a
        // market product; one unambiguous declaration can fill a missing duplicate.
        val productsByPrinting = result.groupBy { Triple(it.sourceVariantId, it.finish, it.variant) }
            .mapValues { (_, rows) -> rows.mapNotNull(PokemonPrintVariant::cardmarketProductId).toSet() }
        // Pricing-only metadata is evidence of disagreement, never a new link.
        declaredProductsByPrinting.filterValues { it.size > 1 }.keys.forEach { conflictingPrintings += it }
        // Older source payloads reuse "generated" for Normal/Holo and Reverse.
        // Preserve existing reference IDs and only disambiguate additional physical
        // printings. Reserve every real source ID before allocating local suffixes.
        val reservedIds = result.mapTo(mutableSetOf()) { it.id }
        val usedIds = mutableSetOf<String>()
        val blockedCardmarketVariantIds = mutableSetOf<String>()
        val printings = result.distinctBy { Triple(it.id, it.finish, it.variant) }.map { printing ->
            var localId = printing.id
            if (localId in usedIds) {
                val base = "${printing.id}~${printing.finish.length}:${printing.finish}${printing.variant.length}:${printing.variant}"
                localId = base
                var suffix = 1
                while (localId in reservedIds || localId in usedIds) localId = "$base~${suffix++}"
            }
            usedIds += localId
            val identity = Triple(printing.sourceVariantId, printing.finish, printing.variant)
            if (identity in conflictingPrintings) {
                blockedCardmarketVariantIds += localId
            }
            printing.copy(id = localId, cardmarketProductId =
                productsByPrinting[identity]?.singleOrNull().takeUnless { identity in conflictingPrintings })
        }
        return CatalogCardDetail(rarity, printings, image, blockedCardmarketVariantIds.toSet())
    }

    /** Inspect only printing-level identity; never import provider prices or root IDs. */
    private fun readPrintingPricingProductId(reader: JsonReader): Long? {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) { reader.skipValue(); return null }
        var productId: Long? = null
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() != "cardmarket" || reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "idProduct" && reader.peek() == JsonReader.Token.NUMBER) {
                    // Invalid/overflowing optional metadata must not throw into the
                    // DE→EN fallback, which could resurrect a contradictory link.
                    val numeric = reader.nextString().toBigDecimalOrNull()
                    productId = try { numeric?.longValueExact()?.takeIf { it > 0L } }
                    catch (_: ArithmeticException) { null }
                } else reader.skipValue()
            }
            reader.endObject()
        }
        reader.endObject()
        return productId
    }

    /** TCGdex returns a logo asset base path; Coil needs the actual image file. */
    private fun assetImageUrl(value: String): String? = value.trim().trimEnd('/').takeIf(String::isNotEmpty)?.let {
        if (it.substringBefore('?').substringAfterLast('/').contains('.')) it else "$it.png"
    }

    private fun collectorIdentity(value: String): String {
        val primary = value.substringBefore('/').uppercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        val prefix = primary.takeWhile(Char::isLetter)
        // Pure letter collectors such as R/RGB must index as R, not invented R0.
        val digits = primary.dropWhile(Char::isLetter).trimStart('0')
            .ifBlank { if (primary.any(Char::isDigit)) "0" else "" }
        return prefix + digits
    }
}

/** Optional metadata failures keep the existing fallback, but cancellation stops the lookup. */
private suspend fun <T> catalogOrDefault(default: T, load: suspend () -> T): T = try {
    load()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    default
}

/**
 * TCGdex stores promo local ids as digits (for example `173`) while the physical card and OCR use
 * the registered prefix (`SVP173`). Strip that prefix only after the requested source set itself
 * has been resolved to the corresponding promo catalogue; arbitrary letters remain significant.
 */
internal fun collectorIdentityForSourceLookup(value: String, resolvedSourceSetId: String): String {
    if (PokemonSetRegistry.normalize(resolvedSourceSetId) == "30thc") {
        classic30thLocalIdForPrintedCollector(value)?.let { return it }
    }
    val primary = value.substringBefore('/').uppercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    val prefix = primary.takeWhile(Char::isLetter)
    val digits = primary.dropWhile(Char::isLetter).trimStart('0').ifBlank { if (primary.any(Char::isDigit)) "0" else "" }
    val promoSet = PokemonSetRegistry.promoSetIdForCollectorNumber(primary)
    return if (
        promoSet != null &&
        PokemonSetRegistry.normalize(promoSet) == PokemonSetRegistry.normalize(resolvedSourceSetId)
    ) digits else prefix + digits
}

/**
 * The index endpoint omits dates for many older records. Keep the set chooser
 * current-first with the source's stable era/id chronology, while a typed search
 * always retains access to the complete German catalogue.
 */
internal fun catalogSetRecencyKey(set: PokemonSet): Double {
    val id = set.id.lowercase(Locale.ROOT)
    val era = when {
        id.startsWith("30th") -> 10
        id.startsWith("me") -> 9
        id.startsWith("sv") -> 8
        id.startsWith("swsh") -> 7
        id.startsWith("sm") -> 6
        id.startsWith("xy") -> 5
        id.startsWith("bw") -> 4
        id.startsWith("hgss") || id.startsWith("col") -> 3
        id.startsWith("dp") || id.startsWith("pl") -> 2
        id.startsWith("ex") || id.startsWith("pop") -> 1
        else -> 0
    }
    val number = Regex("\\d+(?:\\.\\d+)?").find(id)?.value?.toDoubleOrNull() ?: 0.0
    return era * 10_000.0 + number
}
