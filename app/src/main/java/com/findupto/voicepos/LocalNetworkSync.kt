package com.findupto.voicepos

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import java.util.Locale

object LocalNetworkSync {
 private const val PORT=8765
 private const val PREFS="voice_local_network"
 @Volatile private var server:PosLanServer?=null
 private val offlineQueueLock=Any()
 fun token(context:Context):String {
  val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
  return p.getString("access_token",null)?:ByteArray(24).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it)}.also{value->check(p.edit().putString("access_token",value).commit()){"Could not persist the local owner access token"}}
 }
 @Synchronized fun start(context:Context,store:Store):Boolean {
  if(server?.isAlive==true)return true
  val auth=context.getSharedPreferences("voice_staff_auth",Context.MODE_PRIVATE)
  val accounts=runCatching{JSONObject(auth.getString("accounts","{}")?:"{}")}.getOrNull()?:return false
  // Never seed predictable staff PINs. An empty account store may host only
  // long enough for the owner to bootstrap the first OWNER account with the
  // random, device-local access code. Once accounts exist, only OWNER/ADMIN
  // devices may host the LAN server.
  if(accounts.length()>0 && store.staffRole() !in setOf("OWNER","ADMIN")) return false
  return runCatching{PosLanServer(context,store,token(context)).also{it.start(NanoHTTPD.SOCKET_READ_TIMEOUT,false);server=it};true}.getOrDefault(false)
 }
 @Synchronized fun stop(){server?.stop();server=null}
 fun isRunning()=server?.isAlive==true
 fun ipAddress():String=runCatching{
  val interfaces=Collections.list(NetworkInterface.getNetworkInterfaces()).filter{it.isUp&&!it.isLoopback}
  val ordered=interfaces.sortedBy{n->when{n.name.startsWith("wlan",true)->0;n.name.startsWith("ap",true)->1;n.name.startsWith("eth",true)->2;else->3}}
  ordered.asSequence().flatMap{Collections.list(it.inetAddresses).asSequence()}.filterIsInstance<Inet4Address>().firstOrNull{it.isSiteLocalAddress}?.hostAddress?:"IP unavailable"
 }.getOrDefault("IP unavailable")
 fun testConnection(ip:String):String=request(ip,"","/health")

 private fun endpoint(ip:String,path:String):String {
  val host=ip.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")
  val address=host.substringBefore(":")
  val octets=address.split(".").map{it.toIntOrNull()?:-1}
  require(octets.size==4&&octets.all{it in 0..255}){"Enter a valid owner IPv4 address."}
  val privateAddress=octets[0]==10||
   (octets[0]==172&&octets[1] in 16..31)||
   (octets[0]==192&&octets[1]==168)||
   (octets[0]==169&&octets[1]==254)||
   (octets[0]==100&&octets[1] in 64..127)
  require(privateAddress){"Only a private/local network IPv4 address is allowed; public internet addresses are blocked."}
  val port=host.substringAfter(":",PORT.toString()).toIntOrNull()
  require(port!=null&&port in 1..65535){"Enter a valid local port."}
  return "http://$address:$port$path"
 }
 private fun readLimited(input:java.io.InputStream,maxBytes:Int=8_000_000):String {
  val output=ByteArrayOutputStream()
  val buffer=ByteArray(8192)
  var total=0
  while(true){
   val count=input.read(buffer)
   if(count<0)break
   total+=count
   require(total<=maxBytes){"Server response exceeds the 8 MB limit"}
   output.write(buffer,0,count)
  }
  return output.toString(Charsets.UTF_8.name())
 }
 private fun request(ip:String,token:String,path:String,method:String="GET",body:String?=null,ownerCode:Boolean=false):String {
  val bytes=body?.toByteArray(Charsets.UTF_8)
  require(bytes==null||bytes.size<=8_000_000){"Request exceeds the 8 MB limit"}
  val c=URL(endpoint(ip,path)).openConnection() as HttpURLConnection
  try {
   c.connectTimeout=7000;c.readTimeout=10000;c.requestMethod=method
   c.instanceFollowRedirects=false;c.useCaches=false
   c.setRequestProperty(if(ownerCode) "X-POS-Token" else "X-POS-Session",token.trim())
   c.setRequestProperty("Accept","application/json")
   c.setRequestProperty("Cache-Control","no-store")
   c.setRequestProperty("Connection","close")
   if(bytes!=null){c.doOutput=true;c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setFixedLengthStreamingMode(bytes.size);c.outputStream.use{it.write(bytes)}}
   val status=c.responseCode
   val stream=if(status in 200..299)c.inputStream else c.errorStream
   val response=stream?.use{readLimited(it)}.orEmpty()
   if(status !in 200..299)throw IllegalStateException(runCatching{JSONObject(response).optString("error")}.getOrNull()?.takeIf{it.isNotBlank()}?:"Server rejected request ($status)")
   return response
  } finally { c.disconnect() }
 }
 fun sync(serverIp:String,accessToken:String,store:Store):String {
  val response=request(serverIp,accessToken,"/sync","POST",store.exportBackup())
  store.importBackup(response)
  return "Business data synchronized."
 }
 fun pullOrders(ip:String,token:String,store:Store):List<PendingSale> {
  val a=JSONArray(request(ip,token,"/orders"))
  val result=ArrayList<PendingSale>()
  for(i in 0 until a.length()){
   val x=a.getJSONObject(i);val items=x.getJSONArray("items")
   val lines=(0 until items.length()).map{val q=items.getJSONObject(it);SaleItem(q.getString("name"),q.getInt("qty"),q.getDouble("price"),q.optString("kitchen","FAST_FOOD"))}
   val order=PendingSale(x.getLong("id"),lines,x.getLong("time"),x.optString("customerName"),x.optString("customerPhone"),x.optString("orderType","DINE_IN"),x.optString("customerAddress"),x.optString("shiftPerson"),x.optString("tableName"),x.optString("waiterName"),x.optString("counterName"),x.optString("chefName"),x.optString("status","NEW"),x.optString("specialInstructions"),x.optLong("updatedAt",x.getLong("time")),x.optLong("servedAt",0L),x.optString("kitchenProgress","{}"))
   store.upsertRemoteOrder(order);result.add(order)
  }
  runCatching{store.mergeOrderAudit(JSONArray(request(ip,token,"/orders/audit")))}
  // Completed-order IDs have their own retention, independent of the shorter audit log.
  runCatching{store.mergeCompletedOrderIds(JSONArray(request(ip,token,"/orders/completed")))}
  store.reconcileCompletedOrdersFromAudit()
  runCatching{store.mergeShifts(JSONArray(request(ip,token,"/shifts")))}
  runCatching{request(ip,token,"/shifts","POST",store.shiftsJson().toString())}
  return result
 }
 fun pushOrder(ip:String,token:String,order:PendingSale):String=request(ip,token,"/orders","POST",orderJson(order).toString())
 fun queueOfflineOrder(context:Context,order:PendingSale){
  val prefs=context.getSharedPreferences("voice_local_network",Context.MODE_PRIVATE)
  synchronized(offlineQueueLock){
   val queue=runCatching{JSONArray(prefs.getString("offline_order_queue","[]")?:"[]")}.getOrDefault(JSONArray())
   if((0 until queue.length()).any{queue.optJSONObject(it)?.optLong("id")==order.id})return
   check(queue.length()<5000){"Offline order queue is full. Connect to the owner device before accepting more offline orders."}
   queue.put(orderJson(order))
   check(prefs.edit().putString("offline_order_queue",queue.toString()).commit()){"Could not persist the offline order queue"}
  }
 }
 fun offlineOrderQueueCount(context:Context):Int {
  val prefs=context.getSharedPreferences("voice_local_network",Context.MODE_PRIVATE)
  return synchronized(offlineQueueLock){runCatching{JSONArray(prefs.getString("offline_order_queue","[]")?:"[]").length()}.getOrDefault(0)}
 }
 fun retryOfflineOrders(ip:String,token:String,context:Context):Int{
  val prefs=context.getSharedPreferences("voice_local_network",Context.MODE_PRIVATE)
  val batch=synchronized(offlineQueueLock){
   val queue=runCatching{JSONArray(prefs.getString("offline_order_queue","[]")?:"[]")}.getOrDefault(JSONArray())
   (0 until minOf(queue.length(),25)).mapNotNull{index->queue.optJSONObject(index)?.let{JSONObject(it.toString())}}
  }
  val sentIds=HashSet<Long>()
  var sent=0
  for(order in batch){
   try{request(ip,token,"/orders","POST",order.toString());sentIds.add(order.optLong("id"));sent++}
   catch(_:Exception){}
  }
  if(sentIds.isNotEmpty())synchronized(offlineQueueLock){
   val current=runCatching{JSONArray(prefs.getString("offline_order_queue","[]")?:"[]")}.getOrDefault(JSONArray())
   val remaining=JSONArray()
   for(i in 0 until current.length()){
    val order=current.optJSONObject(i)?:continue
    if(order.optLong("id") !in sentIds)remaining.put(order)
   }
   check(prefs.edit().putString("offline_order_queue",remaining.toString()).commit()){"Could not persist offline queue progress"}
  }
  return sent
 }
 fun pullMessages(ip:String,token:String):JSONArray=JSONArray(request(ip,token,"/messages"))
 fun sendMessage(ip:String,token:String,id:String,author:String,role:String,message:String):String=request(ip,token,"/messages","POST",JSONObject().put("id",id).put("author",author).put("role",role).put("message",message).put("time",System.currentTimeMillis()).toString())
 fun pushKitchenStatus(ip:String,token:String,id:Long,station:String,status:String):String=request(ip,token,"/orders/kitchen","POST",JSONObject().put("id",id).put("station",station).put("status",status).toString())
 fun pushStatus(ip:String,token:String,id:Long,status:String,actor:String,role:String,note:String=""):String=request(ip,token,"/orders/status","POST",JSONObject().put("id",id).put("status",status).put("note",note).toString())
 fun loginStaff(ip:String,name:String,pin:String):JSONObject=JSONObject(request(ip,"","/staff/login","POST",JSONObject().put("name",name).put("pin",pin).toString(),true))
 fun enrollStaff(ip:String,ownerSession:String,name:String,pin:String,role:String):String=request(ip,ownerSession,"/staff/enroll","POST",JSONObject().put("name",name).put("pin",pin).put("role",role).toString())
 fun setupOwner(ip:String,ownerCode:String,name:String,pin:String):String=request(ip,ownerCode,"/staff/enroll","POST",JSONObject().put("name",name).put("pin",pin).put("role","OWNER").toString(),true)
 fun listStaff(ip:String,ownerSession:String):JSONArray=JSONArray(request(ip,ownerSession,"/staff/accounts"))
 fun setStaffActive(ip:String,ownerSession:String,name:String,active:Boolean):String=request(ip,ownerSession,"/staff/accounts/status","POST",JSONObject().put("name",name).put("active",active).toString())
 fun resetStaffPin(ip:String,ownerSession:String,name:String,newPin:String):String=request(ip,ownerSession,"/staff/accounts/pin","POST",JSONObject().put("name",name).put("pin",newPin).toString())
 fun editStaffAccount(ip:String,ownerSession:String,oldName:String,newName:String,role:String):String=request(ip,ownerSession,"/staff/accounts/edit","POST",JSONObject().put("oldName",oldName).put("newName",newName).put("role",role).toString())
 fun logoutStaff(ip:String,session:String):String=request(ip,session,"/staff/logout","POST",JSONObject().put("ok",true).toString())
 fun staffPermissions(ip:String,ownerSession:String):JSONArray=JSONArray(request(ip,ownerSession,"/staff/accounts/permissions"))
 fun setStaffPermissions(ip:String,ownerSession:String,name:String,permissions:Set<String>):String=request(ip,ownerSession,"/staff/accounts/permissions","POST",JSONObject().put("name",name).put("permissions",JSONArray(permissions.sorted())).toString())
 private fun orderJson(s:PendingSale)=JSONObject().apply{
  put("id",s.id);put("time",s.time);put("customerName",s.customerName);put("customerPhone",s.customerPhone);put("orderType",s.orderType);put("customerAddress",s.customerAddress);put("shiftPerson",s.shiftPerson);put("tableName",s.tableName);put("waiterName",s.waiterName);put("counterName",s.counterName);put("chefName",s.chefName);put("status",s.status);put("specialInstructions",s.specialInstructions);put("updatedAt",s.updatedAt);put("servedAt",s.servedAt);put("kitchenProgress",s.kitchenProgress)
  put("items",JSONArray().apply{s.items.forEach{put(JSONObject().put("name",it.name).put("qty",it.qty).put("price",it.price).put("kitchen",it.kitchen))}})
 }
 private data class StaffSession(val name:String,val role:String,val expiresAt:Long)
 private class PosLanServer(private val context:Context,private val store:Store,private val secret:String):NanoHTTPD("0.0.0.0",PORT){
  private val sessions=ConcurrentHashMap<String,StaffSession>()
  private data class LoginAttempt(var failures:Int=0,var blockedUntil:Long=0L)
  private val loginAttempts=ConcurrentHashMap<String,LoginAttempt>()
  private val authPrefs=context.getSharedPreferences("voice_staff_auth",Context.MODE_PRIVATE)
  private val messagePrefs=context.getSharedPreferences("voice_staff_messages",Context.MODE_PRIVATE)
  private val messageLock=Any()
  private val accountLock=Any()
  init { restoreSessions() }
  private fun restoreSessions() {
   val saved=runCatching{JSONObject(authPrefs.getString("sessions","{}")?:"{}")}.getOrDefault(JSONObject())
   val now=System.currentTimeMillis()
   saved.keys().forEach { key -> runCatching {
    val item=saved.getJSONObject(key);val expires=item.getLong("expiresAt")
    if(expires>now)sessions[key]=StaffSession(item.getString("name"),item.getString("role"),expires)
   } }
   persistSessions()
  }
  @Synchronized private fun persistSessions() {
   val now=System.currentTimeMillis();val saved=JSONObject()
   sessions.entries.removeIf { it.value.expiresAt<=now }
   sessions.forEach { (key,value) -> saved.put(key,JSONObject().put("name",value.name).put("role",value.role).put("expiresAt",value.expiresAt)) }
   authPrefs.edit().putString("sessions",saved.toString()).apply()
  }
  private fun accounts()=runCatching{JSONObject(authPrefs.getString("accounts","{}")?: "{}")}.getOrElse{throw IllegalStateException("Staff authentication data is unreadable; refusing access") }
  private fun hasPermission(session:IHTTPSession,permission:String):Boolean {
   val authenticated=staffSession(session)?:return false
   val account=accounts().optJSONObject(authenticated.name.lowercase(Locale.ROOT))?:return false
   val stored=account.optJSONArray("permissions")
   val custom=if(stored==null)null else (0 until stored.length()).mapNotNull{stored.optString(it).takeIf{it.isNotBlank()}}.toSet()
   return permission in RolePermissions.effective(authenticated.role,custom)
  }
  private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it)}
  private fun unhex(value:String)=value.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
  private fun hashPin(pin:String,salt:ByteArray):String {
   val spec=PBEKeySpec(pin.toCharArray(),salt,120000,256)
   return try{hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)}finally{spec.clearPassword()}
  }
  private fun staffSession(s:IHTTPSession):StaffSession? {
   val key=s.headers["x-pos-session"].orEmpty();val found=sessions[key]?:return null
   if(found.expiresAt<System.currentTimeMillis()){sessions.remove(key);persistSessions();return null}
   val account=accounts().optJSONObject(found.name.lowercase(Locale.ROOT))
   if(account==null||!account.optBoolean("active",true)||account.optString("role")!=found.role){
    sessions.remove(key);persistSessions();return null
   }
   return found
  }
  private fun isOwner(s:IHTTPSession)=staffSession(s)?.role=="OWNER"
  override fun serve(session:IHTTPSession):Response {
   if(session.uri=="/health"&&session.method==Method.GET)return json(Response.Status.OK,JSONObject().put("service","Voice POS live orders").put("version",2))
   val supplied=session.headers["x-pos-token"].orEmpty()
   val firstSetup=session.uri=="/staff/enroll"&&session.method==Method.POST&&accounts().length()==0
   val login=session.uri=="/staff/login"&&session.method==Method.POST
   if(firstSetup){
    if(!MessageDigest.isEqual(supplied.toByteArray(Charsets.UTF_8),secret.toByteArray(Charsets.UTF_8)))return json(Response.Status.UNAUTHORIZED,JSONObject().put("error","Invalid owner access code"))
   } else if(!login&&staffSession(session)==null)return json(Response.Status.UNAUTHORIZED,JSONObject().put("error","Staff sign-in required"))
   val declaredLength=session.headers["content-length"]?.toLongOrNull()?:0L
   if(declaredLength>8_000_000L)return json(Response.Status.BAD_REQUEST,JSONObject().put("error","Request is too large"))
   return try {
    when {
     session.uri=="/staff/login"&&session.method==Method.POST -> {
      val x=JSONObject(readBody(session));val name=x.optString("name").trim();val pin=x.optString("pin")
      require(name.isNotBlank()&&name.length<=80&&pin.matches(Regex("[0-9]{4,8}"))){"Enter a staff name and a 4–8 digit PIN"}
      if(loginAttempts.size>1000)loginAttempts.entries.removeIf{it.value.blockedUntil<System.currentTimeMillis()&&it.value.failures==0}
      val attemptKey=name.lowercase(Locale.ROOT)
      val attempt=loginAttempts.computeIfAbsent(attemptKey){LoginAttempt()}
      synchronized(attempt){
       require(System.currentTimeMillis()>=attempt.blockedUntil){"Too many incorrect PIN attempts. Try again in a minute."}
      }
      val account=accounts().optJSONObject(attemptKey)
      if(account==null||!account.optBoolean("active",true)){
       synchronized(attempt){attempt.failures++;if(attempt.failures>=5){attempt.failures=0;attempt.blockedUntil=System.currentTimeMillis()+60_000L}}
       throw IllegalArgumentException("Staff account not found or disabled")
      }
      val actual=hashPin(pin,unhex(account.getString("salt")))
      if(!MessageDigest.isEqual(actual.toByteArray(),account.getString("hash").toByteArray())){
       synchronized(attempt){attempt.failures++;if(attempt.failures>=5){attempt.failures=0;attempt.blockedUntil=System.currentTimeMillis()+60_000L}}
       throw IllegalArgumentException("Incorrect staff PIN")
      }
      synchronized(attempt){attempt.failures=0;attempt.blockedUntil=0L}
      val key=hex(ByteArray(32).also{SecureRandom().nextBytes(it)})
      val role=account.getString("role");sessions[key]=StaffSession(account.getString("name"),role,System.currentTimeMillis()+30L*24L*60L*60L*1000L);persistSessions()
      json(Response.Status.OK,JSONObject().put("session",key).put("name",account.getString("name")).put("role",role))
     }
     session.uri=="/staff/enroll"&&session.method==Method.POST -> {
      require(firstSetup||(staffSession(session)?.role in setOf("OWNER","ADMIN")&&hasPermission(session,"manage_staff"))){"Staff management permission required"}
      val x=JSONObject(readBody(session));val name=x.optString("name").trim();val pin=x.optString("pin");val role=x.optString("role").uppercase()
      require(name.isNotBlank()&&name.length<=80&&pin.matches(Regex("[0-9]{4,8}"))){"Enter a staff name and a 4–8 digit PIN"}
      val actor=staffSession(session)?.name?:"Owner bootstrap"
      require(role in setOf("OWNER","ADMIN","COUNTER","WAITER","CHEF","DISHWASHER","RIDER")){"Invalid staff role"}
      synchronized(accountLock){
       val a=accounts()
       val firstAccount=a.length()==0
       if(firstAccount)require(role=="OWNER"){"First account must be OWNER"}
       else require(staffSession(session)?.role in setOf("OWNER","ADMIN")){"Only an owner or admin can create staff accounts"}
       if(!firstAccount&&staffSession(session)?.role=="ADMIN")require(role !in setOf("OWNER","ADMIN")){"Admins can create staff accounts but only the owner can create another admin or owner"}
       val key=name.lowercase(Locale.ROOT);require(!a.has(key)){"Staff account already exists"}
       val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}
       a.put(key,JSONObject().put("name",name).put("role",role).put("salt",hex(salt)).put("hash",hashPin(pin,salt)).put("active",true))
       check(authPrefs.edit().putString("accounts",a.toString()).commit()){"Could not save the new staff account"}
      }
      store.appendOrderAudit(0L,actor,if(firstSetup)"OWNER" else staffSession(session)?.role?:"OWNER","STAFF_ENROLLED","$name ($role)")
      json(Response.Status.OK,JSONObject().put("ok",true).put("name",name).put("role",role))
     }
     session.uri=="/staff/accounts/permissions"&&session.method==Method.GET -> {
      require(isOwner(session)){"Only the owner can inspect detailed staff permissions"}
      val result=JSONArray()
      synchronized(accountLock){
       val a=accounts();a.keys().forEach{key->val item=a.optJSONObject(key)?:return@forEach
        val role=item.optString("role")
        val stored=item.optJSONArray("permissions")
        val effective=if(stored==null)RolePermissions.defaultsFor(role) else (0 until stored.length()).mapNotNull{stored.optString(it).takeIf(String::isNotBlank)}.toSet()
        result.put(JSONObject().put("name",item.optString("name")).put("role",role).put("permissions",JSONArray(effective.sorted())).put("customized",stored!=null))
       }
      }
      json(Response.Status.OK,result)
     }
     session.uri=="/staff/accounts/permissions"&&session.method==Method.POST -> {
      require(isOwner(session)){"Only the owner can customize staff permissions"}
      val x=JSONObject(readBody(session));val name=x.optString("name").trim();val incoming=x.optJSONArray("permissions")?:throw IllegalArgumentException("Permissions list is required")
      require(name.isNotBlank()&&incoming.length()<=RolePermissions.all.size){"Invalid staff name or permission count"}
      val selected=(0 until incoming.length()).map{incoming.getString(it)}.toSet()
      require(selected.all{RolePermissions.isValid(it)}){"Unknown permission in request"}
      synchronized(accountLock){
       val a=accounts();val target=a.optJSONObject(name.lowercase(Locale.ROOT))?:throw IllegalArgumentException("Staff account not found")
       require(target.optString("role")!="OWNER"){"Owner permissions are fixed and cannot be restricted"}
       target.put("permissions",JSONArray(selected.sorted()))
       check(authPrefs.edit().putString("accounts",a.toString()).commit()){"Could not save staff permissions"}
      }
      store.appendOrderAudit(0L,staffSession(session)?.name?:"Owner","OWNER","STAFF_PERMISSIONS_UPDATED","$name: ${selected.sorted().joinToString()}")
      json(Response.Status.OK,JSONObject().put("ok",true).put("name",name).put("permissions",JSONArray(selected.sorted())))
     }
     session.uri=="/staff/accounts"&&session.method==Method.GET -> {
      val viewer=staffSession(session)?:throw IllegalArgumentException("Staff sign-in required")
      require(viewer.role in setOf("OWNER","ADMIN")&&hasPermission(session,"manage_staff")){"Staff management permission required"}
      val result=JSONArray()
      synchronized(accountLock){
       val a=accounts();a.keys().forEach{key->val item=a.optJSONObject(key)?:return@forEach
        if(viewer.role=="ADMIN"&&item.optString("role") in setOf("OWNER","ADMIN"))return@forEach
        result.put(JSONObject().put("name",item.optString("name")).put("role",item.optString("role")).put("active",item.optBoolean("active",true)))
       }
      }
      json(Response.Status.OK,result)
     }
     session.uri=="/staff/accounts/status"&&session.method==Method.POST -> {
      val manager=staffSession(session)?:throw IllegalArgumentException("Staff sign-in required")
      require(manager.role in setOf("OWNER","ADMIN")&&hasPermission(session,"manage_staff")){"Staff management permission required"}
      val actor=manager.name
      val x=JSONObject(readBody(session));val name=x.optString("name").trim();val active=x.getBoolean("active")
      require(name.isNotBlank()){"Staff name is required"}
      synchronized(accountLock){
       val a=accounts();val key=name.lowercase(Locale.ROOT);val target=a.optJSONObject(key)?:throw IllegalArgumentException("Staff account not found")
       if(manager.role=="ADMIN")require(target.optString("role") !in setOf("OWNER","ADMIN")){"Admins cannot change owner or admin accounts"}
       if(!active&&target.optString("role")=="OWNER"){
        var activeOwners=0
        val ownerKeys=a.keys()
        while(ownerKeys.hasNext()){
         val item=a.optJSONObject(ownerKeys.next())
         if(item!=null&&item.optString("role")=="OWNER"&&item.optBoolean("active",true))activeOwners++
        }
        require(activeOwners>1){"The last active owner cannot be disabled"}
       }
       target.put("active",active);check(authPrefs.edit().putString("accounts",a.toString()).commit()){"Could not save staff access changes"}
       if(!active){
        sessions.entries.removeIf{it.value.name.equals(name,true)}
        persistSessions()
       }
      }
      store.appendOrderAudit(0L,actor,manager.role,if(active)"STAFF_ENABLED" else "STAFF_DISABLED",name)
      json(Response.Status.OK,JSONObject().put("ok",true).put("name",name).put("active",active))
     }
     session.uri=="/staff/accounts/edit"&&session.method==Method.POST -> {
      val manager=staffSession(session)?:throw IllegalArgumentException("Staff sign-in required")
      require(manager.role in setOf("OWNER","ADMIN")&&hasPermission(session,"manage_staff")){"Staff management permission required"}
      val x=JSONObject(readBody(session));val oldName=x.optString("oldName").trim();val newName=x.optString("newName").trim();val role=x.optString("role").uppercase()
      require(oldName.isNotBlank()&&newName.isNotBlank()&&newName.length<=80){"Enter a staff name of 1–80 characters"}
      require(role in setOf("OWNER","ADMIN","COUNTER","WAITER","CHEF","DISHWASHER","RIDER")){"Invalid staff role"}
      require(!oldName.equals(manager.name,true)){"You cannot edit your own signed-in account"}
      synchronized(accountLock){
       val a=accounts();val oldKey=oldName.lowercase(Locale.ROOT);val target=a.optJSONObject(oldKey)?:throw IllegalArgumentException("Staff account not found")
       if(manager.role=="ADMIN"){
        require(target.optString("role") !in setOf("OWNER","ADMIN")){"Admins cannot edit owner or admin accounts"}
        require(role !in setOf("OWNER","ADMIN")){"Only the owner can assign owner or admin roles"}
       }
       val newKey=newName.lowercase(Locale.ROOT)
       require(newKey==oldKey||!a.has(newKey)){"Another staff account already uses that name"}
       if(target.optString("role")=="OWNER"&&role!="OWNER"){
        val activeOwners=a.keys().asSequence().mapNotNull{a.optJSONObject(it)}.count{it.optString("role")=="OWNER"&&it.optBoolean("active",true)}
        require(activeOwners>1){"The last active owner cannot be demoted"}
       }
       a.remove(oldKey)
       target.put("name",newName).put("role",role)
       a.put(newKey,target)
       check(authPrefs.edit().putString("accounts",a.toString()).commit()){"Could not save staff account changes"}
       sessions.entries.removeIf{it.value.name.equals(oldName,true)}
       persistSessions()
      }
      store.appendOrderAudit(0L,manager.name,manager.role,"STAFF_ACCOUNT_EDITED","$oldName → $newName ($role)")
      json(Response.Status.OK,JSONObject().put("ok",true).put("name",newName).put("role",role).put("sessionsRevoked",true))
     }
     session.uri=="/staff/accounts/pin"&&session.method==Method.POST -> {
      val manager=staffSession(session)?:throw IllegalArgumentException("Staff sign-in required")
      require(manager.role in setOf("OWNER","ADMIN")&&hasPermission(session,"manage_staff")){"Staff management permission required"}
      val actor=manager.name
      val x=JSONObject(readBody(session));val name=x.optString("name").trim();val pin=x.optString("pin")
      require(name.isNotBlank()&&pin.matches(Regex("[0-9]{4,8}"))){"Enter a staff name and a 4–8 digit PIN"}
      synchronized(accountLock){
       val a=accounts();val key=name.lowercase(Locale.ROOT);val target=a.optJSONObject(key)?:throw IllegalArgumentException("Staff account not found")
       if(manager.role=="ADMIN")require(target.optString("role") !in setOf("OWNER","ADMIN")){"Admins cannot reset owner or admin PINs"}
       val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}
       target.put("salt",hex(salt)).put("hash",hashPin(pin,salt))
       check(authPrefs.edit().putString("accounts",a.toString()).commit()){"Could not save staff PIN reset"}
       sessions.entries.removeIf{it.value.name.equals(name,true)}
       persistSessions()
      }
      store.appendOrderAudit(0L,actor,manager.role,"STAFF_PIN_RESET",name)
      json(Response.Status.OK,JSONObject().put("ok",true).put("name",name).put("sessionsRevoked",true))
     }
     session.uri=="/staff/logout"&&session.method==Method.POST -> {
      val key=session.headers["x-pos-session"].orEmpty()
      require(key.isNotBlank()&&sessions.remove(key)!=null){"Staff session is already signed out"}
      persistSessions()
      json(Response.Status.OK,JSONObject().put("ok",true))
     }
     session.uri=="/messages"&&session.method==Method.GET -> {
      require(hasPermission(session,"view_messages")){"Your staff account cannot view communication"}
      val saved=runCatching{JSONArray(messagePrefs.getString("messages","[]")?:"[]")}.getOrDefault(JSONArray())
      json(Response.Status.OK,saved)
     }
     session.uri=="/messages"&&session.method==Method.POST -> {
      val authenticated=staffSession(session)?:throw IllegalArgumentException("Staff sign-in required")
      require(hasPermission(session,"send_messages")){"Your staff account cannot send messages"}
      val x=JSONObject(readBody(session));val id=x.optString("id");val message=x.optString("message").trim()
      require(id.matches(Regex("[A-Za-z0-9_-]{8,80}"))&&message.isNotBlank()&&message.length<=1000){"Message must contain 1–1000 characters"}
      synchronized(messageLock){
       val saved=runCatching{JSONArray(messagePrefs.getString("messages","[]")?:"[]")}.getOrDefault(JSONArray())
       if((0 until saved.length()).none{saved.optJSONObject(it)?.optString("id")==id}){
        saved.put(JSONObject().put("id",id).put("author",authenticated.name).put("role",authenticated.role).put("message",message).put("time",System.currentTimeMillis()))
        val trimmed=JSONArray();for(i in (saved.length()-500).coerceAtLeast(0) until saved.length())trimmed.put(saved.getJSONObject(i))
        messagePrefs.edit().putString("messages",trimmed.toString()).apply()
       }
      }
      json(Response.Status.OK,JSONObject().put("ok",true))
     }
     session.uri=="/orders"&&session.method==Method.GET -> {
      require(hasPermission(session,"view_orders")){"Your staff account cannot view live orders"}
      json(Response.Status.OK,JSONArray().apply{store.pending().forEach{put(LocalNetworkSync.orderJson(it))}})
     }
     session.uri=="/shifts" -> {
      if(session.method==Method.GET) {
       require(hasPermission(session,"view_shifts")){"Your staff account cannot view shift records"}
       json(Response.Status.OK,store.shiftsJson())
      } else {
       require(hasPermission(session,"manage_shifts")){"Your staff account cannot manage shift records"}
       require(session.method==Method.POST){"Unsupported shift request"}
       val incoming=JSONArray(readBody(session));require(incoming.length()<=2000){"Too many shift records"};store.mergeShifts(incoming);json(Response.Status.OK,JSONObject().put("ok",true).put("count",store.shiftsJson().length()))
      }
     }
     session.uri=="/orders/audit"&&session.method==Method.GET -> {
      require(hasPermission(session,"view_audit")){"Your staff account cannot view order activity"}
      json(Response.Status.OK,store.orderAudit())
     }
     session.uri=="/orders/completed"&&session.method==Method.GET -> {
      require(hasPermission(session,"view_orders")){"Your staff account cannot view completed orders"}
      json(Response.Status.OK,store.completedOrderIdsJson())
     }
     session.uri=="/orders"&&session.method==Method.POST -> {
      require(hasPermission(session,"create_order")){"Your staff account cannot submit orders"}
      val body=readBody(session);val x=JSONObject(body);val a=x.getJSONArray("items")
      require(a.length() in 1..100) { "An order must contain 1 to 100 item lines" }
      val items=(0 until a.length()).map{val q=a.getJSONObject(it);val name=q.getString("name").trim();val qty=q.getInt("qty");val price=q.getDouble("price");require(name.isNotBlank()&&name.length<=120){"Invalid item name"};require(qty in 1..999){"Invalid item quantity"};require(price.isFinite()&&price>=0.0){"Invalid item price"};SaleItem(name,qty,price,q.optString("kitchen","FAST_FOOD").also{station->require(station in setOf("FAST_FOOD","GRILLED","MILK_SHAKES","TEA_COFFEE")){"Invalid kitchen station"}})}
      val id=x.getLong("id");val time=x.getLong("time")
      require(id>0L&&time>0L&&time<=System.currentTimeMillis()+300_000L){"Invalid order identity or timestamp"}
      val orderType=x.optString("orderType","DINE_IN").uppercase()
      require(orderType in setOf("DINE_IN","TAKEAWAY","DELIVERY")){"Invalid order type"}
      val incoming=PendingSale(id,items,time,x.optString("customerName").take(120),x.optString("customerPhone").take(40),orderType,x.optString("customerAddress").take(300),x.optString("shiftPerson").take(80),x.optString("tableName").take(40),x.optString("waiterName").take(80),x.optString("counterName").take(80),x.optString("chefName").take(80),"NEW",x.optString("specialInstructions").take(1000),x.optLong("updatedAt",time).coerceAtLeast(time),0L)
      if(id in store.completedOrderIds()) {
       json(Response.Status.OK,JSONObject().put("ok",true).put("id",id).put("alreadyCompleted",true))
      } else {
       val existing=store.pending().firstOrNull{it.id==id}
       if(existing!=null) {
        val samePayload=existing.time==incoming.time&&existing.items==incoming.items&&
         existing.customerName==incoming.customerName&&existing.customerPhone==incoming.customerPhone&&
         existing.orderType==incoming.orderType&&existing.customerAddress==incoming.customerAddress&&
         existing.specialInstructions==incoming.specialInstructions
        if(!samePayload)json(Response.Status.CONFLICT,JSONObject().put("error","Order ID already exists with different contents; refusing to overwrite the original order"))
        else json(Response.Status.OK,JSONObject().put("ok",true).put("id",id).put("duplicate",true))
       } else {
        store.upsertRemoteOrder(incoming)
        store.appendOrderAudit(incoming.id,staffSession(session)?.name?:incoming.waiterName,staffSession(session)?.role?:"WAITER","ORDER_SENT","Order received by owner device")
        json(Response.Status.OK,JSONObject().put("ok",true).put("id",incoming.id))
       }
      }
     }
     session.uri=="/orders/kitchen"&&session.method==Method.POST -> {
      val x=JSONObject(readBody(session));val authenticated=staffSession(session)?:throw IllegalArgumentException("Staff sign-in required")
      require(hasPermission(session,"update_kitchen")){"Your staff account cannot update kitchen progress"}
      val ok=store.updateKitchenStation(x.getLong("id"),x.getString("station").uppercase(),x.getString("status").uppercase(),authenticated.name,authenticated.role)
      if(!ok)json(Response.Status.CONFLICT,JSONObject().put("error","Kitchen update rejected: check station, role and workflow"))else json(Response.Status.OK,JSONObject().put("ok",true))
     }
     session.uri=="/orders/status"&&session.method==Method.POST -> {
      val x=JSONObject(readBody(session));val authenticated=staffSession(session)?:throw IllegalArgumentException("Staff sign-in required")
      require(hasPermission(session,"update_order_status")){"Your staff account cannot update order status"}
      val role=authenticated.role;val actor=authenticated.name
      val id=x.getLong("id");val status=x.getString("status").uppercase()
      require(id>0L&&status in setOf("ACCEPTED","PREPARING","READY","SERVED","OUT_FOR_DELIVERY","DELIVERED","BILLED")){"Invalid order update"}
      val ok=store.updateOrderWorkflow(id,status,actor,role,x.optString("note").take(1000))
      if(!ok)json(Response.Status.CONFLICT,JSONObject().put("error","Order is missing or the requested workflow transition is not allowed"))else json(Response.Status.OK,JSONObject().put("ok",true))
     }
     session.uri=="/sync"&&session.method==Method.POST -> {
      require(isOwner(session)){"Only the owner can synchronize the full backup"}
      newFixedLengthResponse(Response.Status.OK,"application/json; charset=utf-8",store.mergeBackup(readBody(session,8_000_000L)))
     }
     else -> json(Response.Status.NOT_FOUND,JSONObject().put("error","Not found"))
    }
   }catch(e:Exception){json(Response.Status.BAD_REQUEST,JSONObject().put("error",e.message?:"Request failed"))}
  }
  private fun readBody(s:IHTTPSession,maxBytes:Long=1_000_000L):String{
   val length=s.headers["content-length"]?.toLongOrNull()?:0L
   require(length in 1..maxBytes){"Request body exceeds the allowed size"}
   val files=HashMap<String,String>();s.parseBody(files)
   return (files["postData"]?:throw IllegalArgumentException("Empty request")).also{require(it.toByteArray(Charsets.UTF_8).size<=maxBytes){"Request is too large"}}
  }
  private fun json(status:Response.Status,value:JSONObject)=newFixedLengthResponse(status,"application/json; charset=utf-8",value.toString())
  private fun json(status:Response.Status,value:JSONArray)=newFixedLengthResponse(status,"application/json; charset=utf-8",value.toString())
 }
}
