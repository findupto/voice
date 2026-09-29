package com.findupto.voicepos

import android.bluetooth.*
import android.content.*
import android.os.Build
import androidx.compose.runtime.*
import java.util.UUID

class PrinterManager(private val context:Context){
    private val adapter=BluetoothAdapter.getDefaultAdapter()
    private val spp=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket:BluetoothSocket?=null
    private val found=linkedMapOf<String,BluetoothDevice>()
    var devices by mutableStateOf<List<BluetoothDevice>>(emptyList()); private set
    var discovering by mutableStateOf(false); private set
    private val receiver=object:BroadcastReceiver(){
        override fun onReceive(c:Context?,i:Intent?){
            if(i?.action==BluetoothDevice.ACTION_FOUND){
                val d=if(Build.VERSION.SDK_INT>=33)i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java) else @Suppress("DEPRECATION") i?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if(d!=null){found[d.address]=d;devices=found.values.toList()}
            }else if(i?.action==BluetoothAdapter.ACTION_DISCOVERY_FINISHED) discovering=false
        }
    }
    init{val f=IntentFilter();f.addAction(BluetoothDevice.ACTION_FOUND);f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED)else @Suppress("DEPRECATION") context.registerReceiver(receiver,f);refresh()}
    fun refresh(){runCatching{adapter?.bondedDevices?.forEach{found[it.address]=it};devices=found.values.toList()}}
    fun discover(){runCatching{if(adapter?.isDiscovering==true)adapter.cancelDiscovery();discovering=adapter?.startDiscovery()==true}}
    fun connect(d:BluetoothDevice)=runCatching{adapter?.cancelDiscovery();socket?.close();socket=d.createRfcommSocketToServiceRecord(spp);socket!!.connect();true}.getOrDefault(false)
    fun connected()=socket?.isConnected==true
    fun print(b:ByteArray)=runCatching{val o=socket?.outputStream?:return false;o.write(b);o.flush();true}.getOrDefault(false)
    fun close(){runCatching{socket?.close()};runCatching{context.unregisterReceiver(receiver)}}
}
