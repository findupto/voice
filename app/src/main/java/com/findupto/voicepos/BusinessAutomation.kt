package com.findupto.voicepos

import android.content.Context
import androidx.work.*
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** User-controlled, local-first business alerts. Actions with financial impact remain owner-approved. */
class BusinessAutomationStore(context: Context) {
    private val prefs = context.getSharedPreferences("voice_business_automation_v1", Context.MODE_PRIVATE)
    var lowStockAlerts: Boolean
        get() = prefs.getBoolean("low_stock", true)
        set(value) { prefs.edit().putBoolean("low_stock", value).apply() }
    var delayedOrderAlerts: Boolean
        get() = prefs.getBoolean("delayed_orders", true)
        set(value) { prefs.edit().putBoolean("delayed_orders", value).apply() }
    var dailySummary: Boolean
        get() = prefs.getBoolean("daily_summary", true)
        set(value) { prefs.edit().putBoolean("daily_summary", value).apply() }
    var purchaseDrafts: Boolean
        get() = prefs.getBoolean("purchase_drafts", true)
        set(value) { prefs.edit().putBoolean("purchase_drafts", value).apply() }
    var overdueDuesAlerts: Boolean
        get() = prefs.getBoolean("overdue_dues", true)
        set(value) { prefs.edit().putBoolean("overdue_dues", value).apply() }
    var shiftReminders: Boolean
        get() = prefs.getBoolean("shift_reminders", true)
        set(value) { prefs.edit().putBoolean("shift_reminders", value).apply() }
    var profitInsights: Boolean
        get() = prefs.getBoolean("profit_insights", true)
        set(value) { prefs.edit().putBoolean("profit_insights", value).apply() }
    var automationEnabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    fun lastRun(): Long = prefs.getLong("last_run", 0L)
}

class BusinessAutomationWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
        val store = Store(applicationContext)
        if (store.staffRole() !in setOf("OWNER", "ADMIN")) return Result.success()
        val settings = BusinessAutomationStore(applicationContext)
        if (!settings.automationEnabled) return Result.success()
        val prefs = applicationContext.getSharedPreferences("voice_business_automation_v1", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel("voice_business_automation", "Business automation", NotificationManager.IMPORTANCE_DEFAULT))
        }
        fun notify(id: Int, title: String, message: String) {
            manager.notify(id, NotificationCompat.Builder(applicationContext, "voice_business_automation")
                .setSmallIcon(R.drawable.ic_premium_pos).setContentTitle(title).setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message)).setAutoCancel(true).build())
        }
        if (settings.lowStockAlerts) {
            val low = InventoryStore(applicationContext).lowStock()
            val signature = low.map { it.id }.sorted().joinToString(",")
            if (low.isNotEmpty() && prefs.getString("low_stock_signature", "") != signature) {
                notify(8201, "Low stock needs attention", low.take(8).joinToString { "${it.name}: ${it.quantity} ${it.unit}" } +
                    if (low.size > 8) " • +${low.size - 8} more" else "")
            }
            prefs.edit().putString("low_stock_signature", signature).apply()
        }
        if (settings.delayedOrderAlerts) {
            val overdue = store.pending().filter { it.status !in setOf("SERVED", "DELIVERED", "BILLED") && now - it.time >= 15 * 60 * 1000L }
            val signature = overdue.map { it.id }.sorted().joinToString(",")
            if (overdue.isNotEmpty() && prefs.getString("overdue_signature", "") != signature) {
                notify(8202, "Orders waiting over 15 minutes", overdue.take(6).joinToString { it.tableName.ifBlank { it.orderType } + " • " + it.status } +
                    if (overdue.size > 6) " • +${overdue.size - 6} more" else "")
            }
            prefs.edit().putString("overdue_signature", signature).apply()
        }
        val inventory = InventoryStore(applicationContext)
        if (settings.purchaseDrafts) {
            val drafts = inventory.lowStock().mapNotNull { item ->
                val quantity = suggestedReorderQuantity(item.quantity, item.minimum)
                if (quantity <= 0.0 || !quantity.isFinite()) null else JSONObject()
                    .put("stockId", item.id).put("name", item.name).put("quantity", quantity)
                    .put("unit", item.unit).put("unitCost", item.unitCost)
                    .put("estimatedTotal", quantity * item.unitCost)
            }
            val draftArray = JSONArray().apply { drafts.forEach { put(it) } }
            val signature = drafts.joinToString("|") { "${it.optLong("stockId")}:${it.optDouble("quantity")}" }
            prefs.edit().putString("purchase_order_drafts", draftArray.toString()).apply()
            if (drafts.isNotEmpty() && prefs.getString("purchase_draft_signature", "") != signature) {
                notify(8204, "Purchase order draft ready", drafts.take(6).joinToString { "${it.optString("name")}: ${it.optDouble("quantity")} ${it.optString("unit")}" } +
                    " • Review and approve in Supplier Purchasing")
            }
            prefs.edit().putString("purchase_draft_signature", signature).apply()
        }
        if (settings.overdueDuesAlerts) {
            val sevenDays = now - 7L * 24 * 60 * 60 * 1000
            val overdueCustomers = store.dues().filter { it.createdAt <= sevenDays }
            val overdueSuppliers = SupplierDueStore(applicationContext).all().filter { it.balance > 0.005 && it.createdAt <= sevenDays }
            val signature = overdueCustomers.map { "c${it.id}" } .plus(overdueSuppliers.map { "s${it.id}" }).sorted().joinToString(",")
            if (signature.isNotBlank() && prefs.getString("overdue_dues_signature", "") != signature) {
                notify(8205, "Overdue payments need follow-up",
                    "${overdueCustomers.size} customer account(s) and ${overdueSuppliers.size} supplier bill(s) are 7+ days old.")
            }
            prefs.edit().putString("overdue_dues_signature", signature).apply()
        }
        if (settings.shiftReminders) {
            val longShifts = store.activeShifts().filter { now - it.openedAt >= 12L * 60 * 60 * 1000 }
            val signature = longShifts.map { it.id }.sorted().joinToString(",")
            if (signature.isNotBlank() && prefs.getString("long_shift_signature", "") != signature) {
                notify(8206, "Staff shift review", longShifts.joinToString { "${it.person} • shift open 12+ hours" })
            }
            prefs.edit().putString("long_shift_signature", signature).apply()
        }
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val dayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(cal.time)
        if (settings.dailySummary && prefs.getString("daily_summary_day", "") != dayKey) {
            val today = store.sales().filter { it.time >= cal.timeInMillis }
            val revenue = today.sumOf { it.total }
            val expenses = store.expenses().filter { it.time >= cal.timeInMillis }.sumOf { it.amount }
            val outstanding = today.sumOf { it.dueAmount }
            notify(8203, "Daily business snapshot", "${today.size} sales • Revenue ${String.format(java.util.Locale.getDefault(), "%.2f", revenue)} • Expenses ${String.format(java.util.Locale.getDefault(), "%.2f", expenses)} • Customer dues ${String.format(java.util.Locale.getDefault(), "%.2f", outstanding)}")
            prefs.edit().putString("daily_summary_day", dayKey).apply()
        }
        if (settings.profitInsights) {
            val todaySales = store.sales().filter { it.time >= cal.timeInMillis }
            val revenue = todaySales.sumOf { it.total }
            val foodCost = todaySales.sumOf { inventory.estimatedRecipeCost(it.items) }
            val expenseTotal = store.expenses().filter { it.time >= cal.timeInMillis }.sumOf { it.amount }
            val grossProfit = revenue - foodCost
            val estimatedNet = grossProfit - expenseTotal
            val profitDay = "profit_${dayKey}"
            if (prefs.getString("profit_insight_day", "") != profitDay) {
                notify(8207, "Daily estimated profit insight",
                    "Revenue ${"%.2f".format(revenue)} • Recipe cost ${"%.2f".format(foodCost)} • Expenses ${"%.2f".format(expenseTotal)} • Est. net ${"%.2f".format(estimatedNet)}")
                prefs.edit().putString("profit_insight_day", profitDay).apply()
            }
        }
        prefs.edit().putLong("last_run", now).apply()
        Result.success()
        } catch (_: Exception) { Result.retry() }
    }
}

object BusinessAutomation {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<BusinessAutomationWorker>(1, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            "voice-business-automation", ExistingPeriodicWorkPolicy.KEEP, request)
        val first = OneTimeWorkRequestBuilder<BusinessAutomationWorker>().build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "voice-business-automation-first-run", ExistingWorkPolicy.KEEP, first)
    }
    fun runNow(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "voice-business-automation-manual", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BusinessAutomationWorker>().build())
    }
}
