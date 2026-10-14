package com.findupto.voicepos

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class InventoryStockItem(
    val id: Long,
    val name: String,
    val category: String,
    val quantity: Double,
    val unit: String,
    val minimum: Double,
    val unitCost: Double
)

data class RecipeComponent(val stockName: String, val quantity: Double, val unit: String)
data class ProductRecipe(val productName: String, val components: List<RecipeComponent>)

private fun quantityInStockUnit(quantity: Double, recipeUnit: String, stockUnit: String): Double? {
    val from = recipeUnit.trim().lowercase()
    val to = stockUnit.trim().lowercase()
    if (from == to) return quantity
    val grams = setOf("g", "gram", "grams")
    val kilos = setOf("kg", "kgs", "kilogram", "kilograms")
    val millilitres = setOf("ml", "millilitre", "milliliter", "millilitres", "milliliters")
    val litres = setOf("l", "ltr", "litre", "liter", "litres", "liters")
    if (from in grams && to in kilos) return quantity / 1000.0
    if (from in kilos && to in grams) return quantity * 1000.0
    if (from in millilitres && to in litres) return quantity / 1000.0
    if (from in litres && to in millilitres) return quantity * 1000.0
    return null
}

/** Local-first stock ledger. Recipe keys use the same canonical product naming as sales analytics. */
class InventoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("restaurant_inventory_v1", Context.MODE_PRIVATE)
    fun items(): List<InventoryStockItem> {
        val a = JSONArray(prefs.getString("items", "[]"))
        return (0 until a.length()).mapNotNull { i -> runCatching {
            val o = a.getJSONObject(i)
            InventoryStockItem(o.getLong("id"), o.getString("name"), o.optString("category", "Ingredient"),
                o.optDouble("quantity", 0.0), o.optString("unit", "pcs"),
                o.optDouble("minimum", 0.0), o.optDouble("unitCost", 0.0))
        }.getOrNull() }.sortedBy { it.name.lowercase() }
    }
    private fun saveItems(xs: List<InventoryStockItem>) {
        val a = JSONArray()
        xs.forEach { x -> a.put(JSONObject().put("id", x.id).put("name", x.name).put("category", x.category)
            .put("quantity", x.quantity).put("unit", x.unit).put("minimum", x.minimum).put("unitCost", x.unitCost)) }
        prefs.edit().putString("items", a.toString()).apply()
    }
    fun addItem(name: String, category: String, quantity: Double, unit: String, minimum: Double, unitCost: Double): Boolean {
        if (name.isBlank() || unit.isBlank() || quantity < 0 || minimum < 0 || unitCost < 0) return false
        if (items().any { canonicalProductKey(it.name) == canonicalProductKey(name) }) return false
        saveItems(items() + InventoryStockItem(System.currentTimeMillis(), name.trim(), category, quantity, unit.trim(), minimum, unitCost))
        logMovement(name.trim(), quantity, "Opening stock")
        return true
    }
    fun adjustStock(id: Long, delta: Double, reason: String): Boolean {
        val old = items().firstOrNull { it.id == id } ?: return false
        if (delta == 0.0 || (delta < 0 && old.quantity + delta < 0)) return false
        saveItems(items().map { if (it.id == id) it.copy(quantity = it.quantity + delta) else it })
        logMovement(old.name, delta, reason)
        return true
    }
    fun lowStock(): List<InventoryStockItem> = items().filter { it.quantity <= it.minimum }
    fun recipes(): List<ProductRecipe> {
        val root = JSONObject(prefs.getString("recipes", "{}"))
        return root.keys().asSequence().mapNotNull { key -> runCatching {
            val o = root.getJSONObject(key); val a = o.getJSONArray("components")
            ProductRecipe(o.optString("productName", key), (0 until a.length()).map { i ->
                val c = a.getJSONObject(i); RecipeComponent(c.getString("stockName"), c.getDouble("quantity"), c.optString("unit", "pcs"))
            })
        }.getOrNull() }.toList()
    }
    fun addRecipeComponent(productName: String, stockName: String, quantity: Double, unit: String): Boolean {
        if (productName.isBlank() || stockName.isBlank() || quantity <= 0) return false
        val stock = items().firstOrNull { canonicalProductKey(it.name) == canonicalProductKey(stockName) } ?: return false
        if (quantityInStockUnit(quantity, unit, stock.unit) == null) return false
        val root = JSONObject(prefs.getString("recipes", "{}"))
        val key = canonicalProductKey(productName)
        val old = root.optJSONObject(key) ?: JSONObject().put("productName", productName.trim()).put("components", JSONArray())
        val arr = old.optJSONArray("components") ?: JSONArray()
        val next = JSONArray()
        var replaced = false
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            if (canonicalProductKey(c.optString("stockName")) == canonicalProductKey(stock.name)) {
                if (!replaced) { next.put(JSONObject().put("stockName", stock.name).put("quantity", quantity).put("unit", unit)); replaced = true }
            } else next.put(c)
        }
        if (!replaced) next.put(JSONObject().put("stockName", stock.name).put("quantity", quantity).put("unit", unit))
        old.put("productName", productName.trim()).put("components", next); root.put(key, old)
        prefs.edit().putString("recipes", root.toString()).apply()
        return true
    }
    fun consumeSale(saleItems: List<SaleItem>) {
        val recipeMap = recipes().associateBy { canonicalProductKey(it.productName) }
        val stock = items().toMutableList()
        saleItems.filter { !it.name.startsWith(CART_DISCOUNT_PREFIX) }.forEach { sold ->
            recipeMap[canonicalProductKey(sold.name)]?.components.orEmpty().forEach { c ->
                val i = stock.indexOfFirst { canonicalProductKey(it.name) == canonicalProductKey(c.stockName) }
                if (i >= 0) {
                    val used = quantityInStockUnit(c.quantity, c.unit, stock[i].unit) ?: 0.0
                    stock[i] = stock[i].copy(quantity = stock[i].quantity - used * sold.qty)
                }
            }
        }
        saveItems(stock)
        saleItems.filter { !it.name.startsWith(CART_DISCOUNT_PREFIX) }.forEach { sold ->
            recipeMap[canonicalProductKey(sold.name)]?.components.orEmpty().forEach { c ->
                val stockItem = items().firstOrNull { canonicalProductKey(it.name) == canonicalProductKey(c.stockName) }
                val used = stockItem?.let { quantityInStockUnit(c.quantity, c.unit, it.unit) } ?: c.quantity
                logMovement(c.stockName, -used * sold.qty, "Sale: ${sold.name}")
            }
        }
    }
    private fun logMovement(name: String, quantity: Double, reason: String) {
        val a = JSONArray(prefs.getString("movements", "[]"))
        a.put(JSONObject().put("time", System.currentTimeMillis()).put("name", name).put("quantity", quantity).put("reason", reason))
        while (a.length() > 5000) a.remove(0)
        prefs.edit().putString("movements", a.toString()).apply()
    }
    fun recentMovements(): List<String> {
        val a = JSONArray(prefs.getString("movements", "[]"))
        return (0 until a.length()).mapNotNull { i -> runCatching {
            val o = a.getJSONObject(i)
            "${java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(o.getLong("time")))} • ${o.getString("name")} • ${o.getDouble("quantity")} • ${o.getString("reason")}"
        }.getOrNull() }.reversed().take(20)
    }
}

@Composable
fun InventoryManager(inventory: InventoryStore, menuStore: Store, close: () -> Unit, refresh: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Ingredient") }
    var qty by remember { mutableStateOf("0") }
    var unit by remember { mutableStateOf("pcs") }
    var minimum by remember { mutableStateOf("0") }
    var cost by remember { mutableStateOf("0") }
    var adjustBy by remember { mutableStateOf("1") }
    var product by remember { mutableStateOf("") }
    var dealPrice by remember { mutableStateOf("") }
    var component by remember { mutableStateOf("") }
    var componentQty by remember { mutableStateOf("1") }
    var componentUnit by remember { mutableStateOf("pcs") }
    var message by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val stock = remember(tick) { inventory.items() }
    val recipes = remember(tick) { inventory.recipes() }
    AlertDialog(onDismissRequest = close, modifier = Modifier.fillMaxWidth(), title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Inventory & Deals", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            IconButton(onClick = close) { Icon(Icons.Default.Close, "Close") }
        }
    }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("STOCK") })
                Tab(tab == 1, { tab = 1 }, text = { Text("RECIPES / DEALS") })
            }
            if (tab == 0) {
                Text("Food ingredients, drinks and packaging are tracked separately.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text("Item name (e.g. Chicken, میدہ, Pizza box)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Ingredient", "Packaging", "Resale", "Supply").forEach { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(qty, { qty = it }, label = { Text("Opening qty") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(unit, { unit = it }, label = { Text("Unit (kg, g, pcs)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(minimum, { minimum = it }, label = { Text("Low-stock level") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(cost, { cost = it }, label = { Text("Cost per unit") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Button(onClick = {
                    val ok = inventory.addItem(name, category, qty.toDoubleOrNull() ?: -1.0, unit, minimum.toDoubleOrNull() ?: -1.0, cost.toDoubleOrNull() ?: -1.0)
                    message = if (ok) "Stock item saved" else "Check fields or use a unique item name"
                    if (ok) { name = ""; qty = "0"; minimum = "0"; cost = "0"; tick++; refresh() }
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("ADD STOCK ITEM") }
                val stockValue = stock.sumOf { it.quantity.coerceAtLeast(0.0) * it.unitCost }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Card(Modifier.weight(1f)) { Column(Modifier.padding(10.dp)) { Text("STOCK VALUE", style = MaterialTheme.typography.labelSmall); Text(money(stockValue), fontWeight = FontWeight.Bold) } }
                    Card(Modifier.weight(1f)) { Column(Modifier.padding(10.dp)) { Text("LOW STOCK", style = MaterialTheme.typography.labelSmall); Text(inventory.lowStock().size.toString(), fontWeight = FontWeight.Bold, color = if (inventory.lowStock().isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary) } }
                }
                OutlinedTextField(adjustBy, { adjustBy = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Receive / adjust quantity") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("Current stock • use kg↔g and L↔ml recipes; other units must match.", fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 190.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(stock, key = { it.id }) { item ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.name, fontWeight = FontWeight.SemiBold)
                                        Text("${item.category} • ${item.quantity} ${item.unit} • min ${item.minimum}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (item.quantity <= item.minimum) Text("LOW", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { val amount = adjustBy.toDoubleOrNull() ?: 0.0; if (inventory.adjustStock(item.id, amount, "Stock received")) { tick++; refresh() } }) { Text("+ Receive") }
                                    OutlinedButton(onClick = { val amount = adjustBy.toDoubleOrNull() ?: 0.0; if (inventory.adjustStock(item.id, -amount, "Stock correction / waste")) { tick++; refresh() } }, enabled = (adjustBy.toDoubleOrNull() ?: 0.0) > 0 && item.quantity >= (adjustBy.toDoubleOrNull() ?: 0.0)) { Text("− Adjust") }
                                }
                            }
                        }
                    }
                }
                if (inventory.lowStock().isNotEmpty()) Text("Reorder: " + inventory.lowStock().joinToString { "${it.name} (${it.quantity} ${it.unit})" }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Create a deal as a menu product, then attach every ingredient/packaging component. Each sold unit deducts all recipe components.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(product, { product = it }, label = { Text("Product / deal name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(dealPrice, { dealPrice = it }, label = { Text("Selling price (Rs)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = {
                    val price = dealPrice.toDoubleOrNull()
                    if (product.isBlank() || price == null || price < 0) message = "Enter a product/deal name and valid price"
                    else if (menuStore.menu().any { canonicalProductKey(it.name) == canonicalProductKey(product) }) message = "A matching menu item already exists"
                    else { menuStore.addMenuItem(MenuItem(System.currentTimeMillis(), product.trim(), price)); message = "Deal added to menu. Now add its components."; refresh() }
                }, modifier = Modifier.fillMaxWidth()) { Text("CREATE DEAL / MENU PRODUCT") }
                HorizontalDivider()
                OutlinedTextField(product, { product = it }, label = { Text("Recipe name (must match menu/deal name)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(component, { component = it }, label = { Text("Stock item name (exact item)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(componentQty, { componentQty = it }, label = { Text("Qty per sale") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(componentUnit, { componentUnit = it }, label = { Text("Recipe unit") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Button(onClick = {
                    val amount = componentQty.toDoubleOrNull()
                    val ok = amount != null && inventory.addRecipeComponent(product, component, amount, componentUnit)
                    message = if (ok) "Component saved" else "Product or stock item not found; check names and quantity"
                    if (ok) { component = ""; tick++; refresh() }
                }, modifier = Modifier.fillMaxWidth()) { Text("ADD COMPONENT TO RECIPE") }
                Text("Saved recipes", fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 190.dp)) {
                    items(recipes, key = { canonicalProductKey(it.productName) }) { recipe ->
                        Card { Column(Modifier.fillMaxWidth().padding(8.dp)) {
                            Text(recipe.productName, fontWeight = FontWeight.SemiBold)
                            recipe.components.forEach { Text("• ${it.quantity} ${it.unit} ${it.stockName}", style = MaterialTheme.typography.bodySmall) }
                        } }
                    }
                }
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }, confirmButton = { TextButton(onClick = close) { Text("DONE") } })
}

@Composable
fun CustomerDuesManager(store: Store, close: () -> Unit, refresh: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<DueAccount?>(null) }
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("CASH") }
    var message by remember { mutableStateOf("") }
    var addMode by remember { mutableStateOf(false) }
    var customerName by remember { mutableStateOf("") }
    var customerPhone by remember { mutableStateOf("") }
    var newAmount by remember { mutableStateOf("") }
    val dues = remember(tick) { store.dues() }
    AlertDialog(onDismissRequest = close, modifier = Modifier.fillMaxWidth(), title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Customer dues / ledger", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            IconButton(onClick = close) { Icon(Icons.Default.Close, "Close") }
        }
    }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Outstanding total: ${money(store.dueTotal())}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Button(onClick = { addMode = !addMode }) { Text(if (addMode) "CANCEL" else "ADD DUE") } }
            if (addMode) { OutlinedTextField(customerName, { customerName = it }, label = { Text("Customer name") }, singleLine = true, modifier = Modifier.fillMaxWidth()); OutlinedTextField(customerPhone, { customerPhone = it }, label = { Text("Phone (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()); OutlinedTextField(newAmount, { newAmount = it.filter { c -> c.isDigit() || c == 46.toChar() } }, label = { Text("Amount owed") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Button(onClick = { val v = newAmount.toDoubleOrNull(); if (v != null && store.addCustomerDue(customerName, customerPhone, v)) { customerName = ""; customerPhone = ""; newAmount = ""; addMode = false; tick++; message = "Customer due added"; refresh() } else message = "Enter a customer name and a valid amount" }, modifier = Modifier.fillMaxWidth()) { Text("SAVE CUSTOMER DUE") } }
            if (dues.isEmpty()) Text("No outstanding customer dues.")
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(dues, key = { it.id }) { due ->
                    Card { Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(due.customerName.ifBlank { "Walk-in customer" }, fontWeight = FontWeight.Bold)
                        Text("Sale #${due.saleId} • ${due.customerPhone}", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Balance: ${money(due.balance)}", Modifier.weight(1f), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Button(onClick = { selected = due; amount = due.balance.toString(); method = "CASH" }) { Text("RECEIVE") }; TextButton(onClick = { store.removeDue(due.id); tick++; message = "Due removed from ledger"; refresh() }) { Text("REMOVE") } }
                        }
                    } }
                }
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = close) { Text("DONE") } })
    selected?.let { due ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Receive due payment") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${due.customerName.ifBlank { "Customer" }} • Balance ${money(due.balance)}")
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount received (Rs)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("CASH", "BANK", "CARD").forEach { m -> FilterChip(method == m, { method = m }, label = { Text(m) }) }
                }
                Text("Payment is recorded in the ledger and reduces this customer's balance.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {
            Button(onClick = {
                val v = amount.toDoubleOrNull()
                if (v == null || v <= 0 || v > due.balance) message = "Enter an amount up to ${money(due.balance)}"
                else if (store.recordPayment(due.id, v, method, store.activeShift()?.id ?: 0L)) { message = "Payment received"; selected = null; tick++; refresh() }
                else message = "Payment could not be saved"
            }) { Text("SAVE PAYMENT") }
        }, dismissButton = { TextButton(onClick = { selected = null }) { Text("CANCEL") } })
    }
}
