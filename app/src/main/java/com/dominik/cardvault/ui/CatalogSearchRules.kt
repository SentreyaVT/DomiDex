package com.dominik.cardvault.ui

internal fun matchesCardInSelectedSet(name: String, printedNumber: String, query: String): Boolean {
    val needle = query.trim()
    if (needle.isBlank()) return true
    if (name.contains(needle, ignoreCase = true)) return true
    val normalizedNeedle = normalizeCardNumber(needle)
    val normalizedNumber = normalizeCardNumber(printedNumber)
    return normalizedNeedle.isNotBlank() && (
        normalizedNumber == normalizedNeedle ||
            normalizedNumber.startsWith(normalizedNeedle) ||
            normalizedNeedle.startsWith(normalizedNumber)
        )
}

private fun normalizeCardNumber(value: String): String = value
    .uppercase()
    .replace(" ", "")
    .replace("-", "")
    .substringBefore('/')
    .trimStart('0')
