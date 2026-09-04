package com.dominik.cardvault.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAccountStateTest {
    @Test fun googleProfileTakesPrecedenceOverTheCanonicalProfile() {
        val account = googleAccountState(
            hasGoogleProvider = true,
            providerDisplayName = "Google tester",
            providerEmail = "google@example.com",
            accountDisplayName = "Another linked profile",
            accountEmail = "linked@example.com",
        )

        assertTrue(account.isSignedIn)
        assertEquals("Google tester", account.displayName)
        assertEquals("google@example.com", account.email)
    }

    @Test fun blankGoogleFieldsUseAvailableFirebaseAccountDetails() {
        val account = googleAccountState(
            hasGoogleProvider = true,
            providerDisplayName = " ",
            providerEmail = "\t",
            accountDisplayName = " Tester ",
            accountEmail = " tester@example.com ",
        )

        assertEquals("Tester", account.displayName)
        assertEquals("tester@example.com", account.email)
    }

    @Test fun absentProfileFieldsRemainAbsentForTheUiToHandle() {
        val account = googleAccountState(
            hasGoogleProvider = true,
            providerDisplayName = " ",
            accountEmail = " ",
        )

        assertTrue(account.isSignedIn)
        assertNull(account.displayName)
        assertNull(account.email)
    }

    @Test fun nonGoogleOrSignedOutSessionsDiscardAllAccountDetails() {
        val account = googleAccountState(
            hasGoogleProvider = false,
            providerDisplayName = "Previous tester",
            providerEmail = "previous@example.com",
            accountDisplayName = "Other provider",
            accountEmail = "other@example.com",
        )

        assertFalse(account.isSignedIn)
        assertNull(account.displayName)
        assertNull(account.email)
    }

    @Test fun accountChangesCannotReuseThePreviousAccountsMissingFields() {
        val beforeSignOut = googleAccountState(
            hasGoogleProvider = true,
            providerDisplayName = "First tester",
            providerEmail = "first@example.com",
        )
        val signedOut = googleAccountState(hasGoogleProvider = false)
        val afterAnotherSignIn = googleAccountState(
            hasGoogleProvider = true,
            providerEmail = "second@example.com",
        )

        assertEquals("first@example.com", beforeSignOut.email)
        assertFalse(signedOut.isSignedIn)
        assertNull(signedOut.displayName)
        assertNull(signedOut.email)
        assertTrue(afterAnotherSignIn.isSignedIn)
        assertNull(afterAnotherSignIn.displayName)
        assertEquals("second@example.com", afterAnotherSignIn.email)
    }
}
