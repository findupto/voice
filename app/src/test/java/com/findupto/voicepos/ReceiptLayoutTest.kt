package com.findupto.voicepos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptLayoutTest {
    @Test fun supportsStandard58And80MillimetreWidths() {
        assertEquals(32, ReceiptPaperWidth.fromMillimeters(58).columns)
        assertEquals(384, ReceiptPaperWidth.fromMillimeters(58).rasterDots)
        assertEquals(48, ReceiptPaperWidth.fromMillimeters(80).columns)
        assertEquals(576, ReceiptPaperWidth.fromMillimeters(80).rasterDots)
    }

    @Test fun itemRowsAlwaysFitSelectedPaperWidth() {
        assertEquals(32, ReceiptLayout.itemRow("A very long menu item name", "12", "Rs 123456", 58).length)
        assertEquals(48, ReceiptLayout.itemRow("A very long menu item name", "12", "Rs 123456", 80).length)
    }

    @Test fun totalsStayRightAlignedWithinBothWidths() {
        assertEquals(32, ReceiptLayout.pair("TOTAL BILL", "Rs 12,500", 58).length)
        assertEquals(48, ReceiptLayout.pair("TOTAL BILL", "Rs 12,500", 80).length)
    }

    @Test fun longItemPricesKeepTheirTrailingDigits() {
        val row = ReceiptLayout.itemRow("Very long item name", "1", "Rs 12,345,678", 58)
        assertEquals(32, row.length)
        assertTrue(row.endsWith("Rs 12,345,678"))
    }

    @Test fun longProductNamesWrapWithoutLosingText() {
        val rows = ReceiptLayout.itemRows("Chicken Cheese Burger Extra Large", "1", "Rs 350", 58)
        assertTrue(rows.size > 1)
        assertTrue(rows.all { it.length == 32 })
        assertTrue(rows.joinToString(" ").contains("Burger"))
    }

    @Test fun longCustomerNamesWrapWithoutTruncationOnBothWidths() {
        for (width in listOf(58, 80)) {
            val rows = ReceiptLayout.pairRows(
                "Customer: Christopher Montgomery-Smith",
                "03001234567",
                width
            )
            val columns = ReceiptPaperWidth.fromMillimeters(width).columns
            assertTrue(rows.size > 1)
            assertTrue(rows.all { it.length == columns })
            assertTrue(rows.joinToString(" ").contains("Montgomery-Smith"))
            assertTrue(rows.last().endsWith("03001234567"))
        }
    }

    @Test fun longUnbrokenNamesAreSplitToThePaperWidth() {
        for (width in listOf(58, 80)) {
            val columns = ReceiptPaperWidth.fromMillimeters(width).columns
            val lines = ReceiptLayout.wrap("X".repeat(columns + 7), columns)
            assertEquals(2, lines.size)
            assertTrue(lines.all { it.length <= columns })
            assertEquals(columns + 7, lines.joinToString("").length)
        }
    }

    @Test fun separatorsMatchPaperWidth() {
        assertEquals(32, ReceiptLayout.separator(58).length)
        assertEquals(48, ReceiptLayout.separator(80).length)
    }
}
