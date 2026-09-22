package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonSetRegistry
import com.dominik.cardvault.data.catalog.classic30thLocalIdForPrintedCollector

internal fun manualCollectorNumber(
    input: String,
    printedTotal: Int,
    setSourceId: String? = null,
    printedSetCode: String? = null,
): String? {
    if (isSelectedClassicCollection(setSourceId)) {
        val historic = Regex("^\\s*0*(\\d{1,4})\\s*/\\s*0*(\\d{1,4})\\s*$").matchEntire(input)
        if (historic != null) {
            val canonical = "${historic.groupValues[1].toInt()}/${historic.groupValues[2].toInt()}"
            return canonical.takeIf { classic30thLocalIdForPrintedCollector(it) != null }
        }
        val local = input.filter(Char::isDigit).toIntOrNull() ?: return null
        return local.takeIf { it in 1..30 }?.toString()?.padStart(3, '0')
    }
    val digits = input.substringBefore('/').filter(Char::isDigit)
    if (digits.isBlank() || digits.length > 4) return null
    // Promo catalogue totals grow over time; they are not a denominator printed on the card.
    val denominator = if (isSelectedPromoSet(setSourceId, printedSetCode)) 0 else printedTotal
    val width = maxOf(3, digits.length, denominator.takeIf { it > 0 }?.toString()?.length ?: 0)
    val localNumber = digits.toIntOrNull()?.toString()?.padStart(width, '0') ?: return null
    return if (denominator > 0) "$localNumber/${denominator.toString().padStart(width, '0')}" else localNumber
}

internal fun withManualScanIdentity(
    base: CardTextAnalysis?,
    setSourceId: String,
    printedSetCode: String,
    collectorNumber: String,
): CardTextAnalysis {
    val current = base ?: CardTextAnalysis(emptyList(), null, null, null, emptyList())
    val selectedNumber = if (isSelectedClassicCollection(setSourceId)) {
        manualCollectorNumber(collectorNumber, 0, setSourceId, printedSetCode) ?: collectorNumber
    } else if (isSelectedPromoSet(setSourceId, printedSetCode)) {
        manualCollectorNumber(collectorNumber, 0) ?: collectorNumber
    } else collectorNumber
    return current.copy(
        collectorNumber = selectedNumber,
        setIdentifier = printedSetCode,
        // A failed OCR name must not veto the collector's explicit catalogue set + number.
        searchQueries = emptyList(),
        hasNameRoiEvidence = false,
        numberConfidence = 1f,
        roiEvidence = listOf(
            CardOcrEvidence(CardOcrRegion.SET, listOf(printedSetCode), printedSetCode, 1f),
            CardOcrEvidence(CardOcrRegion.NUMBER, listOf(selectedNumber), selectedNumber, 1f),
        ),
        manualSetSourceId = setSourceId,
        manualIdentityConfirmed = true,
    )
}

private fun isSelectedClassicCollection(setSourceId: String?): Boolean =
    PokemonSetRegistry.normalize(setSourceId.orEmpty()) == "30thc"

private fun isSelectedPromoSet(setSourceId: String?, printedSetCode: String?): Boolean {
    if (setSourceId.isNullOrBlank() || printedSetCode.isNullOrBlank()) return false
    val promoSourceId = PokemonSetRegistry.promoSetIdForCollectorNumber("${printedSetCode}1") ?: return false
    return PokemonSetRegistry.normalize(promoSourceId) == PokemonSetRegistry.normalize(setSourceId)
}
