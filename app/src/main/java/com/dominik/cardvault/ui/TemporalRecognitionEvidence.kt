package com.dominik.cardvault.ui

/**
 * Combines independent OCR/geometry results from a tiny CameraX still burst. Pixels are never
 * averaged: number, set/name evidence and the best visual frame are selected independently.
 */
internal object TemporalRecognitionEvidence {
    fun merge(analyses: List<CardTextAnalysis>): CardTextAnalysis? {
        if (analyses.isEmpty()) return null
        val valid = analyses.filter { it.imageQuality.cardDetected && it.normalizedCard != null }
        val pool = valid.ifEmpty { analyses }
        val visualBest = pool.maxWithOrNull(
            compareBy<CardTextAnalysis> { it.imageQuality.sharpness }
                .thenBy { it.imageQuality.frameQualityScore },
        )
            ?: return analyses.first()
        val numberBest = pool.filter { !it.collectorNumber.isNullOrBlank() }
            .maxByOrNull(CardTextAnalysis::numberConfidence)
        val setBest = pool.filter { !it.setIdentifier.isNullOrBlank() }.maxByOrNull { analysis ->
            analysis.roiEvidence.firstOrNull { it.region == CardOcrRegion.SET }?.confidence ?: 0f
        }
        val identityBest = numberBest ?: pool.maxByOrNull { analysis ->
            analysis.roiEvidence.sumOf { (it.confidence * 100).toInt() }
        } ?: visualBest
        val mergedQueries = pool.flatMap(CardTextAnalysis::searchQueries).distinct().take(8)
        val mergedLines = pool.flatMap(CardTextAnalysis::lines).distinct().take(24)
        val mergedRoi = CardOcrRegion.entries.mapNotNull { region ->
            val evidence = pool.mapNotNull { analysis -> analysis.roiEvidence.firstOrNull { it.region == region } }
            val strongest = evidence.maxByOrNull(CardOcrEvidence::confidence) ?: return@mapNotNull null
            strongest.copy(rawValues = evidence.flatMap(CardOcrEvidence::rawValues).filter(String::isNotBlank).distinct().take(12))
        }
        // Only the chosen visual bitmap survives. Other temporary normalized frames are released
        // before the queue continues to network candidate verification.
        analyses.asSequence().mapNotNull(CardTextAnalysis::normalizedCard).distinct()
            .filter { it !== visualBest.normalizedCard }.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        return visualBest.copy(
            lines = mergedLines,
            collectorNumber = numberBest?.collectorNumber ?: identityBest.collectorNumber,
            setIdentifier = numberBest?.setIdentifier ?: setBest?.setIdentifier ?: identityBest.setIdentifier,
            likelyScript = pool.mapNotNull(CardTextAnalysis::likelyScript).groupingBy { it }.eachCount().maxByOrNull(Map.Entry<CardScript, Int>::value)?.key,
            searchQueries = mergedQueries,
            numberConfidence = numberBest?.numberConfidence ?: identityBest.numberConfidence,
            hasNameRoiEvidence = pool.any(CardTextAnalysis::hasNameRoiEvidence),
            raritySymbolEvidence = pool.maxByOrNull { it.raritySymbolEvidence.confidence }?.raritySymbolEvidence ?: visualBest.raritySymbolEvidence,
            roiEvidence = mergedRoi,
            usedDynamicOcr = pool.any(CardTextAnalysis::usedDynamicOcr),
            usedMultiFrameEvidence = analyses.size > 1,
            visualDescriptors = pool.flatMap(CardTextAnalysis::visualDescriptors).take(4),
            imageQuality = visualBest.imageQuality.copy(selectedBestFrame = analyses.indexOf(visualBest).coerceAtLeast(0)),
            ocrDurationMillis = analyses.maxOfOrNull(CardTextAnalysis::ocrDurationMillis) ?: visualBest.ocrDurationMillis,
            descriptorDurationMillis = analyses.sumOf(CardTextAnalysis::descriptorDurationMillis),
        )
    }
}
