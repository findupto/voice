package com.findupto.voicepos

import android.content.Context
import android.util.Base64
import java.io.File

private const val DEFAULT_NAME = "The Slice Of Heaven"
private const val DEFAULT_ADDRESS = "Old Utility Store near Police Line Khansar Road Bhakkar Punjab Pakistan"
private const val DEFAULT_PHONE = "0332 1872929, 0310 3685151"

class Store(private val context: Context) {
    private val p = context.getSharedPreferences("voice_pos_v4", Context.MODE_PRIVATE)
    init {
        CurrencySettings.symbol = p.getString("currency_symbol", "Rs") ?: "Rs"
        if (!p.getBoolean("defaults_initialized", false)) {
            p.edit().putString("name", (p.getString("name", "") ?: "").ifBlank { DEFAULT_NAME })
                .putString("address", (p.getString("address", "") ?: "").ifBlank { DEFAULT_ADDRESS })
                .putString("phone", (p.getString("phone", "") ?: "").ifBlank { DEFAULT_PHONE })
                .putBoolean("defaults_initialized", true).apply()
        }
    }
    fun cash() = p.getString("cash", "0")!!.toDoubleOrNull() ?: 0.0
    fun setCash(v: Double) = p.edit().putString("cash", v.toString()).apply()
    fun hasOpeningCash() = p.getBoolean("opening", false)
    fun setOpeningCash(v: Double) { setCash(v); p.edit().putBoolean("opening", true).apply() }
    fun profile() = CompanyProfile(
        p.getString("name", DEFAULT_NAME)!!.ifBlank { DEFAULT_NAME },
        p.getString("address", DEFAULT_ADDRESS)!!.ifBlank { DEFAULT_ADDRESS },
        p.getString("phone", DEFAULT_PHONE)!!.ifBlank { DEFAULT_PHONE },
        p.getString("footer", "Thank you for your visit")!!,
        p.getString("logo_path", "")!!, p.getBoolean("sale_logo", true), p.getBoolean("kitchen_logo", true),
        p.getString("currency_symbol", "Rs")!!, p.getString("currency_code", "PKR")!!,
        p.getBoolean("tax_enabled", false), p.getString("tax_rate", "0")!!.toDoubleOrNull() ?: 0.0,
        p.getString("tax_label", "Tax")!!
    ).also { CurrencySettings.symbol = it.currencySymbol }
    fun saveProfile(x: CompanyProfile) = p.edit().putString("name", x.name).putString("address", x.address).putString("phone", x.phone).putString("footer", x.footer).putString("logo_path", x.logoPath).putBoolean("sale_logo", x.saleLogoEnabled).putBoolean("kitchen_logo", x.kitchenLogoEnabled).putString("currency_symbol", x.currencySymbol).putString("currency_code", x.currencyCode).putBoolean("tax_enabled", x.taxEnabled).putString("tax_rate", x.taxRate.toString()).putString("tax_label", x.taxLabel).apply().also { CurrencySettings.symbol = x.currencySymbol }
    fun theme() = runCatching { ReceiptTheme.valueOf(p.getString("theme", "MODERN")!!) }.getOrDefault(ReceiptTheme.MODERN)
    fun setTheme(t: ReceiptTheme) = p.edit().putString("theme", t.name).apply()
    fun customerPrinterAddress() = p.getString("customer_printer", "") ?: ""
    fun kitchenPrinterAddress() = p.getString("kitchen_printer", "") ?: ""
    fun setCustomerPrinterAddress(v: String) = p.edit().putString("customer_printer", v).apply()
    fun setKitchenPrinterAddress(v: String) = p.edit().putString("kitchen_printer", v).apply()
    fun totalWithTax(subtotal: Double): Double { val x = profile(); return if (x.taxEnabled) subtotal + subtotal * x.taxRate / 100.0 else subtotal }
    private fun encodeItems(items: List<SaleItem>) = items.joinToString("|") { "${it.name.replace("~", " ").replace("|", " ")}^${it.qty}^${it.price}" }
    private fun decodeItems(raw: String) = raw.split("|").filter { it.isNotBlank() }.mapNotNull { runCatching { val b = it.split("^"); SaleItem(b[0], b[1].toInt(), b[2].toDouble()) }.getOrNull() }
    fun sales() = p.getString("sales", "")!!.lines().filter { it.isNotBlank() }.mapNotNull { runCatching { val a = it.split("~"); Sale(a[0].toLong(), decodeItems(a[1]), a[2].toDouble(), a[3].toLong(), a.getOrNull(4)?.replace("%7E", "~") ?: "", a.getOrNull(5)?.replace("%7E", "~") ?: "") }.getOrNull() }
    fun addSale(s: Sale) = p.edit().putString("sales", (sales() + s).joinToString("\n") { "${it.id}~${encodeItems(it.items)}~${it.total}~${it.time}~${it.customerName.replace("~", "%7E")}~${it.customerPhone.replace("~", "%7E")}" }).apply()
    fun pending() = p.getString("pending", "")!!.lines().filter { it.isNotBlank() }.mapNotNull { runCatching { val a = it.split("~"); PendingSale(a[0].toLong(), decodeItems(a[1]), a[2].toLong(), a.getOrNull(3)?.replace("%7E", "~") ?: "", a.getOrNull(4)?.replace("%7E", "~") ?: "") }.getOrNull() }
    private fun savePending(all: List<PendingSale>) = p.edit().putString("pending", all.joinToString("\n") { "${it.id}~${encodeItems(it.items)}~${it.time}~${it.customerName.replace("~", "%7E")}~${it.customerPhone.replace("~", "%7E")}" }).apply()
    fun addPending(s: PendingSale) = savePending(pending() + s)
    fun replacePending(s: PendingSale) = savePending(pending().map { if (it.id == s.id) s else it })
    fun removePending(id: Long) = savePending(pending().filterNot { it.id == id })
    fun menu() = p.getString("menu", "")!!.lines().filter { it.isNotBlank() }.mapNotNull { runCatching { val a = it.split("^"); MenuItem(a[0].toLong(), a[1], a[2].toDouble(), a.getOrNull(3) ?: "", a.getOrNull(4) ?: "") }.getOrNull() }
    fun saveMenu(items: List<MenuItem>) = p.edit().putString("menu", items.joinToString("\n") { "${it.id}^${it.name.replace("^", " ")}^${it.price}^${it.variant.replace("^", " ")}^${it.size.replace("^", " ")}" }).apply()
    fun addMenuItem(i: MenuItem) = saveMenu(menu() + i)
    fun updateMenuItem(i: MenuItem) = saveMenu(menu().map { if (it.id == i.id) i else it })
    fun removeMenuItem(id: Long) = saveMenu(menu().filterNot { it.id == id })
    fun expenses() = p.getString("expenses", "")!!.lines().filter { it.isNotBlank() }.mapNotNull { runCatching { val a = it.split("~"); Expense(a[0].toLong(), a[1], a[2], a[3].toDouble(), a[4].toLong()) }.getOrNull() }
    fun addExpense(e: Expense) = p.edit().putString("expenses", (expenses() + e).joinToString("\n") { "${it.id}~${it.title.replace("~", " ")}~${it.category.replace("~", " ")}~${it.amount}~${it.time}" }).apply()
    fun updateExpense(e: Expense) = p.edit().putString("expenses", expenses().map { if (it.id == e.id) e else it }.joinToString("\n") { "${it.id}~${it.title.replace("~", " ")}~${it.category.replace("~", " ")}~${it.amount}~${it.time}" }).apply()
    fun removeExpense(id: Long) = p.edit().putString("expenses", expenses().filterNot { it.id == id }.joinToString("\n") { "${it.id}~${it.title.replace("~", " ")}~${it.category.replace("~", " ")}~${it.amount}~${it.time}" }).apply()
    private fun itemsJson(items: List<SaleItem>) = org.json.JSONArray().apply { items.forEach { put(org.json.JSONObject().apply { put("name", it.name); put("qty", it.qty); put("price", it.price) }) } }
    private fun saleJson(s: Sale) = org.json.JSONObject().apply { put("id", s.id); put("time", s.time); put("total", s.total); put("customerName", s.customerName); put("customerPhone", s.customerPhone); put("items", itemsJson(s.items)) }
    private fun pendingJson(s: PendingSale) = org.json.JSONObject().apply { put("id", s.id); put("time", s.time); put("customerName", s.customerName); put("customerPhone", s.customerPhone); put("items", itemsJson(s.items)) }
    fun exportBackup(): String {
        val prof = profile(); val root = org.json.JSONObject()
        root.put("format", "VOICE_POS_BACKUP_V3"); root.put("cash", cash()); root.put("opening", hasOpeningCash()); root.put("theme", theme().name)
        root.put("profile", org.json.JSONObject().apply { put("name", prof.name); put("address", prof.address); put("phone", prof.phone); put("footer", prof.footer); put("saleLogo", prof.saleLogoEnabled); put("kitchenLogo", prof.kitchenLogoEnabled); put("logo", prof.logoPath.takeIf { it.isNotBlank() }?.let { runCatching { Base64.encodeToString(File(it).readBytes(), Base64.NO_WRAP) }.getOrDefault("") } ?: ""); put("currencySymbol", prof.currencySymbol); put("currencyCode", prof.currencyCode); put("taxEnabled", prof.taxEnabled); put("taxRate", prof.taxRate); put("taxLabel", prof.taxLabel) })
        root.put("menu", org.json.JSONArray().apply { menu().forEach { put(org.json.JSONObject().apply { put("id", it.id); put("name", it.name); put("price", it.price); put("variant", it.variant); put("size", it.size) }) } })
        root.put("sales", org.json.JSONArray().apply { sales().forEach { put(saleJson(it)) } }); root.put("pending", org.json.JSONArray().apply { pending().forEach { put(pendingJson(it)) } }); root.put("expenses", org.json.JSONArray().apply { expenses().forEach { put(org.json.JSONObject().apply { put("id", it.id); put("title", it.title); put("category", it.category); put("amount", it.amount); put("time", it.time) }) } })
        return root.toString()
    }
    private fun jsonItems(a: org.json.JSONArray) = (0 until a.length()).map { val x = a.getJSONObject(it); SaleItem(x.getString("name"), x.getInt("qty"), x.getDouble("price")) }
    fun importBackup(raw: String) {
        val root = org.json.JSONObject(raw); require(root.optString("format").startsWith("VOICE_POS_BACKUP_V")); val pr = root.getJSONObject("profile")
        var logoPath = profile().logoPath; val b64 = pr.optString("logo", "")
        if (b64.isNotBlank()) runCatching { val f = File(context.filesDir, "branding/company_logo.png"); f.parentFile?.mkdirs(); f.writeBytes(Base64.decode(b64, Base64.DEFAULT)); logoPath = f.absolutePath }
        saveProfile(CompanyProfile(pr.optString("name", DEFAULT_NAME), pr.optString("address", DEFAULT_ADDRESS), pr.optString("phone", DEFAULT_PHONE), pr.optString("footer", "Thank you for your visit"), logoPath, pr.optBoolean("saleLogo", true), pr.optBoolean("kitchenLogo", true), pr.optString("currencySymbol", "Rs"), pr.optString("currencyCode", "PKR"), pr.optBoolean("taxEnabled", false), pr.optDouble("taxRate", 0.0), pr.optString("taxLabel", "Tax")))
        setCash(root.optDouble("cash", 0.0)); p.edit().putBoolean("opening", root.optBoolean("opening", true)).putString("theme", root.optString("theme", "MODERN")).apply()
        val ms = root.optJSONArray("menu") ?: org.json.JSONArray(); saveMenu((0 until ms.length()).map { val x = ms.getJSONObject(it); MenuItem(x.getLong("id"), x.getString("name"), x.getDouble("price"), x.optString("variant"), x.optString("size")) })
        val ss = root.optJSONArray("sales") ?: org.json.JSONArray(); p.edit().putString("sales", (0 until ss.length()).joinToString("\n") { val x = ss.getJSONObject(it); "${x.getLong("id")}~${encodeItems(jsonItems(x.getJSONArray("items")))}~${x.getDouble("total")}~${x.getLong("time")}~${x.optString("customerName").replace("~", "%7E")}~${x.optString("customerPhone").replace("~", "%7E")}" }).apply()
        val ps = root.optJSONArray("pending") ?: org.json.JSONArray(); p.edit().putString("pending", (0 until ps.length()).joinToString("\n") { val x = ps.getJSONObject(it); "${x.getLong("id")}~${encodeItems(jsonItems(x.getJSONArray("items")))}~${x.getLong("time")}~${x.optString("customerName").replace("~", "%7E")}~${x.optString("customerPhone").replace("~", "%7E")}" }).apply()
        val es = root.optJSONArray("expenses") ?: org.json.JSONArray(); p.edit().putString("expenses", (0 until es.length()).joinToString("\n") { val x = es.getJSONObject(it); "${x.getLong("id")}~${x.getString("title")}~${x.getString("category")}~${x.getDouble("amount")}~${x.getLong("time")}" }).apply()
    }
}
