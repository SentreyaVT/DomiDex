package com.dominik.cardvault.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ScannerBenchmarkCalculatorTest {
    @Test fun `report separates honest unresolved cases from false positives`() {
        val report = ScannerBenchmarkCalculator.calculate(listOf(
            ScannerBenchmarkSample("A", listOf("A", "B"), ScannerDecision.ACCEPTED, 100),
            ScannerBenchmarkSample("B", listOf("A", "B"), ScannerDecision.AMBIGUOUS, 200),
            ScannerBenchmarkSample("C", emptyList(), ScannerDecision.REJECTED, 300),
            ScannerBenchmarkSample("D", listOf("X"), ScannerDecision.ACCEPTED, 400),
        ))
        assertEquals(.25f, report.top1Accuracy, .001f)
        assertEquals(.50f, report.top3Accuracy, .001f)
        assertEquals(.25f, report.unresolvedRate, .001f)
        assertEquals(.25f, report.falsePositiveRate, .001f)
        assertEquals(250L, report.averageProcessingMillis)
    }
}
