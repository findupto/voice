package com.findupto.voicepos

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EventPlanningDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("voice_event_plans", 0) }
    val savedPlans = remember { mutableStateListOf<JSONObject>().apply {
        runCatching { val a=JSONArray(prefs.getString("plans","[]")); for(i in 0 until a.length()) add(a.getJSONObject(i)) }
    } }
    var eventId by remember { mutableStateOf(0L) }
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Birthday") }
    var customer by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var venue by remember { mutableStateOf("") }
    var guests by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var charges by remember { mutableStateOf("Food & catering|0\nDecoration|0\nVenue|0\nPhotography|0\nOther|0") }
    var paid by remember { mutableStateOf("0") }
    var bookingStatus by remember { mutableStateOf("DRAFT") }
    var message by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("UPCOMING") }
    val currency = remember { NumberFormat.getNumberInstance(Locale("en","PK")) }
    val rows = charges.lines().mapNotNull { line ->
        val p=line.split("|",limit=2); val amount=p.getOrNull(1)?.trim()?.toDoubleOrNull()
        if(p.size==2 && p[0].isNotBlank() && amount!=null && amount>=0) p[0].trim() to amount else null
    }
    val total = rows.sumOf { it.second }
    val paidValue = paid.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    val balance = (total-paidValue).coerceAtLeast(0.0)

    fun persist() { prefs.edit().putString("plans",JSONArray().apply{savedPlans.forEach{put(it)}}.toString()).apply() }
    fun resetForm() {
        eventId=0L; title=""; category="Birthday"; customer=""; phone=""; date=""; venue=""; guests=""
        notes=""; charges="Food & catering|0\nDecoration|0\nVenue|0\nPhotography|0\nOther|0"
        paid="0"; bookingStatus="DRAFT"; message="New event ready."
    }
    fun saveEvent() {
        if(title.isBlank()){message="Enter an event name.";return}
        if(date.isBlank()){message="Enter the event date (YYYY-MM-DD).";return}
        if(paidValue>total){message="Advance/payment cannot exceed the current event estimate.";return}
        val id=if(eventId==0L)System.currentTimeMillis() else eventId
        val record=JSONObject().apply {
            put("id",id);put("title",title.trim());put("category",category);put("customer",customer.trim())
            put("phone",phone.trim());put("date",date.trim());put("venue",venue.trim());put("guests",guests.trim())
            put("notes",notes);put("charges",charges);put("paid",paidValue);put("status",bookingStatus)
            put("updatedAt",System.currentTimeMillis())
        }
        val index=savedPlans.indexOfFirst{it.optLong("id")==id}
        if(index>=0)savedPlans[index]=record else savedPlans.add(0,record)
        eventId=id;persist();message="Event saved successfully."
    }
    fun load(record:JSONObject) {
        eventId=record.optLong("id");title=record.optString("title");category=record.optString("category","Birthday")
        customer=record.optString("customer");phone=record.optString("phone");date=record.optString("date")
        venue=record.optString("venue");guests=record.optString("guests");notes=record.optString("notes")
        charges=record.optString("charges");paid=record.optString("paid","0");bookingStatus=record.optString("status","DRAFT")
        message="Event loaded for editing."
    }
    fun changeStatus(record:JSONObject,status:String) {
        record.put("status",status);record.put("updatedAt",System.currentTimeMillis());persist();message="Event status updated: $status"
        if(record.optLong("id")==eventId)bookingStatus=status
    }
    fun isPast(record:JSONObject):Boolean {
        val raw=record.optString("date")
        val parsed=runCatching{SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{isLenient=false}.parse(raw)?.time?:Long.MAX_VALUE}.getOrDefault(Long.MAX_VALUE)
        return parsed<Long.valueOf(SimpleDateFormat("yyyyMMdd",Locale.US).format(Date())).toString().toLongOrNull()?.let{runCatching{SimpleDateFormat("yyyyMMdd",Locale.US).parse(it.toString())!!.time}.getOrNull()} ?: System.currentTimeMillis()
    }
    val visible=savedPlans.filter { record ->
        val status=record.optString("status","DRAFT")
        when(section) {
            "HISTORY" -> status=="COMPLETED" || status=="CANCELLED" || runCatching { SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(record.optString("date"))?.before(Date())==true }.getOrDefault(false)
            "UPCOMING" -> status!="COMPLETED" && status!="CANCELLED" && !runCatching { SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(record.optString("date"))?.before(Date())==true }.getOrDefault(false)
            else -> true
        }
    }.sortedBy { it.optString("date") }

    fun shareProposal() {
        if(title.isBlank()||date.isBlank()){message="Enter event name and date first.";return}
        runCatching {
            val height=1450
            val bmp=Bitmap.createBitmap(1080,height,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bmp);canvas.drawColor(android.graphics.Color.rgb(246,247,251))
            val card=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.WHITE}
            canvas.drawRoundRect(35f,35f,1045f,1415f,32f,32f,card)
            val accent=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(64,53,125)}
            canvas.drawRoundRect(35f,35f,1045f,330f,32f,32f,accent)
            val white=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.WHITE;textSize=40f;typeface=Typeface.DEFAULT_BOLD}
            val dark=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(34,40,54);textSize=31f;typeface=Typeface.DEFAULT_BOLD}
            val muted=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(105,115,130);textSize=27f}
            val rule=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(225,229,236);strokeWidth=2f}
            canvas.drawText("EVENT PROPOSAL",82f,115f,white)
            canvas.drawText(title.take(36),82f,190f,Paint(white).apply{textSize=48f})
            canvas.drawText("Prepared for: "+customer.ifBlank{"Valued Customer"}.take(40),82f,255f,white)
            var y=405f
            fun line(label:String,value:String){canvas.drawText(label,82f,y,dark);y+=48f;canvas.drawText(value.take(65),100f,y,muted);y+=68f}
            line("EVENT DETAILS",category+" • "+date+" • "+bookingStatus)
            line("VENUE",venue.ifBlank{"To be confirmed"})
            if(guests.isNotBlank())line("EXPECTED GUESTS",guests)
            canvas.drawText("CHARGES (PKR)",82f,y,dark);y+=55f
            rows.take(10).forEach { (name,amount) ->
                canvas.drawText(name.take(32),100f,y,muted)
                val v=currency.format(amount);canvas.drawText(v,970f-muted.measureText(v),y,muted);y+=55f
            }
            canvas.drawLine(82f,y,998f,y,rule);y+=65f
            canvas.drawText("TOTAL ESTIMATE",82f,y,dark)
            val amount="PKR "+currency.format(total);canvas.drawText(amount,998f-dark.measureText(amount),y,dark);y+=65f
            canvas.drawText("Advance paid: PKR "+currency.format(paidValue),82f,y,muted);y+=50f
            canvas.drawText("Balance: PKR "+currency.format(balance),82f,y,dark);y+=70f
            if(notes.isNotBlank()){canvas.drawText("ARRANGEMENTS & NOTES",82f,y,dark);y+=48f;notes.split("\n").take(3).forEach{canvas.drawText(it.take(65),100f,y,muted);y+=42f}}
            canvas.drawText("Estimate only • Final booking subject to management confirmation.",82f,1360f,muted.apply{textSize=22f})
            val uri=MediaStore.Images.Media.insertImage(context.contentResolver,bmp,"Event-"+title.take(24),"Voice POS event proposal")?:error("Could not save proposal")
            bmp.recycle()
            val send=Intent(Intent.ACTION_SEND).apply{type="image/png";putExtra(Intent.EXTRA_STREAM,android.net.Uri.parse(uri));putExtra(Intent.EXTRA_TEXT,"Event proposal: "+title);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            context.startActivity(Intent.createChooser(send,"Share event proposal"));message="Proposal created. Choose WhatsApp or another app."
        }.onFailure{message="Could not create proposal: "+it.message}
    }

    AlertDialog(onDismissRequest=onClose,title={Text("EVENT MANAGEMENT",fontWeight=FontWeight.Bold)},text={
        Column(Modifier.fillMaxWidth().heightIn(max=650.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("UPCOMING","HISTORY","ALL").forEach { tab -> FilterChip(selected=section==tab,onClick={section=tab},label={Text(tab)}) }
            }
            Text("EVENT DETAILS",fontWeight=FontWeight.Bold)
            OutlinedTextField(title,{title=it},label={Text("Event name")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("Birthday","Anniversary","Engagement","Corporate","Private Party","Other").forEach { }
            }
            var categoryMenu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick={categoryMenu=true},modifier=Modifier.fillMaxWidth()) { Text("Category: "+category); Spacer(Modifier.weight(1f)); Text("▼") }
                DropdownMenu(expanded=categoryMenu,onDismissRequest={categoryMenu=false}) {
                    listOf("Birthday","Anniversary","Engagement","Corporate","Private Party","Wedding","Other").forEach { option ->
                        DropdownMenuItem(text={Text(option)},onClick={category=option;categoryMenu=false})
                    }
                }
            }
            OutlinedTextField(customer,{customer=it},label={Text("Customer name")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(phone,{phone=it},label={Text("Phone / WhatsApp")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(date,{date=it},label={Text("Event date (YYYY-MM-DD)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(venue,{venue=it},label={Text("Venue / location")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(guests,{guests=it},label={Text("Expected guests")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Booking status",fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                var statusMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick={statusMenu=true}) { Text(bookingStatus) }
                    DropdownMenu(expanded=statusMenu,onDismissRequest={statusMenu=false}) {
                        listOf("DRAFT","CONFIRMED","IN_PROGRESS","COMPLETED","CANCELLED").forEach { s ->
                            DropdownMenuItem(text={Text(s.replace('_',' '))},onClick={bookingStatus=s;statusMenu=false})
                        }
                    }
                }
            }
            HorizontalDivider()
            Text("ARRANGEMENTS & NOTES",fontWeight=FontWeight.Bold)
            OutlinedTextField(notes,{notes=it},label={Text("Decoration, menu, seating, special requests, timings…")},modifier=Modifier.fillMaxWidth(),minLines=3)
            Text("CHARGES — one item per line: Description|Amount (PKR)",fontWeight=FontWeight.Bold)
            OutlinedTextField(charges,{charges=it},modifier=Modifier.fillMaxWidth(),minLines=4)
            OutlinedTextField(paid,{paid=it.filter{c->c.isDigit()||c=='.'}},label={Text("Advance / payments received (PKR)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("Billing summary",fontWeight=FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Estimated total");Text("PKR "+currency.format(total))}
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Paid");Text("PKR "+currency.format(paidValue))}
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Balance due",fontWeight=FontWeight.Bold);Text("PKR "+currency.format(balance),fontWeight=FontWeight.Bold)}
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Button(onClick=::saveEvent,modifier=Modifier.weight(1f)){Text(if(eventId==0L)"SAVE EVENT" else "UPDATE EVENT")}
                OutlinedButton(onClick=::resetForm,modifier=Modifier.weight(1f)){Text("NEW")}
            }
            OutlinedButton(onClick=::shareProposal,modifier=Modifier.fillMaxWidth()){Text("SHARE PROFESSIONAL EVENT QUOTATION")}
            if(message.isNotBlank())Text(message,color=MaterialTheme.colorScheme.secondary,style=MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("EVENT REGISTER • "+visible.size,fontWeight=FontWeight.Bold)
            if(visible.isEmpty())Text("No events in this section yet.",style=MaterialTheme.typography.bodySmall)
            visible.forEach { record ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(11.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(record.optString("title"),fontWeight=FontWeight.Bold)
                                Text(record.optString("category")+" • "+record.optString("date"),style=MaterialTheme.typography.bodySmall)
                                Text(record.optString("customer")+" • "+record.optString("guests")+" guests",style=MaterialTheme.typography.bodySmall)
                            }
                            AssistChip(onClick={load(record)},label={Text(record.optString("status","DRAFT").replace('_',' '))})
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Total: PKR "+currency.format(runCatching{JSONArray(record.optString("charges").lines().mapNotNull{line->val p=line.split("|",limit=2);p.getOrNull(1)?.toDoubleOrNull()?:0.0}.sum()}.getOrDefault(0.0)),style=MaterialTheme.typography.bodySmall)
                            Text("Paid: PKR "+currency.format(record.optDouble("paid")),style=MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick={load(record)}){Text("EDIT")}
                            TextButton(onClick={changeStatus(record,"CONFIRMED")}){Text("CONFIRM")}
                            TextButton(onClick={changeStatus(record,"COMPLETED")}){Text("COMPLETE")}
                            TextButton(onClick={changeStatus(record,"CANCELLED")}){Text("CANCEL")}
                        }
                        TextButton(onClick={savedPlans.remove(record);persist();message="Event removed."}){Text("DELETE EVENT")}
                    }
                }
            }
        }
    },confirmButton={TextButton(onClick=onClose){Text("CLOSE")}})
}
