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

    fun wrap(value: String, maxColumns: Int): List<String> {
        val width = maxColumns.coerceAtLeast(1)
        val clean = fit(value, Int.MAX_VALUE)
        if (clean.isBlank()) return listOf("")
        val result = mutableListOf<String>()
        var line = ""
        for (word in clean.split(Regex("\\s+"))) {
            var remaining = word
            if (remaining.length > width) {
                if (line.isNotEmpty()) { result += line; line = "" }
                while (remaining.length > width) {
                    result += remaining.take(width)
                    remaining = remaining.drop(width)
                }
            }
            if (remaining.isEmpty()) continue
            if (line.isEmpty()) line = remaining
            else if (line.length + 1 + remaining.length <= width) line += " " + remaining
            else { result += line; line = remaining }
        }
        if (line.isNotEmpty()) result += line
        return result.ifEmpty { listOf("") }
    }

    fun itemRows(name: String, quantity: String, price: String, paperWidthMm: Int): List<String> {
        val width = ReceiptPaperWidth.fromMillimeters(paperWidthMm).columns
        val quantityWidth = 6
        val priceText = fitEnd(price, (width - quantityWidth - 3).coerceAtLeast(1))
        val priceWidth = priceText.length.coerceAtLeast(1)
        val nameWidth = (width - quantityWidth - priceWidth - 2).coerceAtLeast(1)
        val nameLines = wrap(name, nameWidth)
        val first = nameLines.first().padEnd(nameWidth) + " " +
            fit(quantity, quantityWidth).padStart(quantityWidth) + " " + priceText.padStart(priceWidth)
        val continuationPadding = " ".repeat(1 + quantityWidth + 1 + priceWidth)
        return listOf(first) + nameLines.drop(1).map { it.padEnd(nameWidth) + continuationPadding }
    }

    fun itemRow(name: String, quantity: String, price: String, paperWidthMm: Int): String =
        itemRows(name, quantity, price, paperWidthMm).first()
}
