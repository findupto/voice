package com.findupto.voicepos
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LiveOperationsDialog(store:Store,onClose:()->Unit,onChanged:()->Unit){
 val ctx=androidx.compose.ui.platform.LocalContext.current
 val prefs=ctx.getSharedPreferences("voice_local_network",0)
 var ip by remember{mutableStateOf(prefs.getString("owner_ip","")?:"")}
 var code by remember{mutableStateOf(prefs.getString("staff_session","")?:"")}
 var orders by remember{mutableStateOf(store.pending())}
 var message by remember{mutableStateOf("Enter the owner phone IP and access code.")}
 var clock by remember{mutableLongStateOf(System.currentTimeMillis())}
 val role=store.staffRole();val actor=store.staffName().ifBlank{"Staff"};val scope=rememberCoroutineScope()
 fun update(o:PendingSale,status:String){
  scope.launch{
   runCatching{withContext(Dispatchers.IO){LocalNetworkSync.pushStatus(ip,code,o.id,status,actor,role);LocalNetworkSync.pullOrders(ip,code,store)}}
    .onSuccess{orders=it;message="Order updated: $status"}
    .onFailure{
     if(role=="RIDER"&&status in setOf("OUT_FOR_DELIVERY","DELIVERED")){
      store.updateOrderWorkflow(o.id,status,actor,role)
      store.queueOfflineDeliveryUpdate(o.id,status,actor)
      orders=store.pending()
      message="Saved offline • "+store.offlineDeliveryUpdates().length()+" delivery update(s) waiting to sync"
     }else message=it.message?:"Update failed"
    }
   onChanged()
  }
 }
 LaunchedEffect(ip,code){
  var nextDelay=3000L
  while(true){
   if(ip.isNotBlank()&&code.isNotBlank()){
    val started=System.currentTimeMillis()
    runCatching{withContext(Dispatchers.IO){
     val sentOrders=LocalNetworkSync.retryOfflineOrders(ip,code,ctx)
     val queued=store.offlineDeliveryUpdates()
     for(i in 0 until queued.length()){
      val q=queued.getJSONObject(i)
      LocalNetworkSync.pushStatus(ip,code,q.getLong("id"),q.getString("status"),q.optString("actor"),"RIDER")
      store.removeOfflineDeliveryUpdate(0)
     }
     LocalNetworkSync.pullOrders(ip,code,store) to sentOrders
    }}
     .onSuccess{result->
      orders=result.first
      val elapsed=System.currentTimeMillis()-started
      val waiting=ctx.getSharedPreferences("voice_local_network",0).getString("offline_order_queue","[]").orEmpty()
      val waitingCount=runCatching{org.json.JSONArray(waiting).length()}.getOrDefault(0)
      message="Connected • ${result.first.size} orders • ${elapsed} ms • ${result.second} queued orders sent • ${waitingCount} waiting • "+store.offlineDeliveryUpdates().length()+" delivery updates pending"
      nextDelay=3000L
     }
     .onFailure{
      message=it.message?:"Connection failed"
      nextDelay=(nextDelay*2).coerceAtMost(20000L)
     }
   } else {
    message="Enter the owner phone IP and access code."
    nextDelay=3000L
   }
   clock=System.currentTimeMillis()
   delay(nextDelay)
  }
 }
 val active=orders.filter{it.status !in setOf("SERVED","DELIVERED")}
 val ready=active.count{it.status=="READY"}
 val audit=store.orderAudit()
 AlertDialog(onDismissRequest=onClose,title={Text("LIVE OPERATIONS • $role",fontWeight=FontWeight.Bold)},
 text={Column(Modifier.fillMaxWidth().heightIn(max=590.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  OutlinedTextField(ip,{ip=it;prefs.edit().putString("owner_ip",it).apply()},label={Text("Owner phone IP")},singleLine=true,modifier=Modifier.fillMaxWidth())
  OutlinedTextField(code,{code=it;prefs.edit().putString("owner_token",it).apply()},label={Text("Staff session")},singleLine=true,modifier=Modifier.fillMaxWidth())
  Text(message,style=MaterialTheme.typography.bodySmall)
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
   listOf("ACTIVE" to active.size,"READY" to ready,"PREPARING" to active.count{it.status=="PREPARING"}).forEach{(label,count)->
    Card(Modifier.weight(1f)){Column(Modifier.padding(9.dp)){Text(count.toString(),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(label,style=MaterialTheme.typography.labelSmall)}}
   }
  }
  HorizontalDivider()
  LazyColumn(Modifier.weight(1f,false),verticalArrangement=Arrangement.spacedBy(7.dp)){
   items(orders.filter{it.status !in setOf("SERVED","DELIVERED")},key={it.id}){o->
    val age=((clock-o.time).coerceAtLeast(0L)/60000L).toInt()
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=if(age>=15)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)){
     Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
      Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
       Text("${o.tableName.ifBlank{o.orderType}} • #${o.id}",fontWeight=FontWeight.Bold)
       Text("${age} min",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold)
      }
      Text("${o.status} • ${money(o.total)}",fontWeight=FontWeight.SemiBold)
      Text(o.items.joinToString{ "${it.qty} × ${it.name}" },style=MaterialTheme.typography.bodySmall)
      if(o.specialInstructions.isNotBlank())Text("Note: ${o.specialInstructions}")
      Text("Waiter ${o.waiterName.ifBlank{o.shiftPerson}} • Chef ${o.chefName} • Counter ${o.counterName}",style=MaterialTheme.typography.labelSmall)
      Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
       if((role=="COUNTER"||role=="OWNER"||role=="ADMIN")&&o.status=="NEW")TextButton(onClick={update(o,"ACCEPTED")}){Text("ACCEPT")}
       if((role=="CHEF"||role=="OWNER"||role=="ADMIN")&&(o.status=="NEW"||o.status=="ACCEPTED"))TextButton(onClick={update(o,"PREPARING")}){Text("START")}
       if((role=="CHEF"||role=="OWNER"||role=="ADMIN")&&o.status=="PREPARING")Button(onClick={update(o,"READY")}){Text("READY")}
       if((role=="WAITER"||role=="OWNER"||role=="ADMIN")&&o.status=="READY"&&o.orderType!="DELIVERY")Button(onClick={update(o,"SERVED")}){Text("SERVED")}
       if(role=="RIDER"&&o.orderType=="DELIVERY"&&o.status=="READY")Button(onClick={update(o,"OUT_FOR_DELIVERY")}){Text("PICK UP")}
       if(role=="RIDER"&&o.status=="OUT_FOR_DELIVERY")Button(onClick={update(o,"DELIVERED")}){Text("DELIVERED")}
      }
     }
    }
   }
  }
  if(orders.none{it.status !in setOf("SERVED","DELIVERED")})Text("No active orders.")
  if(role=="OWNER"){
   HorizontalDivider()
   Text("RECENT ACTIVITY • ${audit.length()} total records",fontWeight=FontWeight.Bold)
   val start=(audit.length()-6).coerceAtLeast(0)
   for(i in audit.length()-1 downTo start){
    val a=audit.optJSONObject(i)?:continue
    val stamp=SimpleDateFormat("hh:mm a",Locale.getDefault()).format(Date(a.optLong("time")))
    Text("$stamp • ${a.optString("actor")} (${a.optString("role")}) • ${a.optString("action")}",style=MaterialTheme.typography.bodySmall)
   }
  }
 }},
 confirmButton={TextButton(onClick=onClose){Text("CLOSE")}}
 )
}
