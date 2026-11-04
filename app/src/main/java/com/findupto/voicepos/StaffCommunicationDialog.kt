package com.findupto.voicepos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun StaffCommunicationDialog(store: Store, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("voice_local_network", 0) }
    var host by remember { mutableStateOf(if (LocalNetworkSync.isRunning()) "127.0.0.1" else prefs.getString("owner_ip", "").orEmpty()) }
    val token = remember { prefs.getString("staff_session", "").orEmpty() }
    var messages by remember { mutableStateOf(emptyList<JSONObjectMessage>()) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(host, token) {
        while (true) {
            if (host.isNotBlank() && token.isNotBlank()) {
                runCatching {
                    val raw = withContext(Dispatchers.IO) { LocalNetworkSync.pullMessages(host, token) }
                    val parsed = (0 until raw.length()).mapNotNull { i ->
                        raw.optJSONObject(i)?.let { JSONObjectMessage(it.optString("id"), it.optString("author"), it.optString("role"), it.optString("message"), it.optLong("time")) }
                    }
                    messages = parsed
                    error = ""
                }.onFailure { error = it.message ?: "Communication unavailable" }
            } else error = "Connect this device to the Admin/Owner server to use staff communication."
            delay(3000)
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Staff communication") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 520.dp)) {
                OutlinedTextField(value = host, onValueChange = { host = it.trim(); prefs.edit().putString("owner_ip", it.trim()).apply() }, modifier = Modifier.fillMaxWidth(), label = { Text("Admin server address") }, singleLine = true)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (token.isBlank()) Text("Sign in with your staff PIN first. The team chat uses your staff session.", color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.weight(1f, fill = true), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages, key = { it.id }) { item ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text("${item.author} • ${item.role}", style = MaterialTheme.typography.labelMedium)
                                Text(item.message)
                                Text(SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(item.time)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = draft, onValueChange = { if (it.length <= 1000) draft = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Message to restaurant team") }, maxLines = 3)
            }
        },
        confirmButton = {
            Button(enabled = draft.isNotBlank() && !sending && host.isNotBlank() && token.isNotBlank(), onClick = {
                val message = draft.trim()
                sending = true
                error = ""
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { LocalNetworkSync.sendMessage(host, token, UUID.randomUUID().toString(), store.staffName(), store.staffRole(), message) }
                        draft = ""
                    }.onFailure { error = it.message ?: "Message could not be sent" }
                    sending = false
                }
            }) { Text(if (sending) "Sending…" else "Send") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

private data class JSONObjectMessage(val id: String, val author: String, val role: String, val message: String, val time: Long)
