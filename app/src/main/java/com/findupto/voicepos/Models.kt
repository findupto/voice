package com.findupto.voicepos

data class MenuItem(val id: Long, val name: String, val price: Double, val variant: String = "", val size: String = "")
data class SaleItem(val name: String, val qty: Int, val price: Double) { val total: Double get() = qty * price }
data class Sale(
    val id: Long, val items: List<SaleItem>, val total: Double, val time: Long,
    val customerName: String = "", val customerPhone: String = "", val orderType: String = "DINE_IN", val customerAddress: String = "",
    val paymentMethod: String = "CASH", val paidAmount: Double = total, val dueAmount: Double = 0.0, val shiftId: Long = 0L,
    val serviceCharge: Double = 0.0, val tax: Double = 0.0
)
data class PendingSale(val id: Long, val items: List<SaleItem>, val time: Long, val customerName: String = "", val customerPhone: String = "", val orderType: String = "DINE_IN", val customerAddress: String = "") { val total: Double get() = items.sumOf { it.total } }
data class Expense(val id: Long, val title: String, val category: String, val amount: Double, val time: Long, val paymentMethod: String = "CASH", val shiftId: Long = 0L)
data class Shift(val id: Long, val person: String, val openedAt: Long, val openingCash: Double, val closedAt: Long = 0L, val closingCash: Double = 0.0, val status: String = "OPEN")
data class DueAccount(val id: Long, val saleId: Long, val customerName: String, val customerPhone: String, val total: Double, val paid: Double, val createdAt: Long) { val balance: Double get() = (total - paid).coerceAtLeast(0.0) }
data class PaymentRecord(val id: Long, val saleId: Long, val amount: Double, val method: String, val time: Long, val shiftId: Long, val note: String = "")
data class CompanyProfile(
    val name: String = "", val address: String = "", val phone: String = "", val footer: String = "Thank you for your visit",
    val logoPath: String = "", val saleLogoEnabled: Boolean = true, val kitchenLogoEnabled: Boolean = true,
    val currencySymbol: String = "Rs", val currencyCode: String = "PKR", val taxEnabled: Boolean = false,
    val taxRate: Double = 0.0, val taxLabel: String = "Tax", val serviceChargeEnabled: Boolean = false,
    val serviceChargeRate: Double = 0.0, val serviceChargeLabel: String = "Service Charge"
)
enum class ReceiptTheme { CLASSIC, MODERN, COMPACT, RESTAURANT, ADVANCED_PREMIUM }
object CurrencySettings { var symbol: String = "Rs" }
