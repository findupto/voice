package com.findupto.voicepos

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/** Creates a validated, rotating local business backup on the primary Admin/Owner device. */
class PosAutoBackupWorker(context:Context,params:WorkerParameters):Worker(context,params){
 override fun doWork():Result = try {
  val store=Store(applicationContext)
  if(store.staffRole() !in setOf("ADMIN","OWNER")) return Result.success()
  val raw=store.exportBackup()
  val parsed=JSONObject(raw)
  require(parsed.optString("format").startsWith("VOICE_POS_BACKUP_V")) { "Backup format validation failed" }
  val dir=File(applicationContext.filesDir,"automatic_backups")
  if(!dir.exists()&&!dir.mkdirs()) error("Could not create backup directory")
  val stamp=SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(Date())
  val target=File(dir,"voice-pos-$stamp.json")
  val temp=File(dir,".$stamp.tmp")
  FileOutputStream(temp).use { it.write(raw.toByteArray(Charsets.UTF_8)); it.fd.sync() }
  if(!temp.renameTo(target)){temp.delete();error("Could not finalize backup")}
  dir.listFiles()?.filter { it.isFile && it.name.startsWith("voice-pos-") && it.name.endsWith(".json") }
   ?.sortedByDescending { it.lastModified() }?.drop(14)?.forEach { it.delete() }
  Result.success()
 } catch (_:Exception) { Result.retry() }
}

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
   // Remove only the head after the server confirms it; do not skip entries as the queue shrinks.
   while(true){
    val queued=store.offlineDeliveryUpdates()
    if(queued.length()==0)break
    val item=queued.getJSONObject(0)
    LocalNetworkSync.pushStatus(ip,session,item.getLong("id"),item.getString("status"),item.optString("actor"),"RIDER")
    store.removeOfflineDeliveryUpdate(0)
   }
   LocalNetworkSync.pullOrders(ip,session,store)
   Result.success()
  }catch(_:Exception){Result.retry()}
 }
}

object PosBackgroundSync {
 private fun constraints()=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
 fun enqueueNow(context:Context){
  val request=OneTimeWorkRequestBuilder<PosBackgroundSyncWorker>()
   .setConstraints(constraints())
   .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
   .build()
  WorkManager.getInstance(context.applicationContext).enqueueUniqueWork("voice-pos-sync-now",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
 }
 fun schedule(context:Context){
  val request=PeriodicWorkRequestBuilder<PosBackgroundSyncWorker>(15,TimeUnit.MINUTES)
   .setConstraints(constraints())
   .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
   .build()
  WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork("voice-pos-background-sync",ExistingPeriodicWorkPolicy.KEEP,request)
  val backup=PeriodicWorkRequestBuilder<PosAutoBackupWorker>(24,TimeUnit.HOURS)
   .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
   .build()
  WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork("voice-pos-auto-backup",ExistingPeriodicWorkPolicy.KEEP,backup)
  val firstBackup=OneTimeWorkRequestBuilder<PosAutoBackupWorker>().build()
  WorkManager.getInstance(context.applicationContext).enqueueUniqueWork("voice-pos-first-backup",ExistingWorkPolicy.KEEP,firstBackup)
 }
}
