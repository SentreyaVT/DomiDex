package com.dominik.cardvault.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalCollectionOwnerTest {
    @Test fun googleLinkedFirebaseUserIsCanonicalCollectionOwner() {
        assertEquals("uid-google", canonicalCollectionOwnerUid("uid-google", listOf("password", GOOGLE_FIREBASE_PROVIDER_ID)))
    }

    @Test fun githubOnlyFirebaseUserNeverBecomesCollectionOwner() {
        assertNull(canonicalCollectionOwnerUid("uid-github", listOf(GITHUB_FIREBASE_PROVIDER_ID)))
        assertNull(canonicalCollectionOwnerUid("uid-anonymous", emptyList()))
        assertNull(canonicalCollectionOwnerUid(null, listOf(GOOGLE_FIREBASE_PROVIDER_ID)))
    }
}
