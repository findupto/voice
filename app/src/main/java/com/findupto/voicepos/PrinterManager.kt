package com.findupto.voicepos

import android.bluetooth.*
import android.content.*
import android.os.Build
import android.os.Handler
import androidx.compose.runtime.*
import java.util.UUID

class PrinterManager(private val context: Context, private val store: Store) {
    private val adapter=BluetoothAdapter.getDefaultAdapter()
    private val spp=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var customerSocket:BluetoothSocket?=null
    private var kitchenSocket:BluetoothSocket?=null
    private val found=linkedMapOf<String,BluetoothDevice>()
    var devices by mutableStateOf<List<BluetoothDevice>>(emptyList()); private set
    var discovering by mutableStateOf(false); private set
    var customerConnected by mutableStateOf(false); private set
    var kitchenConnected by mutableStateOf(false); private set
    var lastError by mutableStateOf(""); private set
    private val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){when(i?.action){BluetoothDevice.ACTION_FOUND->{val d:BluetoothDevice?=if(Build.VERSION.SDK_INT>=33)i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java)else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);if(d!=null){found[d.address]=d;devices=found.values.toList()}};BluetoothAdapter.ACTION_DISCOVERY_FINISHED->{discovering=false;refresh()}}}}
    init{val f=IntentFilter().apply{addAction(BluetoothDevice.ACTION_FOUND);addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)};if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED)else @Suppress("DEPRECATION") context.registerReceiver(receiver,f);refresh();Handler(context.mainLooper).postDelayed({reconnectSaved()},700)}
    fun refresh(){runCatching{adapter?.bondedDevices?.forEach{found[it.address]=it};devices=found.values.sortedBy{it.name.orEmpty()}.toList()}.onFailure{lastError="Bluetooth permission required"}}
    private fun reconnectSaved(){store.customerPrinterAddress().takeIf{it.isNotBlank()}?.let{device(it)?.let{d->connectCustomer(d)}};store.kitchenPrinterAddress().takeIf{it.isNotBlank()}?.let{device(it)?.let{d->connectKitchen(d)}}}
    fun discover(){runCatching{if(adapter?.isEnabled!=true){lastError="Turn Bluetooth on first";discovering=false;return};refresh();if(adapter?.isDiscovering==true)adapter.cancelDiscovery();Handler(context.mainLooper).postDelayed({runCatching{found.clear();refresh();discovering=adapter?.startDiscovery()==true;if(discovering)Handler(context.mainLooper).postDelayed({if(discovering){runCatching{adapter?.cancelDiscovery()};discovering=false;refresh()}},15000)}.onFailure{discovering=false;lastError="Printer scan failed"}},250)}.onFailure{discovering=false;lastError="Bluetooth scan failed"}}
    fun discoverNow(){lastError="";refresh();Handler(context.mainLooper).post{discover()}}
    fun device(address:String)=devices.firstOrNull{it.address==address}
    private fun connectSocket(old:BluetoothSocket?,d:BluetoothDevice,onResult:(BluetoothSocket?)->Unit){Thread{val socket=runCatching{old?.close();if(adapter?.isDiscovering==true)adapter.cancelDiscovery();d.createRfcommSocketToServiceRecord(spp).also{it.connect()}}.getOrNull();Handler(context.mainLooper).post{if(socket==null)lastError="Could not connect to ${d.name.orEmpty().ifBlank{"printer"}}";onResult(socket)}}.start()}
    fun connectCustomer(d:BluetoothDevice){connectSocket(customerSocket,d){customerSocket=it;customerConnected=it!=null;if(it!=null)store.setCustomerPrinterAddress(d.address)}}
    fun connectKitchen(d:BluetoothDevice){connectSocket(kitchenSocket,d){kitchenSocket=it;kitchenConnected=it!=null;if(it!=null)store.setKitchenPrinterAddress(d.address)}}
    fun disconnectCustomer(){runCatching{customerSocket?.close()};customerSocket=null;customerConnected=false}
    fun disconnectKitchen(){runCatching{kitchenSocket?.close()};kitchenSocket=null;kitchenConnected=false}
    fun printCustomer(b:ByteArray)=printTo(customerSocket,b)
    fun printKitchen(b:ByteArray)=printTo(kitchenSocket,b)
    fun testCustomer()=printTo(customerSocket,"\u001B@\u001Ba\u0001VOICE POS\nRECEIPT PRINTER TEST\n\n\n".toByteArray())
    fun testKitchen()=printTo(kitchenSocket,"\u001B@\u001Ba\u0001VOICE POS\nKITCHEN PRINTER TEST\n\n\n".toByteArray())
    private fun printTo(s:BluetoothSocket?,b:ByteArray):Boolean=runCatching{val o=s?.outputStream?:throw IllegalStateException("Printer is not connected");var off=0;while(off<b.size){val n=minOf(1024,b.size-off);o.write(b,off,n);o.flush();off+=n;Thread.sleep(8)};true}.onFailure{lastError="Print failed: ${it.message}"}.getOrDefault(false)
    fun close(){disconnectCustomer();disconnectKitchen();runCatching{context.unregisterReceiver(receiver)}}
}
