package com.findupto.voicepos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Calendar

internal data class ProductInsight(val name: String, val quantity: Int, val revenue: Double)
internal fun bestSellingProducts(sales: List<Sale>, limit: Int = 5): List<ProductInsight> =
    sales.flatMap { it.items }.filter { it.qty > 0 && !it.name.startsWith(CART_DISCOUNT_PREFIX) }
        .groupBy { canonicalProductKey(it.name) }
        .map { (_, rows) -> ProductInsight(rows.first().name, rows.sumOf { it.qty }, rows.sumOf { it.total }) }
        .sortedWith(compareByDescending<ProductInsight> { it.quantity }.thenByDescending { it.revenue })
        .take(limit)

internal fun averageDailyRevenue(sales: List<Sale>, now: Long = System.currentTimeMillis(), days: Int = 7): Double {
    if (days <= 0) return 0.0
    val start = now - days * 24L * 60L * 60L * 1000L
    return sales.filter { it.time in (start + 1)..now }.sumOf { it.total.coerceAtLeast(0.0) } / days
}

@Composable
fun BusinessInsightsDialog(
    store: Store,
    inventory: InventoryStore,
    sales: List<Sale>,
    close: () -> Unit,
    refresh: () -> Unit
) {
    var tick by remember { mutableIntStateOf(0) }
    var wasteItemId by remember { mutableStateOf("") }
    var wasteQty by remember { mutableStateOf("") }
    var wasteReason by remember { mutableStateOf("Spoilage") }
    var message by remember { mutableStateOf("") }
    val stock = remember(tick) { inventory.items() }
    val now = System.currentTimeMillis()
    val dailyAverage = averageDailyRevenue(sales, now)
    val best = remember(sales) { bestSellingProducts(sales) }
    val menu = remember(tick) { store.menu() }
    val recipes = remember(tick) { inventory.recipes().associateBy { canonicalProductKey(it.productName) } }
    val wasteRows = remember(tick) { inventory.recentMovementDetails(100).filter { it.contains("Waste:") } }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Owner Insights & Forecast") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SALES FORECAST", style = MaterialTheme.typography.titleSmall)
                Text("Last 7 days average: ${money(dailyAverage)} / day", style = MaterialTheme.typography.titleMedium)
                Text("Estimated next 7 days: ${money(dailyAverage * 7)}", color = MaterialTheme.colorScheme.primary)
                Text("Forecast uses a simple recent daily average; it is an estimate, not a guarantee.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("BEST-SELLING PRODUCTS", style = MaterialTheme.typography.titleSmall)
                if (best.isEmpty()) Text("Sales data will appear here after completed sales.")
                best.forEachIndexed { index, item ->
                    Text("${index + 1}. ${item.name} — ${item.quantity} sold • ${money(item.revenue)}")
                }
                HorizontalDivider()
                Text("SMART PRICING (30% target gross margin)", style = MaterialTheme.typography.titleSmall)
                val recommendations = menu.mapNotNull { product ->
                    val cost = inventory.estimatedRecipeCost(listOf(SaleItem(product.name, 1, product.price)))
                    if (cost > 0 && product.price.isFinite() && product.price > 0) Triple(product, cost, cost / 0.70) else null
                }
                if (recommendations.isEmpty()) Text("Add recipe components and stock unit costs to see pricing suggestions.")
                recommendations.forEach { (product, cost, suggested) ->
                    Text("${product.name}: current ${money(product.price)} • estimated cost ${money(cost)} • suggested ${money(suggested)}")
                }
                Text("Suggestions only. Review demand, competitors and costs before changing menu prices.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("RECORD WASTE", style = MaterialTheme.typography.titleSmall)
                if (stock.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    val selected = stock.firstOrNull { it.id.toString() == wasteItemId } ?: stock.first()
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(selected.name, {}, readOnly = true, label = { Text("Stock item") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            stock.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { wasteItemId = item.id.toString(); expanded = false }) }
                        }
                    }
                    OutlinedTextField(wasteQty, { wasteQty = it }, label = { Text("Wasted quantity (${selected.unit})") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(wasteReason, { wasteReason = it }, label = { Text("Reason (e.g. Spoilage)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(onClick = {
                        val qty = wasteQty.toDoubleOrNull()
                        if (qty == null || !qty.isFinite() || qty <= 0 || qty > selected.quantity || wasteReason.isBlank()) {
                            message = "Enter a valid quantity up to ${selected.quantity} ${selected.unit} and a reason"
                        } else if (inventory.adjustStock(selected.id, -qty, "Waste: ${wasteReason.trim()}")) {
                            message = "Waste recorded for ${selected.name}"
                            wasteQty = ""; tick++; refresh()
                        } else message = "Waste could not be saved"
                    }, modifier = Modifier.fillMaxWidth()) { Text("SAVE WASTE RECORD") }
                } else Text("Add inventory items before recording waste.")
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.secondary)
                Text("RECENT WASTE RECORDS", style = MaterialTheme.typography.titleSmall)
                if (wasteRows.isEmpty()) Text("No waste records yet.") else wasteRows.take(10).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("DONE") } }
    )
}
