package com.findupto.voicepos

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.bluetooth.*
import android.content.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.runtime.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bluetooth ESC/POS transport.
 *
 * All socket connection and write operations run away from the UI thread. A single
 * worker serializes jobs so receipt bytes from two sales can never interleave.
 */
@SuppressLint("MissingPermission")
class PrinterManager(private val context: Context, private val store: Store) {
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-pos-printer").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val socketLock = Any()
    private val pendingLock = Any()
    private val pendingPrefs = context.getSharedPreferences("voice_printer_jobs", Context.MODE_PRIVATE)
    private val pendingPrints = AtomicInteger(0)
    @Volatile private var customerSocket: BluetoothSocket? = null
    @Volatile private var kitchenSocket: BluetoothSocket? = null
    private val found = linkedMapOf<String, BluetoothDevice>()

    var devices by mutableStateOf<List<BluetoothDevice>>(emptyList()); private set
    var discovering by mutableStateOf(false); private set
    var customerConnected by mutableStateOf(false); private set
    var kitchenConnected by mutableStateOf(false); private set
    var printing by mutableStateOf(false); private set
    var lastError by mutableStateOf(""); private set
    var lastStatus by mutableStateOf("Printer ready")
        private set
    var lastPrintedAt by mutableStateOf(0L)
        private set
    var pendingCustomerCount by mutableIntStateOf(0)
        private set
    var pendingKitchenCount by mutableIntStateOf(0)
        private set
    var pendingCustomerReviewCount by mutableIntStateOf(0)
        private set
    var pendingKitchenReviewCount by mutableIntStateOf(0)
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (d != null) {
                        found[d.address] = d
                        devices = found.values.sortedBy { it.name.orEmpty() }.toList()
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    discovering = false
                    refresh()
                }
            }
        }
    }

    init {
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33)
            context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, f)
        refresh()
        refreshPendingCounts()
        main.postDelayed({ reconnectSaved() }, 700)
    }

    private fun hasConnectPermission() =
        Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission() =
        Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private fun report(message: String, error: Boolean = false) {
        main.post {
            lastStatus = message
            lastError = if (error) message else ""
        }
    }

    fun refresh() {
        if (adapter == null) {
            devices = emptyList()
            report("This device has no Bluetooth adapter", true)
            return
        }
        if (!hasConnectPermission()) {
            devices = emptyList()
            report("Allow Bluetooth connect permission to list paired printers", true)
            return
        }
        runCatching {
            adapter.bondedDevices.forEach { found[it.address] = it }
            devices = found.values.sortedBy { it.name.orEmpty() }.toList()
        }.onFailure { report("Bluetooth permission required", true) }
    }

    private fun reconnectSaved() {
        val customer = store.customerPrinterAddress()
        val kitchen = store.kitchenPrinterAddress()
        if (customer.isNotBlank()) device(customer)?.let { connectCustomer(it) }
        if (kitchen.isNotBlank() && kitchen != customer) device(kitchen)?.let { connectKitchen(it) }
    }

    fun discover() {
        if (adapter == null) {
            report("This device has no Bluetooth adapter", true)
            discovering = false
            return
        }
        if (!hasConnectPermission() || !hasScanPermission()) {
            report("Allow Bluetooth scan and connect permissions", true)
            discovering = false
            return
        }
        runCatching {
            if (!adapter.isEnabled) {
                report("Turn Bluetooth on first", true)
                discovering = false
                return
            }
            refresh()
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            main.postDelayed({
                runCatching {
                    found.clear()
                    refresh()
                    discovering = adapter.startDiscovery()
                    if (!discovering) report("Could not start Bluetooth scan", true)
                    if (discovering) main.postDelayed({
                        if (discovering) {
                            runCatching { adapter.cancelDiscovery() }
                            discovering = false
                            refresh()
                        }
                    }, 15000)
                }.onFailure {
                    discovering = false
                    report("Printer scan failed: ${it.message}", true)
                }
            }, 250)
        }.onFailure {
            discovering = false
            report("Bluetooth scan failed: ${it.message}", true)
        }
    }

    fun discoverNow() {
        lastError = ""
        refresh()
        main.post { discover() }
    }

    fun device(address: String) = devices.firstOrNull { it.address == address }

    private fun invalidateSocket(failed: BluetoothSocket?, kitchen: Boolean) {
        synchronized(socketLock) {
            if (failed == null) {
                if (kitchen) kitchenSocket = null else customerSocket = null
            } else {
                if (customerSocket === failed) customerSocket = null
                if (kitchenSocket === failed) kitchenSocket = null
                runCatching { failed.close() }
            }
        }
        main.post {
            customerConnected = customerSocket?.isConnected == true
            kitchenConnected = kitchenSocket?.isConnected == true
        }
    }

    private fun connectSocket(
        target: BluetoothDevice,
        current: () -> BluetoothSocket?,
        other: () -> BluetoothSocket?,
        otherAddress: () -> String,
        assign: (BluetoothSocket?) -> Unit,
        onConnected: (Boolean) -> Unit
    ) {
        if (!hasConnectPermission()) {
            report("Allow Bluetooth connect permission", true)
            onConnected(false)
            return
        }
        report("Connecting to ${target.name.orEmpty().ifBlank { "printer" }}…")
        worker.execute {
            var connected: BluetoothSocket? = null
            try {
                if (hasScanPermission() && adapter?.isDiscovering == true) adapter.cancelDiscovery()
                val shared = other()
                if (shared != null && shared.isConnected && (otherAddress() == target.address ||
                    runCatching { shared.remoteDevice.address == target.address }.getOrDefault(false))) {
                    synchronized(socketLock) { assign(shared) }
                    report("Shared connection: ${target.name.orEmpty().ifBlank { target.address }}")
                    main.post { onConnected(true) }
                    return@execute
                }
                val previous = current()
                if (previous != null && previous !== shared) runCatching { previous.close() }
                connected = target.createRfcommSocketToServiceRecord(spp)
                connected!!.connect()
                synchronized(socketLock) { assign(connected) }
                report("Connected: ${target.name.orEmpty().ifBlank { target.address }}")
                main.post { onConnected(true) }
            } catch (e: Exception) {
                runCatching { connected?.close() }
                synchronized(socketLock) { assign(null) }
                report("Could not connect to ${target.name.orEmpty().ifBlank { "printer" }}: ${e.message ?: "connection failed"}", true)
                main.post { onConnected(false) }
            }
        }
    }

    fun connectCustomer(d: BluetoothDevice) {
        connectSocket(d, { customerSocket }, { kitchenSocket }, { store.kitchenPrinterAddress() }, { customerSocket = it }) {
            customerConnected = it
            if (it) {
                store.setCustomerPrinterAddress(d.address)
                if (store.kitchenPrinterAddress() == d.address) {
                    synchronized(socketLock) { kitchenSocket = customerSocket }
                    kitchenConnected = true
                    drainPending(true)
                }
                drainPending(false)
            }
        }
    }

    fun connectKitchen(d: BluetoothDevice) {
        connectSocket(d, { kitchenSocket }, { customerSocket }, { store.customerPrinterAddress() }, { kitchenSocket = it }) {
            kitchenConnected = it
            if (it) {
                store.setKitchenPrinterAddress(d.address)
                if (store.customerPrinterAddress() == d.address) {
                    synchronized(socketLock) { customerSocket = kitchenSocket }
                    customerConnected = true
                    drainPending(false)
                }
                drainPending(true)
            }
        }
    }

    fun disconnectCustomer() {
        synchronized(socketLock) {
            val socket = customerSocket
            customerSocket = null
            if (socket != null && socket !== kitchenSocket) runCatching { socket.close() }
        }
        customerConnected = false
        report("Receipt printer disconnected")
    }

    fun disconnectKitchen() {
        synchronized(socketLock) {
            val socket = kitchenSocket
            kitchenSocket = null
            if (socket != null && socket !== customerSocket) runCatching { socket.close() }
        }
        kitchenConnected = false
        report("Kitchen printer disconnected")
    }

    fun printCustomer(bytes: ByteArray) = enqueuePrint("Customer receipt", false, bytes)
    fun printKitchen(bytes: ByteArray) = enqueuePrint("Kitchen ticket", true, bytes)

    fun testCustomer() = printCustomer(
        byteArrayOf(0x1B, 0x40, 0x1B, 0x61, 1) +
            "VOICE POS\nRECEIPT PRINTER TEST\nConnection and paper feed OK\n\n\n".toByteArray(Charsets.UTF_8)
    )

    fun testKitchen() = printKitchen(
        byteArrayOf(0x1B, 0x40, 0x1B, 0x61, 1) +
            "VOICE POS\nKITCHEN PRINTER TEST\nOrder ticket test OK\n\n\n".toByteArray(Charsets.UTF_8)
    )

    private fun enqueuePrint(label: String, kitchen: Boolean, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) {
            report("Nothing to print", true)
            return false
        }
        val routeAddress = if (kitchen) store.kitchenPrinterAddress() else store.customerPrinterAddress()
        val socket = if (kitchen) kitchenSocket else customerSocket
        val payload = bytes.copyOf()
        if (socket == null || !socket.isConnected) {
            if (!persistPending(kitchen, payload, routeAddress)) {
                report("$label could not be saved. Re-open the sale and use RE-PRINT after reconnecting.", true)
                return false
            }
            if (kitchen) kitchenConnected = false else customerConnected = false
            report("$label saved for its configured printer to reconnect")
            return true
        }
        pendingPrints.incrementAndGet()
        printing = true
        report("Queued $label…")
        worker.execute {
            var usedSocket: BluetoothSocket? = null
            var writeAttempted = false
            try {
                synchronized(socketLock) {
                    val active = if (kitchen) kitchenSocket else customerSocket
                    if (active == null || !active.isConnected) throw IOException("Printer connection was lost before sending")
                    usedSocket = active
                    val output = active.outputStream
                    var offset = 0
                    while (offset < payload.size) {
                        val count = minOf(2048, payload.size - offset)
                        writeAttempted = true
                        output.write(payload, offset, count)
                        offset += count
                    }
                    output.flush()
                }
                main.post {
                    printing = pendingPrints.decrementAndGet() > 0
                    lastPrintedAt = System.currentTimeMillis()
                    lastStatus = "$label sent to printer"
                    lastError = ""
                }
            } catch (e: Exception) {
                invalidateSocket(usedSocket, kitchen)
                val safelyQueued = !writeAttempted && persistPending(kitchen, payload, routeAddress)
                main.post {
                    printing = pendingPrints.decrementAndGet() > 0
                    if (safelyQueued) {
                        lastStatus = "$label saved for its configured printer to reconnect"
                        lastError = ""
                    } else {
                        lastStatus = if (writeAttempted)
                            "Print may be partial. Check the paper before RE-PRINT to avoid duplicates."
                        else "$label could not be saved. Re-open the sale and use RE-PRINT after reconnecting."
                        lastError = lastStatus
                    }
                }
            }
        }
        return true // Accepted into the serialized print queue; completion is shown in Printer Settings.
    }

    private fun persistPending(kitchen: Boolean, bytes: ByteArray, printerAddress: String): Boolean {
        val saved = synchronized(pendingLock) {
            val jobs = runCatching { JSONArray(pendingPrefs.getString("jobs", "[]") ?: "[]") }.getOrDefault(JSONArray())
            if (jobs.length() >= MAX_PENDING_JOBS) return@synchronized false
            jobs.put(JSONObject().put("id", UUID.randomUUID().toString())
                .put("kitchen", kitchen)
                .put("address", printerAddress)
                .put("payload", Base64.encodeToString(bytes, Base64.NO_WRAP)))
            pendingPrefs.edit().putString("jobs", jobs.toString()).commit()
        }
        refreshPendingCounts()
        return saved
    }

    private fun refreshPendingCounts() {
        val counts = synchronized(pendingLock) {
            val jobs = runCatching { JSONArray(pendingPrefs.getString("jobs", "[]") ?: "[]") }.getOrDefault(JSONArray())
            var customer = 0
            var kitchen = 0
            var customerReview = 0
            var kitchenReview = 0
            for (i in 0 until jobs.length()) {
                val job = jobs.optJSONObject(i) ?: continue
                val review = job.optBoolean("needsReview", false)
                if (job.optBoolean("kitchen")) {
                    if (review) kitchenReview++ else kitchen++
                } else {
                    if (review) customerReview++ else customer++
                }
            }
            listOf(customer, kitchen, customerReview, kitchenReview)
        }
        main.post {
            pendingCustomerCount = counts[0]
            pendingKitchenCount = counts[1]
            pendingCustomerReviewCount = counts[2]
            pendingKitchenReviewCount = counts[3]
        }
    }

    private fun markPendingForReview(jobId: String): Boolean {
        val saved = synchronized(pendingLock) {
            val jobs = runCatching { JSONArray(pendingPrefs.getString("jobs", "[]") ?: "[]") }.getOrDefault(JSONArray())
            var foundJob = false
            val updated = JSONArray()
            for (i in 0 until jobs.length()) {
                val item = jobs.optJSONObject(i) ?: continue
                if (item.optString("id") == jobId) {
                    item.put("needsReview", true)
                    foundJob = true
                }
                updated.put(item)
            }
            foundJob && pendingPrefs.edit().putString("jobs", updated.toString()).commit()
        }
        refreshPendingCounts()
        return saved
    }

    fun clearReviewJobs(kitchen: Boolean) {
        worker.execute {
            val saved = synchronized(pendingLock) {
                val jobs = runCatching { JSONArray(pendingPrefs.getString("jobs", "[]") ?: "[]") }.getOrDefault(JSONArray())
                val remaining = JSONArray()
                for (i in 0 until jobs.length()) {
                    val item = jobs.optJSONObject(i) ?: continue
                    if (item.optBoolean("kitchen") != kitchen || !item.optBoolean("needsReview", false)) {
                        remaining.put(item)
                    }
                }
                pendingPrefs.edit().putString("jobs", remaining.toString()).commit()
            }
            refreshPendingCounts()
            report(if (saved) "Reviewed print jobs cleared" else "Could not clear reviewed print jobs", !saved)
        }
    }

    /** Replays only jobs routed to the printer that has just reconnected. */
    private fun drainPending(kitchen: Boolean) {
        val targetAddress = if (kitchen) store.kitchenPrinterAddress() else store.customerPrinterAddress()
        worker.execute {
            while (true) {
                val job = synchronized(pendingLock) {
                    val jobs = runCatching { JSONArray(pendingPrefs.getString("jobs", "[]") ?: "[]") }.getOrDefault(JSONArray())
                    (0 until jobs.length()).firstNotNullOfOrNull { i ->
                        runCatching { jobs.getJSONObject(i) }.getOrNull()?.takeIf {
                            it.optBoolean("kitchen") == kitchen &&
                                !it.optBoolean("needsReview", false) &&
                                (it.optString("address").isBlank() || it.optString("address") == targetAddress)
                        }
                    }
                } ?: break
                val socket = if (kitchen) kitchenSocket else customerSocket
                if (socket == null || !socket.isConnected) break
                var usedSocket: BluetoothSocket? = null
                var writeAttempted = false
                try {
                    val payload = Base64.decode(job.getString("payload"), Base64.DEFAULT)
                    synchronized(socketLock) {
                        val active = if (kitchen) kitchenSocket else customerSocket
                        if (active == null || !active.isConnected) throw IOException("Printer connection was lost")
                        usedSocket = active
                        val output = active.outputStream
                        writeAttempted = true
                        output.write(payload)
                        output.flush()
                    }
                    val removed = synchronized(pendingLock) {
                        val jobs = runCatching { JSONArray(pendingPrefs.getString("jobs", "[]") ?: "[]") }.getOrDefault(JSONArray())
                        val remaining = JSONArray()
                        for (i in 0 until jobs.length()) {
                            val item = jobs.optJSONObject(i) ?: continue
                            if (item.optString("id") != job.optString("id")) remaining.put(item)
                        }
                        pendingPrefs.edit().putString("jobs", remaining.toString()).commit()
                    }
                    refreshPendingCounts()
                    if (!removed) {
                        report("Queued job was sent, but queue state could not be saved. Check paper before retrying.", true)
                        break
                    }
                    report(if (kitchen) "Queued kitchen ticket sent to printer" else "Queued customer receipt sent to printer")
                } catch (e: Exception) {
                    invalidateSocket(usedSocket, kitchen)
                    if (writeAttempted) {
                        val paused = markPendingForReview(job.optString("id"))
                        report(
                            if (paused) "Queued print may be partial and is paused. Check paper; use PRINT SLIP or RE-PRINT if missing."
                            else "Queued print may be partial and could not be paused. Check paper before reconnecting or retrying.",
                            true
                        )
                    } else {
                        report("Queued print was not sent; job retained for the next reconnect.", true)
                    }
                    break
                }
            }
        }
    }

    fun close() {
        disconnectCustomer()
        disconnectKitchen()
        worker.shutdown()
        runCatching { context.unregisterReceiver(receiver) }
    }

    companion object {
        private const val MAX_PENDING_JOBS = 500
    }
}
