package com.findupto.voicepos

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private lateinit var printer: PrinterManager
    private val heard = mutableStateOf("")
    private val voiceStatus = mutableStateOf("Ready for voice")
    private var recognizer: SpeechRecognizer? = null
    private var voiceRetryCount = 0
    private var listening = false
    private var recognitionMode = 0 // 0 = system, 1 = on-device
    private var recognitionAttempt = 0
    private val voiceHandler by lazy { android.os.Handler(mainLooper) }

    private val exportMenuLauncher = registerForActivityResult(CreateDocument("text/csv")) { uri -> uri?.let { writeMenuCsv(it) } }
    private val importMenuLauncher = registerForActivityResult(OpenDocument()) { uri -> uri?.let { readMenuCsv(it) } }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        voiceStatus.value = if (canUseVoice()) "Voice ready" else "Voice service unavailable"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        printer = PrinterManager(this)

        val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT < 31) permissionsToRequest += Manifest.permission.ACCESS_FINE_LOCATION
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
                    listen = ::listen,
                    openSpeechSettings = ::openSpeechSettings,
                    exportMenu = { exportMenuLauncher.launch("voice-pos-menu.csv") },
                    importMenu = { importMenuLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) }
                )
            }
        }
    }

    // Use Android's built-in speech service. Do not require an on-device language
    // model/package, so the app does not send the user to download a voice pack.
    private fun canUseVoice(): Boolean = SpeechRecognizer.isRecognitionAvailable(this)

    private fun listen() {
        voiceRetryCount = 0
        recognitionAttempt = 0
        recognitionMode = 0
        startVoiceListening()
    }

    private fun startVoiceListening() {
        if (listening) {
            recognizer?.cancel()
            listening = false
        }
        if (!canUseVoice()) {
            voiceStatus.value = "Android voice service is unavailable"
            openSpeechSettings()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            voiceStatus.value = "Microphone permission is required"
            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }

        recognizer?.destroy()
        recognizer = if (recognitionMode == 1 && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }.apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listening = true; voiceStatus.value = "Listening… Speak now" }
                override fun onBeginningOfSpeech() { listening = true; voiceStatus.value = "Listening… Speak now" }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { listening = false; voiceStatus.value = "Processing…" }
                override fun onError(error: Int) {
                    listening = false
                    val retryable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_NETWORK ||
                        error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_SERVER ||
                        error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                    if (retryable && voiceRetryCount < 3) {
                        voiceRetryCount++
                        recognitionAttempt++
                        voiceStatus.value = "Voice method failed — retrying automatically (" + voiceRetryCount + "/3)…"
                        voiceHandler.postDelayed({
                            recognitionMode = if (
                                Build.VERSION.SDK_INT >= 31 &&
                                SpeechRecognizer.isOnDeviceRecognitionAvailable(this@MainActivity) &&
                                recognitionAttempt % 2 == 1
                            ) 1 else 0
                            startVoiceListening()
                        }, 650)
                        return
                    }
                    voiceStatus.value = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Microphone could not start — use Manual Add"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Speech language unavailable — use Manual Add"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice recognizer is busy — use Manual Add"
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No voice heard — use Manual Add"
                        else -> "Voice error — use Manual Add"
                    }
                }
                override fun onResults(results: Bundle?) {
                    heard.value = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty().trim()
                    voiceRetryCount = 0
                    recognitionAttempt = 0
                    voiceStatus.value = if (heard.value.isBlank()) "No voice heard — tap Try Voice or use Manual Add" else "Voice captured"
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, java.util.Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000)
        }
        recognizer?.startListening(intent)
    }

    private fun openSpeechSettings() {
        val intent = Intent("com.android.settings.SPEECH_RECOGNITION_SETTINGS")
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun writeMenuCsv(uri: Uri) {
        val csv = buildString {
            appendLine("name,variant,size,price")
            store.menu().forEach { item ->
                val values = listOf(item.name, item.variant, item.size, item.price.toString())
                appendLine(values.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" })
            }
        }
        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(csv) }
    }

    private fun readMenuCsv(uri: Uri) {
        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
        val imported = text.lines().drop(1).mapNotNull { line ->
            val parts = parseCsvLine(line)
            when {
                parts.size >= 4 -> {
                    val name = parts[0]
                    val variant = parts[1]
                    val size = parts[2]
                    val price = parts[3].toDoubleOrNull()
                    if (name.isBlank() || price == null || price <= 0) null
                    else MenuItem(System.currentTimeMillis() + importedHash(name + variant + size), name, price, variant, size)
                }
                parts.size >= 2 -> {
                    val name = parts.dropLast(1).joinToString(",")
                    val price = parts.last().toDoubleOrNull()
                    if (name.isBlank() || price == null || price <= 0) null
                    else MenuItem(System.currentTimeMillis() + importedHash(name), name, price)
                }
                else -> null
            }
        }
        if (imported.isNotEmpty()) {
            val merged = (store.menu() + imported).distinctBy { item ->
                item.name.trim().lowercase() + "|" + item.variant.trim().lowercase() + "|" + item.size.trim().lowercase()
            }
            store.saveMenu(merged)
            voiceStatus.value = "Imported " + imported.size + " menu row(s)"
        } else {
            voiceStatus.value = "No valid menu rows found in CSV"
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { result += current.toString().trim(); current.clear() }
                else -> current.append(ch)
            }
            i++
        }
        result += current.toString().trim()
        return result
    }

    private fun importedHash(name: String): Long = name.hashCode().toLong() and 0xffffffffL

    override fun onDestroy() {
        recognizer?.destroy()
        voiceHandler.removeCallbacksAndMessages(null)
        printer.close()
        super.onDestroy()
    }
}
