package com.findupto.voicepos

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HeldOrder(val id: Long, val name: String, val items: List<SaleItem>, val time: Long)
data class StaffMember(val id: Long, val name: String, val role: String, val active: Boolean = true)
data class StockItem(val name: String, val stock: Double, val lowAt: Double, val unit: String)
data class TableSlot(val id: String, val label: String, val status: String = "AVAILABLE", val orderId: Long = 0L)
data class LoyaltyCustomer(val phone: String, val name: String, val points: Int, val visits: Int, val spent: Double)

class PremiumFeatureStore(context: Context) {
    private val p = context.getSharedPreferences("voice_pos_ultra_v1", Context.MODE_PRIVATE)
    private fun enc(s: String) = s.replace("~", "%7E").replace("|", "%7C")
    private fun dec(s: String) = s.replace("%7C", "|").replace("%7E", "~")

    fun favorites(): Set<Long> = p.getStringSet("favorites", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
    fun setFavorite(id: Long, enabled: Boolean) { val s = favorites().toMutableSet(); if (enabled) s.add(id) else s.remove(id); p.edit().putStringSet("favorites", s.map(Long::toString).toSet()).apply() }

    fun heldOrders(): List<HeldOrder> = p.getString("held", "")!!.lines().filter { it.isNotBlank() }.mapNotNull { runCatching { val a=it.split("~"); HeldOrder(a[0].toLong(), dec(a[1]), a[2].split("|").filter(String::isNotBlank).mapNotNull { x -> val q=x.split("^"); if(q.size>=3) SaleItem(dec(q[0]),q[1].toInt(),q[2].toDouble()) else null }, a[3].toLong()) }.getOrNull() }
    private fun saveHeld(xs: List<HeldOrder>) = p.edit().putString("held", xs.joinToString("\n") { h -> "${h.id}~${enc(h.name)}~${h.items.joinToString("|") { "${enc(it.name)}^${it.qty}^${it.price}" }}~${h.time}" }).apply()
    fun holdOrder(name: String, items: List<SaleItem>) { if(items.isEmpty()) return; saveHeld(heldOrders()+HeldOrder(System.currentTimeMillis(),name,items,System.currentTimeMillis())) }
    fun removeHeld(id: Long) = saveHeld(heldOrders().filterNot { it.id==id })

    fun staff(): List<StaffMember> = p.getString("staff", "")!!.lines().filter { it.isNotBlank() }.mapNotNull { runCatching { val a=it.split("~"); StaffMember(a[0].toLong(),dec(a[1]),dec(a[2]),a.getOrNull(3)!="0") }.getOrNull() }
    private fun saveStaff(xs: List<StaffMember>) = p.edit().putString("staff",xs.joinToString("\n") { "${it.id}~${enc(it.name)}~${enc(it.role)}~${if(it.active)1 else 0}" }).apply()
    fun upsertStaff(x: StaffMember) = saveStaff((staff().filterNot { it.id==x.id })+x)
    fun removeStaff(id: Long) = saveStaff(staff().filterNot { it.id==id })

    fun stock(): List<StockItem> = p.getString("stock", "")!!.lines().filter { it.isNotBlank() }.mapNotNull { runCatching { val a=it.split("~"); StockItem(dec(a[0]),a[1].toDouble(),a[2].toDouble(),dec(a[3])) }.getOrNull() }
    private fun saveStock(xs: List<StockItem>) = p.edit().putString("stock",xs.joinToString("\n") { "${enc(it.name)}~${it.stock}~${it.lowAt}~${enc(it.unit)}" }).apply()
    fun upsertStock(x: StockItem) = saveStock((stock().filterNot { it.name.equals(x.name,true) })+x)
    fun removeStock(name: String) = saveStock(stock().filterNot { it.name.equals(name,true) })

    fun tables(): List<TableSlot> = p.getString("tables", "")!!.lines().filter { it.isNotBlank() }.mapNotNull { runCatching { val a=it.split("~"); TableSlot(a[0],dec(a[1]),dec(a[2]),a.getOrNull(3)?.toLongOrNull()?:0L) }.getOrNull() }
    private fun saveTables(xs: List<TableSlot>) = p.edit().putString("tables",xs.joinToString("\n") { "${enc(it.id)}~${enc(it.label)}~${enc(it.status)}~${it.orderId}" }).apply()
    fun ensureTables(count: Int) { if(tables().isEmpty()) saveTables((1..count).map { TableSlot(it.toString(),"Table $it") }) }
    fun updateTable(x: TableSlot) = saveTables((tables().filterNot { it.id==x.id })+x)

    fun loyalty(): List<LoyaltyCustomer> = p.getString("loyalty", "")!!.lines().filter { it.isNotBlank() }.mapNotNull { runCatching { val a=it.split("~"); LoyaltyCustomer(dec(a[0]),dec(a[1]),a[2].toInt(),a[3].toInt(),a[4].toDouble()) }.getOrNull() }
    private fun saveLoyalty(xs: List<LoyaltyCustomer>) = p.edit().putString("loyalty",xs.joinToString("\n") { "${enc(it.phone)}~${enc(it.name)}~${it.points}~${it.visits}~${it.spent}" }).apply()
    fun upsertLoyalty(x: LoyaltyCustomer) = saveLoyalty((loyalty().filterNot { it.phone==x.phone })+x)
    fun awardSale(phone: String,name: String,total: Double) { if(phone.isBlank()) return; val old=loyalty().firstOrNull{it.phone==phone}; upsertLoyalty(LoyaltyCustomer(phone,name.ifBlank{old?.name?:"Customer"},(old?.points?:0)+(total/100).toInt(),(old?.visits?:0)+1,(old?.spent?:0.0)+total)) }

    fun giftBalance(): Map<String,Double> = p.getString("gift", "")!!.lines().filter { it.isNotBlank() }.mapNotNull { runCatching { val a=it.split("~"); a[0] to a[1].toDouble() }.getOrNull() }.toMap()
    fun setGift(code: String,balance: Double) { val m=giftBalance().toMutableMap();m[code.uppercase()]=balance.coerceAtLeast(0.0);p.edit().putString("gift",m.entries.joinToString("\n"){ "${it.key}~${it.value}" }).apply() }
}
