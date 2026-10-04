package com.findupto.voicepos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.concurrent.thread
import java.text.SimpleDateFormat
import java.util.*

private val PAYMENT_METHODS=listOf("CASH","CARD","EASYPAISA","JAZZCASH","BANK","RAAST","OTHER")
private fun fmt(v:Double)=String.format(Locale.US,"%,.0f",v)

@Composable fun SettlementDialog(order:PendingSale,onConfirm:(String,Double,Double)->Unit,onCancel:()->Unit){
 var method by remember{mutableStateOf("CASH")};var paid by remember{mutableStateOf(fmt(order.total))};var expanded by remember{mutableStateOf(false)}
 AlertDialog(onDismissRequest=onCancel,title={Text("Settle Order #${order.id}")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
  Text("Total ${CurrencySettings.symbol} ${fmt(order.total)}",fontWeight=FontWeight.Bold)
  ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=!expanded}){OutlinedTextField(value=method,onValueChange={},readOnly=true,label={Text("Payment account")},modifier=Modifier.menuAnchor().fillMaxWidth());ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){PAYMENT_METHODS.forEach{DropdownMenuItem(text={Text(it)},onClick={method=it;expanded=false})}}}
  OutlinedTextField(value=paid,onValueChange={paid=it.filter{c->c.isDigit()||c=='.'}},label={Text("Amount paid")},singleLine=true,modifier=Modifier.fillMaxWidth())
  val p=paid.toDoubleOrNull()?:0.0;val due=(order.total-p).coerceAtLeast(0.0);if(due>0)Text("Due balance: ${CurrencySettings.symbol} ${fmt(due)}",color=MaterialTheme.colorScheme.error,fontWeight=FontWeight.Bold)
 }},confirmButton={Button(onClick={val p=(paid.toDoubleOrNull()?:0.0).coerceIn(0.0,order.total);onConfirm(method,p,(order.total-p).coerceAtLeast(0.0))}){Text("SAVE PAYMENT")}},dismissButton={TextButton(onClick=onCancel){Text("CANCEL")}})
}

@Composable fun UltraControlCenter(store:Store,onClose:()->Unit){
 var tab by remember{mutableIntStateOf(0)};var tick by remember{mutableIntStateOf(0)};var dueSelected by remember{mutableStateOf<DueAccount?>(null)}
 Dialog(onDismissRequest=onClose){Surface(shape=MaterialTheme.shapes.extraLarge,modifier=Modifier.fillMaxWidth().heightIn(max=760.dp)){Column(Modifier.padding(18.dp)){
  Row(verticalAlignment=Alignment.CenterVertically){Text("ULTRA CONTROL CENTER",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));TextButton(onClick=onClose){Text("CLOSE")}}
  TabRow(selectedTabIndex=tab){listOf("SHIFT","MONEY","PRODUCTS","AI").forEachIndexed{i,t->Tab(selected=tab==i,onClick={tab=i},text={Text(t)})}}
  when(tab){0->ShiftPanel(store,{tick++});1->MoneyPanel(store,tick,{dueSelected=it});2->ProductPanel(store,tick);3->AiPanel(store)}
 }} }
 dueSelected?.let{d->DuePaymentDialog(d,store.activeShift()?.id?:0L,{amount,method->val ok=store.recordPayment(d.id,amount,method,store.activeShift()?.id?:0L);if(ok)tick++;dueSelected=null},{dueSelected=null})}
}

@Composable private fun ShiftPanel(store:Store,refresh:()->Unit){
 val s=store.activeShift();var person by remember{mutableStateOf("")};var opening by remember{mutableStateOf("0")};var closing by remember{mutableStateOf("0")};var closeMode by remember{mutableStateOf(false)}
 Column(Modifier.padding(top=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  if(s==null){Text("No active shift",fontWeight=FontWeight.Bold);OutlinedTextField(person,{person=it},label={Text("Cashier / shift person")},modifier=Modifier.fillMaxWidth());OutlinedTextField(opening,{opening=it.filter{c->c.isDigit()||c=='.'}},label={Text("Opening cash")},modifier=Modifier.fillMaxWidth());Button(onClick={if(person.isNotBlank()){store.openShift(person,opening.toDoubleOrNull()?:0.0);refresh()}},modifier=Modifier.fillMaxWidth()){Text("START SHIFT")}}
  else {Text("ACTIVE SHIFT",fontWeight=FontWeight.Bold);Text("${s.person} • ${SimpleDateFormat("dd MMM, hh:mm a",Locale.US).format(Date(s.openedAt))}");Text("Opening cash: ${CurrencySettings.symbol} ${fmt(s.openingCash)}");Text("Expected cash now: ${CurrencySettings.symbol} ${fmt(store.expectedShiftCash(s.id))}");Text("Sales: ${CurrencySettings.symbol} ${fmt(store.shiftSales(s.id).sumOf{it.total})}");Text("Expenses: ${CurrencySettings.symbol} ${fmt(store.shiftExpenses(s.id).sumOf{it.amount})}");Button(onClick={closeMode=true},modifier=Modifier.fillMaxWidth()){Text("CLOSE / HANDOVER SHIFT")}}
 }
 if(closeMode)AlertDialog(onDismissRequest={closeMode=false},title={Text("Close Shift")},text={OutlinedTextField(closing,{closing=it.filter{c->c.isDigit()||c=='.'}},label={Text("Counted cash")},singleLine=true)},confirmButton={Button(onClick={store.closeShift(s!!.id,closing.toDoubleOrNull()?:0.0);closeMode=false;refresh()}){Text("CLOSE SHIFT")}},dismissButton={TextButton(onClick={closeMode=false}){Text("CANCEL")}})
}

@Composable private fun MoneyPanel(store:Store,tick:Int,onDue:(DueAccount)->Unit){val totals=store.paymentTotals();val dues=store.dues();Column(Modifier.padding(top=12.dp)){Text("PAYMENT ACCOUNTS",fontWeight=FontWeight.Bold);PAYMENT_METHODS.forEach{Text("$it   ${CurrencySettings.symbol} ${fmt(totals[it]?:0.0)}",Modifier.padding(vertical=3.dp))};Text("Outstanding dues: ${CurrencySettings.symbol} ${fmt(store.dueTotal())}",fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=8.dp));LazyColumn(Modifier.heightIn(max=280.dp)){items(dues){d->Card(Modifier.fillMaxWidth().padding(vertical=4.dp)){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(d.customerName.ifBlank{"Walk-in"},fontWeight=FontWeight.Bold);Text("Sale #${d.saleId} • Due ${CurrencySettings.symbol} ${fmt(d.balance)}")};Button(onClick={onDue(d)}){Text("PAY")}}}}}}
}

@Composable private fun DuePaymentDialog(d:DueAccount,shiftId:Long,onDone:(Double,String)->Unit,onCancel:()->Unit){var amount by remember{mutableStateOf(fmt(d.balance))};var method by remember{mutableStateOf("CASH")};AlertDialog(onDismissRequest=onCancel,title={Text("Receive Due Payment")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("${d.customerName.ifBlank{"Customer"}} • Balance ${CurrencySettings.symbol} ${fmt(d.balance)}");OutlinedTextField(amount,{amount=it.filter{c->c.isDigit()||c=='.'}},label={Text("Amount")});Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){PAYMENT_METHODS.take(5).forEach{FilterChip(selected=method==it,onClick={method=it},label={Text(it)})}}}},confirmButton={Button(onClick={onDone((amount.toDoubleOrNull()?:0.0).coerceIn(0.0,d.balance),method)}){Text("RECORD PAYMENT")}},dismissButton={TextButton(onClick=onCancel){Text("CANCEL")}})
}

@Composable private fun ProductPanel(store:Store,tick:Int){var range by remember{mutableIntStateOf(0)};val now=System.currentTimeMillis();val start=when(range){0->StoreDay.start();1->now-7*86400000L;2->now-30*86400000L;else->0L};Column(Modifier.padding(top=12.dp)){Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("TODAY","7 DAYS","30 DAYS","ALL").forEachIndexed{i,t->FilterChip(selected=range==i,onClick={range=i},label={Text(t)})}};Text("PRODUCT QUANTITY SOLD",fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=10.dp));LazyColumn(Modifier.heightIn(max=430.dp)){items(store.productTotals(start)){(name,qty)->Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Text(name,Modifier.weight(1f));Text(qty.toString(),fontWeight=FontWeight.Bold)}}}}}

@Composable private fun AiPanel(store:Store){var q by remember{mutableStateOf("")};var answer by remember{mutableStateOf("Ask about sales, products, dues, payments, shifts or business performance.")};var busy by remember{mutableStateOf(false)};val main=android.os.Handler(android.os.Looper.getMainLooper());Column(Modifier.padding(top=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("BUILT-IN AI ASSISTANT",fontWeight=FontWeight.Bold);Text("Gemini Nano is used when the device supports ML Kit Prompt API; otherwise POS analytics are used locally.");OutlinedTextField(q,{q=it},label={Text("Ask your business assistant")},modifier=Modifier.fillMaxWidth(),minLines=2);Button(enabled=!busy,onClick={busy=true;val query=q;thread{val local=when{query.contains("today",true)->"Today gross sales: ${CurrencySettings.symbol} ${fmt(store.grossSales(StoreDay.start(),System.currentTimeMillis()))}. Realized: ${CurrencySettings.symbol} ${fmt(store.realizedSales(StoreDay.start(),System.currentTimeMillis()))}. Expenses: ${CurrencySettings.symbol} ${fmt(store.totalExpenses(StoreDay.start(),System.currentTimeMillis()))}. Due: ${CurrencySettings.symbol} ${fmt(store.dueTotal())}.";query.contains("top",true)||query.contains("best",true)->store.productTotals().take(8).joinToString(", "){"${it.first} ${it.second}"};else->null};val ai=if(local==null)AiEngine.rewriteVoiceBlocking(query,store.menu()) else null;main.post{answer=ai?:local?:"Gemini Nano is unavailable on this device. Try a supported device or ask the built-in analytics questions.";busy=false}}}){Text(if(busy)"THINKING…" else "ASK AI")};Text(answer)}}
object StoreDay{fun start():Long=Calendar.getInstance().apply{set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)}.timeInMillis}
