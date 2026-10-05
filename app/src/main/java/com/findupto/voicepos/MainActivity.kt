package com.findupto.voicepos

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
    private val heard=mutableStateOf("")
    private val voiceStatus=mutableStateOf("Power Voice ready")
    private val scanStatus=mutableStateOf("")
    private val logoVersion=mutableStateOf(0)
    private var recognizer:SpeechRecognizer?=null
    private var listening=false
    private var voiceAttempt=0
    private val voiceLocales=listOf("en-PK","en-US","ur-PK")
    private val voiceHandler by lazy{android.os.Handler(mainLooper)}
    private val exportMenuLauncher=registerForActivityResult(CreateDocument("text/csv")){uri->uri?.let{writeMenuCsv(it)}}
    private val importMenuLauncher=registerForActivityResult(OpenDocument()){uri->uri?.let{readMenuCsv(it)}}
    private val logoLauncher=registerForActivityResult(OpenDocument()){uri->uri?.let{saveLogo(it)}}
    private val scanLauncher=registerForActivityResult(OpenDocument()){uri->uri?.let{scanMenuDocument(it)}}
    private val backupExportLauncher=registerForActivityResult(CreateDocument("application/json")){uri->uri?.let{contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{w->w.write(store.exportBackup())};scanStatus.value="Full business backup exported"}}
    private val backupImportLauncher=registerForActivityResult(OpenDocument()){uri->uri?.let{runCatching{val raw=contentResolver.openInputStream(uri)?.bufferedReader()?.use{r->r.readText()}?:"";store.importBackup(raw);scanStatus.value="Business data restored successfully — restart the app"}.onFailure{scanStatus.value="Backup import failed: ${it.message}"}}}
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){voiceStatus.value=if(canUseVoice())"Power Voice ready" else "Voice service unavailable"}

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);StoreMigration.migrate(this);store=Store(this);printer=PrinterManager(this,store)
        val ps=mutableListOf(Manifest.permission.RECORD_AUDIO)
        if(Build.VERSION.SDK_INT<31)ps+=Manifest.permission.ACCESS_FINE_LOCATION else{ps+=Manifest.permission.BLUETOOTH_SCAN;ps+=Manifest.permission.BLUETOOTH_CONNECT}
        permissions.launch(ps.toTypedArray())
        setContent{VoicePosTheme{PosApp(store,printer,heard.value,voiceStatus.value,logoVersion.value,{heard.value=""},::listen,::openSpeechSettings,{exportMenuLauncher.launch("voice-pos-menu.csv")},{importMenuLauncher.launch(arrayOf("text/csv","text/comma-separated-values","text/plain"))},{logoLauncher.launch(arrayOf("image/png","image/jpeg","image/webp"))},{scanLauncher.launch(arrayOf("image/*","application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","text/csv","text/plain"))},{backupExportLauncher.launch("voice-pos-business-backup.json")},{backupImportLauncher.launch(arrayOf("application/json","text/json"))})}}
    }

    private fun canUseVoice()=SpeechRecognizer.isRecognitionAvailable(this)
    private fun chooseRawVoiceResults(r:Bundle?):String{val candidates=r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().map{it.trim()}.filter{it.isNotBlank()};if(candidates.isEmpty())return "";val menu=store.menu();return candidates.maxWithOrNull(compareBy<String>{VoiceCommandEngine.parse(it,menu).size}.thenBy{candidate->menu.maxOfOrNull{m->VoiceCommandEngine.parse(candidate,listOf(m)).size}?:0}.thenByDescending{it.length})?:candidates.first()}
    private fun improveVoice(raw:String){if(raw.isBlank()){heard.value="";voiceStatus.value="No speech detected — tap VOICE and speak the full order";return};val direct=VoiceCommandEngine.parse(raw,store.menu());if(direct.isNotEmpty()){heard.value=raw;voiceStatus.value="Command understood ✓";return};voiceStatus.value="Understanding…";Thread{val ai=AiEngine.rewriteVoiceBlocking(raw,store.menu());val normalized=ai?.takeIf{it.isNotBlank()};val parsed=normalized?.let{VoiceCommandEngine.parse(it,store.menu())}.orEmpty();runOnUiThread{if(parsed.isNotEmpty()){heard.value=normalized!!;voiceStatus.value="AI command understood ✓"}else{heard.value="";voiceStatus.value="I heard: $raw — no safe POS action matched"}}}.start()}
    private fun voiceIntent(locale:String,offline:Boolean=false):Intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,locale);putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,locale);putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,10);putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,packageName);putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,700);putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,5000);putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,2600);putExtra("android.speech.extra.PREFER_OFFLINE",offline);if(Build.VERSION.SDK_INT>=23)putExtra("android.speech.extra.BIASING_STRINGS",store.menu().take(200).flatMap{listOf(it.name,it.variant,it.size)}.filter{it.isNotBlank()}.distinct().toCollection(java.util.ArrayList()))}
    private fun listen(){if(!canUseVoice()){voiceStatus.value="Android voice service unavailable";openSpeechSettings();return};if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO));return};voiceAttempt=0;startVoiceAttempt()}
    private fun startVoiceAttempt(){recognizer?.destroy();listening=false;val locale=voiceLocales.getOrElse(voiceAttempt.coerceAtMost(voiceLocales.lastIndex)){"en-US"};voiceStatus.value="Power Voice • Listening…";recognizer=SpeechRecognizer.createSpeechRecognizer(this).apply{setRecognitionListener(object:RecognitionListener{override fun onReadyForSpeech(p:Bundle?){listening=true;voiceStatus.value="Power Voice • Listening…"};override fun onBeginningOfSpeech(){listening=true};override fun onRmsChanged(v:Float){};override fun onBufferReceived(b:ByteArray?){ };override fun onEndOfSpeech(){listening=false;voiceStatus.value="Understanding…"};override fun onError(e:Int){listening=false;voiceAttempt++;if(voiceAttempt<voiceLocales.size){voiceStatus.value="Voice retry ${voiceAttempt+1}/${voiceLocales.size}…";voiceHandler.postDelayed({startVoiceAttempt()},350)}else if(Build.VERSION.SDK_INT>=31&&SpeechRecognizer.isOnDeviceRecognitionAvailable(this@MainActivity)){startDeviceVoice()}else{voiceStatus.value="Voice service failed. Check internet and microphone permissions, then tap VOICE again"}};override fun onResults(r:Bundle?){voiceAttempt=0;improveVoice(chooseRawVoiceResults(r))};override fun onPartialResults(r:Bundle?){ };override fun onEvent(t:Int,p:Bundle?){ }})};runCatching{recognizer?.startListening(voiceIntent(locale,false))}.onFailure{voiceAttempt++;if(voiceAttempt<voiceLocales.size)voiceHandler.postDelayed({startVoiceAttempt()},350)else voiceStatus.value="Could not start voice recognition"}}
    private fun startDeviceVoice(){recognizer?.destroy();voiceStatus.value="Power Voice • Offline listening…";recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply{setRecognitionListener(object:RecognitionListener{override fun onReadyForSpeech(p:Bundle?){listening=true};override fun onBeginningOfSpeech(){listening=true};override fun onRmsChanged(v:Float){};override fun onBufferReceived(b:ByteArray?){ };override fun onEndOfSpeech(){listening=false;voiceStatus.value="Understanding…"};override fun onError(e:Int){voiceStatus.value="Voice unavailable on this device. Use online speech or enable a speech service."};override fun onResults(r:Bundle?){improveVoice(chooseRawVoiceResults(r))};override fun onPartialResults(r:Bundle?){ };override fun onEvent(t:Int,p:Bundle?){ }})};runCatching{recognizer?.startListening(voiceIntent("en-US",true))}.onFailure{voiceStatus.value="Offline voice unavailable — use online speech"}}
    private fun openSpeechSettings(){runCatching{startActivity(Intent("com.android.settings.SPEECH_RECOGNITION_SETTINGS"))}.onFailure{startActivity(Intent(Settings.ACTION_SETTINGS))}}

    private fun saveLogo(uri:Uri){runCatching{
        runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        val source=contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it)}?:error("image could not be decoded")
        require(source.width>0&&source.height>0){"invalid image"}
        val max=900;val scale=minOf(1f,max.toFloat()/source.width,max.toFloat()/source.height)
        val scaled=if(scale<1f)Bitmap.createScaledBitmap(source,(source.width*scale).toInt().coerceAtLeast(1),(source.height*scale).toInt().coerceAtLeast(1),true)else source
        val f=File(filesDir,"branding/company_logo.png");f.parentFile?.mkdirs();FileOutputStream(f).use{out->require(scaled.compress(Bitmap.CompressFormat.PNG,100,out)){"PNG encoding failed"}}
        require(f.exists()&&f.length()>0){"logo file was not saved"}
        store.saveProfile(store.profile().copy(logoPath=f.absolutePath,saleLogoEnabled=true,kitchenLogoEnabled=false));logoVersion.value++;scanStatus.value="Company logo uploaded ✓ (${f.length()/1024} KB)";voiceStatus.value="Logo saved successfully ✓"
    }.onFailure{scanStatus.value="Could not upload logo: ${it.message}";voiceStatus.value="Logo upload failed — ${it.message}"}}

    private fun writeMenuCsv(uri:Uri){val csv=buildString{appendLine("name,variant,size,price");store.menu().forEach{appendLine(listOf(it.name,it.variant,it.size,it.price).joinToString(","){"\"${it.toString().replace("\"","\"\"")}\""})}};contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(csv)}}
    private fun readMenuCsv(uri:Uri){val text=contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}?:return;val imported=text.lines().drop(1).mapNotNull{val p=parseCsvLine(it);if(p.size>=4)p[0].takeIf{n->n.isNotBlank()}?.let{n->p[3].toDoubleOrNull()?.let{pr->MenuItem(System.currentTimeMillis(),n,pr,p[1],p[2])}}else null};if(imported.isNotEmpty()){store.saveMenu((store.menu()+imported).distinctBy{it.name.lowercase()+"|"+it.variant.lowercase()+"|"+it.size.lowercase()+"|"+it.price});scanStatus.value="${imported.size} menu products imported"}}
    private fun readDocxText(uri:Uri):String=buildString{contentResolver.openInputStream(uri)?.use{input->ZipInputStream(input).use{zip->while(true){val e=zip.nextEntry?:break;if(e.name=="word/document.xml"){val raw=zip.readBytes().toString(Charsets.UTF_8);append(raw.replace(Regex("<w:tab[^>]*/>")," ").replace(Regex("</w:p>"),"\n").replace(Regex("<[^>]+>")," ").replace(Regex("\\s+")," ").trim())}}}}}
    private fun scanMenuDocument(uri:Uri){scanStatus.value="Reading menu… detecting product / variant / size / price rows";val mime=contentResolver.getType(uri).orEmpty().lowercase(Locale.US);if(mime.contains("wordprocessingml")||uri.toString().lowercase().contains(".docx")){runCatching{val text=readDocxText(uri);val clean=MenuScanParser.parse(text);if(clean.isNotEmpty()){store.saveMenu((store.menu()+clean).distinctBy{it.name.lowercase()+"|"+it.variant.lowercase()+"|"+it.size.lowercase()+"|"+it.price});scanStatus.value="DOCX: ${clean.size} structured menu variants imported ✓"}else scanStatus.value="DOCX opened, but no confident product + price rows were found"}.onFailure{scanStatus.value="DOCX read failed: ${it.message}"};return}
        val images=mutableListOf<Bitmap>();runCatching{if(mime=="application/pdf"||uri.toString().lowercase().endsWith(".pdf")){contentResolver.openFileDescriptor(uri,"r")!!.use{fd->PdfRenderer(fd).use{r->for(i in 0 until r.pageCount.coerceAtMost(50)){r.openPage(i).use{page->val scale=3;val b=Bitmap.createBitmap(page.width*scale,page.height*scale,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);images+=b}}}}}else{contentResolver.openInputStream(uri)?.use{val b=BitmapFactory.decodeStream(it);if(b!=null)images+=b}}}.onFailure{scanStatus.value="Could not open menu: ${it.message}";return}
        if(images.isEmpty()){scanStatus.value="No readable pages found";return}
        val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);val barcode=BarcodeScanning.getClient();var remaining=images.size;val found=mutableListOf<MenuItem>();fun finish(){remaining--;if(remaining>0)return;recognizer.close();barcode.close();val clean=found.distinctBy{it.name.lowercase()+"|"+it.variant.lowercase()+"|"+it.size.lowercase()+"|"+it.price};if(clean.isNotEmpty()){store.saveMenu((store.menu()+clean).distinctBy{it.name.lowercase()+"|"+it.variant.lowercase()+"|"+it.size.lowercase()+"|"+it.price});scanStatus.value="Scanner imported ${clean.size} structured menu variants ✓"}else scanStatus.value="No confident product + price rows found — try a clearer image or PDF"};images.forEach{bmp->val img=InputImage.fromBitmap(bmp,0);recognizer.process(img).addOnSuccessListener{result->found+=MenuScanParser.parse(result.text)}.addOnCompleteListener{barcode.process(img).addOnCompleteListener{finish()}}}}
    private fun parseCsvLine(s:String):List<String>{val out=mutableListOf<String>();var cur="";var q=false;for(c in s){if(c=='\"')q=!q else if(c==','&&!q){out+=cur;cur=""}else cur+=c};out+=cur;return out.map{it.trim().removeSurrounding("\"").replace("\"\"","\"")}}
}
