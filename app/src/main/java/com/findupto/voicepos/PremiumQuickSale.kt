package com.findupto.voicepos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun PremiumQuickSale(
    cart: List<SaleItem>, menu: List<MenuItem>, addProduct: (MenuItem) -> Unit, send: () -> Unit, clear: () -> Unit, manual: () -> Unit, discount: () -> Unit,
    changeQty: (SaleItem, Int) -> Unit, hold: () -> Unit
) {
    val products = cart.filterNot { it.name.startsWith(CART_DISCOUNT_PREFIX) }
    val discountAmount = cartDiscount(cart)
    val subtotal = cartProductSubtotal(cart)
    val total = cart.sumOf { it.total }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primary.copy(alpha = .16f)) {
                        Icon(Icons.Default.PointOfSale, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("QUICK CHECKOUT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        Text("Build an order", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        Text("Tap a product to add it instantly.", style = MaterialTheme.typography.bodySmall)
                    }
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondary.copy(alpha = .14f)) {
                        Text("${products.size} items", modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (menu.isNotEmpty()) {
                    menu.take(8).chunked(2).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEach { product ->
                                Card(onClick = { addProduct(product) }, modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Icon(Icons.Default.AddShoppingCart, null, tint = MaterialTheme.colorScheme.primary)
                                        Text(product.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                        Text(money(product.price), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                OutlinedButton(onClick = manual, Modifier.fillMaxWidth()) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("SEARCH MENU / CUSTOM ITEM") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Current Order", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row {
                        if (cart.isNotEmpty()) TextButton(onClick = discount) { Text(if (discountAmount > 0) "CHANGE DISCOUNT" else "DISCOUNT") }
                        if (cart.isNotEmpty()) TextButton(onClick = hold) { Text("HOLD") }
                        if (cart.isNotEmpty()) TextButton(onClick = clear) { Text("Clear") }
                    }
                }
                if (cart.isEmpty()) EmptyState(Icons.Default.ShoppingCart, "No items", "Use ADD PRODUCTS")
                products.forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(item.name, fontWeight = FontWeight.SemiBold); Text("${item.qty} × ${money(item.price)}", style = MaterialTheme.typography.bodySmall) }
                        IconButton(onClick = { changeQty(item, -1) }) { Icon(Icons.Default.Remove, null) }
                        Text(money(item.total), modifier = Modifier.widthIn(min = 76.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        IconButton(onClick = { changeQty(item, 1) }) { Icon(Icons.Default.Add, null) }
                    }
                }
                if (cart.isNotEmpty()) {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("Subtotal"); Text(money(subtotal)) }
                    if (discountAmount > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Discount", color = MaterialTheme.colorScheme.secondary); Text("- ${money(discountAmount)}", color = MaterialTheme.colorScheme.secondary) }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("TOTAL", fontWeight = FontWeight.Bold); Text(money(total), fontWeight = FontWeight.Bold) }
                    Button(onClick = send, Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("CUSTOMER / ORDER TYPE / PRINT") }
                }
            }
        }
    }
}
