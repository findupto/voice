package com.findupto.voicepos

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

fun money(v:Double)="Rs "+String.format(Locale.US,"%,.0f",v)
fun date(v:Long)=SimpleDateFormat("dd MMM yyyy, hh:mm a",Locale.US).format(Date(v))
fun receipt(s:Sale,p:CompanyProfile,t:ReceiptTheme):ByteArray{
    val o=ByteArrayOutputStream();fun w(s:String){o.write(s.toByteArray(Charsets.UTF_8))}
    o.write(byteArrayOf(0x1B,0x40));o.write(byteArrayOf(0x1B,0x45,0x01));o.write(byteArrayOf(0x1B,0x61,if(t==ReceiptTheme.MODERN)0 else 1))
    w(p.name.take(32)+"\n");o.write(byteArrayOf(0x1B,0x45,0))
    if(p.address.isNotBlank())w(p.address.take(42)+"\n");if(p.phone.isNotBlank())w(p.phone.take(42)+"\n")
    w("SALE #"+s.id+"\n"+date(s.time)+"\n------------------------------------------\n")
    s.items.forEach{w(it.qty.toString()+" x "+it.name.take(30)+"\n  "+money(it.price)+" each    "+money(it.total)+"\n")}
    w("------------------------------------------\n");o.write(byteArrayOf(0x1B,0x45,0x01));w("TOTAL                         "+money(s.total)+"\n");o.write(byteArrayOf(0x1B,0x45,0))
    if(p.footer.isNotBlank())w("\n"+p.footer.take(42)+"\n");w("\n\n\n");return o.toByteArray()
}
fun startDay():Long=Calendar.getInstance().apply{set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)}.timeInMillis
fun inRange(t:Long,f:Int):Boolean{val since=when(f){0->startDay();1->System.currentTimeMillis()-7*86400000L;2->System.currentTimeMillis()-30*86400000L;else->0L};return t>=since}
