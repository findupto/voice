package com.findupto.voicepos

import java.util.Locale

sealed class VoiceCommand {
    data class Add(val item: SaleItem): VoiceCommand()
    data object CompletePrint: VoiceCommand()
    data object Complete: VoiceCommand()
    data object Clear: VoiceCommand()
    data object Sales: VoiceCommand()
    data object Expenses: VoiceCommand()
    data object Reports: VoiceCommand()
    data object Settings: VoiceCommand()
    data class Remove(val name:String): VoiceCommand()
}

object VoiceCommandEngine {
    private val words=mapOf("zero" to 0,"one" to 1,"two" to 2,"three" to 3,"four" to 4,"five" to 5,"six" to 6,"seven" to 7,"eight" to 8,"nine" to 9,"ten" to 10)
    fun parse(raw:String):List<VoiceCommand>{
        var s=raw.lowercase(Locale.US).replace(" rupees "," rs ").replace(" rupee "," rs ")
        words.forEach{(w,n)->s=s.replace(Regex("\\b"+w+"\\b"),n.toString())}
        val out=mutableListOf<VoiceCommand>()
        if(Regex("\\b(clear|empty|cancel) (the )?(cart|order)\\b").containsMatchIn(s)) out+=VoiceCommand.Clear
        if(Regex("\\b(show|open|go to) (sales|sale history)\\b").containsMatchIn(s)) out+=VoiceCommand.Sales
        if(Regex("\\b(show|open|go to) (expenses|expense)\\b").containsMatchIn(s)) out+=VoiceCommand.Expenses
        if(Regex("\\b(show|open|go to) (reports|analysis|dashboard)\\b").containsMatchIn(s)) out+=VoiceCommand.Reports
        if(Regex("\\b(open|show) (settings|printer)\\b").containsMatchIn(s)) out+=VoiceCommand.Settings
        val remove=Regex("(?i)(?:remove|delete) (?:item )?(.+)").find(s); if(remove!=null) out+=VoiceCommand.Remove(remove.groupValues[1].trim())
        val pat=Regex("""(?i)(?:^|,|;|\band\b|\bthen\b)\s*(\d+)\s+(.+?)(?:\s+(?:price|at)\s+|\s+)(?:rs\.?\s*)?(\d+(?:\.\d+)?)(?:\s+each)?(?=,|;|\band\b|\bthen\b|$)""")
        pat.findAll(s).forEach{m->val q=m.groupValues[1].toIntOrNull()?:0;val n=m.groupValues[2].trim().removeSuffix("price").trim();val pr=m.groupValues[3].toDoubleOrNull()?:0.0;if(q>0&&n.isNotBlank()&&pr>0)out+=VoiceCommand.Add(SaleItem(n,q,pr))}
        if(Regex("\\bcomplete (the )?(sale|order|bill)\\b").containsMatchIn(s)) out+=if(Regex("\\bprint\\b").containsMatchIn(s)) VoiceCommand.CompletePrint else VoiceCommand.Complete
        return out
    }
}
