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
    val matches = menu.filter {
        query.isBlank() || it.name.contains(query, true) || it.variant.contains(query, true) || it.size.contains(query, true)
    }.take(24)

    fun add(menuItem: MenuItem, qty: Int = 1) {
        val idx = items.indexOfFirst { it.name.equals(menuItem.name, true) && it.price == menuItem.price }
        items = if (idx >= 0) items.toMutableList().also { it[idx] = it[idx].copy(qty = it[idx].qty + qty) }
        else items + SaleItem(menuItem.name, qty, menuItem.price)
    }

    AlertDialog(
        onDismissRequest = close,
        title = { Text("Amend Kitchen Order") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Order #${order.id}", fontWeight = FontWeight.Bold)
                Text("Add items after the order has already gone to the kitchen. Saving will print an AMENDED ORDER.", style = MaterialTheme.typography.bodySmall)
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
                    Card(Modifier.fillMaxWidth().clickable { add(m) }) {
                        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name, fontWeight = FontWeight.SemiBold)
                                val meta = listOf(m.variant, m.size).filter { it.isNotBlank() }.joinToString(" • ")
                                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(money(m.price))
                            Icon(Icons.Default.Add, "Add")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { save(order.copy(items = items)) }) { Text("SAVE & PRINT AMENDMENT") }
        },
        dismissButton = { TextButton(onClick = close) { Text("CANCEL") } }
    )
}
