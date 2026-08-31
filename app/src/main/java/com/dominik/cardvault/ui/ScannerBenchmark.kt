package com.dominik.cardvault.ui

/** Developer-only evaluation model. It contains catalogue ids and scores, never account data. */
internal data class ScannerBenchmarkSample(
    val expectedCardKey: String,
    val predictedCardKeys: List<String>,
    val decision: ScannerDecision,
    val processingMillis: Long,
)

internal data class ScannerBenchmarkReport(
    val sampleCount: Int,
    val top1Accuracy: Float,
    val top3Accuracy: Float,
    val unresolvedRate: Float,
    val falsePositiveRate: Float,
    val averageProcessingMillis: Long,
)

internal object ScannerBenchmarkCalculator {
    fun calculate(samples: List<ScannerBenchmarkSample>): ScannerBenchmarkReport {
        if (samples.isEmpty()) return ScannerBenchmarkReport(0, 0f, 0f, 0f, 0f, 0L)
        val top1 = samples.count { it.predictedCardKeys.firstOrNull() == it.expectedCardKey }
        val top3 = samples.count { it.expectedCardKey in it.predictedCardKeys.take(3) }
        val unresolved = samples.count { it.decision == ScannerDecision.REJECTED }
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
            averageProcessingMillis = samples.sumOf(ScannerBenchmarkSample::processingMillis) / samples.size,
        )
    }
}
