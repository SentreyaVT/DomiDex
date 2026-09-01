package com.dominik.cardvault.auth

import android.app.Activity
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.OAuthProvider
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class GitHubAccountState(
    val isSignedIn: Boolean,
    val displayName: String?,
    val providerUserId: String?,
)

sealed interface GitHubLoginResult {
    data class Success(val account: GitHubAccountState) : GitHubLoginResult
    data object Cancelled : GitHubLoginResult
    data class Failed(val safeMessage: String) : GitHubLoginResult
}

/**
 * Firebase owns the GitHub OAuth client secret and retains the resulting user
 * session. This gateway intentionally requests no `repo` scope and never
 * exposes an OAuth access token to the app UI.
 */
class FirebaseGitHubAuthGateway(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    fun currentAccount(): GitHubAccountState {
        val canonicalUser = auth.currentUser ?: return GitHubAccountState(false, null, null)
        if (canonicalCollectionOwnerUid(canonicalUser) == null) {
            return GitHubAccountState(false, null, null)
        }
        val github = canonicalUser.providerData.firstOrNull { it.providerId == GITHUB_PROVIDER_ID }
        return GitHubAccountState(
            isSignedIn = github != null,
            displayName = github?.displayName?.takeIf(String::isNotBlank),
            providerUserId = github?.uid,
        )
    }

    suspend fun signIn(activity: Activity): GitHubLoginResult {
        return try {
            val canonicalUser = auth.currentUser
                ?: return GitHubLoginResult.Failed("Bitte zuerst mit deinem Google-Konto anmelden. GitHub wird nur mit diesem bestehenden DomiDex-Konto verknüpft.")
            if (canonicalCollectionOwnerUid(canonicalUser) == null) {
                return GitHubLoginResult.Failed("Bitte zuerst mit deinem Google-Konto anmelden. GitHub wird nur mit diesem bestehenden DomiDex-Konto verknüpft.")
            }
            currentAccount().takeIf { it.isSignedIn }?.let { return GitHubLoginResult.Success(it) }
            val provider = OAuthProvider.newBuilder(GITHUB_PROVIDER_ID)
                .addCustomParameter("allow_signup", "false")
                .build()
            // Never call signInWithProvider here: that could replace the canonical collection
            // UID with a GitHub-only Firebase user. GitHub is linked to Google or not used.
            val result = canonicalUser.startActivityForLinkWithProvider(activity, provider).awaitResult()
            val linkedUser = result.user
                ?: return GitHubLoginResult.Failed("GitHub-Anmeldung konnte nicht abgeschlossen werden.")
            if (linkedUser.uid != canonicalUser.uid || canonicalCollectionOwnerUid(linkedUser) != canonicalUser.uid) {
                return GitHubLoginResult.Failed("GitHub konnte nicht sicher mit dem bestehenden DomiDex-Konto verknüpft werden.")
            }
            GitHubLoginResult.Success(currentAccount())
        } catch (error: Throwable) {
            if (error.message?.contains("cancel", ignoreCase = true) == true) GitHubLoginResult.Cancelled
            else GitHubLoginResult.Failed("GitHub-Anmeldung konnte nicht abgeschlossen werden.")
        }
    }

    private companion object { const val GITHUB_PROVIDER_ID = GITHUB_FIREBASE_PROVIDER_ID }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resumeWith(Result.success(task.result))
        else continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase task failed"))
    }
}
