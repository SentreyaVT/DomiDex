package com.dominik.cardvault.auth

import com.google.firebase.auth.FirebaseUser

internal const val GOOGLE_FIREBASE_PROVIDER_ID = "google.com"
internal const val GITHUB_FIREBASE_PROVIDER_ID = "github.com"

/**
 * The collection has exactly one canonical identity: a Firebase user linked to Google.
 * GitHub is an optional update-download credential and can never create/switch collection
 * ownership. Existing GitHub-only users stay isolated and require an explicit future migration.
 */
internal fun canonicalCollectionOwnerUid(firebaseUid: String?, providerIds: Iterable<String>): String? =
    firebaseUid?.takeIf(String::isNotBlank)
        ?.takeIf { GOOGLE_FIREBASE_PROVIDER_ID in providerIds }

internal fun canonicalCollectionOwnerUid(user: FirebaseUser?): String? =
    canonicalCollectionOwnerUid(user?.uid, user?.providerData.orEmpty().map { it.providerId })

internal fun hasFirebaseProvider(user: FirebaseUser?, providerId: String): Boolean =
    user?.providerData?.any { it.providerId == providerId } == true
