package com.findupto.voicepos

import android.content.Context
import java.util.Locale

class Store(context: Context) {
    private val p = context.getSharedPreferences("voice_pos_v3", Context.MODE_PRIVATE)
    fun cash() = p.getString("cash", "0")!!.toDoubleOrNull() ?: 0.0
    fun setCash(v: Double) = p.edit().putString("cash", v.toString()).apply()
    fun hasOpeningCash() = p.getBoolean("opening", false)
    fun setOpeningCash(v: Double) { setCash(v); p.edit().putBoolean("opening", true).apply() }
    fun profile() = CompanyProfile(
        p.getString("name","My Restaurant") ?: "My Restaurant",
        p.getString("address","") ?: "", p.getString("phone","") ?: "",
        p.getString("footer","Thank you for your visit") ?: "Thank you for your visit")
    fun saveProfile(x: CompanyProfile) = p.edit().putString("name",x.name).putString("address",x.address).putString("phone",x.phone).putString("footer",x.footer).apply()
    fun theme() = runCatching { ReceiptTheme.valueOf(p.getString("theme","MODERN")!!) }.getOrDefault(ReceiptTheme.MODERN)
    fun setTheme(t: ReceiptTheme) = p.edit().putString("theme",t.name).apply()
    fun sales(): List<Sale> = p.getString("sales","")!!.lines().filter{it.isNotBlank()}.mapNotNull{line->runCatching{
        val a=line.split("~"); val items=a[1].split("|").filter{it.isNotBlank()}.map{q->val b=q.split("^");SaleItem(b[0],b[1].toInt(),b[2].toDouble())}
        Sale(a[0].toLong(),items,a[2].toDouble(),a[3].toLong())
    }.getOrNull()}
    fun addSale(s: Sale) { val raw=(sales()+s).joinToString("\n"){x->x.id.toString()+"~"+x.items.joinToString("|"){it.name.replace("~"," ")+"^"+it.qty+"^"+it.price}+"~"+x.total+"~"+x.time}; p.edit().putString("sales",raw).apply() }
    fun expenses(): List<Expense> = p.getString("expenses","")!!.lines().filter{it.isNotBlank()}.mapNotNull{line->runCatching{
        val a=line.split("~"); Expense(a[0].toLong(),a[1],a[2],a[3].toDouble(),a[4].toLong())
    }.getOrNull()}
    fun addExpense(e: Expense) { val raw=(expenses()+e).joinToString("\n"){x->x.id.toString()+"~"+x.title.replace("~"," ")+"~"+x.category.replace("~"," ")+"~"+x.amount+"~"+x.time}; p.edit().putString("expenses",raw).apply() }
}
