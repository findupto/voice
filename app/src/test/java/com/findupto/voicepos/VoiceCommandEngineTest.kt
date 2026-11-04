package com.findupto.voicepos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandEngineTest {
    @Test fun exactMenuMatchKeepsRequestedQuantityAndCatalogPrice() {
        val menu = listOf(MenuItem(1L, "Latte", 420.0))
        val commands = VoiceCommandEngine.parse("add 2 latte", menu)

        assertEquals(1, commands.size)
        val added = commands.single() as VoiceCommand.Add
        assertEquals("Latte", added.item.name)
        assertEquals(2, added.item.qty)
        assertEquals(420.0, added.item.price, 0.001)
    }

    @Test fun ambiguousMenuVariantsAreNotGuessed() {
        val menu = listOf(
            MenuItem(1L, "Burger", 500.0, size = "Small"),
            MenuItem(2L, "Burger", 750.0, size = "Large")
        )

        val commands = VoiceCommandEngine.parse("add 1 burger", menu)

        assertTrue(commands.none { it is VoiceCommand.Add })
    }

    @Test fun explicitVariantResolvesOtherwiseAmbiguousProduct() {
        val menu = listOf(
            MenuItem(1L, "Burger", 500.0, size = "Small"),
            MenuItem(2L, "Burger", 750.0, size = "Large")
        )

        val added = VoiceCommandEngine.parse("add 1 large burger", menu)
            .filterIsInstance<VoiceCommand.Add>()
            .single()

        assertEquals("Burger", added.item.name)
        assertEquals(750.0, added.item.price, 0.001)
    }

    @Test fun unrelatedSpeechDoesNotCreateCatalogSale() {
        val menu = listOf(MenuItem(1L, "Latte", 420.0))

        assertTrue(VoiceCommandEngine.parse("please add 2 pizza", menu)
            .none { it is VoiceCommand.Add })
    }
    @Test fun voiceAddedCatalogItemPreservesKitchenStation() {
        val menu = listOf(MenuItem(7L, "Chicken Pasta", 890.0, kitchen = "PASTA"))

        val added = VoiceCommandEngine.parse("add 2 chicken pasta", menu)
            .filterIsInstance<VoiceCommand.Add>()
            .single()

        assertEquals(2, added.item.qty)
        assertEquals("PASTA", added.item.kitchen)
    }
}
