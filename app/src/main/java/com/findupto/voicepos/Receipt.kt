package com.findupto.voicepos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

fun money(v: Double) = "${CurrencySettings.symbol} " + MoneyMath.format(v, CurrencySettings.code)
fun date(v: Long) = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date(v))
private fun isDiscountItem(x: SaleItem) = x.name.startsWith(CART_DISCOUNT_PREFIX)
private fun discountLabel(x: SaleItem) = x.name.removePrefix(CART_DISCOUNT_PREFIX).trim().ifBlank { "Discount" }
private fun containsUrdu(s: String) = s.any {
    it.code in 0x0600..0x06FF || it.code in 0x0750..0x077F || it.code in 0x08A0..0x08FF ||
        it.code in 0xFB50..0xFDFF || it.code in 0xFE70..0xFEFF
}

private fun logo(path: String, paperWidthMm: Int): ByteArray? {
    val src = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
    if (src.width <= 0 || src.height <= 0) { src.recycle(); return null }
    val layout = ReceiptPaperWidth.fromMillimeters(paperWidthMm)
    val maxWidth = (layout.rasterDots - 24).coerceAtLeast(8)
    val maxHeight = if (layout == ReceiptPaperWidth.MM_80) 120 else 88
    val scale = minOf(1f, maxWidth.toFloat() / src.width, maxHeight.toFloat() / src.height)
    val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src,
        (src.width * scale).toInt().coerceAtLeast(8), (src.height * scale).toInt().coerceAtLeast(8), true) else src
    val bytesPerRow = (bmp.width + 7) / 8
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x1D,0x76,0x30,0,bytesPerRow.toByte(),(bytesPerRow shr 8).toByte(),bmp.height.toByte(),(bmp.height shr 8).toByte()))
    for (y in 0 until bmp.height) for (column in 0 until bytesPerRow) {
        var value = 0
        for (bit in 0..7) {
            val x = column * 8 + bit
            if (x < bmp.width) {
                val color = bmp.getPixel(x, y)
                val gray = (Color.red(color)*299 + Color.green(color)*587 + Color.blue(color)*114)/1000
                if (Color.alpha(color) >= 100 && gray < 190) value = value or (1 shl (7-bit))
            }
        }
        out.write(value)
    }
    bmp.recycle()
    if (bmp !== src) src.recycle()
    return out.toByteArray()
}

private fun writeHeader(p: CompanyProfile, out: ByteArrayOutputStream, time: Long, paperWidthMm: Int) {
    val layout = ReceiptPaperWidth.fromMillimeters(paperWidthMm)
    fun w(s: String) { out.write(s.toByteArray(Charsets.UTF_8)) }
    fun c(vararg x: Int) { out.write(x.map { (it and 255).toByte() }.toByteArray()) }
    c(0x1B,0x40,0x1B,0x61,1)
    if (p.saleLogoEnabled && p.logoPath.isNotBlank()) logo(p.logoPath,paperWidthMm)?.let { out.write(it); w("\n") }
    c(0x1B,0x45,1,0x1D,0x21,0x11)
    w(ReceiptLayout.fit(p.name.ifBlank { "VOICE POS" },layout.columns)+"\n")
    c(0x1D,0x21,0,0x1B,0x45,0)
    if (p.phone.isNotBlank()) w(ReceiptLayout.fit(p.phone,layout.columns)+"\n")
    p.address.split("\n").filter { it.isNotBlank() }.forEach { line ->
        val address = ReceiptLayout.fit(line,layout.columns)
        if (containsUrdu(address)) out.write(ReceiptImage.escPosLine(address,layout.rasterDots)) else w(address+"\n")
    }
    w(ReceiptLayout.separator(paperWidthMm)+"\n")
    w(ReceiptLayout.pair("Date",date(time),paperWidthMm)+"\n")
}

private fun writeKitchenHeader(out: ByteArrayOutputStream, sale: PendingSale, paperWidthMm: Int) {
    fun w(s: String) { out.write(s.toByteArray(Charsets.UTF_8)) }
    fun c(vararg x: Int) { out.write(x.map { (it and 255).toByte() }.toByteArray()) }
    c(0x1B,0x40,0x1B,0x61,1,0x1B,0x45,1,0x1D,0x21,0x11)
    w("KITCHEN ORDER\n")
    c(0x1D,0x21,0,0x1B,0x45,0)
    w(ReceiptLayout.pair("ORDER #",sale.id.toString(),paperWidthMm)+"\n")
    c(0x1B,0x45,1)
    w("*** "+ReceiptLayout.fit(sale.orderType.replace('_',' ').uppercase(Locale.US),ReceiptPaperWidth.fromMillimeters(paperWidthMm).columns)+" ***\n")
    c(0x1B,0x45,0)
    w(ReceiptLayout.pair("TIME",date(sale.time),paperWidthMm)+"\n")
    if (sale.shiftPerson.isNotBlank()) w(ReceiptLayout.pair("SHIFT PERSON",sale.shiftPerson,paperWidthMm)+"\n")
    w(ReceiptLayout.separator(paperWidthMm)+"\n")
}

private fun escpos(
    p: CompanyProfile, title: String, items: List<SaleItem>, total: Double, theme: ReceiptTheme,
    customerName: String = "", customerPhone: String = "", time: Long = System.currentTimeMillis(),
    orderType: String = "DINE_IN", customerAddress: String = "", paymentMethod: String = "",
    paidAmount: Double = total, dueAmount: Double = 0.0, shiftPerson: String = "", paperWidthMm: Int = 58,
    storedServiceCharge: Double? = null, storedTax: Double? = null
): ByteArray {
    val out = ByteArrayOutputStream()
    val layout = ReceiptPaperWidth.fromMillimeters(paperWidthMm)
    fun w(s: String) { out.write(s.toByteArray(Charsets.UTF_8)) }
    fun c(vararg x: Int) { out.write(x.map { (it and 255).toByte() }.toByteArray()) }
    val productItems = items.filterNot(::isDiscountItem)
    val subtotal = productItems.sumOf { it.total }
    val discount = cartDiscount(items).coerceAtMost(subtotal)
    val net = (subtotal-discount).coerceAtLeast(0.0)
    val calculated = MoneyMath.calculate(net,p.serviceChargeRate,p.taxRate,p.serviceChargeEnabled,p.taxEnabled,p.currencyCode)
    val useStoredCharges = storedServiceCharge != null && storedTax != null &&
        storedServiceCharge.isFinite() && storedTax.isFinite() && storedServiceCharge >= 0.0 && storedTax >= 0.0 &&
        (storedServiceCharge > 0.0 || storedTax > 0.0 || total <= net + 0.005)
    val serviceCharge = if (useStoredCharges) storedServiceCharge!! else calculated.serviceCharge
    val taxAmount = if (useStoredCharges) storedTax!! else calculated.tax
    writeHeader(p,out,time,paperWidthMm)
    c(0x1B,0x45,1); w(ReceiptLayout.fit(title,layout.columns)+"\n"); c(0x1B,0x45,0)
    w(ReceiptLayout.pair("Order Type",orderType.replace('_',' '),paperWidthMm)+"\n")
    if (shiftPerson.isNotBlank()) w(ReceiptLayout.pair("Served by",shiftPerson,paperWidthMm)+"\n")
    if (customerName.isNotBlank() || customerPhone.isNotBlank() || customerAddress.isNotBlank()) {
        w(ReceiptLayout.separator(paperWidthMm)+"\n")
        if (customerName.isNotBlank() || customerPhone.isNotBlank()) {
            val line=ReceiptLayout.pair(customerName.ifBlank{"Customer"},customerPhone,paperWidthMm)
            if (containsUrdu(line)) out.write(ReceiptImage.escPosLine(line,layout.rasterDots)) else w(line+"\n")
        }
        if (customerAddress.isNotBlank()) {
            val address="Address: "+ReceiptLayout.fit(customerAddress,(layout.columns-9).coerceAtLeast(0))
            if (containsUrdu(address)) out.write(ReceiptImage.escPosLine(address,layout.rasterDots)) else w(address+"\n")
        }
    }
    w(ReceiptLayout.separator(paperWidthMm)+"\n")
    w(ReceiptLayout.itemRow("ITEM","QTY","PRICE",paperWidthMm)+"\n")
    w(ReceiptLayout.separator(paperWidthMm)+"\n")
    productItems.forEach { w(ReceiptLayout.itemRow(it.name,it.qty.toString(),money(it.total),paperWidthMm)+"\n") }
    w(ReceiptLayout.separator(paperWidthMm)+"\n")
    w(ReceiptLayout.pair("Bill",money(subtotal),paperWidthMm)+"\n")
    if (discount>0) {
        val label=items.filter(::isDiscountItem).joinToString(" / "){discountLabel(it)}.ifBlank{"Discount"}
        w(ReceiptLayout.pair(label,"- "+money(discount),paperWidthMm)+"\n")
    }
    if (serviceCharge>0) w(ReceiptLayout.pair(p.serviceChargeLabel.ifBlank{"Service Charge"}+" ${p.serviceChargeRate}%",money(serviceCharge),paperWidthMm)+"\n")
    if (taxAmount>0) w(ReceiptLayout.pair(p.taxLabel.ifBlank{"Tax"}+" ${p.taxRate}%",money(taxAmount),paperWidthMm)+"\n")
    c(0x1B,0x45,1); w(ReceiptLayout.pair("TOTAL BILL",money(total),paperWidthMm)+"\n"); c(0x1B,0x45,0)
    if (paymentMethod.isNotBlank()) w(ReceiptLayout.pair("Paid via",paymentMethod,paperWidthMm)+"\n")
    if (dueAmount>0.005) { w(ReceiptLayout.pair("Paid",money(paidAmount),paperWidthMm)+"\n"); w(ReceiptLayout.pair("BALANCE DUE",money(dueAmount),paperWidthMm)+"\n") }
    w(ReceiptLayout.separator(paperWidthMm)+"\n")
    if (p.footer.isNotBlank()) { c(0x1B,0x61,1); w(ReceiptLayout.fit(p.footer,layout.columns)+"\n") }
    w("\n\n\n")
    return out.toByteArray()
}

fun customerReceipt(s: Sale, p: CompanyProfile, t: ReceiptTheme, shiftPerson: String = "", paperWidthMm: Int = 58) =
    escpos(p,"SALE #"+s.id,s.items,s.total,t,s.customerName,s.customerPhone,s.time,s.orderType,s.customerAddress,s.paymentMethod,s.paidAmount,s.dueAmount,shiftPerson,paperWidthMm,s.serviceCharge,s.tax)

fun expenseReceipt(e: Expense, p: CompanyProfile, t: ReceiptTheme, paperWidthMm: Int = 58): ByteArray {
    val out=ByteArrayOutputStream()
    fun w(s:String){out.write(s.toByteArray(Charsets.UTF_8))}
    fun c(vararg x:Int){out.write(x.map{(it and 255).toByte()}.toByteArray())}
    writeHeader(p,out,e.time,paperWidthMm)
    c(0x1B,0x45,1);w("EXPENSE #${e.id}\n");c(0x1B,0x45,0)
    w(ReceiptLayout.separator(paperWidthMm)+"\n")
    w(ReceiptLayout.pair("Description",e.title,paperWidthMm)+"\n")
    w(ReceiptLayout.pair("Category",e.category,paperWidthMm)+"\n")
    w(ReceiptLayout.pair("Paid via",e.paymentMethod,paperWidthMm)+"\n")
    w(ReceiptLayout.separator(paperWidthMm)+"\n")
    c(0x1B,0x45,1);w(ReceiptLayout.pair("AMOUNT",money(e.amount),paperWidthMm)+"\n");c(0x1B,0x45,0)
    w(ReceiptLayout.separator(paperWidthMm)+"\n")
    if(p.footer.isNotBlank())w(ReceiptLayout.fit(p.footer,ReceiptPaperWidth.fromMillimeters(paperWidthMm).columns)+"\n")
    w("\n\n\n")
    return out.toByteArray()
}

fun kitchenReceipt(s: PendingSale, p: CompanyProfile, t: ReceiptTheme, paperWidthMm: Int = 58): ByteArray {
    val out=ByteArrayOutputStream()
    fun w(x:String){out.write(x.toByteArray(Charsets.UTF_8))}
    fun c(vararg x:Int){out.write(x.map{(it and 255).toByte()}.toByteArray())}
    val width=ReceiptPaperWidth.fromMillimeters(paperWidthMm).columns
    writeKitchenHeader(out,s,paperWidthMm)
    c(0x1B,0x45,1,0x1D,0x21,0x22);w("ORDER ITEMS\n");c(0x1D,0x21,0,0x1B,0x45,0)
    w(ReceiptLayout.separator(paperWidthMm)+"\n")
    s.items.filterNot(::isDiscountItem).forEach {
        c(0x1B,0x45,1,0x1D,0x21,0x11);w(ReceiptLayout.fit("${it.qty} x ${it.name}",width)+"\n");c(0x1D,0x21,0,0x1B,0x45,0)
    }
    w(ReceiptLayout.separator(paperWidthMm)+"\n")
    c(0x1B,0x45,1)
    val route=when(s.orderType.uppercase(Locale.US)){"DELIVERY"->"*** DELIVERY - RIDER COPY ***";"TAKEAWAY"->"*** TAKEAWAY - PICKUP ***";else->"*** DINE-IN - TABLE SERVICE ***"}
    w(ReceiptLayout.fit(route,width)+"\n");c(0x1B,0x45,0);w("\n\n\n")
    return out.toByteArray()
}

fun startDay():Long=Calendar.getInstance().apply{set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)}.timeInMillis
fun inRange(t:Long,f:Int):Boolean{val since=when(f){0->startDay();1->System.currentTimeMillis()-7*86400000L;2->System.currentTimeMillis()-30*86400000L;else->0L};return t>=since}
