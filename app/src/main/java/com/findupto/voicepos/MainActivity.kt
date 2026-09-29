package com.findupto.voicepos

import android.bluetooth.*
import android.content.*
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

data class SaleItem(val name:String,val qty:Int,val price:Double){val total get()=qty*price}
data class Sale(val id:Long,val items:List<SaleItem>,val total:Double,val time:Long)
data class Expense(val id:Long,val title:String,val amount:Double,val time:Long)

class Store(ctx:Context){
 private val p=ctx.getSharedPreferences("pos",0)
 fun cash()=p.getString("cash","0")!!.toDoubleOrNull()?:0.0
 fun setCash(v:Double)=p.edit().putString("cash",v.toString()).apply()
 fun company()=p.getString("company","My Restaurant")?:"My Restaurant"
 fun setCompany(v:String)=p.edit().putString("company",v).apply()
 fun sales():List<Sale>{val r=p.getString("sales","")?:"";if(r.isBlank())return emptyList();return r.split("\n").mapNotNull{runCatching{val a=it.split("~");val items=a[1].split("|").filter(String::isNotBlank).map{z->val b=z.split("^");SaleItem(b[0],b[1].toInt(),b[2].toDouble())};Sale(a[0].toLong(),items,a[2].toDouble(),a[3].toLong())}.getOrNull()}}
 fun addSale(s:Sale){val a=sales()+s;p.edit().putString("sales",a.joinToString("\n"){x->"# {x.id}~"+x.items.joinToString("|"){i->"# {i.name}^# {i.qty}^# {i.price}"}+"~# {x.total}~# {x.time}"}).apply()}
 fun expenses():List<Expense>{val r=p.getString("expenses","")?:"";if(r.isBlank())return emptyList();return r.split("\n").filter(String::isNotBlank).mapNotNull{runCatching{val a=it.split("~");Expense(a[0].toLong(),a[1],a[2].toDouble(),a[3].toLong())}.getOrNull()}}
 fun addExpense(e:Expense){val a=expenses()+e;p.edit().putString("expenses",a.joinToString("\n"){"# {it.id}~# {it.title}~# {it.amount}~# {it.time}"}).apply()}
}

class PrinterManager{
 private var socket:BluetoothSocket?=null
 fun devices():List<BluetoothDevice>{val a=BluetoothAdapter.getDefaultAdapter()?:return emptyList();return runCatching{a.bondedDevices.toList()}.getOrDefault(emptyList())}
 fun connect(d:BluetoothDevice)=runCatching{socket?.close();socket=d.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));socket!!.connect();true}.getOrDefault(false)
 fun print(bytes:ByteArray)=runCatching{socket?.outputStream?.write(bytes);socket?.outputStream?.flush();true}.getOrDefault(false)
 fun connected()=socket?.isConnected==true
 fun close()=runCatching{socket?.close()}
}

class MainActivity:ComponentActivity(){
 private lateinit var store:Store;private lateinit var printer:PrinterManager
 private val voice=mutableStateOf("")
 private val launcher=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){r->voice.value=r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?:""}
 override fun onCreate(b:Bundle?){super.onCreate(b);store=Store(this);printer=PrinterManager();setContent{App(store,printer,{listen()},voice.value){voice.value=""}}}
 private fun listen(){if(!SpeechRecognizer.isRecognitionAvailable(this))return;launcher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-PK");putExtra(RecognizerIntent.EXTRA_PROMPT,"Speak your sale")})}
 override fun onDestroy(){printer.close();super.onDestroy()}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun App(store:Store,printer:PrinterManager,listen:()->Unit,heard:String,clear:()->Unit){
 var tab by remember{mutableIntStateOf(0)};var cart by remember{mutableStateOf(emptyList<SaleItem>())};var cash by remember{mutableStateOf(store.cash())};var tick by remember{mutableIntStateOf(0)};var expense by remember{mutableStateOf(false)};var settings by remember{mutableStateOf(false)}
 var expName by remember{mutableStateOf("")};var expAmt by remember{mutableStateOf("")}
 val sales=remember(tick){store.sales()};val exps=remember(tick){store.expenses()}
 LaunchedEffect(heard){if(heard.isNotBlank()){cart=parseVoice(heard,cart);if(heard.lowercase().contains("complete sale")){val s=Sale(System.currentTimeMillis(),cart,cart.sumOf{it.total},System.currentTimeMillis());store.addSale(s);cash+=s.total;store.setCash(cash);cart=emptyList();tick++};clear()}}
 Scaffold(topBar={TopAppBar(title={Text("Voice POS")},actions={IconButton({settings=true}){Icon(Icons.Default.Settings,"Settings")}})},bottomBar={NavigationBar{val ns=listOf("Sale","Sales","Expenses","Reports");val isx=listOf(Icons.Default.PointOfSale,Icons.Default.ReceiptLong,Icons.Default.Payments,Icons.Default.BarChart);ns.forEachIndexed{i,n->NavigationBarItem(tab==i,{tab=i},{Icon(isx[i],n)},label={Text(n)})}}}){p->
  Column(Modifier.fillMaxSize().padding(p).padding(16.dp)){when(tab){0->SaleScreen(cart,{cart=it},listen,store.company());1->SalesScreen(sales);2->ExpensesScreen(exps){expense=true};3->ReportsScreen(sales.sumOf{it.total},exps.sumOf{it.amount},cash)}}
 }
 if(expense)AlertDialog(onDismissRequest={expense=false},title={Text("Add Expense")},text={Column{OutlinedTextField(expName,{expName=it},label={Text("Description")});OutlinedTextField(expAmt,{expAmt=it},label={Text("Amount")})}},confirmButton={TextButton({val a=expAmt.toDoubleOrNull();if(a!=null){store.addExpense(Expense(System.currentTimeMillis(),expName,a,System.currentTimeMillis()));cash-=a;store.setCash(cash);tick++};expense=false;expName="";expAmt=""}){Text("Save")}},dismissButton={TextButton({expense=false}){Text("Cancel")}})
 if(settings)SettingsDialog(store,printer){settings=false}
}

@Composable fun SaleScreen(cart:List<SaleItem>,setCart:(List<SaleItem>)->Unit,listen:()->Unit,company:String){
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){
  Card(Modifier.fillMaxWidth().padding(vertical=10.dp)){Column(Modifier.padding(18.dp)){Text("Quick Sale",style=MaterialTheme.typography.titleLarge);Text(company,color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(12.dp));Button(listen,Modifier.fillMaxWidth()){Icon(Icons.Default.Mic,null);Spacer(Modifier.width(8.dp));Text("Speak Sale")};Spacer(Modifier.height(10.dp))
   cart.forEach{x->ListItem({Text(x.name)},supportingContent={Text("# {x.qty} × # {money(x.price)}")},trailingContent={Text(money(x.total))})};HorizontalDivider();Row(Modifier.fillMaxWidth().padding(top=12.dp),Arrangement.SpaceBetween){Text("Total",style=MaterialTheme.typography.titleLarge);Text(money(cart.sumOf{it.total}),style=MaterialTheme.typography.titleLarge)}
   Spacer(Modifier.height(10.dp));Text("Say: “4 special shawarma price 200 each, 2 large chicken fajita price 1350 each…”",style=MaterialTheme.typography.bodySmall)
  }
 }
}

@Composable fun SalesScreen(sales:List<Sale>){LazyColumn{items(sales.reversed()){s->Card(Modifier.fillMaxWidth().padding(vertical=5.dp)){Column(Modifier.padding(14.dp)){Text("# {s.id}",style=MaterialTheme.typography.labelSmall);Text(money(s.total),style=MaterialTheme.typography.titleLarge);Text(s.items.joinToString(", "){"# {it.qty}× # {it.name}"});Text(date(s.time),style=MaterialTheme.typography.bodySmall)}}}}}
@Composable fun ExpensesScreen(es:List<Expense>,add:()->Unit){Row(Modifier.fillMaxWidth().padding(vertical=10.dp),Arrangement.SpaceBetween,Alignment.CenterVertically){Text("Expenses",style=MaterialTheme.typography.headlineSmall);Button(add){Text("Add")}};LazyColumn{items(es.reversed()){e->ListItem({Text(e.title)},supportingContent={Text(date(e.time))},trailingContent={Text(money(e.amount))})}}}
@Composable fun ReportsScreen(s:Double,e:Double,c:Double){Column(Modifier.verticalScroll(rememberScrollState())){Text("Reports",style=MaterialTheme.typography.headlineSmall);Metric("Total Sales",s);Metric("Total Expenses",e);Metric("Cash In Hand",c);Metric("Net",s-e)}}
@Composable fun Metric(t:String,v:Double){Card(Modifier.fillMaxWidth().padding(vertical=5.dp)){Row(Modifier.fillMaxWidth().padding(18.dp),Arrangement.SpaceBetween){Text(t);Text(money(v),style=MaterialTheme.typography.titleLarge)}}}

@Composable fun SettingsDialog(store:Store,printer:PrinterManager,close:()->Unit){var company by remember{mutableStateOf(store.company())};var ds by remember{mutableStateOf(printer.devices())};var status by remember{mutableStateOf(if(printer.connected())"Connected" else "Not connected")};AlertDialog(onDismissRequest=close,title={Text("Settings")},text={Column(Modifier.verticalScroll(rememberScrollState())){OutlinedTextField(company,{company=it},label={Text("Company")});Spacer(Modifier.height(8.dp));Button({ds=printer.devices()},Modifier.fillMaxWidth()){Text("Discover Bluetooth Printers")};Text(status);ds.forEach{d->TextButton({status=if(printer.connect(d))"Connected: # {d.name}" else "Connection failed"}){Text(d.name?:"Unknown")}};Text("80mm ESC/POS receipt printing")}},confirmButton={TextButton({store.setCompany(company);close()}){Text("Save")}})}

fun parseVoice(raw:String,old:List<SaleItem>):List<SaleItem>{val out=old.toMutableList();raw.split(","," and "," then ",";").map(String::trim).filter(String::isNotBlank).forEach{p->val m=Regex("""(?i)\b(\d+)\s+(.+?)\s+(?:price\s+)?(\d+(?:\.\d+)?)\s*(?:each)?\b""").find(p)?:return@forEach;val q=m.groupValues[1].toInt();val n=m.groupValues[2].replace(Regex("(?i)\b(price|at|for)\b"),"").trim();val v=m.groupValues[3].toDouble();out.add(SaleItem(n,q,v))};return out}
fun money(v:Double)="Rs # {String.format(Locale.US,"%.0f",v)}"
fun date(v:Long)=SimpleDateFormat("dd MMM yyyy, hh:mm a",Locale.US).format(Date(v))
