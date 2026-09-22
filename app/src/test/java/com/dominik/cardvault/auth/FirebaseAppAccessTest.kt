package com.dominik.cardvault.auth

import org.junit.Assert.*
import org.junit.Test

class FirebaseAppAccessTest {
    private val all = FirebaseEntitlements(admin = true, appAccess = true, pretester = true)
    @Test fun absentOverridePreservesClaims() { assertEquals(all, effectiveFirebaseEntitlements(all)) }
    @Test fun overrideCannotGrantClaim() {
        assertEquals(FirebaseEntitlements(), effectiveFirebaseEntitlements(FirebaseEntitlements(), mapOf("admin" to true, "appAccess" to true)))
    }
    @Test fun falseRevokesMatchingRoleNotOtherRoles() {
        assertEquals(all.copy(appAccess = false), effectiveFirebaseEntitlements(all, mapOf("appAccess" to false)))
        assertTrue(effectiveFirebaseEntitlements(all, mapOf("appAccess" to false)).canUseAppBackend)
    }
    @Test fun disabledRevokesAllIncludingAdmin() {
        assertEquals(FirebaseEntitlements(), effectiveFirebaseEntitlements(all, mapOf("disabled" to true)))
    }
    @Test fun malformedOverridesFailClosed() {
        listOf(null, "true", 1).forEach { malformed ->
            assertEquals(FirebaseEntitlements(), effectiveFirebaseEntitlements(all, mapOf("admin" to malformed, "appAccess" to malformed, "pretester" to malformed)))
        }
    }
    @Test fun pretesterAloneDoesNotGrantCollectionCloud() {
        assertFalse(effectiveFirebaseEntitlements(FirebaseEntitlements(pretester = true)).canUseAppBackend)
    }
}
