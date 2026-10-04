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
    cart: List<SaleItem>, voiceStatus: String, send: () -> Unit, listen: () -> Unit,
    openSpeechSettings: () -> Unit, clear: () -> Unit, manual: () -> Unit, discount: () -> Unit,
    changeQty: (SaleItem, Int) -> Unit, hold: () -> Unit
) {
    val products = cart.filterNot { it.name.startsWith(CART_DISCOUNT_PREFIX) }
    val discountAmount = cartDiscount(cart)
    val subtotal = cartProductSubtotal(cart)
    val total = cart.sumOf { it.total }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) { Text("Power Voice", fontWeight = FontWeight.Bold); Text(voiceStatus, style = MaterialTheme.typography.bodySmall) }
                    IconButton(onClick = listen) { Icon(Icons.Default.MicNone, "Listen") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = manual, Modifier.weight(1f)) { Text("ADD PRODUCTS") }
                    OutlinedButton(onClick = listen, Modifier.weight(1f)) { Text("VOICE") }
                }
                Text("Multi-add: select many products and quantities in one step. Example: 2 shawarma + 1 fries + 3 tea.", style = MaterialTheme.typography.bodySmall)
                if (voiceStatus.contains("error", true) || voiceStatus.contains("unavailable", true)) OutlinedButton(onClick = openSpeechSettings, Modifier.fillMaxWidth()) { Text("Check speech language / settings") }
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
                if (cart.isEmpty()) EmptyState(Icons.Default.ShoppingCart, "No items", "Use ADD PRODUCTS or voice")
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
