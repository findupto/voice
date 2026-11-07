package com.findupto.voicepos

enum class ReceiptPaperWidth(val millimeters: Int, val columns: Int, val rasterDots: Int) {
    MM_58(58, 32, 384),
    MM_80(80, 48, 576);

    companion object {
        fun fromMillimeters(value: Int): ReceiptPaperWidth =
            if (value >= 80) MM_80 else MM_58
    }
}

object ReceiptLayout {
    fun fit(value: String, maxColumns: Int): String = value
        .replace("\n", " ").replace("×", "x").replace("—", "-").replace("–", "-")
        .replace("•", "*").replace("“", "\"").replace("”", "\"").replace("’", "'")
        .trim().take(maxColumns.coerceAtLeast(0))

    fun separator(paperWidthMm: Int) =
        "-".repeat(ReceiptPaperWidth.fromMillimeters(paperWidthMm).columns)

    fun pair(left: String, right: String, paperWidthMm: Int): String {
        val width = ReceiptPaperWidth.fromMillimeters(paperWidthMm).columns
        val r = fit(right, (width - 1).coerceAtLeast(0))
        val l = fit(left, (width - r.length - 1).coerceAtLeast(0))
        return l + " ".repeat(width - l.length - r.length) + r
    }

    fun itemRow(name: String, quantity: String, price: String, paperWidthMm: Int): String {
        val width = ReceiptPaperWidth.fromMillimeters(paperWidthMm).columns
        val priceWidth = 11
        val quantityWidth = 6
        val nameWidth = width - quantityWidth - priceWidth - 2
        return fit(name, nameWidth).padEnd(nameWidth) + " " +
            fit(quantity, quantityWidth).padStart(quantityWidth) + " " +
            fit(price, priceWidth).padStart(priceWidth)
    }
}
