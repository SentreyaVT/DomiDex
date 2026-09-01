package com.dominik.cardvault.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintedDenominatorGuardTest {
    @Test fun `printed denominator blocks a contradictory source set`() {
        assertTrue(printedDenominatorMatches("117/084", 84))
        assertFalse(printedDenominatorMatches("117/084", 86))
        assertFalse(printedDenominatorMatches("117/084", 0))
    }

    @Test fun `promo identity without denominator is not falsely rejected`() {
        assertTrue(printedDenominatorMatches("SVP173", 0))
        assertTrue(printedDenominatorMatches("TG01/TG30", 172))
    }
}
