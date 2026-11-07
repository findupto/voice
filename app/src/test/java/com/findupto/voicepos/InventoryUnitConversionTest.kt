package com.findupto.voicepos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryUnitConversionTest {
    @Test fun convertsGramsToKilograms() {
        assertEquals(2.5, quantityInStockUnit(2500.0, "g", "kg")!!, 0.000001)
    }

    @Test fun convertsKilogramsToGrams() {
        assertEquals(2000.0, quantityInStockUnit(2.0, "kg", "g")!!, 0.000001)
    }

    @Test fun convertsMillilitresToLitres() {
        assertEquals(0.75, quantityInStockUnit(750.0, "ml", "l")!!, 0.000001)
    }

    @Test fun convertsLitresToMillilitres() {
        assertEquals(1500.0, quantityInStockUnit(1.5, "litre", "ml")!!, 0.000001)
    }

    @Test fun acceptsEquivalentUnitsIgnoringCaseAndWhitespace() {
        assertEquals(3.0, quantityInStockUnit(3.0, " KG ", "kg")!!, 0.000001)
    }

    @Test fun rejectsIncompatibleUnits() {
        assertNull(quantityInStockUnit(2.0, "kg", "pcs"))
    }
    @Test fun suggestsTopUpToTwiceMinimum() {
        assertEquals(7.0, suggestedReorderQuantity(3.0, 5.0), 0.000001)
    }

    @Test fun noSuggestionWhenStockIsAboveMinimum() {
        assertEquals(0.0, suggestedReorderQuantity(12.0, 5.0), 0.000001)
    }

    @Test fun aggregatesRecipeRequirementsAcrossSoldProducts() {
        val stock = listOf(InventoryStockItem(1L, "Flour", "Dry", 5.0, "kg", 1.0, 10.0))
        val recipes = listOf(
            ProductRecipe("Bread", listOf(RecipeComponent("Flour", 100.0, "g"))),
            ProductRecipe("Roll", listOf(RecipeComponent("Flour", 200.0, "g")))
        )
        val plan = saleStockRequirementPlan(
            listOf(SaleItem("Bread", 2, 10.0), SaleItem("Roll", 1, 15.0)), recipes, stock
        )
        assertEquals(0.4, plan.requiredByStockId[1L]!!, 0.000001)
        assertTrue(plan.issues.isEmpty())
    }

    @Test fun reportsMissingRecipeStockInsteadOfSilentlySkippingIt() {
        val plan = saleStockRequirementPlan(
            listOf(SaleItem("Bread", 1, 10.0)),
            listOf(ProductRecipe("Bread", listOf(RecipeComponent("Butter", 20.0, "g")))), emptyList()
        )
        assertTrue(plan.issues.any { it.contains("Butter") && it.contains("not found") })
    }

    @Test fun reportsIncompatibleRecipeUnits() {
        val stock = listOf(InventoryStockItem(1L, "Butter", "Dairy", 2.0, "pcs", 1.0, 10.0))
        val plan = saleStockRequirementPlan(
            listOf(SaleItem("Bread", 1, 10.0)),
            listOf(ProductRecipe("Bread", listOf(RecipeComponent("Butter", 20.0, "g")))), stock
        )
        assertTrue(plan.issues.any { it.contains("cannot be converted") })
    }

    @Test fun rejectsInvalidReorderInputs() {
        assertEquals(0.0, suggestedReorderQuantity(Double.NaN, 5.0), 0.000001)
        assertEquals(0.0, suggestedReorderQuantity(1.0, 0.0), 0.000001)
    }

}
