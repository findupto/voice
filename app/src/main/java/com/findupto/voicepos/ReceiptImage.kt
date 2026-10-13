package com.findupto.voicepos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.io.ByteArrayOutputStream

/** Shapes Urdu/RTL text with Android and prints the rendered receipt as ESC/POS raster data. */
object ReceiptImage {
    fun bitmap(text: String, width: Int = 384, textSize: Float = 22f): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.BLACK
            textSize = textSize
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val blocks = text.split('\n').map { line ->
            if (line.isBlank()) null else StaticLayout.Builder.obtain(line, 0, line.length, paint, width - 24)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                .setIncludePad(true)
                .setLineSpacing(1f, 1f)
                .build()
        }
        val height = (blocks.sumOf { it?.height ?: 12 } + 32).coerceIn(64, 30000)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        var y = 16f
        blocks.forEach { layout ->
            if (layout == null) y += 12f else {
                canvas.save()
                canvas.translate(12f, y)
                layout.draw(canvas)
                canvas.restore()
                y += layout.height
            }
        }
        return bmp
    }

    /** Render one receipt line as raster data without resetting printer styles or layout. */
    fun escPosLine(text: String, width: Int = 384): ByteArray {
        val source = bitmap(text, width, 18f)
        val bytesPerRow = source.width / 8
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, bytesPerRow.toByte(),
            (bytesPerRow shr 8).toByte(), source.height.toByte(), (source.height shr 8).toByte()))
        for (y in 0 until source.height) {
            for (bx in 0 until bytesPerRow) {
                var bits = 0
                for (bit in 0..7) {
                    val pixel = source.getPixel(bx * 8 + bit, y)
                    val gray = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                    if (gray < 170) bits = bits or (1 shl (7 - bit))
                }
                out.write(bits)
            }
        }
        source.recycle()
        return out.toByteArray()
    }

    fun escPos(text: String): ByteArray {
        val source = bitmap(text)
        val bytesPerRow = source.width / 8
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40, 0x1B, 0x61, 0x01))
        out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, bytesPerRow.toByte(),
            (bytesPerRow shr 8).toByte(), source.height.toByte(), (source.height shr 8).toByte()))
        for (y in 0 until source.height) {
            for (bx in 0 until bytesPerRow) {
                var bits = 0
                for (bit in 0..7) {
                    val pixel = source.getPixel(bx * 8 + bit, y)
                    val gray = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                    if (gray < 170) bits = bits or (1 shl (7 - bit))
                }
                out.write(bits)
            }
        }
        out.write(byteArrayOf(0x0A, 0x0A, 0x0A))
        source.recycle()
        return out.toByteArray()
    }
}
