package com.findupto.voicepos

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.bluetooth.*
import android.content.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

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

    private fun connectSocket(
        target: BluetoothDevice,
        current: () -> BluetoothSocket?,
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
                val previous = current()
                runCatching { previous?.close() }
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
        connectSocket(d, { customerSocket }, { customerSocket = it }) {
            customerConnected = it
            if (it) store.setCustomerPrinterAddress(d.address)
        }
    }

    fun connectKitchen(d: BluetoothDevice) {
        connectSocket(d, { kitchenSocket }, { kitchenSocket = it }) {
            kitchenConnected = it
            if (it) store.setKitchenPrinterAddress(d.address)
        }
    }

    fun disconnectCustomer() {
        synchronized(socketLock) { runCatching { customerSocket?.close() }; customerSocket = null }
        customerConnected = false
        report("Receipt printer disconnected")
    }

    fun disconnectKitchen() {
        synchronized(socketLock) { runCatching { kitchenSocket?.close() }; kitchenSocket = null }
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
        val socket = if (kitchen) kitchenSocket else customerSocket
        if (socket == null || !socket.isConnected) {
            if (kitchen) kitchenConnected = false else customerConnected = false
            report("$label not sent: printer is disconnected. Reconnect it in Printer Settings.", true)
            return false
        }
        val payload = bytes.copyOf()
        printing = true
        report("Printing $label…")
        worker.execute {
            try {
                synchronized(socketLock) {
                    val active = if (kitchen) kitchenSocket else customerSocket
                    if (active == null || !active.isConnected) throw IOException("Printer connection was lost")
                    val output = active.outputStream
                    var offset = 0
                    while (offset < payload.size) {
                        val count = minOf(2048, payload.size - offset)
                        output.write(payload, offset, count)
                        offset += count
                    }
                    output.flush()
                }
                main.post {
                    printing = false
                    lastPrintedAt = System.currentTimeMillis()
                    lastStatus = "$label sent successfully"
                    lastError = ""
                }
            } catch (e: Exception) {
                synchronized(socketLock) {
                    if (kitchen) { runCatching { kitchenSocket?.close() }; kitchenSocket = null }
                    else { runCatching { customerSocket?.close() }; customerSocket = null }
                }
                main.post {
                    printing = false
                    if (kitchen) kitchenConnected = false else customerConnected = false
                    report("Print failed: ${e.message ?: "printer connection lost"}. Reconnect and retry.", true)
                }
            }
        }
        return true // Accepted into the serialized print queue; completion is shown in Printer Settings.
    }

    fun close() {
        disconnectCustomer()
        disconnectKitchen()
        worker.shutdown()
        runCatching { context.unregisterReceiver(receiver) }
    }
}
