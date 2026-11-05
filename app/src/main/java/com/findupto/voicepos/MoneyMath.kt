package com.findupto.voicepos

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import java.util.Locale

/**
 * Currency-aware decimal arithmetic for POS totals.
 *
 * Values are kept as decimal numbers during calculations and rounded only at
 * currency boundaries. Invalid or negative financial inputs are rejected.
 */
object MoneyMath {
    fun fractionDigits(currencyCode: String): Int = runCatching {
        Currency.getInstance(currencyCode.uppercase(Locale.ROOT)).defaultFractionDigits
            .takeIf { it >= 0 } ?: 2
    }.getOrDefault(2).coerceIn(0, 3)

    fun decimal(value: Double): BigDecimal {
        require(value.isFinite()) { "Amount must be a finite number" }
        return BigDecimal.valueOf(value)
    }

    fun round(value: Double, currencyCode: String = "PKR"): Double =
        decimal(value).setScale(fractionDigits(currencyCode), RoundingMode.HALF_UP).toDouble()

    fun multiply(unitPrice: Double, quantity: Int, currencyCode: String = "PKR"): Double {
        require(unitPrice >= 0.0 && quantity >= 0) { "Price and quantity cannot be negative" }
        return decimal(unitPrice)
            .multiply(BigDecimal.valueOf(quantity.toLong()))
            .setScale(fractionDigits(currencyCode), RoundingMode.HALF_UP)
            .toDouble()
    }

    fun percentage(base: Double, rate: Double, currencyCode: String = "PKR"): Double {
        require(base >= 0.0 && rate >= 0.0) { "Base and percentage rate cannot be negative" }
        return decimal(base).multiply(decimal(rate))
            .divide(BigDecimal("100"), fractionDigits(currencyCode) + 4, RoundingMode.HALF_UP)
            .setScale(fractionDigits(currencyCode), RoundingMode.HALF_UP)
            .toDouble()
    }

    data class Breakdown(
        val subtotal: Double,
        val serviceCharge: Double,
        val tax: Double,
        val total: Double
    )

    fun calculate(
        subtotal: Double,
        serviceRate: Double,
        taxRate: Double,
        serviceEnabled: Boolean,
        taxEnabled: Boolean,
        currencyCode: String = "PKR"
    ): Breakdown {
        require(subtotal >= 0.0 && serviceRate >= 0.0 && taxRate >= 0.0) {
            "Subtotal and rates cannot be negative"
        }
        val digits = fractionDigits(currencyCode)
        val base = decimal(subtotal).setScale(digits, RoundingMode.HALF_UP)
        val service = if (serviceEnabled) {
            decimal(percentage(base.toDouble(), serviceRate, currencyCode))
        } else BigDecimal.ZERO.setScale(digits)
        val taxable = base.add(service)
        val tax = if (taxEnabled) {
            decimal(percentage(taxable.toDouble(), taxRate, currencyCode))
        } else BigDecimal.ZERO.setScale(digits)
        val total = base.add(service).add(tax).setScale(digits, RoundingMode.HALF_UP)
        return Breakdown(base.toDouble(), service.toDouble(), tax.toDouble(), total.toDouble())
    }
}
