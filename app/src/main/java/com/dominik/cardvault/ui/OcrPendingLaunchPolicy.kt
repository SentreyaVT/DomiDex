package com.dominik.cardvault.ui

/**
 * Order already-grouped, uncached inputs; never regroup, renumber or inspect their pixels.
 * Original NUMBER evidence from each candidate orientation precedes repeated NAME variants.
 * Stable sorting keeps candidate orientation encounter order within each priority and leaves
 * every remaining group in its original order. Thus two orientations both get an initial
 * NUMBER opportunity before either gets a NAME; NAME and SET originals follow for both.
 * Unknown set codes still need additional evidence under the unchanged conservative selector.
 */
internal fun orderPendingOcrGroups(
    pending: List<List<IndexedValue<OcrInput>>>,
    orientationProbe: Boolean,
): List<List<IndexedValue<OcrInput>>> {
    // Full OCR must retain its existing order, including the exact list/group identities.
    if (!orientationProbe) return pending
    return pending.sortedBy { group ->
        val input = group.first().value
        if (input.variantId != "original" || input.orientation == null) 3 else when (input.profileId) {
            "orientation_rc3_number" -> 0
            "orientation_rc3_name" -> 1
            "orientation_rc3_set" -> 2
            else -> 3
        }
    }
}
