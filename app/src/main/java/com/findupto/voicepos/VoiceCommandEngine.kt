package com.findupto.voicepos

import java.util.Locale

sealed class VoiceCommand {
    data class Add(val item: SaleItem) : VoiceCommand()
    data object CompletePrint : VoiceCommand()
    data object Complete : VoiceCommand()
    data object Clear : VoiceCommand()
    data object Sales : VoiceCommand()
    data object Expenses : VoiceCommand()
    data object Reports : VoiceCommand()
    data object Settings : VoiceCommand()
    data object Menu : VoiceCommand()
    data object Kitchen : VoiceCommand()
    data object Analytics : VoiceCommand()
    data object NewOrder : VoiceCommand()
    data object PayLast : VoiceCommand()
    data class Remove(val name: String) : VoiceCommand()
    data class Quantity(val name: String, val delta: Int) : VoiceCommand()
}

object VoiceCommandEngine {
    private val numberWords = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
    )

    fun parse(raw: String, menu: List<MenuItem> = emptyList()): List<VoiceCommand> {
        var s = raw.lowercase(Locale.US)
            .replace(Regex("""\brupees?\b"""), "rs")
            .replace("rs.", "rs")
            .trim()
        numberWords.forEach { (word, number) -> s = s.replace(Regex("""\b$word\b"""), number.toString()) }
        val out = mutableListOf<VoiceCommand>()

        if (Regex("""\b(clear|empty|cancel|start over) (the )?(cart|order)\b|\bnew order\b""").containsMatchIn(s)) out += VoiceCommand.Clear
        if (Regex("""\b(show|open|go to) (sales|sale history)\b""").containsMatchIn(s)) out += VoiceCommand.Sales
        if (Regex("""\b(show|open|go to) (expenses|expense)\b""").containsMatchIn(s)) out += VoiceCommand.Expenses
        if (Regex("""\b(show|open|go to) (reports|analysis|dashboard|analytics)\b""").containsMatchIn(s)) out += VoiceCommand.Reports
        if (Regex("""\b(show|open|go to) (menu|products)\b""").containsMatchIn(s)) out += VoiceCommand.Menu
        if (Regex("""\b(show|open|go to) (kitchen|orders)\b""").containsMatchIn(s)) out += VoiceCommand.Kitchen
        if (Regex("""\b(open|show) (settings|printer)\b""").containsMatchIn(s)) out += VoiceCommand.Settings
        if (Regex("""\b(pay|paid|payment) (last|latest|order)\b""").containsMatchIn(s)) out += VoiceCommand.PayLast

        Regex("""\b(?:remove|delete) (?:item )?(.+)""").find(s)?.let { out += VoiceCommand.Remove(it.groupValues[1].trim()) }

        Regex("""\b(?:increase|add) (?:quantity of )?(.+?)\s+by\s+(\d+)\b""").find(s)?.let {
            out += VoiceCommand.Quantity(it.groupValues[1].trim(), it.groupValues[2].toInt())
        }
        Regex("""\b(?:decrease|reduce) (?:quantity of )?(.+?)\s+by\s+(\d+)\b""").find(s)?.let {
            out += VoiceCommand.Quantity(it.groupValues[1].trim(), -it.groupValues[2].toInt())
        }

        val itemPattern = Regex(
            """(?:^|,|;|\band\b|\bthen\b|\badd\b)\s*(\d+)\s+(.+?)\s+(?:price|at)\s+(?:rs\s*)?(\d+(?:\.\d+)?)\s*(?:each)?(?=\s*(?:,|;|\band\b|\bthen\b|\badd\b|$))""",
            RegexOption.IGNORE_CASE
        )
        itemPattern.findAll(s).forEach { m ->
            val qty = m.groupValues[1].toIntOrNull() ?: 0
            val name = m.groupValues[2].trim()
            val price = m.groupValues[3].toDoubleOrNull() ?: 0.0
            if (qty > 0 && name.isNotBlank() && price > 0) out += VoiceCommand.Add(SaleItem(name, qty, price))
        }

        // Also accept natural shop phrasing such as "Deal 5 at 1470".
        // Here the product name comes first, followed by quantity and total/unit price.
        val nameQtyPricePattern = Regex(
            """(?:^|,|;|\\band\\b|\\bthen\\b)\\s*(.+?)\\s+(\\d+)\\s+(?:price|at)\\s+(?:rs\\s*)?(\\d+(?:\\.\\d+)?)\\s*(?:each)?(?=\\s*(?:,|;|\\band\\b|\\bthen\\b|$))""",
            RegexOption.IGNORE_CASE
        )
        nameQtyPricePattern.findAll(s).forEach { m ->
            val name = m.groupValues[1].trim().removePrefix("add ").removePrefix("order ").trim()
            val qty = m.groupValues[2].toIntOrNull() ?: 0
            val price = m.groupValues[3].toDoubleOrNull() ?: 0.0
            if (qty > 0 && name.isNotBlank() && price > 0) out += VoiceCommand.Add(SaleItem(name, qty, price))
        }

        // Detect products directly from the configured Menu, including natural phrases
        // such as "2 chicken shawarma", "add chicken shawarma", and variant/size names.
        menu.sortedByDescending { (it.name + " " + it.variant + " " + it.size).length }.forEach { product ->
            val label = listOf(product.name, product.variant, product.size)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val escaped = Regex.escape(label.lowercase(Locale.US))
            val nameEscaped = Regex.escape(product.name.lowercase(Locale.US))
            Regex("""(?:^|\\b)(\\d+)\\s+(?:x\\s+)?$escaped(?:\\s+each)?(?=$|\\b|,|;|\\band\\b|\\bthen\\b)""", RegexOption.IGNORE_CASE)
                .findAll(s).forEach { m ->
                    val qty = m.groupValues[1].toIntOrNull() ?: 0
                    if (qty > 0) out += VoiceCommand.Add(SaleItem(product.name, qty, product.price))
                }
            if (Regex("""\\b(?:add|order)\\s+(?:\\d+\\s+)?(?:x\\s+)?$escaped(?:\\s+each)?\\b""", RegexOption.IGNORE_CASE).containsMatchIn(s))
                out += VoiceCommand.Add(SaleItem(product.name, 1, product.price))
            if (Regex("""\b(send|print|place) (the )?(order|kitchen)\b|\bplace order\b""").containsMatchIn(s)) out += VoiceCommand.CompletePrint
        return out.distinctBy { it.toString() }
    }
}
