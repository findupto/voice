package com.findupto.voicepos
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun LiveOperationsDialog(store:Store,onClose:()->Unit,onChanged:()->Unit){
 val ctx=LocalContext.current
 var ip by remember{mutableStateOf(ctx.getSharedPreferences("voice_local_network",0).getString("owner_ip","")?:"")}
 var code by remember{mutableStateOf(LocalNetworkSync.token(ctx))}
 var orders by remember{mutableStateOf(store.pending())}
 var message by remember{mutableStateOf("Enter the owner phone IP and access code.")}
 val role=store.staffRole();val actor=store.staffName().ifBlank{"Staff"};val scope=rememberCoroutineScope()
 fun update(o:PendingSale,status:String){scope.launch{runCatching{withContext(Dispatchers.IO){LocalNetworkSync.pushStatus(ip,code,o.id,status,actor,role);LocalNetworkSync.pullOrders(ip,code,store)}}.onSuccess{orders=it;message="Order updated."}.onFailure{message=it.message?:"Update failed"};onChanged()}}
 LaunchedEffect(ip,code){while(true){if(ip.isNotBlank()&&code.isNotBlank()){runCatching{withContext(Dispatchers.IO){LocalNetworkSync.pullOrders(ip,code,store)}}.onSuccess{orders=it;message="Connected • live refresh every 4 seconds"}.onFailure{message=it.message?: "Connection failed"}};delay(4000)}}
 AlertDialog(onDismissRequest=onClose,title={Text("LIVE ORDERS • ${role}",fontWeight=FontWeight.Bold)},
 text={Column(Modifier.fillMaxWidth().heightIn(max=540.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  OutlinedTextField(ip,{ip=it;ctx.getSharedPreferences("voice_local_network",0).edit().putString("owner_ip",it).apply()},label={Text("Owner phone IP")},singleLine=true)
  OutlinedTextField(code,{code=it;ctx.getSharedPreferences("voice_local_network",0).edit().putString("owner_token",it).apply()},label={Text("Access code")},singleLine=true)
  Text(message,style=MaterialTheme.typography.bodySmall)
  HorizontalDivider()
  LazyColumn(verticalArrangement=Arrangement.spacedBy(7.dp)){
   items(orders,key={it.id}){o->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
    Text("${o.tableName.ifBlank{o.orderType}} • #${o.id}",fontWeight=FontWeight.Bold)
    Text("${o.status} • ${money(o.total)}")
    Text(o.items.joinToString{ "${it.qty} × ${it.name}" },style=MaterialTheme.typography.bodySmall)
    if(o.specialInstructions.isNotBlank())Text("Note: ${o.specialInstructions}")
    Text("Waiter ${o.waiterName.ifBlank{o.shiftPerson}} • Chef ${o.chefName} • Counter ${o.counterName}",style=MaterialTheme.typography.labelSmall)
    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
     if(role=="CHEF"&&(o.status=="NEW"||o.status=="ACCEPTED"))TextButton(onClick={update(o,"PREPARING")}){Text("PREPARING")}
     if(role=="CHEF"&&o.status=="PREPARING")Button(onClick={update(o,"READY")}){Text("READY")}
     if(role=="WAITER"&&o.status=="READY")Button(onClick={update(o,"SERVED")}){Text("SERVED")}
     if((role=="COUNTER"||role=="OWNER")&&o.status=="NEW")TextButton(onClick={update(o,"ACCEPTED")}){Text("ACCEPT")}
    }
   }}}
  }
  if(role=="OWNER")Text("Audit entries: ${store.orderAudit().length()}",fontWeight=FontWeight.Bold)
 }},
 confirmButton={TextButton(onClick={onClose}){Text("CLOSE")}}
 )
}
