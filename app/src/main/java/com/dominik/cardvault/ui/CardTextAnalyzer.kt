package com.dominik.cardvault.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.dominik.cardvault.data.catalog.PokemonSetRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal enum class CardScript { LATIN, JAPANESE, CHINESE }

internal data class CardTextAnalysis(
    val lines: List<String>,
    val collectorNumber: String?,
    val setIdentifier: String?,
    val likelyScript: CardScript?,
    val searchQueries: List<String>,
    /** Confidence of the specialised lower-border number parser, not a card match. */
    val numberConfidence: Float = 0f,
    /** True when a dedicated top-name ROI yielded usable Latin name evidence. */
    val hasNameRoiEvidence: Boolean = false,
    /** Low-confidence lower-border colour evidence; never assigns a rarity on its own. */
    val raritySymbolEvidence: RaritySymbolEvidence = RaritySymbolEvidence(SymbolColor.UNKNOWN, null, 0f),
    /** Module-1 result; failed frames intentionally never reach a candidate lookup. */
    val imageQuality: CardImageQuality = CardImageQuality(),
    /** Current-session OCR evidence; selected fields may accompany a consented tester report. */
    val roiEvidence: List<CardOcrEvidence> = emptyList(),
    /** Perspective-corrected image kept only for the current local artwork cross-check. */
    val normalizedCard: Bitmap? = null,
    /** True when a layout-agnostic lower-corner/strip ROI contributed OCR evidence. */
    val usedDynamicOcr: Boolean = false,
    /** True when independent still frames contributed best-per-stage evidence. */
    val usedMultiFrameEvidence: Boolean = false,
    /** Compact in-memory descriptors; raw temporal frames are discarded immediately. */
    val visualDescriptors: List<ArtworkDescriptor> = emptyList(),
    /** Local diagnostic timings only; no image or account data is persisted. */
    val ocrDurationMillis: Long = 0L,
    val descriptorDurationMillis: Long = 0L,
    /** Explicit catalogue set chosen by the collector; never inferred from OCR. */
    val manualSetSourceId: String? = null,
    /** Allows an explicit set+number choice to continue even when Module 1 rejected the photo. */
    val manualIdentityConfirmed: Boolean = false,
    /** Canonical OCR plane selected from the capture's aspect-correct orientation candidates. */
    val analysisOrientation: AnalysisOrientation? = null,
    /** Primary-ROI orientation evidence only; this is not the final card-match confidence. */
    val orientationScore: Int = 0,
    /** Distance to the runner-up. A small margin deliberately prevents automatic matching. */
    val orientationMargin: Int = 0,
)

internal data class AnalysisOrientationEvidence(
    val orientation: AnalysisOrientation,
    val analysis: CardTextAnalysis,
    val score: Int = AnalysisOrientationSelector.score(analysis),
)

internal data class AnalysisOrientationSelection(
    val best: AnalysisOrientationEvidence?,
    val runnerUpScore: Int,
    val margin: Int,
    val accepted: Boolean,
    val reason: String,
)

/** Conservative, deterministic orientation selection based only on primary identity ROIs. */
internal object AnalysisOrientationSelector {
    internal const val MIN_SCORE = 70
    internal const val MIN_MARGIN = 12

    fun score(analysis: CardTextAnalysis): Int {
        val hasNumber = analysis.collectorNumber != null
        val hasSet = analysis.setIdentifier != null
        val hasName = analysis.hasNameRoiEvidence && analysis.searchQueries.isNotEmpty()
        return (
            (analysis.numberConfidence * 50f).toInt() +
                (if (hasSet) 25 else 0) +
                (if (hasName) 20 else 0) +
                (if (hasNumber && (hasSet || hasName)) 5 else 0)
            ).coerceIn(0, 100)
    }

    fun select(evidence: List<AnalysisOrientationEvidence>): AnalysisOrientationSelection {
        val ranked = evidence.sortedWith(
            compareByDescending<AnalysisOrientationEvidence>(AnalysisOrientationEvidence::score)
                .thenBy { it.orientation.degrees },
        )
        val best = ranked.firstOrNull()
        val runnerUp = ranked.getOrNull(1)?.score ?: 0
        val margin = ((best?.score ?: 0) - runnerUp).coerceAtLeast(0)
        val accepted = best != null &&
            best.analysis.collectorNumber != null &&
            best.score >= MIN_SCORE &&
            margin >= MIN_MARGIN
        val reason = when {
            best == null || best.score < MIN_SCORE || best.analysis.collectorNumber == null -> "orientation_evidence_insufficient"
            margin < MIN_MARGIN -> "orientation_evidence_ambiguous"
            else -> "orientation_selected"
        }
        return AnalysisOrientationSelection(best, runnerUp, margin, accepted, reason)
    }
}

/** Ensures a timeout and a late OCR completion can never parse/release the same bitmap twice. */
internal class SingleAnalysisDeliveryGate {
    private val claimed = AtomicBoolean(false)
    fun <T> claim(block: () -> T): T? = if (claimed.compareAndSet(false, true)) block() else null
}

/** Parses only lower-border-like number pairs; HP/KP and attack damage are excluded. */
internal object CardNumberParser {
    internal data class Evidence(val value: String, val confidence: Float, val supportingReads: Int = 1)
    // The lower border is the only place where these additional OCR repairs are
    // accepted. Z/2 and G/6 are common on foiled German cards; accepting them
    // in ordinary body text would make attack damage unsafe evidence.
    // ML Kit repeatedly reads the tiny printed slash as V or Y on real 12 MP captures.
    // This repair is deliberately confined to two numeric groups inside an identity ROI.
    private val pair = Regex("(?i)(?<![A-Z0-9])([0-9OIlSBZg]{1,4})\\s*[/|VY]\\s*([0-9OIlSBZg]{1,4})(?![A-Z0-9])")
    private val prefixedPair = Regex("(?i)(?<![A-Z0-9])([A-Z]{1,3})[-_ ]*([0-9OIlSBZg]{1,3})\\s*[/|]\\s*\\1[-_ ]*([0-9OIlSBZg]{1,3})(?![A-Z0-9])")
    private val setBoundSingle = Regex("(?i)(?:^|\\s)([A-Z]{3})(?:[-_ ]+DE)?[-_ ]+([0-9OIlSBZg]{1,4})(?:\\s|$)")
    // German promo borders have no /total: ML Kit can split MEP / DE / 071 into
    // separate lines or join the boxed regulation mark to MEP. Keep the registered
    // promo prefix bound to its number; never infer it from a name or bare digits.
    private val promoCollector = Regex("(?i)(?<![A-Z0-9])([A-Z]{2,4})[-_\\s]*(?:DE[-_\\s]*|D[-_\\s]+)?(?=[0-9OIlSBZg]*[0-9])([0-9OIlSBZg]{1,3})(?![A-Z0-9/])")

    fun find(text: String): Evidence? {
        val lines = text.lineSequence()
        .map { it.trim() }
        .filterNot { line -> line.contains(Regex("(?i)\\b(?:hp|kp)\\b")) }
        .toList()
        val pairEvidence = pairCandidates(lines)
        // Multiple independent ROI/preprocessing reads are evidence. A single early OCR mistake
        // must not beat the collector pair repeated by the other lower-border variants.
        val consensus = pairEvidence.groupBy(Evidence::value).values.maxWithOrNull(
            compareBy<List<Evidence>> { it.size }
                .thenBy { evidence -> evidence.count { it.confidence >= .95f } }
                .thenBy { evidence -> evidence.maxOf(Evidence::confidence) },
        )
        return consensus?.let { evidence ->
            Evidence(
                value = evidence.first().value,
                confidence = (evidence.maxOf(Evidence::confidence) + (evidence.size - 1) * .01f).coerceAtMost(.99f),
                supportingReads = evidence.size,
            )
        } ?: promoCollector.findAll(lines.joinToString("\n")).mapNotNull { match ->
                val prefix = match.groupValues[1].uppercase()
                val number = normalize(match.groupValues[2]).takeIf(String::isNotBlank) ?: return@mapNotNull null
                val canonical = PokemonSetRegistry.canonicalPromoCollectorNumber(prefix + number)
                    ?: prefix.takeIf { it.endsWith("DE") }?.dropLast(2)
                        ?.let { PokemonSetRegistry.canonicalPromoCollectorNumber(it + number) }
                    ?: prefix.takeIf { it.length == 4 && it.first() in "DEFGHIJO" }
                        ?.drop(1)?.let { PokemonSetRegistry.canonicalPromoCollectorNumber(it + number) }
                canonical?.let { Evidence(it, .90f) }
        }.firstOrNull()
            ?: lines.asSequence().flatMap { setBoundSingle.findAll(it) }
                .filter { PokemonSetRegistry.isVerifiedPrintedCode(it.groupValues[1]) }
                .mapNotNull { normalize(it.groupValues[2]).takeIf(String::isNotBlank) }
                .map { number -> Evidence(number.padStart(3, '0'), .64f) }
                .firstOrNull()
    }

    internal fun pairCandidates(text: String): List<Evidence> = pairCandidates(
        text.lineSequence().map(String::trim).filter(String::isNotBlank).toList(),
    )

    private fun pairCandidates(lines: List<String>): List<Evidence> = lines.flatMap { line ->
            val ordinary = pair.findAll(line).mapNotNull { match ->
                val left = normalize(match.groupValues[1]); val right = normalize(match.groupValues[2])
                if (left.isBlank() || right.isBlank()) null
                else Evidence("${left.padStart(3, '0')}/${right.padStart(3, '0')}", if (match.value.any { it in "OolISBZzGg" }) .84f else .96f)
            }.toList()
            val subsets = prefixedPair.findAll(line).mapNotNull { match ->
                val prefix = match.groupValues[1].uppercase()
                val left = normalize(match.groupValues[2]); val right = normalize(match.groupValues[3])
                if (left.isBlank() || right.isBlank()) null
                else Evidence("$prefix${left.padStart(2, '0')}/$prefix${right.padStart(2, '0')}", if (match.value.any { it in "OolISBZzGg" }) .84f else .95f)
            }.toList()
            ordinary + subsets
        }

    private fun normalize(value: String): String = value
        .replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1')
        .replace('S', '5').replace('s', '5').replace('B', '8')
        .replace('Z', '2').replace('z', '2').replace('G', '6').replace('g', '6')
        .filter(Char::isDigit)
}

internal data class IdentityNumberReading(val text: String, val isTightIdentityStrip: Boolean)

/**
 * A shallow lower-left identity strip excludes rule text and therefore outranks broad lower-card
 * crops only when at least two preprocessing variants independently agree. A single sharp-looking
 * OCR mistake is never promoted. If the tight strip is empty or disagrees internally, the existing
 * all-ROI consensus remains the safe fallback.
 */
internal fun selectIdentityNumberText(readings: List<IdentityNumberReading>): String {
    val all = readings.map(IdentityNumberReading::text).filter(String::isNotBlank)
    val tight = readings.filter(IdentityNumberReading::isTightIdentityStrip)
        .map(IdentityNumberReading::text)
        .filter(String::isNotBlank)
    if (tight.isEmpty()) return all.joinToString("\n")
    val tightEvidence = CardNumberParser.find(tight.joinToString("\n"))
    val allEvidence = CardNumberParser.find(all.joinToString("\n"))
    val tightPairs = tight.flatMap(CardNumberParser::pairCandidates)
    val numeratorConsensus = tightPairs.groupingBy { it.value.substringBefore('/') }.eachCount()
        .maxByOrNull(Map.Entry<String, Int>::value)
    val denominatorConsensus = tightPairs.groupingBy { it.value.substringAfter('/') }.eachCount()
        .maxByOrNull(Map.Entry<String, Int>::value)
    val componentCorroboratedPair = if (
        (numeratorConsensus?.value ?: 0) >= 2 && (denominatorConsensus?.value ?: 0) >= 2
    ) {
        tightPairs.firstOrNull {
            it.value.substringBefore('/') == numeratorConsensus?.key &&
                it.value.substringAfter('/') == denominatorConsensus?.key
        }
    } else null
    if (componentCorroboratedPair != null) return componentCorroboratedPair.value
    val tightIsCorroborated = tightEvidence != null && (
        tightEvidence.supportingReads >= 2 || tightEvidence.value == allEvidence?.value
    )
    return if (tightIsCorroborated) tight.joinToString("\n") else all.joinToString("\n")
}

internal object CardTextParser {
    private val collectorNumberPattern = Regex("(?i)(?<![A-Z0-9])(?:[A-Z]{3}\\s+)?(\\d{1,4})\\s*/\\s*(\\d{1,4})")
    private val noisePattern = Regex("(?i)^(?:hp|kp|stage|basic|basis|trainer|energy|energie|pokemon)\\s*\\d*$")
    private val rulesOrAttackPattern = Regex("(?i)^(?:ability|fähigkeit|attacke|schwäche|resistenz|rückzug|retreat|illus\\.?|regel|damage|schaden|draw|discard|heal|switch|search|choose|once during).*")
    private val setIdentifierPattern = Regex("(?i)(?<![A-Z0-9])(?:SV|SWSH|SM|XY|BW|DP|HS|EX|ME)\\s*[- ]?\\d{1,3}(?![A-Z0-9])")
    // The lower border may start with a one-letter regulation mark such as H or J.
    // It is never a set identifier. The current printed set marker is exactly the
    // following three contiguous letters (for example "H PBL DE 001/084").
    private val threeLetterBorderSetCodePattern = Regex("(?i)(?<![A-Z0-9])([A-Z]{3})(?![A-Z0-9])\\s*(?=[0-9OIlSBZg]{1,4}\\s*[/|])")
    // Some German lower borders have no delimiter between the three-letter code and
    // the number (for example "OBF098/197"). It remains tied to a number pair so
    // title or attack text can never become a set marker.
    private val compactThreeLetterBorderSetCodePattern = Regex("(?i)(?<![A-Z0-9])([A-Z]{3})(?![A-Z0-9])[-_]?\\s*(?=[0-9OIlSBZg]{1,4}\\s*[/|])")
    private val threeLetterSetCodeBeforeSingleNumber = Regex("(?i)(?<![A-Z0-9])([A-Z]{3})(?![A-Z0-9])[-_ ]+(?=[0-9OIlSBZg]{1,4}(?:\\s|$))")
    // Foil glare can attach one stray OCR letter to the printed code (e.g. CRIm). Only a
    // source-verified three-letter prefix directly followed by the collector number is usable.
    private val noisyFourLetterSetPrefixBeforeNumber = Regex("(?i)(?<![A-Z0-9])([A-Z]{3})[A-Z](?![A-Z0-9])[-_ ]+(?=[0-9OIlSBZg]{1,4}(?:\\s*[/|oO]\\s*[0-9OIlSBZg]{1,4})?)")
    // Language/market abbreviations often sit beside the lower-border set code.
    // They identify the card's language, never its expansion.
    // Scanner scope is deliberately German. "DE" can be printed with or without
    // separators after the actual set code (e.g. CRI-DE); it must never become a
    // bogus set code or part of the recognised card name.
    private val germanMarkerPattern = Regex("(?i)(?<![A-Z0-9])DE(?![A-Z0-9])")

    fun parse(
        textByScript: Map<CardScript, String>,
        preferredNameText: String? = null,
        preferredNumberText: String? = null,
        preferredSetText: String? = null,
    ): CardTextAnalysis {
        val latinScore = textByScript[CardScript.LATIN].orEmpty().count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }
        val japaneseScore = textByScript[CardScript.JAPANESE].orEmpty().count { it in '\u3040'..'\u30ff' }
        val chineseScore = textByScript[CardScript.CHINESE].orEmpty().count { it in '\u3400'..'\u9fff' }
        val likelyScript = when {
            japaneseScore >= 3 && japaneseScore >= latinScore / 2 -> CardScript.JAPANESE
            chineseScore >= 3 && chineseScore >= latinScore / 2 -> CardScript.CHINESE
            latinScore >= 3 -> CardScript.LATIN
            japaneseScore >= 3 -> CardScript.JAPANESE
            chineseScore >= 3 -> CardScript.CHINESE
            else -> null
        }
        val selectedTexts = likelyScript?.let { listOf(textByScript[it].orEmpty()) } ?: textByScript.values
        val lines = selectedTexts.asSequence()
            .flatMap { it.lineSequence() }
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length in 2..80 }
            .distinct()
            .toList()
        // Prefer the dedicated lower-border ROI. Corrections are deliberately limited
        // to a number pair candidate and never leak into name matching.
        // Identity numbers are valid only from the normalized lower-border ROI.
        // Do not fall back to arbitrary OCR lines: HP, damage, copyright years
        // and artwork text otherwise become dangerous false positives.
        // Direct parser callers (unit fixtures and legacy integrations) may not
        // provide ROI values yet. The camera pipeline always supplies all three
        // values (also as empty strings), so it never falls back to whole-card
        // OCR in production.
        val legacyStructuredText = lines.joinToString("\n")
        val numberEvidence = CardNumberParser.find(
            preferredNumberText ?: legacyStructuredText,
        )
        val collectorNumber = numberEvidence?.value
        // The set marker follows the same geometrical rule. It is read only from
        // the lower identification band, never from names, attacks or rule text.
        fun setCodeFrom(line: String): String? {
            // German cards may read "CRI DE", "DE CRI", or "CRI-DE". Treat DE
            // as the language marker in all three forms; no missing marker is an
            // error because the app's scan scope is German by design.
            val lowerBorder = germanMarkerPattern.replace(line, " ")
                .replace(Regex("[-_]+\\s*"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val printedCandidates = listOfNotNull(
                threeLetterBorderSetCodePattern.find(lowerBorder)?.groupValues?.get(1),
                compactThreeLetterBorderSetCodePattern.find(lowerBorder)?.groupValues?.get(1),
                threeLetterSetCodeBeforeSingleNumber.find(lowerBorder)?.groupValues?.get(1),
                printedSetCodeBeforeNumber(lowerBorder),
                noisyFourLetterSetPrefixBeforeNumber.find(lowerBorder)?.groupValues?.get(1),
            )
            val verifiedPrintedCode = printedCandidates.firstOrNull(PokemonSetRegistry::isVerifiedPrintedCode)
            return verifiedPrintedCode
                // Older cards can use an established alpha-numeric series marker
                // such as SV3. This fallback still never accepts H/J as a set ID.
                ?: setIdentifierPattern.find(lowerBorder)?.value
        }
        val setRoiLines = preferredSetText.orEmpty().lineSequence().filter(String::isNotBlank).toList()
        val numberRoiLines = preferredNumberText.orEmpty().lineSequence().filter(String::isNotBlank).toList()
        fun mostFrequentCode(lines: List<String>): String? = lines.mapNotNull(::setCodeFrom)
            .map { it.replace(Regex("[- ]"), "").uppercase() }
            .groupingBy { it }
            .eachCount()
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            ?.key
        // Real holo capture showed why independent ROI order is unsafe: a set-only crop read
        // SRI, while the same printed lower-border line correctly read GRI + 087 together.
        // A code physically bound to the selected collector number is therefore stronger than
        // any standalone set crop. No fuzzy code or missing digit is invented here.
        val numberBoundSetLines = numberRoiLines.filter { line ->
            CardNumberParser.find(line)?.value == collectorNumber
        }
        val fallbackLowerBorderLines = if (preferredSetText != null || preferredNumberText != null) {
            setRoiLines + numberRoiLines
        } else {
            legacyStructuredText.lineSequence().toList()
        }
        val setIdentifier = collectorNumber?.let(PokemonSetRegistry::promoSetIdForCollectorNumber)
            ?: mostFrequentCode(numberBoundSetLines)
            ?: mostFrequentCode(fallbackLowerBorderLines)
        // Name lookup is intentionally restricted to the top name ROI. Attack
        // names and rules text may look like Pokémon names but cannot identify a
        // printing without the lower-border evidence above.
        val nameSource = preferredNameText ?: legacyStructuredText
        val queries = nameSource.lineSequence().asSequence()
            .map { it.substringBefore("  ").trim(' ', '·', '-', '•') }
            .filter { it.length in 3..36 && it.any(Char::isLetter) && !noisePattern.matches(it) && !rulesOrAttackPattern.matches(it) }
            // Border codes and country markers are metadata, not a card name.
            .filterNot { CardNumberParser.find(it) != null || collectorNumberPattern.containsMatchIn(it) }
            .filterNot { it.count(Char::isDigit) > it.length / 2 }
            .filterNot { it.count { character -> character in ".,:;!?" } > 2 }
            .take(8)
            .toList()
        return CardTextAnalysis(
            lines = lines,
            collectorNumber = collectorNumber,
            setIdentifier = setIdentifier,
            likelyScript = likelyScript,
            searchQueries = queries,
            numberConfidence = numberEvidence?.confidence ?: 0f,
            hasNameRoiEvidence = preferredNameText?.any(Char::isLetter) ?: queries.isNotEmpty(),
        )
    }

    /**
     * Lower borders can include a one-letter regulation mark (H/I/J/…) and DE.
     * Neither is a set ID. Only a contiguous three-letter code immediately
     * associated with the collector number is accepted.
     */
    private fun printedSetCodeBeforeNumber(line: String): String? {
        val tokens = line.uppercase().split(Regex("[^A-Z0-9/]+"))
        val numberIndex = tokens.indexOfFirst { Regex("\\d{1,4}/\\d{1,4}").matches(it) }
        if (numberIndex < 0) return null
        return tokens.take(numberIndex).takeLast(5).asReversed()
            .firstOrNull { token -> token.length == 3 && token.all(Char::isLetter) && token != "DE" }
    }
}

internal class CardTextAnalyzer(@Suppress("UNUSED_PARAMETER") context: Context) : AutoCloseable {
    // The live beta path is deliberately bounded to the on-device Latin recognizer.
    // Waiting for optional CJK modules could leave a captured card in an endless
    // "analysis running" state on offline/emulator devices. A German/English card
    // therefore always reaches its truthful candidate/manual continuation promptly.
    private val recognizers: Map<CardScript, TextRecognizer> = mapOf(
        CardScript.LATIN to TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
    )
    // Module 1 uses OpenCV and must never run in the camera callback or Compose main dispatcher.
    // One slot matches the scanner queue's bounded worker count, keeping memory and native work
    // predictable even when a collector captures a series quickly.
    private val preprocessingExecutor = Executors.newFixedThreadPool(ScanQueuePolicy.MAX_PARALLEL_WORKERS)

    fun analyze(
        file: File,
        allowManualWholeImageFallback: Boolean = false,
        onComplete: (Result<CardTextAnalysis>) -> Unit,
    ) {
        preprocessingExecutor.execute { analyzeNormalized(file, allowManualWholeImageFallback, onComplete) }
    }

    suspend fun analyzeAwait(file: File, allowManualWholeImageFallback: Boolean = false): Result<CardTextAnalysis> = suspendCancellableCoroutine { continuation ->
        analyze(file, allowManualWholeImageFallback) { result ->
            if (continuation.isActive) continuation.resume(result)
            else result.getOrNull()?.normalizedCard?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun analyzeNormalized(
        file: File,
        allowManualWholeImageFallback: Boolean,
        onComplete: (Result<CardTextAnalysis>) -> Unit,
    ) {
        val normalized = CardImageNormalizer.normalize(file, allowManualWholeImageFallback)
        val initialDisplay = normalized.bitmap
        if (initialDisplay == null) {
            // Module 1 is a hard safety gate. A sleeve, binder pocket or arbitrary background
            // must not flow into identity OCR and accidentally become a Binder card.
            onComplete(Result.success(CardTextAnalysis(emptyList(), null, null, null, emptyList(), imageQuality = normalized.quality)))
            return
        }
        normalized.recognitionBitmap?.let { if (!it.isRecycled) it.recycle() }
        normalized.originalCrop?.let { if (!it.isRecycled) it.recycle() }
        val variants = normalized.analysisVariants.ifEmpty {
            listOf(CanonicalCardAnalysisVariant(AnalysisOrientation.DEG_0, initialDisplay))
        }
        val ocrStarted = SystemClock.elapsedRealtime()
        val mainHandler = Handler(Looper.getMainLooper())
        fun finish(result: Result<CardTextAnalysis>) = mainHandler.post { onComplete(result) }
        fun recycleVariants(keep: Bitmap? = null) = variants.map(CanonicalCardAnalysisVariant::bitmap).distinct().forEach {
            if (it !== keep && !it.isRecycled) it.recycle()
        }
        fun safeManualResult(reason: String, best: AnalysisOrientationEvidence? = null, margin: Int = 0) {
            val descriptorStarted = SystemClock.elapsedRealtime()
            val descriptor = runCatching { CardArtworkVerifier.describe(initialDisplay) }.getOrNull()
            recycleVariants(initialDisplay)
            val base = best?.analysis ?: CardTextAnalysis(emptyList(), null, null, null, emptyList())
            finish(Result.success(base.copy(
                collectorNumber = null,
                setIdentifier = null,
                imageQuality = normalized.quality.copy(reason = reason),
                normalizedCard = initialDisplay,
                visualDescriptors = descriptor?.let(::listOf).orEmpty(),
                analysisOrientation = best?.orientation,
                orientationScore = best?.score ?: 0,
                orientationMargin = margin,
                ocrDurationMillis = SystemClock.elapsedRealtime() - ocrStarted,
                descriptorDurationMillis = SystemClock.elapsedRealtime() - descriptorStarted,
            )))
        }

        val probeInputs = variants.flatMap(::buildOrientationProbeInputs)
        recognizeBatch(probeInputs, ORIENTATION_TIMEOUT_MILLIS) { probeBatch ->
            try {
                if (probeBatch.readings.isEmpty()) {
                    safeManualResult("orientation_evidence_insufficient")
                    return@recognizeBatch
                }
                val latin = probeBatch.readings[CardScript.LATIN].orEmpty()
                val evidence = variants.map { variant ->
                    val indexed = probeInputs.withIndex().filter { it.value.orientation == variant.orientation }
                    val localInputs = indexed.map { it.value }
                    val localReadings = indexed.mapIndexedNotNull { localIndex, global ->
                        latin[global.index]?.let { localIndex to it }
                    }.toMap()
                    val parsed = parseOcrSnapshot(
                        inputs = localInputs,
                        snapshot = mapOf(CardScript.LATIN to localReadings),
                        scanId = file.nameWithoutExtension,
                        emitDiagnostics = false,
                    ).analysis
                    AnalysisOrientationEvidence(variant.orientation, parsed)
                }
                val selection = AnalysisOrientationSelector.select(evidence)
                if (com.dominik.cardvault.BuildConfig.DEBUG) {
                    ScannerDiagnosticLog.event(
                        "ocr_orientation",
                        file.nameWithoutExtension,
                        "scores" to evidence.joinToString { "${it.orientation.degrees}:${it.score}" },
                        "selected" to selection.best?.orientation?.degrees,
                        "margin" to selection.margin,
                        "decision" to selection.reason,
                    )
                }
                val winner = selection.best
                if (!selection.accepted || winner == null) {
                    safeManualResult(selection.reason, selection.best, selection.margin)
                    return@recognizeBatch
                }

                val selected = variants.first { it.orientation == winner.orientation }
                val selectedDisplay = Bitmap.createScaledBitmap(
                    selected.bitmap,
                    CardScanConfiguration.NORMALIZED_WIDTH,
                    CardScanConfiguration.NORMALIZED_HEIGHT,
                    true,
                )
                if (initialDisplay !== selectedDisplay && !initialDisplay.isRecycled) initialDisplay.recycle()
                val raritySymbolEvidence = CardRaritySymbolAnalyzer.analyze(selectedDisplay)
                val inputs = buildOcrInputs(selected.bitmap, null, selected.orientation)
                recycleVariants(selectedDisplay)
                recognizeBatch(inputs, FULL_OCR_TIMEOUT_MILLIS) { fullBatch ->
                    try {
                        if (fullBatch.readings.isEmpty()) {
                            finish(Result.success(CardTextAnalysis(
                                emptyList(), null, null, null, emptyList(),
                                imageQuality = normalized.quality.copy(reason = "ocr_timeout"),
                                normalizedCard = selectedDisplay,
                                analysisOrientation = selected.orientation,
                                orientationScore = winner.score,
                                orientationMargin = selection.margin,
                            )))
                            return@recognizeBatch
                        }
                        // Orientation already read real identity ROIs on this exact plane.
                        // Do not discard those readings when the later OCR batch is partial.
                        // Reuse only the selected orientation, with the same parser/consensus.
                        val selectedProbeInputs = probeInputs.withIndex().filter { it.value.orientation == selected.orientation }
                        val combinedReadings = (fullBatch.readings.keys + probeBatch.readings.keys).associateWith { script ->
                            fullBatch.readings[script].orEmpty() + selectedProbeInputs.mapIndexedNotNull { index, probe ->
                                probeBatch.readings[script]?.get(probe.index)?.let { inputs.size + index to it }
                            }.toMap()
                        }
                        val parsed = parseOcrSnapshot(
                            inputs + selectedProbeInputs.map { it.value },
                            combinedReadings,
                            file.nameWithoutExtension,
                            emitDiagnostics = true,
                        )
                        val descriptorStarted = SystemClock.elapsedRealtime()
                        val descriptor = CardArtworkVerifier.describe(selectedDisplay)
                        finish(Result.success(parsed.analysis.copy(
                            raritySymbolEvidence = raritySymbolEvidence,
                            imageQuality = normalized.quality,
                            roiEvidence = parsed.evidence,
                            normalizedCard = selectedDisplay,
                            usedDynamicOcr = parsed.usedDynamicEvidence,
                            visualDescriptors = listOf(descriptor),
                            analysisOrientation = selected.orientation,
                            orientationScore = winner.score,
                            orientationMargin = selection.margin,
                            ocrDurationMillis = SystemClock.elapsedRealtime() - ocrStarted,
                            descriptorDurationMillis = SystemClock.elapsedRealtime() - descriptorStarted,
                        )))
                    } catch (error: Throwable) {
                        if (!selectedDisplay.isRecycled) selectedDisplay.recycle()
                        finish(Result.failure(error))
                    }
                }
            } catch (error: Throwable) {
                recycleVariants()
                if (!initialDisplay.isRecycled) initialDisplay.recycle()
                finish(Result.failure(error))
            }
        }
    }

    private fun recognizeBatch(
        inputs: List<OcrInput>,
        timeoutMillis: Long,
        onComplete: (OcrBatchResult) -> Unit,
    ) {
        if (inputs.isEmpty()) {
            preprocessingExecutor.execute { onComplete(OcrBatchResult(emptyMap(), IllegalStateException("Keine OCR-ROIs vorhanden."))) }
            return
        }
        val results = mutableMapOf<CardScript, MutableMap<Int, String>>()
        val failures = mutableListOf<Throwable>()
        val lock = Any()
        val gate = SingleAnalysisDeliveryGate()
        val handler = Handler(Looper.getMainLooper())
        val inputsReleased = AtomicBoolean(false)
        var remaining = recognizers.size * inputs.size
        fun releaseInputs() {
            if (inputsReleased.compareAndSet(false, true)) {
                inputs.map(OcrInput::bitmap).distinct().forEach { if (!it.isRecycled) it.recycle() }
            }
        }
        fun deliver(readings: Map<CardScript, Map<Int, String>>, failure: Throwable?) {
            gate.claim {
                handler.removeCallbacksAndMessages(null)
                preprocessingExecutor.execute { onComplete(OcrBatchResult(readings, failure)) }
            }
        }
        handler.postDelayed({
            val partial = synchronized(lock) { results.mapValues { it.value.toMap() } }
            deliver(partial, if (partial.isEmpty()) TimeoutException("Lokale Texterkennung hat kein Ergebnis geliefert.") else null)
        }, timeoutMillis)
        recognizers.forEach { (script, recognizer) ->
            inputs.forEachIndexed { imageIndex, input ->
                recognizer.process(input.image)
                    .addOnSuccessListener { text ->
                        synchronized(lock) { results.getOrPut(script, ::mutableMapOf)[imageIndex] = text.text }
                    }
                    .addOnFailureListener { error -> synchronized(lock) { failures += error } }
                    .addOnCompleteListener {
                        val terminal = synchronized(lock) {
                            remaining -= 1
                            if (remaining == 0) results.mapValues { it.value.toMap() } else null
                        }
                        if (terminal != null) {
                            releaseInputs()
                            deliver(terminal, if (terminal.isEmpty()) failures.firstOrNull() else null)
                        }
                    }
            }
        }
    }

    override fun close() {
        preprocessingExecutor.shutdownNow()
        recognizers.values.forEach(TextRecognizer::close)
    }

    private companion object {
        const val ORIENTATION_TIMEOUT_MILLIS = 5_000L
        const val FULL_OCR_TIMEOUT_MILLIS = 9_000L
    }
}

private data class OcrBatchResult(
    val readings: Map<CardScript, Map<Int, String>>,
    val failure: Throwable? = null,
)

private data class ParsedOcrSnapshot(
    val analysis: CardTextAnalysis,
    val evidence: List<CardOcrEvidence>,
    val usedDynamicEvidence: Boolean,
)

private data class OcrInput(
    val regions: Set<CardOcrRegion>,
    val image: InputImage,
    val dynamic: Boolean,
    val bitmap: Bitmap,
    val profileId: String,
    val variantId: String,
    val isTightIdentityStrip: Boolean,
    val orientation: AnalysisOrientation? = null,
)

private fun parseOcrSnapshot(
    inputs: List<OcrInput>,
    snapshot: Map<CardScript, Map<Int, String>>,
    scanId: String,
    emitDiagnostics: Boolean,
): ParsedOcrSnapshot {
    val texts = snapshot.mapValues { (_, readings) -> readings.toSortedMap().values.joinToString("\n") }
    val latin = snapshot[CardScript.LATIN].orEmpty()
    fun values(region: CardOcrRegion) = inputs.mapIndexedNotNull { index, input ->
        latin[index]?.takeIf { region in input.regions }
    }
    val names = values(CardOcrRegion.NAME)
    val sets = values(CardOcrRegion.SET)
    val numbers = values(CardOcrRegion.NUMBER)
    val numberReadings = inputs.mapIndexedNotNull { index, input ->
        latin[index]?.takeIf { CardOcrRegion.NUMBER in input.regions }?.let { reading ->
            IdentityNumberReading(reading, input.isTightIdentityStrip)
        }
    }
    val selectedNumberText = selectIdentityNumberText(numberReadings)
    if (emitDiagnostics && com.dominik.cardvault.BuildConfig.DEBUG) {
        inputs.forEachIndexed { index, input ->
            val reading = latin[index].orEmpty()
            if (CardOcrRegion.NUMBER in input.regions && reading.isNotBlank()) {
                ScannerDiagnosticLog.event(
                    "ocr_identity_roi",
                    scanId,
                    "orientation" to input.orientation?.degrees,
                    "profile" to input.profileId,
                    "variant" to input.variantId,
                    "tight" to input.isTightIdentityStrip,
                    "text" to reading,
                )
            }
        }
    }
    val parsed = CardTextParser.parse(
        texts,
        preferredNameText = names.joinToString("\n"),
        preferredNumberText = selectedNumberText,
        preferredSetText = sets.joinToString("\n"),
    )
    val evidence = listOf(
        CardOcrEvidence(CardOcrRegion.NAME, names, parsed.searchQueries.firstOrNull(), if (parsed.hasNameRoiEvidence) .62f else 0f),
        CardOcrEvidence(CardOcrRegion.SET, sets, parsed.setIdentifier, parsed.setIdentifier?.let { .80f } ?: 0f),
        CardOcrEvidence(CardOcrRegion.NUMBER, numbers, parsed.collectorNumber, parsed.numberConfidence),
    )
    val dynamicEvidenceUsed = inputs.mapIndexedNotNull { index, input ->
        latin[index]?.takeIf { input.dynamic && it.isNotBlank() }
    }.isNotEmpty()
    return ParsedOcrSnapshot(parsed, evidence, dynamicEvidenceUsed)
}

private fun cropAtReadableScale(source: Bitmap, roi: CardRoi): Bitmap {
    val crop = roi.crop(source)
    if (roi.bottom - roi.top > .12f) return crop
    val scale = if (roi.bottom - roi.top <= .10f) 3 else 2
    return Bitmap.createScaledBitmap(crop, crop.width * scale, crop.height * scale, true).also { crop.recycle() }
}

// These are the bounded top/lower crops used by the last pre-ROI recognition path. They remain
// secondary inputs: current layout-specific ROIs run first and all parsed evidence still passes
// through the existing conservative identity and candidate decision logic.
internal val historicalNameFallbackRoi = CardRoi(0f, 0f, 1f, .34f)
internal val historicalIdentityFallbackRoi = CardRoi(0f, .58f, 1f, 1f)

private fun buildOrientationProbeInputs(variant: CanonicalCardAnalysisVariant): List<OcrInput> {
    val modern = CardOcrRois.profiles.first { it.id == "modern" }
    val identityStrip = CardOcrRois.profiles.first { it.id == "full_art_left" }.number
    val extendedBorder = CardOcrRois.profiles.first { it.id == "extended_lower_border" }.set.union(
        CardOcrRois.profiles.first { it.id == "extended_lower_border" }.number,
    )
    fun original(regions: Set<CardOcrRegion>, roi: CardRoi, profile: String, tight: Boolean = false): OcrInput {
        val crop = cropAtReadableScale(variant.bitmap, roi)
        return OcrInput(
            regions,
            InputImage.fromBitmap(crop, 0),
            dynamic = profile != "modern",
            bitmap = crop,
            profileId = "orientation_$profile",
            variantId = "original",
            isTightIdentityStrip = tight,
            orientation = variant.orientation,
        )
    }
    fun enhanced(regions: Set<CardOcrRegion>, roi: CardRoi, profile: String, tight: Boolean = false): OcrInput {
        val crop = cropAtReadableScale(variant.bitmap, roi)
        val enhanced = enhanceForOcr(crop)
        crop.recycle()
        return OcrInput(
            regions,
            InputImage.fromBitmap(enhanced, 0),
            dynamic = profile != "modern",
            bitmap = enhanced,
            profileId = "orientation_$profile",
            variantId = "enhanced",
            isTightIdentityStrip = tight,
            orientation = variant.orientation,
        )
    }
    fun rc3Variants(region: CardOcrRegion, roi: CardRoi, profile: String, inverted: Boolean = false): List<OcrInput> {
        val result = mutableListOf(
            original(setOf(region), roi, profile),
            enhanced(setOf(region), roi, profile),
        )
        if (inverted) {
            val crop = cropAtReadableScale(variant.bitmap, roi)
            val enhancedBitmap = enhanceForOcr(crop)
            val invertedBitmap = invertForOcr(enhancedBitmap)
            crop.recycle()
            enhancedBitmap.recycle()
            result += OcrInput(
                setOf(region),
                InputImage.fromBitmap(invertedBitmap, 0),
                dynamic = false,
                bitmap = invertedBitmap,
                profileId = "orientation_$profile",
                variantId = "inverted",
                isTightIdentityStrip = false,
                orientation = variant.orientation,
            )
        }
        return result
    }
    // RC3 reached real-card recognition through three independent ROIs. Preserve that exact
    // evidence separation for orientation selection; the current full OCR and final conservative
    // candidate decision still run afterwards. Historical broad bands remain secondary fallbacks.
    return buildList {
        addAll(rc3Variants(CardOcrRegion.NAME, modern.name, "rc3_name"))
        addAll(rc3Variants(CardOcrRegion.SET, modern.set, "rc3_set", inverted = true))
        addAll(rc3Variants(CardOcrRegion.NUMBER, modern.number, "rc3_number", inverted = true))
        // The narrow, enlarged border already used by full OCR must also be available
        // before orientation selection. Otherwise small promo digits never reach full OCR.
        add(original(setOf(CardOcrRegion.SET, CardOcrRegion.NUMBER), identityStrip, "identity_strip", tight = true))
        add(enhanced(setOf(CardOcrRegion.SET, CardOcrRegion.NUMBER), identityStrip, "identity_strip", tight = true))
        add(original(setOf(CardOcrRegion.SET, CardOcrRegion.NUMBER), extendedBorder, "extended_border"))
        add(enhanced(setOf(CardOcrRegion.SET, CardOcrRegion.NUMBER), extendedBorder, "extended_border"))
        addAll(listOf(
            original(setOf(CardOcrRegion.NAME), historicalNameFallbackRoi, "historical_name"),
            original(setOf(CardOcrRegion.SET, CardOcrRegion.NUMBER), historicalIdentityFallbackRoi, "historical_identity"),
        ))
    }
}

private fun buildOcrInputs(
    bitmap: Bitmap,
    recognitionBitmap: Bitmap?,
    orientation: AnalysisOrientation? = null,
): List<OcrInput> {
    fun variants(
        regions: Set<CardOcrRegion>,
        roi: CardRoi,
        dynamic: Boolean,
        profileId: String,
        inverted: Boolean = false,
    ): List<OcrInput> {
        val crop = cropAtReadableScale(bitmap, roi)
        val enhanced = recognitionBitmap?.let { cropAtReadableScale(it, roi) } ?: enhanceForOcr(crop)
        val tightIdentity = CardOcrRegion.NUMBER in regions && roi.bottom - roi.top <= .10f
        val result = mutableListOf(OcrInput(regions, InputImage.fromBitmap(crop, 0), dynamic, crop, profileId, "original", tightIdentity, orientation))
        if (enhanced !== crop) result += OcrInput(regions, InputImage.fromBitmap(enhanced, 0), dynamic, enhanced, profileId, "enhanced", tightIdentity, orientation)
        if (roi.bottom - roi.top <= .12f) {
            val thresholded = thresholdForOcr(enhanced)
            result += OcrInput(regions, InputImage.fromBitmap(thresholded, 0), dynamic, thresholded, profileId, "threshold", tightIdentity, orientation)
        }
        if (inverted) {
            val invertedBitmap = invertForOcr(enhanced)
            result += OcrInput(regions, InputImage.fromBitmap(invertedBitmap, 0), dynamic, invertedBitmap, profileId, "inverted", tightIdentity, orientation)
        }
        return result
    }
    val primary = CardOcrRois.profiles.take(3)
    val dynamic = CardOcrRois.profiles.drop(3)
    // Names need only three broad top bands. Lower identity data additionally searches left,
    // centre, right and a full-width strip so special-card print can move without enabling unsafe
    // whole-card OCR.
    return buildList {
        primary.forEach { profile ->
            addAll(variants(setOf(CardOcrRegion.NAME), profile.name, false, "${profile.id}_name"))
        }
        (primary + dynamic).forEachIndexed { index, profile ->
            val isDynamic = index >= primary.size
            // Set and collector number occupy the same tiny printed band. Reading their union once
            // preserves their physical relationship, halves ML Kit work and improves consensus.
            addAll(variants(setOf(CardOcrRegion.SET, CardOcrRegion.NUMBER), profile.set.union(profile.number), isDynamic, profile.id, inverted = true))
        }
        addAll(variants(setOf(CardOcrRegion.NAME), historicalNameFallbackRoi, true, "historical_name"))
        addAll(variants(setOf(CardOcrRegion.SET, CardOcrRegion.NUMBER), historicalIdentityFallbackRoi, true, "historical_identity", inverted = true))
    }
}

private fun thresholdForOcr(source: Bitmap): Bitmap {
    val rgba = Mat(); val gray = Mat(); val binary = Mat()
    return try {
        Utils.bitmapToMat(source, rgba)
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, org.opencv.core.Size(3.0, 3.0), 0.0)
        Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        Bitmap.createBitmap(binary.cols(), binary.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(binary, it) }
    } finally { rgba.release(); gray.release(); binary.release() }
}

/** Small, allocation-bounded ROI enhancement for glare/low-contrast lower print. */
private fun enhanceForOcr(source: Bitmap): Bitmap {
    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val contrast = 1.32f; val translate = (-.5f * contrast + .5f) * 255f
    val matrix = ColorMatrix().apply {
        setSaturation(0f)
        postConcat(ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        )))
    }
    Canvas(output).drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) })
    return output
}

private fun invertForOcr(source: Bitmap): Bitmap {
    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val matrix = ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ))
    Canvas(output).drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) })
    return output
}
