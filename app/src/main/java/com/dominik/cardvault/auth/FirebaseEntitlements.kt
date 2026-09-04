package com.dominik.cardvault.auth

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Server-owned roles carried by the Firebase ID token. The Android client never grants them. */
internal data class FirebaseEntitlements(
    val admin: Boolean = false,
    val pretester: Boolean = false,
    val appAccess: Boolean = false,
) {
    val canUseAppBackend: Boolean get() = appAccess || admin
    val canUsePretesterBackend: Boolean get() = pretester || admin
}

internal data class FirebaseEntitlementAccount(
    val uid: String,
    val entitlements: FirebaseEntitlements,
)

/** Mirrors the strict `claim == true` checks in the Web app and Firebase Rules. */
internal fun firebaseEntitlementsFromClaims(claims: Map<String, *>): FirebaseEntitlements =
    FirebaseEntitlements(
        admin = claims["admin"] == true,
        pretester = claims["pretester"] == true,
        appAccess = claims["appAccess"] == true,
    )

internal fun canUseFirebaseAppBackend(
    expectedUid: String?,
    currentUid: String?,
    entitlements: FirebaseEntitlements,
): Boolean = !expectedUid.isNullOrBlank() && expectedUid == currentUid && entitlements.canUseAppBackend

/** Reads the claims from Firebase's verified ID-token result, optionally forcing a server refresh. */
internal suspend fun FirebaseUser.firebaseEntitlementAccount(forceRefresh: Boolean = false): FirebaseEntitlementAccount {
    val token = getIdToken(forceRefresh).awaitFirebaseAuthTask()
    return FirebaseEntitlementAccount(uid, firebaseEntitlementsFromClaims(token.claims))
}

/** Returns null when signed out or when the account changes while its token is being resolved. */
internal suspend fun FirebaseAuth.currentFirebaseEntitlementAccount(
    forceRefresh: Boolean = false,
): FirebaseEntitlementAccount? {
    val user = currentUser ?: return null
    val account = user.firebaseEntitlementAccount(forceRefresh)
    return account.takeIf { currentUser?.uid == user.uid }
}

private suspend fun <T> Task<T>.awaitFirebaseAuthTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resumeWith(Result.success(task.result))
        else continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase Auth task failed"))
    }
}
