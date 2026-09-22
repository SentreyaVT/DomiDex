package com.dominik.cardvault.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomePolicyTest {
    @Test fun freshInstallationOffersWelcome() { assertTrue(needsDomiDexWelcome(false, false)) }
    @Test fun previousArrivalNeverReopensWelcome() { assertFalse(needsDomiDexWelcome(true, false)) }
    @Test fun previousTutorialChoiceNeverReopensWelcome() { assertFalse(needsDomiDexWelcome(false, true)) }
    @Test fun completedWelcomeNeverReopensWelcome() { assertFalse(needsDomiDexWelcome(true, true)) }
}
