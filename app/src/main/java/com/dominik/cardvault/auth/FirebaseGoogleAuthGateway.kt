package com.dominik.cardvault.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.dominik.cardvault.R
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class GoogleAccountState(val isSignedIn: Boolean, val displayName: String?)

sealed interface GoogleLoginResult {
    data class Success(val account: GoogleAccountState) : GoogleLoginResult
    data object Cancelled : GoogleLoginResult
    data class Failed(val cause: Throwable) : GoogleLoginResult
}

/** Google login only. It deliberately does not derive support, admin, or Pro entitlements. */
class FirebaseGoogleAuthGateway(
    private val context: Context,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    fun currentAccount(): GoogleAccountState = auth.currentUser?.let {
        GoogleAccountState(true, it.displayName?.takeIf(String::isNotBlank))
    } ?: GoogleAccountState(false, null)

    suspend fun signIn(): GoogleLoginResult {
        return try {
            val googleOption = GetGoogleIdOption.Builder()
                .setServerClientId(context.getString(R.string.default_web_client_id))
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(false)
                .build()
            val request = GetCredentialRequest.Builder().addCredentialOption(googleOption).build()
            val credential = CredentialManager.create(context).getCredential(context, request).credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return GoogleLoginResult.Failed(IllegalStateException("Unsupported credential type"))
            }
            val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
            auth.signInWithCredential(GoogleAuthProvider.getCredential(googleCredential.idToken, null)).awaitResult()
            GoogleLoginResult.Success(currentAccount())
        } catch (_: GetCredentialCancellationException) {
            GoogleLoginResult.Cancelled
        } catch (error: Throwable) {
            GoogleLoginResult.Failed(error)
        }
    }

    fun signOut() {
        auth.signOut()
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resumeWith(Result.success(task.result))
        else continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase task failed"))
    }
}
