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

data class SupplierDue(val id: Long, val supplier: String, val phone: String, val description: String, val total: Double, val paid: Double, val createdAt: Long) {
    val balance: Double get() = (total - paid).coerceAtLeast(0.0)
}
class SupplierDueStore(context: Context) {
    private val prefs = context.getSharedPreferences("voice_supplier_dues_v1", Context.MODE_PRIVATE)
    fun all(): List<SupplierDue> {
        val a = JSONArray(prefs.getString("entries", "[]"))
        return (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { o -> SupplierDue(o.getLong("id"),o.getString("supplier"),o.optString("phone"),o.optString("description"),o.getDouble("total"),o.getDouble("paid"),o.getLong("createdAt")) } }.getOrNull() }.sortedByDescending { it.createdAt }
    }
    private fun save(xs: List<SupplierDue>) {
        val a = JSONArray()
        xs.forEach { x -> a.put(JSONObject().put("id",x.id).put("supplier",x.supplier).put("phone",x.phone).put("description",x.description).put("total",x.total).put("paid",x.paid).put("createdAt",x.createdAt)) }
        prefs.edit().putString("entries",a.toString()).apply()
    }
    fun add(supplier: String, phone: String, description: String, amount: Double): Boolean {
        if (supplier.isBlank() || amount <= 0.0) return false
        save(all() + SupplierDue(System.currentTimeMillis(),supplier.trim(),phone.trim(),description.trim(),amount,0.0,System.currentTimeMillis()))
        return true
    }
    fun update(x: SupplierDue) = save(all().map { if (it.id == x.id) x else it })
    fun remove(id: Long) = save(all().filterNot { it.id == id })
}
@Composable
fun SupplierDuesManager(store: SupplierDueStore, close: () -> Unit, changed: () -> Unit) {
    var rows by remember { mutableStateOf(store.all()) }
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
                Text("Outstanding: ${CurrencySettings.symbol} " + rows.sumOf { it.balance }.formatMoney())
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
private fun Double.formatMoney(): String = String.format(java.util.Locale.US,"%,.2f",this)
