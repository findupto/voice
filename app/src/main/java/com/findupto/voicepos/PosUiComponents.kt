package com.findupto.voicepos
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp


private fun shareText(context: android.content.Context, title: String, body: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
private fun kitchenShareText(order: PendingSale, profile: CompanyProfile): String = buildString {
    append(profile.name).append("\nKITCHEN ORDER #").append(order.id).append("\n")
    append("Type: ").append(order.orderType.replace('_',' ')).append("\n")
    append("Time: ").append(date(order.time)).append("\n")
    if(order.customerName.isNotBlank()) append("Customer: ").append(order.customerName).append("\n")
    if(order.customerPhone.isNotBlank()) append("Phone: ").append(order.customerPhone).append("\n")
    if(order.customerAddress.isNotBlank()) append("Address: ").append(order.customerAddress).append("\n")
    append("------------------------------\n")
    order.items.filterNot { it.name.startsWith(CART_DISCOUNT_PREFIX) }.forEach {
        append(it.qty).append(" x ").append(it.name).append("\n")
    }
}
private fun customerQuoteText(order: PendingSale, profile: CompanyProfile): String = buildString {
    val subtotal = order.total
    val service = if (profile.serviceChargeEnabled) subtotal * profile.serviceChargeRate / 100.0 else 0.0
    val tax = if (profile.taxEnabled) (subtotal + service) * profile.taxRate / 100.0 else 0.0
    append(profile.name).append("\n")
    if (profile.phone.isNotBlank()) append(profile.phone).append("\n")
    if (profile.address.isNotBlank()) append(profile.address).append("\n")
    append("ORDER QUOTATION #").append(order.id).append("\n")
    append("Date: ").append(date(order.time)).append("\n")
    append("Type: ").append(order.orderType.replace('_',' ')).append("\n")
    if(order.customerName.isNotBlank()) append("Customer: ").append(order.customerName).append("\n")
    if(order.customerPhone.isNotBlank()) append("Phone: ").append(order.customerPhone).append("\n")
    if(order.customerAddress.isNotBlank()) append("Delivery address: ").append(order.customerAddress).append("\n")
    append("------------------------------\n")
    order.items.forEach {
        if (it.name.startsWith(CART_DISCOUNT_PREFIX)) {
            append("Discount: -").append(money(kotlin.math.abs(it.total))).append("\n")
        } else {
            append(it.qty).append(" x ").append(it.name).append(" — ").append(money(it.total)).append("\n")
        }
    }
    append("------------------------------\n")
    append("Subtotal: ").append(money(subtotal)).append("\n")
    if (profile.serviceChargeEnabled) append(profile.serviceChargeLabel).append(": ").append(money(service)).append("\n")
    if (profile.taxEnabled) append(profile.taxLabel).append(": ").append(money(tax)).append("\n")
    append("ESTIMATED TOTAL: ").append(money(subtotal + service + tax)).append("\n")
    append(profile.footer)
}
private fun customerShareText(sale: Sale, profile: CompanyProfile): String = buildString {
    val subtotal = sale.items.sumOf { it.total }
    append(profile.name).append("\n")
    if (profile.phone.isNotBlank()) append(profile.phone).append("\n")
    if (profile.address.isNotBlank()) append(profile.address).append("\n")
    append("RECEIPT #").append(sale.id).append("\n")
    append("Date: ").append(date(sale.time)).append("\n")
    if(sale.customerName.isNotBlank()) append("Customer: ").append(sale.customerName).append("\n")
    if(sale.customerPhone.isNotBlank()) append("Phone: ").append(sale.customerPhone).append("\n")
    append("------------------------------\n")
    sale.items.forEach {
        if (it.name.startsWith(CART_DISCOUNT_PREFIX)) {
            append("Discount: -").append(money(kotlin.math.abs(it.total))).append("\n")
        } else {
            append(it.qty).append(" x ").append(it.name).append(" — ").append(money(it.total)).append("\n")
        }
    }
    append("------------------------------\n")
    append("Subtotal: ").append(money(subtotal)).append("\n")
    if (sale.serviceCharge > 0.005) append(profile.serviceChargeLabel).append(": ").append(money(sale.serviceCharge)).append("\n")
    if (sale.tax > 0.005) append(profile.taxLabel).append(": ").append(money(sale.tax)).append("\n")
    append("TOTAL: ").append(money(sale.total)).append("\n")
    append("Paid (").append(sale.paymentMethod).append("): ").append(money(sale.paidAmount)).append("\n")
    if(sale.dueAmount > 0.005) append("Balance due: ").append(money(sale.dueAmount)).append("\n")
    append(profile.footer)
}

@Composable fun QuickSale(cart:List<SaleItem>,voiceStatus:String,send:()->Unit,listen:()->Unit,openSpeechSettings:()->Unit,clear:()->Unit,manual:()->Unit,changeQty:(SaleItem,Int)->Unit){Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Mic,null);Spacer(Modifier.width(8.dp));Column(Modifier.weight(1f)){Text("Power Voice",fontWeight=FontWeight.Bold);Text(voiceStatus,style=MaterialTheme.typography.bodySmall)};IconButton(onClick=listen){Icon(Icons.Default.MicNone,"Listen")}};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick=manual,Modifier.weight(1f)){Text("MANUAL ADD")};OutlinedButton(onClick=listen,Modifier.weight(1f)){Text("VOICE")}};if(voiceStatus.contains("error",true)||voiceStatus.contains("unavailable",true)||voiceStatus.contains("failed",true))OutlinedButton(onClick=openSpeechSettings,Modifier.fillMaxWidth()){Text("Check speech language / settings")};Text("Try: 2 shawarma, 1 fries / add 3 tea / change shawarma price to 250",style=MaterialTheme.typography.bodySmall)}};Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Current Order",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);if(cart.isNotEmpty())TextButton(onClick=clear){Text("Clear")}};if(cart.isEmpty())EmptyState(Icons.Default.ShoppingCart,"No items","Use voice or manual add");cart.forEach{item->Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(item.name,fontWeight=FontWeight.SemiBold);Text("${item.qty} × ${money(item.price)}",style=MaterialTheme.typography.bodySmall)};IconButton(onClick={changeQty(item,-1)}){Icon(Icons.Default.Remove,null)};Text(money(item.total));IconButton(onClick={changeQty(item,1)}){Icon(Icons.Default.Add,null)}}};if(cart.isNotEmpty()){HorizontalDivider();Row(Modifier.fillMaxWidth().padding(top=10.dp),horizontalArrangement=Arrangement.SpaceBetween){Text("TOTAL",fontWeight=FontWeight.Bold);Text(money(cart.sumOf{it.total}),fontWeight=FontWeight.Bold)};Button(onClick=send,Modifier.fillMaxWidth().padding(top=10.dp)){Text("CUSTOMER / ORDER TYPE / PRINT")}}}}}}

@Composable fun ManualCartDialog(menu:List<MenuItem>,add:(MenuItem)->Unit,addCustom:(String,Int,Double)->Unit,close:()->Unit){
var query by remember{mutableStateOf("")};var name by remember{mutableStateOf("")};var qty by remember{mutableStateOf("1")};var price by remember{mutableStateOf("")}
val filtered=menu.filter{it.name.contains(query,true)||it.variant.contains(query,true)||it.size.contains(query,true)}.take(30)
AlertDialog(onDismissRequest=close,title={Text("Add Product")},text={Column(Modifier.fillMaxWidth().heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
Text("Quick custom product",fontWeight=FontWeight.Bold)
OutlinedTextField(value=name,onValueChange={name=it},modifier=Modifier.fillMaxWidth(),label={Text("Product — not in menu? Type it here")},singleLine=true)
Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
OutlinedTextField(value=qty,onValueChange={qty=it.filter(Char::isDigit).take(4)},modifier=Modifier.weight(1f),label={Text("Qty")},singleLine=true)
OutlinedTextField(value=price,onValueChange={price=it.filter{ch->ch.isDigit()||ch=='.'}.take(12)},modifier=Modifier.weight(1f),label={Text("Unit price")},singleLine=true)
}
Button(onClick={val q=qty.toIntOrNull()?:0;val p=price.toDoubleOrNull();if(name.isNotBlank()&&q>0&&p!=null&&p>=0){addCustom(name.trim(),q,p);name="";price="";qty="1"}},Modifier.fillMaxWidth()){Text("ADD CUSTOM PRODUCT")}
HorizontalDivider()
Text("Or choose from menu",fontWeight=FontWeight.Bold)
OutlinedTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth(),label={Text("Search menu")},singleLine=true)
filtered.forEach{item->Card(Modifier.fillMaxWidth().clickable{add(item)}){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(item.name,fontWeight=FontWeight.SemiBold);val meta=listOf(item.variant,item.size).filter(String::isNotBlank).joinToString(" • ");if(meta.isNotBlank())Text(meta);Text(money(item.price))};Icon(Icons.Default.Add,"Add item")}}}
}},confirmButton={TextButton(onClick=close){Text("DONE")}})
}
@Composable fun MenuPage(menu:List<MenuItem>,store:Store,refresh:()->Unit,exportMenu:()->Unit,importMenu:()->Unit,scanDocument:()->Unit,addToCart:(MenuItem)->Unit){var adding by remember{mutableStateOf(false)};Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){Button(onClick={adding=true},Modifier.weight(1f)){Text("ADD")};OutlinedButton(onClick=exportMenu,Modifier.weight(1f)){Text("EXPORT")};OutlinedButton(onClick=importMenu,Modifier.weight(1f)){Text("IMPORT")}};Button(onClick=scanDocument,Modifier.fillMaxWidth()){Icon(Icons.Default.DocumentScanner,null);Spacer(Modifier.width(6.dp));Text("SMART MENU SCANNER — IMAGE / PDF / DOCX")};LazyColumn{items(menu){item->Card(Modifier.fillMaxWidth().padding(vertical=3.dp).clickable{addToCart(item)}){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(item.name,fontWeight=FontWeight.SemiBold);Text(listOf(item.variant,item.size).filter(String::isNotBlank).joinToString(" • "));Text(money(item.price))};IconButton(onClick={store.removeMenuItem(item.id);refresh()}){Icon(Icons.Default.Delete,"Delete")}}}}}};if(adding)AddMenuDialog({n,v,s,p->store.addMenuItem(MenuItem(System.currentTimeMillis(),n.trim(),p,v.trim(),s.trim()));adding=false;refresh()}){adding=false}}
@Composable fun AddMenuDialog(save:(String,String,String,Double)->Unit,close:()->Unit){var n by remember{mutableStateOf("")};var v by remember{mutableStateOf("")};var s by remember{mutableStateOf("")};var p by remember{mutableStateOf("")};AlertDialog(onDismissRequest=close,title={Text("Product / Variant / Size")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){OutlinedTextField(n,{n=it},label={Text("Product")});OutlinedTextField(v,{v=it},label={Text("Variant")});OutlinedTextField(s,{s=it},label={Text("Size")});OutlinedTextField(p,{p=it},label={Text("Price")})}},confirmButton={TextButton(onClick={p.toDoubleOrNull()?.takeIf{it>0}?.let{if(n.isNotBlank())save(n,v,s,it)}}){Text("SAVE")}},dismissButton={TextButton(onClick=close){Text("CANCEL")}})}
@Composable
fun KitchenQueue(
    queue: List<PendingSale>,
    profile: CompanyProfile,
    printer: PrinterManager,
    pay: (PendingSale) -> Unit,
    edit: (PendingSale) -> Unit
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Kitchen Queue", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        LazyColumn {
            items(queue) { order ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { edit(order) }) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("#${order.id} • ${order.orderType.replace('_', ' ')}", fontWeight = FontWeight.Bold)
                                Text(order.items.joinToString(", ") { "${it.qty}× ${it.name}" })
                                if (order.customerName.isNotBlank() || order.customerPhone.isNotBlank()) {
                                    Text(listOf(order.customerName, order.customerPhone).filter(String::isNotBlank).joinToString(" • "))
                                }
                                if (order.customerAddress.isNotBlank()) Text(order.customerAddress, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { pay(order) }) { Text("PAY & PRINT") }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { shareText(context, "Share customer quotation", customerQuoteText(order, profile)) }) {
                                Text("SHARE CUSTOMER QUOTE")
                            }
                            OutlinedButton(onClick = { shareText(context, "Share kitchen slip", kitchenShareText(order, profile)) }) {
                                Text("SHARE KITCHEN SLIP")
                            }
                            OutlinedButton(onClick = { printer.printKitchen(kitchenReceipt(order, profile, ReceiptTheme.CLASSIC)) }) {
                                Text("PRINT SLIP")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable fun EditDialog(order:PendingSale,save:(PendingSale)->Unit,close:()->Unit){var items by remember{mutableStateOf(order.items)};AlertDialog(onDismissRequest=close,title={Text("Edit Order")},text={Column{items.forEach{item->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("${item.qty} × ${item.name}",Modifier.weight(1f));IconButton(onClick={items=items.map{x->if(x==item)x.copy(qty=x.qty-1)else x}.filter{it.qty>0}}){Icon(Icons.Default.Remove,null)}}}}},confirmButton={TextButton(onClick={save(order.copy(items=items))}){Text("SAVE")}},dismissButton={TextButton(onClick=close){Text("CLOSE")}})}
@Composable fun SalesPage(sales:List<Sale>,open:(Sale)->Unit){Column(Modifier.fillMaxSize().padding(16.dp)){Text("Sales",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);LazyColumn{items(sales.reversed()){sale->Card(Modifier.fillMaxWidth().padding(vertical=4.dp).clickable{open(sale)}){Row(Modifier.padding(12.dp)){Column(Modifier.weight(1f)){Text("Receipt #${sale.id} • ${sale.orderType.replace('_',' ')}",fontWeight=FontWeight.Bold);if(sale.customerName.isNotBlank()||sale.customerPhone.isNotBlank())Text(listOf(sale.customerName,sale.customerPhone).filter(String::isNotBlank).joinToString(" • "));if(sale.customerAddress.isNotBlank())Text(sale.customerAddress,style=MaterialTheme.typography.bodySmall);Text(date(sale.time))};Text(money(sale.total))}}}}}}
@Composable fun SaleDetailDialog(sale:Sale,profile:CompanyProfile,printer:PrinterManager,theme:ReceiptTheme,close:()->Unit){
val context=LocalContext.current
AlertDialog(onDismissRequest=close,title={Text("Receipt #${sale.id}")},text={Column(Modifier.verticalScroll(rememberScrollState())){Text(sale.orderType.replace('_',' '),fontWeight=FontWeight.Bold);if(sale.customerName.isNotBlank()||sale.customerPhone.isNotBlank())Text("Customer: ${listOf(sale.customerName,sale.customerPhone).filter(String::isNotBlank).joinToString(" • ")}",fontWeight=FontWeight.Bold);if(sale.customerAddress.isNotBlank())Text("Address: ${sale.customerAddress}");sale.items.forEach{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("${it.qty} × ${it.name}");Text(money(it.total))}};HorizontalDivider();Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("TOTAL",fontWeight=FontWeight.Bold);Text(money(sale.total),fontWeight=FontWeight.Bold)}}},
confirmButton={Row{TextButton(onClick={shareText(context,"Share customer receipt",customerShareText(sale,profile))}){Text("SHARE")};TextButton(onClick={printer.printCustomer(customerReceipt(sale,profile,theme))}){Text("RE-PRINT")}}},
dismissButton={TextButton(onClick=close){Text("CLOSE")}})
}
@Composable fun CustomerDetailsDialog(done:(String,String,String,String)->Unit,close:()->Unit){var n by remember{mutableStateOf("")};var p by remember{mutableStateOf("")};var a by remember{mutableStateOf("")};var type by remember{mutableStateOf("DINE_IN")};AlertDialog(onDismissRequest=close,title={Text("Customer & Order")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Order Type",fontWeight=FontWeight.Bold);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("DINE_IN","TAKEAWAY","DELIVERY").forEach{x->FilterChip(selected=type==x,onClick={type=x},label={Text(x.replace('_',' '))})}};OutlinedTextField(value=n,onValueChange={n=it},label={Text("Customer Name")},singleLine=true);OutlinedTextField(value=p,onValueChange={p=it},label={Text("Phone")},singleLine=true);if(type=="DELIVERY")OutlinedTextField(value=a,onValueChange={a=it},label={Text("Delivery Address")},minLines=2)}},confirmButton={TextButton(onClick={done(n,p,type,a)}){Text("PRINT KITCHEN")}},dismissButton={TextButton(onClick=close){Text("SKIP")}})}
@Composable fun ExpensesPage(expenses:List<Expense>,add:()->Unit){Column(Modifier.fillMaxSize().padding(16.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Expenses",style=MaterialTheme.typography.headlineSmall);Button(onClick=add){Text("ADD")}};LazyColumn{items(expenses.reversed()){ListItem(headlineContent={Text(it.title)},supportingContent={Text(it.category)},trailingContent={Text(money(it.amount))})}}}}
@Composable fun Analytics(sales:List<Sale>,expenses:List<Expense>,cash:Double){val sv=sales.sumOf{it.total};val ev=expenses.sumOf{it.amount};Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())){Text("Analytics",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Metric("Sales",sv,Icons.Default.TrendingUp);Metric("Expenses",ev,Icons.Default.TrendingDown);Metric("Cash",cash,Icons.Default.AccountBalanceWallet);Metric("Net",sv-ev,Icons.Default.AccountBalance)}}
@Composable fun Metric(title:String,value:Double,icon:ImageVector){Card(Modifier.fillMaxWidth().padding(vertical=4.dp)){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null);Text(title,Modifier.weight(1f));Text(money(value),fontWeight=FontWeight.Bold)}}}
@Composable fun EmptyState(icon:ImageVector,title:String,body:String){Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(icon,null);Text(title,fontWeight=FontWeight.Bold);Text(body)}}
@Composable fun OpeningCash(done:(Double)->Unit){var v by remember{mutableStateOf("")};AlertDialog(onDismissRequest={},title={Text("Opening Cash")},text={OutlinedTextField(v,{v=it},label={Text("Cash")})},confirmButton={TextButton(onClick={done(v.toDoubleOrNull()?:0.0)}){Text("CONTINUE")}})}
@Composable fun ExpenseDialog(done:(String,String,Double)->Unit){var n by remember{mutableStateOf("")};var c by remember{mutableStateOf("")};var a by remember{mutableStateOf("")};AlertDialog(onDismissRequest={},title={Text("Expense")},text={Column{OutlinedTextField(n,{n=it},label={Text("Description")});OutlinedTextField(c,{c=it},label={Text("Category")});OutlinedTextField(a,{a=it},label={Text("Amount")})}},confirmButton={TextButton(onClick={a.toDoubleOrNull()?.let{done(n,c,it)}}){Text("SAVE")}})}
@Composable fun SettingsPage(store:Store,printer:PrinterManager,pickLogo:()->Unit,exportBackup:()->Unit,importBackup:()->Unit,logoVersion:Int,close:()->Unit){var profile by remember(logoVersion){mutableStateOf(store.profile())};var theme by remember(logoVersion){mutableStateOf(store.theme())};AlertDialog(onDismissRequest=close,title={Text("Settings")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(7.dp)){Text("Business Transfer",fontWeight=FontWeight.Bold);Row(Modifier.fillMaxWidth()){Button(onClick=exportBackup,Modifier.weight(1f)){Text("EXPORT")};OutlinedButton(onClick=importBackup,Modifier.weight(1f)){Text("IMPORT")}};OutlinedTextField(profile.name,{profile=profile.copy(name=it)},label={Text("Company Name")});OutlinedTextField(profile.phone,{profile=profile.copy(phone=it)},label={Text("Phone")});OutlinedTextField(profile.address,{profile=profile.copy(address=it)},label={Text("Address")},minLines=3);OutlinedTextField(profile.footer,{profile=profile.copy(footer=it)},label={Text("Footer")});Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(if(profile.logoPath.isBlank())"Company logo not uploaded"else"Company logo uploaded ✓",Modifier.weight(1f));OutlinedButton(onClick=pickLogo){Text("UPLOAD / CHANGE LOGO")}};if(profile.logoPath.isNotBlank())Text("Saved locally • ${FileStatus.size(profile.logoPath)}",style=MaterialTheme.typography.bodySmall);Text("Receipt style");Row(Modifier.horizontalScroll(rememberScrollState())){ReceiptTheme.values().forEach{t->FilterChip(theme==t,{theme=t},label={Text(t.name)})}}}},confirmButton={TextButton(onClick={store.saveProfile(profile.copy(saleLogoEnabled=true,kitchenLogoEnabled=false));store.setTheme(theme);close()}){Text("SAVE")}})}
object FileStatus{fun size(path:String):String=runCatching{val f=java.io.File(path);if(f.exists())"${(f.length()/1024).coerceAtLeast(1)} KB"else"file missing"}.getOrDefault("file missing")}
