package com.findupto.voicepos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun QuickSale(cart: List<SaleItem>, voiceStatus: String, send: () -> Unit, listen: () -> Unit, openSpeechSettings: () -> Unit, clear: () -> Unit, manual: () -> Unit, changeQty: (SaleItem, Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text("Power Voice", fontWeight = FontWeight.Bold); Text(voiceStatus, style = MaterialTheme.typography.bodySmall) }; IconButton(onClick = listen) { Icon(Icons.Default.MicNone, "Listen") } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = manual, Modifier.weight(1f)) { Text("MANUAL ADD") }; OutlinedButton(onClick = listen, Modifier.weight(1f)) { Text("VOICE") } }
            if (voiceStatus.contains("error", true) || voiceStatus.contains("unavailable", true)) OutlinedButton(onClick = openSpeechSettings, Modifier.fillMaxWidth()) { Text("Check speech language / settings") }
            Text("Example: two shawarma price 200", style = MaterialTheme.typography.bodySmall)
        } }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Current Order", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); if (cart.isNotEmpty()) TextButton(onClick = clear) { Text("Clear") } }
            if (cart.isEmpty()) EmptyState(Icons.Default.ShoppingCart, "No items", "Use voice or manual add")
            cart.forEach { item -> Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(item.name, fontWeight = FontWeight.SemiBold); Text("${item.qty} × ${money(item.price)}", style = MaterialTheme.typography.bodySmall) }; IconButton(onClick = { changeQty(item, -1) }) { Icon(Icons.Default.Remove, null) }; Text(money(item.total)); IconButton(onClick = { changeQty(item, 1) }) { Icon(Icons.Default.Add, null) } } }
            if (cart.isNotEmpty()) { HorizontalDivider(); Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("TOTAL", fontWeight = FontWeight.Bold); Text(money(cart.sumOf { it.total }), fontWeight = FontWeight.Bold) }; Button(onClick = send, Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("CUSTOMER DETAILS & PRINT KITCHEN") } }
        } }
    }
}

@Composable
fun ManualCartDialog(menu: List<MenuItem>, add: (MenuItem) -> Unit, addCustom: (String, Int, Double) -> Unit, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }
    var price by remember { mutableStateOf("") }
    val filtered = menu.filter { it.name.contains(query, true) || it.variant.contains(query, true) || it.size.contains(query, true) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Add Product") },
        text = {
  Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search menu") }, singleLine = true)
      filtered.take(20).forEach { item ->
          Card(Modifier.fillMaxWidth().clickable { add(item) }) {
              Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                  Column(Modifier.weight(1f)) { Text(item.name, fontWeight = FontWeight.SemiBold); Text(listOf(item.variant, item.size).filter(String::isNotBlank).joinToString(" • ")); Text(money(item.price)) }
                  Icon(Icons.Default.Add, null)
              }
          }
      }
      HorizontalDivider()
      Text("Custom", fontWeight = FontWeight.Bold)
      OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Product") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(value = qty, onValueChange = { qty = it.filter(Char::isDigit) }, label = { Text("Qty") }, modifier = Modifier.weight(1f), singleLine = true)
          OutlinedTextField(value = price, onValueChange = { price = it }, label = { Text("Price") }, modifier = Modifier.weight(1f), singleLine = true)
      }
      Button(onClick = { val q = qty.toIntOrNull() ?: 0; val p = price.toDoubleOrNull() ?: 0.0; if (name.isNotBlank() && q > 0 && p > 0) { addCustom(name.trim(), q, p); name = ""; price = ""; qty = "1" } }, modifier = Modifier.fillMaxWidth()) { Text("ADD CUSTOM") }
  }
        },
        confirmButton = { TextButton(onClick = close) { Text("DONE") } }
    )
}
@Composable
fun MenuPage(menu: List<MenuItem>, store: Store, refresh: () -> Unit, exportMenu: () -> Unit, importMenu: () -> Unit, scanDocument: () -> Unit, addToCart: (MenuItem) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Button(onClick = { adding = true }, Modifier.weight(1f)) { Text("ADD") }; OutlinedButton(onClick = exportMenu, Modifier.weight(1f)) { Text("EXPORT") }; OutlinedButton(onClick = importMenu, Modifier.weight(1f)) { Text("IMPORT") } }
        Button(onClick = scanDocument, Modifier.fillMaxWidth()) { Icon(Icons.Default.DocumentScanner, null); Spacer(Modifier.width(6.dp)); Text("SMART MENU SCANNER — IMAGE / PDF") }
        LazyColumn { items(menu) { item -> Card(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { addToCart(item) }) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(item.name, fontWeight = FontWeight.SemiBold); Text(listOf(item.variant, item.size).filter(String::isNotBlank).joinToString(" • ")); Text(money(item.price)) }; IconButton(onClick = { store.removeMenuItem(item.id); refresh() }) { Icon(Icons.Default.Delete, "Delete") } } } } }
    }
    if (adding) AddMenuDialog({ n, v, s, p -> store.addMenuItem(MenuItem(System.currentTimeMillis(), n.trim(), p, v.trim(), s.trim())); adding = false; refresh() }) { adding = false }
}

@Composable
fun AddMenuDialog(save: (String, String, String, Double) -> Unit, close: () -> Unit) { var n by remember { mutableStateOf("") }; var v by remember { mutableStateOf("") }; var s by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = close, title = { Text("Product / Variant / Size") }, text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedTextField(n, { n = it }, label = { Text("Product") }); OutlinedTextField(v, { v = it }, label = { Text("Variant") }); OutlinedTextField(s, { s = it }, label = { Text("Size") }); OutlinedTextField(p, { p = it }, label = { Text("Price") }) } }, confirmButton = { TextButton(onClick = { p.toDoubleOrNull()?.takeIf { it > 0 }?.let { if (n.isNotBlank()) save(n, v, s, it) } }) { Text("SAVE") } }, dismissButton = { TextButton(onClick = close) { Text("CANCEL") } }) }

@Composable
fun KitchenQueue(queue: List<PendingSale>, profile: CompanyProfile, printer: PrinterManager, pay: (PendingSale) -> Unit, edit: (PendingSale) -> Unit) { Column(Modifier.fillMaxSize().padding(16.dp)) { Text("Kitchen Queue", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); LazyColumn { items(queue) { order -> Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { edit(order) }) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("#${order.id}", fontWeight = FontWeight.Bold); Text(order.items.joinToString(", ") { "${it.qty}× ${it.name}" }); if (order.customerName.isNotBlank() || order.customerPhone.isNotBlank()) Text(listOf(order.customerName, order.customerPhone).filter(String::isNotBlank).joinToString(" • ")) }; TextButton(onClick = { pay(order) }) { Text("PAY & PRINT") } } } } } } }

@Composable
fun EditDialog(order: PendingSale, save: (PendingSale) -> Unit, close: () -> Unit) { var items by remember { mutableStateOf(order.items) }; AlertDialog(onDismissRequest = close, title = { Text("Edit Order") }, text = { Column { items.forEach { item -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("${item.qty} × ${item.name}", Modifier.weight(1f)); IconButton(onClick = { items = items.map { x -> if (x == item) x.copy(qty = x.qty - 1) else x }.filter { it.qty > 0 } }) { Icon(Icons.Default.Remove, null) } } } } }, confirmButton = { TextButton(onClick = { save(order.copy(items = items)) }) { Text("SAVE") } }, dismissButton = { TextButton(onClick = close) { Text("CLOSE") } }) }

@Composable
fun SalesPage(sales: List<Sale>, open: (Sale) -> Unit) { Column(Modifier.fillMaxSize().padding(16.dp)) { Text("Sales", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); LazyColumn { items(sales.reversed()) { sale -> Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { open(sale) }) { Row(Modifier.padding(12.dp)) { Column(Modifier.weight(1f)) { Text("Receipt #${sale.id}", fontWeight = FontWeight.Bold); if (sale.customerName.isNotBlank() || sale.customerPhone.isNotBlank()) Text(listOf(sale.customerName, sale.customerPhone).filter(String::isNotBlank).joinToString(" • ")); Text(date(sale.time)) }; Text(money(sale.total)) } } } } } }

@Composable
fun SaleDetailDialog(sale: Sale, profile: CompanyProfile, printer: PrinterManager, theme: ReceiptTheme, close: () -> Unit) { AlertDialog(onDismissRequest = close, title = { Text("Receipt #${sale.id}") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { if (sale.customerName.isNotBlank() || sale.customerPhone.isNotBlank()) Text("Customer: ${listOf(sale.customerName, sale.customerPhone).filter(String::isNotBlank).joinToString(" • ")}", fontWeight = FontWeight.Bold); sale.items.forEach { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${it.qty} × ${it.name}"); Text(money(it.total)) } }; HorizontalDivider(); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("TOTAL", fontWeight = FontWeight.Bold); Text(money(sale.total), fontWeight = FontWeight.Bold) } } }, confirmButton = { TextButton(onClick = { printer.printCustomer(customerReceipt(sale, profile, theme)) }) { Text("RE-PRINT") } }, dismissButton = { TextButton(onClick = close) { Text("CLOSE") } }) }

@Composable
fun CustomerDetailsDialog(done: (String, String) -> Unit, close: () -> Unit) { var n by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = close, title = { Text("Customer Details") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(n, { n = it }, label = { Text("Customer Name") }, singleLine = true); OutlinedTextField(p, { p = it }, label = { Text("Phone") }, singleLine = true) } }, confirmButton = { TextButton(onClick = { done(n, p) }) { Text("PRINT KITCHEN") } }, dismissButton = { TextButton(onClick = close) { Text("SKIP") } }) }

@Composable
fun ExpensesPage(expenses: List<Expense>, add: () -> Unit) { Column(Modifier.fillMaxSize().padding(16.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Expenses", style = MaterialTheme.typography.headlineSmall); Button(onClick = add) { Text("ADD") } }; LazyColumn { items(expenses.reversed()) { ListItem(headlineContent = { Text(it.title) }, supportingContent = { Text(it.category) }, trailingContent = { Text(money(it.amount)) }) } } } }

@Composable
fun Analytics(sales: List<Sale>, expenses: List<Expense>, cash: Double) { val sv = sales.sumOf { it.total }; val ev = expenses.sumOf { it.amount }; Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) { Text("Analytics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Metric("Sales", sv, Icons.Default.TrendingUp); Metric("Expenses", ev, Icons.Default.TrendingDown); Metric("Cash", cash, Icons.Default.AccountBalanceWallet); Metric("Net", sv - ev, Icons.Default.AccountBalance) } }
@Composable fun Metric(title: String, value: Double, icon: ImageVector) { Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null); Text(title, Modifier.weight(1f)); Text(money(value), fontWeight = FontWeight.Bold) } } }
@Composable fun EmptyState(icon: ImageVector, title: String, body: String) { Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null); Text(title, fontWeight = FontWeight.Bold); Text(body) } }
@Composable fun OpeningCash(done: (Double) -> Unit) { var v by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = {}, title = { Text("Opening Cash") }, text = { OutlinedTextField(v, { v = it }, label = { Text("Cash") }) }, confirmButton = { TextButton(onClick = { done(v.toDoubleOrNull() ?: 0.0) }) { Text("CONTINUE") } }) }
@Composable fun ExpenseDialog(done: (String, String, Double) -> Unit) { var n by remember { mutableStateOf("") }; var c by remember { mutableStateOf("") }; var a by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = {}, title = { Text("Expense") }, text = { Column { OutlinedTextField(n, { n = it }, label = { Text("Description") }); OutlinedTextField(c, { c = it }, label = { Text("Category") }); OutlinedTextField(a, { a = it }, label = { Text("Amount") }) } }, confirmButton = { TextButton(onClick = { a.toDoubleOrNull()?.let { done(n, c, it) } }) { Text("SAVE") } }) }

@Composable
fun SettingsPage(store: Store, printer: PrinterManager, pickLogo: () -> Unit, exportBackup: () -> Unit, importBackup: () -> Unit, close: () -> Unit) { var profile by remember { mutableStateOf(store.profile()) }; var theme by remember { mutableStateOf(store.theme()) }; AlertDialog(onDismissRequest = close, title = { Text("Settings") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) { Text("Business Transfer", fontWeight = FontWeight.Bold); Row(Modifier.fillMaxWidth()) { Button(onClick = exportBackup, Modifier.weight(1f)) { Text("EXPORT") }; OutlinedButton(onClick = importBackup, Modifier.weight(1f)) { Text("IMPORT") } }; OutlinedTextField(profile.name, { profile = profile.copy(name = it) }, label = { Text("Company Name") }); OutlinedTextField(profile.phone, { profile = profile.copy(phone = it) }, label = { Text("Phone") }); OutlinedTextField(profile.address, { profile = profile.copy(address = it) }, label = { Text("Address") }, minLines = 3); OutlinedTextField(profile.footer, { profile = profile.copy(footer = it) }, label = { Text("Footer") }); Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(if (profile.logoPath.isBlank()) "Company logo not uploaded" else "Company logo uploaded ✓", Modifier.weight(1f)); OutlinedButton(onClick = pickLogo) { Text("UPLOAD / CHANGE LOGO") } }; Text("Receipt style"); Row(Modifier.horizontalScroll(rememberScrollState())) { ReceiptTheme.values().forEach { t -> FilterChip(theme == t, { theme = t }, label = { Text(t.name) }) } } } }, confirmButton = { TextButton(onClick = { store.saveProfile(profile.copy(saleLogoEnabled = true, kitchenLogoEnabled = true)); store.setTheme(theme); close() }) { Text("SAVE") } }) }
