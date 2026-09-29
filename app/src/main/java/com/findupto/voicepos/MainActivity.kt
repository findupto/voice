package com.findupto.voicepos

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private lateinit var printer: PrinterManager
    private val heard = mutableStateOf("")
    private val voiceStatus = mutableStateOf("Ready for offline voice")
    private var recognizer: SpeechRecognizer? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        voiceStatus.value = if (canUseOfflineVoice()) "Offline voice ready" else "Offline voice model unavailable"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        printer = PrinterManager(this)

        val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            permissionsToRequest += Manifest.permission.BLUETOOTH_SCAN
            permissionsToRequest += Manifest.permission.BLUETOOTH_CONNECT
        }
        permissions.launch(permissionsToRequest.toTypedArray())

        setContent {
            VoicePosTheme {
                PosApp(
                    store = store,
                    printer = printer,
                    heard = heard.value,
                    voiceStatus = voiceStatus.value,
                    clearHeard = { heard.value = "" },
                    listen = ::listen
                )
            }
        }
    }

    private fun canUseOfflineVoice(): Boolean =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)

    private fun listen() {
        if (!canUseOfflineVoice()) {
            voiceStatus.value = "Offline voice needs Android 12+ with an installed speech language pack"
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            voiceStatus.value = "Microphone permission is required"
            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { voiceStatus.value = "Listening offline…" }
                override fun onBeginningOfSpeech() { voiceStatus.value = "Listening…" }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { voiceStatus.value = "Processing offline…" }
                override fun onError(error: Int) {
                    voiceStatus.value = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech heard — try again"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out — try again"
                        else -> "Offline voice error — check the speech language pack"
                    }
                }
                override fun onResults(results: Bundle?) {
                    heard.value = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    voiceStatus.value = if (heard.value.isBlank()) "Ready for offline voice" else "Voice captured offline"
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-PK")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer?.startListening(intent)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        printer.close()
        super.onDestroy()
    }
}
