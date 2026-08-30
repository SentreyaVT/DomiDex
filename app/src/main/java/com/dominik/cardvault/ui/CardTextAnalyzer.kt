package com.dominik.cardvault.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
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
    private val collectorNumberPattern = Regex("(?i)(?<![A-Z0-9])(?:[A-Z]{2,5}\\s+)?(\\d{1,4})\\s*/\\s*(\\d{1,4})")
    private val noisePattern = Regex("(?i)^(?:hp|kp|stage|basic|basis|trainer|energy|energie|pokemon)\\s*\\d*$")
    private val rulesOrAttackPattern = Regex("(?i)^(?:ability|fähigkeit|attacke|schwäche|resistenz|rückzug|retreat|illus\\.?|regel|damage|schaden|draw|discard|heal|switch|search|choose|once during).*")
    private val setIdentifierPattern = Regex("(?i)(?<![A-Z0-9])(?:SV|SWSH|SM|XY|BW|DP|HS|EX|ME)\\s*[- ]?\\d{1,3}(?![A-Z0-9])")
    private val borderSetCodePattern = Regex("(?i)(?<![A-Z0-9])([A-Z]{2,5})\\s+(?=\\d{1,4}\\s*/)")
    // Some German lower borders have no space between the printed code and the
    // number (for example "OBF098/197"). Keep this constrained to a number
    // pair so title/attack text can never be mistaken for a set code.
    private val compactBorderSetCodePattern = Regex("(?i)(?<![A-Z0-9])([A-Z]{2,5})[-_]?\\s*(?=[0-9OIlSBZg]{1,4}\\s*[/|])")
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
        val numberEvidence = CardNumberParser.find(preferredNumberText.orEmpty())
            ?: lines.asSequence().mapNotNull(CardNumberParser::find).firstOrNull()
        val collectorNumber = numberEvidence?.value
        val setIdentifier = lines.asReversed().asSequence().mapNotNull { line ->
            // German cards may read "CRI DE", "DE CRI", or "CRI-DE". Treat DE
            // as the language marker in all three forms; no missing marker is an
            // error because the app's scan scope is German by design.
            val lowerBorder = germanMarkerPattern.replace(line, " ")
                .replace(Regex("[-_]+\\s*"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            setIdentifierPattern.find(lowerBorder)?.value
                ?: borderSetCodePattern.find(lowerBorder)?.groupValues?.get(1)
                ?: compactBorderSetCodePattern.find(lowerBorder)?.groupValues?.get(1)
                ?: printedSetCodeBeforeNumber(lowerBorder)
        }.firstOrNull()?.replace(Regex("[- ]"), "")?.uppercase()
        val queries = ((preferredNameText?.lineSequence()?.toList().orEmpty()) + lines).asSequence()
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
            hasNameRoiEvidence = preferredNameText?.any(Char::isLetter) == true,
        )
    }

    /**
     * Lower borders can include a one-letter regulation mark (H/I/…) and DE.
     * Neither is a set ID. Keep the nearby 3–4 letter printed code instead.
     */
    private fun printedSetCodeBeforeNumber(line: String): String? {
        val tokens = line.uppercase().split(Regex("[^A-Z0-9/]+"))
        val numberIndex = tokens.indexOfFirst { Regex("\\d{1,4}/\\d{1,4}").matches(it) }
        if (numberIndex < 0) return null
        return tokens.take(numberIndex).takeLast(5).asReversed()
            .firstOrNull { token -> token.length in 2..5 && token != "DE" && token !in setOf("HP", "KP") }
    }
}

internal class CardTextAnalyzer(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    // The live beta path is deliberately bounded to the on-device Latin recognizer.
    // Waiting for optional CJK modules could leave a captured card in an endless
    // "analysis running" state on offline/emulator devices. A German/English card
    // therefore always reaches its truthful candidate/manual continuation promptly.
    private val recognizers: Map<CardScript, TextRecognizer> = mapOf(
        CardScript.LATIN to TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
    )

    fun analyze(file: File, onComplete: (Result<CardTextAnalysis>) -> Unit) {
        val image = runCatching { InputImage.fromFilePath(appContext, Uri.fromFile(file)) }
            .getOrElse { onComplete(Result.failure(it)); return }
        // Keep the read order deterministic: the name area is deliberately first.
        // ML Kit finishes requests asynchronously, so a plain append would otherwise
        // occasionally make attack text win over the title line.
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
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        val raritySymbolEvidence = decoded?.let(CardRaritySymbolAnalyzer::analyze)
            ?: RaritySymbolEvidence(SymbolColor.UNKNOWN, null, 0f)
        val roiInputs = decoded?.let { bitmap ->
            // OCR is deliberately confined to two small regions first. This makes
            // holo glare and attack text far less influential than full-card OCR.
            val nameArea = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, (bitmap.height * 0.34f).toInt().coerceAtLeast(1))
            val lowerStart = (bitmap.height * 0.58f).toInt().coerceIn(0, bitmap.height - 1)
            val lowerArea = Bitmap.createBitmap(bitmap, 0, lowerStart, bitmap.width, bitmap.height - lowerStart)
            listOf(
                InputImage.fromBitmap(nameArea, 0),
                InputImage.fromBitmap(enhanceForOcr(nameArea), 0),
                InputImage.fromBitmap(lowerArea, 0),
                InputImage.fromBitmap(enhanceForOcr(lowerArea), 0),
            )
        }
        // Name ROI variants → complete image fallback → lower-border variants.
        // This happens only after a capture, never in ImageAnalysis.
        val images = if (roiInputs != null) listOf(roiInputs[0], roiInputs[1], image, roiInputs[2], roiInputs[3]) else listOf(image)
        fun parseSnapshot(snapshot: Map<CardScript, Map<Int, String>>): CardTextAnalysis {
            val texts = snapshot.mapValues { (_, readings) -> readings.toSortedMap().values.joinToString("\n") }
            val latin = snapshot[CardScript.LATIN].orEmpty()
            val nameRoi = if (roiInputs != null) listOfNotNull(latin[0], latin[1]).joinToString("\n") else null
            val lowerRoi = if (roiInputs != null) listOfNotNull(latin[3], latin[4]).joinToString("\n") else null
            return CardTextParser.parse(texts, preferredNameText = nameRoi, preferredNumberText = lowerRoi)
                .copy(raritySymbolEvidence = raritySymbolEvidence)
        }
        var remaining = recognizers.size * images.size
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
        recognizers.forEach { (script, recognizer) -> images.forEachIndexed { imageIndex, imageToRead ->
            recognizer.process(imageToRead)
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
