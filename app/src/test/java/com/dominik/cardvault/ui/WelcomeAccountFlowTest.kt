package com.dominik.cardvault.ui

import com.dominik.cardvault.auth.GoogleAccountState
import com.dominik.cardvault.auth.GoogleLoginResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WelcomeAccountFlowTest {
    @Test fun successfulGoogleLoginCompletesPresentationWithoutSecondConfirmation() = runBlocking {
        var signedIn = false
        var logins = 0
        var saves = 0
        val result = completeWelcomeAccountChoice(true, { signedIn }, {
            logins++; signedIn = true; GoogleLoginResult.Success(GoogleAccountState(true, "Synthetic"))
        }, { saves++; true })
        assertEquals(WelcomeAccountOutcome.COMPLETE, result)
        assertEquals(1, logins)
        assertEquals(1, saves)
    }

    @Test fun alreadyAuthenticatedSessionNeverOpensAChooserAgain() = runBlocking {
        val result = completeWelcomeAccountChoice(true, { true }, { throw AssertionError("No repeated chooser") }, { true })
        assertEquals(WelcomeAccountOutcome.COMPLETE, result)
    }

    @Test fun cancelledOrFailedLoginCannotMarkWelcomeAsFinished() = runBlocking {
        for (login in listOf(GoogleLoginResult.Cancelled, GoogleLoginResult.Failed(IllegalStateException("private detail")))) {
            val result = completeWelcomeAccountChoice(true, { false }, { login }, { throw AssertionError("Do not save") })
            assertEquals(if (login == GoogleLoginResult.Cancelled) WelcomeAccountOutcome.CANCELLED else WelcomeAccountOutcome.LOGIN_FAILED, result)
        }
    }

    @Test fun claimedSuccessWithoutCurrentAccountDoesNotCompletePresentation() = runBlocking {
        assertEquals(WelcomeAccountOutcome.LOGIN_FAILED, completeWelcomeAccountChoice(true, { false }, {
            GoogleLoginResult.Success(GoogleAccountState(true, "Synthetic"))
        }, { throw AssertionError("Account was lost") }))
    }

    @Test fun explicitLocalUseDoesNotCallAuthentication() = runBlocking {
        assertEquals(WelcomeAccountOutcome.COMPLETE, completeWelcomeAccountChoice(false, { false },
            { throw AssertionError("Local use is not Google consent") }, { true }))
    }

    @Test fun failedPreferenceCommitCanBeRetriedWithoutRepeatingLogin() = runBlocking {
        var signedIn = false
        var logins = 0
        val signIn: suspend () -> GoogleLoginResult = {
            logins++; signedIn = true; GoogleLoginResult.Success(GoogleAccountState(true, "Synthetic"))
        }
        assertEquals(WelcomeAccountOutcome.SAVE_FAILED, completeWelcomeAccountChoice(true, { signedIn }, signIn, { false }))
        assertEquals(WelcomeAccountOutcome.COMPLETE, completeWelcomeAccountChoice(true, { signedIn }, signIn, { true }))
        assertEquals(1, logins)
    }

    @Test fun lifecycleCancellationIsNotMisreportedAsLoginOrStorageFailure() = runBlocking {
        try {
            completeWelcomeAccountChoice(true, { false }, { throw CancellationException() }, { false })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        try {
            completeWelcomeAccountChoice(false, { false }, { throw AssertionError() }, { throw CancellationException() })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
