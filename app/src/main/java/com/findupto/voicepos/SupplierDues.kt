package com.findupto.voicepos

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class SupplierDue(val id: Long, val supplier: String, val phone: String, val description: String, val total: Double, val paid: Double, val createdAt: Long, val referenceId: String = "") {
    val balance: Double get() = (total - paid).coerceAtLeast(0.0)
}
class SupplierDueStore(context: Context) {
    private val prefs = context.getSharedPreferences("voice_supplier_dues_v1", Context.MODE_PRIVATE)
    fun all(): List<SupplierDue> {
        val a = JSONArray(prefs.getString("entries", "[]"))
        return (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { o -> SupplierDue(o.getLong("id"),o.getString("supplier"),o.optString("phone"),o.optString("description"),o.getDouble("total"),o.getDouble("paid"),o.getLong("createdAt"),o.optString("referenceId","")) } }.getOrNull() }.sortedByDescending { it.createdAt }
    }
    private fun save(xs: List<SupplierDue>) {
        val a = JSONArray()
        xs.forEach { x -> a.put(JSONObject().put("id",x.id).put("supplier",x.supplier).put("phone",x.phone).put("description",x.description).put("total",x.total).put("paid",x.paid).put("createdAt",x.createdAt).put("referenceId",x.referenceId)) }
        prefs.edit().putString("entries",a.toString()).apply()
    }
    fun add(supplier: String, phone: String, description: String, amount: Double): Boolean {
        if (supplier.isBlank() || amount <= 0.0) return false
        save(all() + SupplierDue(System.currentTimeMillis(),supplier.trim(),phone.trim(),description.trim(),amount,0.0,System.currentTimeMillis()))
        return true
    }
    /** Idempotent payable creation for retry-safe purchase-order receiving. */
    @Synchronized fun addOnce(referenceId: String, supplier: String, phone: String, description: String, amount: Double): Boolean {
        if (referenceId.isBlank() || supplier.isBlank() || !amount.isFinite() || amount <= 0.0) return false
        val existing = all()
        if (existing.any { it.referenceId == referenceId }) return true
        val now = System.currentTimeMillis()
        val row = SupplierDue(now, supplier.trim(), phone.trim(), description.trim(), amount, 0.0, now, referenceId)
        val next = JSONArray().apply {
            existing.forEach { x -> put(JSONObject().put("id",x.id).put("supplier",x.supplier).put("phone",x.phone).put("description",x.description).put("total",x.total).put("paid",x.paid).put("createdAt",x.createdAt).put("referenceId",x.referenceId)) }
            put(JSONObject().put("id",row.id).put("supplier",row.supplier).put("phone",row.phone).put("description",row.description).put("total",row.total).put("paid",row.paid).put("createdAt",row.createdAt).put("referenceId",row.referenceId))
        }
        return prefs.edit().putString("entries", next.toString()).commit()
    }
    fun update(x: SupplierDue) = save(all().map { if (it.id == x.id) x else it })
    fun remove(id: Long) = save(all().filterNot { it.id == id })
}
@Composable
fun SupplierDuesManager(store: SupplierDueStore, close: () -> Unit, changed: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val purchaseStore = remember(context) { SupplierPurchaseStore(context) }
    var rows by remember { mutableStateOf(store.all()) }
    var purchaseOrders by remember { mutableStateOf(purchaseStore.all()) }
    val supplierSummaries = remember(rows, purchaseOrders) {
        (rows.map { it.supplier } + purchaseOrders.map { it.supplier })
            .filter { it.isNotBlank() }.distinctBy { it.trim().lowercase(java.util.Locale.ROOT) }
            .sortedBy { it.lowercase(java.util.Locale.ROOT) }
            .map { name ->
                val dues = rows.filter { it.supplier.equals(name, true) }
                val orders = purchaseOrders.filter { it.supplier.equals(name, true) }
                SupplierSummary(name, orders.sumOf { it.total }, orders.count { it.received }, orders.count { !it.received }, dues.sumOf { it.paid }, dues.sumOf { it.balance })
            }
    }
    var addMode by remember { mutableStateOf(false) }
    var supplier by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var paying by remember { mutableStateOf<SupplierDue?>(null) }
    var payment by remember { mutableStateOf("") }
    var statementMode by remember { mutableStateOf(false) }
    var statementFrom by remember { mutableStateOf("") }
    var statementTo by remember { mutableStateOf("") }
    val statementRows = remember(rows, purchaseOrders, statementFrom, statementTo) {
        val from = statementFrom.toStatementBoundary(false)
        val to = statementTo.toStatementBoundary(true)
        val duesInRange = rows.filter { (from == null || it.createdAt >= from) && (to == null || it.createdAt <= to) }
        val ordersInRange = purchaseOrders.filter { (from == null || it.createdAt >= from) && (to == null || it.createdAt <= to) }
        (duesInRange.map { due ->
            listOf(due.createdAt, "PAYABLE", due.supplier, due.description, due.total, due.paid, due.balance, "Recorded")
        } + ordersInRange.map { order ->
            listOf(order.createdAt, "PURCHASE ORDER", order.supplier, "${order.quantity} ${order.unit} ${order.itemName}", order.total, 0.0, order.total, if (order.received) "Received" else "Pending")
        }).sortedBy { it[0] as Long }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Supplier / Vendor Payables") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Outstanding payable: ${CurrencySettings.symbol} " + rows.sumOf { it.balance }.formatMoney(), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("SUPPLIER SUMMARY", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { statementMode = !statementMode }) { Text(if (statementMode) "HIDE STATEMENT" else "STATEMENT / EXPORT") }
                }
                if (statementMode) {
                    Text("Filter by creation date (YYYY-MM-DD). Leave blank for all dates.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(statementFrom, { statementFrom = it.take(10) }, label = { Text("From date") }, modifier = Modifier.weight(1f), singleLine = true, isError = statementFrom.isNotBlank() && statementFrom.toStatementBoundary(false) == null)
                        OutlinedTextField(statementTo, { statementTo = it.take(10) }, label = { Text("To date") }, modifier = Modifier.weight(1f), singleLine = true, isError = statementTo.isNotBlank() && statementTo.toStatementBoundary(true) == null)
                    }
                    Text("${statementRows.size} statement entries", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val folder = File(context.cacheDir, "shared_receipts").apply { mkdirs() }
                                val file = File(folder, "supplier-statement-${System.currentTimeMillis()}.pdf")
                                createSupplierStatementPdf(file, statementRows, statementFrom, statementTo)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_SUBJECT, "Supplier statement")
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "Share supplier PDF statement"))
                            }.onFailure {
                                android.widget.Toast.makeText(context, "PDF export failed: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("EXPORT PDF STATEMENT") }
                    OutlinedButton(
                        onClick = {
                            val csv = buildString {
                                appendLine("date,type,supplier,details,amount,paid_to_date,current_balance,status")
                                statementRows.forEach { row ->
                                    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(row[0] as Long))
                                    val cells = listOf(date, row[1], row[2], row[3], row[4], row[5], row[6], row[7])
                                    appendLine(cells.joinToString(",") { value ->
                                        val raw = when (value) { is Double -> String.format(java.util.Locale.US, "%.2f", value); else -> value.toString() }
                                        "\"${raw.replace("\"", "\"\"")}\""
                                    })
                                }
                            }
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_SUBJECT, "Supplier statement")
                                putExtra(Intent.EXTRA_TEXT, csv)
                            }
                            context.startActivity(Intent.createChooser(share, "Export supplier statement"))
                        },
                        enabled = (statementFrom.isBlank() || statementFrom.toStatementBoundary(false) != null) && (statementTo.isBlank() || statementTo.toStatementBoundary(true) != null) && (statementFrom.isBlank() || statementTo.isBlank() || statementFrom <= statementTo),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("EXPORT CSV STATEMENT") }
                    LazyColumn(Modifier.heightIn(max = 180.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(statementRows) { row ->
                            Text("${java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(row[0] as Long))} • ${row[1]} • ${row[2]} • ${row[7]} • ${CurrencySettings.symbol} ${String.format(java.util.Locale.US, "%.2f", row[4] as Double)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (supplierSummaries.isEmpty()) Text("No supplier purchases or payables yet.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 170.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(supplierSummaries, key = { it.name.lowercase(java.util.Locale.ROOT) }) { summary ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(summary.name, style = MaterialTheme.typography.titleSmall)
                                Text("Purchased: ${CurrencySettings.symbol} ${summary.purchased.formatMoney()} • Paid: ${CurrencySettings.symbol} ${summary.paid.formatMoney()}", style = MaterialTheme.typography.bodySmall)
                                Text("Received orders: ${summary.receivedOrders} • Pending: ${summary.pendingOrders} • Outstanding: ${CurrencySettings.symbol} ${summary.outstanding.formatMoney()}", style = MaterialTheme.typography.bodySmall, color = if (summary.outstanding > 0.005) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
                Button(onClick = { addMode = !addMode }) { Text(if (addMode) "CANCEL ADD" else "ADD SUPPLIER DUE") }
                if (addMode) {
                    OutlinedTextField(supplier,{supplier=it},label={Text("Supplier / vendor name")},singleLine=true)
                    OutlinedTextField(phone,{phone=it},label={Text("Phone (optional)")},singleLine=true)
                    OutlinedTextField(description,{description=it},label={Text("Purchase / note")},singleLine=true)
                    OutlinedTextField(amount,{amount=it.filter { c -> c.isDigit() || c=='.' }},label={Text("Amount owed")},singleLine=true)
                    Button(onClick = { val value=amount.toDoubleOrNull(); if(value!=null && store.add(supplier,phone,description,value)){rows=store.all();supplier="";phone="";description="";amount="";addMode=false;changed()} }) { Text("SAVE PAYABLE") }
                }
                LazyColumn(Modifier.heightIn(max=340.dp)) {
                    items(rows,key={it.id}) { row ->
                        Card(Modifier.fillMaxWidth().padding(vertical=3.dp)) {
                            Column(Modifier.padding(10.dp), verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                Text(row.supplier, style=MaterialTheme.typography.titleMedium)
                                if(row.phone.isNotBlank()) Text(row.phone,style=MaterialTheme.typography.bodySmall)
                                if(row.description.isNotBlank()) Text(row.description,style=MaterialTheme.typography.bodySmall)
                                Text("Owed: ${CurrencySettings.symbol} ${row.balance.formatMoney()}  •  Original: ${row.total.formatMoney()}")
                                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick={paying=row;payment=""},enabled=row.balance>0.005){Text("RECORD PAYMENT")}
                                    TextButton(onClick={store.remove(row.id);rows=store.all();changed()}){Text("REMOVE")}
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick=close){Text("DONE")} }
    )
    paying?.let { due ->
        AlertDialog(onDismissRequest={paying=null},title={Text("Pay ${due.supplier}")},
            text={OutlinedTextField(payment,{payment=it.filter{c->c.isDigit()||c=='.'}},label={Text("Payment (balance ${due.balance.formatMoney()})")},singleLine=true)},
            confirmButton={TextButton(onClick={val n=payment.toDoubleOrNull();if(n!=null&&n>0){store.update(due.copy(paid=due.paid+n.coerceAtMost(due.balance)));rows=store.all();changed();paying=null}}){Text("SAVE PAYMENT")}},
            dismissButton={TextButton(onClick={paying=null}){Text("CANCEL")}})
    }
}
private data class SupplierSummary(
    val name: String,
    val purchased: Double,
    val receivedOrders: Int,
    val pendingOrders: Int,
    val paid: Double,
    val outstanding: Double
)
private fun Double.formatMoney(): String = String.format(java.util.Locale.US,"%,.2f",this)

private fun String.toStatementBoundary(endOfDay: Boolean): Long? {
    if (isBlank()) return null
    return runCatching {
        val date = java.time.LocalDate.parse(trim())
        val start = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (endOfDay) date.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() - 1 else start
    }.getOrNull()
}

private fun createSupplierStatementPdf(file: File, rows: List<List<Any>>, from: String, to: String) {
    val document = PdfDocument()
    val pageWidth = 595
    val pageHeight = 842
    val margin = 36f
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 43, 61); textSize = 20f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 79, 96); textSize = 9f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(45, 52, 65); textSize = 8f }
    val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(105, 112, 125); textSize = 9f }
    val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(210, 215, 224); strokeWidth = 0.8f }
    val columns = floatArrayOf(margin, 100f, 190f, 350f, 410f, 470f, 555f)
    var pageNumber = 0
    var page: PdfDocument.Page? = null
    var canvas: Canvas? = null
    var y = 0f
    fun startPage() {
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        canvas = page!!.canvas
        y = 42f
        if (pageNumber == 1) {
            canvas!!.drawText("SUPPLIER STATEMENT", margin, y, titlePaint); y += 22f
            canvas!!.drawText("Period: ${from.ifBlank { "All dates" }} to ${to.ifBlank { "All dates" }}", margin, y, mutedPaint); y += 24f
        } else {
            canvas!!.drawText("SUPPLIER STATEMENT (continued)", margin, y, titlePaint); y += 28f
        }
        val labels = listOf("DATE", "TYPE", "SUPPLIER", "AMOUNT", "PAID", "BALANCE")
        labels.forEachIndexed { index, label -> canvas!!.drawText(label, columns[index], y, headerPaint) }
        y += 8f
        canvas!!.drawLine(margin, y, pageWidth - margin, y, rulePaint)
        y += 16f
    }
    fun finishPage() { page?.let { document.finishPage(it) }; page = null; canvas = null }
    try {
        startPage()
        var amountTotal = 0.0
        var paidTotal = 0.0
        var balanceTotal = 0.0
        rows.forEach { row ->
            if (y > pageHeight - 65f) { finishPage(); startPage() }
            val timestamp = row[0] as Long
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(timestamp))
            val type = row[1].toString()
            val supplier = row[2].toString().take(25)
            val amount = row[4] as Double
            val paid = row[5] as Double
            val balance = row[6] as Double
            val status = row[7].toString()
            canvas!!.drawText(date, columns[0], y, bodyPaint)
            canvas!!.drawText(type.take(15), columns[1], y, bodyPaint)
            canvas!!.drawText(supplier, columns[2], y, bodyPaint)
            canvas!!.drawText(String.format(java.util.Locale.US, "%.2f", amount), columns[3], y, bodyPaint)
            canvas!!.drawText(String.format(java.util.Locale.US, "%.2f", paid), columns[4], y, bodyPaint)
            canvas!!.drawText(String.format(java.util.Locale.US, "%.2f", balance), columns[5], y, bodyPaint)
            y += 12f
            canvas!!.drawText("Details: ${row[3].toString().take(90)} • ${status}", columns[1], y, mutedPaint)
            y += 17f
            amountTotal += amount; paidTotal += paid; balanceTotal += balance
        }
        if (y > pageHeight - 85f) { finishPage(); startPage() }
        canvas!!.drawLine(margin, y, pageWidth - margin, y, rulePaint); y += 18f
        canvas!!.drawText("TOTALS", margin, y, headerPaint)
        canvas!!.drawText(String.format(java.util.Locale.US, "Amount: %.2f   Paid: %.2f   Balance: %.2f", amountTotal, paidTotal, balanceTotal), 115f, y, headerPaint)
        canvas!!.drawText("Generated by Voice POS", margin, pageHeight - 24f, mutedPaint)
        finishPage()
        file.outputStream().use { document.writeTo(it) }
    } finally {
        runCatching { document.close() }
    }
}
