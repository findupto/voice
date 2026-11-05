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
    val unitCost: Double,
    val baseQuantity: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
    val barcode: String = ""
)

data class RecipeComponent(val stockName: String, val quantity: Double, val unit: String)
data class ProductRecipe(val productName: String, val components: List<RecipeComponent>)

/** Suggested top-up to a target level; invalid stock values produce no reorder quantity. */
internal fun suggestedReorderQuantity(current: Double, minimum: Double, targetMultiplier: Double = 2.0): Double {
    if (!current.isFinite() || !minimum.isFinite() || !targetMultiplier.isFinite() ||
        current < 0.0 || minimum <= 0.0 || targetMultiplier <= 1.0) return 0.0
    val target = minimum * targetMultiplier
    if (!target.isFinite()) return 0.0
    return (target - current).coerceAtLeast(0.0)
}

internal fun quantityInStockUnit(quantity: Double, recipeUnit: String, stockUnit: String): Double? {
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
    init { migrateLegacyMovementBaseline() }
    fun items(): List<InventoryStockItem> {
        val a = JSONArray(prefs.getString("items", "[]"))
        return (0 until a.length()).mapNotNull { i -> runCatching {
            val o = a.getJSONObject(i)
            InventoryStockItem(o.getLong("id"), o.getString("name"), o.optString("category", "Ingredient"),
                o.optDouble("quantity", 0.0), o.optString("unit", "pcs"),
                o.optDouble("minimum", 0.0), o.optDouble("unitCost", 0.0),
                o.optDouble("baseQuantity", Double.NaN), o.optLong("updatedAt", 0L), o.optString("barcode", ""))
        }.getOrNull() }.sortedBy { it.name.lowercase() }
    }
    private fun saveItems(xs: List<InventoryStockItem>) {
        val a = JSONArray()
        xs.forEach { x -> a.put(JSONObject().put("id", x.id).put("name", x.name).put("category", x.category)
            .put("quantity", x.quantity).put("unit", x.unit).put("minimum", x.minimum).put("unitCost", x.unitCost)
            .put("baseQuantity", x.baseQuantity).put("updatedAt", x.updatedAt).put("barcode", x.barcode)) }
        prefs.edit().putString("items", a.toString()).apply()
    }
    fun addItem(name: String, category: String, quantity: Double, unit: String, minimum: Double, unitCost: Double): Boolean {
        if (name.isBlank() || unit.isBlank() || !quantity.isFinite() || !minimum.isFinite() || !unitCost.isFinite() || quantity < 0 || minimum < 0 || unitCost < 0) return false
        if (items().any { canonicalProductKey(it.name) == canonicalProductKey(name) }) return false
        val id = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        val next = items() + InventoryStockItem(id, name.trim(), category, quantity, unit.trim(), minimum, unitCost, 0.0, now)
        return persistStockChange(next, name.trim(), quantity, "Opening stock", id, "opening:$id", now)
    }
    /** Idempotent receipt: retries with the same purchase order ID never add stock twice. */
    @Synchronized fun receivePurchaseOrder(purchaseOrderId: Long, stockItemId: Long, quantity: Double, reason: String): Boolean {
        if (purchaseOrderId <= 0L || !quantity.isFinite() || quantity <= 0.0) return false
        val movementId = "purchase:$purchaseOrderId:$stockItemId"
        val movements = JSONArray(prefs.getString("movements", "[]"))
        if ((0 until movements.length()).any { movements.optJSONObject(it)?.optString("id") == movementId }) return true
        val current = items()
        val item = current.firstOrNull { it.id == stockItemId } ?: return false
        if (!(item.quantity + quantity).isFinite()) return false
        val now = System.currentTimeMillis()
        val next = current.map { if (it.id == stockItemId) it.copy(quantity = it.quantity + quantity, updatedAt = now) else it }
        return persistStockChange(next, item.name, quantity, reason, stockItemId, movementId, now)
    }

    @Synchronized fun adjustStock(id: Long, delta: Double, reason: String): Boolean {
        if (!delta.isFinite()) return false
        val old = items().firstOrNull { it.id == id } ?: return false
        if (delta == 0.0 || (delta < 0 && old.quantity + delta < 0)) return false
        val now = System.currentTimeMillis()
        val next = items().map { if (it.id == id) it.copy(quantity = it.quantity + delta, updatedAt = now) else it }
        return persistStockChange(next, old.name, delta, reason, id, java.util.UUID.randomUUID().toString(), now)
    }
    @Synchronized fun reconcileCount(id: Long, countedQuantity: Double, note: String = "Physical stock count"): Boolean {
        if (!countedQuantity.isFinite() || countedQuantity < 0.0) return false
        val current = items().firstOrNull { it.id == id } ?: return false
        val delta = countedQuantity - current.quantity
        if (kotlin.math.abs(delta) < 0.0000001) return true
        val now = System.currentTimeMillis()
        val next = items().map { if (it.id == id) it.copy(quantity = countedQuantity, updatedAt = now) else it }
        return persistStockChange(next, current.name, delta, note, id, java.util.UUID.randomUUID().toString(), now)
    }
    fun estimatedRecipeCost(saleItems: List<SaleItem>): Double {
        val stockByKey = items().associateBy { canonicalProductKey(it.name) }
        val recipeByKey = recipes().associateBy { canonicalProductKey(it.productName) }
        return saleItems.filter { !it.name.startsWith(CART_DISCOUNT_PREFIX) }.sumOf { sold ->
            recipeByKey[canonicalProductKey(sold.name)]?.components.orEmpty().sumOf componentLoop@ { component ->
                val stock = stockByKey[canonicalProductKey(component.stockName)] ?: return@componentLoop 0.0
                val amount = quantityInStockUnit(component.quantity, component.unit, stock.unit) ?: return@componentLoop 0.0
                amount * sold.qty * stock.unitCost
            }
        }
    }
    fun recentMovementDetails(limit: Int = 12): List<String> {
        val a = JSONArray(prefs.getString("movements", "[]"))
        return (0 until a.length()).mapNotNull { i -> runCatching {
            val o = a.getJSONObject(i)
            val stamp = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(o.getLong("time")))
            stamp + " • " + o.getString("name") + " • " + o.getDouble("quantity") + " • " + o.getString("reason")
        }.getOrNull() }.takeLast(limit).reversed()
    }
    @Synchronized fun updateBarcode(id: Long, barcode: String): Boolean {
        val clean = barcode.trim()
        if (clean.isBlank()) return false
        val all = items()
        if (all.any { it.id != id && it.barcode.equals(clean, true) }) return false
        if (all.none { it.id == id }) return false
        val now = System.currentTimeMillis()
        return prefs.edit().putString("items", itemsArray(all.map { if (it.id == id) it.copy(barcode = clean, updatedAt = now) else it }).toString()).commit()
    }
    fun findByBarcode(barcode: String): InventoryStockItem? = items().firstOrNull { it.barcode.equals(barcode.trim(), true) && barcode.isNotBlank() }
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
        if (productName.isBlank() || stockName.isBlank() || unit.isBlank() || !quantity.isFinite() || quantity <= 0) return false
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
                if (!replaced) { next.put(JSONObject().put("stockName", stock.name).put("quantity", quantity).put("unit", unit).put("updatedAt", System.currentTimeMillis())); replaced = true }
            } else next.put(c)
        }
        if (!replaced) next.put(JSONObject().put("stockName", stock.name).put("quantity", quantity).put("unit", unit))
        old.put("productName", productName.trim()).put("components", next); root.put(key, old)
        prefs.edit().putString("recipes", root.toString()).apply()
        return true
    }
    /** Checks all recipe requirements together before a sale is finalized. */
    fun insufficientForSale(saleItems: List<SaleItem>): List<String> {
        val recipeMap = recipes().associateBy { canonicalProductKey(it.productName) }
        val required = linkedMapOf<String, Double>()
        val stockByKey = items().associateBy { canonicalProductKey(it.name) }
        saleItems.filter { !it.name.startsWith(CART_DISCOUNT_PREFIX) }.forEach { sold ->
            recipeMap[canonicalProductKey(sold.name)]?.components.orEmpty().forEach { component ->
                val key = canonicalProductKey(component.stockName)
                val stock = stockByKey[key] ?: return@forEach
                val amount = quantityInStockUnit(component.quantity, component.unit, stock.unit) ?: return@forEach
                required[key] = (required[key] ?: 0.0) + amount * sold.qty
            }
        }
        return required.mapNotNull { (key, amount) ->
            val stock = stockByKey[key] ?: return@mapNotNull null
            if (stock.quantity + 1e-9 < amount) "${stock.name}: need ${"%.2f".format(amount)} ${stock.unit}, available ${"%.2f".format(stock.quantity)}" else null
        }
    }

    @Synchronized fun consumeSale(saleId: Long, saleItems: List<SaleItem>): Boolean {
        if (saleId <= 0L) return false
        val processed = JSONArray(prefs.getString("consumed_sales", "[]"))
        if ((0 until processed.length()).any { processed.optLong(it) == saleId }) return true
        val existingMovements = JSONArray(prefs.getString("movements", "[]"))
        if ((0 until existingMovements.length()).any { existingMovements.optJSONObject(it)?.optString("referenceId") == "sale:$saleId" }) {
            processed.put(saleId)
            return prefs.edit().putString("consumed_sales", processed.toString()).commit()
        }
        val recipeMap = recipes().associateBy { canonicalProductKey(it.productName) }
        val stock = items().toMutableList()
        val required = linkedMapOf<Int, Double>()
        saleItems.filter { !it.name.startsWith(CART_DISCOUNT_PREFIX) }.forEach saleLoop@ { sold ->
            if (sold.qty <= 0) return false
            recipeMap[canonicalProductKey(sold.name)]?.components.orEmpty().forEach componentLoop@ { component ->
                val index = stock.indexOfFirst { canonicalProductKey(it.name) == canonicalProductKey(component.stockName) }
                if (index < 0) return@componentLoop
                val used = quantityInStockUnit(component.quantity, component.unit, stock[index].unit)
                    ?: return@componentLoop
                if (!used.isFinite() || used < 0.0) return false
                required[index] = (required[index] ?: 0.0) + used * sold.qty
            }
        }
        // Recheck under the same lock as stock adjustments so a stale pre-check
        // cannot drive inventory below zero.
        if (required.any { (index, amount) -> !amount.isFinite() || stock[index].quantity + 1e-9 < amount }) return false
        val now = System.currentTimeMillis()
        required.forEach { (index, amount) -> stock[index] = stock[index].copy(quantity = stock[index].quantity - amount, updatedAt = now) }
        val movementArray = JSONArray(prefs.getString("movements", "[]"))
        required.forEach { (index, amount) ->
            val item = stock[index]
            val movementId = "sale:$saleId:${item.id}"
            if ((0 until movementArray.length()).none { movementArray.optJSONObject(it)?.optString("id") == movementId }) {
                movementArray.put(movementRecord(item.name, -amount, "Sale: recipe consumption", item.id, movementId, now))
            }
        }
        processed.put(saleId)
        return prefs.edit().putString("items", itemsArray(stock).toString())
            .putString("movements", movementArray.toString())
            .putString("consumed_sales", processed.toString()).commit()
    }
    fun consumeSale(saleItems: List<SaleItem>) { consumeSale(System.currentTimeMillis(), saleItems) }
    private fun itemsArray(xs: List<InventoryStockItem>) = JSONArray().apply {
        xs.forEach { x -> put(JSONObject().put("id", x.id).put("name", x.name).put("category", x.category)
            .put("quantity", x.quantity).put("unit", x.unit).put("minimum", x.minimum).put("unitCost", x.unitCost)
            .put("baseQuantity", x.baseQuantity).put("updatedAt", x.updatedAt)) }
    }
    private fun movementRecord(name: String, quantity: Double, reason: String, stockId: Long, movementId: String, time: Long) =
        JSONObject().put("id", movementId).put("stockId", stockId).put("referenceId", movementId.substringBeforeLast(':', ""))
            .put("time", time).put("name", name).put("quantity", quantity).put("reason", reason)
            .put("actor", prefs.getString("staff_name", "").orEmpty())
    private fun persistStockChange(xs: List<InventoryStockItem>, name: String, quantity: Double, reason: String, stockId: Long, movementId: String, time: Long): Boolean {
        val movements = JSONArray(prefs.getString("movements", "[]"))
        if ((0 until movements.length()).none { movements.optJSONObject(it)?.optString("id") == movementId }) {
            movements.put(movementRecord(name, quantity, reason, stockId, movementId, time))
        }
        return prefs.edit().putString("items", itemsArray(xs).toString()).putString("movements", movements.toString()).commit()
    }
    private fun migrateLegacyMovementBaseline() {
        val all = items()
        val movements = JSONArray(prefs.getString("movements", "[]"))
        val migrated = all.map { item ->
            if (item.baseQuantity.isFinite()) item else {
                val legacyDelta = (0 until movements.length()).mapNotNull { i ->
                    val movement = movements.optJSONObject(i) ?: return@mapNotNull null
                    if (movement.optLong("stockId", 0L) == 0L && canonicalProductKey(movement.optString("name")) == canonicalProductKey(item.name)) movement.optDouble("quantity", 0.0) else null
                }.sum()
                item.copy(baseQuantity = item.quantity - legacyDelta)
            }
        }
        if (all.any { !it.baseQuantity.isFinite() }) saveItems(migrated)
    }

    fun exportBackup(): JSONObject = JSONObject().put("items", JSONArray(prefs.getString("items", "[]")))
        .put("recipes", JSONObject(prefs.getString("recipes", "{}")))
        .put("movements", JSONArray(prefs.getString("movements", "[]")))
        .put("consumedSales", JSONArray(prefs.getString("consumed_sales", "[]")))

    fun mergeBackup(local: JSONObject?, remote: JSONObject?): JSONObject {
        val left = local ?: exportBackup()
        val right = remote ?: JSONObject()
        val mergedItems = linkedMapOf<Long, JSONObject>()
        for (source in listOf(left.optJSONArray("items") ?: JSONArray(), right.optJSONArray("items") ?: JSONArray())) for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            val id = item.optLong("id"); val previous = mergedItems[id]
            if (previous == null || (item.has("baseQuantity") && !previous.has("baseQuantity")) || (item.has("baseQuantity") == previous.has("baseQuantity") && item.optLong("updatedAt") >= previous.optLong("updatedAt"))) mergedItems[id] = JSONObject(item.toString())
        }
        val movements = linkedMapOf<String, JSONObject>()
        for (source in listOf(left.optJSONArray("movements") ?: JSONArray(), right.optJSONArray("movements") ?: JSONArray())) for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            val id = item.optString("id").ifBlank { "${item.optLong("time")}:${item.optString("name")}:${item.optDouble("quantity")}:${item.optString("reason")}" }
            movements.putIfAbsent(id, JSONObject(item.toString()).put("id", id))
        }
        val recipeRoot = JSONObject(left.optJSONObject("recipes")?.toString() ?: "{}")
        val remoteRecipes = right.optJSONObject("recipes") ?: JSONObject()
        remoteRecipes.keys().forEach { key ->
            val incoming = remoteRecipes.optJSONObject(key) ?: return@forEach
            val current = recipeRoot.optJSONObject(key)
            if (current == null) recipeRoot.put(key, JSONObject(incoming.toString())) else {
                val components = linkedMapOf<String, JSONObject>()
                for (source in listOf(current.optJSONArray("components") ?: JSONArray(), incoming.optJSONArray("components") ?: JSONArray())) for (i in 0 until source.length()) {
                    val component = source.optJSONObject(i) ?: continue
                    val componentKey = canonicalProductKey(component.optString("stockName"))
                    val previous = components[componentKey]
                    if (previous == null || component.optLong("updatedAt") >= previous.optLong("updatedAt")) components[componentKey] = JSONObject(component.toString())
                }
                current.put("components", JSONArray().apply { components.values.forEach { put(it) } }); recipeRoot.put(key, current)
            }
        }
        val consumed = linkedSetOf<Long>()
        for (source in listOf(left.optJSONArray("consumedSales") ?: JSONArray(), right.optJSONArray("consumedSales") ?: JSONArray())) for (i in 0 until source.length()) source.optLong(i).takeIf { it > 0L }?.let { consumed.add(it) }
        val movementArray = JSONArray().apply { movements.values.sortedBy { it.optLong("time") }.forEach { put(it) } }
        val movementTotals = linkedMapOf<Long, Double>()
        for (i in 0 until movementArray.length()) {
            val movement = movementArray.optJSONObject(i) ?: continue; val stockId = movement.optLong("stockId", 0L)
            if (stockId > 0L) movementTotals[stockId] = (movementTotals[stockId] ?: 0.0) + movement.optDouble("quantity", 0.0)
        }
        val itemArray = JSONArray()
        mergedItems.values.forEach { item ->
            val id = item.optLong("id")
            if (movementTotals.containsKey(id)) item.put("quantity", item.optDouble("baseQuantity", item.optDouble("quantity")) + movementTotals.getValue(id))
            itemArray.put(item)
        }
        return JSONObject().put("items", itemArray).put("recipes", recipeRoot).put("movements", movementArray).put("consumedSales", JSONArray().apply { consumed.forEach { put(it) } })
    }

    fun importBackup(data: JSONObject?) {
        if (data == null) return
        val items = data.optJSONArray("items") ?: JSONArray(); val recipes = data.optJSONObject("recipes") ?: JSONObject()
        val movements = data.optJSONArray("movements") ?: JSONArray(); val consumed = data.optJSONArray("consumedSales") ?: JSONArray()
        prefs.edit().putString("items", items.toString()).putString("recipes", recipes.toString()).putString("movements", movements.toString()).putString("consumed_sales", consumed.toString()).commit()
        migrateLegacyMovementBaseline()
    }

    fun recentMovements(): List<String> {
        val a = JSONArray(prefs.getString("movements", "[]"))
        return (0 until a.length()).mapNotNull { i -> runCatching {
            val o = a.getJSONObject(i)
            "${java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(o.getLong("time")))} • ${o.getString("name")} • ${o.getDouble("quantity")} • ${o.getString("reason")}"
        }.getOrNull() }.reversed().take(20)
    }
}

@androidx.camera.core.ExperimentalGetImage
@Composable
fun InventoryManager(inventory: InventoryStore, menuStore: Store, close: () -> Unit, refresh: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Ingredient") }
    var qty by remember { mutableStateOf("0") }
    var unit by remember { mutableStateOf("pcs") }
    var minimum by remember { mutableStateOf("0") }
    var cost by remember { mutableStateOf("0") }
    var barcode by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val barcodeScanner = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.getStringExtra(LiveQrScannerActivity.EXTRA_BARCODE)?.let { barcode = it }
    }
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
    val reorderPlan = remember(tick) { inventory.lowStock().map { it to suggestedReorderQuantity(it.quantity, it.minimum) }.filter { it.second > 0.0 } }
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(barcode, { barcode = it }, label = { Text("Barcode / SKU") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedButton(onClick = { barcodeScanner.launch(android.content.Intent(context, LiveQrScannerActivity::class.java).putExtra(LiveQrScannerActivity.EXTRA_SCAN_ANY_BARCODE, true)) }) { Text("SCAN") }
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
                    val duplicateBarcode = barcode.isNotBlank() && inventory.findByBarcode(barcode) != null
                    var ok = !duplicateBarcode && inventory.addItem(name, category, qty.toDoubleOrNull() ?: -1.0, unit, minimum.toDoubleOrNull() ?: -1.0, cost.toDoubleOrNull() ?: -1.0)
                    if (ok && barcode.isNotBlank()) {
                        val saved = inventory.items().firstOrNull { canonicalProductKey(it.name) == canonicalProductKey(name) }
                        ok = saved != null && inventory.updateBarcode(saved.id, barcode)
                    }
                    message = if (ok) "Stock item saved" else if (duplicateBarcode) "This barcode is already assigned to another item" else "Check fields or use a unique item name"
                    if (ok) { name = ""; barcode = ""; qty = "0"; minimum = "0"; cost = "0"; tick++; refresh() }
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
                        var countedQuantity by remember(item.id, tick) { mutableStateOf(item.quantity.toString()) }
                        Card {
                            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.name, fontWeight = FontWeight.SemiBold)
                                        Text("${item.category} • ${item.quantity} ${item.unit} • min ${item.minimum}" + if (item.barcode.isNotBlank()) " • ${item.barcode}" else "", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (item.quantity <= item.minimum) Text("LOW", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(countedQuantity, { countedQuantity = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Physical count") }, modifier = Modifier.weight(1f), singleLine = true)
                                    OutlinedButton(onClick = { val actual = countedQuantity.toDoubleOrNull(); if (actual != null && inventory.reconcileCount(item.id, actual)) { tick++; refresh(); message = "Physical count saved for ${item.name}" } else message = "Enter a valid non-negative count" }) { Text("COUNT") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { val amount = adjustBy.toDoubleOrNull() ?: 0.0; if (inventory.adjustStock(item.id, amount, "Stock received")) { tick++; refresh() } }) { Text("+ Receive") }
                                    OutlinedButton(onClick = { val amount = adjustBy.toDoubleOrNull() ?: 0.0; if (inventory.adjustStock(item.id, -amount, "Stock correction / waste")) { tick++; refresh() } }, enabled = (adjustBy.toDoubleOrNull() ?: 0.0) > 0 && item.quantity >= (adjustBy.toDoubleOrNull() ?: 0.0)) { Text("− Adjust") }
                                }
                            }
                        }
                    }
                }
                if (inventory.lowStock().isNotEmpty()) {
                    Text("REORDER SUGGESTIONS", fontWeight = FontWeight.Bold)
                    Text("Suggested quantities top each item up to twice its minimum level. Review supplier availability and adjust before ordering.", style = MaterialTheme.typography.bodySmall)
                    reorderPlan.forEach { (item, amount) ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, fontWeight = FontWeight.SemiBold)
                                    Text("Now: ${item.quantity} ${item.unit} • Minimum: ${item.minimum} ${item.unit}", style = MaterialTheme.typography.bodySmall)
                                    Text("Suggested order: ${String.format(java.util.Locale.US, "%.2f", amount)} ${item.unit}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                                Text(money(amount * item.unitCost), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Text("Low stock: " + inventory.lowStock().joinToString { "${it.name} (${it.quantity} ${it.unit})" }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Text("Recent stock movements", fontWeight = FontWeight.Bold)
                inventory.recentMovementDetails().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
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
