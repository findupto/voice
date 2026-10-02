package com.findupto.voicepos

import android.bluetooth.*
import android.content.*
import android.os.Build
import androidx.compose.runtime.*
import android.os.Handler
import java.util.UUID

class PrinterManager(private val context: Context) {
    private val adapter=BluetoothAdapter.getDefaultAdapter()
    private val spp=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var customerSocket:BluetoothSocket?=null
    private var kitchenSocket:BluetoothSocket?=null
    private val found=linkedMapOf<String,BluetoothDevice>()
    var devices by mutableStateOf<List<BluetoothDevice>>(emptyList()); private set
    var discovering by mutableStateOf(false); private set
    private val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){when(i?.action){BluetoothDevice.ACTION_FOUND->{val d:BluetoothDevice?=if(Build.VERSION.SDK_INT>=33)i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java)else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);if(d!=null){found[d.address]=d;devices=found.values.toList()}};BluetoothAdapter.ACTION_DISCOVERY_FINISHED->{discovering=false;refresh()}}}}
    init{val f=IntentFilter().apply{addAction(BluetoothDevice.ACTION_FOUND);addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)};if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED)else @Suppress("DEPRECATION") context.registerReceiver(receiver,f);refresh()}
    fun refresh(){runCatching{adapter?.bondedDevices?.forEach{found[it.address]=it};devices=found.values.toList()}}
    fun discover(){
        runCatching {
            if (adapter?.isEnabled != true) { discovering = false; return }
            refresh()
            if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
            Handler(context.mainLooper).postDelayed({
                runCatching {
                    discovering = adapter?.startDiscovery() == true
                }.onFailure { discovering = false }
            }, 150)
        }.onFailure { discovering = false }
    }

    fun discoverNow() {
        refresh()
        discover()
    }
    fun device(address:String)=devices.firstOrNull{it.address==address}
    private fun connect(old:BluetoothSocket?,d:BluetoothDevice)=runCatching{old?.close();d.createRfcommSocketToServiceRecord(spp).also{it.connect()}}.getOrNull()
    fun connectCustomer(d:BluetoothDevice){customerSocket=connect(customerSocket,d)}
    fun connectKitchen(d:BluetoothDevice){kitchenSocket=connect(kitchenSocket,d)}
    fun printCustomer(b:ByteArray)=printTo(customerSocket,b)
    fun printKitchen(b:ByteArray)=printTo(kitchenSocket,b)
    private fun printTo(s:BluetoothSocket?,b:ByteArray)=runCatching{val o=s?.outputStream?:return false;o.write(b);o.flush();true}.getOrDefault(false)
    fun close(){runCatching{customerSocket?.close()};runCatching{kitchenSocket?.close()};runCatching{context.unregisterReceiver(receiver)}}
}
