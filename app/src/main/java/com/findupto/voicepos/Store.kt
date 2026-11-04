package com.findupto.voicepos

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

private const val DEFAULT_NAME="The Slice Of Heaven"
private const val DEFAULT_ADDRESS="Old Utility Store near Police Line Khansar Road Bhakkar Punjab Pakistan"
private const val DEFAULT_PHONE="0332 1872929, 0310 3685151"
private val orderSequence=AtomicInteger(0)

class Store(private val context:Context){
 private val p=context.getSharedPreferences("voice_pos_v5",Context.MODE_PRIVATE)
 private val orderNode:Int by lazy { p.getInt("order_node",-1).takeIf{it>=0} ?: SecureRandom().nextInt(65536).also{p.edit().putInt("order_node",it).apply()} }
 fun newOrderId():Long { val elapsed=(System.currentTimeMillis()-1704067200000L).coerceAtLeast(1L); return (elapsed shl 22) or (orderNode.toLong() shl 6) or (orderSequence.getAndIncrement().toLong() and 63L) }
 fun hasSale(id:Long)=sales().any{it.id==id}

 init{CurrencySettings.symbol=p.getString("currency_symbol","Rs")?:"Rs";if(!p.getBoolean("defaults_initialized",false))p.edit().putString("name",p.getString("name","")?.ifBlank{DEFAULT_NAME}?:DEFAULT_NAME).putString("address",p.getString("address","")?.ifBlank{DEFAULT_ADDRESS}?:DEFAULT_ADDRESS).putString("phone",p.getString("phone","")?.ifBlank{DEFAULT_PHONE}?:DEFAULT_PHONE).putBoolean("defaults_initialized",true).apply()}
 fun cash()=p.getString("cash","0")!!.toDoubleOrNull()?:0.0;fun setCash(v:Double)=p.edit().putString("cash",v.toString()).apply();fun hasOpeningCash()=p.getBoolean("opening",false);fun setOpeningCash(v:Double){setCash(v);p.edit().putBoolean("opening",true).apply()}
 fun profile()=CompanyProfile(p.getString("name",DEFAULT_NAME)!!.ifBlank{DEFAULT_NAME},p.getString("address",DEFAULT_ADDRESS)!!.ifBlank{DEFAULT_ADDRESS},p.getString("phone",DEFAULT_PHONE)!!.ifBlank{DEFAULT_PHONE},p.getString("footer","Thank you for your visit")!!,p.getString("logo_path","")!!,p.getBoolean("sale_logo",true),p.getBoolean("kitchen_logo",true),p.getString("currency_symbol","Rs")!!,p.getString("currency_code","PKR")!!,p.getBoolean("tax_enabled",false),p.getString("tax_rate","0")!!.toDoubleOrNull()?:0.0,p.getString("tax_label","Tax")!!,p.getBoolean("service_enabled",false),p.getString("service_rate","0")!!.toDoubleOrNull()?:0.0,p.getString("service_label","Service Charge")!!).also{CurrencySettings.symbol=it.currencySymbol}
 fun saveProfile(x:CompanyProfile)=p.edit().putString("name",x.name).putString("address",x.address).putString("phone",x.phone).putString("footer",x.footer).putString("logo_path",x.logoPath).putBoolean("sale_logo",x.saleLogoEnabled).putBoolean("kitchen_logo",x.kitchenLogoEnabled).putString("currency_symbol",x.currencySymbol).putString("currency_code",x.currencyCode).putBoolean("tax_enabled",x.taxEnabled).putString("tax_rate",x.taxRate.toString()).putString("tax_label",x.taxLabel).putBoolean("service_enabled",x.serviceChargeEnabled).putString("service_rate",x.serviceChargeRate.toString()).putString("service_label",x.serviceChargeLabel).apply().also{CurrencySettings.symbol=x.currencySymbol}
 fun theme()=runCatching{ReceiptTheme.valueOf(p.getString("theme","MODERN")!!)}.getOrDefault(ReceiptTheme.MODERN);fun setTheme(t:ReceiptTheme)=p.edit().putString("theme",t.name).apply();fun customerPrinterAddress()=p.getString("customer_printer","")?:"";fun kitchenPrinterAddress()=p.getString("kitchen_printer","")?:"";fun setCustomerPrinterAddress(v:String)=p.edit().putString("customer_printer",v).apply();fun setKitchenPrinterAddress(v:String)=p.edit().putString("kitchen_printer",v).apply()
 fun serviceCharge(x:Double):Double{val q=profile();return if(q.serviceChargeEnabled)x*q.serviceChargeRate/100.0 else 0.0}
 fun tax(x:Double):Double{val q=profile();val b=x+serviceCharge(x);return if(q.taxEnabled)b*q.taxRate/100.0 else 0.0}
 fun totalWithTax(x:Double)=x+serviceCharge(x)+tax(x)
 private fun enc(s:String)=s.replace("~","%7E");private fun dec(s:String)=s.replace("%7E","~");private fun ei(xs:List<SaleItem>)=xs.joinToString("|"){"${it.name.replace("~"," ").replace("|"," ").replace("^"," ")}^${it.qty}^${it.price}^${it.kitchen}"};private fun di(s:String)=s.split("|").filter{it.isNotBlank()}.mapNotNull{runCatching{val a=it.split("^");SaleItem(a[0],a[1].toInt(),a[2].toDouble(),a.getOrNull(3)?: "FAST_FOOD")}.getOrNull()}
 fun sales()=p.getString("sales","")!!.lines().filter{it.isNotBlank()}.mapNotNull{runCatching{val a=it.split("~");Sale(a[0].toLong(),di(a[1]),a[2].toDouble(),a[3].toLong(),dec(a.getOrNull(4).orEmpty()),dec(a.getOrNull(5).orEmpty()),a.getOrNull(6)?:"DINE_IN",dec(a.getOrNull(7).orEmpty()),a.getOrNull(8)?:"CASH",a.getOrNull(9)?.toDoubleOrNull()?:a[2].toDouble(),a.getOrNull(10)?.toDoubleOrNull()?:0.0,a.getOrNull(11)?.toLongOrNull()?:0L,a.getOrNull(12)?.toDoubleOrNull()?:0.0,a.getOrNull(13)?.toDoubleOrNull()?:0.0)}.getOrNull()}
 private fun saveSales(xs:List<Sale>)=p.edit().putString("sales",xs.joinToString("\n"){"${it.id}~${ei(it.items)}~${it.total}~${it.time}~${enc(it.customerName)}~${enc(it.customerPhone)}~${it.orderType}~${enc(it.customerAddress)}~${it.paymentMethod}~${it.paidAmount}~${it.dueAmount}~${it.shiftId}~${it.serviceCharge}~${it.tax}"}).apply()
 fun addSale(s:Sale):Boolean{if(hasSale(s.id))return false;saveSales(sales()+s);if(s.paidAmount>0)addPayment(PaymentRecord(System.currentTimeMillis(),s.id,s.paidAmount,s.paymentMethod,System.currentTimeMillis(),s.shiftId));if(s.dueAmount>0)addDue(DueAccount(s.id,s.id,s.customerName,s.customerPhone,s.total,s.paidAmount,s.time));return true};fun updateSale(s:Sale)=saveSales(sales().map{if(it.id==s.id)s else it})
 fun pending()=p.getString("pending","")!!.lines().filter{it.isNotBlank()}.mapNotNull{runCatching{val a=it.split("~");PendingSale(a[0].toLong(),di(a[1]),a[2].toLong(),dec(a.getOrNull(3).orEmpty()),dec(a.getOrNull(4).orEmpty()),a.getOrNull(5)?:"DINE_IN",dec(a.getOrNull(6).orEmpty()),dec(a.getOrNull(7).orEmpty()),dec(a.getOrNull(8).orEmpty()),dec(a.getOrNull(9).orEmpty()),dec(a.getOrNull(10).orEmpty()),dec(a.getOrNull(11).orEmpty()),a.getOrNull(12)?:"NEW",dec(a.getOrNull(13).orEmpty()),a.getOrNull(14)?.toLongOrNull()?:a[2].toLong(),a.getOrNull(15)?.toLongOrNull()?:0L,a.getOrNull(16)?: "{}")}.getOrNull()};private fun savePending(xs:List<PendingSale>)=p.edit().putString("pending",xs.joinToString("\n"){"${it.id}~${ei(it.items)}~${it.time}~${enc(it.customerName)}~${enc(it.customerPhone)}~${it.orderType}~${enc(it.customerAddress)}~${enc(it.shiftPerson)}~${enc(it.tableName)}~${enc(it.waiterName)}~${enc(it.counterName)}~${enc(it.chefName)}~${it.status}~${enc(it.specialInstructions)}~${it.updatedAt}~${it.servedAt}~${it.kitchenProgress.replace("~"," ")}"}).apply();fun addPending(s:PendingSale){if(pending().none{it.id==s.id}&&!hasSale(s.id))savePending(pending()+s)};fun replacePending(s:PendingSale)=savePending(pending().map{if(it.id==s.id)s else it});fun removePending(id:Long)=savePending(pending().filterNot{it.id==id})

 // Shared service workflow metadata and audit history. Each transition is timestamped and attributed.
 @Synchronized fun upsertRemoteOrder(order:PendingSale){
  val current=pending().firstOrNull{it.id==order.id}
  if(current==null){addPending(order);return}
  // Never let a delayed poll overwrite a newer amendment or kitchen status.
  if(order.updatedAt>current.updatedAt)replacePending(order)
 }
 @Synchronized fun updateKitchenStation(id:Long,station:String,status:String,actor:String,role:String):Boolean {
  if(role!="CHEF"&&role!="OWNER"&&role!="ADMIN")return false
  if(station !in setOf("FAST_FOOD","GRILLED","MILK_SHAKES","TEA_COFFEE")||status !in setOf("PREPARING","READY"))return false
  val order=pending().firstOrNull{it.id==id}?:return false
  if(order.items.none{it.kitchen==station})return false
  val progress=runCatching{org.json.JSONObject(order.kitchenProgress)}.getOrDefault(org.json.JSONObject())
  val current=progress.optString(station,"NEW")
  val allowed=if(status=="PREPARING")current=="NEW" else current=="PREPARING"
  if(!allowed)return current==status
  progress.put(station,status)
  val required=order.items.map{it.kitchen}.distinct()
  val allReady=required.all{progress.optString(it,"NEW")=="READY"}
  val updated=order.copy(kitchenProgress=progress.toString(),status=if(allReady)"READY" else if(required.any{progress.optString(it)=="PREPARING"})"PREPARING" else order.status,updatedAt=System.currentTimeMillis())
  replacePending(updated)
  appendOrderAudit(id,actor,role,"KITCHEN_$station","$status • ${if(allReady)"All stations ready" else "Station progress updated"}",updated.updatedAt)
  return true
 }
 @Synchronized fun updateOrderWorkflow(id:Long, status:String, actor:String, role:String, note:String="", table:String?=null):Boolean {
  val order=pending().firstOrNull{it.id==id}?:return false
  if(order.status==status)return true
  val allowed=when(order.status){
   "NEW"->setOf("ACCEPTED","PREPARING")
   "ACCEPTED"->setOf("PREPARING")
   "PREPARING"->setOf("READY")
   "READY"->setOf("SERVED","OUT_FOR_DELIVERY","BILLED")
   "OUT_FOR_DELIVERY"->setOf("DELIVERED","BILLED")
   "SERVED"->setOf("BILLED")
   "DELIVERED"->setOf("BILLED")
   else->emptySet()
  }
  if(status !in allowed)return false
  if((status=="PREPARING"||status=="READY")&&role!="CHEF"&&role!="OWNER"&&role!="ADMIN")return false
  if(status=="SERVED"&&role!="WAITER"&&role!="OWNER"&&role!="ADMIN")return false
  if(status in setOf("OUT_FOR_DELIVERY","DELIVERED")&&role!="RIDER"&&role!="WAITER"&&role!="OWNER"&&role!="ADMIN")return false
  if(status=="ACCEPTED"&&role!="COUNTER"&&role!="OWNER"&&role!="ADMIN")return false
  if(status=="BILLED"&&role!="WAITER"&&role!="COUNTER"&&role!="OWNER"&&role!="ADMIN")return false
  val now=System.currentTimeMillis()
  val updated=order.copy(status=status,updatedAt=now,servedAt=if(status=="SERVED")now else order.servedAt,
   chefName=if(role=="CHEF"||role=="DISHWASHER")actor else order.chefName,
   counterName=if(role=="COUNTER")actor else order.counterName,
   waiterName=if(role=="WAITER")actor else order.waiterName,
   specialInstructions=if(note.isBlank())order.specialInstructions else note,
   tableName=table?:order.tableName)
  replacePending(updated)
  appendOrderAudit(id,actor,role,status,note,now)
  if(status=="BILLED"){markOrderCompleted(id);removePending(id)}
  return true
 }
 @Synchronized fun appendOrderAudit(orderId:Long, actor:String, role:String, action:String, detail:String="", at:Long=System.currentTimeMillis()) {
  val old=orderAudit();val a=org.json.JSONArray()
  // Retain a substantially longer local audit trail for later reconciliation.
  for(i in (old.length()-4999).coerceAtLeast(0) until old.length())a.put(old.getJSONObject(i))
  a.put(org.json.JSONObject().put("eventId",java.util.UUID.randomUUID().toString()).put("orderId",orderId).put("actor",actor).put("role",role).put("action",action).put("detail",detail).put("time",at))
  p.edit().putString("order_audit",a.toString()).apply()
 }
 @Synchronized fun mergeOrderAudit(remote:org.json.JSONArray){
  val all=linkedMapOf<String,org.json.JSONObject>()
  for(source in listOf(orderAudit(),remote))for(i in 0 until source.length()){
   val item=source.optJSONObject(i)?:continue
   val id=item.optString("eventId").ifBlank{"${item.optLong("time")}:${item.optLong("orderId")}:${item.optString("actor")}:${item.optString("action")}"}
   all[id]=item
  }
  val merged=org.json.JSONArray()
  all.values.sortedBy{it.optLong("time")}.takeLast(5000).forEach{merged.put(it)}
  p.edit().putString("order_audit",merged.toString()).apply()
 }
 @Synchronized fun markOrderCompleted(id:Long) {
  if(id<=0L)return
  val ids=completedOrderIds().toMutableSet()
  ids.add(id)
  val saved=org.json.JSONArray()
  ids.sorted().takeLast(20000).forEach{saved.put(it)}
  p.edit().putString("completed_order_ids",saved.toString()).commit()
 }
 @Synchronized fun mergeCompletedOrderIds(remote:org.json.JSONArray) {
  val ids=completedOrderIds().toMutableSet()
  for(i in 0 until remote.length())remote.optLong(i).takeIf{it>0L}?.let{ids.add(it)}
  val saved=org.json.JSONArray()
  ids.sorted().takeLast(20000).forEach{saved.put(it)}
  p.edit().putString("completed_order_ids",saved.toString()).commit()
 }
 fun completedOrderIds():Set<Long> = runCatching {
  val a=org.json.JSONArray(p.getString("completed_order_ids","[]")?:"[]")
  (0 until a.length()).mapNotNull{i->a.optLong(i).takeIf{it>0L}}.toSet()
 }.getOrDefault(emptySet())
 fun completedOrderIdsJson()=org.json.JSONArray().apply{completedOrderIds().sorted().forEach{put(it)}}
 @Synchronized fun reconcileCompletedOrdersFromAudit() {
  val ids=completedOrderIds().toMutableSet()
  val completed=orderAudit()
  for(i in 0 until completed.length()) {
   val event=completed.optJSONObject(i)?:continue
   event.optLong("orderId").takeIf{it>0L&&event.optString("action")=="BILLED"}?.let{ids.add(it)}
  }
  val saved=org.json.JSONArray()
  ids.sorted().takeLast(20000).forEach{saved.put(it)}
  p.edit().putString("completed_order_ids",saved.toString()).commit()
  if(ids.isNotEmpty())savePending(pending().filterNot{it.id in ids})
 }
 fun orderAudit():org.json.JSONArray=runCatching{org.json.JSONArray(p.getString("order_audit","[]")?:"[]")}.getOrDefault(org.json.JSONArray())
 fun setStaffSession(name:String,role:String){p.edit().putString("staff_name",name.trim()).putString("staff_role",role.uppercase()).apply()}
 fun staffName()=p.getString("staff_name","")?:""
 fun staffRole()=p.getString("staff_role","GUEST")?:"GUEST"
 fun queueOfflineDeliveryUpdate(id:Long,status:String,actor:String){val a=runCatching{org.json.JSONArray(p.getString("offline_delivery_queue","[]")?:"[]")}.getOrDefault(org.json.JSONArray());a.put(org.json.JSONObject().put("id",id).put("status",status).put("actor",actor).put("time",System.currentTimeMillis()));p.edit().putString("offline_delivery_queue",a.toString()).apply()}
 fun offlineDeliveryUpdates()=runCatching{org.json.JSONArray(p.getString("offline_delivery_queue","[]")?:"[]")}.getOrDefault(org.json.JSONArray())
 fun removeOfflineDeliveryUpdate(index:Int){val old=offlineDeliveryUpdates();val next=org.json.JSONArray();for(i in 0 until old.length())if(i!=index)next.put(old.getJSONObject(i));p.edit().putString("offline_delivery_queue",next.toString()).apply()}
 fun menu()=p.getString("menu","")!!.lines().filter{it.isNotBlank()}.mapNotNull{runCatching{val a=it.split("^");MenuItem(a[0].toLong(),a[1],a[2].toDouble(),a.getOrNull(3)?:"",a.getOrNull(4)?:"",a.getOrNull(5)?:"FAST_FOOD")}.getOrNull()};fun saveMenu(xs:List<MenuItem>)=p.edit().putString("menu",xs.joinToString("\n"){"${it.id}^${it.name.replace("^"," ")}^${it.price}^${it.variant.replace("^"," ")}^${it.size.replace("^"," ")}^${it.kitchen}"}).apply();fun addMenuItem(i:MenuItem)=saveMenu(menu()+i);fun updateMenuItem(i:MenuItem)=saveMenu(menu().map{if(it.id==i.id)i else it});fun removeMenuItem(id:Long)=saveMenu(menu().filterNot{it.id==id})
 fun expenses()=p.getString("expenses","")!!.lines().filter{it.isNotBlank()}.mapNotNull{runCatching{val a=it.split("~");Expense(a[0].toLong(),a[1],a[2],a[3].toDouble(),a[4].toLong(),a.getOrNull(5)?:"CASH",a.getOrNull(6)?.toLongOrNull()?:0L)}.getOrNull()};fun addExpense(e:Expense)=p.edit().putString("expenses",(expenses()+e).joinToString("\n"){"${it.id}~${it.title.replace("~"," ")}~${it.category.replace("~"," ")}~${it.amount}~${it.time}~${it.paymentMethod}~${it.shiftId}"}).apply();fun updateExpense(e:Expense)=p.edit().putString("expenses",expenses().map{if(it.id==e.id)e else it}.joinToString("\n"){"${it.id}~${it.title.replace("~"," ")}~${it.category.replace("~"," ")}~${it.amount}~${it.time}~${it.paymentMethod}~${it.shiftId}"}).apply();fun removeExpense(id:Long)=p.edit().putString("expenses",expenses().filterNot{it.id==id}.joinToString("\n"){"${it.id}~${it.title.replace("~"," ")}~${it.category.replace("~"," ")}~${it.amount}~${it.time}~${it.paymentMethod}~${it.shiftId}"}).apply()
 fun shifts()=p.getString("shifts","")!!.lines().filter{it.isNotBlank()}.mapNotNull{runCatching{val a=it.split("~");Shift(a[0].toLong(),dec(a[1]),a[2].toLong(),a[3].toDouble(),a.getOrNull(4)?.toLongOrNull()?:0L,a.getOrNull(5)?.toDoubleOrNull()?:0.0,a.getOrNull(6)?:"OPEN")}.getOrNull()};private fun saveShifts(xs:List<Shift>)=p.edit().putString("shifts",xs.joinToString("\n"){"${it.id}~${enc(it.person)}~${it.openedAt}~${it.openingCash}~${it.closedAt}~${it.closingCash}~${it.status}"}).apply();fun activeShifts()=shifts().filter{it.status=="OPEN"}.sortedBy{it.openedAt};fun activeShift(person:String=staffName()):Shift?{val open=activeShifts();return if(person.isBlank())open.lastOrNull() else open.lastOrNull{it.person.equals(person.trim(),true)} };fun openShift(person:String,cash:Double):Shift{val who=person.trim().ifBlank{staffName().ifBlank{"Staff"}};activeShift(who)?.let{return it};val now=System.currentTimeMillis();val s=Shift(newOrderId(),who,now,cash);saveShifts(shifts()+s);appendOrderAudit(0L,who,staffRole(),"SHIFT_OPENED","Opening cash: $cash",now);return s};fun closeShift(id:Long,cash:Double){val shift=shifts().firstOrNull{it.id==id&&it.status=="OPEN"}?:return;val now=System.currentTimeMillis();saveShifts(shifts().map{if(it.id==id)it.copy(closedAt=now,closingCash=cash,status="CLOSED")else it});appendOrderAudit(0L,shift.person,staffRole(),"SHIFT_CLOSED","Closing cash: $cash; expected cash: ${expectedShiftCash(id)}",now)};fun mergeShifts(remote:org.json.JSONArray){val all=linkedMapOf<Long,Shift>();(shifts()+ (0 until remote.length()).mapNotNull{i->runCatching{val x=remote.getJSONObject(i);Shift(x.getLong("id"),x.optString("person"),x.getLong("openedAt"),x.optDouble("openingCash",0.0),x.optLong("closedAt",0L),x.optDouble("closingCash",0.0),x.optString("status","OPEN"))}.getOrNull()}).forEach{s->val old=all[s.id];all[s.id]=if(old==null)s else if(s.closedAt>=old.closedAt&&s.status=="CLOSED")s else old};saveShifts(all.values.sortedBy{it.openedAt})};fun shiftsJson():org.json.JSONArray=org.json.JSONArray().apply{shifts().forEach{s->put(org.json.JSONObject().put("id",s.id).put("person",s.person).put("openedAt",s.openedAt).put("openingCash",s.openingCash).put("closedAt",s.closedAt).put("closingCash",s.closingCash).put("status",s.status))}};fun shiftSales(id:Long)=sales().filter{it.shiftId==id};fun shiftExpenses(id:Long)=expenses().filter{it.shiftId==id}
 fun payments()=p.getString("payments","")!!.lines().filter{it.isNotBlank()}.mapNotNull{runCatching{val a=it.split("~");PaymentRecord(a[0].toLong(),a[1].toLong(),a[2].toDouble(),a[3],a[4].toLong(),a[5].toLong(),dec(a.getOrNull(6).orEmpty()))}.getOrNull()};private fun addPayment(x:PaymentRecord)=p.edit().putString("payments",(payments()+x).joinToString("\n"){"${it.id}~${it.saleId}~${it.amount}~${it.method}~${it.time}~${it.shiftId}~${enc(it.note)}"}).apply();fun expectedShiftCash(id:Long):Double{val s=shifts().firstOrNull{it.id==id}?:return 0.0;return s.openingCash+payments().filter{it.shiftId==id&&it.method=="CASH"}.sumOf{it.amount}-shiftExpenses(id).filter{it.paymentMethod=="CASH"}.sumOf{it.amount}}
 private fun allDues()=p.getString("dues","")!!.lines().filter{it.isNotBlank()}.mapNotNull{runCatching{val a=it.split("~");DueAccount(a[0].toLong(),a[1].toLong(),dec(a[2]),dec(a[3]),a[4].toDouble(),a[5].toDouble(),a[6].toLong())}.getOrNull()}
 fun dues()=allDues().filter{it.balance>0.005};private fun saveDues(xs:List<DueAccount>)=p.edit().putString("dues",xs.joinToString("\n"){"${it.id}~${it.saleId}~${enc(it.customerName)}~${enc(it.customerPhone)}~${it.total}~${it.paid}~${it.createdAt}"}).apply();private fun addDue(x:DueAccount)=saveDues(allDues()+x);fun addCustomerDue(name:String,phone:String,total:Double):Boolean{if(name.isBlank()||total<=0.0)return false;val id=System.currentTimeMillis();saveDues(allDues()+DueAccount(id,id,name.trim(),phone.trim(),total,0.0,id));return true};fun removeDue(id:Long){saveDues(allDues().filterNot{it.id==id})};fun recordPayment(dueId:Long,amount:Double,method:String,shiftId:Long):Boolean{val d=dues().firstOrNull{it.id==dueId}?:return false;val pay=amount.coerceIn(0.0,d.balance);if(pay<=0)return false;addPayment(PaymentRecord(System.currentTimeMillis(),d.saleId,pay,method,System.currentTimeMillis(),shiftId,"Due payment"));if(method=="CASH")setCash(cash()+pay);saveDues(allDues().map{if(it.id==d.id)it.copy(paid=(it.paid+pay).coerceAtMost(it.total))else it});sales().firstOrNull{it.id==d.saleId}?.let{val paid=it.paidAmount+pay;updateSale(it.copy(paidAmount=paid,dueAmount=(it.total-paid).coerceAtLeast(0.0)))};return true}
 fun paymentTotals(start:Long=0L,end:Long=Long.MAX_VALUE)=payments().filter{it.time in start..end}.groupBy{it.method}.mapValues{it.value.sumOf(PaymentRecord::amount)};fun productTotals(start:Long=0L,end:Long=Long.MAX_VALUE)=sales().filter{it.time in start..end}.flatMap{it.items}.groupBy{canonicalProductKey(it.name)}.map{(_,items)->items.first().name to items.sumOf(SaleItem::qty)}.sortedByDescending{it.second};fun grossSales(start:Long=0L,end:Long=Long.MAX_VALUE)=sales().filter{it.time in start..end}.sumOf{it.total};fun realizedSales(start:Long=0L,end:Long=Long.MAX_VALUE)=sales().filter{it.time in start..end}.sumOf{it.paidAmount};fun dueTotal()=dues().sumOf{it.balance};fun totalExpenses(start:Long=0L,end:Long=Long.MAX_VALUE)=expenses().filter{it.time in start..end}.sumOf{it.amount}
 private fun itemsJson(xs:List<SaleItem>)=org.json.JSONArray().apply{xs.forEach{put(org.json.JSONObject().apply{put("name",it.name);put("qty",it.qty);put("price",it.price);put("kitchen",it.kitchen)})}};private fun saleJson(s:Sale)=org.json.JSONObject().apply{put("id",s.id);put("time",s.time);put("total",s.total);put("customerName",s.customerName);put("customerPhone",s.customerPhone);put("orderType",s.orderType);put("customerAddress",s.customerAddress);put("paymentMethod",s.paymentMethod);put("paidAmount",s.paidAmount);put("dueAmount",s.dueAmount);put("shiftId",s.shiftId);put("serviceCharge",s.serviceCharge);put("tax",s.tax);put("items",itemsJson(s.items))};private fun pendingJson(s:PendingSale)=org.json.JSONObject().apply{put("id",s.id);put("time",s.time);put("customerName",s.customerName);put("customerPhone",s.customerPhone);put("orderType",s.orderType);put("customerAddress",s.customerAddress);put("shiftPerson",s.shiftPerson);put("tableName",s.tableName);put("waiterName",s.waiterName);put("counterName",s.counterName);put("chefName",s.chefName);put("status",s.status);put("specialInstructions",s.specialInstructions);put("updatedAt",s.updatedAt);put("servedAt",s.servedAt);put("kitchenProgress",s.kitchenProgress);put("items",itemsJson(s.items))}
 fun exportBackup():String{val r=org.json.JSONObject();val q=profile();r.put("format","VOICE_POS_BACKUP_V5");r.put("cash",cash());r.put("opening",hasOpeningCash());r.put("theme",theme().name);r.put("profile",org.json.JSONObject().apply{put("name",q.name);put("address",q.address);put("phone",q.phone);put("footer",q.footer);put("saleLogo",q.saleLogoEnabled);put("kitchenLogo",q.kitchenLogoEnabled);put("logo",q.logoPath.takeIf{it.isNotBlank()}?.let{runCatching{Base64.encodeToString(File(it).readBytes(),Base64.NO_WRAP)}.getOrDefault("")}?:"");put("currencySymbol",q.currencySymbol);put("currencyCode",q.currencyCode);put("taxEnabled",q.taxEnabled);put("taxRate",q.taxRate);put("taxLabel",q.taxLabel);put("serviceEnabled",q.serviceChargeEnabled);put("serviceRate",q.serviceChargeRate);put("serviceLabel",q.serviceChargeLabel)});r.put("menu",org.json.JSONArray().apply{menu().forEach{put(org.json.JSONObject().apply{put("id",it.id);put("name",it.name);put("price",it.price);put("variant",it.variant);put("size",it.size);put("kitchen",it.kitchen)})}});r.put("sales",org.json.JSONArray().apply{sales().forEach{put(saleJson(it))}});r.put("pending",org.json.JSONArray().apply{pending().forEach{put(pendingJson(it))}});r.put("expenses",org.json.JSONArray().apply{expenses().forEach{put(org.json.JSONObject().apply{put("id",it.id);put("title",it.title);put("category",it.category);put("amount",it.amount);put("time",it.time);put("paymentMethod",it.paymentMethod);put("shiftId",it.shiftId)})}});r.put("shifts",org.json.JSONArray().apply{shifts().forEach{put(org.json.JSONObject().apply{put("id",it.id);put("person",it.person);put("openedAt",it.openedAt);put("openingCash",it.openingCash);put("closedAt",it.closedAt);put("closingCash",it.closingCash);put("status",it.status)})}});r.put("dues",org.json.JSONArray().apply{allDues().forEach{put(org.json.JSONObject().apply{put("id",it.id);put("saleId",it.saleId);put("customerName",it.customerName);put("customerPhone",it.customerPhone);put("total",it.total);put("paid",it.paid);put("createdAt",it.createdAt)})}});r.put("payments",org.json.JSONArray().apply{payments().forEach{put(org.json.JSONObject().apply{put("id",it.id);put("saleId",it.saleId);put("amount",it.amount);put("method",it.method);put("time",it.time);put("shiftId",it.shiftId);put("note",it.note)})}});r.put("events",org.json.JSONArray(context.getSharedPreferences("voice_event_plans",0).getString("plans","[]")?:"[]"));r.put("inventory",InventoryStore(context).exportBackup());r.put("orderAudit",orderAudit());return r.toString()}
 private fun ja(a:org.json.JSONArray)= (0 until a.length()).map{val x=a.getJSONObject(it);SaleItem(x.getString("name"),x.getInt("qty"),x.getDouble("price"),x.optString("kitchen","FAST_FOOD"))}

 @Synchronized fun mergeBackup(raw:String):String {
  val incoming=org.json.JSONObject(raw)
  require(incoming.optString("format").startsWith("VOICE_POS_BACKUP_V"))
  val current=org.json.JSONObject(exportBackup())
  fun mergeArray(name:String, identity:(org.json.JSONObject)->String) {
   val merged=org.json.JSONArray();val seen=linkedSetOf<String>()
   val old=current.optJSONArray(name)?:org.json.JSONArray()
   val fresh=incoming.optJSONArray(name)?:org.json.JSONArray()
   for(source in listOf(old,fresh)) for(i in 0 until source.length()) {
    val item=source.optJSONObject(i)?:continue
    val key=identity(item)
    if(seen.add(key))merged.put(item)
   }
   current.put(name,merged)
  }
  run {
   val byId=linkedMapOf<String,org.json.JSONObject>()
   for(source in listOf(current.optJSONArray("sales")?:org.json.JSONArray(),incoming.optJSONArray("sales")?:org.json.JSONArray())) {
    for(i in 0 until source.length()) {
     val item=source.optJSONObject(i)?:continue
     val key=item.optString("id")
     val previous=byId[key]
     val newerPaid=item.optDouble("paidAmount",0.0)> (previous?.optDouble("paidAmount",0.0)?:-1.0)
     val samePaid=item.optDouble("paidAmount",0.0)==(previous?.optDouble("paidAmount",0.0)?:-1.0)
     val lowerDue=item.optDouble("dueAmount",Double.MAX_VALUE)<(previous?.optDouble("dueAmount",Double.MAX_VALUE)?:Double.MAX_VALUE)
     if(previous==null||newerPaid||(samePaid&&lowerDue))byId[key]=item
    }
   }
   current.put("sales",org.json.JSONArray().apply{byId.values.forEach{put(it)}})
  }
  // Keep the newest version of each live order; the older first-wins merge
  // silently discarded waiter notes and kitchen status changes during sync.
  run {
   val byId=linkedMapOf<String,org.json.JSONObject>()
   val old=current.optJSONArray("pending")?:org.json.JSONArray()
   val fresh=incoming.optJSONArray("pending")?:org.json.JSONArray()
   for(source in listOf(old,fresh)) for(i in 0 until source.length()) {
    val item=source.optJSONObject(i)?:continue
    val key=item.optString("id")
    val previous=byId[key]
    if(previous==null||item.optLong("updatedAt",item.optLong("time"))>previous.optLong("updatedAt",previous.optLong("time")))byId[key]=item
   }
   current.put("pending",org.json.JSONArray().apply{byId.values.forEach{put(it)}})
  }
  mergeArray("expenses"){it.optString("id")+":"+it.optString("time")+":"+it.optString("title")}
  run {
   val byId=linkedMapOf<String,org.json.JSONObject>()
   for(source in listOf(current.optJSONArray("shifts")?:org.json.JSONArray(),incoming.optJSONArray("shifts")?:org.json.JSONArray())) {
    for(i in 0 until source.length()) {
     val item=source.optJSONObject(i)?:continue
     val key=item.optString("id")
     val previous=byId[key]
     val incomingClosed=item.optString("status")=="CLOSED"
     val previousClosed=previous?.optString("status")=="CLOSED"
     if(previous==null||(incomingClosed&&!previousClosed)||(incomingClosed&&previousClosed&&item.optLong("closedAt")>=previous.optLong("closedAt")))byId[key]=item
    }
   }
   current.put("shifts",org.json.JSONArray().apply{byId.values.forEach{put(it)}})
  }
  mergeArray("payments"){it.optString("saleId")+":"+it.optString("amount")+":"+it.optString("method")+":"+it.optString("time")}
  run {
   val paidBySale=linkedMapOf<String,Double>()
   val payments=current.optJSONArray("payments")?:org.json.JSONArray()
   for(i in 0 until payments.length()){
    val payment=payments.optJSONObject(i)?:continue
    val key=payment.optString("saleId")
    paidBySale[key]=(paidBySale[key]?:0.0)+payment.optDouble("amount",0.0)
   }
   val sales=current.optJSONArray("sales")?:org.json.JSONArray()
   for(i in 0 until sales.length()){
    val sale=sales.optJSONObject(i)?:continue
    val total=sale.optDouble("total",0.0).coerceAtLeast(0.0)
    val paid=maxOf(sale.optDouble("paidAmount",0.0),paidBySale[sale.optString("id")]?:0.0).coerceIn(0.0,total)
    sale.put("paidAmount",paid).put("dueAmount",(total-paid).coerceAtLeast(0.0))
   }
  }
  run {
   val byId=linkedMapOf<String,org.json.JSONObject>()
   for(source in listOf(current.optJSONArray("dues")?:org.json.JSONArray(),incoming.optJSONArray("dues")?:org.json.JSONArray())) {
    for(i in 0 until source.length()) {
     val item=source.optJSONObject(i)?:continue
     val key=item.optString("id")+":"+item.optString("saleId")
     val previous=byId[key]
     if(previous==null||item.optDouble("paid",0.0)>previous.optDouble("paid",0.0))byId[key]=org.json.JSONObject(item.toString())
    }
   }
   val paidBySale=linkedMapOf<String,Double>()
   val payments=current.optJSONArray("payments")?:org.json.JSONArray()
   for(i in 0 until payments.length()) {
    val payment=payments.optJSONObject(i)?:continue
    val key=payment.optString("saleId")
    paidBySale[key]=(paidBySale[key]?:0.0)+payment.optDouble("amount",0.0)
   }
   val merged=org.json.JSONArray()
   byId.values.forEach{due->
    val total=due.optDouble("total",0.0).coerceAtLeast(0.0)
    val paid=maxOf(due.optDouble("paid",0.0),paidBySale[due.optString("saleId")]?:0.0).coerceIn(0.0,total)
    due.put("paid",paid)
    merged.put(due)
   }
   current.put("dues",merged)
  }
  mergeArray("events"){it.optString("id")}
  // Preserve audit events from both backups. Older records without eventId
  // use a stable content key so repeated imports remain idempotent.
  run {
   val byId=linkedMapOf<String,org.json.JSONObject>()
   for(source in listOf(current.optJSONArray("orderAudit")?:org.json.JSONArray(),incoming.optJSONArray("orderAudit")?:org.json.JSONArray())) {
    for(i in 0 until source.length()) {
     val event=source.optJSONObject(i)?:continue
     val key=event.optString("eventId").ifBlank {
      listOf("orderId","actor","role","action","detail","time").joinToString("|"){event.optString(it)}
     }
     byId.putIfAbsent(key,event)
    }
   }
   // Sort by event time before bounding so merge order cannot discard newer history.
   val ordered=byId.values.sortedWith(compareBy<org.json.JSONObject>{it.optLong("time")}.thenBy{it.optString("eventId")})
   val bounded=org.json.JSONArray()
   ordered.takeLast(5000).forEach{bounded.put(it)}
   current.put("orderAudit",bounded)
  }
  val sold=current.optJSONArray("sales")?:org.json.JSONArray()
  val soldIds=(0 until sold.length()).mapNotNull{sold.optJSONObject(it)?.optString("id")}.toSet()
  val pending=current.optJSONArray("pending")?:org.json.JSONArray()
  val cleanPending=org.json.JSONArray()
  for(i in 0 until pending.length()){val item=pending.optJSONObject(i)?:continue;if(item.optString("id") !in soldIds)cleanPending.put(item)}
  current.put("pending",cleanPending)
  current.put("inventory",InventoryStore(context).mergeBackup(current.optJSONObject("inventory"),incoming.optJSONObject("inventory")))
  importBackup(current.toString())
  return exportBackup()
 }

 @Synchronized fun importBackup(raw:String){
 require(raw.isNotBlank()&&raw.length<=50*1024*1024){"Backup file is empty or exceeds 50 MB"}
 val r=org.json.JSONObject(raw)
 require(r.optString("format").matches(Regex("VOICE_POS_BACKUP_V[0-9]+"))){"Unsupported backup format"}
 listOf("menu","sales","pending","expenses","dues","shifts","payments").forEach{name->require(r.opt(name) is org.json.JSONArray){"Backup is missing or has invalid $name data"}}
 val q=r.optJSONObject("profile")?:error("Backup profile is missing")
 require(q.optString("name").isNotBlank()){"Backup business name is missing"}
 val safetyDir=File(context.filesDir,"automatic_backups")
 if(!safetyDir.exists()&&!safetyDir.mkdirs())error("Could not create pre-restore safety folder")
 val safetyFile=File(safetyDir,"before-restore-${System.currentTimeMillis()}.json")
 val safetyRaw=exportBackup()
 java.io.FileOutputStream(safetyFile).use{it.write(safetyRaw.toByteArray(Charsets.UTF_8));it.fd.sync()}
 require(safetyFile.length()>0L){"Could not preserve current data before restore"}
 safetyDir.listFiles()?.filter{it.isFile&&it.name.startsWith("before-restore-")&&it.name.endsWith(".json")}?.sortedByDescending{it.lastModified()}?.drop(5)?.forEach{it.delete()}
 var lp=profile().logoPath;val b=q.optString("logo","");if(b.isNotBlank())runCatching{val f=File(context.filesDir,"branding/company_logo.png");f.parentFile?.mkdirs();f.writeBytes(Base64.decode(b,Base64.DEFAULT));lp=f.absolutePath};saveProfile(CompanyProfile(q.optString("name",DEFAULT_NAME),q.optString("address",DEFAULT_ADDRESS),q.optString("phone",DEFAULT_PHONE),q.optString("footer","Thank you for your visit"),lp,q.optBoolean("saleLogo",true),q.optBoolean("kitchenLogo",true),q.optString("currencySymbol","Rs"),q.optString("currencyCode","PKR"),q.optBoolean("taxEnabled",false),q.optDouble("taxRate",0.0),q.optString("taxLabel","Tax"),q.optBoolean("serviceEnabled",false),q.optDouble("serviceRate",0.0),q.optString("serviceLabel","Service Charge")));setCash(r.optDouble("cash",0.0));p.edit().putBoolean("opening",r.optBoolean("opening",true)).putString("theme",r.optString("theme","MODERN")).apply();val m=r.optJSONArray("menu")?:org.json.JSONArray();saveMenu((0 until m.length()).map{val x=m.getJSONObject(it);MenuItem(x.getLong("id"),x.getString("name"),x.getDouble("price"),x.optString("variant"),x.optString("size"),x.optString("kitchen","FAST_FOOD"))});val s=r.optJSONArray("sales")?:org.json.JSONArray();saveSales((0 until s.length()).map{val x=s.getJSONObject(it);Sale(x.getLong("id"),ja(x.getJSONArray("items")),x.getDouble("total"),x.getLong("time"),x.optString("customerName"),x.optString("customerPhone"),x.optString("orderType","DINE_IN"),x.optString("customerAddress"),x.optString("paymentMethod","CASH"),x.optDouble("paidAmount",x.getDouble("total")),x.optDouble("dueAmount",0.0),x.optLong("shiftId",0L),x.optDouble("serviceCharge",0.0),x.optDouble("tax",0.0))});val pd=r.optJSONArray("pending")?:org.json.JSONArray();savePending((0 until pd.length()).map{val x=pd.getJSONObject(it);PendingSale(x.getLong("id"),ja(x.getJSONArray("items")),x.getLong("time"),x.optString("customerName"),x.optString("customerPhone"),x.optString("orderType","DINE_IN"),x.optString("customerAddress"),x.optString("shiftPerson"),x.optString("tableName"),x.optString("waiterName"),x.optString("counterName"),x.optString("chefName"),x.optString("status","NEW"),x.optString("specialInstructions"),x.optLong("updatedAt",x.getLong("time")),x.optLong("servedAt",0L),x.optString("kitchenProgress","{}"))});val e=r.optJSONArray("expenses")?:org.json.JSONArray();p.edit().putString("expenses",(0 until e.length()).joinToString("\n"){val x=e.getJSONObject(it);"${x.getLong("id")}~${x.getString("title")}~${x.getString("category")}~${x.getDouble("amount")}~${x.getLong("time")}~${x.optString("paymentMethod","CASH")}~${x.optLong("shiftId",0L)}"}).apply();val d=r.optJSONArray("dues")?:org.json.JSONArray();p.edit().putString("dues",(0 until d.length()).joinToString("\n"){val x=d.getJSONObject(it);"${x.getLong("id")}~${x.getLong("saleId")}~${enc(x.optString("customerName"))}~${enc(x.optString("customerPhone"))}~${x.getDouble("total")}~${x.getDouble("paid")}~${x.getLong("createdAt")}"}).apply();val sh=r.optJSONArray("shifts")?:org.json.JSONArray();p.edit().putString("shifts",(0 until sh.length()).joinToString("\n"){val x=sh.getJSONObject(it);"${x.getLong("id")}~${enc(x.optString("person"))}~${x.getLong("openedAt")}~${x.getDouble("openingCash")}~${x.optLong("closedAt",0L)}~${x.optDouble("closingCash",0.0)}~${x.optString("status","OPEN")}"}).apply();val py=r.optJSONArray("payments")?:org.json.JSONArray();p.edit().putString("payments",(0 until py.length()).joinToString("\n"){val x=py.getJSONObject(it);"${x.getLong("id")}~${x.getLong("saleId")}~${x.getDouble("amount")}~${x.getString("method")}~${x.getLong("time")}~${x.getLong("shiftId")}~${enc(x.optString("note"))}"}).apply();if(r.has("events"))context.getSharedPreferences("voice_event_plans",0).edit().putString("plans",r.optJSONArray("events")?.toString()?: "[]").apply();if(r.has("inventory"))InventoryStore(context).importBackup(r.optJSONObject("inventory"));if(r.has("orderAudit"))p.edit().putString("order_audit",r.optJSONArray("orderAudit")?.toString()?:"[]").apply()}
}
