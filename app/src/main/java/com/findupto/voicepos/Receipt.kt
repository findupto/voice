package com.findupto.voicepos

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
fun money(v:Double)="Rs "+String.format(Locale.US,"%,.0f",v)
fun date(v:Long)=SimpleDateFormat("dd MMM yyyy, hh:mm a",Locale.US).format(Date(v))
private fun escpos(p:CompanyProfile,title:String,items:List<SaleItem>,total:Double,theme:ReceiptTheme,customerName:String="",customerPhone:String=""):ByteArray{
if(theme==ReceiptTheme.ADVANCED_PREMIUM){
val o=ByteArrayOutputStream(); fun w(s:String){o.write(s.toByteArray(Charsets.UTF_8))}
o.write(byteArrayOf(0x1B,0x40)); o.write(byteArrayOf(0x1B,0x61,0x01)); o.write(byteArrayOf(0x1B,0x45,0x01)); o.write(byteArrayOf(0x1D,0x21,0x11)); w(p.name.take(30)+"\n"); o.write(byteArrayOf(0x1D,0x21,0x00)); o.write(byteArrayOf(0x1B,0x45,0x00))
if(p.address.isNotBlank())w(p.address.take(42)+"\n"); if(p.phone.isNotBlank())w(p.phone.take(42)+"\n")
w("==========================================\n"); o.write(byteArrayOf(0x1B,0x45,0x01)); w(title+"\n"); o.write(byteArrayOf(0x1B,0x45,0x00)); w(date(System.currentTimeMillis())+"\n")
if(customerName.isNotBlank())w("Customer: "+customerName.take(30)+"\n"); if(customerPhone.isNotBlank())w("Phone: "+customerPhone.take(30)+"\n")
w("------------------------------------------\n"); items.forEach{w(it.qty.toString()+"  "+it.name.take(25)+"\n");w("     "+money(it.price)+" x "+it.qty+"       "+money(it.total)+"\n")}
w("------------------------------------------\n"); o.write(byteArrayOf(0x1B,0x45,0x01)); w("TOTAL  "+money(total)+"\n"); o.write(byteArrayOf(0x1B,0x45,0x00)); if(p.footer.isNotBlank())w("\n"+p.footer.take(42)+"\n");w("\n\n\n");return o.toByteArray()}
private fun escpos(p:CompanyProfile,title:String,items:List<SaleItem>,total:Double,theme:ReceiptTheme):ByteArray{val o=ByteArrayOutputStream();fun w(s:String){o.write(s.toByteArray(Charsets.UTF_8))};o.write(byteArrayOf(0x1B,0x40));o.write(byteArrayOf(0x1B,0x61,if(theme==ReceiptTheme.MODERN)0 else 1));o.write(byteArrayOf(0x1B,0x45,0x01));w(p.name.take(32)+"\n");o.write(byteArrayOf(0x1B,0x45,0));if(p.address.isNotBlank())w(p.address.take(42)+"\n");if(p.phone.isNotBlank())w(p.phone.take(42)+"\n");w(title+"\n"+date(System.currentTimeMillis())+"\n------------------------------------------\n");items.forEach{w("${it.qty} x ${it.name.take(30)}\n  ${money(it.price)} each    ${money(it.total)}\n")};w("------------------------------------------\n");o.write(byteArrayOf(0x1B,0x45,0x01));w("TOTAL                         ${money(total)}\n");o.write(byteArrayOf(0x1B,0x45,0));if(p.footer.isNotBlank())w("\n"+p.footer.take(42)+"\n");w("\n\n\n");return o.toByteArray()}
fun customerReceipt(s:Sale,p:CompanyProfile,t:ReceiptTheme)=escpos(p,"SALE #${s.id}",s.items,s.total,t,s.customerName,s.customerPhone)
fun kitchenReceipt(s:PendingSale,p:CompanyProfile,t:ReceiptTheme):ByteArray{val o=ByteArrayOutputStream();fun w(x:String){o.write(x.toByteArray(Charsets.UTF_8))};o.write(byteArrayOf(0x1B,0x40));o.write(byteArrayOf(0x1B,0x61,0x01));o.write(byteArrayOf(0x1B,0x45,0x01));w("KITCHEN\n");o.write(byteArrayOf(0x1B,0x45,0));w("${p.name.take(32)}\nORDER #${s.id}\n${date(s.time)}\n------------------------------------------\n");s.items.forEach{w("${it.qty} x ${it.name.take(34)}\n")};w("\n------------------------------------------\n\n\n");return o.toByteArray()}
fun startDay():Long=Calendar.getInstance().apply{set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)}.timeInMillis
fun inRange(t:Long,f:Int):Boolean{val since=when(f){0->startDay();1->System.currentTimeMillis()-7*86400000L;2->System.currentTimeMillis()-30*86400000L;else->0L};return t>=since}
