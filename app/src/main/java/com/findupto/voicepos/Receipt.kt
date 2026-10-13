package com.findupto.voicepos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

fun money(v:Double)="${CurrencySettings.symbol} "+String.format(Locale.US,"%,.0f",v)
fun date(v:Long)=SimpleDateFormat("dd MMM yyyy, hh:mm a",Locale.US).format(Date(v))
private const val W=42
private const val SEP="------------------------------------------"
private fun isDiscountItem(x:SaleItem)=x.name.startsWith(CART_DISCOUNT_PREFIX)
private fun discountLabel(x:SaleItem)=x.name.removePrefix(CART_DISCOUNT_PREFIX).trim().ifBlank{"Discount"}
private fun fit(s:String,n:Int)=s.replace("\n"," ").replace("×","x").replace("—","-").replace("–","-").replace("•","*").replace("“","\"").replace("”","\"").replace("’","\u0027").trim().take(n)
private fun pair(a:String,b:String):String{val r=fit(b,W);val l=fit(a,W-r.length-1);return l+" ".repeat((W-l.length-r.length).coerceAtLeast(1))+r}
private fun cols(name:String,qty:String,price:String):String{val pw=11;val qw=6;val nw=W-qw-pw-2;return fit(name,nw).padEnd(nw)+" "+fit(qty,qw).padStart(qw)+" "+fit(price,pw).padStart(pw)}

private fun logo(path:String):ByteArray?{
    val src=runCatching{BitmapFactory.decodeFile(path)}.getOrNull()?:return null
    if(src.width<=0||src.height<=0)return null
    val maxW=280;val maxH=88;val scale=minOf(1f,maxW.toFloat()/src.width,maxH.toFloat()/src.height)
    val bmp=if(scale<1f)Bitmap.createScaledBitmap(src,(src.width*scale).toInt().coerceAtLeast(8),(src.height*scale).toInt().coerceAtLeast(8),true)else src
    val wb=(bmp.width+7)/8;val out=ByteArrayOutputStream()
    out.write(byteArrayOf(0x1D,0x76,0x30,0,wb.toByte(),(wb shr 8).toByte(),bmp.height.toByte(),(bmp.height shr 8).toByte()))
    for(y in 0 until bmp.height)for(q in 0 until wb){var v=0;for(bit in 0..7){val x=q*8+bit;if(x<bmp.width){val c=bmp.getPixel(x,y);val alpha=Color.alpha(c);val g=(Color.red(c)*299+Color.green(c)*587+Color.blue(c)*114)/1000;if(alpha>=100&&g<190)v=v or(1 shl (7-bit))}};out.write(v)}
    return out.toByteArray()
}

private fun writeHeader(p:CompanyProfile,o:ByteArrayOutputStream,time:Long){fun w(s:String){o.write(s.toByteArray(Charsets.UTF_8))};fun c(vararg x:Int){o.write(x.map{(it and 255).toByte()}.toByteArray())};c(0x1B,0x40,0x1B,0x61,1);if(p.saleLogoEnabled&&p.logoPath.isNotBlank())logo(p.logoPath)?.let{o.write(it);w("\n")};c(0x1B,0x45,1,0x1D,0x21,0x11);w(fit(p.name.ifBlank{"VOICE POS"},W)+"\n");c(0x1D,0x21,0,0x1B,0x45,0);if(p.phone.isNotBlank())w(fit(p.phone,W)+"\n");p.address.split("\n").filter{it.isNotBlank()}.forEach{w(fit(it,W)+"\n")};w(SEP+"\n");w(pair("Date",date(time))+"\n")}

private fun writeKitchenHeader(o:ByteArrayOutputStream,s:PendingSale){fun w(x:String){o.write(x.toByteArray(Charsets.UTF_8))};fun c(vararg x:Int){o.write(x.map{(it and 255).toByte()}.toByteArray())};c(0x1B,0x40,0x1B,0x61,1,0x1B,0x45,1,0x1D,0x21,0x11);w("KITCHEN ORDER\n");c(0x1D,0x21,0,0x1B,0x45,0);w(pair("ORDER #",s.id.toString())+"\n");c(0x1B,0x45,1);w("*** "+s.orderType.replace('_',' ').uppercase(Locale.US)+" ***\n");c(0x1B,0x45,0);w(pair("TIME",date(s.time))+"\n");w(SEP+"\n")}

private fun escpos(p:CompanyProfile,title:String,items:List<SaleItem>,total:Double,theme:ReceiptTheme,customerName:String="",customerPhone:String="",time:Long=System.currentTimeMillis(),orderType:String="DINE_IN",customerAddress:String="",paymentMethod:String="",paidAmount:Double=total,dueAmount:Double=0.0):ByteArray{val o=ByteArrayOutputStream();fun w(s:String){o.write(s.toByteArray(Charsets.UTF_8))};fun c(vararg x:Int){o.write(x.map{(it and 255).toByte()}.toByteArray())};val productItems=items.filterNot(::isDiscountItem);val subtotal=productItems.sumOf{it.total};val discount=cartDiscount(items).coerceAtMost(subtotal);val net=(subtotal-discount).coerceAtLeast(0.0);val service=if(p.serviceChargeEnabled)net*p.serviceChargeRate/100.0 else 0.0;val taxBase=net+service;val tax=if(p.taxEnabled)taxBase*p.taxRate/100.0 else 0.0;writeHeader(p,o,time);c(0x1B,0x45,1);w(fit(title,W)+"\n");c(0x1B,0x45,0);w(pair("Order Type",orderType.replace('_',' '))+"\n");if(customerName.isNotBlank()||customerPhone.isNotBlank()||customerAddress.isNotBlank()){w(SEP+"\n");if(customerName.isNotBlank()||customerPhone.isNotBlank())w(pair(customerName.ifBlank{"Customer"},customerPhone)+"\n");if(customerAddress.isNotBlank())w("Address: "+fit(customerAddress,W-9)+"\n")};w(SEP+"\n");w(cols("ITEM","QTY","PRICE")+"\n");w(SEP+"\n");productItems.forEach{w(cols(it.name,it.qty.toString(),money(it.total))+"\n")};w(SEP+"\n");w(pair("Bill",money(subtotal))+"\n");if(discount>0)w(pair(items.filter(::isDiscountItem).joinToString(" / "){discountLabel(it)}.ifBlank{"Discount"},"- "+money(discount))+"\n");if(p.serviceChargeEnabled&&service>0)w(pair(p.serviceChargeLabel.ifBlank{"Service Charge"}+" ${p.serviceChargeRate}%",money(service))+"\n");if(p.taxEnabled&&tax>0)w(pair(p.taxLabel.ifBlank{"Tax"}+" ${p.taxRate}%",money(tax))+"\n");c(0x1B,0x45,1);w(pair("TOTAL BILL",money(total))+"\n");c(0x1B,0x45,0);if(paymentMethod.isNotBlank())w(pair("Paid via",paymentMethod)+"\n");if(dueAmount>0.005){w(pair("Paid",money(paidAmount))+"\n");w(pair("BALANCE DUE",money(dueAmount))+"\n")};w(SEP+"\n");if(p.footer.isNotBlank()){c(0x1B,0x61,1);w(fit(p.footer,W)+"\n")};w("\n\n\n");return o.toByteArray()}
fun customerReceipt(s:Sale,p:CompanyProfile,t:ReceiptTheme):ByteArray = ReceiptImage.escPos(buildString {
    append(p.name.ifBlank { "VOICE POS" }).append('\n')
    if (p.phone.isNotBlank()) append(p.phone).append('\n')
    if (p.address.isNotBlank()) append(p.address).append('\n')
    append("SALE #").append(s.id).append('\n').append(date(s.time)).append('\n')
    append("Order: ").append(s.orderType.replace('_',' ')).append('\n')
    if (s.customerName.isNotBlank()) append("Customer: ").append(s.customerName).append('\n')
    if (s.customerPhone.isNotBlank()) append("Phone: ").append(s.customerPhone).append('\n')
    if (s.customerAddress.isNotBlank()) append("Address: ").append(s.customerAddress).append('\n')
    append("------------------------------------------\n")
    append("ITEM                         QTY      PRICE\n")
    s.items.filterNot(::isDiscountItem).forEach { append(it.name).append("   ").append(it.qty).append(" x ").append(money(it.total)).append('\n') }
    val subtotal=s.items.filterNot(::isDiscountItem).sumOf { it.total }
    val discount=cartDiscount(s.items).coerceAtMost(subtotal)
    append("------------------------------------------\n")
    append("Subtotal: ").append(money(subtotal)).append('\n')
    if(discount>0.005) append("Discount: -").append(money(discount)).append('\n')
    if(s.serviceCharge>0.005) append(p.serviceChargeLabel).append(": ").append(money(s.serviceCharge)).append('\n')
    if(s.tax>0.005) append(p.taxLabel).append(": ").append(money(s.tax)).append('\n')
    append("TOTAL: ").append(money(s.total)).append('\n')
    if(s.paymentMethod.isNotBlank()) append("Paid via: ").append(s.paymentMethod).append('\n')
    append("Paid: ").append(money(s.paidAmount)).append('\n')
    if(s.dueAmount>0.005) append("Balance due: ").append(money(s.dueAmount)).append('\n')
    append("------------------------------------------\n")
    if(p.footer.isNotBlank()) append(p.footer).append('\n')
})
fun expenseReceipt(e:Expense,p:CompanyProfile,t:ReceiptTheme):ByteArray = ReceiptImage.escPos(buildString {
    append(p.name.ifBlank { "VOICE POS" }).append('\n')
    if(p.phone.isNotBlank()) append(p.phone).append('\n')
    if(p.address.isNotBlank()) append(p.address).append('\n')
    append("EXPENSE #").append(e.id).append('\n').append(date(e.time)).append('\n')
    append("------------------------------------------\n")
    append("Description: ").append(e.title).append('\n')
    append("Category: ").append(e.category).append('\n')
    append("Paid via: ").append(e.paymentMethod).append('\n')
    append("------------------------------------------\n")
    append("AMOUNT: ").append(money(e.amount)).append('\n')
    if(p.footer.isNotBlank()) append(p.footer).append('\n')
})
fun kitchenReceipt(s:PendingSale,p:CompanyProfile,t:ReceiptTheme):ByteArray = ReceiptImage.escPos(buildString {
    append(p.name.ifBlank { "VOICE POS" }).append('\n')
    append("KITCHEN ORDER").append('\n')
    append("ORDER #").append(s.id).append('\n')
    append(s.orderType.replace('_',' ').uppercase(Locale.US)).append('\n')
    append(date(s.time)).append('\n')
    if(s.customerName.isNotBlank()) append("Customer: ").append(s.customerName).append('\n')
    if(s.customerPhone.isNotBlank()) append("Phone: ").append(s.customerPhone).append('\n')
    if(s.customerAddress.isNotBlank()) append("Address: ").append(s.customerAddress).append('\n')
    append("------------------------------------------\n")
    s.items.filterNot(::isDiscountItem).forEach { append(it.qty).append(" x ").append(it.name).append('\n') }
    append("------------------------------------------\n")
    if(p.footer.isNotBlank()) append(p.footer).append('\n')
})
fun startDay():Long=Calendar.getInstance().apply{set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)}.timeInMillis
fun inRange(t:Long,f:Int):Boolean{val since=when(f){0->startDay();1->System.currentTimeMillis()-7*86400000L;2->System.currentTimeMillis()-30*86400000L;else->0L};return t>=since}
