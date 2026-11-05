package com.findupto.voicepos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodPairingEngineTest {
    private val menu = listOf(
        MenuItem(1, "Chicken Burger", 450.0),
        MenuItem(2, "Regular Fries", 180.0),
        MenuItem(3, "Cola", 100.0),
        MenuItem(4, "Brownie", 220.0),
        MenuItem(5, "Garlic Mayo Dip", 60.0)
    )

    @Test fun mainSuggestsDrinkAndSide() {
        val suggestions = FoodPairingEngine.suggest(menu, listOf(SaleItem("Chicken Burger", 1, 450.0)))
        assertTrue(suggestions.any { it.item.name == "Cola" })
        assertTrue(suggestions.any { it.item.name == "Regular Fries" })
    }

    @Test fun doesNotRecommendProductsAlreadyInCart() {
        val suggestions = FoodPairingEngine.suggest(menu, listOf(
            SaleItem("Chicken Burger", 1, 450.0),
            SaleItem("Cola", 1, 100.0)
        ))
        assertTrue(suggestions.none { canonicalProductKey(it.item.name) == canonicalProductKey("Cola") })
    }

    @Test fun emptyCartHasNoUpsellSuggestions() {
        assertEquals(emptyList<FoodPairingEngine.Suggestion>(), FoodPairingEngine.suggest(menu, emptyList()))
    }
}
