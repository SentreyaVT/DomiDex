package com.dominik.cardvault.ui

import com.dominik.cardvault.auth.GoogleLoginResult
import kotlinx.coroutines.CancellationException

internal enum class WelcomeAccountOutcome { COMPLETE, CANCELLED, LOGIN_FAILED, SAVE_FAILED }

/** Presentation completion only; session adoption and upload consents stay in their existing flows. */
internal suspend fun completeWelcomeAccountChoice(
    useGoogle: Boolean,
    hasSignedInAccount: () -> Boolean,
    signIn: suspend () -> GoogleLoginResult,
    completePresentation: suspend () -> Boolean,
): WelcomeAccountOutcome {
    if (useGoogle && !hasSignedInAccount()) {
        val result = try { signIn() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return WelcomeAccountOutcome.LOGIN_FAILED }
        when (result) {
            GoogleLoginResult.Cancelled -> return WelcomeAccountOutcome.CANCELLED
            is GoogleLoginResult.Failed -> return WelcomeAccountOutcome.LOGIN_FAILED
            is GoogleLoginResult.Success -> if (!hasSignedInAccount()) return WelcomeAccountOutcome.LOGIN_FAILED
        }
    }
    return try {
        if (completePresentation()) WelcomeAccountOutcome.COMPLETE else WelcomeAccountOutcome.SAVE_FAILED
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { WelcomeAccountOutcome.SAVE_FAILED }
}
