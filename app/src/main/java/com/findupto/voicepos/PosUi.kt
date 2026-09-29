package com.findupto.voicepos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable fun VoicePosTheme(content:@Composable()->Unit)=MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF9B8CFF),secondary=Color(0xFF42D6A4)),content=content)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PosApp(store:Store,printer:PrinterManager,heard:String,clearHeard:()->Unit,listen:()->Unit){
 var tab by remember{mutableIntStateOf(0)}
 var cart by remember{mutableStateOf(emptyList<SaleItem>())}
 var cash by remember{mutableStateOf(store.cash())}
 var tick by remember{mutableIntStateOf(0)}
 var filter by remember{mutableIntStateOf(0)}
 var edit by remember{mutableStateOf<PendingSale?>(null)}
 var settings by remember{mutableStateOf(false)}
 var expense by remember{mutableStateOf(false)}
 var opening by remember{mutableStateOf(!store.hasOpeningCash())}
 val p=remember(tick){store.profile()};val sales=remember(tick){store.sales()};val expenses=remember(tick){store.expenses()};val queue=remember(tick){store.pending()}
 LaunchedEffect(heard){if(heard.isNotBlank()){VoiceCommandEngine.parse(heard).forEach{c->when(c){
 is VoiceCommand.Add->{val i=cart.indexOfFirst{it.name.equals(c.item.name,true)&&it.price==c.item.price};cart=if(i>=0)cart.toMutableList().also{it[i]=it[i].copy(qty=it[i].qty+c.item.qty)}else cart+c.item}
 VoiceCommand.Clear->{cart=emptyList()};VoiceCommand.Sales->{tab=2};VoiceCommand.Expenses->{tab=3};VoiceCommand.Reports->{tab=4};VoiceCommand.Settings->{settings=true}
 is VoiceCommand.Remove->{cart=cart.filterNot{it.name.contains(c.name,true)}}
 VoiceCommand.CompletePrint->{if(cart.isNotEmpty()){val q=PendingSale(System.currentTimeMillis(),cart,System.currentTimeMillis());store.addPending(q);printer.printKitchen(kitchenReceipt(q,p,store.theme()));cart=emptyList();tick++}}
 VoiceCommand.Complete->{}
 }};clearHeard()}}
 fun send(){if(cart.isEmpty())return;val q=PendingSale(System.currentTimeMillis(),cart,System.currentTimeMillis());store.addPending(q);printer.printKitchen(kitchenReceipt(q,p,store.theme()));cart=emptyList();tick++}
 fun pay(q:PendingSale){val s=Sale(q.id,q.items,q.total,System.currentTimeMillis());store.addSale(s);cash+=s.total;store.setCash(cash);store.removePending(q.id);printer.printCustomer(customerReceipt(s,p,store.theme()));tick++}
 Scaffold(topBar={CenterAlignedTopAppBar(title={Column(horizontalAlignment=Alignment.CenterHorizontally){Text(p.name,fontWeight=FontWeight.Bold);Text("PREMIUM POS",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)}},actions={IconButton({settings=true}){Icon(Icons.Default.Settings,null)}})},bottomBar={NavigationBar{listOf("Sale","Kitchen","Sales","Expenses","Analytics").forEachIndexed{i,n->NavigationBarItem(tab==i,{tab=i},icon={Icon(listOf(Icons.Default.PointOfSale,Icons.Default.Restaurant,Icons.Default.ReceiptLong,Icons.Default.Payments,Icons.Default.Insights)[i],null)},label={Text(n)})}}},floatingActionButton={FloatingActionButton(listen,shape=CircleShape){Icon(Icons.Default.Mic,null)}}){pad->Box(Modifier.fillMaxSize().padding(pad)){when(tab){
 0->QuickSale(cart,send,listen,{cart=emptyList()}){item,d->cart=cart.map{if(it==item)it.copy(qty=(it.qty+d).coerceAtLeast(0))else it}.filter{it.qty>0}}
 1->KitchenQueue(queue,p,printer,::pay){edit=it}
 2->SalesPage(sales.filter{inRange(it.time,filter)},filter,{filter=it},p,printer)
 3->ExpensesPage(expenses.filter{inRange(it.time,filter)},filter,{filter=it}){expense=true}
 4->Analytics(sales.filter{inRange(it.time,filter)},expenses.filter{inRange(it.time,filter)},cash,filter,{filter=it})
}}}
 if(opening)OpeningCash{cash=it;opening=false}
 if(expense)ExpenseDialog{n,c,a->store.addExpense(Expense(System.currentTimeMillis(),n,c,a,System.currentTimeMillis()));cash-=a;store.setCash(cash);tick++;expense=false}
 if(settings)SettingsPage(store,printer){settings=false;tick++}
 edit?.let{EditDialog(it,{store.replacePending(it);edit=null;tick++},{edit=null})}
}

@Composable private fun QuickSale(cart:List<SaleItem>,send:()->Unit,listen:()->Unit,clear:()->Unit,qty:(SaleItem,Int)->Unit){
 Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("Voice checkout",fontWeight=FontWeight.Bold);Text("Kitchen first • payment later • editable");IconButton(listen){Icon(Icons.Default.Mic,null)}
   if(cart.isEmpty())EmptyState(Icons.Default.Mic,"Ready for voice","Say: 2 shawarma price 300 each, 1 fries price 150 each")else{
    cart.forEach{item->Row(Modifier.fillMaxWidth().padding(6.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(item.name);Text(item.qty.toString()+" × "+money(item.price))};IconButton({qty(item,-1)}){Icon(Icons.Default.RemoveCircleOutline,null)};Text(money(item.total));IconButton({qty(item,1)}){Icon(Icons.Default.AddCircleOutline,null)}}}
    HorizontalDivider();Row(Modifier.fillMaxWidth(),Arrangement.SpaceBetween){Text("TOTAL",fontWeight=FontWeight.Bold);Text(money(cart.sumOf{it.total}),fontWeight=FontWeight.Bold)}
    Button(send,Modifier.fillMaxWidth()){Icon(Icons.Default.Restaurant,null);Spacer(Modifier.width(6.dp));Text("PRINT KITCHEN & HOLD PAYMENT")}
   }
  }}
 }
}

@Composable private fun KitchenQueue(q:List<PendingSale>,p:CompanyProfile,pr:PrinterManager,pay:(PendingSale)->Unit,edit:(PendingSale)->Unit){
 Column(Modifier.fillMaxSize().padding(16.dp)){
  Row(Modifier.fillMaxWidth(),Arrangement.SpaceBetween,Alignment.CenterVertically){Column{Text("Kitchen Queue",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(q.size.toString()+" unpaid")};Button({q.forEach{pr.printKitchen(kitchenReceipt(it,p,ReceiptTheme.MODERN))}},enabled=q.isNotEmpty()){Text("Print all one-by-one")}}
  LazyColumn{items(q){s->Card(Modifier.fillMaxWidth().padding(5.dp).clickable{edit(s)}){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Restaurant,null);Column(Modifier.weight(1f)){Text("#"+s.id,fontWeight=FontWeight.Bold);Text(s.items.joinToString(", "){it.qty.toString()+"× "+it.name});Text(money(s.total))};TextButton({pay(s)}){Text("PAY & PRINT")}}}}}
 }
}

@Composable private fun EditDialog(s:PendingSale,save:(PendingSale)->Unit,close:()->Unit){
 var items by remember{mutableStateOf(s.items)};var name by remember{mutableStateOf("")};var price by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text("Edit kitchen slip #"+s.id)},text={Column(Modifier.verticalScroll(rememberScrollState())){
  items.forEach{it->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(it.name);Text(it.qty.toString()+" × "+money(it.price))};IconButton({items=items.map{x->if(x==it)x.copy(qty=x.qty-1)else x}.filter{x->x.qty>0}}){Icon(Icons.Default.Remove,null)}}}
  HorizontalDivider();OutlinedTextField(name,{name=it},label={Text("Add item")});OutlinedTextField(price,{price=it},label={Text("Price")})
 }},confirmButton={TextButton({val v=price.toDoubleOrNull();save(s.copy(items=if(name.isNotBlank()&&v!=null)items+SaleItem(name,1,v)else items))}){Text("Save")}},dismissButton={TextButton(close){Text("Cancel")}})
}

@Composable private fun SalesPage(s:List<Sale>,f:Int,set:(Int)->Unit,p:CompanyProfile,pr:PrinterManager){
 Column(Modifier.fillMaxSize().padding(16.dp)){Text("Paid Sales",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);FilterRow(f,set);LazyColumn{items(s.reversed()){x->Card(Modifier.fillMaxWidth().padding(4.dp)){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("#"+x.id,fontWeight=FontWeight.Bold);Text(x.items.joinToString(", "){it.qty.toString()+"× "+it.name});Text(money(x.total))};TextButton({pr.printCustomer(customerReceipt(x,p,ReceiptTheme.MODERN))}){Text("Re-print")}}}}}}}
@Composable private fun ExpensesPage(e:List<Expense>,f:Int,set:(Int)->Unit,add:()->Unit){Column(Modifier.fillMaxSize().padding(16.dp)){Row(Modifier.fillMaxWidth(),Arrangement.SpaceBetween,Alignment.CenterVertically){Text("Expenses",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Button(add){Text("Add")}};FilterRow(f,set);LazyColumn{items(e.reversed()){x->ListItem(headlineContent={Text(x.title)},supportingContent={Text(x.category+" • "+date(x.time))},trailingContent={Text(money(x.amount))})}}}}
@Composable private fun FilterRow(f:Int,set:(Int)->Unit){Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf("Today","7 days","30 days","All").forEachIndexed{i,n->FilterChip(f==i,{set(i)},label={Text(n)})}}}
@Composable private fun Analytics(s:List<Sale>,e:List<Expense>,cash:Double,f:Int,set:(Int)->Unit){val sv=s.sumOf{it.total};val ev=e.sumOf{it.amount};Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())){Text("Analytics",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);FilterRow(f,set);Metric("Sales",sv,Icons.Default.TrendingUp);Metric("Expenses",ev,Icons.Default.TrendingDown);Metric("Cash",cash,Icons.Default.AccountBalanceWallet);Metric("Net",sv-ev,Icons.Default.AccountBalance)}}
@Composable private fun Metric(t:String,v:Double,i:ImageVector){Card(Modifier.fillMaxWidth().padding(5.dp)){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(i,null);Spacer(Modifier.width(12.dp));Text(t,Modifier.weight(1f));Text(money(v),fontWeight=FontWeight.Bold)}}}
@Composable private fun EmptyState(i:ImageVector,t:String,b:String){Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(i,null,Modifier.size(40.dp));Text(t,fontWeight=FontWeight.Bold);Text(b,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
@Composable private fun OpeningCash(done:(Double)->Unit){var v by remember{mutableStateOf("")};AlertDialog(onDismissRequest={},title={Text("Opening Cash")},text={OutlinedTextField(v,{v=it},label={Text("Cash in hand")})},confirmButton={TextButton({done(v.toDoubleOrNull()?:0.0)}){Text("Continue")}})}
@Composable private fun ExpenseDialog(done:(String,String,Double)->Unit){var n by remember{mutableStateOf("")};var c by remember{mutableStateOf("")};var a by remember{mutableStateOf("")};AlertDialog(onDismissRequest={},title={Text("Add Expense")},text={Column{OutlinedTextField(n,{n=it},label={Text("Description")});OutlinedTextField(c,{c=it},label={Text("Category")});OutlinedTextField(a,{a=it},label={Text("Amount")})}},confirmButton={TextButton({a.toDoubleOrNull()?.let{done(n,c,it)}}){Text("Save")}},dismissButton={TextButton({}){Text("Close")}})}
@Composable private fun SettingsPage(store:Store,pr:PrinterManager,close:()->Unit){
 var p by remember{mutableStateOf(store.profile())};var t by remember{mutableStateOf(store.theme())};var cp by remember{mutableStateOf(store.customerPrinterAddress())};var kp by remember{mutableStateOf(store.kitchenPrinterAddress())}
 AlertDialog(onDismissRequest=close,title={Text("Premium Print Settings")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
  OutlinedTextField(p.name,{p=p.copy(name=it)},label={Text("Slip header")});OutlinedTextField(p.address,{p=p.copy(address=it)},label={Text("Address")});OutlinedTextField(p.phone,{p=p.copy(phone=it)},label={Text("Phone")});OutlinedTextField(p.footer,{p=p.copy(footer=it)},label={Text("Slip footer")})
  Text("Receipt style",fontWeight=FontWeight.Bold);Row{ReceiptTheme.values().forEach{x->FilterChip(t==x,{t=x},label={Text(x.name)})}}
  Button({pr.discover()}){Text(if(pr.discovering)"Discovering…" else "Find Bluetooth printers")}
  pr.devices.forEach{d->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(d.name?:d.address,Modifier.weight(1f),maxLines=1);TextButton({cp=d.address}){Text(if(cp==d.address)"Receipt ✓" else "Receipt")};TextButton({kp=d.address}){Text(if(kp==d.address)"Kitchen ✓" else "Kitchen")}}}
 }},confirmButton={TextButton({store.saveProfile(p);store.setTheme(t);store.setCustomerPrinterAddress(cp);store.setKitchenPrinterAddress(kp);pr.device(cp)?.let{pr.connectCustomer(it)};pr.device(kp)?.let{pr.connectKitchen(it)};close()}){Text("Save")}})
}
