package com.dominik.cardvault.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionConnectivityPolicyTest {
    @Test fun currentNetworkRequiresBothInternetAndValidationAndSuppressesDuplicates() {
        val network = CollectionDefaultNetworkPolicy()
        assertNull(network.capabilities(10, hasInternet = true, validated = true))
        assertNull(network.available(10))
        assertNull(network.capabilities(10, hasInternet = false, validated = false))
        assertNull(network.capabilities(10, hasInternet = true, validated = false))
        assertNull(network.capabilities(10, hasInternet = false, validated = true))
        assertEquals(true, network.capabilities(10, hasInternet = true, validated = true))
        assertNull(network.available(10))
        assertNull(network.capabilities(10, hasInternet = true, validated = true))
        assertEquals(false, network.capabilities(10, hasInternet = true, validated = false))
        assertNull(network.capabilities(10, hasInternet = true, validated = false))
        assertEquals(true, network.capabilities(10, hasInternet = true, validated = true))
    }

    @Test fun replacementInvalidatesOldCapabilitiesAndLateOldEventsCannotNegateNewNetwork() {
        val network = CollectionDefaultNetworkPolicy()
        assertNull(network.available(10))
        assertEquals(true, network.capabilities(10, hasInternet = true, validated = true))
        assertEquals(false, network.available(20))
        assertNull(network.capabilities(10, hasInternet = true, validated = true))
        assertNull(network.lost(10))
        assertEquals(true, network.capabilities(20, hasInternet = true, validated = true))
        assertNull(network.lost(10))
        assertNull(network.capabilities(10, hasInternet = false, validated = false))
        assertNull(network.capabilities(20, hasInternet = true, validated = true))
        assertEquals(false, network.lost(20))
        assertNull(network.lost(20))
        assertNull(network.capabilities(20, hasInternet = true, validated = true))
    }

    @Test fun sameNetworkCanBecomeAvailableAgainAfterLoss() {
        val network = CollectionDefaultNetworkPolicy()
        assertNull(network.available(10))
        assertEquals(true, network.capabilities(10, hasInternet = true, validated = true))
        assertEquals(false, network.lost(10))
        assertNull(network.available(10))
        assertEquals(true, network.capabilities(10, hasInternet = true, validated = true))
    }

    @Test fun independentRegistrationsShareNoConnectivityState() {
        val firstNetwork = CollectionDefaultNetworkPolicy()
        val secondNetwork = CollectionDefaultNetworkPolicy()
        assertNull(firstNetwork.available(10))
        assertEquals(true, firstNetwork.capabilities(10, hasInternet = true, validated = true))
        assertNull(secondNetwork.capabilities(10, hasInternet = true, validated = true))
        assertNull(secondNetwork.available(20))
        assertEquals(true, secondNetwork.capabilities(20, hasInternet = true, validated = true))
        assertEquals(false, firstNetwork.lost(10))
        assertNull(secondNetwork.capabilities(20, hasInternet = true, validated = true))
        assertEquals(false, secondNetwork.lost(20))
    }
}
