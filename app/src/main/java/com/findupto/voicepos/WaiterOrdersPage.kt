package com.findupto.voicepos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun WaiterOrdersPage(
    pending: List<PendingSale>,
    sales: List<Sale>,
    store: Store,
    edit: (PendingSale) -> Unit,
    bill: (PendingSale) -> Unit,
    advance: (PendingSale, String) -> Unit,
    openSale: (Sale) -> Unit
) {
    val name = store.staffName()
    val myPending = pending.filter { it.waiterName.equals(name, true) || (it.waiterName.isBlank() && it.shiftPerson.equals(name, true)) }
        .sortedByDescending { it.time }
    val myShiftIds = store.shifts().filter { it.person.equals(name, true) }.map { it.id }.toSet()
    val history = sales.filter { it.shiftId in myShiftIds }.sortedByDescending { it.time }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("My Orders", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("${myPending.size} active • ${history.size} billed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("ACTIVE ORDERS", fontWeight = FontWeight.Bold) }
            if (myPending.isEmpty()) item { Text("No active orders assigned to you.") }
            items(myPending, key = { "p${it.id}" }) { order ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${order.tableName.ifBlank { order.orderType.replace('_', ' ') }} • #${order.id}", fontWeight = FontWeight.Bold)
                        Text("${order.status} • ${order.items.sumOf { it.qty }} items")
                        Text(order.items.joinToString { "${it.qty}× ${it.name}" }, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { edit(order) }) { Text("ADD / MODIFY") }
                            Button(onClick = { bill(order) }, enabled = order.status !in setOf("CANCELLED")) { Text("BILL / PRINT") }
                            if (order.status == "READY" && order.orderType != "DELIVERY") OutlinedButton(onClick = { advance(order, "SERVED") }) { Text("MARK SERVED") }
                            if (order.status == "READY" && order.orderType == "DELIVERY") OutlinedButton(onClick = { advance(order, "OUT_FOR_DELIVERY") }) { Text("DISPATCH") }
                            if (order.status == "OUT_FOR_DELIVERY") OutlinedButton(onClick = { advance(order, "DELIVERED") }) { Text("DELIVERED") }
                        }
                    }
                }
            }
            item { HorizontalDivider(); Text("BILLED HISTORY", fontWeight = FontWeight.Bold) }
            if (history.isEmpty()) item { Text("No billed orders recorded on this waiter shift yet.") }
            items(history, key = { "s${it.id}" }) { sale ->
                Card(Modifier.fillMaxWidth().clickable { openSale(sale) }) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("${sale.orderType.replace('_', ' ')} • #${sale.id}", fontWeight = FontWeight.Bold)
                            Text("${sale.items.sumOf { it.qty }} items • ${date(sale.time)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(money(sale.total), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
