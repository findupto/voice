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
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import android.Manifest
import android.os.Build
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
    private val bluetoothPermissionLauncher=registerForActivityResult(RequestMultiplePermissions()){ grants -> if(grants.values.any{it}) printer.refresh() }
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
        val bluetoothPermissions=if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if(bluetoothPermissions.any{checkSelfPermission(it)!=android.content.pm.PackageManager.PERMISSION_GRANTED}) bluetoothPermissionLauncher.launch(bluetoothPermissions)
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

    private fun saveLogo(uri:Uri){runCatching{
        runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        val source=contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it)}?:error("image could not be decoded")
        require(source.width>0&&source.height>0){"invalid image"}
        val max=900;val scale=minOf(1f,max.toFloat()/source.width,max.toFloat()/source.height)
        val scaled=if(scale<1f)Bitmap.createScaledBitmap(source,(source.width*scale).toInt().coerceAtLeast(1),(source.height*scale).toInt().coerceAtLeast(1),true)else source
        val f=File(filesDir,"branding/company_logo.png");f.parentFile?.mkdirs();FileOutputStream(f).use{out->require(scaled.compress(Bitmap.CompressFormat.PNG,100,out)){"PNG encoding failed"}}
        require(f.exists()&&f.length()>0){"logo file was not saved"}
        store.saveProfile(store.profile().copy(logoPath=f.absolutePath,saleLogoEnabled=true,kitchenLogoEnabled=false));logoVersion.value++;scanStatus.value="Company logo uploaded ✓ (${f.length()/1024} KB)";scanStatus.value="Logo saved successfully ✓"
    }.onFailure{scanStatus.value="Could not upload logo: ${it.message}";scanStatus.value="Logo upload failed — ${it.message}"}}

    private fun writeMenuCsv(uri:Uri){val csv=buildString{appendLine("name,variant,size,price");store.menu().forEach{appendLine(listOf(it.name,it.variant,it.size,it.price).joinToString(","){"\"${it.toString().replace("\"","\"\"")}\""})}};contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(csv)}}
    private fun readMenuCsv(uri:Uri){runCatching{val text=contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}?:error("Could not read CSV");val lines=text.removePrefix("\uFEFF").lines().filter{it.isNotBlank()};require(lines.isNotEmpty()){"CSV is empty"};val first=parseCsvLine(lines.first()).map{it.trim().lowercase()};val hasHeader=first.any{it in setOf("name","product","product name")}||first.any{it=="price"};val rows=if(hasHeader)lines.drop(1)else lines;val existing=store.menu().toMutableList();var added=0;var updated=0;rows.forEach{line->val p=parseCsvLine(line);if(p.size>=2){val name=p[0].trim();val priceIndex=if(p.size>=4)3 else 1;val price=p.getOrNull(priceIndex)?.replace(",","")?.trim()?.toDoubleOrNull();if(name.isNotBlank()&&price!=null&&price>=0){val variant=p.getOrNull(if(p.size>=4)1 else 2).orEmpty().trim();val size=p.getOrNull(if(p.size>=4)2 else 3).orEmpty().trim();val idx=existing.indexOfFirst{it.name.equals(name,true)&&it.variant.equals(variant,true)&&it.size.equals(size,true)};if(idx>=0){existing[idx]=existing[idx].copy(price=price);updated++}else{existing.add(MenuItem(System.currentTimeMillis()+added,name,price,variant,size));added++}}}};require(added+updated>0){"No valid products found. Use columns name,variant,size,price"};store.saveMenu(existing);scanStatus.value="Bulk import complete: $added added, $updated updated"} .onFailure{scanStatus.value="CSV import failed: ${it.message}"}}
    private fun readDocxText(uri:Uri):String=buildString{contentResolver.openInputStream(uri)?.use{input->ZipInputStream(input).use{zip->while(true){val e=zip.nextEntry?:break;if(e.name=="word/document.xml"){val raw=zip.readBytes().toString(Charsets.UTF_8);append(raw.replace(Regex("<w:tab[^>]*/>")," ").replace(Regex("</w:p>"),"\n").replace(Regex("<[^>]+>")," ").replace(Regex("\\s+")," ").trim())}}}}}
    private fun scanMenuDocument(uri:Uri){scanStatus.value="Reading menu… detecting product / variant / size / price rows";val mime=contentResolver.getType(uri).orEmpty().lowercase(Locale.US);if(mime.contains("wordprocessingml")||uri.toString().lowercase().contains(".docx")){runCatching{val text=readDocxText(uri);val clean=MenuScanParser.parse(text);if(clean.isNotEmpty()){store.saveMenu((store.menu()+clean).distinctBy{it.name.lowercase()+"|"+it.variant.lowercase()+"|"+it.size.lowercase()+"|"+it.price});scanStatus.value="DOCX: ${clean.size} structured menu variants imported ✓"}else scanStatus.value="DOCX opened, but no confident product + price rows were found"}.onFailure{scanStatus.value="DOCX read failed: ${it.message}"};return}
        val images=mutableListOf<Bitmap>();runCatching{if(mime=="application/pdf"||uri.toString().lowercase().endsWith(".pdf")){contentResolver.openFileDescriptor(uri,"r")!!.use{fd->PdfRenderer(fd).use{r->for(i in 0 until r.pageCount.coerceAtMost(50)){r.openPage(i).use{page->val scale=3;val b=Bitmap.createBitmap(page.width*scale,page.height*scale,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);images+=b}}}}}else{contentResolver.openInputStream(uri)?.use{val b=BitmapFactory.decodeStream(it);if(b!=null)images+=b}}}.onFailure{scanStatus.value="Could not open menu: ${it.message}";return}
        if(images.isEmpty()){scanStatus.value="No readable pages found";return}
        val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);val barcode=BarcodeScanning.getClient();var remaining=images.size;val found=mutableListOf<MenuItem>();fun finish(){remaining--;if(remaining>0)return;recognizer.close();barcode.close();val clean=found.distinctBy{it.name.lowercase()+"|"+it.variant.lowercase()+"|"+it.size.lowercase()+"|"+it.price};if(clean.isNotEmpty()){store.saveMenu((store.menu()+clean).distinctBy{it.name.lowercase()+"|"+it.variant.lowercase()+"|"+it.size.lowercase()+"|"+it.price});scanStatus.value="Scanner imported ${clean.size} structured menu variants ✓"}else scanStatus.value="No confident product + price rows found — try a clearer image or PDF"};images.forEach{bmp->val img=InputImage.fromBitmap(bmp,0);recognizer.process(img).addOnSuccessListener{result->found+=MenuScanParser.parse(result.text)}.addOnCompleteListener{barcode.process(img).addOnCompleteListener{finish()}}}}
    override fun onDestroy() {
        if (::printer.isInitialized) printer.close()
        super.onDestroy()
    }

    private fun parseCsvLine(s:String):List<String>{val out=mutableListOf<String>();var cur="";var q=false;for(c in s){if(c=='\"')q=!q else if(c==','&&!q){out+=cur;cur=""}else cur+=c};out+=cur;return out.map{it.trim().removeSurrounding("\"").replace("\"\"","\"")}}
}
