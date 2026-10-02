package com.findupto.voicepos

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.runtime.mutableStateOf
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private lateinit var printer: PrinterManager
    private val heard=mutableStateOf("")
    private val voiceStatus=mutableStateOf("Power Voice ready")
    private val scanStatus=mutableStateOf("")
    private var recognizer:SpeechRecognizer?=null
    private var listening=false
    private var recognitionMode=1
    private val voiceHandler by lazy{android.os.Handler(mainLooper)}
    private val exportMenuLauncher=registerForActivityResult(CreateDocument("text/csv")){uri->uri?.let{writeMenuCsv(it)}}
    private val importMenuLauncher=registerForActivityResult(OpenDocument()){uri->uri?.let{readMenuCsv(it)}}
    private val logoLauncher=registerForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let{saveLogo(it)}}
    private val scanLauncher=registerForActivityResult(OpenDocument()){uri->uri?.let{scanMenuDocument(it)}}
    private val backupExportLauncher=registerForActivityResult(CreateDocument("application/json")){uri->uri?.let{contentResolver.openOutputStream(it)?.bufferedWriter()?.use{w->w.write(store.exportBackup())};scanStatus.value="Full business backup exported"}}
    private val backupImportLauncher=registerForActivityResult(OpenDocument()){uri->uri?.let{runCatching{val raw=contentResolver.openInputStream(it)?.bufferedReader()?.use{r->r.readText()}?:"";store.importBackup(raw);scanStatus.value="Business data restored successfully — restart the app"}.onFailure{scanStatus.value="Backup import failed: ${it.message}"}}}
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){voiceStatus.value=if(canUseVoice())"Power Voice ready" else "Voice service unavailable"}

    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);store=Store(this);printer=PrinterManager(this);val ps=mutableListOf(Manifest.permission.RECORD_AUDIO);if(Build.VERSION.SDK_INT<31)ps+=Manifest.permission.ACCESS_FINE_LOCATION else{ps+=Manifest.permission.BLUETOOTH_SCAN;ps+=Manifest.permission.BLUETOOTH_CONNECT};permissions.launch(ps.toTypedArray());setContent{VoicePosTheme{PosApp(store,printer,heard.value,voiceStatus.value,{heard.value=""},::listen,::openSpeechSettings,{exportMenuLauncher.launch("voice-pos-menu.csv")},{importMenuLauncher.launch(arrayOf("text/csv","text/comma-separated-values","text/plain"))},{logoLauncher.launch("image/*")},{scanLauncher.launch(arrayOf("image/*","application/pdf"))},{backupExportLauncher.launch("voice-pos-business-backup.json")},{backupImportLauncher.launch(arrayOf("application/json","text/json"))})}}}
    private fun canUseVoice()=SpeechRecognizer.isRecognitionAvailable(this)
    private fun listen(){if(!canUseVoice()){voiceStatus.value="Android voice service unavailable";openSpeechSettings();return};if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO));return};recognizer?.destroy();listening=false;val useDevice=Build.VERSION.SDK_INT>=31&&SpeechRecognizer.isOnDeviceRecognitionAvailable(this);recognitionMode=if(useDevice)1 else 0;recognizer=(if(useDevice)SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)).apply{setRecognitionListener(object:RecognitionListener{override fun onReadyForSpeech(p:Bundle?){listening=true;voiceStatus.value=if(recognitionMode==1)"Power Voice • on-device • Listening…" else "Voice • Listening…"};override fun onBeginningOfSpeech(){listening=true};override fun onRmsChanged(v:Float){};override fun onBufferReceived(b:ByteArray?){ };override fun onEndOfSpeech(){listening=false;voiceStatus.value="Understanding…"};override fun onError(e:Int){listening=false;if(recognitionMode==1&&e!=SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS){voiceStatus.value="On-device voice unavailable — retrying online…";voiceHandler.postDelayed({startOnlineVoice()},350)}else voiceStatus.value="Voice error — try again"};override fun onResults(r:Bundle?){heard.value=r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty();voiceStatus.value=if(heard.value.isBlank())"No voice heard" else "Command understood"};override fun onPartialResults(r:Bundle?){ };override fun onEvent(t:Int,p:Bundle?){}})};val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault().toLanguageTag());putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,8);putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,800);putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,5000)};recognizer?.startListening(i)}
    private fun startOnlineVoice(){recognizer?.destroy();recognitionMode=0;recognizer=SpeechRecognizer.createSpeechRecognizer(this).apply{setRecognitionListener(object:RecognitionListener{override fun onReadyForSpeech(p:Bundle?){voiceStatus.value="Voice • Listening…"};override fun onBeginningOfSpeech(){};override fun onRmsChanged(v:Float){};override fun onBufferReceived(b:ByteArray?){};override fun onEndOfSpeech(){voiceStatus.value="Understanding…"};override fun onError(e:Int){voiceStatus.value="Voice failed — try again"};override fun onResults(r:Bundle?){heard.value=r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty();voiceStatus.value="Command understood"};override fun onPartialResults(r:Bundle?){};override fun onEvent(t:Int,p:Bundle?){}})};recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault().toLanguageTag());putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,8);putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)})}
    private fun openSpeechSettings(){runCatching{startActivity(Intent("com.android.settings.SPEECH_RECOGNITION_SETTINGS"))}.onFailure{startActivity(Intent(Settings.ACTION_SETTINGS))}}
    private fun saveLogo(uri:Uri){runCatching{val f=File(filesDir,"branding/company_logo.png");f.parentFile?.mkdirs();contentResolver.openInputStream(uri)?.use{input->FileOutputStream(f).use{output->input.copyTo(output)}}?:error("empty image");store.saveProfile(store.profile().copy(logoPath=f.absolutePath));scanStatus.value="Company logo uploaded successfully"}.onFailure{scanStatus.value="Could not upload logo: ${it.message}"}}
    private fun writeMenuCsv(uri:Uri){val csv=buildString{appendLine("name,variant,size,price");store.menu().forEach{appendLine(listOf(it.name,it.variant,it.size,it.price).joinToString(","){"\"${it.toString().replace("\"","\"\"")}\""})}};contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(csv)}}
    private fun readMenuCsv(uri:Uri){val text=contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}?:return;val imported=text.lines().drop(1).mapNotNull{val p=parseCsvLine(it);if(p.size>=4)p[0].takeIf{n->n.isNotBlank()}?.let{n->p[3].toDoubleOrNull()?.let{pr->MenuItem(System.currentTimeMillis(),n,pr,p[1],p[2])}}else null};if(imported.isNotEmpty()){store.saveMenu((store.menu()+imported).distinctBy{it.name.lowercase()+"|"+it.variant.lowercase()+"|"+it.size.lowercase()+"|"+it.price});scanStatus.value="${imported.size} menu products imported"}}
    private fun scanMenuDocument(uri:Uri){scanStatus.value="Scanning document… extracting product rows";val images=mutableListOf<Bitmap>();runCatching{if(contentResolver.getType(uri)=="application/pdf"||uri.toString().lowercase().endsWith(".pdf")){val fd=contentResolver.openFileDescriptor(uri,"r")!!;PdfRenderer(fd).use{r->for(i in 0 until r.pageCount.coerceAtMost(30)){r.openPage(i).use{page->val b=Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888);page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);images+=b}}};fd.close()}else{contentResolver.openInputStream(uri)?.use{val b=android.graphics.BitmapFactory.decodeStream(it);if(b!=null)images+=b}}}.onFailure{scanStatus.value="Could not open document: ${it.message}";return};if(images.isEmpty()){scanStatus.value="No readable pages found";return};val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);val barcode=BarcodeScanning.getClient();var remaining=images.size;val found=mutableListOf<MenuItem>();fun finish(){remaining--;if(remaining>0)return;recognizer.close();barcode.close();val clean=found.distinctBy{it.name.lowercase()+"|"+it.variant.lowercase()+"|"+it.size.lowercase()+"|"+it.price};if(clean.isNotEmpty()){store.saveMenu((store.menu()+clean).distinctBy{it.name.lowercase()+"|"+it.variant.lowercase()+"|"+it.size.lowercase()+"|"+it.price});scanStatus.value="Scanner found ${clean.size} product variants and added them to Menu"}else scanStatus.value="No confident product + price rows found"};images.forEach{bmp->val img=InputImage.fromBitmap(bmp,0);recognizer.process(img).addOnSuccessListener{result->found+=parseProducts(result.text)}.addOnCompleteListener{barcode.process(img).addOnCompleteListener{finish()}}}}

    private fun parseProducts(text:String):List<MenuItem>{
        val out=mutableListOf<MenuItem>();val ignored=Regex("(?i)^(menu|food|items?|products?|description|name|price|qty|quantity|total|subtotal|sub total|tax|gst|discount|amount|bill|invoice|address|phone|contact|thank|thanks|www|http).*");val priceRx=Regex("(?i)(?:rs\\.?|pkr\\s*)?\\s*(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)\\s*(?:rs|pkr)?$")
        text.lines().map{it.replace("\\t"," ").replace(Regex("[|]")," ").replace(Regex("\\s+")," ").trim()}.filter{it.length in 2..120&&!ignored.matches(it)}.forEach{line->
            val m=priceRx.find(line)?:return@forEach;val price=m.groupValues[1].replace(",","").toDoubleOrNull()?:return@forEach;if(price<=0||price>=10000000)return@forEach;var left=line.substring(0,m.range.first).trim().replace(Regex("^[•·*#\\-–—]+\\s*"),"").trim();if(left.isBlank()||left.length<2)return@forEach
            val tokens=left.split(" ");var name=left;var variant="";var size="";val sizeRx=Regex("(?i)^(small|medium|large|regular|jumbo|mini|family|single|double|triple|half|full|\\d+(?:ml|l|g|kg|oz|inch|in))$");val variantRx=Regex("(?i)^(small|medium|large|regular|classic|special|spicy|mild|hot|cold|grilled|fried|cheese|chicken|beef|mutton|veg|vegetable|single|double|family|combo|deal|pack|box|plate|cup|glass)$")
            if(tokens.size>=2){val tail=tokens.last();if(sizeRx.matches(tail)){size=tail;name=tokens.dropLast(1).joinToString(" ")}else if(variantRx.matches(tail)){variant=tail;name=tokens.dropLast(1).joinToString(" ");if(tokens.size>=3&&sizeRx.matches(tokens[tokens.size-2])){size=tokens[tokens.size-2];name=tokens.dropLast(2).joinToString(" ")}}}
            if(name.length<2||name.matches(Regex("(?i)^(total|subtotal|tax|discount|amount|price|bill)$")))return@forEach
            out+=MenuItem(System.nanoTime()+out.size,name,price,variant,size)
        }
        return out.distinctBy{it.name.lowercase()+"|"+it.variant.lowercase()+"|"+it.size.lowercase()+"|"+it.price}
    }
    private fun parseCsvLine(s:String):List<String>{val out=mutableListOf<String>();var cur="";var q=false;for(c in s){if(c=='\"')q=!q else if(c==','&&!q){out+=cur;cur=""}else cur+=c};out+=cur;return out.map{it.trim().removeSurrounding("\"").replace("\"\"","\"")}}
}