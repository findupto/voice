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
  return runCatching{PosLanServer(store,token(context)).also{it.start(NanoHTTPD.SOCKET_READ_TIMEOUT,false);server=it};true}.getOrDefault(false)
 }
 @Synchronized fun stop(){server?.stop();server=null}
 fun isRunning()=server?.isAlive==true
 fun ipAddress():String=runCatching{Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence().filter{it.isUp&&!it.isLoopback}.flatMap{Collections.list(it.inetAddresses).asSequence()}.filterIsInstance<Inet4Address>().firstOrNull{it.isSiteLocalAddress}?.hostAddress?:"IP unavailable"}.getOrDefault("IP unavailable")
 private fun endpoint(ip:String,path:String):String {
  val host=ip.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")
  val address=host.substringBefore(":")
  require(address.split(".").size==4&&address.split(".").all{it.toIntOrNull() in 0..255}){"Enter a valid owner IPv4 address."}
  val port=host.substringAfter(":",PORT.toString()).toIntOrNull()
  require(port!=null&&port in 1..65535){"Enter a valid local port."}
  return "http://$address:$port$path
 }
 private fun request(ip:String,token:String,path:String,method:String="GET",body:String?=null):String {
  val c=URL(endpoint(ip,path)).openConnection() as HttpURLConnection
  try {
   c.connectTimeout=2500;c.readTimeout=5000;c.requestMethod=method
   c.setRequestProperty("X-POS-Token",token.trim())
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
   val lines=(0 until items.length()).map{val q=items.getJSONObject(it);SaleItem(q.getString("name"),q.getInt("qty"),q.getDouble("price"))}
   val order=PendingSale(x.getLong("id"),lines,x.getLong("time"),x.optString("customerName"),x.optString("customerPhone"),x.optString("orderType","DINE_IN"),x.optString("customerAddress"),x.optString("shiftPerson"),x.optString("tableName"),x.optString("waiterName"),x.optString("counterName"),x.optString("chefName"),x.optString("status","NEW"),x.optString("specialInstructions"),x.optLong("updatedAt",x.getLong("time")),x.optLong("servedAt",0L))
   store.upsertRemoteOrder(order);result.add(order)
  }
  return result
 }
 fun pushOrder(ip:String,token:String,order:PendingSale):String=request(ip,token,"/orders","POST",orderJson(order).toString())
 fun pushStatus(ip:String,token:String,id:Long,status:String,actor:String,role:String,note:String=""):String=request(ip,token,"/orders/status","POST",JSONObject().put("id",id).put("status",status).put("actor",actor).put("role",role).put("note",note).toString())
 private fun orderJson(s:PendingSale)=JSONObject().apply{
  put("id",s.id);put("time",s.time);put("customerName",s.customerName);put("customerPhone",s.customerPhone);put("orderType",s.orderType);put("customerAddress",s.customerAddress);put("shiftPerson",s.shiftPerson);put("tableName",s.tableName);put("waiterName",s.waiterName);put("counterName",s.counterName);put("chefName",s.chefName);put("status",s.status);put("specialInstructions",s.specialInstructions);put("updatedAt",s.updatedAt);put("servedAt",s.servedAt)
  put("items",JSONArray().apply{s.items.forEach{put(JSONObject().put("name",it.name).put("qty",it.qty).put("price",it.price))}})
 }
 private class PosLanServer(private val store:Store,private val secret:String):NanoHTTPD("0.0.0.0",PORT){
  override fun serve(session:IHTTPSession):Response {
   if(session.uri=="/health"&&session.method==Method.GET)return json(Response.Status.OK,JSONObject().put("service","Voice POS live orders").put("version",2))
   val supplied=session.headers["x-pos-token"].orEmpty()
   if(!MessageDigest.isEqual(supplied.toByteArray(Charsets.UTF_8),secret.toByteArray(Charsets.UTF_8)))
    return json(Response.Status.UNAUTHORIZED,JSONObject().put("error","Invalid owner access code"))
   val declaredLength=session.headers["content-length"]?.toLongOrNull()?:0L
   if(declaredLength>8_000_000L)return json(Response.Status.BAD_REQUEST,JSONObject().put("error","Request is too large"))
   return try {
    when {
     session.uri=="/orders"&&session.method==Method.GET -> json(Response.Status.OK,JSONArray().apply{store.pending().forEach{put(LocalNetworkSync.orderJson(it))}})
     session.uri=="/orders"&&session.method==Method.POST -> {
      val body=readBody(session);val x=JSONObject(body);val a=x.getJSONArray("items")
      require(a.length() in 1..100) { "An order must contain 1 to 100 item lines" }
      val items=(0 until a.length()).map{val q=a.getJSONObject(it);val name=q.getString("name").trim();val qty=q.getInt("qty");val price=q.getDouble("price");require(name.isNotBlank()&&name.length<=120){"Invalid item name"};require(qty in 1..999){"Invalid item quantity"};require(price.isFinite()&&price>=0.0){"Invalid item price"};SaleItem(name,qty,price)}
      val id=x.getLong("id");val time=x.getLong("time")
      require(id>0L&&time>0L&&time<=System.currentTimeMillis()+300_000L){"Invalid order identity or timestamp"}
      val orderType=x.optString("orderType","DINE_IN").uppercase()
      require(orderType in setOf("DINE_IN","TAKEAWAY","DELIVERY")){"Invalid order type"}
      val incoming=PendingSale(id,items,time,x.optString("customerName").take(120),x.optString("customerPhone").take(40),orderType,x.optString("customerAddress").take(300),x.optString("shiftPerson").take(80),x.optString("tableName").take(40),x.optString("waiterName").take(80),x.optString("counterName").take(80),x.optString("chefName").take(80),"NEW",x.optString("specialInstructions").take(1000),x.optLong("updatedAt",time).coerceAtLeast(time),0L)
      val existing=store.pending().firstOrNull{it.id==id}
      val order=if(existing==null)incoming else incoming.copy(status=existing.status,waiterName=existing.waiterName,chefName=existing.chefName,counterName=existing.counterName,servedAt=existing.servedAt)
      store.upsertRemoteOrder(order)
      if(existing==null)store.appendOrderAudit(order.id,order.waiterName.ifBlank{order.shiftPerson},"WAITER","ORDER_SENT","Order received by owner device")
      json(Response.Status.OK,JSONObject().put("ok",true).put("id",order.id))
     }
     session.uri=="/orders/status"&&session.method==Method.POST -> {
      val x=JSONObject(readBody(session));val role=x.optString("role").uppercase();val actor=x.optString("actor").trim()
      require(role in setOf("OWNER","COUNTER","WAITER","CHEF")){"Invalid staff role"}
      require(actor.isNotBlank()&&actor.length<=80){"Staff name is required"}
      val id=x.getLong("id");val status=x.getString("status").uppercase()
      require(id>0L&&status in setOf("ACCEPTED","PREPARING","READY","SERVED")){"Invalid order update"}
      val ok=store.updateOrderWorkflow(id,status,actor,role,x.optString("note").take(1000))
      if(!ok)json(Response.Status.CONFLICT,JSONObject().put("error","Order is missing or the requested workflow transition is not allowed"))else json(Response.Status.OK,JSONObject().put("ok",true))
     }
     session.uri=="/sync"&&session.method==Method.POST -> newFixedLengthResponse(Response.Status.OK,"application/json; charset=utf-8",store.mergeBackup(readBody(session,8_000_000L)))
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
