package com.findupto.voicepos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AmendPendingOrderDialog(
    order: PendingSale,
    menu: List<MenuItem>,
    save: (PendingSale) -> Unit,
    close: () -> Unit
) {
    var items by remember(order.id) { mutableStateOf(order.items) }
    var query by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var customQty by remember { mutableStateOf("1") }
    var customPrice by remember { mutableStateOf("") }
    val matches = menu.filter {
        query.isBlank() || it.name.contains(query, true) || it.variant.contains(query, true) || it.size.contains(query, true)
    }.take(24)

    fun add(name: String, price: Double, qty: Int = 1) {
        if (name.isBlank() || price < 0.0 || qty < 1) return
        val idx = items.indexOfFirst { it.name.equals(name.trim(), true) && it.price == price }
        items = if (idx >= 0) items.toMutableList().also { it[idx] = it[idx].copy(qty = it[idx].qty + qty) }
        else items + SaleItem(name.trim(), qty, price)
    }

    AlertDialog(
        onDismissRequest = close,
        title = { Text("Amend Kitchen Order") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Order #${order.id}", fontWeight = FontWeight.Bold)
                Text("Add a menu item or a custom product. Custom product fields are at the top so you do not need to scroll through the menu.", style = MaterialTheme.typography.bodySmall)
                Text("Quick custom product", fontWeight = FontWeight.Bold)
                OutlinedTextField(customName, { customName = it }, Modifier.fillMaxWidth(), label = { Text("Product name (not in menu)") }, singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(customQty, { customQty = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), label = { Text("Qty") }, singleLine = true)
                    OutlinedTextField(customPrice, { customPrice = it.filter { ch -> ch.isDigit() || ch == '.' }.take(12) }, Modifier.weight(1f), label = { Text("Unit price") }, singleLine = true)
                }
                Button(onClick = {
                    val qty = customQty.toIntOrNull() ?: 1
                    val price = customPrice.toDoubleOrNull()
                    if (customName.isNotBlank() && qty > 0 && price != null && price >= 0.0) {
                        add(customName, price, qty)
                        customName = ""; customQty = "1"; customPrice = ""
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("ADD CUSTOM PRODUCT") }
                HorizontalDivider()
                Text("Current order", fontWeight = FontWeight.Bold)
                items.forEach { item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${item.qty} × ${item.name}", Modifier.weight(1f))
                        Text(money(item.total))
                        IconButton(onClick = {
                            items = items.map { x -> if (x == item) x.copy(qty = x.qty - 1) else x }.filter { it.qty > 0 }
                        }) { Icon(Icons.Default.Remove, "Remove one") }
                    }
                }
                HorizontalDivider()
                Text("Add from menu", fontWeight = FontWeight.Bold)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search product / variant / size") }, singleLine = true)
                matches.forEach { m ->
                    Card(Modifier.fillMaxWidth().clickable { add(m.name, m.price) }) {
                        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name, fontWeight = FontWeight.SemiBold)
                                val meta = listOf(m.variant, m.size).filter { it.isNotBlank() }.joinToString(" • ")
                                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(money(m.price))
                            Icon(Icons.Default.Add, "Add one")
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { save(order.copy(items = items)) }) { Text("SAVE & PRINT AMENDMENT") } },
        dismissButton = { TextButton(onClick = close) { Text("CANCEL") } }
    )
}
