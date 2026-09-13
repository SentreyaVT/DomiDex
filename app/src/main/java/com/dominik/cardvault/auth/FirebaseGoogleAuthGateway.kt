package com.dominik.cardvault.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.dominik.cardvault.R
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

data class GoogleAccountState(
    val isSignedIn: Boolean,
    val displayName: String?,
    val email: String? = null,
)

/** A fresh display snapshot; signed-out or non-Google sessions expose no account details. */
internal fun googleAccountState(
    hasGoogleProvider: Boolean,
    providerDisplayName: String? = null,
    providerEmail: String? = null,
    accountDisplayName: String? = null,
    accountEmail: String? = null,
): GoogleAccountState {
    if (!hasGoogleProvider) return GoogleAccountState(false, null)
    return GoogleAccountState(
        isSignedIn = true,
        displayName = providerDisplayName?.trim()?.takeIf(String::isNotEmpty)
            ?: accountDisplayName?.trim()?.takeIf(String::isNotEmpty),
        email = providerEmail?.trim()?.takeIf(String::isNotEmpty)
            ?: accountEmail?.trim()?.takeIf(String::isNotEmpty),
    )
}

sealed interface GoogleLoginResult {
    data class Success(val account: GoogleAccountState) : GoogleLoginResult
    data object Cancelled : GoogleLoginResult
    data class Failed(val cause: Throwable) : GoogleLoginResult
}

sealed interface GoogleLogoutResult {
    data object Success : GoogleLogoutResult
    data class CredentialStateWarning(val cause: Throwable) : GoogleLogoutResult
}

/** Google login only. Server claims are refreshed after login but are never granted by Android. */
class FirebaseGoogleAuthGateway(
    private val context: Context,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    private val credentialManager = CredentialManager.create(context)

    fun currentAccount(): GoogleAccountState {
        val user = auth.currentUser
        val google = user?.providerData?.firstOrNull { it.providerId == GOOGLE_FIREBASE_PROVIDER_ID }
        return googleAccountState(
            hasGoogleProvider = google != null,
            providerDisplayName = google?.displayName,
            providerEmail = google?.email,
            accountDisplayName = user?.displayName,
            accountEmail = user?.email,
        )
    }

    suspend fun signIn(): GoogleLoginResult {
        return try {
            // This flow is always started by an explicit "Mit Google anmelden" action.
            // The dedicated option reliably opens Google's account chooser instead of
            // reusing a stale Credential Manager suggestion from an earlier session.
            val googleOption = GetSignInWithGoogleOption.Builder(
                context.getString(R.string.default_web_client_id),
            )
                .build()
            val request = GetCredentialRequest.Builder().addCredentialOption(googleOption).build()
            val credential = credentialManager.getCredential(context, request).credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return GoogleLoginResult.Failed(IllegalStateException("Unsupported credential type"))
            }
            val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val firebaseUser = auth.signInWithCredential(
                GoogleAuthProvider.getCredential(googleCredential.idToken, null),
            ).awaitResult().user ?: error("Firebase sign-in returned no user")
            // A newly granted tester/app role must be visible before any gated sync is started.
            // Authentication itself remains successful if this best-effort refresh is briefly
            // offline; every backend gateway still fails closed until it can resolve a token.
            runCatching { firebaseUser.firebaseEntitlementAccount(forceRefresh = true) }
            GoogleLoginResult.Success(currentAccount())
        } catch (_: GetCredentialCancellationException) {
            GoogleLoginResult.Cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            GoogleLoginResult.Failed(error)
        }
    }

    suspend fun signOut(): GoogleLogoutResult {
        auth.signOut()
        return try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            GoogleLogoutResult.Success
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Firebase is already signed out. Surface only that Google's cached chooser
            // state could not be reset, so the local collection never stays attached.
            GoogleLogoutResult.CredentialStateWarning(error)
        }
    }

    suspend fun switchAccount(): GoogleLoginResult {
        return try {
            // Keep the current Firebase session until the user actually selects a new
            // Google account. Cancelling the chooser must not sign the user out.
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            signIn()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            GoogleLoginResult.Failed(error)
        }
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resumeWith(Result.success(task.result))
        else continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase task failed"))
    }
}
