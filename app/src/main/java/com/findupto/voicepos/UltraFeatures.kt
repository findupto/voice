@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.findupto.voicepos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.concurrent.thread
import java.text.SimpleDateFormat
import java.util.*

private val PAYMENT_METHODS = listOf("CASH", "CARD", "EASYPAISA", "JAZZCASH", "BANK", "RAAST", "OTHER")
private fun fmt(v: Double) = String.format(Locale.US, "%,.0f", v)

@Composable
fun SettlementDialog(order: PendingSale, grandTotal: Double, onConfirm: (String, Double, Double) -> Unit, onCancel: () -> Unit) {
    var method by remember { mutableStateOf("CASH") }
    var paid by remember { mutableStateOf(fmt(grandTotal)) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Settle Order #${order.id}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Total ${CurrencySettings.symbol} ${fmt(grandTotal)}", fontWeight = FontWeight.Bold)
                if (order.orderType != "DINE_IN") Text(order.orderType.replace('_', ' '), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    PAYMENT_METHODS.take(5).forEach { m -> FilterChip(selected = method == m, onClick = { method = m }, label = { Text(m) }) }
                }
                OutlinedTextField(paid, { paid = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Amount paid") }, singleLine = true)
                val p = paid.toDoubleOrNull() ?: 0.0
                val due = (grandTotal - p).coerceAtLeast(0.0)
                if (due > 0) Text("Due balance: ${CurrencySettings.symbol} ${fmt(due)}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = { Button(onClick = { val p = (paid.toDoubleOrNull() ?: 0.0).coerceIn(0.0, grandTotal); onConfirm(method, p, (grandTotal - p).coerceAtLeast(0.0)) }) { Text("SAVE PAYMENT") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("CANCEL") } }
    )
}

@Composable
fun UltraControlCenter(store: Store, onClose: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var dueSelected by remember { mutableStateOf<DueAccount?>(null) }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().heightIn(max = 820.dp)) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ULTRA CONTROL CENTER", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("CLOSE") }
                }
                ScrollableTabRow(selectedTabIndex = tab) {
                    listOf("SHIFT", "MONEY", "PRODUCTS", "OPS", "TEAM", "TABLES", "AI").forEachIndexed { i, title ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                    }
                }
                when (tab) {
                    0 -> ShiftPanel(store) { tick++ }
                    1 -> MoneyPanel(store) { dueSelected = it }
                    2 -> ProductPanel(store)
                    3 -> OpsPanel { tick++ }
                    4 -> TeamPanel { tick++ }
                    5 -> TablePanel { tick++ }
                    6 -> AiPanel(store)
                }
            }
        }
    }
    dueSelected?.let { d ->
        DuePaymentDialog(d, { amount, method -> if (store.recordPayment(d.id, amount, method, store.activeShift()?.id ?: 0L)) tick++; dueSelected = null }, { dueSelected = null })
    }
}

@Composable
private fun ShiftPanel(store: Store, refresh: () -> Unit) {
    val s = store.activeShift()
    var person by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf("0") }
    var closing by remember { mutableStateOf("0") }
    var closeMode by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s == null) {
            Text("No active shift", fontWeight = FontWeight.Bold)
            OutlinedTextField(person, { person = it }, label = { Text("Cashier / shift person") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(opening, { opening = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Opening cash") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if (person.isNotBlank()) { store.openShift(person, opening.toDoubleOrNull() ?: 0.0); refresh() } }, modifier = Modifier.fillMaxWidth()) { Text("START SHIFT") }
        } else {
            Text("ACTIVE SHIFT", fontWeight = FontWeight.Bold)
            Text("${s.person} • ${SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(Date(s.openedAt))}")
            Text("Opening cash: ${CurrencySettings.symbol} ${fmt(s.openingCash)}")
            Text("Expected cash: ${CurrencySettings.symbol} ${fmt(store.expectedShiftCash(s.id))}")
            Text("Sales: ${CurrencySettings.symbol} ${fmt(store.shiftSales(s.id).sumOf { it.total })}")
            Text("Expenses: ${CurrencySettings.symbol} ${fmt(store.shiftExpenses(s.id).sumOf { it.amount })}")
            Button(onClick = { closeMode = true }, modifier = Modifier.fillMaxWidth()) { Text("CLOSE / HANDOVER SHIFT") }
        }
    }
    if (closeMode && s != null) AlertDialog(
        onDismissRequest = { closeMode = false }, title = { Text("Close Shift") },
        text = { OutlinedTextField(closing, { closing = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Counted cash") }) },
        confirmButton = { Button(onClick = { store.closeShift(s.id, closing.toDoubleOrNull() ?: 0.0); closeMode = false; refresh() }) { Text("CLOSE SHIFT") } },
        dismissButton = { TextButton(onClick = { closeMode = false }) { Text("CANCEL") } }
    )
}

@Composable
private fun MoneyPanel(store: Store, onDue: (DueAccount) -> Unit) {
    val totals = store.paymentTotals()
    val dues = store.dues()
    Column(Modifier.padding(top = 12.dp)) {
        Text("PAYMENT ACCOUNTS", fontWeight = FontWeight.Bold)
        PAYMENT_METHODS.forEach { Text("$it   ${CurrencySettings.symbol} ${fmt(totals[it] ?: 0.0)}", Modifier.padding(vertical = 3.dp)) }
        Text("Outstanding dues: ${CurrencySettings.symbol} ${fmt(store.dueTotal())}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        LazyColumn(Modifier.heightIn(max = 300.dp)) {
            items(dues) { d ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(d.customerName.ifBlank { "Walk-in" }, fontWeight = FontWeight.Bold); Text("Sale #${d.saleId} • Due ${CurrencySettings.symbol} ${fmt(d.balance)}") }
                        Button(onClick = { onDue(d) }) { Text("PAY") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DuePaymentDialog(d: DueAccount, onDone: (Double, String) -> Unit, onCancel: () -> Unit) {
    var amount by remember { mutableStateOf(fmt(d.balance)) }
    var method by remember { mutableStateOf("CASH") }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Receive Due Payment") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${d.customerName.ifBlank { "Customer" }} • Balance ${CurrencySettings.symbol} ${fmt(d.balance)}")
            OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Amount") })
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { PAYMENT_METHODS.take(5).forEach { m -> FilterChip(selected = method == m, onClick = { method = m }, label = { Text(m) }) } }
        }
    }, confirmButton = { Button(onClick = { onDone((amount.toDoubleOrNull() ?: 0.0).coerceIn(0.0, d.balance), method) }) { Text("RECORD PAYMENT") } }, dismissButton = { TextButton(onClick = onCancel) { Text("CANCEL") } })
}

@Composable
private fun ProductPanel(store: Store) {
    var range by remember { mutableIntStateOf(0) }
    val now = System.currentTimeMillis()
    val start = when (range) { 0 -> StoreDay.start(); 1 -> now - 7 * 86400000L; 2 -> now - 30 * 86400000L; else -> 0L }
    Column(Modifier.padding(top = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("TODAY", "7 DAYS", "30 DAYS", "ALL").forEachIndexed { i, t -> FilterChip(selected = range == i, onClick = { range = i }, label = { Text(t) }) } }
        Text("PRODUCT QUANTITY SOLD", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        LazyColumn(Modifier.heightIn(max = 430.dp)) { items(store.productTotals(start)) { pair -> Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Text(pair.first, Modifier.weight(1f)); Text(pair.second.toString(), fontWeight = FontWeight.Bold) } } }
    }
}

@Composable
private fun OpsPanel(refresh: () -> Unit) {
    val context = LocalContext.current
    val fs = remember(context) { PremiumFeatureStore(context) }
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScrollableTabRow(selectedTabIndex = tab) { listOf("FAVORITES", "HELD", "INVENTORY", "LOYALTY", "GIFT").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) } }
        when (tab) {
            0 -> FavoritesPanel(fs, refresh)
            1 -> HeldPanel(fs, refresh)
            2 -> InventoryPanel(fs, refresh)
            3 -> LoyaltyPanel(fs)
            4 -> GiftPanel(fs, refresh)
        }
    }
}

@Composable private fun FavoritesPanel(fs: PremiumFeatureStore, refresh: () -> Unit) {
    val menu = remember { mutableStateOf(emptyList<MenuItem>()) }
    Text("FAST SELLERS / FAVORITES", fontWeight = FontWeight.Bold)
    Text("Favorites are saved on this device and are ready for a future one-tap quick-order grid.", style = MaterialTheme.typography.bodySmall)
    Text("Manage favorites from the Menu screen in the next quick-order layout.")
}

@Composable private fun HeldPanel(fs: PremiumFeatureStore, refresh: () -> Unit) {
    Text("HELD ORDERS", fontWeight = FontWeight.Bold)
    LazyColumn(Modifier.heightIn(max = 430.dp)) {
        items(fs.heldOrders()) { h -> Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(h.name, fontWeight = FontWeight.Bold); Text(h.items.joinToString(", ") { "${it.qty}× ${it.name}" }) }; TextButton(onClick = { fs.removeHeld(h.id); refresh() }) { Text("DELETE") } } } }
    }
    if (fs.heldOrders().isEmpty()) Text("No held orders.", style = MaterialTheme.typography.bodySmall)
}

@Composable private fun InventoryPanel(fs: PremiumFeatureStore, refresh: () -> Unit) {
    var name by remember { mutableStateOf("") }; var stock by remember { mutableStateOf("") }; var low by remember { mutableStateOf("5") }
    Text("INVENTORY / LOW STOCK", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Ingredient / SKU") }, modifier = Modifier.weight(1f)); OutlinedTextField(stock, { stock = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Stock") }, modifier = Modifier.weight(1f)) }
    OutlinedTextField(low, { low = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Low at") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = { val v = stock.toDoubleOrNull(); if (v != null && name.isNotBlank()) { fs.upsertStock(StockItem(name, v, low.toDoubleOrNull() ?: 5.0, "unit")); name = ""; stock = ""; refresh() } }, modifier = Modifier.fillMaxWidth()) { Text("SAVE STOCK") }
    LazyColumn(Modifier.heightIn(max = 300.dp)) { items(fs.stock()) { x -> ListItem(headlineContent = { Text(x.name) }, supportingContent = { Text("${x.stock} ${x.unit} • reorder at ${x.lowAt}") }, trailingContent = { if (x.stock <= x.lowAt) Text("LOW", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }) } }
}

@Composable private fun LoyaltyPanel(fs: PremiumFeatureStore) {
    Text("LOYALTY / CUSTOMER VALUE", fontWeight = FontWeight.Bold)
    val customers = fs.loyalty().sortedByDescending { it.spent }
    LazyColumn(Modifier.heightIn(max = 470.dp)) { items(customers) { x -> ListItem(headlineContent = { Text(x.name) }, supportingContent = { Text("${x.phone} • ${x.visits} visits • ${x.points} points") }, trailingContent = { Text(money(x.spent)) }) } }
    if (customers.isEmpty()) Text("Customers earn points automatically when a phone number is captured at checkout.")
}

@Composable private fun GiftPanel(fs: PremiumFeatureStore, refresh: () -> Unit) {
    var code by remember { mutableStateOf("") }; var balance by remember { mutableStateOf("") }
    Text("GIFT CARD / STORE CREDIT LEDGER", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedTextField(code, { code = it }, label = { Text("Code") }, modifier = Modifier.weight(1f)); OutlinedTextField(balance, { balance = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Balance") }, modifier = Modifier.weight(1f)) }
    Button(onClick = { balance.toDoubleOrNull()?.let { fs.setGift(code, it); code = ""; balance = ""; refresh() } }, modifier = Modifier.fillMaxWidth()) { Text("SAVE GIFT CARD") }
    fs.giftBalance().forEach { (c, b) -> ListItem(headlineContent = { Text(c) }, trailingContent = { Text(money(b), fontWeight = FontWeight.Bold) }) }
}

@Composable
private fun TeamPanel(refresh: () -> Unit) {
    val context = LocalContext.current; val fs = remember(context) { PremiumFeatureStore(context) }
    var name by remember { mutableStateOf("") }; var role by remember { mutableStateOf("CASHIER") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("STAFF & PERMISSIONS", fontWeight = FontWeight.Bold); Text("OWNER • MANAGER • CASHIER • KITCHEN", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Staff name") }, modifier = Modifier.weight(1f)); OutlinedTextField(role, { role = it.uppercase() }, label = { Text("Role") }, modifier = Modifier.weight(1f)) }
        Button(onClick = { if (name.isNotBlank()) { fs.upsertStaff(StaffMember(System.currentTimeMillis(), name, role)); name = ""; refresh() } }, modifier = Modifier.fillMaxWidth()) { Text("ADD STAFF") }
        LazyColumn(Modifier.heightIn(max = 400.dp)) { items(fs.staff()) { x -> Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(x.name, fontWeight = FontWeight.Bold); Text(x.role) }; TextButton(onClick = { fs.removeStaff(x.id); refresh() }) { Text("REMOVE") } } } } }
    }
}

@Composable
private fun TablePanel(refresh: () -> Unit) {
    val context = LocalContext.current; val fs = remember(context) { PremiumFeatureStore(context) }
    LaunchedEffect(Unit) { fs.ensureTables(12) }
    val tables = fs.tables()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("TABLE / FLOOR CONTROL", fontWeight = FontWeight.Bold); Text("Open and clear tables without leaving the POS.", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.heightIn(max = 500.dp)) { items(tables) { t -> val next = if (t.status == "AVAILABLE") "OCCUPIED" else "AVAILABLE"; Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(t.label, fontWeight = FontWeight.Bold); Text(t.status) }; Button(onClick = { fs.updateTable(t.copy(status = next)); refresh() }) { Text(if (t.status == "AVAILABLE") "OPEN" else "CLEAR") } } } } }
    }
}

@Composable
private fun AiPanel(store: Store) {
    var q by remember { mutableStateOf("") }; var answer by remember { mutableStateOf("Ask about sales, products, dues, payments, shifts or business performance.") }; var busy by remember { mutableStateOf(false) }; val main = android.os.Handler(android.os.Looper.getMainLooper())
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("BUILT-IN AI ASSISTANT", fontWeight = FontWeight.Bold)
        Text("AI is an assistant; accounting totals remain calculated from POS transaction records.")
        OutlinedTextField(q, { q = it }, label = { Text("Ask your business assistant") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Button(enabled = !busy, onClick = { busy = true; val query = q; thread { val local = when { query.contains("today", true) -> "Today gross sales: ${CurrencySettings.symbol} ${fmt(store.grossSales(StoreDay.start(), System.currentTimeMillis()))}. Realized: ${CurrencySettings.symbol} ${fmt(store.realizedSales(StoreDay.start(), System.currentTimeMillis()))}. Expenses: ${CurrencySettings.symbol} ${fmt(store.totalExpenses(StoreDay.start(), System.currentTimeMillis()))}. Due: ${CurrencySettings.symbol} ${fmt(store.dueTotal())}."; query.contains("top", true) || query.contains("best", true) -> store.productTotals().take(8).joinToString(", ") { "${it.first} ${it.second}" }; query.contains("payment", true) || query.contains("account", true) -> store.paymentTotals().entries.joinToString(", ") { "${it.key}: ${CurrencySettings.symbol} ${fmt(it.value)}" }; else -> null }; val ai = if (local == null) AiEngine.rewriteVoiceBlocking(query, store.menu()) else null; main.post { answer = ai ?: local ?: "AI availability depends on the Android device/model. Local analytics remain available."; busy = false } } }) { Text(if (busy) "THINKING…" else "ASK AI") }
        Text(answer)
    }
}

object StoreDay { fun start(): Long = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }
