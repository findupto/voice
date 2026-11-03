package com.findupto.voicepos

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun LocalNetworkDialog(store: Store, onClose: () -> Unit) {
    val context = LocalContext.current
    var hosting by remember { mutableStateOf(LocalNetworkSync.isRunning()) }
    var ip by remember { mutableStateOf("") }
    var code by remember { mutableStateOf(context.getSharedPreferences("voice_local_network",0).getString("staff_session","") ?: "") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("No internet or cloud account is required.") }
    val localIp = remember(hosting) { LocalNetworkSync.ipAddress() }
    val accessCode = remember { LocalNetworkSync.token(context) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("LOCAL RESTAURANT NETWORK", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Use one mobile as the main data server. Other phones join through the same Wi-Fi or an already-enabled hotspot.")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("MAIN SERVER — THIS DEVICE", fontWeight = FontWeight.Bold)
                        Text(if (hosting) "Server is running" else "Server is stopped", color = if (hosting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        if (hosting) {
                            Text("Server address", style = MaterialTheme.typography.labelMedium)
                            Text("$localIp:8765", fontWeight = FontWeight.Bold)
                            Text("Access code", style = MaterialTheme.typography.labelMedium)
                            SelectionContainer { Text(accessCode, fontWeight = FontWeight.Bold) }
                            Text("Share this address and access code with staff devices on the same network.", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = {
                            if (hosting) {
                                LocalNetworkSync.stop()
                                hosting = false
                                message = "Main server stopped."
                            } else {
                                hosting = LocalNetworkSync.start(context, store)
                                message = if (hosting) "Local server is ready. Keep this app open while staff are connected." else "Could not start server. Check whether port 8765 is already in use."
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (hosting) "STOP MAIN SERVER" else "START MAIN SERVER")
                        }
                    }
                }
                HorizontalDivider()
                Text("CONNECT THIS DEVICE TO OWNER", fontWeight = FontWeight.Bold)
                OutlinedTextField(value = ip, onValueChange = { ip = it; context.getSharedPreferences("voice_local_network", 0).edit().putString("owner_ip", it).apply() }, label = { Text("Owner phone IP address") }, placeholder = { Text("192.168.1.10") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = code, onValueChange = { code = it; context.getSharedPreferences("voice_local_network", 0).edit().putString("owner_token", it).apply() }, label = { Text("Staff session token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    if (ip.isBlank() || code.isBlank()) {
                        message = "Enter the owner's IP address and access code."
                    } else {
                        busy = true
                        message = "Synchronizing…"
                        Thread {
                            val result = runCatching { LocalNetworkSync.sync(ip, code, store) }
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                busy = false
                                message = result.getOrElse { "Sync failed: ${it.message}" }
                                if (result.isSuccess) Toast.makeText(context, "Restaurant data synchronized", Toast.LENGTH_SHORT).show()
                            }
                        }.start()
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "SYNCHRONIZING…" else "SYNC WITH MAIN DEVICE")
                }
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Text("Network safety: Voice POS will not enable/disable Wi-Fi, switch networks, or start a hotspot automatically. Connect both phones to the same existing Wi-Fi/hotspot first.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE") } }
    )
}
