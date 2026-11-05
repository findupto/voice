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
    var permissionTarget by remember { mutableStateOf<ManagedStaffAccount?>(null) }
    var selectedPermissions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var permissionBusy by remember { mutableStateOf(false) }

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

    fun openPermissions(account: ManagedStaffAccount) {
        if (permissionBusy || busyName.isNotBlank()) return
        permissionBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LocalNetworkSync.staffPermissions(host, session) }
            }
            permissionBusy = false
            result.onSuccess { json ->
                val row = (0 until json.length()).mapNotNull { json.optJSONObject(it) }
                    .firstOrNull { it.optString("name").equals(account.name, true) }
                selectedPermissions = row?.optJSONArray("permissions")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }.toSet()
                } ?: RolePermissions.defaultsFor(account.role)
                permissionTarget = account
            }.onFailure { message = it.message ?: "Could not load permissions." }
        }
    }

    fun savePermissions(account: ManagedStaffAccount) {
        if (permissionBusy) return
        permissionBusy = true
        val permissions = selectedPermissions
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LocalNetworkSync.setStaffPermissions(host, session, account.name, permissions) }
            }
            permissionBusy = false
            result.onSuccess {
                permissionTarget = null
                message = "Permissions updated for ${account.name}. Changes apply immediately."
            }.onFailure { message = it.message ?: "Could not update permissions." }
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
                                if (account.role != "OWNER") TextButton(
                                    onClick = { openPermissions(account) },
                                    enabled = busyName.isBlank() && !permissionBusy
                                ) { Text("PERMISSIONS") }
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

    permissionTarget?.let { account ->
        AlertDialog(
            onDismissRequest = { if (!permissionBusy) permissionTarget = null },
            title = { Text("Permissions — ${account.name}") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Choose exactly what this staff account can do. Changes are enforced by the main device, not just hidden in the interface.", style = MaterialTheme.typography.bodySmall)
                    val labels = mapOf(
                        "view_orders" to "View live and completed orders",
                        "create_order" to "Create / submit orders",
                        "update_order_status" to "Advance order workflow",
                        "update_kitchen" to "Update kitchen station progress",
                        "view_messages" to "Read team messages",
                        "send_messages" to "Send team messages",
                        "view_shifts" to "View shift records",
                        "manage_shifts" to "Synchronize / manage shifts",
                        "view_audit" to "View order activity audit",
                        "manage_staff" to "Manage staff accounts"
                    )
                    RolePermissions.all.sorted().forEach { permission ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(
                                checked = permission in selectedPermissions,
                                onCheckedChange = { checked ->
                                    selectedPermissions = if (checked) selectedPermissions + permission else selectedPermissions - permission
                                },
                                enabled = !permissionBusy
                            )
                            Text(labels[permission] ?: permission, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { savePermissions(account) }, enabled = !permissionBusy) {
                    Text(if (permissionBusy) "SAVING…" else "SAVE PERMISSIONS")
                }
            },
            dismissButton = {
                TextButton(onClick = { permissionTarget = null }, enabled = !permissionBusy) { Text("CANCEL") }
            }
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
