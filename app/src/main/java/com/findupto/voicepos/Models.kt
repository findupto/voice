package com.findupto.voicepos

data class MenuItem(val id: Long, val name: String, val price: Double, val variant: String = "", val size: String = "")
data class SaleItem(val name: String, val qty: Int, val price: Double) { val total: Double get() = qty * price }
data class Sale(val id: Long, val items: List<SaleItem>, val total: Double, val time: Long, val customerName: String = "", val customerPhone: String = "")
data class PendingSale(val id: Long, val items: List<SaleItem>, val time: Long, val customerName: String = "", val customerPhone: String = "") { val total: Double get() = items.sumOf { it.total } }
data class Expense(val id: Long, val title: String, val category: String, val amount: Double, val time: Long)
data class CompanyProfile(
    val name: String = "",
    val address: String = "",
    val phone: String = "",
    val footer: String = "Thank you for your visit",
    val logoPath: String = "",
    val saleLogoEnabled: Boolean = true,
    val kitchenLogoEnabled: Boolean = true,
    val currencySymbol: String = "Rs",
    val currencyCode: String = "PKR",
    val taxEnabled: Boolean = false,
    val taxRate: Double = 0.0,
    val taxLabel: String = "Tax"
)
enum class ReceiptTheme { CLASSIC, MODERN, COMPACT, RESTAURANT, ADVANCED_PREMIUM }
object CurrencySettings { var symbol: String = "Rs" }
