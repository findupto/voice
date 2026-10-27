package com.findupto.voicepos

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class DiscountCard(
    val id: String, val token: String, val name: String, val phone: String,
    val type: String, val value: Double, val expiry: Long, val maxUses: Int, val uses: Int = 0
) {
    val qrPayload: String get() = "VOICEPOS-DISCOUNT|$id|$token"
    val expired: Boolean get() = expiry > 0L && System.currentTimeMillis() > expiry
    val exhausted: Boolean get() = maxUses > 0 && uses >= maxUses
    val active: Boolean get() = !expired && !exhausted
    fun discountLabel(): String = if (type == "PERCENT") "${value}% OFF" else "${money(value)} OFF"
}

class DiscountCardStore(context: Context) {
    private val prefs = context.getSharedPreferences("voice_pos_discount_cards_v1", Context.MODE_PRIVATE)
    fun cards(): List<DiscountCard> = runCatching {
        val arr = JSONArray(prefs.getString("cards", "[]"))
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { o ->
            DiscountCard(o.getString("id"), o.getString("token"), o.optString("name"), o.optString("phone"),
                o.optString("type", "PERCENT"), o.optDouble("value"), o.optLong("expiry"),
                o.optInt("maxUses"), o.optInt("uses"))
        } }
    }.getOrDefault(emptyList())
    private fun save(xs: List<DiscountCard>) {
        val arr = JSONArray()
        xs.forEach { c -> arr.put(JSONObject().put("id", c.id).put("token", c.token).put("name", c.name)
            .put("phone", c.phone).put("type", c.type).put("value", c.value).put("expiry", c.expiry)
            .put("maxUses", c.maxUses).put("uses", c.uses)) }
        prefs.edit().putString("cards", arr.toString()).apply()
    }
    fun create(name: String, phone: String, type: String, value: Double, expiry: Long, maxUses: Int): DiscountCard {
        val c = DiscountCard(UUID.randomUUID().toString(), UUID.randomUUID().toString().replace("-", ""),
            name.trim(), phone.trim(), type, value, expiry, maxUses)
        save(cards() + c)
        return c
    }
    fun validate(payload: String): DiscountCard? {
        val p = payload.trim().split("|")
        if (p.size != 3 || p[0] != "VOICEPOS-DISCOUNT") return null
        return cards().firstOrNull { it.id == p[1] && it.token == p[2] && it.active }
    }
    fun redeem(payload: String): Pair<DiscountCard?, String> {
        val p = payload.trim().split("|")
        if (p.size != 3 || p[0] != "VOICEPOS-DISCOUNT") return null to "Invalid discount QR code"
        val all = cards()
        val card = all.firstOrNull { it.id == p[1] && it.token == p[2] } ?: return null to "Card not found on this device"
        if (card.expired) return card to "Expired on ${card.expiry.toDateLabel()}"
        if (card.exhausted) return card to "Usage limit reached"
        save(all.map { if (it.id == card.id) it.copy(uses = it.uses + 1) else it })
        prefs.edit().putString("history", (prefs.getString("history", "") ?: "") + "${System.currentTimeMillis()}|${card.id}|${card.name.replace("|", " ")}|REDEEMED\n").apply()
        return card.copy(uses = card.uses + 1) to "Valid card redeemed successfully"
    }
    fun history(): List<String> = (prefs.getString("history", "") ?: "").lines().filter { it.isNotBlank() }.reversed()
    fun qr(payload: String, size: Int = 560): android.graphics.Bitmap {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        return android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
            for (x in 0 until size) for (y in 0 until size) b.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
}
private fun Long.toDateLabel(): String = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(this))
private fun String.dateAtEndOfDay(): Long? = runCatching {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
    fmt.parse(this)?.let { d -> java.util.Calendar.getInstance().apply { time = d; set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59); set(java.util.Calendar.SECOND, 59); set(java.util.Calendar.MILLISECOND, 999) }.timeInMillis }
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscountCardsDialog(onClose: () -> Unit, onScanImage: () -> Unit, scannedCode: String, clearScannedCode: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { DiscountCardStore(context) }
    var tick by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("PERCENT") }
    var value by remember { mutableStateOf("10") }
    var expiry by remember { mutableStateOf("") }
    var maxUses by remember { mutableStateOf("1") }
    var manualCode by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<DiscountCard?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    LaunchedEffect(scannedCode) { if (scannedCode.isNotBlank()) { manualCode = scannedCode; status = "QR image scanned. Tap VALIDATE / REDEEM."; clearScannedCode() } }
    val cards = remember(tick) { store.cards().sortedByDescending { it.id } }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().heightIn(max = 850.dp)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CardGiftcard, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("DISCOUNT CARDS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("CLOSE") }
                }
                Text("Create branded QR coupons, reward loyal customers, and track redemptions on this device.", style = MaterialTheme.typography.bodySmall)
                val activeCount = cards.count { it.active }
                val redeemedCount = cards.sumOf { it.uses }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("TOTAL CARDS" to cards.size.toString(), "ACTIVE" to activeCount.toString(), "REDEEMED" to redeemedCount.toString()).forEach { (label, amount) ->
                        Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(amount, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                                Text(label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("CREATE A CARD", fontWeight = FontWeight.Bold)
                Text("QUICK DISCOUNT PRESETS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("5", "10", "15", "20", "25").forEach { preset ->
                        FilterChip(selected = type == "PERCENT" && value == preset, onClick = { type = "PERCENT"; value = preset }, label = { Text("$preset%") })
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Customer name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("WhatsApp / phone number") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == "PERCENT", onClick = { type = "PERCENT" }, label = { Text("% Percent") })
                    FilterChip(selected = type == "FIXED", onClick = { type = "FIXED" }, label = { Text("Fixed amount") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value, { value = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, label = { Text(if (type == "PERCENT") "Discount %" else "Amount off") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    OutlinedTextField(maxUses, { maxUses = it.filter(Char::isDigit).take(4) }, label = { Text("Uses (0 = unlimited)") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                }
                OutlinedTextField(expiry, { expiry = it.filter { c -> c.isDigit() || c == '-' }.take(10) }, label = { Text("Expiry date (YYYY-MM-DD, optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = {
                    val amount = value.toDoubleOrNull()
                    val date = if (expiry.isBlank()) 0L else expiry.dateAtEndOfDay()
                    if (name.isBlank() || amount == null || amount <= 0 || (type == "PERCENT" && amount > 100) || maxUses.toIntOrNull() == null || maxUses.toInt() < 0 || (expiry.isNotBlank() && date == null)) {
                        status = "Enter a customer name, valid discount, usage limit and YYYY-MM-DD expiry."
                    } else {
                        selected = store.create(name, phone, type, amount, date ?: 0L, maxUses.toInt())
                        status = "Discount card created and saved."
                        name = ""; phone = ""; value = "10"; expiry = ""; maxUses = "1"; tick++
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("GENERATE DISCOUNT CARD + QR") }
                if (status.isNotBlank()) Text(status, color = if (status.contains("Invalid") || status.contains("Expired") || status.contains("not found") || status.contains("limit")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                selected?.let { c ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(c.discountLabel(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(c.name + if (c.phone.isNotBlank()) " • ${c.phone}" else "")
                            Text("Expiry: " + if (c.expiry == 0L) "No expiry" else c.expiry.toDateLabel())
                            Image(bitmap = remember(c.id) { store.qr(c.qrPayload).asImageBitmap() }, contentDescription = "Discount card QR code", modifier = Modifier.size(190.dp))
                            Text("Uses: ${c.uses}/${if (c.maxUses == 0) "∞" else c.maxUses}")
                            Button(onClick = { shareDiscountCard(context, store, c) }, modifier = Modifier.fillMaxWidth()) { Text("SHARE CARD") }
                        }
                    }
                }
                HorizontalDivider()
                Text("VALIDATE / REDEEM", fontWeight = FontWeight.Bold)
                OutlinedTextField(manualCode, { manualCode = it }, label = { Text("Scan QR image or paste card QR text") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onScanImage, modifier = Modifier.weight(1f)) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(5.dp)); Text("SCAN IMAGE") }
                    Button(onClick = {
                        val valid = store.validate(manualCode)
                        if (valid == null) {
                            val p = manualCode.trim().split("|")
                            val found = if (p.size == 3) store.cards().firstOrNull { it.id == p[1] && it.token == p[2] } else null
                            status = when { found?.expired == true -> "Expired on ${found.expiry.toDateLabel()}"; found?.exhausted == true -> "Usage limit reached"; else -> "Invalid QR code, expired card, or usage limit reached." }
                        } else {
                            val (redeemed, message) = store.redeem(manualCode)
                            status = if (redeemed != null) "$message — ${redeemed.discountLabel()}, use ${redeemed.uses}" else message
                            tick++
                        }
                    }, modifier = Modifier.weight(1f)) { Text("VALIDATE / REDEEM") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SAVED CARDS (${cards.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { historyOpen = !historyOpen }) { Text(if (historyOpen) "HIDE HISTORY" else "USE HISTORY") }
                }
                cards.forEach { c ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, fontWeight = FontWeight.SemiBold)
                                Text("${c.discountLabel()} • ${if (c.expiry == 0L) "No expiry" else c.expiry.toDateLabel()}", style = MaterialTheme.typography.bodySmall)
                                Text(if (c.expired) "EXPIRED" else if (c.exhausted) "LIMIT REACHED" else "ACTIVE • ${c.uses}/${if (c.maxUses == 0) "∞" else c.maxUses}", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { selected = c }) { Text("VIEW / SHARE") }
                        }
                    }
                }
                if (historyOpen) store.history().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun shareDiscountCard(context: Context, store: DiscountCardStore, card: DiscountCard) {
    runCatching {
        val qr = store.qr(card.qrPayload)
        val bitmap = android.graphics.Bitmap.createBitmap(qr.width, qr.height + 160, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(qr, 0f, 0f, null)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 28f; textAlign = android.graphics.Paint.Align.CENTER }
        canvas.drawText(card.discountLabel(), bitmap.width / 2f, qr.height + 38f, paint)
        canvas.drawText(card.name.take(34), bitmap.width / 2f, qr.height + 76f, paint)
        canvas.drawText("Expires: " + if (card.expiry == 0L) "Never" else card.expiry.toDateLabel(), bitmap.width / 2f, qr.height + 114f, paint)
        val directory = File(context.cacheDir, "shared_receipts").apply { mkdirs() }
        val file = File(directory, "discount_${card.id}.png")
        FileOutputStream(file).use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val text = "Discount card: ${card.discountLabel()}\nCustomer: ${card.name}\nCode: ${card.qrPayload}\nExpiry: ${if (card.expiry == 0L) "No expiry" else card.expiry.toDateLabel()}"
        val intent = Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, "Your discount card"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.startActivity(Intent.createChooser(intent, "Share discount card (choose WhatsApp)"))
    }.onFailure { android.widget.Toast.makeText(context, "Could not share card: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
}
