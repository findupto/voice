package com.findupto.voicepos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

private data class ManagedStaffAccount(val name: String, val role: String, val active: Boolean)

@Composable
fun StaffAccountsDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("voice_local_network", 0) }
    val scope = rememberCoroutineScope()
    val session = prefs.getString("staff_session", "").orEmpty()
    val host = remember {
        if (LocalNetworkSync.isRunning()) "127.0.0.1"
        else prefs.getString("owner_ip", "").orEmpty()
    }
    var accounts by remember { mutableStateOf<List<ManagedStaffAccount>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var busyName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Load staff accounts from the main POS device.") }
    var resetTarget by remember { mutableStateOf<ManagedStaffAccount?>(null) }
    var newPin by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<ManagedStaffAccount?>(null) }
    var editName by remember { mutableStateOf("") }
    var editRole by remember { mutableStateOf("WAITER") }

    fun refresh() {
        if (loading || busyName.isNotBlank()) return
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LocalNetworkSync.listStaff(host, session) }
            }
            loading = false
            result.onSuccess { json: JSONArray ->
                accounts = (0 until json.length()).mapNotNull { index ->
                    json.optJSONObject(index)?.let {
                        ManagedStaffAccount(
                            it.optString("name"),
                            it.optString("role"),
                            it.optBoolean("active", true)
                        )
                    }
                }.sortedBy { it.name.lowercase() }
                message = "${accounts.size} staff account(s) loaded."
            }.onFailure { message = it.message ?: "Could not load staff accounts." }
        }
    }

    fun updateAccount(account: ManagedStaffAccount, active: Boolean) {
        if (busyName.isNotBlank()) return
        busyName = account.name
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LocalNetworkSync.setStaffActive(host, session, account.name, active) }
            }
            busyName = ""
            result.onSuccess {
                message = if (active) "${account.name} enabled." else "${account.name} disabled; their sessions were revoked."
                refresh()
            }.onFailure { message = it.message ?: "Could not update account." }
        }
    }

    fun savePin(account: ManagedStaffAccount) {
        if (newPin.length !in 4..8 || busyName.isNotBlank()) return
        busyName = account.name
        val pin = newPin
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LocalNetworkSync.resetStaffPin(host, session, account.name, pin) }
            }
            busyName = ""
            result.onSuccess {
                newPin = ""
                resetTarget = null
                message = "PIN reset for ${account.name}. Their previous sessions were revoked."
                refresh()
            }.onFailure { message = it.message ?: "Could not reset PIN." }
        }
    }

    fun saveEdit(account: ManagedStaffAccount) {
        val updatedName = editName.trim()
        if (updatedName.isBlank() || updatedName.length > 80 || busyName.isNotBlank()) return
        busyName = account.name
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LocalNetworkSync.editStaffAccount(host, session, account.name, updatedName, editRole) }
            }
            busyName = ""
            result.onSuccess {
                editTarget = null
                message = "Updated $updatedName ($editRole). Their previous sessions were revoked."
                refresh()
            }.onFailure { message = it.message ?: "Could not edit account." }
        }
    }

    LaunchedEffect(host, session) { refresh() }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("STAFF ACCOUNT MANAGEMENT", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(if (session.isNotBlank()) "Owner and Admin staff controls. Admins can manage regular staff; only Owners can manage privileged accounts." else "Sign in as an Owner or Admin on the main POS device to manage staff.")
                if (host.isBlank() || session.isBlank()) {
                    Text("Sign in as OWNER or ADMIN on the main device before managing accounts.", color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(
                    onClick = ::refresh,
                    enabled = !loading && busyName.isBlank() && host.isNotBlank() && session.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (loading) "LOADING…" else "REFRESH STAFF LIST") }

                accounts.forEach { account ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(account.name, fontWeight = FontWeight.Bold)
                                    Text(account.role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                }
                                Text(
                                    if (account.active) "ACTIVE" else "DISABLED",
                                    color = if (account.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { updateAccount(account, !account.active) },
                                    enabled = busyName.isBlank() && account.name.isNotBlank()
                                ) { Text(if (account.active) "DISABLE" else "ENABLE") }
                                TextButton(
                                    onClick = { editTarget = account; editName = account.name; editRole = account.role },
                                    enabled = busyName.isBlank()
                                ) { Text("EDIT") }
                                TextButton(
                                    onClick = { resetTarget = account; newPin = "" },
                                    enabled = busyName.isBlank()
                                ) { Text("RESET PIN") }
                            }
                        }
                    }
                }
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE") } }
    )

    editTarget?.let { account ->
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Edit staff — ${account.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it.take(80) },
                        label = { Text("Staff name") },
                        singleLine = true
                    )
                    Text("Staff role", style = MaterialTheme.typography.labelMedium)
                    val roles = listOf("ADMIN", "COUNTER", "WAITER", "CHEF", "DISHWASHER", "RIDER") +
                        if (account.role == "OWNER") listOf("OWNER") else emptyList()
                    roles.forEach { role ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = editRole == role, onClick = { editRole = role })
                            Text(role)
                        }
                    }
                    Text("Changing the name or role signs this staff member out on all devices.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = { saveEdit(account) },
                    enabled = editName.isNotBlank() && busyName.isBlank()
                ) { Text(if (busyName == account.name) "SAVING…" else "SAVE CHANGES") }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("CANCEL") } }
        )
    }

    resetTarget?.let { account ->
        AlertDialog(
            onDismissRequest = { resetTarget = null; newPin = "" },
            title = { Text("Reset PIN — ${account.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { newPin = it.filter(Char::isDigit).take(8) },
                        label = { Text("New 4–8 digit PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Text("All existing sessions for this staff member will be signed out.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = { savePin(account) },
                    enabled = newPin.length in 4..8 && busyName.isBlank()
                ) { Text(if (busyName == account.name) "SAVING…" else "RESET PIN") }
            },
            dismissButton = {
                TextButton(onClick = { resetTarget = null; newPin = "" }) { Text("CANCEL") }
            }
        )
    }
}
