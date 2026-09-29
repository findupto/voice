package com.findupto.voicepos

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
class MainActivity:ComponentActivity(){
 private lateinit var store:Store;private lateinit var printer:PrinterManager;private val heard=androidx.compose.runtime.mutableStateOf("")
 private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){}
 private val speech=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){r->heard.value=r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?:""}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);store=Store(this);printer=PrinterManager(this);val list=mutableListOf(Manifest.permission.RECORD_AUDIO);if(Build.VERSION.SDK_INT>=31){list+=Manifest.permission.BLUETOOTH_SCAN;list+=Manifest.permission.BLUETOOTH_CONNECT};permissions.launch(list.toTypedArray());setContent{VoicePosTheme{PosApp(store,printer,heard.value,{heard.value=""},::listen)}}}
 private fun listen(){if(!SpeechRecognizer.isRecognitionAvailable(this))return;speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-PK")})}
 override fun onDestroy(){printer.close();super.onDestroy()}
}
