package com.findupto.voicepos

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.*

@Composable
fun VoicePosTheme(content:@Composable()->Unit){
    val dark=darkColorScheme(primary=androidx.compose.ui.graphics.Color(0xFF8B7BFF),secondary=androidx.compose.ui.graphics.Color(0xFF36D399),background=androidx.compose.ui.graphics.Color(0xFF080A0F),surface=androidx.compose.ui.graphics.Color(0xFF11141B))
    MaterialTheme(colorScheme=dark,content=content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosApp(store:Store,printer:PrinterManager,heard:String,clearHeard:()->Unit,listen:()->Unit){
    var tab by remember{mutableIntStateOf(0)}
    var cart by remember{mutableStateOf(emptyList<SaleItem>())}
    var cash by remember{mutableStateOf(store.cash())}
    var tick by remember{mutableIntStateOf(0)}
    var filter by remember{mutableIntStateOf(0)}
    var saleDetail by remember{mutableStateOf<Sale?>(null)}
    var settings by remember{mutableStateOf(false)}
    var expense by remember{mutableStateOf(false)}
    var opening by remember{mutableStateOf(!store.hasOpeningCash())}
    var message by remember{mutableStateOf("")}
    val profile=remember(tick){store.profile()}
    val sales=remember(tick){store.sales()}
    val expenses=remember(tick){store.expenses()}

    LaunchedEffect(heard){
        if(heard.isBlank())return@LaunchedEffect
        VoiceCommandEngine.parse(heard).forEach{cmd->
            when(cmd){
                is VoiceCommand.Add->{val i=cart.indexOfFirst{it.name.equals(cmd.item.name,true)&&it.price==cmd.item.price};cart=if(i>=0)cart.toMutableList().also{it[i]=it[i].copy(qty=it[i].qty+cmd.item.qty)}else cart+cmd.item;message="Added "+cmd.item.qty+" x "+cmd.item.name}
                VoiceCommand.Clear->{cart=emptyList();message="Cart cleared"}
                VoiceCommand.Sales->{tab=1}
                VoiceCommand.Expenses->{tab=2}
                VoiceCommand.Reports->{tab=3}
                VoiceCommand.Settings->{settings=true}
                is VoiceCommand.Remove->{cart=cart.filterNot{it.name.contains(cmd.name,true)};message="Item removed"}
                VoiceCommand.Complete,VoiceCommand.CompletePrint->{
                    if(cart.isEmpty()){message="Cart is empty"}else{
                        val s=Sale(System.currentTimeMillis(),cart,cart.sumOf{it.total},System.currentTimeMillis());store.addSale(s);cash+=s.total;store.setCash(cash)
                        val printed=cmd is VoiceCommand.CompletePrint&&printer.print(receipt(s,profile,store.theme()))
                        message=if(cmd is VoiceCommand.CompletePrint)if(printed)"Sale completed and printed" else "Sale saved - connect printer" else "Sale completed"
                        cart=emptyList();tick++
                    }
                }
            }
        }
        clearHeard()
    }
    val fs=sales.filter{inRange(it.time,filter)}
    val fe=expenses.filter{inRange(it.time,filter)}

    Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={CenterAlignedTopAppBar(title={Column(horizontalAlignment=Alignment.CenterHorizontally){Text(profile.name,fontWeight=FontWeight.Bold);Text("Smart POS",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},actions={IconButton({settings=true}){Icon(Icons.Default.Settings,null)}})},bottomBar={NavigationBar{val names=listOf("Quick Sale","Sales","Expenses","Analytics");val icons=listOf(Icons.Default.PointOfSale,Icons.Default.ReceiptLong,Icons.Default.Payments,Icons.Default.Insights);names.forEachIndexed{i,n->NavigationBarItem(tab==i,{tab=i},icon={Icon(icons[i],null)},label={Text(n)})}}},floatingActionButton={FloatingActionButton(listen,shape=CircleShape){Icon(Icons.Default.Mic,null)}}){
        pad->Box(Modifier.fillMaxSize().padding(pad)){when(tab){
            0->QuickSale(cart,profile,printer,listen){cart=emptyList()}
            1->SalesPage(fs,filter,{filter=it}){saleDetail=it}
            2->ExpensesPage(fe,filter,{filter=it}){expense=true}
            3->Analytics(fs,fe,cash,filter,{filter=it})
        }}
    }
    if(opening)OpeningCash{cash=it;opening=false}
    if(expense)ExpenseDialog{n,c,a->store.addExpense(Expense(System.currentTimeMillis(),n,c,a,System.currentTimeMillis()));cash-=a;store.setCash(cash);tick++;expense=false}
    if(settings)SettingsPage(store,printer){settings=false;tick++}
    saleDetail?.let{SaleDialog(it,profile,printer,store.theme()){saleDetail=null}}
    if(message.isNotBlank()){LaunchedEffect(message){kotlinx.coroutines.delay(1600);message=""};Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter){Surface(shape=RoundedCornerShape(18.dp),tonalElevation=8.dp,modifier=Modifier.padding(bottom=84.dp)){Text(message,Modifier.padding(16.dp))}}}
}

@Composable
fun QuickSale(cart:List<SaleItem>,p:CompanyProfile,printer:PrinterManager,listen:()->Unit,clear:()->Unit){
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Surface(shape=RoundedCornerShape(28.dp),tonalElevation=6.dp,modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center){Icon(Icons.Default.RecordVoiceOver,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(30.dp))}
            Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text("Voice checkout",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("Speak items, prices and actions.",color=MaterialTheme.colorScheme.onSurfaceVariant)};IconButton(listen){Icon(Icons.Default.ArrowForward,null)}
        }}
        Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(18.dp)){
            Row(Modifier.fillMaxWidth(),Arrangement.SpaceBetween){Text("Current order",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);if(cart.isNotEmpty())TextButton(clear){Text("Clear")}}
            if(cart.isEmpty())EmptyState(Icons.Default.Mic,"Ready for voice","Try: 4 special shawarma price 200 each, 2 fries price 150 each")
            else{cart.forEach{Row(Modifier.fillMaxWidth().padding(vertical=9.dp),Arrangement.SpaceBetween){Column(Modifier.weight(1f)){Text(it.name,fontWeight=FontWeight.SemiBold);Text(it.qty.toString()+" x "+money(it.price),color=MaterialTheme.colorScheme.onSurfaceVariant)};Text(money(it.total),fontWeight=FontWeight.Bold)}};HorizontalDivider();Row(Modifier.fillMaxWidth().padding(top=12.dp),Arrangement.SpaceBetween){Text("Total",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge);Text(money(cart.sumOf{it.total}),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge)}}
        }}
        Text("Voice command shortcuts",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        CommandCard("Add items","4 special shawarma price 200 each")
        CommandCard("Finish + print","complete sale and print")
        CommandCard("Navigate","show sales • show reports • show expenses")
    }
}
@Composable fun CommandCard(a:String,b:String){Surface(shape=RoundedCornerShape(16.dp),tonalElevation=2.dp,modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(a,fontWeight=FontWeight.SemiBold);Text(b,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
@Composable fun EmptyState(i:ImageVector,t:String,b:String){Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(i,null,modifier=Modifier.size(40.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.height(8.dp));Text(t,fontWeight=FontWeight.Bold);Text(b,color=MaterialTheme.colorScheme.onSurfaceVariant)}}

@Composable
fun SalesPage(sales:List<Sale>,filter:Int,setFilter:(Int)->Unit,open:(Sale)->Unit){
    Column(Modifier.fillMaxSize().padding(16.dp)){Text("Sales",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);FilterRow(filter,setFilter);Spacer(Modifier.height(10.dp));LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){items(sales.reversed()){s->Card(Modifier.fillMaxWidth().clickable{open(s)},shape=RoundedCornerShape(20.dp)){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.ReceiptLong,null,modifier=Modifier.size(42.dp));Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text("#"+s.id,fontWeight=FontWeight.Bold);Text(s.items.sumOf{it.qty}.toString()+" items • "+date(s.time),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text(money(s.total),fontWeight=FontWeight.Bold)}}}}}
}
@Composable fun ExpensesPage(es:List<Expense>,filter:Int,setFilter:(Int)->Unit,add:()->Unit){
    Column(Modifier.fillMaxSize().padding(16.dp)){Row(Modifier.fillMaxWidth(),Arrangement.SpaceBetween,Alignment.CenterVertically){Text("Expenses",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Button(add){Icon(Icons.Default.Add,null);Spacer(Modifier.width(5.dp));Text("Add")}};FilterRow(filter,setFilter);LazyColumn{items(es.reversed()){ListItem(headlineContent={Text(it.title,fontWeight=FontWeight.SemiBold)},supportingContent={Text(it.category+" • "+date(it.time))},trailingContent={Text(money(it.amount),fontWeight=FontWeight.Bold)})}}}
}
@Composable fun FilterRow(s:Int,set:(Int)->Unit){Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf("Today","7 days","30 days","All").forEachIndexed{i,n->FilterChip(s==i,{set(i)},label={Text(n)})}}
@Composable
fun Analytics(sales:List<Sale>,expenses:List<Expense>,cash:Double,filter:Int,setFilter:(Int)->Unit){
    val sv=sales.sumOf{it.total};val ev=expenses.sumOf{it.amount}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Text("Analytics",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text("Business snapshot",color=MaterialTheme.colorScheme.onSurfaceVariant);FilterRow(filter,setFilter)}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){Metric("Sales",sv,Icons.Default.TrendingUp,Modifier.weight(1f));Metric("Expenses",ev,Icons.Default.TrendingDown,Modifier.weight(1f))}}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){Metric("Cash",cash,Icons.Default.AccountBalanceWallet,Modifier.weight(1f));Metric("Net",sv-ev,Icons.Default.AccountBalance,Modifier.weight(1f))}}
        item{Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(18.dp)){Text("7-day sales",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);SalesChart(sales)}}}
        item{Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(18.dp)){Text("Activity",fontWeight=FontWeight.Bold);Text(sales.size.toString()+" sales • "+sales.sumOf{it.items.sumOf{q->q.qty}}+" units")}}}
    }
}
@Composable fun Metric(t:String,v:Double,i:ImageVector,m:Modifier){Card(m,shape=RoundedCornerShape(22.dp)){Column(Modifier.padding(16.dp)){Icon(i,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.height(10.dp));Text(t,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(money(v),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}}}
@Composable
fun SalesChart(sales:List<Sale>){
    val vals=(0..6).map{d->val day=Calendar.getInstance().apply{add(Calendar.DAY_OF_YEAR,-d)};sales.filter{val x=Calendar.getInstance().apply{timeInMillis=it.time};x.get(Calendar.YEAR)==day.get(Calendar.YEAR)&&x.get(Calendar.DAY_OF_YEAR)==day.get(Calendar.DAY_OF_YEAR)}.sumOf{it.total}}.reversed()
    val mx=maxOf(1.0,vals.maxOrNull()?:1.0)
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
        vals.forEachIndexed{i,v->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text((i+1).toString(),modifier=Modifier.width(24.dp),style=MaterialTheme.typography.labelSmall);LinearProgressIndicator(progress={ (v/mx).toFloat() },modifier=Modifier.weight(1f).height(10.dp));Spacer(Modifier.width(8.dp));Text(money(v),style=MaterialTheme.typography.labelSmall)}}}
}

