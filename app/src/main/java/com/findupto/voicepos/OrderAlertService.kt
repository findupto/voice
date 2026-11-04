package com.findupto.voicepos

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject

class OrderAlertService : Service() {
    companion object {
        private const val CHANNEL = "pos_order_alerts"
        private const val SERVICE_ID = 8100
        private const val ACTION_ACK = "com.findupto.voicepos.ACK_ORDER_ALERT"
    }
    @Volatile private var running = true
    private var worker: Thread? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(SERVICE_ID, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_premium_pos)
            .setContentTitle("Restaurant POS alerts active")
            .setContentText("Watching for waiter, kitchen and counter orders")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build())
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
                if (ip.isNotBlank() && session.isNotBlank() && role in setOf("WAITER","CHEF","COUNTER","OWNER","ADMIN")) {
                    LocalNetworkSync.pullOrders(ip, session, store).forEach { order ->
                        val signature = "${order.status}|${order.updatedAt}|${order.kitchenProgress}|${order.items.joinToString { it.name + ":" + it.qty }}"
                        val state = getSharedPreferences("pos_order_alert_state", MODE_PRIVATE)
                        val old = state.getString("seen_${order.id}", null)
                        if (old != signature && shouldAlert(role, order) && state.getString("ack_${order.id}", null) != signature) {
                            postOrderAlert(order.id, signature, role, order.tableName.ifBlank { order.orderType },
                                order.items.joinToString { "${it.qty}× ${it.name}" }, order.specialInstructions)
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
            "WAITER" -> order.status in setOf("NEW","READY","OUT_FOR_DELIVERY")
            "COUNTER" -> order.status in setOf("NEW","READY")
            "CHEF" -> order.items.any { progress.optString(it.kitchen, "NEW") != "READY" }
            else -> true
        }
    }

    private fun postOrderAlert(id: Long, signature: String, role: String, table: String, items: String, note: String) {
        val launch = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("pos_order_alert", true).putExtra("order_id", id)
        val fullScreen = PendingIntent.getActivity(this, id.toInt(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val ackIntent = Intent(this, OrderAlertService::class.java).setAction(ACTION_ACK)
            .putExtra("id", id).putExtra("signature", signature)
        val ack = PendingIntent.getService(this, (id xor 0x5A5A).toInt(), ackIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = "$table • $items" + if (note.isBlank()) "" else " • Note: $note"
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_premium_pos)
            .setContentTitle("NEW POS ORDER • $role")
            .setContentText(body.take(220))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, "ACKNOWLEDGE", ack)
            .setOngoing(true).setAutoCancel(false).build()
        getSystemService(NotificationManager::class.java).notify(id.toInt(), notification)
        val power = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val lock = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "VoicePOS:OrderAlert")
        if (!lock.isHeld) lock.acquire(12_000L)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Urgent order alerts", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "Urgent waiter, kitchen and counter order alerts"
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }
}