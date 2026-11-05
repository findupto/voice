package com.findupto.voicepos

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class SupplierDue(val id: Long, val supplier: String, val phone: String, val description: String, val total: Double, val paid: Double, val createdAt: Long, val referenceId: String = "") {
    val balance: Double get() = (total - paid).coerceAtLeast(0.0)
}
class SupplierDueStore(context: Context) {
    private val prefs = context.getSharedPreferences("voice_supplier_dues_v1", Context.MODE_PRIVATE)
    fun all(): List<SupplierDue> {
        val a = JSONArray(prefs.getString("entries", "[]"))
        return (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { o -> SupplierDue(o.getLong("id"),o.getString("supplier"),o.optString("phone"),o.optString("description"),o.getDouble("total"),o.getDouble("paid"),o.getLong("createdAt"),o.optString("referenceId","")) } }.getOrNull() }.sortedByDescending { it.createdAt }
    }
    private fun save(xs: List<SupplierDue>) {
        val a = JSONArray()
        xs.forEach { x -> a.put(JSONObject().put("id",x.id).put("supplier",x.supplier).put("phone",x.phone).put("description",x.description).put("total",x.total).put("paid",x.paid).put("createdAt",x.createdAt).put("referenceId",x.referenceId)) }
        prefs.edit().putString("entries",a.toString()).apply()
    }
    fun add(supplier: String, phone: String, description: String, amount: Double): Boolean {
        if (supplier.isBlank() || amount <= 0.0) return false
        save(all() + SupplierDue(System.currentTimeMillis(),supplier.trim(),phone.trim(),description.trim(),amount,0.0,System.currentTimeMillis()))
        return true
    }
    /** Idempotent payable creation for retry-safe purchase-order receiving. */
    @Synchronized fun addOnce(referenceId: String, supplier: String, phone: String, description: String, amount: Double): Boolean {
        if (referenceId.isBlank() || supplier.isBlank() || !amount.isFinite() || amount <= 0.0) return false
        val existing = all()
        if (existing.any { it.referenceId == referenceId }) return true
        val now = System.currentTimeMillis()
        val row = SupplierDue(now, supplier.trim(), phone.trim(), description.trim(), amount, 0.0, now, referenceId)
        val next = JSONArray().apply {
            existing.forEach { x -> put(JSONObject().put("id",x.id).put("supplier",x.supplier).put("phone",x.phone).put("description",x.description).put("total",x.total).put("paid",x.paid).put("createdAt",x.createdAt).put("referenceId",x.referenceId)) }
            put(JSONObject().put("id",row.id).put("supplier",row.supplier).put("phone",row.phone).put("description",row.description).put("total",row.total).put("paid",row.paid).put("createdAt",row.createdAt).put("referenceId",row.referenceId))
        }
        return prefs.edit().putString("entries", next.toString()).commit()
    }
    fun update(x: SupplierDue) = save(all().map { if (it.id == x.id) x else it })
    fun remove(id: Long) = save(all().filterNot { it.id == id })
}
@Composable
fun SupplierDuesManager(store: SupplierDueStore, close: () -> Unit, changed: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val purchaseStore = remember(context) { SupplierPurchaseStore(context) }
    var rows by remember { mutableStateOf(store.all()) }
    var purchaseOrders by remember { mutableStateOf(purchaseStore.all()) }
    val supplierSummaries = remember(rows, purchaseOrders) {
        (rows.map { it.supplier } + purchaseOrders.map { it.supplier })
            .filter { it.isNotBlank() }.distinctBy { it.trim().lowercase(java.util.Locale.ROOT) }
            .sortedBy { it.lowercase(java.util.Locale.ROOT) }
            .map { name ->
                val dues = rows.filter { it.supplier.equals(name, true) }
                val orders = purchaseOrders.filter { it.supplier.equals(name, true) }
                SupplierSummary(name, orders.sumOf { it.total }, orders.count { it.received }, orders.count { !it.received }, dues.sumOf { it.paid }, dues.sumOf { it.balance })
            }
    }
    var addMode by remember { mutableStateOf(false) }
    var supplier by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var paying by remember { mutableStateOf<SupplierDue?>(null) }
    var payment by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Supplier / Vendor Payables") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Outstanding payable: ${CurrencySettings.symbol} " + rows.sumOf { it.balance }.formatMoney(), style = MaterialTheme.typography.titleSmall)
                Text("SUPPLIER SUMMARY", style = MaterialTheme.typography.titleMedium)
                if (supplierSummaries.isEmpty()) Text("No supplier purchases or payables yet.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 170.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(supplierSummaries, key = { it.name.lowercase(java.util.Locale.ROOT) }) { summary ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(summary.name, style = MaterialTheme.typography.titleSmall)
                                Text("Purchased: ${CurrencySettings.symbol} ${summary.purchased.formatMoney()} • Paid: ${CurrencySettings.symbol} ${summary.paid.formatMoney()}", style = MaterialTheme.typography.bodySmall)
                                Text("Received orders: ${summary.receivedOrders} • Pending: ${summary.pendingOrders} • Outstanding: ${CurrencySettings.symbol} ${summary.outstanding.formatMoney()}", style = MaterialTheme.typography.bodySmall, color = if (summary.outstanding > 0.005) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
                Button(onClick = { addMode = !addMode }) { Text(if (addMode) "CANCEL ADD" else "ADD SUPPLIER DUE") }
                if (addMode) {
                    OutlinedTextField(supplier,{supplier=it},label={Text("Supplier / vendor name")},singleLine=true)
                    OutlinedTextField(phone,{phone=it},label={Text("Phone (optional)")},singleLine=true)
                    OutlinedTextField(description,{description=it},label={Text("Purchase / note")},singleLine=true)
                    OutlinedTextField(amount,{amount=it.filter { c -> c.isDigit() || c=='.' }},label={Text("Amount owed")},singleLine=true)
                    Button(onClick = { val value=amount.toDoubleOrNull(); if(value!=null && store.add(supplier,phone,description,value)){rows=store.all();supplier="";phone="";description="";amount="";addMode=false;changed()} }) { Text("SAVE PAYABLE") }
                }
                LazyColumn(Modifier.heightIn(max=340.dp)) {
                    items(rows,key={it.id}) { row ->
                        Card(Modifier.fillMaxWidth().padding(vertical=3.dp)) {
                            Column(Modifier.padding(10.dp), verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                Text(row.supplier, style=MaterialTheme.typography.titleMedium)
                                if(row.phone.isNotBlank()) Text(row.phone,style=MaterialTheme.typography.bodySmall)
                                if(row.description.isNotBlank()) Text(row.description,style=MaterialTheme.typography.bodySmall)
                                Text("Owed: ${CurrencySettings.symbol} ${row.balance.formatMoney()}  •  Original: ${row.total.formatMoney()}")
                                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick={paying=row;payment=""},enabled=row.balance>0.005){Text("RECORD PAYMENT")}
                                    TextButton(onClick={store.remove(row.id);rows=store.all();changed()}){Text("REMOVE")}
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick=close){Text("DONE")} }
    )
    paying?.let { due ->
        AlertDialog(onDismissRequest={paying=null},title={Text("Pay ${due.supplier}")},
            text={OutlinedTextField(payment,{payment=it.filter{c->c.isDigit()||c=='.'}},label={Text("Payment (balance ${due.balance.formatMoney()})")},singleLine=true)},
            confirmButton={TextButton(onClick={val n=payment.toDoubleOrNull();if(n!=null&&n>0){store.update(due.copy(paid=due.paid+n.coerceAtMost(due.balance)));rows=store.all();changed();paying=null}}){Text("SAVE PAYMENT")}},
            dismissButton={TextButton(onClick={paying=null}){Text("CANCEL")}})
    }
}
private data class SupplierSummary(
    val name: String,
    val purchased: Double,
    val receivedOrders: Int,
    val pendingOrders: Int,
    val paid: Double,
    val outstanding: Double
)
private fun Double.formatMoney(): String = String.format(java.util.Locale.US,"%,.2f",this)
