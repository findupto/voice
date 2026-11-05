package com.findupto.voicepos

/** Rule-based menu pairing that works offline and never invents products or prices. */
object FoodPairingEngine {
    data class Suggestion(val item: MenuItem, val reason: String, val score: Int)

    private enum class Group { MAIN, SIDE, DRINK, DESSERT, OTHER }

    private fun group(item: MenuItem): Group {
        val text = listOf(item.name, item.variant, item.size).joinToString(" ").lowercase()
        return when {
            listOf("cola", "soda", "drink", "water", "juice", "tea", "coffee", "shake", "lemonade").any(text::contains) -> Group.DRINK
            listOf("cake", "brownie", "ice cream", "icecream", "cookie", "dessert", "pudding").any(text::contains) -> Group.DESSERT
            listOf("fries", "nugget", "garlic bread", "salad", "dip", "sauce", "wedges", "coleslaw").any(text::contains) -> Group.SIDE
            listOf("pizza", "burger", "shawarma", "roll", "sandwich", "wrap", "pasta", "biryani", "rice", "steak", "wings").any(text::contains) -> Group.MAIN
            else -> Group.OTHER
        }
    }

    private fun identity(item: MenuItem): String =
        listOf(canonicalProductKey(item.name), item.variant.trim().lowercase(), item.size.trim().lowercase())
            .joinToString("|")

    fun suggest(menu: List<MenuItem>, cart: List<SaleItem>, limit: Int = 4): List<Suggestion> {
        if (menu.isEmpty() || cart.isEmpty() || limit <= 0) return emptyList()
        val alreadyAdded = cart.filterNot { it.name.startsWith(CART_DISCOUNT_PREFIX) }
            .map { canonicalProductKey(it.name) }.toSet()
        val cartGroups = cart.filterNot { it.name.startsWith(CART_DISCOUNT_PREFIX) }
            .map { group(MenuItem(0L, it.name, it.price, kitchen = it.kitchen)) }
        val hasMain = Group.MAIN in cartGroups
        val hasDrink = Group.DRINK in cartGroups
        val hasSide = Group.SIDE in cartGroups
        val targets = when {
            hasMain && !hasDrink -> setOf(Group.DRINK, Group.SIDE)
            hasMain && !hasSide -> setOf(Group.SIDE, Group.DRINK)
            hasDrink && !hasMain -> setOf(Group.MAIN, Group.SIDE)
            hasSide && !hasMain -> setOf(Group.MAIN, Group.DRINK)
            Group.DESSERT in cartGroups -> setOf(Group.DRINK)
            else -> setOf(Group.DRINK, Group.SIDE, Group.DESSERT)
        }
        val reasons = mapOf(
            Group.DRINK to "Complete the order with a drink",
            Group.SIDE to "A popular side to go with this order",
            Group.MAIN to "Add a main item to complete the meal",
            Group.DESSERT to "Offer something sweet"
        )
        return menu.asSequence()
            .filter { it.price >= 0.0 && it.name.isNotBlank() }
            .filterNot { canonicalProductKey(it.name) in alreadyAdded }
            .filter { group(it) in targets }
            .distinctBy(::identity)
            .map { item ->
                val category = group(item)
                val score = when (category) {
                    Group.DRINK -> if (hasMain) 100 else 75
                    Group.SIDE -> if (hasMain) 95 else 70
                    Group.MAIN -> if (hasDrink || hasSide) 100 else 80
                    Group.DESSERT -> 60
                    Group.OTHER -> 0
                }
                Suggestion(item, reasons[category] ?: "Suggested menu pairing", score)
            }
            .sortedWith(compareByDescending<Suggestion> { it.score }.thenBy { it.item.price })
            .take(limit)
            .toList()
    }
}
