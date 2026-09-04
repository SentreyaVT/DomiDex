package com.dominik.cardvault.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseEntitlementsTest {
    @Test fun claimsAreStrictBooleans() {
        val entitlements = firebaseEntitlementsFromClaims(
            mapOf("admin" to "true", "pretester" to 1, "appAccess" to true),
        )

        assertFalse(entitlements.admin)
        assertFalse(entitlements.pretester)
        assertTrue(entitlements.appAccess)
    }

    @Test fun adminInheritsAppAndPretesterAccess() {
        val entitlements = firebaseEntitlementsFromClaims(mapOf("admin" to true))

        assertTrue(entitlements.canUseAppBackend)
        assertTrue(entitlements.canUsePretesterBackend)
    }

    @Test fun pretesterAloneDoesNotUnlockAppBackend() {
        val entitlements = firebaseEntitlementsFromClaims(mapOf("pretester" to true))

        assertFalse(entitlements.canUseAppBackend)
        assertTrue(entitlements.canUsePretesterBackend)
    }

    @Test fun backendAccessIsBoundToTheCurrentUid() {
        val appUser = FirebaseEntitlements(appAccess = true)

        assertTrue(canUseFirebaseAppBackend("uid-a", "uid-a", appUser))
        assertFalse(canUseFirebaseAppBackend("uid-a", "uid-b", appUser))
        assertFalse(canUseFirebaseAppBackend(null, "uid-a", appUser))
        assertFalse(canUseFirebaseAppBackend("uid-a", "uid-a", FirebaseEntitlements()))
    }
}
