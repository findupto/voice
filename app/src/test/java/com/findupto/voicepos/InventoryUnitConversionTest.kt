package com.findupto.voicepos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
