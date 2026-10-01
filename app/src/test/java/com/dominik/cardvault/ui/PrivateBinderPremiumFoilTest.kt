package com.dominik.cardvault.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateBinderPremiumFoilTest {
    @Test fun `expanded materials have disjoint routes and preserve the anniversary and classic effects`() {
        val all = PrivateBinderVisualEngine.holoEffects
        assertEquals(7, all.count { privateBinderUsesAdditionalFoil(it.id) })
        assertEquals(11, all.count { privateBinderUsesElementFoil(it.id) })
        all.forEach { effect ->
            val routes = listOf(privateBinderUsesPremiumFoil(effect.id), privateBinderUsesElementFoil(effect.id),
                privateBinderUsesAdditionalFoil(effect.id)).count { it }
            assertTrue(effect.id, routes <= 1)
            if (effect.id in listOf("none", "classic_holo", "stardust", "bubble", "pixel", "anniversary_30"))
                assertEquals(effect.id, 0, routes)
        }
        assertFalse(privateBinderUsesAdditionalFoil("unknown"))
        assertFalse(privateBinderUsesElementFoil("unknown"))
    }

    @Test fun `material detail is bounded for every quality and invalid budget`() {
        listOf(Int.MIN_VALUE, -1, 0, 3, 7, 10, Int.MAX_VALUE).forEach { value ->
            val budget = privateBinderPremiumFoilBudget(value)
            assertTrue(budget.bands in 3..5)
            assertTrue(budget.cloudSamples in 18..34)
            assertTrue(budget.facets in 6..12)
            assertTrue(budget.glints in 4..8)
            assertEquals(budget, privateBinderPremiumFoilBudget(value))
        }
    }

    @Test fun `only rainbow through diamond use the new material renderer`() {
        val wanted = listOf("rainbow", "linear", "diagonal", "galaxy", "nebula", "aurora", "crystal", "prism", "diamond")
        assertEquals(wanted, PrivateBinderVisualEngine.holoEffects
            .filter { privateBinderUsesPremiumFoil(it.id) }.map { it.id })
        listOf("none", "classic_holo", "anniversary_30", "element_psychic", "unknown", "RAINBOW", "")
            .forEach { assertFalse(it, privateBinderUsesPremiumFoil(it)) }
    }
}
