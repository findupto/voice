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

    fun fitEnd(value: String, maxColumns: Int): String {
        val clean = fit(value, Int.MAX_VALUE)
        if (clean.length <= maxColumns) return clean
        if (maxColumns <= 0) return ""
        if (maxColumns == 1) return clean.takeLast(1)
        return "…" + clean.takeLast(maxColumns - 1)
    }

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
        val quantityWidth = 6
        val priceText = fitEnd(price, (width - quantityWidth - 3).coerceAtLeast(1))
        val priceWidth = priceText.length.coerceAtLeast(1)
        val nameWidth = (width - quantityWidth - priceWidth - 2).coerceAtLeast(1)
        return fit(name, nameWidth).padEnd(nameWidth) + " " +
            fit(quantity, quantityWidth).padStart(quantityWidth) + " " +
            priceText.padStart(priceWidth)
    }
}
