package com.findupto.voicepos

import android.content.Context

class Store(context: Context) {
    private val p = context.getSharedPreferences("voice_pos_v4", Context.MODE_PRIVATE)

    fun cash() = p.getString("cash", "0")!!.toDoubleOrNull() ?: 0.0
    fun setCash(v: Double) = p.edit().putString("cash", v.toString()).apply()
    fun hasOpeningCash() = p.getBoolean("opening", false)
    fun setOpeningCash(v: Double) { setCash(v); p.edit().putBoolean("opening", true).apply() }

    fun profile(): CompanyProfile {
        val savedName = p.getString("name", null)
        val savedAddress = p.getString("address", null)
        val savedPhone = p.getString("phone", null)
        return CompanyProfile(
            savedName ?: "The Slice of Heaven",
            savedAddress ?: "Old Utility Store near Police Line\nKhansar Road Bhakkar Punjab\nPakistan",
            savedPhone ?: "0332 1872929, 0310 3685151",
            p.getString("footer", "Thank you for your visit") ?: "Thank you for your visit"
        )
    }

    fun saveProfile(x: CompanyProfile) = p.edit()
        .putString("name", x.name)
        .putString("address", x.address)
        .putString("phone", x.phone)
        .putString("footer", x.footer)
        .apply()

    fun theme() = runCatching {
        ReceiptTheme.valueOf(p.getString("theme", "MODERN")!!)
    }.getOrDefault(ReceiptTheme.MODERN)

    fun setTheme(t: ReceiptTheme) = p.edit().putString("theme", t.name).apply()
    fun customerPrinterAddress() = p.getString("customer_printer", "") ?: ""
    fun kitchenPrinterAddress() = p.getString("kitchen_printer", "") ?: ""
    fun setCustomerPrinterAddress(v: String) = p.edit().putString("customer_printer", v).apply()
    fun setKitchenPrinterAddress(v: String) = p.edit().putString("kitchen_printer", v).apply()

    private fun encodeItems(items: List<SaleItem>) = items.joinToString("|") {
        "${it.name.replace("~", " ").replace("|", " ")}^${it.qty}^${it.price}"
    }

    private fun decodeItems(raw: String): List<SaleItem> =
        raw.split("|").filter { it.isNotBlank() }.mapNotNull { q ->
            runCatching {
                val b = q.split("^")
                SaleItem(b[0], b[1].toInt(), b[2].toDouble())
            }.getOrNull()
        }

    fun sales(): List<Sale> =
        p.getString("sales", "")!!.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val a = line.split("~")
                Sale(a[0].toLong(), decodeItems(a[1]), a[2].toDouble(), a[3].toLong())
            }.getOrNull()
        }

    fun addSale(s: Sale) {
        val raw = (sales() + s).joinToString("\n") {
            "${it.id}~${encodeItems(it.items)}~${it.total}~${it.time}"
        }
        p.edit().putString("sales", raw).apply()
    }

    fun pending(): List<PendingSale> =
        p.getString("pending", "")!!.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val a = line.split("~")
                PendingSale(a[0].toLong(), decodeItems(a[1]), a[2].toLong())
            }.getOrNull()
        }

    private fun savePending(all: List<PendingSale>) {
        val raw = all.joinToString("\n") {
            "${it.id}~${encodeItems(it.items)}~${it.time}"
        }
        p.edit().putString("pending", raw).apply()
    }

    fun addPending(s: PendingSale) = savePending(pending() + s)
    fun replacePending(s: PendingSale) = savePending(pending().map { if (it.id == s.id) s else it })
    fun removePending(id: Long) = savePending(pending().filterNot { it.id == id })

    fun expenses(): List<Expense> =
        p.getString("expenses", "")!!.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val a = line.split("~")
                Expense(a[0].toLong(), a[1], a[2], a[3].toDouble(), a[4].toLong())
            }.getOrNull()
        }

    fun addExpense(e: Expense) {
        val raw = (expenses() + e).joinToString("\n") {
            "${it.id}~${it.title.replace("~", " ")}~${it.category.replace("~", " ")}~${it.amount}~${it.time}"
        }
        p.edit().putString("expenses", raw).apply()
    }
}