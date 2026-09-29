package com.findupto.voicepos

data class SaleItem(val name: String, val qty: Int, val price: Double) { val total: Double get() = qty * price }
data class Sale(val id: Long, val items: List<SaleItem>, val total: Double, val time: Long)
data class Expense(val id: Long, val title: String, val category: String, val amount: Double, val time: Long)
data class CompanyProfile(val name: String = "My Restaurant", val address: String = "", val phone: String = "", val footer: String = "Thank you for your visit")
enum class ReceiptTheme { CLASSIC, MODERN, COMPACT, RESTAURANT }
