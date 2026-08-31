package com.dominik.cardvault.ui

/** Developer-only evaluation model. It contains catalogue ids and scores, never account data. */
internal enum class ScannerCardClass { NORMAL, EX, FULL_ART, HOLO, REVERSE_HOLO, TRAINER_SPECIAL, UNKNOWN }

internal data class ScannerBenchmarkSample(
    val expectedCardKey: String,
    val predictedCardKeys: List<String>,
    val decision: ScannerDecision,
    val processingMillis: Long,
    val cardClass: ScannerCardClass = ScannerCardClass.NORMAL,
)

internal data class ScannerBenchmarkReport(
    val sampleCount: Int,
    val top1Accuracy: Float,
    val top3Accuracy: Float,
    val unresolvedRate: Float,
    val falsePositiveRate: Float,
    val reviewRate: Float,
    val unknownRate: Float,
    val averageProcessingMillis: Long,
    val byCardClass: Map<ScannerCardClass, ScannerBenchmarkClassReport> = emptyMap(),
)

internal data class ScannerBenchmarkClassReport(
    val sampleCount: Int,
    val top1Accuracy: Float,
    val top3Accuracy: Float,
    val falsePositiveRate: Float,
    val reviewRate: Float,
    val unknownRate: Float,
    val averageProcessingMillis: Long,
)

internal object ScannerBenchmarkCalculator {
    fun calculate(samples: List<ScannerBenchmarkSample>): ScannerBenchmarkReport {
        if (samples.isEmpty()) return ScannerBenchmarkReport(0, 0f, 0f, 0f, 0f, 0f, 0f, 0L)
        val top1 = samples.count { it.predictedCardKeys.firstOrNull() == it.expectedCardKey }
        val top3 = samples.count { it.expectedCardKey in it.predictedCardKeys.take(3) }
        val unresolved = samples.count { it.decision == ScannerDecision.REJECTED }
        val review = samples.count { it.decision == ScannerDecision.AMBIGUOUS }
        // Only a confidently accepted wrong identity is a false positive. Honest review results
        // are measured separately and must never be counted as fake accuracy.
        val falsePositive = samples.count {
            it.decision == ScannerDecision.ACCEPTED && it.predictedCardKeys.firstOrNull() != it.expectedCardKey
        }
        return ScannerBenchmarkReport(
            sampleCount = samples.size,
            top1Accuracy = top1.toFloat() / samples.size,
            top3Accuracy = top3.toFloat() / samples.size,
            unresolvedRate = unresolved.toFloat() / samples.size,
            falsePositiveRate = falsePositive.toFloat() / samples.size,
            reviewRate = review.toFloat() / samples.size,
            unknownRate = unresolved.toFloat() / samples.size,
            averageProcessingMillis = samples.sumOf(ScannerBenchmarkSample::processingMillis) / samples.size,
            byCardClass = samples.groupBy(ScannerBenchmarkSample::cardClass).mapValues { (_, classSamples) ->
                val classTop1 = classSamples.count { it.predictedCardKeys.firstOrNull() == it.expectedCardKey }
                val classTop3 = classSamples.count { it.expectedCardKey in it.predictedCardKeys.take(3) }
                val classFalsePositive = classSamples.count { it.decision == ScannerDecision.ACCEPTED && it.predictedCardKeys.firstOrNull() != it.expectedCardKey }
                val classReview = classSamples.count { it.decision == ScannerDecision.AMBIGUOUS }
                val classUnknown = classSamples.count { it.decision == ScannerDecision.REJECTED }
                ScannerBenchmarkClassReport(
                    sampleCount = classSamples.size,
                    top1Accuracy = classTop1.toFloat() / classSamples.size,
                    top3Accuracy = classTop3.toFloat() / classSamples.size,
                    falsePositiveRate = classFalsePositive.toFloat() / classSamples.size,
                    reviewRate = classReview.toFloat() / classSamples.size,
                    unknownRate = classUnknown.toFloat() / classSamples.size,
                    averageProcessingMillis = classSamples.sumOf(ScannerBenchmarkSample::processingMillis) / classSamples.size,
                )
            },
        )
    }
}
