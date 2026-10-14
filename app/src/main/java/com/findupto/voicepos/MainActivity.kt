package com.findupto.voicepos

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.runtime.mutableStateOf
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private lateinit var printer: PrinterManager
    private val scanStatus=mutableStateOf("")
    private val logoVersion=mutableStateOf(0)
    private val exportMenuLauncher=registerForActivityResult(CreateDocument("text/csv")){uri->uri?.let{writeMenuCsv(it)}}
    private val importMenuLauncher=registerForActivityResult(OpenDocument()){uri->uri?.let{readMenuCsv(it)}}
    private val logoLauncher=registerForActivityResult(OpenDocument()){uri->uri?.let{saveLogo(it)}}
    private val scanLauncher=registerForActivityResult(OpenDocument()){uri->uri?.let{scanMenuDocument(it)}}
    private val backupExportLauncher=registerForActivityResult(CreateDocument("application/json")){uri->uri?.let{contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{w->w.write(store.exportBackup())};scanStatus.value="Full business backup exported"}}
    private val backupImportLauncher=registerForActivityResult(OpenDocument()){uri->uri?.let{runCatching{val raw=contentResolver.openInputStream(uri)?.bufferedReader()?.use{r->r.readText()}?:"";store.importBackup(raw);scanStatus.value="Business data restored successfully — restart the app"}.onFailure{scanStatus.value="Backup import failed: ${it.message}"}}}

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        StoreMigration.migrate(this)
        store=Store(this)
        printer=PrinterManager(this,store)
        setContent {
            VoicePosTheme {
                PosApp(
                    store, printer, logoVersion.value,
                    { exportMenuLauncher.launch("voice-pos-menu.csv") },
                    { importMenuLauncher.launch(arrayOf("text/csv","text/comma-separated-values","text/plain")) },
                    { logoLauncher.launch(arrayOf("image/png","image/jpeg","image/webp")) },
                    { scanLauncher.launch(arrayOf("image/*","application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","text/csv","text/plain")) },
                    { backupExportLauncher.launch("voice-pos-business-backup.json") },
                    { backupImportLauncher.launch(arrayOf("application/json","text/json")) }
                )
            }
        }
    }

    private fun canUseVoice()=SpeechRecognizer.isRecognitionAvailable(this)
    private fun chooseRawVoiceResults(r:Bundle?):String{val candidates=r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().map{it.trim()}.filter{it.isNotBlank()};if(candidates.isEmpty())return "";val menu=store.menu();return candidates.maxWithOrNull(compareBy<String>{VoiceCommandEngine.parse(it,menu).size}.thenBy{candidate->menu.maxOfOrNull{m->VoiceCommandEngine.parse(candidate,listOf(m)).size}?:0}.thenByDescending{it.length})?:candidates.first()}
    private fun improveVoice(raw:String){if(raw.isBlank()){heard.value="";voiceStatus.value="No speech detected — speak the complete command";return};val direct=VoiceCommandEngine.parse(raw,store.menu());if(direct.isNotEmpty()){heard.value=raw;voiceStatus.value="Heard: $raw • Command ready ✓";return};voiceStatus.value="AI understanding…";Thread{val ai=AiEngine.rewriteVoiceBlocking(raw,store.menu());val normalized=ai?.takeIf{it.isNotBlank()};val parsed=normalized?.let{VoiceCommandEngine.parse(it,store.menu())}.orEmpty();runOnUiThread{if(parsed.isNotEmpty()){heard.value=normalized!!;voiceStatus.value="AI understood ✓ • $normalized"}else if(!normalized.isNullOrBlank()){heard.value="";voiceStatus.value=normalized}else{heard.value="";voiceStatus.value="I heard: $raw • no safe POS action matched • AI ${AiEngine.status()}"}}}.start()}
    private fun voiceIntent(locale: String, offline: Boolean = false): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2600)
            putExtra("android.speech.extra.PREFER_OFFLINE", offline)
            if (Build.VERSION.SDK_INT >= 23) {
                val biasTerms = store.menu().take(300)
                    .flatMap { listOf(it.name, it.variant, it.size) }
                    .filter { it.isNotBlank() }
                    .distinct()
                putStringArrayListExtra("android.speech.extra.BIASING_STRINGS", ArrayList(biasTerms))
            }
            if (Build.VERSION.SDK_INT >= 34) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                    arrayListOf("en-US", "en-PK", "ur-PK", "hi-IN")
                )
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            }
        }
    }

    private fun listen(){if(!canUseVoice()){voiceStatus.value="Android speech service unavailable — install/enable a speech recognition service";openSpeechSettings();return};if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO));return};voiceAttempt=0;startVoiceAttempt()}
    private fun startVoiceAttempt(){recognizer?.destroy();listening=false;val locale=voiceLocales.getOrElse(voiceAttempt.coerceAtMost(voiceLocales.lastIndex)){"en-US"};voiceStatus.value="Power Voice • Listening ($locale)…";recognizer=SpeechRecognizer.createSpeechRecognizer(this).apply{setRecognitionListener(object:RecognitionListener{override fun onReadyForSpeech(p:Bundle?){listening=true;voiceStatus.value="Power Voice • Listening…"};override fun onBeginningOfSpeech(){listening=true};override fun onRmsChanged(v:Float){};override fun onBufferReceived(b:ByteArray?){ };override fun onEndOfSpeech(){listening=false;voiceStatus.value="AI understanding…"};override fun onError(e:Int){listening=false;voiceAttempt++;val reason=when(e){SpeechRecognizer.ERROR_NETWORK_TIMEOUT->"network timeout";SpeechRecognizer.ERROR_NETWORK->"network error";SpeechRecognizer.ERROR_NO_MATCH->"no speech match";SpeechRecognizer.ERROR_SPEECH_TIMEOUT->"speech timeout";SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS->"microphone permission";SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED->"language unsupported";SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE->"language unavailable";SpeechRecognizer.ERROR_RECOGNIZER_BUSY->"recognizer busy";SpeechRecognizer.ERROR_SERVER->"speech server error";else->"error $e"};if(voiceAttempt<voiceLocales.size){voiceStatus.value="Voice $reason • retry ${voiceAttempt+1}/${voiceLocales.size}…";voiceHandler.postDelayed({startVoiceAttempt()},450)}else if(Build.VERSION.SDK_INT>=31&&SpeechRecognizer.isOnDeviceRecognitionAvailable(this@MainActivity)){startDeviceVoice()}else{voiceStatus.value="Voice $reason • all recognition routes failed. Check the phone speech service."}};override fun onResults(r:Bundle?){voiceAttempt=0;improveVoice(chooseRawVoiceResults(r))};override fun onPartialResults(r:Bundle?){ };override fun onEvent(t:Int,p:Bundle?){ }})};runCatching{recognizer?.startListening(voiceIntent(locale,false))}.onFailure{voiceAttempt++;if(voiceAttempt<voiceLocales.size)voiceHandler.postDelayed({startVoiceAttempt()},450)else voiceStatus.value="Could not start voice recognition: ${it.message}"}}
    @TargetApi(Build.VERSION_CODES.S)
    private fun startDeviceVoice(){recognizer?.destroy();voiceStatus.value="Power Voice • Offline listening…";recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply{setRecognitionListener(object:RecognitionListener{override fun onReadyForSpeech(p:Bundle?){listening=true};override fun onBeginningOfSpeech(){listening=true};override fun onRmsChanged(v:Float){};override fun onBufferReceived(b:ByteArray?){ };override fun onEndOfSpeech(){listening=false;voiceStatus.value="AI understanding…"};override fun onError(e:Int){voiceStatus.value="Offline voice error $e — falling back to online speech";voiceAttempt=0;voiceHandler.postDelayed({startVoiceAttempt()},300)};override fun onResults(r:Bundle?){improveVoice(chooseRawVoiceResults(r))};override fun onPartialResults(r:Bundle?){ };override fun onEvent(t:Int,p:Bundle?){ }})};runCatching{recognizer?.startListening(voiceIntent("en-US",true))}.onFailure{voiceStatus.value="Offline voice unavailable — using online speech";voiceAttempt=0;startVoiceAttempt()}}
    private fun openSpeechSettings(){runCatching{startActivity(Intent("com.android.settings.SPEECH_RECOGNITION_SETTINGS"))}.onFailure{startActivity(Intent(Settings.ACTION_SETTINGS))}}

    override fun onDestroy() {
        if (::printer.isInitialized) printer.close()
        super.onDestroy()
    }

    private fun parseCsvLine(s:String):List<String>{val out=mutableListOf<String>();var cur="";var q=false;for(c in s){if(c=='\"')q=!q else if(c==','&&!q){out+=cur;cur=""}else cur+=c};out+=cur;return out.map{it.trim().removeSurrounding("\"").replace("\"\"","\"")}}
}
