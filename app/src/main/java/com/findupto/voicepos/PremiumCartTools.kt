package com.findupto.voicepos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

const val CART_DISCOUNT_PREFIX = "__CART_DISCOUNT__"
fun cartDiscount(cart: List<SaleItem>): Double = -cart.filter { it.name.startsWith(CART_DISCOUNT_PREFIX) }.sumOf { it.total }.coerceAtLeast(0.0)
fun cartProductSubtotal(cart: List<SaleItem>): Double = cart.filterNot { it.name.startsWith(CART_DISCOUNT_PREFIX) }.sumOf { it.total }

@Composable
fun MultiAddCartDialog(menu: List<MenuItem>, addSelected: (List<Pair<MenuItem, Int>>) -> Unit, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val quantities = remember { mutableStateMapOf<Long, Int>() }
    val filtered = menu.filter { it.name.contains(query, true) || it.variant.contains(query, true) || it.size.contains(query, true) }.take(100)
    val selectedCount = quantities.values.count { it > 0 }
    AlertDialog(onDismissRequest = close, title = { Text("Add Multiple Products") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search products") }, singleLine = true)
            Text("Select multiple products, set quantities, then add them all at once.", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(filtered, key = { it.id }) { item ->
                    val qty = quantities[item.id] ?: 0
                    Card(Modifier.fillMaxWidth().clickable { quantities[item.id] = if (qty > 0) 0 else 1 }) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = qty > 0, onCheckedChange = { quantities[item.id] = if (it) 1 else 0 })
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.titleSmall)
                                val detail = listOf(item.variant, item.size).filter(String::isNotBlank).joinToString(" • ")
                                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
                                Text(money(item.price), style = MaterialTheme.typography.bodySmall)
                            }
                            if (qty > 0) {
                                IconButton(onClick = { quantities[item.id] = (qty - 1).coerceAtLeast(1) }) { Text("−") }
                                Text(qty.toString(), style = MaterialTheme.typography.titleMedium)
                                IconButton(onClick = { quantities[item.id] = qty + 1 }) { Text("+") }
                            }
                        }
                    }
                }
            }
            Text("$selectedCount product${if (selectedCount == 1) "" else "s"} selected", style = MaterialTheme.typography.labelLarge)
        }
    }, confirmButton = { Button(onClick = {
        addSelected(filtered.mapNotNull { item -> quantities[item.id]?.takeIf { it > 0 }?.let { item to it } }); close()
    }, enabled = selectedCount > 0) { Text("ADD SELECTED") } }, dismissButton = { TextButton(onClick = close) { Text("CANCEL") } })
}

@Composable
fun DiscountDialog(base: Double, current: Double, apply: (Double) -> Unit, close: () -> Unit) {
    var mode by remember { mutableStateOf("PERCENT") }
    var value by remember { mutableStateOf(if (current > 0) current.toString() else "") }
    val entered = value.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    val amount = if (mode == "PERCENT") (base * entered / 100.0).coerceAtMost(base) else entered.coerceAtMost(base)
    AlertDialog(onDismissRequest = close, title = { Text("Cart Discount") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Subtotal: ${money(base)}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(mode == "PERCENT", { mode = "PERCENT" }, label = { Text("%") })
                FilterChip(mode == "AMOUNT", { mode = "AMOUNT" }, label = { Text("Amount") })
            }
            OutlinedTextField(value, { value = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.fillMaxWidth(), label = { Text(if (mode == "PERCENT") "Discount %" else "Discount amount") }, singleLine = true)
            Text("Discount: ${money(amount)}", style = MaterialTheme.typography.titleMedium)
            Text("New subtotal: ${money((base - amount).coerceAtLeast(0.0))}")
        }
    }, confirmButton = { Button(onClick = { apply(amount); close() }, enabled = amount > 0) { Text("APPLY") } }, dismissButton = { TextButton(onClick = { apply(0.0); close() }) { Text("REMOVE") } })
}
