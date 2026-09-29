package com.findupto.voicepos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun VoicePosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF9B8CFF),
            secondary = androidx.compose.ui.graphics.Color(0xFF42D6A4)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosApp(
    store: Store,
    printer: PrinterManager,
    heard: String,
    voiceStatus: String,
    clearHeard: () -> Unit,
    listen: () -> Unit,
    openSpeechSettings: () -> Unit,
    exportMenu: () -> Unit,
    importMenu: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var cart by remember { mutableStateOf(emptyList<SaleItem>()) }
    var cash by remember { mutableStateOf(store.cash()) }
    var tick by remember { mutableIntStateOf(0) }
    var filter by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<PendingSale?>(null) }
    var settings by remember { mutableStateOf(false) }
    var expense by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(!store.hasOpeningCash()) }
    var selectedSale by remember { mutableStateOf<Sale?>(null) }
    val menu = remember(tick) { store.menu() }

    val profile = remember(tick) { store.profile() }
    val sales = remember(tick) { store.sales() }
    val expenses = remember(tick) { store.expenses() }
    val pending = remember(tick) { store.pending() }

    LaunchedEffect(heard) {
        if (heard.isNotBlank()) {
            VoiceCommandEngine.parse(heard, menu).forEach { command ->
                when (command) {
                    is VoiceCommand.Add -> {
                        val index = cart.indexOfFirst { it.name.equals(command.item.name, true) && it.price == command.item.price }
                        cart = if (index >= 0) {
                            cart.toMutableList().also { list ->
                                list[index] = list[index].copy(qty = list[index].qty + command.item.qty)
                            }
                        } else cart + command.item
                    }
                    VoiceCommand.Clear -> cart = emptyList()
                    VoiceCommand.Sales -> tab = 3
                    VoiceCommand.Expenses -> tab = 4
                    VoiceCommand.Reports -> tab = 5
                    VoiceCommand.Settings -> settings = true
                    VoiceCommand.Menu -> tab = 1
                    VoiceCommand.Kitchen -> tab = 2
                    VoiceCommand.Analytics -> tab = 5
                    VoiceCommand.NewOrder -> cart = emptyList()
                    is VoiceCommand.PayLast -> pending.lastOrNull()?.let { pay(it) }
                    is VoiceCommand.Quantity -> {
                        cart = cart.map { item ->
                            if (item.name.contains(command.name, true)) item.copy(qty = (item.qty + command.delta).coerceAtLeast(0)) else item
                        }.filter { it.qty > 0 }
                    }
                    is VoiceCommand.Remove -> cart = cart.filterNot { it.name.contains(command.name, true) }
                    VoiceCommand.CompletePrint -> {
                        if (cart.isNotEmpty()) {
                            val order = PendingSale(System.currentTimeMillis(), cart, System.currentTimeMillis())
                            store.addPending(order)
                            printer.printKitchen(kitchenReceipt(order, profile, store.theme()))
                            cart = emptyList()
                            tick++
                        }
                    }
                    VoiceCommand.Complete -> Unit
                }
            }
            clearHeard()
        }
    }

    fun sendOrder() {
        if (cart.isEmpty()) return
        val order = PendingSale(System.currentTimeMillis(), cart, System.currentTimeMillis())
        store.addPending(order)
        printer.printKitchen(kitchenReceipt(order, profile, store.theme()))
        cart = emptyList()
        tick++
    }

    fun pay(order: PendingSale) {
        val sale = Sale(order.id, order.items, order.total, System.currentTimeMillis())
        store.addSale(sale)
        cash += sale.total
        store.setCash(cash)
        store.removePending(order.id)
        printer.printCustomer(customerReceipt(sale, profile, store.theme()))
        tick++
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(profile.name.ifBlank { "The Slice of Heaven" }, fontWeight = FontWeight.Bold)
                        Text("VOICE POS • PREMIUM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                },
                actions = {
                    IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                val names = listOf("Sale", "Menu", "Kitchen", "Sales", "Expenses", "Analytics")
                val icons = listOf(Icons.Default.PointOfSale, Icons.Default.MenuBook, Icons.Default.Restaurant, Icons.Default.ReceiptLong, Icons.Default.Payments, Icons.Default.Insights)
                names.forEachIndexed { index, name ->
                    NavigationBarItem(tab == index, { tab = index }, icon = { Icon(icons[index], name) }, label = { Text(name) })
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = listen,
                icon = { Icon(Icons.Default.Mic, "Voice") },
                text = { Text("VOICE") },
                shape = CircleShape
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> QuickSale(cart, voiceStatus, ::sendOrder, listen, openSpeechSettings, { cart = emptyList() }) { item, delta ->
                    cart = cart.map { if (it == item) it.copy(qty = (it.qty + delta).coerceAtLeast(0)) else it }.filter { it.qty > 0 }
                }
                1 -> MenuPage(menu, store, tick = tick, refresh = { tick++ }, exportMenu = exportMenu, importMenu = importMenu) { item ->
                    val index = cart.indexOfFirst { it.name.equals(item.name, true) && it.price == item.price }
                    cart = if (index >= 0) cart.toMutableList().also { list -> list[index] = list[index].copy(qty = list[index].qty + 1) } else cart + SaleItem(item.name, 1, item.price)
                    tab = 0
                }
                2 -> KitchenQueue(pending, profile, printer, ::pay) { editing = it }
                3 -> SalesPage(sales.filter { inRange(it.time, filter) }, filter, { filter = it }, { selectedSale = it })
                4 -> ExpensesPage(expenses.filter { inRange(it.time, filter) }, filter, { filter = it }) { expense = true }
                5 -> Analytics(sales.filter { inRange(it.time, filter) }, expenses.filter { inRange(it.time, filter) }, cash, filter, { filter = it })
            }
        }
    }

    if (opening) OpeningCash {
        cash = it
        store.setOpeningCash(it)
        opening = false
    }
    if (expense) ExpenseDialog { name, category, amount ->
        store.addExpense(Expense(System.currentTimeMillis(), name, category, amount, System.currentTimeMillis()))
        cash -= amount
        store.setCash(cash)
        tick++
        expense = false
    }
    if (settings) SettingsPage(store, printer) { settings = false; tick++ }
    editing?.let { order -> EditDialog(order, { updated -> store.replacePending(updated); editing = null; tick++ }, { editing = null }) }
    selectedSale?.let { sale -> SaleDetailDialog(sale, profile, printer, store.theme()) { selectedSale = null } }
}

@Composable
private fun QuickSale(
    cart: List<SaleItem>,
    voiceStatus: String,
    send: () -> Unit,
    listen: () -> Unit,
    openSpeechSettings: () -> Unit,
    clear: () -> Unit,
    changeQty: (SaleItem, Int) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Voice checkout", fontWeight = FontWeight.Bold)
                        Text("Offline • multi-item orders", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = listen) { Icon(Icons.Default.MicNone, "Listen") }
                }
                AssistChip(onClick = listen, label = { Text(voiceStatus) }, leadingIcon = { Icon(Icons.Default.WifiOff, null) })
                if (voiceStatus.contains("error", true) || voiceStatus.contains("pack", true) || voiceStatus.contains("unavailable", true)) {
                    OutlinedButton(onClick = openSpeechSettings, modifier = Modifier.fillMaxWidth()) { Text("Check / Download Speech Language Pack") }
                }
                Text("Try: 2 shawarma price 300 each and 1 fries price 150 each", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Current order", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (cart.isNotEmpty()) TextButton(onClick = clear) { Text("Clear") }
                }
                if (cart.isEmpty()) {
                    EmptyState(Icons.Default.ShoppingCart, "No items yet", "Use the voice button to add multiple items.")
                } else {
                    cart.forEach { item ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.SemiBold)
                                Text(item.qty.toString() + " × " + money(item.price), style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { changeQty(item, -1) }) { Icon(Icons.Default.RemoveCircleOutline, null) }
                            Text(money(item.total), fontWeight = FontWeight.Bold)
                            IconButton(onClick = { changeQty(item, 1) }) { Icon(Icons.Default.AddCircleOutline, null) }
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), Arrangement.SpaceBetween) {
                        Text("TOTAL", fontWeight = FontWeight.Bold)
                        Text(money(cart.sumOf { it.total }), fontWeight = FontWeight.Bold)
                    }
                    Button(onClick = send, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Icon(Icons.Default.Restaurant, null)
                        Spacer(Modifier.width(6.dp))
                        Text("PRINT KITCHEN & HOLD PAYMENT")
                    }
                }
            }
        }
    }
}


@Composable
private fun MenuPage(menu: List<MenuItem>, store: Store, tick: Int, refresh: () -> Unit, exportMenu: () -> Unit, importMenu: () -> Unit, addToCart: (MenuItem) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { adding = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add Product") }
            OutlinedButton(onClick = exportMenu, modifier = Modifier.weight(1f)) { Text("Download CSV") }
            OutlinedButton(onClick = importMenu, modifier = Modifier.weight(1f)) { Text("Upload CSV") }
        }
        Text("Add multiple rows for the same product to create different variants/sizes and prices.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (menu.isEmpty()) EmptyState(Icons.Default.MenuBook, "No products", "Add products one by one or upload a CSV menu.")
        LazyColumn { items(menu) { item ->
            Card(Modifier.fillMaxWidth().clickable { addToCart(item) }) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontWeight = FontWeight.SemiBold)
                        val details = listOf(item.variant, item.size).filter { it.isNotBlank() }.joinToString(" • ")
                        if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        Text(money(item.price), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { store.removeMenuItem(item.id); refresh() }) { Icon(Icons.Default.Delete, "Delete") }
                }
            }
        } }
    }
    if (adding) AddMenuDialog({ name, variant, size, price ->
        store.addMenuItem(MenuItem(System.currentTimeMillis(), name.trim(), price, variant.trim(), size.trim()))
        adding = false
        refresh()
    }) { adding = false }
}

@Composable
private fun AddMenuDialog(save: (String, String, String, Double) -> Unit, close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var variant by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Add Product / Variant / Size") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Product name") })
            OutlinedTextField(variant, { variant = it }, label = { Text("Variant (e.g. Chicken, Beef)") })
            OutlinedTextField(size, { size = it }, label = { Text("Size (e.g. Small, Medium, Large)") })
            OutlinedTextField(price, { price = it }, label = { Text("Price") })
        } },
        confirmButton = { TextButton(onClick = {
            price.toDoubleOrNull()?.takeIf { it > 0 }?.let { if (name.isNotBlank()) save(name, variant, size, it) }
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } }
    )
}
@Composable
private fun KitchenQueue(queue: List<PendingSale>, profile: CompanyProfile, printer: PrinterManager, pay: (PendingSale) -> Unit, edit: (PendingSale) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text("Kitchen Queue", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(queue.size.toString() + " unpaid orders")
            }
            Button(onClick = { queue.forEach { printer.printKitchen(kitchenReceipt(it, profile, ReceiptTheme.MODERN)) } }, enabled = queue.isNotEmpty()) { Text("Print all") }
        }
        LazyColumn {
            items(queue) { order ->
                Card(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { edit(order) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Restaurant, null)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text("#" + order.id, fontWeight = FontWeight.Bold)
                            Text(order.items.joinToString(", ") { it.qty.toString() + "× " + it.name })
                            Text(money(order.total))
                        }
                        TextButton(onClick = { pay(order) }) { Text("PAY & PRINT") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditDialog(order: PendingSale, save: (PendingSale) -> Unit, close: () -> Unit) {
    var items by remember { mutableStateOf(order.items) }
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Edit order #" + order.id) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                items.forEach { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(item.name); Text(item.qty.toString() + " × " + money(item.price), style = MaterialTheme.typography.bodySmall) }
                        IconButton(onClick = { items = items.map { x -> if (x == item) x.copy(qty = x.qty - 1) else x }.filter { it.qty > 0 } }) { Icon(Icons.Default.Remove, null) }
                    }
                }
                HorizontalDivider()
                OutlinedTextField(name, { name = it }, label = { Text("Add item") })
                OutlinedTextField(price, { price = it }, label = { Text("Price") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = price.toDoubleOrNull()
                save(order.copy(items = if (name.isNotBlank() && value != null) items + SaleItem(name, 1, value) else items))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } }
    )
}

@Composable
private fun SalesPage(sales: List<Sale>, filter: Int, setFilter: (Int) -> Unit, open: (Sale) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Paid Sales", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        FilterRow(filter, setFilter)
        if (sales.isEmpty()) EmptyState(Icons.Default.ReceiptLong, "No sales", "Completed receipts will appear here.")
        LazyColumn {
            items(sales.reversed()) { sale ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { open(sale) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ReceiptLong, null)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("Receipt #" + sale.id, fontWeight = FontWeight.Bold)
                            Text(sale.items.sumOf { it.qty }.toString() + " items • " + date(sale.time), style = MaterialTheme.typography.bodySmall)
                            Text(money(sale.total), fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = { open(sale) }) { Text("DETAILS") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaleDetailDialog(sale: Sale, profile: CompanyProfile, printer: PrinterManager, theme: ReceiptTheme, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Receipt #" + sale.id) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(date(sale.time), style = MaterialTheme.typography.bodySmall)
                sale.items.forEach { item ->
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.SemiBold)
                            Text(item.qty.toString() + " × " + money(item.price), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(money(item.total), fontWeight = FontWeight.SemiBold)
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("TOTAL", fontWeight = FontWeight.Bold)
                    Text(money(sale.total), fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = { TextButton(onClick = { printer.printCustomer(customerReceipt(sale, profile, theme)) }) { Text("RE-PRINT") } },
        dismissButton = { TextButton(onClick = close) { Text("Close") } }
    )
}

@Composable
private fun ExpensesPage(expenses: List<Expense>, filter: Int, setFilter: (Int) -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Expenses", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Button(onClick = add) { Text("Add") }
        }
        FilterRow(filter, setFilter)
        if (expenses.isEmpty()) EmptyState(Icons.Default.Payments, "No expenses", "Track shop expenses here.")
        LazyColumn {
            items(expenses.reversed()) {
                ListItem(
                    headlineContent = { Text(it.title) },
                    supportingContent = { Text(it.category + " • " + date(it.time)) },
                    trailingContent = { Text(money(it.amount), fontWeight = FontWeight.Bold) }
                )
            }
        }
    }
}

@Composable
private fun FilterRow(filter: Int, setFilter: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf("Today", "7 days", "30 days", "All").forEachIndexed { i, n ->
            FilterChip(filter == i, { setFilter(i) }, label = { Text(n) })
        }
    }
}

@Composable
private fun Analytics(sales: List<Sale>, expenses: List<Expense>, cash: Double, filter: Int, setFilter: (Int) -> Unit) {
    val salesValue = sales.sumOf { it.total }
    val expenseValue = expenses.sumOf { it.amount }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Analytics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        FilterRow(filter, setFilter)
        Metric("Sales", salesValue, Icons.Default.TrendingUp)
        Metric("Expenses", expenseValue, Icons.Default.TrendingDown)
        Metric("Cash", cash, Icons.Default.AccountBalanceWallet)
        Metric("Net", salesValue - expenseValue, Icons.Default.AccountBalance)
    }
}

@Composable
private fun Metric(title: String, value: Double, icon: ImageVector) {
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
            Text(title, Modifier.weight(1f))
            Text(money(value), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(40.dp))
        Text(title, fontWeight = FontWeight.Bold)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OpeningCash(done: (Double) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Opening Cash") },
        text = { OutlinedTextField(value, { value = it }, label = { Text("Cash in hand") }) },
        confirmButton = { TextButton(onClick = { done(value.toDoubleOrNull() ?: 0.0) }) { Text("Continue") } }
    )
}

@Composable
private fun ExpenseDialog(done: (String, String, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Add Expense") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Description") })
                OutlinedTextField(category, { category = it }, label = { Text("Category") })
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount") })
            }
        },
        confirmButton = { TextButton(onClick = { amount.toDoubleOrNull()?.let { done(name, category, it) } }) { Text("Save") } },
        dismissButton = { TextButton(onClick = {}) { Text("Close") } }
    )
}

@Composable
private fun SettingsPage(store: Store, printer: PrinterManager, close: () -> Unit) {
    var profile by remember { mutableStateOf(store.profile()) }
    var theme by remember { mutableStateOf(store.theme()) }
    var customer by remember { mutableStateOf(store.customerPrinterAddress()) }
    var kitchen by remember { mutableStateOf(store.kitchenPrinterAddress()) }

    AlertDialog(
        onDismissRequest = close,
        title = { Text("Premium Print Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Company", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(profile.name, { profile = profile.copy(name = it) }, label = { Text("Slip header") })
                OutlinedTextField(profile.address, { profile = profile.copy(address = it) }, label = { Text("Address") }, minLines = 3)
                OutlinedTextField(profile.phone, { profile = profile.copy(phone = it) }, label = { Text("Phone") })
                OutlinedTextField(profile.footer, { profile = profile.copy(footer = it) }, label = { Text("Slip footer") })
                Text("Receipt style", fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ReceiptTheme.values().forEach { t -> FilterChip(theme == t, { theme = t }, label = { Text(t.name) }) }
                }
                Text("Bluetooth printers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Button(onClick = { printer.discover() }) { Text(if (printer.discovering) "Discovering…" else "Find Bluetooth printers") }
                printer.devices.forEach { device ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(device.name ?: device.address, Modifier.weight(1f), maxLines = 1)
                        TextButton(onClick = { customer = device.address }) { Text(if (customer == device.address) "Receipt ✓" else "Receipt") }
                        TextButton(onClick = { kitchen = device.address }) { Text(if (kitchen == device.address) "Kitchen ✓" else "Kitchen") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                store.saveProfile(profile)
                store.setTheme(theme)
                store.setCustomerPrinterAddress(customer)
                store.setKitchenPrinterAddress(kitchen)
                printer.device(customer)?.let { printer.connectCustomer(it) }
                printer.device(kitchen)?.let { printer.connectKitchen(it) }
                close()
            }) { Text("Save") }
        }
    )
}
