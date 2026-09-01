package com.dominik.cardvault.ui

internal fun manualCollectorNumber(input: String, printedTotal: Int): String? {
    val digits = input.substringBefore('/').filter(Char::isDigit).take(4)
    if (digits.isBlank()) return null
    val width = maxOf(3, digits.length, printedTotal.takeIf { it > 0 }?.toString()?.length ?: 0)
    val localNumber = digits.toIntOrNull()?.toString()?.padStart(width, '0') ?: return null
    return if (printedTotal > 0) "$localNumber/${printedTotal.toString().padStart(width, '0')}" else localNumber
}

internal fun withManualScanIdentity(
    base: CardTextAnalysis?,
    setSourceId: String,
    printedSetCode: String,
    collectorNumber: String,
): CardTextAnalysis {
    val current = base ?: CardTextAnalysis(emptyList(), null, null, null, emptyList())
    val retained = current.roiEvidence.filterNot { it.region in setOf(CardOcrRegion.SET, CardOcrRegion.NUMBER) }
    return current.copy(
        collectorNumber = collectorNumber,
        setIdentifier = printedSetCode,
        numberConfidence = 1f,
        roiEvidence = retained + listOf(
            CardOcrEvidence(CardOcrRegion.SET, listOf(printedSetCode), printedSetCode, 1f),
            CardOcrEvidence(CardOcrRegion.NUMBER, listOf(collectorNumber), collectorNumber, 1f),
        ),
        manualSetSourceId = setSourceId,
        manualIdentityConfirmed = true,
    )
}
