package com.findupto.voicepos

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject

@androidx.camera.core.ExperimentalGetImage
class OrderAlertService : Service() {
    companion object {
        private const val CHANNEL = "pos_order_updates_v2"
        private const val SERVICE_CHANNEL = "pos_service_status_v1"
        private const val SERVICE_ID = 8100
        private const val ACTION_ACK = "com.findupto.voicepos.ACK_ORDER_ALERT"
    }
    @Volatile private var running = true
    private var worker: Thread? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(SERVICE_ID, NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_premium_pos)
            .setContentTitle("Restaurant POS alerts active")
            .setContentText("Watching for waiter, kitchen and counter orders")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build())
        val initialStore = Store(this)
        if (initialStore.staffRole() in setOf("ADMIN", "OWNER")) {
            LocalNetworkSync.start(this, initialStore)
        }
        worker = Thread { monitorOrders() }.also { it.name = "PosOrderAlertMonitor"; it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ACK) {
            val id = intent.getLongExtra("id", -1L)
            val signature = intent.getStringExtra("signature").orEmpty()
            if (id > 0 && signature.isNotBlank()) {
                getSharedPreferences("pos_order_alert_state", MODE_PRIVATE).edit().putString("ack_$id", signature).apply()
                getSystemService(NotificationManager::class.java).cancel(id.toInt())
            }
        }
        return START_STICKY
    }

    private fun monitorOrders() {
        val prefs = getSharedPreferences("voice_local_network", MODE_PRIVATE)
        val store = Store(this)
        while (running) {
            try {
                val ip = prefs.getString("owner_ip", "").orEmpty()
                val session = prefs.getString("staff_session", "").orEmpty()
                val role = store.staffRole()
                if (role in setOf("ADMIN", "OWNER") && !LocalNetworkSync.isRunning()) {
                    LocalNetworkSync.start(this, store)
                }
                if (ip.isNotBlank() && session.isNotBlank() && role in setOf("WAITER","CHEF","COUNTER","OWNER","ADMIN")) {
                    LocalNetworkSync.pullOrders(ip, session, store).forEach { order ->
                        val signature = "${order.status}|${order.updatedAt}|${order.kitchenProgress}|${order.items.joinToString { it.name + ":" + it.qty }}"
                        val state = getSharedPreferences("pos_order_alert_state", MODE_PRIVATE)
                        val old = state.getString("seen_${order.id}", null)
                        val acknowledged = state.getString("ack_${order.id}", null) == signature
                        val lastAlert = state.getLong("alert_time_${order.id}", 0L)
                        val repeatDue = false
                        if (shouldAlert(role, order) && !acknowledged && old != signature) {
                            postOrderAlert(order.id, signature, role, order.tableName.ifBlank { order.orderType },
                                order.items.joinToString { "${it.qty}× ${it.name}" }, order.specialInstructions)
                            state.edit().putLong("alert_time_${order.id}", System.currentTimeMillis()).apply()
                        }
                        state.edit().putString("seen_${order.id}", signature).apply()
                    }
                }
            } catch (_: Exception) { }
            try { Thread.sleep(4000L) } catch (_: InterruptedException) { break }
        }
    }

    private fun shouldAlert(role: String, order: PendingSale): Boolean {
        if (order.status in setOf("SERVED","DELIVERED")) return false
        val progress = runCatching { JSONObject(order.kitchenProgress) }.getOrDefault(JSONObject())
        return when (role) {
            "WAITER" -> order.status in setOf("READY","OUT_FOR_DELIVERY")
            "COUNTER" -> order.status in setOf("NEW","READY")
            "CHEF" -> order.status in setOf("NEW","PREPARING") && order.items.any { progress.optString(it.kitchen, "NEW") != "READY" }
            else -> true
        }
    }

    private fun postOrderAlert(id: Long, signature: String, role: String, table: String, items: String, note: String) {
        val launch = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("pos_order_alert", true).putExtra("order_id", id)
        val openOrder = PendingIntent.getActivity(this, id.toInt(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val ackIntent = Intent(this, OrderAlertService::class.java).setAction(ACTION_ACK)
            .putExtra("id", id).putExtra("signature", signature)
        val ack = PendingIntent.getService(this, (id xor 0x5A5A).toInt(), ackIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = "$table • $items" + if (note.isBlank()) "" else " • Note: $note"
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_premium_pos)
            .setContentTitle("NEW POS ORDER • $role")
            .setContentText(body.take(220))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openOrder)
            .addAction(0, "ACKNOWLEDGE", ack)
            .setOngoing(false).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(id.toInt(), notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL, "Order updates", NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "Quiet updates for new and changing restaurant orders"
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            manager.createNotificationChannel(channel)
            val service = NotificationChannel(SERVICE_CHANNEL, "Background service", NotificationManager.IMPORTANCE_LOW)
            service.description = "Keeps order synchronization active"
            manager.createNotificationChannel(service)
        }
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }
}