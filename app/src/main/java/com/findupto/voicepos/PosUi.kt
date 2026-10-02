package com.findupto.voicepos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun VoicePosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF9B8CFF), secondary = Color(0xFF42D6A4)), content = content)
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
    importMenu: () -> Unit,
    pickLogo: () -> Unit,
    scanDocument: () -> Unit,
    exportBackup: () -> Unit,
    importBackup: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var cart by remember { mutableStateOf(emptyList<SaleItem>()) }
    var cash by remember { mutableStateOf(store.cash()) }
    var tick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<PendingSale?>(null) }
    var settings by remember { mutableStateOf(false) }
    var expense by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(!store.hasOpeningCash()) }
    var selectedSale by remember { mutableStateOf<Sale?>(null) }
    var manualCart by remember { mutableStateOf(false) }
    var customerDialog by remember { mutableStateOf(false) }
    var customerName by remember { mutableStateOf("") }
    var customerPhone by remember { mutableStateOf("") }

    val menu = remember(tick) { store.menu() }
    val profile = remember(tick) { store.profile() }
    val sales = remember(tick) { store.sales() }
    val expenses = remember(tick) { store.expenses() }
    val pending = remember(tick) { store.pending() }

    fun addToCart(item: SaleItem) {
        val index = cart.indexOfFirst { it.name.equals(item.name, true) && it.price == item.price }
        cart = if (index >= 0) cart.toMutableList().also { it[index] = it[index].copy(qty = it[index].qty + item.qty) } else cart + item
    }

    fun pay(order: PendingSale) {
        val sale = Sale(order.id, order.items, order.total, System.currentTimeMillis(), order.customerName, order.customerPhone)
        store.addSale(sale)
        cash += sale.total
        store.setCash(cash)
        store.removePending(order.id)
        printer.printCustomer(customerReceipt(sale, profile, store.theme()))
        tick++
    }

    LaunchedEffect(heard) {
        if (heard.isNotBlank()) {
            VoiceCommandEngine.parse(heard, menu).forEach { command ->
                when (command) {
                    is VoiceCommand.Add -> addToCart(command.item)
                    VoiceCommand.Clear, VoiceCommand.NewOrder -> cart = emptyList()
                    VoiceCommand.Sales -> tab = 3
                    VoiceCommand.Expenses -> tab = 4
                    VoiceCommand.Reports, VoiceCommand.Analytics -> tab = 5
                    VoiceCommand.Settings -> settings = true
                    VoiceCommand.Menu -> tab = 1
                    VoiceCommand.Kitchen -> tab = 2
                    is VoiceCommand.PayLast -> pending.lastOrNull()?.let(::pay)
                    is VoiceCommand.Quantity -> cart = cart.map { if (it.name.contains(command.name, true)) it.copy(qty = (it.qty + command.delta).coerceAtLeast(0)) else it }.filter { it.qty > 0 }
                    is VoiceCommand.Remove -> cart = cart.filterNot { it.name.contains(command.name, true) }
                    VoiceCommand.CompletePrint -> if (cart.isNotEmpty()) {
                        val order = PendingSale(System.currentTimeMillis(), cart, System.currentTimeMillis(), customerName, customerPhone)
                        store.addPending(order)
                        printer.printKitchen(kitchenReceipt(order, profile, store.theme()))
                        cart = emptyList()
                        tick++
                    }
                    VoiceCommand.Complete -> Unit
                }
            }
            clearHeard()
        }
    }

    fun sendOrder() { if (cart.isNotEmpty()) customerDialog = true }
    fun saveCustomer(name: String, phone: String) {
        customerName = name.trim()
        customerPhone = phone.trim()
        val order = PendingSale(System.currentTimeMillis(), cart, System.currentTimeMillis(), customerName, customerPhone)
        store.addPending(order)
        printer.printKitchen(kitchenReceipt(order, profile, store.theme()))
        cart = emptyList()
        tick++
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(profile.name.ifBlank { "VOICE POS" }, fontWeight = FontWeight.Bold); Text("PREMIUM POS", style = MaterialTheme.typography.labelSmall) } },
                actions = { IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Settings") } }
            )
        },
        bottomBar = {
            NavigationBar {
                val names = listOf("Sale", "Menu", "Kitchen", "Sales", "Expenses", "Analytics")
                val icons = listOf(Icons.Default.PointOfSale, Icons.Default.MenuBook, Icons.Default.Restaurant, Icons.Default.ReceiptLong, Icons.Default.Payments, Icons.Default.Insights)
                names.forEachIndexed { index, name -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icons[index], name) }, label = { Text(name) }) }
            }
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = listen, icon = { Icon(Icons.Default.Mic, "Voice") }, text = { Text("VOICE") }, shape = CircleShape) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> QuickSale(cart, voiceStatus, ::sendOrder, listen, openSpeechSettings, { cart = emptyList() }, { manualCart = true }) { item, delta -> cart = cart.map { if (it == item) it.copy(qty = (it.qty + delta).coerceAtLeast(0)) else it }.filter { it.qty > 0 } }
                1 -> MenuPage(menu, store, { tick++ }, exportMenu, importMenu, scanDocument) { addToCart(SaleItem(it.name, 1, it.price)); tab = 0 }
                2 -> KitchenQueue(pending, profile, printer, ::pay) { editing = it }
                3 -> SalesPage(sales, { selectedSale = it })
                4 -> ExpensesPage(expenses) { expense = true }
                5 -> Analytics(sales, expenses, cash)
            }
        }
    }

    if (opening) OpeningCash { cash = it; store.setOpeningCash(it); opening = false }
    if (expense) ExpenseDialog { name, category, amount -> store.addExpense(Expense(System.currentTimeMillis(), name, category, amount, System.currentTimeMillis())); cash -= amount; store.setCash(cash); tick++; expense = false }
    if (settings) SettingsPage(store, printer, pickLogo, exportBackup, importBackup) { settings = false; tick++ }
    if (customerDialog) CustomerDetailsDialog({ name, phone -> saveCustomer(name, phone); customerDialog = false }, { customerDialog = false })
    editing?.let { order -> EditDialog(order, { updated -> store.replacePending(updated); editing = null; tick++ }, { editing = null }) }
    selectedSale?.let { sale -> SaleDetailDialog(sale, profile, printer, store.theme()) { selectedSale = null } }
    if (manualCart) ManualCartDialog(menu, { addToCart(SaleItem(it.name, 1, it.price)) }, { name, qty, price -> addToCart(SaleItem(name, qty, price)) }) { manualCart = false }
}
