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
    data class Remove(val name: String) : VoiceCommand()
}

object VoiceCommandEngine {
    private val numberWords = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
    )

    fun parse(raw: String, menu: List<MenuItem> = emptyList()): List<VoiceCommand> {
        var s = raw.lowercase(Locale.US)
            .replace(" rupees ", " rs ")
            .replace(" rupee ", " rs ")
            .replace("rs.", "rs")
            .trim()

        numberWords.forEach { (word, number) ->
            s = s.replace(Regex("\\b$word\\b"), number.toString())
        }

        val out = mutableListOf<VoiceCommand>()
        if (Regex("\\b(clear|empty|cancel) (the )?(cart|order)\\b").containsMatchIn(s)) out += VoiceCommand.Clear
        if (Regex("\\b(show|open|go to) (sales|sale history)\\b").containsMatchIn(s)) out += VoiceCommand.Sales
        if (Regex("\\b(show|open|go to) (expenses|expense)\\b").containsMatchIn(s)) out += VoiceCommand.Expenses
        if (Regex("\\b(show|open|go to) (reports|analysis|dashboard)\\b").containsMatchIn(s)) out += VoiceCommand.Reports
        if (Regex("\\b(open|show) (settings|printer)\\b").containsMatchIn(s)) out += VoiceCommand.Settings
        Regex("(?i)\\b(?:remove|delete) (?:item )?(.+)").find(s)?.let {
            out += VoiceCommand.Remove(it.groupValues[1].trim())
        }

        val itemPattern = Regex(
            """(?:^|,|;|\band\b|\bthen\b|\badd\b)\s*(\d+)\s+(.+?)\s+(?:price|at)\s+(?:rs\s*)?(\d+(?:\.\d+)?)\s*(?:each)?(?=\s*(?:,|;|\band\b|\bthen\b|\badd\b|$))""",
            RegexOption.IGNORE_CASE
        )

        itemPattern.findAll(s).forEach { match ->
            val qty = match.groupValues[1].toIntOrNull() ?: 0
            val name = match.groupValues[2].trim()
            val price = match.groupValues[3].toDoubleOrNull() ?: 0.0
            if (qty > 0 && name.isNotBlank() && price > 0) {
                out += VoiceCommand.Add(SaleItem(name, qty, price))
            }
        }


        if (menu.isNotEmpty()) {
            menu.sortedByDescending { it.name.length }.forEach { product ->
                val escaped = Regex.escape(product.name.lowercase(Locale.US))
                Regex("""(?:^|\\b)(\\d+)\\s+$escaped(?:\\s+each)?(?=$|\\b|,|;|\\band\\b|\\bthen\\b)""", RegexOption.IGNORE_CASE)
                    .findAll(s).forEach { m ->
                        val qty = m.groupValues[1].toIntOrNull() ?: 0
                        if (qty > 0) out += VoiceCommand.Add(SaleItem(product.name, qty, product.price))
                    }
                if (Regex("""(?:^|\\b)add\\s+$escaped(?:\\s+each)?(?=$|\\b|,|;)""", RegexOption.IGNORE_CASE).containsMatchIn(s))
                    out += VoiceCommand.Add(SaleItem(product.name, 1, product.price))
            }
        }

        if (Regex("\\b(send|print) (the )?(order|kitchen)\\b").containsMatchIn(s)) {
            out += VoiceCommand.CompletePrint
        }
        return out
    }
}
