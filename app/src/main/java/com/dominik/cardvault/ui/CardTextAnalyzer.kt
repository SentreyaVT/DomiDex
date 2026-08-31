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
    /** Current-session OCR evidence by normalized ROI. It is not persisted or uploaded. */
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
)

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
    private val setBoundSingle = Regex("(?i)(?:^|\\s)[A-Z]{3}(?:[-_ ]+DE)?[-_ ]+([0-9OIlSBZg]{1,4})(?:\\s|$)")
    private val promoCollector = Regex("(?i)(?<![A-Z0-9])(SWSH|HGSS|BW|XY|SM)[-_ ]*([0-9OIlSBZg]{1,3})(?![A-Z0-9])")

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
        } ?: lines.asSequence().mapNotNull { line ->
            promoCollector.find(line)?.let { match ->
                val prefix = match.groupValues[1].uppercase()
                val number = normalize(match.groupValues[2]).takeIf(String::isNotBlank) ?: return@let null
                val width = if (prefix == "SWSH") 3 else 2
                Evidence(prefix + number.padStart(width, '0'), .90f)
            }
        }.firstOrNull()
            ?: lines.asSequence().mapNotNull { line -> setBoundSingle.find(line)?.groupValues?.get(1)?.let(::normalize)?.takeIf(String::isNotBlank) }
                .map { number -> Evidence(number.padStart(3, '0'), .64f) }
                .firstOrNull()
    }

    internal fun pairCandidates(text: String): List<Evidence> = pairCandidates(
        text.lineSequence().map(String::trim).filter(String::isNotBlank).toList(),
    )

    private fun pairCandidates(lines: List<String>): List<Evidence> = lines.flatMap { line ->
            pair.findAll(line).mapNotNull { match ->
                val left = normalize(match.groupValues[1]); val right = normalize(match.groupValues[2])
                if (left.isBlank() || right.isBlank()) null
                else Evidence("${left.padStart(3, '0')}/${right.padStart(3, '0')}", if (match.value.any { it in "OolISBZzGg" }) .84f else .96f)
            }.toList()
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
    // Two slots match the scanner queue's bounded worker count, keeping memory and native work
    // predictable even when a collector captures a series quickly.
    private val preprocessingExecutor = Executors.newFixedThreadPool(ScanQueuePolicy.MAX_PARALLEL_WORKERS)

    fun analyze(file: File, onComplete: (Result<CardTextAnalysis>) -> Unit) {
        preprocessingExecutor.execute { analyzeNormalized(file, onComplete) }
    }

    suspend fun analyzeAwait(file: File): Result<CardTextAnalysis> = suspendCancellableCoroutine { continuation ->
        analyze(file) { result -> if (continuation.isActive) continuation.resume(result) }
    }

    private fun analyzeNormalized(file: File, onComplete: (Result<CardTextAnalysis>) -> Unit) {
        val normalized = CardImageNormalizer.normalize(file)
        val bitmap = normalized.bitmap
        if (bitmap == null) {
            // Module 1 is a hard safety gate. A sleeve, binder pocket or arbitrary background
            // must not flow into identity OCR and accidentally become a Binder card.
            onComplete(Result.success(CardTextAnalysis(emptyList(), null, null, null, emptyList(), imageQuality = normalized.quality)))
            return
        }
        val raritySymbolEvidence = CardRaritySymbolAnalyzer.analyze(bitmap)
        val inputs = buildOcrInputs(bitmap, normalized.recognitionBitmap)
        val ocrStarted = SystemClock.elapsedRealtime()
        normalized.recognitionBitmap?.recycle()
        normalized.originalCrop?.recycle()
        // Keep the read order deterministic. Each region has explicit variants; full-card OCR is
        // intentionally absent so attack text and KP never become primary identity evidence.
        val results = mutableMapOf<CardScript, MutableMap<Int, String>>()
        val failures = mutableListOf<Throwable>()
        val deliveryGate = SingleAnalysisDeliveryGate()
        val inputBitmapsReleased = AtomicBoolean(false)
        val timeoutHandler = Handler(Looper.getMainLooper())
        fun releaseInputBitmaps() {
            if (inputBitmapsReleased.compareAndSet(false, true)) {
                inputs.map(OcrInput::bitmap).distinct().forEach { inputBitmap ->
                    if (!inputBitmap.isRecycled) inputBitmap.recycle()
                }
            }
        }
        fun deliver(buildOutcome: () -> Result<CardTextAnalysis>) {
            val claimed = deliveryGate.claim {
                timeoutHandler.removeCallbacksAndMessages(null)
                true
            } ?: return
            if (!claimed) return
            // Descriptor sampling is CPU work and must not run on ML Kit's main-thread callback.
            preprocessingExecutor.execute {
                val outcome = try { buildOutcome() } catch (error: Throwable) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    Result.failure(error)
                }
                // All screen state is deliberately updated on the main dispatcher, preserving
                // the scan item's own id/state association.
                timeoutHandler.post { onComplete(outcome) }
            }
        }
        fun parseSnapshot(snapshot: Map<CardScript, Map<Int, String>>): CardTextAnalysis {
            val texts = snapshot.mapValues { (_, readings) -> readings.toSortedMap().values.joinToString("\n") }
            val latin = snapshot[CardScript.LATIN].orEmpty()
            fun values(region: CardOcrRegion) = inputs.mapIndexedNotNull { index, input -> latin[index]?.takeIf { region in input.regions } }
            val names = values(CardOcrRegion.NAME)
            val sets = values(CardOcrRegion.SET)
            val numbers = values(CardOcrRegion.NUMBER)
            val numberReadings = inputs.mapIndexedNotNull { index, input ->
                latin[index]?.takeIf { CardOcrRegion.NUMBER in input.regions }?.let { reading ->
                    IdentityNumberReading(reading, input.isTightIdentityStrip)
                }
            }
            val selectedNumberText = selectIdentityNumberText(numberReadings)
            if (com.dominik.cardvault.BuildConfig.DEBUG) {
                inputs.forEachIndexed { index, input ->
                    val reading = latin[index].orEmpty()
                    if (CardOcrRegion.NUMBER in input.regions && reading.isNotBlank()) {
                        ScannerDiagnosticLog.event(
                            "ocr_identity_roi",
                            file.nameWithoutExtension,
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
            val descriptorStarted = SystemClock.elapsedRealtime()
            val descriptor = CardArtworkVerifier.describe(bitmap)
            return parsed.copy(
                raritySymbolEvidence = raritySymbolEvidence,
                imageQuality = normalized.quality,
                roiEvidence = evidence,
                normalizedCard = bitmap,
                usedDynamicOcr = dynamicEvidenceUsed,
                visualDescriptors = listOf(descriptor),
                ocrDurationMillis = SystemClock.elapsedRealtime() - ocrStarted,
                descriptorDurationMillis = SystemClock.elapsedRealtime() - descriptorStarted,
            )
        }
        var remaining = recognizers.size * inputs.size
        val lock = Any()
        // Never leave a person waiting indefinitely for an OCR engine on a weak or
        // cold-start emulator. Partial text is still useful; no text cleanly opens
        // the existing manual set/number route instead of pretending to know a card.
        timeoutHandler.postDelayed({
            val partial = synchronized(lock) {
                results.mapValues { (_, readings) -> readings.toMap() }
            }
            deliver {
                if (partial.isNotEmpty()) Result.success(parseSnapshot(partial))
                else Result.failure(TimeoutException("Lokale Texterkennung hat kein Ergebnis geliefert."))
            }
        }, 9_000L)
        recognizers.forEach { (script, recognizer) -> inputs.forEachIndexed { imageIndex, input ->
            recognizer.process(input.image)
                .addOnSuccessListener { text ->
                    synchronized(lock) { results.getOrPut(script, ::mutableMapOf)[imageIndex] = text.text }
                }
                .addOnFailureListener { error ->
                    synchronized(lock) { failures += error }
                }
                .addOnCompleteListener {
                    val terminalSnapshot = synchronized(lock) {
                        remaining -= 1
                        if (remaining != 0) null
                        else results.mapValues { (_, readings) -> readings.toMap() }
                    }
                    if (terminalSnapshot != null) {
                        releaseInputBitmaps()
                        deliver {
                            if (terminalSnapshot.isNotEmpty()) Result.success(parseSnapshot(terminalSnapshot))
                            else Result.failure(failures.firstOrNull() ?: IllegalStateException("Texterkennung ohne Ergebnis beendet."))
                        }
                    }
                }
        } }
    }

    override fun close() {
        preprocessingExecutor.shutdownNow()
        recognizers.values.forEach(TextRecognizer::close)
    }
}

private data class OcrInput(
    val regions: Set<CardOcrRegion>,
    val image: InputImage,
    val dynamic: Boolean,
    val bitmap: Bitmap,
    val profileId: String,
    val variantId: String,
    val isTightIdentityStrip: Boolean,
)

private fun buildOcrInputs(bitmap: Bitmap, recognitionBitmap: Bitmap?): List<OcrInput> {
    fun cropAtReadableScale(source: Bitmap, roi: CardRoi): Bitmap {
        val crop = roi.crop(source)
        if (roi.bottom - roi.top > .12f) return crop
        // The collector line on ACE SPEC/full-art cards is physically tiny. Upscaling the
        // already perspective-corrected ROI does not invent pixels; it gives ML Kit a stable
        // glyph size while keeping rule text and the rest of the card excluded.
        val scale = if (roi.bottom - roi.top <= .10f) 3 else 2
        return Bitmap.createScaledBitmap(crop, crop.width * scale, crop.height * scale, true).also { crop.recycle() }
    }
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
        val result = mutableListOf(OcrInput(regions, InputImage.fromBitmap(crop, 0), dynamic, crop, profileId, "original", tightIdentity))
        if (enhanced !== crop) result += OcrInput(regions, InputImage.fromBitmap(enhanced, 0), dynamic, enhanced, profileId, "enhanced", tightIdentity)
        if (roi.bottom - roi.top <= .12f) {
            val thresholded = thresholdForOcr(enhanced)
            result += OcrInput(regions, InputImage.fromBitmap(thresholded, 0), dynamic, thresholded, profileId, "threshold", tightIdentity)
        }
        if (inverted) {
            val invertedBitmap = invertForOcr(enhanced)
            result += OcrInput(regions, InputImage.fromBitmap(invertedBitmap, 0), dynamic, invertedBitmap, profileId, "inverted", tightIdentity)
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
