package com.dominik.cardvault.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resumeWithException

/** Mirrors accountAccess overrides in Firestore/Storage rules; overrides can revoke, never grant. */
internal fun effectiveFirebaseEntitlements(
    claims: FirebaseEntitlements,
    serverAccess: Map<String, *> = emptyMap<String, Any>(),
): FirebaseEntitlements {
    if (serverAccess["disabled"] == true) return FirebaseEntitlements()
    fun allowed(role: String) = !serverAccess.containsKey(role) || serverAccess[role] == true
    return FirebaseEntitlements(
        admin = claims.admin && allowed("admin"),
        appAccess = claims.appAccess && allowed("appAccess"),
        pretester = claims.pretester && allowed("pretester"),
    )
}

/** Cached tokens alone cannot re-enable access revoked by the server. Offline stays local. */
internal suspend fun FirebaseAuth.currentVerifiedFirebaseEntitlementAccount(
    firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    forceRefresh: Boolean = false,
): FirebaseEntitlementAccount? = withTimeoutOrNull(10_000L) {
    val account = currentFirebaseEntitlementAccount(forceRefresh) ?: return@withTimeoutOrNull VerifiedAccount(null)
    if (!account.entitlements.canUseAppBackend && !account.entitlements.canUsePretesterBackend) return@withTimeoutOrNull VerifiedAccount(account)
    val access = suspendCancellableCoroutine { continuation ->
        firestore.collection("accountAccess").document(account.uid).get(Source.SERVER).addOnCompleteListener { task ->
            if (continuation.isActive) {
                if (task.isSuccessful) continuation.resumeWith(Result.success(task.result.data.orEmpty()))
                else continuation.resumeWithException(task.exception ?: IllegalStateException("Kontofreigabe konnte nicht geprüft werden."))
            }
        }
    }
    VerifiedAccount(account.copy(entitlements = effectiveFirebaseEntitlements(account.entitlements, access))
        .takeIf { currentUser?.uid == account.uid })
}?.account ?: if (currentUser == null) null else throw java.io.IOException("Kontofreigabe konnte nicht rechtzeitig geprüft werden.")

private data class VerifiedAccount(val account: FirebaseEntitlementAccount?)

/** Repository boundary for the explicit, irreversible guest-to-account ownership transfer. */
internal suspend fun requireVerifiedCollectionOwnerForClaim(
    auth: FirebaseAuth = FirebaseAuth.getInstance(),
    firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
): String {
    val account = auth.currentVerifiedFirebaseEntitlementAccount(firestore, forceRefresh = true)
    require(account != null && canUseFirebaseAppBackend(account.uid, canonicalCollectionOwnerUid(auth.currentUser), account.entitlements)) {
        "Die Kontofreigabe muss online bestätigt sein, bevor du deinen lokalen Bestand übernimmst."
    }
    return account.uid
}
