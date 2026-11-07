package com.findupto.voicepos

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@SuppressLint("MissingPermission")
@Composable fun PrinterSettingsDialog(store:Store,printer:PrinterManager,close:()->Unit){
    var paperWidth by remember(store) { mutableIntStateOf(store.receiptPaperWidthMm()) }
    AlertDialog(
        onDismissRequest=close,
        title={Text("Printer Manager")},
        text={
            Column(Modifier.fillMaxWidth().heightIn(max=620.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text("RECEIPT PAPER WIDTH",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    FilterChip(selected=paperWidth==58,onClick={paperWidth=58;store.setReceiptPaperWidthMm(58)},label={Text("58 mm")})
                    FilterChip(selected=paperWidth==80,onClick={paperWidth=80;store.setReceiptPaperWidthMm(80)},label={Text("80 mm")})
                }
                Text(if(paperWidth==58)"58 mm layout • 32 character columns" else "80 mm layout • 48 character columns",style=MaterialTheme.typography.bodySmall)
                Text("CUSTOMER RECEIPT PRINTER",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelLarge)
                Text(if(printer.customerConnected)"Connected ✓" else "Not connected",style=MaterialTheme.typography.bodySmall)
                Text("KITCHEN PRINTER",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelLarge)
                Text(if(printer.kitchenConnected)"Connected ✓" else "Not connected",style=MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button(onClick={printer.discoverNow()},Modifier.weight(1f),enabled=!printer.discovering){
                        Icon(Icons.Default.Bluetooth,null);Spacer(Modifier.width(4.dp));Text(if(printer.discovering)"SCANNING…" else "SCAN PRINTERS")
                    }
                    OutlinedButton(onClick={printer.refresh()},Modifier.weight(1f)){Text("REFRESH")}
                }
                if(printer.lastStatus.isNotBlank()){
                    Card(Modifier.fillMaxWidth()){
                        Text(printer.lastStatus,Modifier.padding(10.dp),style=MaterialTheme.typography.bodySmall,
                            color=if(printer.lastError.isNotBlank())MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }
                Text("Pair printers in Android Bluetooth settings first. Select a printer below for either role.",style=MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max=250.dp)){
                    items(printer.devices){d->
                        val name=runCatching{d.name}.getOrNull().orEmpty().ifBlank{"Bluetooth printer"}
                        val address=runCatching{d.address}.getOrNull().orEmpty()
                        Card(Modifier.fillMaxWidth().padding(vertical=3.dp)){
                            Column(Modifier.padding(10.dp)){
                                Text(name,fontWeight=FontWeight.SemiBold)
                                Text(address,style=MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                    Button(onClick={printer.connectCustomer(d)},Modifier.weight(1f)){Text("RECEIPT")}
                                    OutlinedButton(onClick={printer.connectKitchen(d)},Modifier.weight(1f)){Text("KITCHEN")}
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedButton(onClick={printer.testCustomer()},Modifier.weight(1f),enabled=printer.customerConnected&&!printer.printing){Text("TEST RECEIPT")}
                    OutlinedButton(onClick={printer.testKitchen()},Modifier.weight(1f),enabled=printer.kitchenConnected&&!printer.printing){Text("TEST KITCHEN")}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    TextButton(onClick={printer.disconnectCustomer()},Modifier.weight(1f),enabled=printer.customerConnected){Text("DISCONNECT RECEIPT")}
                    TextButton(onClick={printer.disconnectKitchen()},Modifier.weight(1f),enabled=printer.kitchenConnected){Text("DISCONNECT KITCHEN")}
                }
                Text("Print jobs are queued off the UI thread and sent in order. If a job fails, reconnect the printer and retry the sale receipt from Sale Details.",style=MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton={TextButton(onClick=close){Text("DONE")}}
    )
}

@Composable fun BusinessSettingsDialog(store:Store,close:()->Unit){val initial=store.profile();var symbol by remember{mutableStateOf(initial.currencySymbol)};var code by remember{mutableStateOf(initial.currencyCode)};var taxEnabled by remember{mutableStateOf(initial.taxEnabled)};var taxRate by remember{mutableStateOf(initial.taxRate.toString().trimEnd('0').trimEnd('.'))};var taxLabel by remember{mutableStateOf(initial.taxLabel)};var serviceEnabled by remember{mutableStateOf(initial.serviceChargeEnabled)};var serviceRate by remember{mutableStateOf(initial.serviceChargeRate.toString().trimEnd('0').trimEnd('.'))};var serviceLabel by remember{mutableStateOf(initial.serviceChargeLabel)};AlertDialog(onDismissRequest=close,title={Text("Tax, Currency & Service")},text={Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){Text("Currency",fontWeight=FontWeight.Bold);OutlinedTextField(symbol,{symbol=it},label={Text("Currency symbol")},singleLine=true,modifier=Modifier.fillMaxWidth());OutlinedTextField(code,{code=it.uppercase().take(3)},label={Text("Currency code")},singleLine=true,modifier=Modifier.fillMaxWidth());HorizontalDivider();Text("Tax",fontWeight=FontWeight.Bold);Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Enable tax",Modifier.weight(1f));Switch(taxEnabled,{taxEnabled=it})};OutlinedTextField(taxLabel,{taxLabel=it},label={Text("Tax label")},singleLine=true,modifier=Modifier.fillMaxWidth());OutlinedTextField(taxRate,{taxRate=it.filter{c->c.isDigit()||c=='.'}},label={Text("Tax rate %")},singleLine=true,modifier=Modifier.fillMaxWidth());HorizontalDivider();Text("Service Charge",fontWeight=FontWeight.Bold);Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Enable service charge",Modifier.weight(1f));Switch(serviceEnabled,{serviceEnabled=it})};OutlinedTextField(serviceLabel,{serviceLabel=it},label={Text("Service charge label")},singleLine=true,modifier=Modifier.fillMaxWidth());OutlinedTextField(serviceRate,{serviceRate=it.filter{c->c.isDigit()||c=='.'}},label={Text("Service charge %")},singleLine=true,modifier=Modifier.fillMaxWidth());Text("Service charge is added before tax and shown separately on receipts.",style=MaterialTheme.typography.bodySmall)}},confirmButton={TextButton(onClick={val tr=taxRate.toDoubleOrNull()?.coerceAtLeast(0.0)?:0.0;val sr=serviceRate.toDoubleOrNull()?.coerceAtLeast(0.0)?:0.0;store.saveProfile(initial.copy(currencySymbol=symbol.trim().ifBlank{"Rs"},currencyCode=code.trim().ifBlank{"PKR"}.uppercase(),taxEnabled=taxEnabled,taxRate=tr,taxLabel=taxLabel.trim().ifBlank{"Tax"},serviceChargeEnabled=serviceEnabled,serviceChargeRate=sr,serviceChargeLabel=serviceLabel.trim().ifBlank{"Service Charge"}));close()}){Text("SAVE")}},dismissButton={TextButton(onClick=close){Text("CANCEL")}})}


@Composable fun BusinessAutomationDialog(context: android.content.Context, close: () -> Unit) {
    val settings = remember(context) { BusinessAutomationStore(context) }
    var enabled by remember { mutableStateOf(settings.automationEnabled) }
    var stock by remember { mutableStateOf(settings.lowStockAlerts) }
    var orders by remember { mutableStateOf(settings.delayedOrderAlerts) }
    var summary by remember { mutableStateOf(settings.dailySummary) }
    var drafts by remember { mutableStateOf(settings.purchaseDrafts) }
    var dues by remember { mutableStateOf(settings.overdueDuesAlerts) }
    var shifts by remember { mutableStateOf(settings.shiftReminders) }
    var profit by remember { mutableStateOf(settings.profitInsights) }
    val reorderDrafts = remember(context) { runCatching { org.json.JSONArray(context.getSharedPreferences("voice_business_automation_v1", android.content.Context.MODE_PRIVATE).getString("purchase_order_drafts", "[]")) }.getOrDefault(org.json.JSONArray()) }
    val liveStore = remember(context) { Store(context) }
    val liveInventory = remember(context) { InventoryStore(context) }
    val liveSupplierDues = remember(context) { SupplierDueStore(context) }
    val lowStockCount = remember(context) { liveInventory.lowStock().size }
    val openOrderCount = remember(context) { liveStore.pending().count { it.status !in setOf("SERVED", "DELIVERED", "BILLED") } }
    val customerDueCount = remember(context) { liveStore.dues().count { it.balance > 0.005 } }
    val supplierDueCount = remember(context) { liveSupplierDues.all().count { it.balance > 0.005 } }
    val activeShiftCount = remember(context) { liveStore.activeShifts().size }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Business Automation") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Let Voice POS monitor routine operations in the background. Financial actions and supplier orders always require owner approval.", style = MaterialTheme.typography.bodySmall)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("LIVE BUSINESS PULSE", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        Text("$lowStockCount low-stock items • $openOrderCount active orders")
                        Text("$customerDueCount customer balances • $supplierDueCount supplier bills")
                        Text("$activeShiftCount open staff shifts • Last check: " +
                            if (settings.lastRun() == 0L) "Not run yet" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(settings.lastRun())),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Automation engine", fontWeight = FontWeight.Bold); Text("Enable scheduled checks", style = MaterialTheme.typography.bodySmall) }
                    Switch(enabled, { enabled = it })
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Low-stock alerts"); Text("Notify when ingredients reach minimum", style = MaterialTheme.typography.bodySmall) }; Switch(stock, { stock = it }) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Delayed-order alerts"); Text("Notify when open orders wait 15+ minutes", style = MaterialTheme.typography.bodySmall) }; Switch(orders, { orders = it }) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Daily business snapshot"); Text("Sales, revenue, expenses and customer dues", style = MaterialTheme.typography.bodySmall) }; Switch(summary, { summary = it }) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Purchase-order drafts"); Text("Prepare suggested top-ups; owner approves orders", style = MaterialTheme.typography.bodySmall) }; Switch(drafts, { drafts = it }) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Overdue payment alerts"); Text("Customer and supplier balances 7+ days old", style = MaterialTheme.typography.bodySmall) }; Switch(dues, { dues = it }) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Staff shift reminders"); Text("Flag shifts still open after 12 hours", style = MaterialTheme.typography.bodySmall) }; Switch(shifts, { shifts = it }) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Profit insights"); Text("Estimate recipe cost and net profit daily", style = MaterialTheme.typography.bodySmall) }; Switch(profit, { profit = it }) }
                Text("Saved reorder draft: " + if (reorderDrafts.length() == 0) "No low-stock items currently flagged." else (0 until reorderDrafts.length()).joinToString { i -> val item = reorderDrafts.optJSONObject(i) ?: org.json.JSONObject(); item.optString("name") + " • " + item.optDouble("quantity") + " " + item.optString("unit") }, style = MaterialTheme.typography.bodySmall)
                Text("Checks run approximately hourly. Android battery settings and notification permission can affect delivery.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.automationEnabled = enabled
                settings.lowStockAlerts = stock
                settings.delayedOrderAlerts = orders
                settings.dailySummary = summary
                settings.purchaseDrafts = drafts
                settings.overdueDuesAlerts = dues
                settings.shiftReminders = shifts
                settings.profitInsights = profit
                if (enabled) BusinessAutomation.runNow(context)
                close()
            }) { Text("SAVE & RUN") }
        },
        dismissButton = { TextButton(onClick = close) { Text("CANCEL") } }
    )
}
