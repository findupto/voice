package com.findupto.voicepos

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

@Composable
fun EventPlanningDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("voice_event_plans", 0) }
    val savedPlans = remember { mutableStateListOf<JSONObject>().apply { runCatching { val array = JSONArray(prefs.getString("plans", "[]")); for (i in 0 until array.length()) add(array.getJSONObject(i)) } } }
    var title by remember { mutableStateOf("") }
    var customer by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var venue by remember { mutableStateOf("") }
    var guests by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var charges by remember { mutableStateOf("Venue|0\nFood & catering|0\nDecoration|0\nPhotography|0\nOther|0") }
    var status by remember { mutableStateOf("") }
    val rows = charges.lines().mapNotNull { line ->
        val parts = line.split("|", limit = 2)
        val amount = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
        if (parts.size == 2 && parts[0].isNotBlank() && amount != null && amount >= 0) parts[0].trim() to amount else null
    }
    val total = rows.sumOf { it.second }
    val currency = remember { NumberFormat.getNumberInstance(Locale("en", "PK")) }
    fun persistPlans() { prefs.edit().putString("plans", JSONArray().apply { savedPlans.forEach { put(it) } }.toString()).apply() }
    fun saveDraft() {
        if (title.isBlank()) { status = "Enter an event name before saving."; return }
        val draft = JSONObject().apply { put("id", System.currentTimeMillis()); put("title", title); put("customer", customer); put("phone", phone); put("date", date); put("venue", venue); put("guests", guests); put("notes", notes); put("charges", charges); put("status", "DRAFT") }
        savedPlans.add(0, draft); persistPlans(); status = "Draft saved on this device."
    }
    fun sharePoster() {
        if (title.isBlank() || date.isBlank()) { status = "Enter event name and date first."; return }
        runCatching {
            val bmp = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(android.graphics.Color.rgb(12, 20, 35))
            val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(155, 140, 255) }
            canvas.drawRoundRect(56f, 55f, 1024f, 310f, 34f, 34f, accent)
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 48f; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
            val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(20, 18, 40); textSize = 34f; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
            val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.LTGRAY; textSize = 32f }
            canvas.drawText("EVENT PROPOSAL", 96f, 125f, dark)
            canvas.drawText(title.take(34), 96f, 205f, dark)
            canvas.drawText("Prepared for: ${customer.ifBlank { "Valued Customer" }.take(34)}", 96f, 265f, dark)
            var y = 390f
            fun line(label: String, value: String) { canvas.drawText(label, 80f, y, white); y += 48f; canvas.drawText(value.take(58), 100f, y, muted); y += 68f }
            line("EVENT DETAILS", "$date  •  ${venue.ifBlank { "Venue to be confirmed" }}")
            if (guests.isNotBlank()) line("GUESTS", guests)
            canvas.drawText("CHARGES (PKR)", 80f, y, white); y += 60f
            rows.take(8).forEach { (name, amount) ->
                canvas.drawText(name.take(28), 100f, y, muted)
                val value = currency.format(amount)
                canvas.drawText(value, 970f - value.length * 18f, y, white)
                y += 56f
            }
            canvas.drawRect(80f, y, 1000f, y + 3f, accent); y += 75f
            val totalPaint = Paint(white).apply { textSize = 44f }
            canvas.drawText("TOTAL ESTIMATE", 80f, y, totalPaint)
            val totalText = "PKR ${currency.format(total)}"
            canvas.drawText(totalText, 970f - totalText.length * 23f, y, totalPaint)
            y += 90f
            if (notes.isNotBlank()) { canvas.drawText("NOTES", 80f, y, white); y += 48f; canvas.drawText(notes.take(60), 100f, y, muted) }
            canvas.drawText("Final pricing subject to management confirmation.", 80f, 1270f, muted.apply { textSize = 24f })
            val uri = MediaStore.Images.Media.insertImage(context.contentResolver, bmp, "Event-${title.take(24)}", "Voice POS event proposal")
                ?: error("Could not save proposal image")
            bmp.recycle()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(uri))
                putExtra(Intent.EXTRA_TEXT, "Event proposal: $title")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share event proposal"))
            status = "Proposal image created. Choose WhatsApp to share."
        }.onFailure { status = "Could not create image: ${it.message}" }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("EVENT PLANNING & QUOTATIONS", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Event name / type") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(customer, { customer = it }, label = { Text("Customer name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it }, label = { Text("WhatsApp number (optional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(date, { date = it }, label = { Text("Event date / time") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(venue, { venue = it }, label = { Text("Venue / location") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(guests, { guests = it }, label = { Text("Expected guests") }, modifier = Modifier.fillMaxWidth())
                Text("CHARGES — one item per line: Description|Amount (PKR)", fontWeight = FontWeight.Bold)
                OutlinedTextField(charges, { charges = it }, modifier = Modifier.fillMaxWidth(), minLines = 5)
                Text("Estimated total: PKR ${currency.format(total)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(notes, { notes = it }, label = { Text("Package details / terms / notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Text("Quote is an estimate until management confirms the final price, availability and booking.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = ::saveDraft, modifier = Modifier.weight(1f)) { Text("SAVE DRAFT") }
                    TextButton(onClick = { title = ""; customer = ""; phone = ""; date = ""; venue = ""; guests = ""; notes = ""; charges = "Venue|0\nFood & catering|0\nDecoration|0\nPhotography|0\nOther|0"; status = "New event form ready." }, modifier = Modifier.weight(1f)) { Text("NEW EVENT") }
                }
                if (savedPlans.isNotEmpty()) {
                    Text("SAVED EVENT DRAFTS (" + savedPlans.size + ")", fontWeight = FontWeight.Bold)
                    savedPlans.take(8).forEach { draft ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(draft.optString("title"), fontWeight = FontWeight.SemiBold)
                                Text(listOf(draft.optString("customer"), draft.optString("date")).filter { it.isNotBlank() }.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { title = draft.optString("title"); customer = draft.optString("customer"); phone = draft.optString("phone"); date = draft.optString("date"); venue = draft.optString("venue"); guests = draft.optString("guests"); notes = draft.optString("notes"); charges = draft.optString("charges"); status = "Draft loaded." }) { Text("LOAD") }
                            TextButton(onClick = { savedPlans.remove(draft); persistPlans(); status = "Draft deleted." }) { Text("DELETE") }
                        }
                    }
                }
                if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = { Button(onClick = ::sharePoster) { Text("SHARE AS IMAGE") } },
        dismissButton = { TextButton(onClick = onClose) { Text("CLOSE") } }
    )
}
