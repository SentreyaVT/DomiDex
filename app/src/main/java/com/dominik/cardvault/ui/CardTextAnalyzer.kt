package com.dominik.cardvault.ui

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

internal enum class CardScript { LATIN, JAPANESE, CHINESE }

internal data class CardTextAnalysis(
    val lines: List<String>,
    val collectorNumber: String?,
    val likelyScript: CardScript?,
    val searchQueries: List<String>,
)

internal object CardTextParser {
    private val collectorNumberPattern = Regex("(?i)(?<![A-Z0-9])(?:[A-Z]{1,4}\\s*)?\\d{1,4}\\s*/\\s*(?:[A-Z]{1,4}\\s*)?\\d{1,4}")
    private val noisePattern = Regex("(?i)^(?:hp|kp|stage|basic|basis|trainer|energy|energie|pokemon)\\s*\\d*$")

    fun parse(textByScript: Map<CardScript, String>): CardTextAnalysis {
        val lines = textByScript.values.asSequence()
            .flatMap { it.lineSequence() }
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length in 2..80 }
            .distinct()
            .toList()
        val combined = lines.joinToString(" ")
        val collectorNumber = lines.asSequence().mapNotNull { collectorNumberPattern.find(it)?.value }.firstOrNull()?.replace(" ", "")
        val likelyScript = when {
            combined.any { it in '\u3040'..'\u30ff' } -> CardScript.JAPANESE
            combined.any { it in '\u3400'..'\u9fff' } -> CardScript.CHINESE
            combined.any(Char::isLetter) -> CardScript.LATIN
            else -> null
        }
        val queries = lines.asSequence()
            .map { it.substringBefore("  ").trim(' ', '·', '-', '•') }
            .filter { it.length in 3..36 && it.any(Char::isLetter) && !noisePattern.matches(it) }
            .filterNot { it.count(Char::isDigit) > it.length / 2 }
            .take(8)
            .toList()
        return CardTextAnalysis(lines, collectorNumber, likelyScript, queries)
    }
}

internal class CardTextAnalyzer(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val recognizers: Map<CardScript, TextRecognizer> = mapOf(
        CardScript.LATIN to TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
        CardScript.JAPANESE to TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
        CardScript.CHINESE to TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
    )

    fun analyze(file: File, onComplete: (Result<CardTextAnalysis>) -> Unit) {
        val image = runCatching { InputImage.fromFilePath(appContext, Uri.fromFile(file)) }
            .getOrElse { onComplete(Result.failure(it)); return }
        val results = mutableMapOf<CardScript, String>()
        val failures = mutableListOf<Throwable>()
        var remaining = recognizers.size
        val lock = Any()
        recognizers.forEach { (script, recognizer) ->
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    synchronized(lock) { results[script] = text.text }
                }
                .addOnFailureListener { error ->
                    synchronized(lock) { failures += error }
                }
                .addOnCompleteListener {
                    val outcome = synchronized(lock) {
                        remaining -= 1
                        if (remaining != 0) null
                        else if (results.isNotEmpty()) Result.success(CardTextParser.parse(results))
                        else Result.failure(failures.firstOrNull() ?: IllegalStateException("Texterkennung ohne Ergebnis beendet."))
                    }
                    outcome?.let(onComplete)
                }
        }
    }

    override fun close() = recognizers.values.forEach(TextRecognizer::close)
}
