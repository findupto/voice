package com.findupto.voicepos

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

data class SaleItem(val name: String, val qty: Int, val price: Double) {
    val total: Double get() = qty * price
}
data class Sale(val id: Long, val items: List<SaleItem>, val total: Double, val time: Long)
data class Expense(val id: Long, val title: String, val amount: Double, val time: Long)

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("pos", Context.MODE_PRIVATE)

    fun cash(): Double = prefs.getString("cash", "0")?.toDoubleOrNull() ?: 0.0
    fun setCash(value: Double) = prefs.edit().putString("cash", value.toString()).apply()
    fun hasOpeningCash(): Boolean = prefs.getBoolean("opening_set", false)
    fun setOpeningCash(value: Double) {
        setCash(value)
        prefs.edit().putBoolean("opening_set", true).apply()
    }
    fun company(): String = prefs.getString("company", "My Restaurant") ?: "My Restaurant"
    fun setCompany(value: String) = prefs.edit().putString("company", value).apply()

    fun sales(): List<Sale> {
        val raw = prefs.getString("sales", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            runCatching {
                val parts = line.split("~")
                val items = parts[1].split("|").filter { it.isNotBlank() }.map { item ->
                    val p = item.split("^")
                    SaleItem(p[0], p[1].toInt(), p[2].toDouble())
                }
                Sale(parts[0].toLong(), items, parts[2].toDouble(), parts[3].toLong())
            }.getOrNull()
        }
    }

    fun addSale(sale: Sale) {
        val all = sales() + sale
        val raw = all.joinToString("\n") { saleValue ->
            saleValue.id.toString() + "~" +
                saleValue.items.joinToString("|") { "${it.name}^${it.qty}^${it.price}" } +
                "~${saleValue.total}~${saleValue.time}"
        }
        prefs.edit().putString("sales", raw).apply()
    }

    fun expenses(): List<Expense> {
        val raw = prefs.getString("expenses", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            runCatching {
                val p = line.split("~")
                Expense(p[0].toLong(), p[1], p[2].toDouble(), p[3].toLong())
            }.getOrNull()
        }
    }

    fun addExpense(expense: Expense) {
        val raw = (expenses() + expense).joinToString("\n") {
            "${it.id}~${it.title}~${it.amount}~${it.time}"
        }
        prefs.edit().putString("expenses", raw).apply()
    }
}

class PrinterManager {
    private var socket: BluetoothSocket? = null
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun devices(): List<BluetoothDevice> {
        return runCatching {
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun connect(device: BluetoothDevice): Boolean {
        return runCatching {
            socket?.close()
            socket = device.createRfcommSocketToServiceRecord(spp)
            socket!!.connect()
            true
        }.getOrDefault(false)
    }

    fun print(data: ByteArray): Boolean {
        return runCatching {
            val output = socket?.outputStream ?: return false
            output.write(data)
            output.flush()
            true
        }.getOrDefault(false)
    }

    fun connected(): Boolean = socket?.isConnected == true
    fun close() = runCatching { socket?.close() }
}

class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private lateinit var printer: PrinterManager
    private val heard = mutableStateOf("")

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    private val speech = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        heard.value = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?: ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        printer = PrinterManager()

        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        }
        permissions.launch(list.toTypedArray())

        setContent {
            MaterialTheme {
                PosApp(
                    store = store,
                    printer = printer,
                    heard = heard.value,
                    clearHeard = { heard.value = "" },
                    listen = ::listen
                )
            }
        }
    }

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-PK")
        speech.launch(intent)
    }

    override fun onDestroy() {
        printer.close()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosApp(
    store: Store,
    printer: PrinterManager,
    heard: String,
    clearHeard: () -> Unit,
    listen: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var cart by remember { mutableStateOf(emptyList<SaleItem>()) }
    var cash by remember { mutableStateOf(store.cash()) }
    var refresh by remember { mutableIntStateOf(0) }
    var expenseDialog by remember { mutableStateOf(false) }
    var settingsDialog by remember { mutableStateOf(false) }
    var openingDialog by remember { mutableStateOf(!store.hasOpeningCash()) }
    var expenseName by remember { mutableStateOf("") }
    var expenseAmount by remember { mutableStateOf("") }

    val sales = remember(refresh) { store.sales() }
    val expenses = remember(refresh) { store.expenses() }

    LaunchedEffect(heard) {
        if (heard.isNotBlank()) {
            cart = parseVoice(heard, cart)
            if (heard.contains("complete sale", ignoreCase = true) && cart.isNotEmpty()) {
                val sale = Sale(
                    System.currentTimeMillis(),
                    cart,
                    cart.sumOf { it.total },
                    System.currentTimeMillis()
                )
                store.addSale(sale)
                cash += sale.total
                store.setCash(cash)
                printer.print(receipt(sale, store.company()))
                cart = emptyList()
                refresh++
            }
            clearHeard()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice POS") },
                actions = {
                    IconButton(onClick = { settingsDialog = true }) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                val labels = listOf("Sale", "Sales", "Expenses", "Reports")
                val icons = listOf(
                    Icons.Default.PointOfSale,
                    Icons.Default.ReceiptLong,
                    Icons.Default.Payments,
                    Icons.Default.BarChart
                )
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icons[index], label) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            when (tab) {
                0 -> SaleScreen(cart, listen, store.company(), printer)
                1 -> SalesScreen(sales, store.company(), printer)
                2 -> ExpensesScreen(expenses) { expenseDialog = true }
                3 -> ReportsScreen(
                    sales.sumOf { it.total },
                    expenses.sumOf { it.amount },
                    cash
                )
            }
        }
    }

    if (expenseDialog) {
        AlertDialog(
            onDismissRequest = { expenseDialog = false },
            title = { Text("Add Expense") },
            text = {
                Column {
                    OutlinedTextField(
                        value = expenseName,
                        onValueChange = { expenseName = it },
                        label = { Text("Description") }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = expenseAmount,
                        onValueChange = { expenseAmount = it },
                        label = { Text("Amount") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val amount = expenseAmount.toDoubleOrNull()
                    if (amount != null && amount > 0) {
                        store.addExpense(
                            Expense(
                                System.currentTimeMillis(),
                                expenseName,
                                amount,
                                System.currentTimeMillis()
                            )
                        )
                        cash -= amount
                        store.setCash(cash)
                        refresh++
                    }
                    expenseName = ""
                    expenseAmount = ""
                    expenseDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { expenseDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (settingsDialog) {
        SettingsDialog(store, printer) { settingsDialog = false }
    }

    if (openingDialog) {
        OpeningCashDialog(store) {
            cash = it
            openingDialog = false
        }
    }
}

@Composable
fun SaleScreen(
    cart: List<SaleItem>,
    listen: () -> Unit,
    company: String,
    printer: PrinterManager
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Quick Sale", style = MaterialTheme.typography.headlineSmall)
                Text(company, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = listen,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Mic, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Speak Sale")
                }

                Spacer(Modifier.height(12.dp))

                if (cart.isEmpty()) {
                    Text("Speak quantities, product names and prices.")
                    Text("Then say: complete sale and print.")
                } else {
                    cart.forEach { item ->
                        ListItem(
                            headlineContent = { Text(item.name) },
                            supportingContent = {
                                Text("${item.qty} × ${money(item.price)}")
                            },
                            trailingContent = { Text(money(item.total)) }
                        )
                    }

                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        Arrangement.SpaceBetween
                    ) {
                        Text("Total", style = MaterialTheme.typography.titleLarge)
                        Text(
                            money(cart.sumOf { it.total }),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            val sale = Sale(
                                System.currentTimeMillis(),
                                cart,
                                cart.sumOf { it.total },
                                System.currentTimeMillis()
                            )
                            printer.print(receipt(sale, company))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Print, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Print Current Sale")
                    }
                }
            }
        }
    }
}

@Composable
fun SalesScreen(
    sales: List<Sale>,
    company: String,
    printer: PrinterManager
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(sales.reversed()) { sale ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Sale #${sale.id}", style = MaterialTheme.typography.labelSmall)
                    Text(money(sale.total), style = MaterialTheme.typography.titleLarge)
                    Text(
                        sale.items.joinToString(", ") {
                            "${it.qty}× ${it.name}"
                        }
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        Arrangement.SpaceBetween,
                        Alignment.CenterVertically
                    ) {
                        Text(date(sale.time), style = MaterialTheme.typography.bodySmall)
                        TextButton(
                            onClick = {
                                printer.print(receipt(sale, company))
                            }
                        ) {
                            Text("Re-print")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExpensesScreen(expenses: List<Expense>, add: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            Arrangement.SpaceBetween,
            Alignment.CenterVertically
        ) {
            Text("Expenses", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = add) { Text("Add") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(expenses.reversed()) { expense ->
                ListItem(
                    headlineContent = { Text(expense.title) },
                    supportingContent = { Text(date(expense.time)) },
                    trailingContent = { Text(money(expense.amount)) }
                )
            }
        }
    }
}

@Composable
fun ReportsScreen(sales: Double, expenses: Double, cash: Double) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Reports", style = MaterialTheme.typography.headlineSmall)
        Metric("Total Sales", sales)
        Metric("Total Expenses", expenses)
        Metric("Cash In Hand", cash)
        Metric("Net", sales - expenses)
    }
}

@Composable
fun Metric(title: String, value: Double) {
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            Arrangement.SpaceBetween
        ) {
            Text(title)
            Text(money(value), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun SettingsDialog(
    store: Store,
    printer: PrinterManager,
    close: () -> Unit
) {
    var company by remember { mutableStateOf(store.company()) }
    var devices by remember { mutableStateOf(printer.devices()) }
    var status by remember {
        mutableStateOf(if (printer.connected()) "Connected" else "Not connected")
    }

    AlertDialog(
        onDismissRequest = close,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = company,
                    onValueChange = { company = it },
                    label = { Text("Company name") }
                )
                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { devices = printer.devices() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Bluetooth, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Discover Bluetooth Printers")
                }

                Text(status, style = MaterialTheme.typography.bodySmall)

                devices.forEach { device ->
                    TextButton(
                        onClick = {
                            status = if (printer.connect(device)) {
                                "Connected: ${device.name ?: "Printer"}"
                            } else {
                                "Connection failed"
                            }
                        }
                    ) {
                        Text(device.name ?: "Unknown printer")
                    }
                }

                Text("80mm ESC/POS printing", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                store.setCompany(company)
                close()
            }) {
                Text("Save")
            }
        }
    )
}

@Composable
fun OpeningCashDialog(store: Store, done: (Double) -> Unit) {
    var value by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Opening Cash") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Cash in hand / counter cash") }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val amount = value.toDoubleOrNull() ?: 0.0
                store.setOpeningCash(amount)
                done(amount)
            }) {
                Text("Continue")
            }
        }
    )
}

fun parseVoice(raw: String, old: List<SaleItem>): List<SaleItem> {
    val result = old.toMutableList()
    val parts = raw.split(Regex(",|\\band\\b|\\bthen\\b|;"))
    val pattern = Regex(
        """(?i)\b(\d+)\s+(.+?)\s+(?:price\s+)?(\d+(?:\.\d+)?)\s*(?:each)?\b"""
    )

    for (part in parts) {
        val match = pattern.find(part.trim()) ?: continue
        val qty = match.groupValues[1].toIntOrNull() ?: continue
        val name = match.groupValues[2].trim()
        val price = match.groupValues[3].toDoubleOrNull() ?: continue
        if (name.isNotBlank()) result += SaleItem(name, qty, price)
    }
    return result
}

fun money(value: Double): String =
    "Rs " + String.format(Locale.US, "%.0f", value)

fun date(value: Long): String =
    SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date(value))

fun receipt(sale: Sale, company: String): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x1B, 0x40))
    out.write(byteArrayOf(0x1B, 0x61, 0x01))
    out.write((company + "\n").toByteArray(Charsets.UTF_8))
    out.write(("SALE #" + sale.id + "\n").toByteArray(Charsets.UTF_8))
    out.write((date(sale.time) + "\n").toByteArray(Charsets.UTF_8))
    out.write(byteArrayOf(0x1B, 0x61, 0x00))
    out.write("--------------------------------\n".toByteArray(Charsets.UTF_8))
    sale.items.forEach { item ->
        out.write(
            ("${item.qty} x ${item.name}\n").toByteArray(Charsets.UTF_8)
        )
        out.write(
            ("    ${money(item.total)}\n").toByteArray(Charsets.UTF_8)
        )
    }
    out.write("--------------------------------\n".toByteArray(Charsets.UTF_8))
    out.write(("TOTAL: " + money(sale.total) + "\n\n\n").toByteArray(Charsets.UTF_8))
    return out.toByteArray()
}
