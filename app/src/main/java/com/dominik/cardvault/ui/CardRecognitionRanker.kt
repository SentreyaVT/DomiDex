package com.dominik.cardvault.ui

import com.dominik.cardvault.model.CatalogSearchResult
import java.text.Normalizer

/** Explainable ranking for candidates that already passed the set + printed-number gate. */
internal object CardRecognitionRanker {
    fun score(analysis: CardTextAnalysis, candidate: CatalogSearchResult): Int {
        val readNumber = analysis.collectorNumber?.substringBefore('/')?.trimStart('0')
        val catalogNumber = candidate.collectorNumber?.substringBefore('/')?.trimStart('0')
        val numberMatch = readNumber != null && readNumber == catalogNumber
        val name = normalize(candidate.name)
        val nameEvidence = analysis.searchQueries.maxOfOrNull { similarity(normalize(it), name) } ?: 0f
        val hasReadableName = analysis.hasNameRoiEvidence && analysis.searchQueries.any { normalize(it).length >= 3 }
        // Set + number are hard gates before this method is called.  A readable
        // German name then confirms the identity; a clear contradiction must not
        // become a high-confidence false positive.
        var confidence = (numberMatch.then(55) + (analysis.setIdentifier != null).then(20) + (nameEvidence * 25f).toInt())
        // Lower-border colour is deliberately only corroboration. It can never
        // turn an unknown/contradictory candidate into a match by itself.
        val rarity = CardRarityResolver.fromMetadata(candidate.rarity, candidate.collectorNumber.orEmpty(), candidate.mainSetSize)
        if (CardRarityResolver.symbolCorroborates(rarity.type, analysis.raritySymbolEvidence)) confidence += 2
        if (hasReadableName && nameEvidence < .38f) confidence = minOf(confidence, 58)
        return confidence.coerceIn(0, 100)
    }

    fun label(score: Int) = when {
        score >= 90 -> "sehr plausibel"
        score >= 76 -> "plausibel"
        else -> "nicht eindeutig"
    }

    // OCR frequently drops German umlauts/accents. Compare their base letters,
    // while keeping the original strings for display and manual confirmation.
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .lowercase()
        .replace("ß", "ss")
        .filter(Char::isLetterOrDigit)
    private fun similarity(left: String, right: String): Float {
        if (left.isBlank() || right.isBlank()) return 0f
        if (left == right || left.contains(right) || right.contains(left)) return 1f
        val a = left.windowed(2).toSet(); val b = right.windowed(2).toSet()
        val bigram = a.intersect(b).size.toFloat() / a.union(b).size.coerceAtLeast(1)
        val distance = levenshtein(left, right)
        val edit = 1f - distance.toFloat() / maxOf(left.length, right.length)
        return (bigram * .42f + edit.coerceIn(0f, 1f) * .58f).coerceIn(0f, 1f)
    }

    private fun Boolean.then(value: Int) = if (this) value else 0

    private fun levenshtein(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { row, leftChar ->
            val current = IntArray(right.length + 1); current[0] = row + 1
            right.forEachIndexed { column, rightChar ->
                current[column + 1] = minOf(current[column] + 1, previous[column + 1] + 1, previous[column] + if (leftChar == rightChar) 0 else 1)
            }
            previous = current
        }
        return previous.last()
    }
}
