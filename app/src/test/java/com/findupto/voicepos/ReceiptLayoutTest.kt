package com.findupto.voicepos

import org.junit.Assert.assertEquals
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

    @Test fun separatorsMatchPaperWidth() {
        assertEquals(32, ReceiptLayout.separator(58).length)
        assertEquals(48, ReceiptLayout.separator(80).length)
    }
}
