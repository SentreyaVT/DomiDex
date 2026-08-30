package com.dominik.cardvault.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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
)

/** Parses only lower-border-like number pairs; HP/KP and attack damage are excluded. */
internal object CardNumberParser {
    internal data class Evidence(val value: String, val confidence: Float)
    // The lower border is the only place where these additional OCR repairs are
    // accepted. Z/2 and G/6 are common on foiled German cards; accepting them
    // in ordinary body text would make attack damage unsafe evidence.
    private val pair = Regex("(?i)(?<![A-Z0-9])([0-9OIlSBZg]{1,4})\\s*[/|]\\s*([0-9OIlSBZg]{1,4})(?![A-Z0-9])")

    fun find(text: String): Evidence? = text.lineSequence()
        .map { it.trim() }
        .filterNot { line -> line.contains(Regex("(?i)\\b(?:hp|kp)\\b")) }
        .mapNotNull { line -> pair.find(line)?.let { match ->
            val left = normalize(match.groupValues[1]); val right = normalize(match.groupValues[2])
            if (left.isBlank() || right.isBlank()) null
            else Evidence("${left.padStart(3, '0')}/${right.padStart(3, '0')}", if (line.any { it in "OolISBZzGg" }) .84f else .96f)
        } }
        .firstOrNull()

    private fun normalize(value: String): String = value
        .replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1')
        .replace('S', '5').replace('s', '5').replace('B', '8')
        .replace('Z', '2').replace('z', '2').replace('G', '6').replace('g', '6')
        .filter(Char::isDigit)
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
        val lowerBorderLines = if (preferredSetText != null || preferredNumberText != null) {
            preferredSetText.orEmpty().lineSequence().toList() + preferredNumberText.orEmpty().lineSequence().toList()
        } else {
            legacyStructuredText.lineSequence().toList()
        }
        val setIdentifier = lowerBorderLines.asSequence().mapNotNull { line ->
            // German cards may read "CRI DE", "DE CRI", or "CRI-DE". Treat DE
            // as the language marker in all three forms; no missing marker is an
            // error because the app's scan scope is German by design.
            val lowerBorder = germanMarkerPattern.replace(line, " ")
                .replace(Regex("[-_]+\\s*"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            threeLetterBorderSetCodePattern.find(lowerBorder)?.groupValues?.get(1)
                ?: compactThreeLetterBorderSetCodePattern.find(lowerBorder)?.groupValues?.get(1)
                // Older cards can use an established alpha-numeric series marker
                // such as SV3. This fallback still never accepts H/J as a set ID.
                ?: setIdentifierPattern.find(lowerBorder)?.value
                ?: printedSetCodeBeforeNumber(lowerBorder)
        }.firstOrNull()?.replace(Regex("[- ]"), "")?.uppercase()
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

    fun analyze(file: File, onComplete: (Result<CardTextAnalysis>) -> Unit) {
        val normalized = CardImageNormalizer.normalize(file)
        val bitmap = normalized.bitmap
        if (bitmap == null) {
            // Module 1 is a hard safety gate. A sleeve, binder pocket or arbitrary background
            // must not flow into identity OCR and accidentally become a Binder card.
            onComplete(Result.success(CardTextAnalysis(emptyList(), null, null, null, emptyList(), imageQuality = normalized.quality)))
            return
        }
        val raritySymbolEvidence = CardRaritySymbolAnalyzer.analyze(bitmap)
        val inputs = buildOcrInputs(bitmap)
        // Keep the read order deterministic. Each region has explicit variants; full-card OCR is
        // intentionally absent so attack text and KP never become primary identity evidence.
        val results = mutableMapOf<CardScript, MutableMap<Int, String>>()
        val failures = mutableListOf<Throwable>()
        val delivered = AtomicBoolean(false)
        val timeoutHandler = Handler(Looper.getMainLooper())
        fun deliver(outcome: Result<CardTextAnalysis>) {
            if (delivered.compareAndSet(false, true)) {
                timeoutHandler.removeCallbacksAndMessages(null)
                onComplete(outcome)
            }
        }
        fun parseSnapshot(snapshot: Map<CardScript, Map<Int, String>>): CardTextAnalysis {
            val texts = snapshot.mapValues { (_, readings) -> readings.toSortedMap().values.joinToString("\n") }
            val latin = snapshot[CardScript.LATIN].orEmpty()
            fun values(region: CardOcrRegion) = inputs.mapIndexedNotNull { index, input -> latin[index]?.takeIf { input.region == region } }
            val names = values(CardOcrRegion.NAME)
            val sets = values(CardOcrRegion.SET)
            val numbers = values(CardOcrRegion.NUMBER)
            val parsed = CardTextParser.parse(
                texts,
                preferredNameText = names.joinToString("\n"),
                preferredNumberText = numbers.joinToString("\n"),
                preferredSetText = sets.joinToString("\n"),
            )
            val evidence = listOf(
                CardOcrEvidence(CardOcrRegion.NAME, names, parsed.searchQueries.firstOrNull(), if (parsed.hasNameRoiEvidence) .62f else 0f),
                CardOcrEvidence(CardOcrRegion.SET, sets, parsed.setIdentifier, parsed.setIdentifier?.let { .80f } ?: 0f),
                CardOcrEvidence(CardOcrRegion.NUMBER, numbers, parsed.collectorNumber, parsed.numberConfidence),
            )
            return parsed.copy(raritySymbolEvidence = raritySymbolEvidence, imageQuality = normalized.quality, roiEvidence = evidence, normalizedCard = bitmap)
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
            if (partial.isNotEmpty()) deliver(Result.success(parseSnapshot(partial)))
            else deliver(Result.failure(TimeoutException("Lokale Texterkennung hat kein Ergebnis geliefert.")))
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
                    val outcome = synchronized(lock) {
                        remaining -= 1
                        if (remaining != 0) null
                        else if (results.isNotEmpty()) Result.success(parseSnapshot(results.mapValues { (_, readings) -> readings.toMap() }))
                        else Result.failure(failures.firstOrNull() ?: IllegalStateException("Texterkennung ohne Ergebnis beendet."))
                    }
                    outcome?.let(::deliver)
                }
        } }
    }

    override fun close() = recognizers.values.forEach(TextRecognizer::close)
}

private data class OcrInput(val region: CardOcrRegion, val image: InputImage)

private fun buildOcrInputs(bitmap: Bitmap): List<OcrInput> {
    fun variants(region: CardOcrRegion, roi: CardRoi, inverted: Boolean = false): List<OcrInput> {
        val crop = roi.crop(bitmap)
        val enhanced = enhanceForOcr(crop)
        val result = mutableListOf(
            OcrInput(region, InputImage.fromBitmap(crop, 0)),
            OcrInput(region, InputImage.fromBitmap(enhanced, 0)),
        )
        if (inverted) result += OcrInput(region, InputImage.fromBitmap(invertForOcr(enhanced), 0))
        return result
    }
    return variants(CardOcrRegion.NAME, CardOcrRois.name) +
        variants(CardOcrRegion.SET, CardOcrRois.set, inverted = true) +
        variants(CardOcrRegion.NUMBER, CardOcrRois.number, inverted = true)
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
