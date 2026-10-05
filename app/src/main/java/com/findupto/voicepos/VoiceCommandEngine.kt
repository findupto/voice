package com.findupto.voicepos

import java.util.Locale

sealed class VoiceCommand {
    data class Add(val item: SaleItem): VoiceCommand()
    data class MenuAdd(val item: MenuItem): VoiceCommand()
    data class MenuPrice(val name:String,val price:Double): VoiceCommand()
    data class MenuRemove(val name:String): VoiceCommand()
    data object CompletePrint: VoiceCommand()
    data object Complete: VoiceCommand()
    data object Clear: VoiceCommand()
    data object Sales: VoiceCommand()
    data object Expenses: VoiceCommand()
    data object Reports: VoiceCommand()
    data object Settings: VoiceCommand()
    data object Menu: VoiceCommand()
    data object Kitchen: VoiceCommand()
    data object Analytics: VoiceCommand()
    data object NewOrder: VoiceCommand()
    data object PayLast: VoiceCommand()
    data class Remove(val name:String): VoiceCommand()
    data class Quantity(val name:String,val delta:Int): VoiceCommand()
}

object VoiceCommandEngine {
    private val nums=mapOf("zero" to 0,"oh" to 0,"one" to 1,"won" to 1,"two" to 2,"to" to 2,"too" to 2,"three" to 3,"tree" to 3,"four" to 4,"for" to 4,"five" to 5,"six" to 6,"seven" to 7,"eight" to 8,"ate" to 8,"nine" to 9,"ten" to 10,"eleven" to 11,"twelve" to 12,"thirteen" to 13,"fourteen" to 14,"fifteen" to 15,"sixteen" to 16,"seventeen" to 17,"eighteen" to 18,"nineteen" to 19,"twenty" to 20)
    private val filler=Regex("\\b(please|kindly|can you|could you|i want|i need|give me|add|order|make|get|take|put|include|for me|menu item|product|items?|of)\\b",RegexOption.IGNORE_CASE)
    private fun norm(x:String)=x.lowercase(Locale.US).replace("&"," and ").replace(Regex("[^a-z0-9]+")," ").trim().replace(Regex("\\s+")," ")
    private fun phonetic(x:String)=norm(x).replace(" ","").replace(Regex("[aeiou]"),"")
    private fun distance(a:String,b:String):Int{val d=Array(a.length+1){IntArray(b.length+1)};for(i in 0..a.length)d[i][0]=i;for(j in 0..b.length)d[0][j]=j;for(i in 1..a.length)for(j in 1..b.length)d[i][j]=minOf(d[i-1][j]+1,d[i][j-1]+1,d[i-1][j-1]+if(a[i-1]==b[j-1])0 else 1);return d[a.length][b.length]}
    private fun tokenScore(a:String,b:String):Double{if(a==b)return 1.0;if(a.length<2||b.length<2)return 0.0;if(a.contains(b)||b.contains(a))return .90;if(phonetic(a)==phonetic(b))return .95;val d=distance(a,b);return when{d<=1->.93;d<=2->.86;d<=3&&minOf(a.length,b.length)>=5->.78;d<=4&&minOf(a.length,b.length)>=7->.68;else->0.0}}
    private fun bestMenu(text:String,menu:List<MenuItem>):MenuItem?{val q=norm(text).split(" ").filter{it.isNotBlank()&&!it.matches(Regex("\\d+"))};if(q.isEmpty()||menu.isEmpty())return null;return menu.map{p->val words=listOf(p.name,p.variant,p.size).filter{it.isNotBlank()}.flatMap{norm(it).split(" ")};val scores=q.map{a->words.maxOfOrNull{b->tokenScore(a,b)}?:0.0};val coverage=scores.count{it>=.62};val avg=if(scores.isEmpty())0.0 else scores.average();Triple(p,avg,coverage)}.filter{it.third>=1}.maxWithOrNull(compareBy<Triple<MenuItem,Double,Int>>{it.third}.thenBy{it.second})?.takeIf{it.second>=.62}?.first}
    private fun numbers(s:String)=nums.entries.fold(s){x,(w,n)->x.replace(Regex("\\b${Regex.escape(w)}\\b"),n.toString())}
    private fun embeddedQty(s:String,menu:List<MenuItem>):Pair<Int,String>{
        val numeric=Regex("\\b(\\d+)\\b").find(s);if(numeric!=null){val q=numeric.groupValues[1].toIntOrNull()?.coerceIn(1,999)?:1;return q to s.removeRange(numeric.range).replace(Regex("\\s+")," ").trim()}
        return 1 to s
    }
    private fun splitOrderSegments(s:String):List<String>{val normalized=s.replace(Regex("\\s+and\\s+(?=(?:\\d+|zero|one|two|three|four|five|six|seven|eight|nine|ten)\\b"),",");return normalized.split(Regex("\\s*(?:,|;|\\bthen\\b|\\band then\\b)\\s*")).map{it.trim()}.filter{it.isNotBlank()}}
    private fun looksLikeQuickSale(raw:String,segment:String,price:Double?):Boolean{if(price==null)return false;val r=norm(raw);return Regex("\\b(add|order|take|give|sell|make|put|buy|need|want)\\b").containsMatchIn(r)||Regex("\\b(price|rupees?|rs|pkr)\\b").containsMatchIn(r)||Regex("\\b\\d+\\s+[a-z]",RegexOption.IGNORE_CASE).containsMatchIn(r)}
    private fun menuItemFromVoice(name:String,price:String):MenuItem?=price.replace(",","").toDoubleOrNull()?.let{p->name.trim().takeIf{it.isNotBlank()}?.let{MenuItem(System.currentTimeMillis(),it,p)}}
    fun parse(raw:String,menu:List<MenuItem> = emptyList()):List<VoiceCommand>{
        var s=numbers(norm(raw)).replace(Regex("\\b(rupees?|rs|pkr|price|cost|worth)\\b")," rs ").replace(Regex("\\s+")," ").trim();val out=mutableListOf<VoiceCommand>()
        if(Regex("\\b(clear|empty|cancel|start over|reset)\\b.*\\b(cart|order)\\b|\\bnew order\\b").containsMatchIn(s))out+=VoiceCommand.Clear
        if(Regex("\\b(show|open|go to)\\s+(sales|sale history|history)\\b").containsMatchIn(s))out+=VoiceCommand.Sales
        if(Regex("\\b(show|open|go to)\\s+(expenses|expense)\\b").containsMatchIn(s))out+=VoiceCommand.Expenses
        if(Regex("\\b(show|open|go to)\\s+(reports|analysis|dashboard|analytics)\\b").containsMatchIn(s))out+=VoiceCommand.Reports
        if(Regex("\\b(show|open|go to)\\s+(menu|products)\\b").containsMatchIn(s))out+=VoiceCommand.Menu
        if(Regex("\\b(show|open|go to)\\s+(kitchen|orders)\\b").containsMatchIn(s))out+=VoiceCommand.Kitchen
        if(Regex("\\b(open|show)\\s+(settings|printer)\\b").containsMatchIn(s))out+=VoiceCommand.Settings
        if(Regex("\\b(pay|paid|payment|settle)\\s+(last|latest|order)\\b|\\bpay last\\b").containsMatchIn(s))out+=VoiceCommand.PayLast
        if(Regex("\\b(checkout|complete|finish|place|send|print)\\b.*\\b(order|bill|kitchen|receipt)\\b|\\bplace order\\b").containsMatchIn(s))out+=VoiceCommand.CompletePrint
        Regex("\\b(?:remove|delete|cancel)\\s+(.+?)\\s+(?:from|off)\\s+(?:the )?menu\\b").find(s)?.let{out+=VoiceCommand.MenuRemove(it.groupValues[1].trim())}
        Regex("\\b(?:remove|delete|cancel)\\s+(?:item|product)?\\s*(.+)$").find(s)?.let{out+=VoiceCommand.Remove(it.groupValues[1].trim())}
        Regex("\\b(?:increase|add)\\s+(?:quantity of )?(.+?)\\s+by\\s+(\\d+)\\b").find(s)?.let{out+=VoiceCommand.Quantity(it.groupValues[1].trim(),it.groupValues[2].toInt())}
        Regex("\\b(?:decrease|reduce)\\s+(?:quantity of )?(.+?)\\s+by\\s+(\\d+)\\b").find(s)?.let{out+=VoiceCommand.Quantity(it.groupValues[1].trim(),-it.groupValues[2].toInt())}
        Regex("\\b(?:change|update|set)\\s+(.+?)\\s+(?:price|cost)\\s+(?:to|at|=|rs)?\\s*(\\d+(?:[.,]\\d{1,2})?)\\b").find(s)?.let{out+=VoiceCommand.MenuPrice(it.groupValues[1].trim(),it.groupValues[2].replace(",","").toDouble())}
        Regex("\\b(?:change|update|set)\\s+(.+?)\\s+(?:to|at)\\s+(\\d+(?:[.,]\\d{1,2})?)\\b").find(s)?.let{out+=VoiceCommand.MenuPrice(it.groupValues[1].trim(),it.groupValues[2].replace(",","").toDouble())}
        val menuPatterns=listOf(
            Regex("\\b(?:add|create)\\s+(.+?)\\s+(?:to|in)\\s+(?:the )?menu\\s+(?:at|price|for|rs)?\\s*(\\d+(?:[.,]\\d{1,2})?)\\b"),
            Regex("\\b(?:add|create)\\s+(.+?)\\s+(?:to|in)\\s+(?:the )?menu\\s+(?:with )?(?:price|at|for|rs)?\\s*(\\d+(?:[.,]\\d{1,2})?)\\b"),
            Regex("\\b(?:add|create)\\s+(.+?)\\s+(\\d+(?:[.,]\\d{1,2})?)\\s+(?:to|in)\\s+(?:the )?menu\\b")
        )
        for(rx in menuPatterns){rx.find(raw.lowercase(Locale.US))?.let{menuItemFromVoice(it.groupValues[1].trim(),it.groupValues[2])?.let{m->out+=VoiceCommand.MenuAdd(m)}};if(out.any{it is VoiceCommand.MenuAdd})break}
        val segments=splitOrderSegments(s);val priceRx=Regex("(?:\\brs\\s*)?(\\d+(?:[.,]\\d{1,2})?)\\s*(?:each|per item)?$",RegexOption.IGNORE_CASE)
        for(seg0 in segments){var seg=seg0.replace(filler," ").replace(Regex("\\s+")," ").trim();val pm=priceRx.find(seg);val price=pm?.groupValues?.get(1)?.replace(",","")?.toDoubleOrNull();if(pm!=null)seg=seg.substring(0,pm.range.first).trim();seg=seg.replace(Regex("\\b(rs|price|cost|at|for)\\s*$"),"").trim();if(seg.isBlank())continue;val(qty,withoutQty)=embeddedQty(seg,menu);val name=norm(withoutQty).replace(Regex("^(?:of|for)\\s+"),"").trim();if(name.isBlank())continue;val product=bestMenu(name,menu);if(product!=null){out+=VoiceCommand.Add(SaleItem(product.name,qty,price?:product.price))}else if(looksLikeQuickSale(raw,seg,price)){out+=VoiceCommand.Add(SaleItem(name.replaceFirstChar{if(it.isLowerCase())it.titlecase(Locale.US) else it.toString()},qty,price!!))}}
        return out.distinctBy{it.toString()}
    }
}
