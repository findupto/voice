package com.findupto.voicepos

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
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
  require(host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}(:[0-9]{1,5})?"))){"Enter the owner's local IPv4 address."}
  return "http://"+(if(host.contains(":"))host else "$host:$PORT")+path
 }
 private fun request(ip:String,token:String,path:String,method:String="GET",body:String?=null):String {
  val c=URL(endpoint(ip,path)).openConnection() as HttpURLConnection
  c.connectTimeout=3500;c.readTimeout=7000;c.requestMethod=method
  c.setRequestProperty("X-POS-Token",token.trim())
  if(body!=null){c.doOutput=true;c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))}}
  val status=c.responseCode
  val response=(if(status in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
  c.disconnect()
  if(status !in 200..299)throw IllegalStateException(JSONObject(response).optString("error","Server rejected request ($status)"))
  return response
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
   if(session.headers["x-pos-token"]!=secret)return json(Response.Status.UNAUTHORIZED,JSONObject().put("error","Invalid owner access code"))
   return try {
    when {
     session.uri=="/orders"&&session.method==Method.GET -> json(Response.Status.OK,JSONArray().apply{store.pending().forEach{put(orderJson(it))}})
     session.uri=="/orders"&&session.method==Method.POST -> {
      val body=readBody(session);val x=JSONObject(body);val a=x.getJSONArray("items")
      val items=(0 until a.length()).map{val q=a.getJSONObject(it);SaleItem(q.getString("name"),q.getInt("qty"),q.getDouble("price"))}
      val order=PendingSale(x.getLong("id"),items,x.getLong("time"),x.optString("customerName"),x.optString("customerPhone"),x.optString("orderType","DINE_IN"),x.optString("customerAddress"),x.optString("shiftPerson"),x.optString("tableName"),x.optString("waiterName"),x.optString("counterName"),x.optString("chefName"),x.optString("status","NEW"),x.optString("specialInstructions"),x.optLong("updatedAt",x.getLong("time")),x.optLong("servedAt",0L))
      store.upsertRemoteOrder(order);store.appendOrderAudit(order.id,order.waiterName.ifBlank{order.shiftPerson},"WAITER","ORDER_SENT","Order received by owner device")
      json(Response.Status.OK,JSONObject().put("ok",true).put("id",order.id))
     }
     session.uri=="/orders/status"&&session.method==Method.POST -> {
      val x=JSONObject(readBody(session));val ok=store.updateOrderWorkflow(x.getLong("id"),x.getString("status"),x.optString("actor"),x.optString("role"),x.optString("note"))
      if(!ok)json(Response.Status.NOT_FOUND,JSONObject().put("error","Order not found"))else json(Response.Status.OK,JSONObject().put("ok",true))
     }
     session.uri=="/sync"&&session.method==Method.POST -> json(Response.Status.OK,JSONObject().put("backup",store.mergeBackup(readBody(session))))
     else -> json(Response.Status.NOT_FOUND,JSONObject().put("error","Not found"))
    }
   }catch(e:Exception){json(Response.Status.BAD_REQUEST,JSONObject().put("error",e.message?:"Request failed"))}
  }
  private fun readBody(s:IHTTPSession):String{val files=HashMap<String,String>();s.parseBody(files);return files["postData"]?:throw IllegalArgumentException("Empty request")}
  private fun json(status:Response.Status,value:JSONObject)=newFixedLengthResponse(status,"application/json; charset=utf-8",value.toString())
  private fun json(status:Response.Status,value:JSONArray)=newFixedLengthResponse(status,"application/json; charset=utf-8",value.toString())
 }
}
