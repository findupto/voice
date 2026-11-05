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
import java.util.Locale

data class PurchaseOrder(
    val id: Long,
    val supplier: String,
    val stockItemId: Long,
    val itemName: String,
    val quantity: Double,
    val unit: String,
    val unitCost: Double,
    val createdAt: Long,
    val receivedAt: Long = 0L
) {
    val total: Double get() = quantity * unitCost
    val received: Boolean get() = receivedAt > 0L
}

class SupplierPurchaseStore(context: Context) {
    private val prefs = context.getSharedPreferences("voice_supplier_purchase_orders_v1", Context.MODE_PRIVATE)

    fun all(): List<PurchaseOrder> {
        val rows = JSONArray(prefs.getString("orders", "[]"))
        return (0 until rows.length()).mapNotNull { i -> runCatching {
            val o = rows.getJSONObject(i)
            PurchaseOrder(
                o.getLong("id"), o.getString("supplier"), o.getLong("stockItemId"),
                o.getString("itemName"), o.getDouble("quantity"), o.getString("unit"),
                o.getDouble("unitCost"), o.getLong("createdAt"), o.optLong("receivedAt", 0L)
            )
        }.getOrNull() }.sortedByDescending { it.createdAt }
    }

    @Synchronized fun create(supplier: String, item: InventoryStockItem, quantity: Double, unitCost: Double): Boolean {
        if (supplier.isBlank() || !quantity.isFinite() || quantity <= 0.0 || !unitCost.isFinite() || unitCost < 0.0) return false
        val now = System.currentTimeMillis()
        val row = PurchaseOrder(now, supplier.trim(), item.id, item.name, quantity, item.unit, unitCost, now)
        val next = JSONArray().apply {
            all().forEach { put(toJson(it)) }
            put(toJson(row))
        }
        return prefs.edit().putString("orders", next.toString()).commit()
    }

    @Synchronized fun receive(id: Long, inventory: InventoryStore, supplierDues: SupplierDueStore): Boolean {
        val order = all().firstOrNull { it.id == id } ?: return false
        if (order.received) return false
        val item = inventory.items().firstOrNull { it.id == order.stockItemId } ?: return false
        if (!inventory.adjustStock(item.id, order.quantity, "Purchase order #$id received from ${order.supplier}")) return false
        if (order.total > 0.0 && !supplierDues.add(order.supplier, "", "Purchase order #$id • ${order.quantity} ${order.unit} ${order.itemName}", order.total)) return false
        val updated = order.copy(receivedAt = System.currentTimeMillis())
        val next = JSONArray().apply { all().forEach { put(toJson(if (it.id == id) updated else it)) } }
        return prefs.edit().putString("orders", next.toString()).commit()
    }

    private fun toJson(x: PurchaseOrder) = JSONObject()
        .put("id", x.id).put("supplier", x.supplier).put("stockItemId", x.stockItemId)
        .put("itemName", x.itemName).put("quantity", x.quantity).put("unit", x.unit)
        .put("unitCost", x.unitCost).put("createdAt", x.createdAt).put("receivedAt", x.receivedAt)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplierPurchasingManager(
    inventory: InventoryStore,
    supplierDues: SupplierDueStore,
    close: () -> Unit,
    changed: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ordersStore = remember(context) { SupplierPurchaseStore(context) }
    var tick by remember { mutableIntStateOf(0) }
    var supplier by remember { mutableStateOf("") }
    var itemName by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var unitCost by remember { mutableStateOf("0") }
    var message by remember { mutableStateOf("") }
    val stock = remember(tick) { inventory.items() }
    val orders = remember(tick) { ordersStore.all() }

    AlertDialog(
        onDismissRequest = close,
        modifier = Modifier.fillMaxWidth(),
        title = { Text("Supplier purchase orders") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 580.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Create an order, then mark it received when the goods arrive. Receiving adds stock and records the supplier payable.")
                OutlinedTextField(supplier, { supplier = it }, label = { Text("Supplier name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = itemName,
                        onValueChange = { itemName = it },
                        label = { Text("Inventory item") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        stock.filter { it.name.contains(itemName, ignoreCase = true) }.forEach { item ->
                            DropdownMenuItem(text = { Text("${item.name} • ${item.unit}") }, onClick = {
                                itemName = item.name
                                unitCost = item.unitCost.toString()
                                expanded = false
                            })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(quantity, { quantity = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Quantity") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(unitCost, { unitCost = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Cost / unit") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                val selected = stock.firstOrNull { canonicalProductKey(it.name) == canonicalProductKey(itemName) }
                val total = (quantity.toDoubleOrNull() ?: 0.0) * (unitCost.toDoubleOrNull() ?: 0.0)
                Text("Order total: ${CurrencySettings.symbol} ${String.format(Locale.US, "%,.2f", total)}", style = MaterialTheme.typography.titleSmall)
                Button(
                    onClick = {
                        val qty = quantity.toDoubleOrNull() ?: 0.0
                        val cost = unitCost.toDoubleOrNull() ?: -1.0
                        if (selected != null && ordersStore.create(supplier, selected, qty, cost)) {
                            message = "Purchase order created"
                            supplier = ""; itemName = ""; quantity = "1"; unitCost = "0"
                            tick++; changed()
                        } else message = "Enter a supplier, select an existing stock item, and check quantity and cost"
                    },
                    enabled = selected != null && supplier.isNotBlank() && (quantity.toDoubleOrNull() ?: 0.0) > 0 && (unitCost.toDoubleOrNull() ?: -1.0) >= 0,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("CREATE PURCHASE ORDER") }
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("Purchase order history", style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.heightIn(max = 250.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(orders, key = { it.id }) { order ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(order.supplier, style = MaterialTheme.typography.titleSmall)
                                Text("${order.quantity} ${order.unit} ${order.itemName} • ${CurrencySettings.symbol} ${String.format(Locale.US, "%,.2f", order.total)}")
                                Text(if (order.received) "RECEIVED" else "PENDING RECEIPT", color = if (order.received) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                if (!order.received) {
                                    OutlinedButton(onClick = {
                                        if (ordersStore.receive(order.id, inventory, supplierDues)) {
                                            message = "Goods received; stock and supplier payable updated"
                                            tick++; changed()
                                        } else message = "Could not receive this order. Check stock item and try again."
                                    }) { Text("MARK GOODS RECEIVED") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("DONE") } }
    )
}
