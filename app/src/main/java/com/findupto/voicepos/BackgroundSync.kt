package com.findupto.voicepos

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Best-effort background reconciliation. WorkManager retries when a network is available. */
class PosBackgroundSyncWorker(context:Context,params:WorkerParameters):Worker(context,params){
 override fun doWork():Result {
  val prefs=applicationContext.getSharedPreferences("voice_local_network",Context.MODE_PRIVATE)
  val ip=prefs.getString("owner_ip","").orEmpty()
  val session=prefs.getString("staff_session","").orEmpty()
  if(ip.isBlank()||session.isBlank())return Result.success()
  return try{
   val store=Store(applicationContext)
   LocalNetworkSync.retryOfflineOrders(ip,session,applicationContext)
   val queued=store.offlineDeliveryUpdates()
   var index=0
   while(index<queued.length()){
    val item=queued.getJSONObject(index)
    LocalNetworkSync.pushStatus(ip,session,item.getLong("id"),item.getString("status"),item.optString("actor"),"RIDER")
    store.removeOfflineDeliveryUpdate(0)
    index++
   }
   LocalNetworkSync.pullOrders(ip,session,store)
   Result.success()
  }catch(_:Exception){Result.retry()}
 }
}

object PosBackgroundSync {
 fun schedule(context:Context){
  val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
  val request=PeriodicWorkRequestBuilder<PosBackgroundSyncWorker>(15,TimeUnit.MINUTES)
   .setConstraints(constraints)
   .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
   .build()
  WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork("voice-pos-background-sync",ExistingPeriodicWorkPolicy.KEEP,request)
 }
}
