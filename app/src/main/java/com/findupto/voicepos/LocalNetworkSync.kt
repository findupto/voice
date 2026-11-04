package com.findupto.voicepos

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections

object LocalNetworkSync {
 private const val PORT=8765
 private const val PREFS="voice_local_network"
 @Volatile private var server:PosLanServer?=null
 fun token(context:Context):String {
  val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
  return p.getString("access_token",null)?:ByteArray(24).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it)}.also{p.edit().putString("access_token",it).apply()}
 }
 @Synchronized fun start(context:Context,store:Store):Boolean {
  if(server?.isAlive==true)return true
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
  require(address.split(".").size==4&&address.split(".").all{it.toIntOrNull() in 0..255}){"Enter a valid owner IPv4 address."}
  val port=host.substringAfter(":",PORT.toString()).toIntOrNull()
  require(port!=null&&port in 1..65535){"Enter a valid local port."}
  return "http://$address:$port$path"
 }
 private fun request(ip:String,token:String,path:String,method:String="GET",body:String?=null,ownerCode:Boolean=false):String {
  val c=URL(endpoint(ip,path)).openConnection() as HttpURLConnection
  try {
   c.connectTimeout=7000;c.readTimeout=10000;c.requestMethod=method
   c.setRequestProperty(if(ownerCode) "X-POS-Token" else "X-POS-Session",token.trim())
   c.setRequestProperty("Accept","application/json")
   if(body!=null){val bytes=body.toByteArray(Charsets.UTF_8);c.doOutput=true;c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setFixedLengthStreamingMode(bytes.size);c.outputStream.use{it.write(bytes)}}
   val status=c.responseCode
   val stream=if(status in 200..299)c.inputStream else c.errorStream
   val response=stream?.bufferedReader()?.use{it.readText()}?.take(8_000_000).orEmpty()
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
   val order=PendingSale(x.getLong("id"),lines,x.getLong("time"),x.optString("customerName"),x.optString("customerPhone"),x.optString("orderType","DINE_IN"),x.optString("customerAddress"),x.optString("shiftPerson"),x.optString("tableName"),x.optString("waiterName"),x.optString("counterName"),x.optString("chefName"),x.optString("status","NEW"),x.optString("specialInstructions"),x.optLong("updatedAt",x.getLong("time")),x.optLong("servedAt",0L))
   store.upsertRemoteOrder(order);result.add(order)
  }
  return result
 }
 fun pushOrder(ip:String,token:String,order:PendingSale):String=request(ip,token,"/orders","POST",orderJson(order).toString())
 fun queueOfflineOrder(context:Context,order:PendingSale){
  val prefs=context.getSharedPreferences("voice_local_network",Context.MODE_PRIVATE)
  val queue=runCatching{JSONArray(prefs.getString("offline_order_queue","[]")?:"[]")}.getOrDefault(JSONArray())
  if((0 until queue.length()).none{queue.optJSONObject(it)?.optLong("id")==order.id})queue.put(orderJson(order))
  prefs.edit().putString("offline_order_queue",queue.toString()).apply()
 }
 fun retryOfflineOrders(ip:String,token:String,context:Context):Int{
  val prefs=context.getSharedPreferences("voice_local_network",Context.MODE_PRIVATE)
  val queue=runCatching{JSONArray(prefs.getString("offline_order_queue","[]")?:"[]")}.getOrDefault(JSONArray())
  var sent=0
  val remaining=JSONArray()
  for(i in 0 until queue.length()){
   val order=queue.optJSONObject(i)?:continue
   try{request(ip,token,"/orders","POST",order.toString());sent++}
   catch(_:Exception){remaining.put(order)}
  }
  prefs.edit().putString("offline_order_queue",remaining.toString()).apply()
  return sent
 }
 fun pushStatus(ip:String,token:String,id:Long,status:String,actor:String,role:String,note:String=""):String=request(ip,token,"/orders/status","POST",JSONObject().put("id",id).put("status",status).put("note",note).toString())
 fun loginStaff(ip:String,name:String,pin:String):JSONObject=JSONObject(request(ip,"","/staff/login","POST",JSONObject().put("name",name).put("pin",pin).toString(),true))
 fun enrollStaff(ip:String,ownerSession:String,name:String,pin:String,role:String):String=request(ip,ownerSession,"/staff/enroll","POST",JSONObject().put("name",name).put("pin",pin).put("role",role).toString())
 fun setupOwner(ip:String,ownerCode:String,name:String,pin:String):String=request(ip,ownerCode,"/staff/enroll","POST",JSONObject().put("name",name).put("pin",pin).put("role","OWNER").toString(),true)
 private fun orderJson(s:PendingSale)=JSONObject().apply{
  put("id",s.id);put("time",s.time);put("customerName",s.customerName);put("customerPhone",s.customerPhone);put("orderType",s.orderType);put("customerAddress",s.customerAddress);put("shiftPerson",s.shiftPerson);put("tableName",s.tableName);put("waiterName",s.waiterName);put("counterName",s.counterName);put("chefName",s.chefName);put("status",s.status);put("specialInstructions",s.specialInstructions);put("updatedAt",s.updatedAt);put("servedAt",s.servedAt)
  put("items",JSONArray().apply{s.items.forEach{put(JSONObject().put("name",it.name).put("qty",it.qty).put("price",it.price).put("kitchen",it.kitchen))}})
 }
 private data class StaffSession(val name:String,val role:String,val expiresAt:Long)
 private class PosLanServer(private val context:Context,private val store:Store,private val secret:String):NanoHTTPD("0.0.0.0",PORT){
  private val sessions=ConcurrentHashMap<String,StaffSession>()
  private data class LoginAttempt(var failures:Int=0,var blockedUntil:Long=0L)
  private val loginAttempts=ConcurrentHashMap<String,LoginAttempt>()
  private val authPrefs=context.getSharedPreferences("voice_staff_auth",Context.MODE_PRIVATE)
  private fun accounts()=runCatching{JSONObject(authPrefs.getString("accounts","{}")?: "{}")}.getOrDefault(JSONObject())
  private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it)}
  private fun unhex(value:String)=value.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
  private fun hashPin(pin:String,salt:ByteArray):String {
   val spec=PBEKeySpec(pin.toCharArray(),salt,120000,256)
   return try{hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)}finally{spec.clearPassword()}
  }
  private fun staffSession(s:IHTTPSession):StaffSession? {
   val key=s.headers["x-pos-session"].orEmpty();val found=sessions[key]?:return null
   if(found.expiresAt<System.currentTimeMillis()){sessions.remove(key);return null};return found
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
      val attemptKey=name.lowercase()
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
      val role=account.getString("role");sessions[key]=StaffSession(account.getString("name"),role,System.currentTimeMillis()+43200000L)
      json(Response.Status.OK,JSONObject().put("session",key).put("name",account.getString("name")).put("role",role))
     }
     session.uri=="/staff/enroll"&&session.method==Method.POST -> {
      require(firstSetup||isOwner(session)){"Only the owner can manage staff accounts"}
      val x=JSONObject(readBody(session));val name=x.optString("name").trim();val pin=x.optString("pin");val role=x.optString("role").uppercase()
      require(name.isNotBlank()&&name.length<=80&&pin.matches(Regex("[0-9]{4,8}"))){"Enter a staff name and a 4–8 digit PIN"}
      require(role in setOf("OWNER","ADMIN","COUNTER","WAITER","CHEF","DISHWASHER","RIDER")){"Invalid staff role"}
      if(firstSetup)require(role=="OWNER"){"First account must be OWNER"}
      val a=accounts();val key=name.lowercase();require(!a.has(key)){"Staff account already exists"}
      val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}
      a.put(key,JSONObject().put("name",name).put("role",role).put("salt",hex(salt)).put("hash",hashPin(pin,salt)).put("active",true))
      authPrefs.edit().putString("accounts",a.toString()).apply()
      json(Response.Status.OK,JSONObject().put("ok",true).put("name",name).put("role",role))
     }
     session.uri=="/orders"&&session.method==Method.GET -> {
      require(staffSession(session)?.role in setOf("OWNER","ADMIN","COUNTER","WAITER","CHEF","DISHWASHER","RIDER")){"Your staff role cannot view live orders"}
      json(Response.Status.OK,JSONArray().apply{store.pending().forEach{put(LocalNetworkSync.orderJson(it))}})
     }
     session.uri=="/orders"&&session.method==Method.POST -> {
      require(staffSession(session)?.role in setOf("WAITER","OWNER","ADMIN")){"Only a waiter can submit orders"}
      val body=readBody(session);val x=JSONObject(body);val a=x.getJSONArray("items")
      require(a.length() in 1..100) { "An order must contain 1 to 100 item lines" }
      val items=(0 until a.length()).map{val q=a.getJSONObject(it);val name=q.getString("name").trim();val qty=q.getInt("qty");val price=q.getDouble("price");require(name.isNotBlank()&&name.length<=120){"Invalid item name"};require(qty in 1..999){"Invalid item quantity"};require(price.isFinite()&&price>=0.0){"Invalid item price"};SaleItem(name,qty,price,q.optString("kitchen","FAST_FOOD").also{station->require(station in setOf("FAST_FOOD","GRILLED","MILK_SHAKES","TEA_COFFEE")){"Invalid kitchen station"}})}
      val id=x.getLong("id");val time=x.getLong("time")
      require(id>0L&&time>0L&&time<=System.currentTimeMillis()+300_000L){"Invalid order identity or timestamp"}
      val orderType=x.optString("orderType","DINE_IN").uppercase()
      require(orderType in setOf("DINE_IN","TAKEAWAY","DELIVERY")){"Invalid order type"}
      val incoming=PendingSale(id,items,time,x.optString("customerName").take(120),x.optString("customerPhone").take(40),orderType,x.optString("customerAddress").take(300),x.optString("shiftPerson").take(80),x.optString("tableName").take(40),x.optString("waiterName").take(80),x.optString("counterName").take(80),x.optString("chefName").take(80),"NEW",x.optString("specialInstructions").take(1000),x.optLong("updatedAt",time).coerceAtLeast(time),0L)
      val existing=store.pending().firstOrNull{it.id==id}
      val order=if(existing==null)incoming else incoming.copy(status=existing.status,waiterName=existing.waiterName,chefName=existing.chefName,counterName=existing.counterName,servedAt=existing.servedAt)
      store.upsertRemoteOrder(order)
      if(existing==null)store.appendOrderAudit(order.id,staffSession(session)?.name?:order.waiterName,staffSession(session)?.role?:"WAITER","ORDER_SENT","Order received by owner device")
      json(Response.Status.OK,JSONObject().put("ok",true).put("id",order.id))
     }
     session.uri=="/orders/status"&&session.method==Method.POST -> {
      val x=JSONObject(readBody(session));val authenticated=staffSession(session)?:throw IllegalArgumentException("Staff sign-in required")
      val role=authenticated.role;val actor=authenticated.name
      val id=x.getLong("id");val status=x.getString("status").uppercase()
      require(id>0L&&status in setOf("ACCEPTED","PREPARING","READY","SERVED","OUT_FOR_DELIVERY","DELIVERED")){"Invalid order update"}
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
