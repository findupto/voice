package com.findupto.voicepos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyMathTest {
    @Test fun roundsHalfUpForPkr() {
        assertEquals(10.13, MoneyMath.round(10.125, "PKR"), 0.000001)
    }

    @Test fun roundsAccordingToCurrencyPrecision() {
        assertEquals(10.0, MoneyMath.round(10.49, "JPY"), 0.000001)
        assertEquals(10.125, MoneyMath.round(10.1246, "KWD"), 0.000001)
    }

    @Test fun calculatesServiceBeforeTax() {
        val result = MoneyMath.calculate(
            subtotal = 1000.0,
            serviceRate = 10.0,
            taxRate = 5.0,
            serviceEnabled = true,
            taxEnabled = true,
            currencyCode = "PKR"
        )
        assertEquals(1000.0, result.subtotal, 0.000001)
        assertEquals(100.0, result.serviceCharge, 0.000001)
        assertEquals(55.0, result.tax, 0.000001)
        assertEquals(1155.0, result.total, 0.000001)
    }

    @Test fun preservesNegativeCartDiscountLines() {
        CurrencySettings.code = "PKR"
        assertEquals(-50.0, SaleItem(CART_DISCOUNT_PREFIX, 1, -50.0).total, 0.000001)
    }

    @Test fun rejectsNonFiniteAmounts() {
        assertThrows(IllegalArgumentException::class.java) {
            MoneyMath.round(Double.NaN, "PKR")
        }
    }
}
