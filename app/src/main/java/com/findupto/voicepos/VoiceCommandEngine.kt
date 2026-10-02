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
            .replace(Regex("""[!?]+"""), " ")
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

        val nameQtyPricePattern = Regex(
            """(?:^|,|;|\band\b|\bthen\b)\s*(.+?)\s+(\d+)\s+(?:price|at)\s+(?:rs\s*)?(\d+(?:\.\d+)?)\s*(?:each)?(?=\s*(?:,|;|\band\b|\bthen\b|$))""",
            RegexOption.IGNORE_CASE
        )
        nameQtyPricePattern.findAll(s).forEach { m ->
            val name = m.groupValues[1].trim().removePrefix("add ").removePrefix("order ").trim()
            val qty = m.groupValues[2].toIntOrNull() ?: 0
            val price = m.groupValues[3].toDoubleOrNull() ?: 0.0
            if (qty > 0 && name.isNotBlank() && price > 0) out += VoiceCommand.Add(SaleItem(name, qty, price))
        }

                // Strong menu detection: normalize punctuation and tolerate natural filler words.
        fun norm(x:String)=x.lowercase(Locale.US).replace("&"," and ").replace(Regex("[^a-z0-9]+")," ").trim().replace(Regex("\\s+")," ")
        val normalized=norm(s)
        menu.sortedByDescending { (it.name+" "+it.variant+" "+it.size).length }.forEach { product ->
            val label=norm(listOf(product.name,product.variant,product.size).filter{it.isNotBlank()}.joinToString(" "))
            val qty=Regex("\\b(\\d+)\\s+(?:x\\s+)?"+Regex.escape(label)+"\\b").find(normalized)?.groupValues?.get(1)?.toIntOrNull()
            val commandQty=Regex("\\b(?:add|order|give|make|take|get)\\s+(?:me\\s+)?(\\d+)?\\s*(?:x\\s+)?"+Regex.escape(label)+"\\b").find(normalized)?.groupValues?.get(1)?.toIntOrNull()
            if(qty!=null && qty>0) out += VoiceCommand.Add(SaleItem(product.name,qty,product.price))
            else if(commandQty!=null && commandQty>0) out += VoiceCommand.Add(SaleItem(product.name,commandQty,product.price))
            else {
                val tokens=norm(product.name).split(" ").filter{it.length>1}
                if(tokens.isNotEmpty() && tokens.all { normalized.contains(Regex("\\b"+Regex.escape(it)+"\\b")) } && !out.any { it is VoiceCommand.Add && it.item.name.equals(product.name,true) })
                    out += VoiceCommand.Add(SaleItem(product.name,1,product.price))
            }
        }
        if (Regex("""\b(send|print|place) (the )?(order|kitchen)\b|\bplace order\b""").containsMatchIn(s)) out += VoiceCommand.CompletePrint
        return out.distinctBy { it.toString() }
    }
}
