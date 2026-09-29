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
    private val voiceStatus = mutableStateOf("Ready for offline voice")
    private var recognizer: SpeechRecognizer? = null

    private val exportMenuLauncher = registerForActivityResult(CreateDocument("text/csv")) { uri -> uri?.let { writeMenuCsv(it) } }
    private val importMenuLauncher = registerForActivityResult(OpenDocument()) { uri -> uri?.let { readMenuCsv(it) } }

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
                    listen = ::listen,
                    openSpeechSettings = ::openSpeechSettings,
                    exportMenu = { exportMenuLauncher.launch("voice-pos-menu.csv") },
                    importMenu = { importMenuLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) }
                )
            }
        }
        ensureOfflineVoicePack()
    }

    private fun canUseOfflineVoice(): Boolean =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)

    private fun ensureOfflineVoicePack() {
        if (Build.VERSION.SDK_INT < 31 || canUseOfflineVoice()) return
        voiceStatus.value = "Speech language pack missing — opening Android speech settings"
        val prefs = getSharedPreferences("voice_pos_v4", MODE_PRIVATE)
        if (!prefs.getBoolean("speech_settings_prompted", false)) {
            prefs.edit().putBoolean("speech_settings_prompted", true).apply()
            runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                .onFailure { runCatching { startActivity(Intent("com.android.settings.SPEECH_RECOGNITION_SETTINGS")) } }
        }
    }

    private fun listen() {
        if (!canUseOfflineVoice()) {
            voiceStatus.value = "Offline voice needs Android 12+ with the speech language pack installed"
            ensureOfflineVoicePack()
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
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "English (Pakistan) speech pack is unavailable"
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "English (Pakistan) speech pack is unavailable"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice recognizer is busy — try again"
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

    private fun openSpeechSettings() {
        val intent = Intent("com.android.settings.SPEECH_RECOGNITION_SETTINGS")
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent("android.settings.SETTINGS"))
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
            val parts = line.split(",").map { it.trim().trim('"').replace("\"\"", "\"") }
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
        if (imported.isNotEmpty()) store.saveMenu(imported.distinctBy { it.name.lowercase() })
    }

    private fun importedHash(name: String): Long = name.hashCode().toLong() and 0xffffffffL

    override fun onDestroy() {
        recognizer?.destroy()
        printer.close()
        super.onDestroy()
    }
}
