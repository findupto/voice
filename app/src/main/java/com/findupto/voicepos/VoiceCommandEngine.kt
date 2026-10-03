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
    private val numberWords=mapOf("zero" to 0,"one" to 1,"two" to 2,"to" to 2,"too" to 2,"three" to 3,"four" to 4,"for" to 4,"five" to 5,"six" to 6,"seven" to 7,"eight" to 8,"ate" to 8,"nine" to 9,"ten" to 10,"eleven" to 11,"twelve" to 12)
    private val filler=Regex("\\b(please|kindly|can you|could you|i want|i need|give me|add|order|make|get|take|put|include|for me)\\b",RegexOption.IGNORE_CASE)
    private fun norm(x:String)=x.lowercase(Locale.US).replace("&"," and ").replace(Regex("[^a-z0-9]+")," ").trim().replace(Regex("\\s+")," ")
    private fun distance(a:String,b:String):Int{val d=Array(a.length+1){IntArray(b.length+1)};for(i in 0..a.length)d[i][0]=i;for(j in 0..b.length)d[0][j]=j;for(i in 1..a.length)for(j in 1..b.length)d[i][j]=minOf(d[i-1][j]+1,d[i][j-1]+1,d[i-1][j-1]+if(a[i-1]==b[j-1])0 else 1);return d[a.length][b.length]}
    private fun tokenScore(a:String,b:String):Double{if(a==b)return 1.0;if(a.length<3||b.length<3)return 0.0;if(a.contains(b)||b.contains(a))return .82;val d=distance(a,b);return if(d<=2) .78 else if(d<=3&&minOf(a.length,b.length)>=6).62 else 0.0}
    private fun bestMenu(text:String,menu:List<MenuItem>):MenuItem?{val q=norm(text);if(q.isBlank()||menu.isEmpty())return null;val qw=q.split(" ");return menu.map{p->val labels=listOf(p.name,p.variant,p.size).filter{it.isNotBlank()}.map(::norm);val lw=labels.flatMap{it.split(" ")};val scores=qw.map{a->lw.maxOfOrNull{b->tokenScore(a,b)}?:0.0};val matched=scores.count{it>=.62};val score=scores.sum()/qw.size.coerceAtLeast(1);Triple(p,score,matched)}.filter{it.third>0}.maxWithOrNull(compareBy<Triple<MenuItem,Double,Int>>{it.second}.thenBy{it.third})?.takeIf{it.third>=1&& (it.second>=.58 || (qw.size==1&&it.second>=.75))}?.first}
    private fun normalizeNumbers(input:String):String{var s=input;numberWords.forEach{(w,n)->s=s.replace(Regex("\\b${Regex.escape(w)}\\b"),n.toString())};return s}

    fun parse(raw:String,menu:List<MenuItem> = emptyList()):List<VoiceCommand>{
        var s=normalizeNumbers(norm(raw)).replace(Regex("\\b(rupees?|rs|pkr|price|cost|worth)\\b")," rs ").replace(Regex("\\s+")," ").trim()
        val out=mutableListOf<VoiceCommand>()
        if(Regex("\\b(clear|empty|cancel|start over|reset)\\b.*\\b(cart|order)\\b|\\bnew order\\b").containsMatchIn(s))out+=VoiceCommand.Clear
        if(Regex("\\b(show|open|go to)\\s+(sales|sale history)\\b").containsMatchIn(s))out+=VoiceCommand.Sales
        if(Regex("\\b(show|open|go to)\\s+(expenses|expense)\\b").containsMatchIn(s))out+=VoiceCommand.Expenses
        if(Regex("\\b(show|open|go to)\\s+(reports|analysis|dashboard|analytics)\\b").containsMatchIn(s))out+=VoiceCommand.Reports
        if(Regex("\\b(show|open|go to)\\s+(menu|products)\\b").containsMatchIn(s))out+=VoiceCommand.Menu
        if(Regex("\\b(show|open|go to)\\s+(kitchen|orders)\\b").containsMatchIn(s))out+=VoiceCommand.Kitchen
        if(Regex("\\b(open|show)\\s+(settings|printer)\\b").containsMatchIn(s))out+=VoiceCommand.Settings
        if(Regex("\\b(pay|paid|payment|settle)\\s+(last|latest|order)\\b|\\bpay last\\b").containsMatchIn(s))out+=VoiceCommand.PayLast
        if(Regex("\\b(checkout|complete|finish|place|send|print)\\b.*\\b(order|bill|kitchen|receipt)\\b|\\bplace order\\b").containsMatchIn(s))out+=VoiceCommand.CompletePrint
        Regex("\\b(?:remove|delete|cancel)\\s+(?:item|product)?\\s*(.+)$").find(s)?.let{out+=VoiceCommand.Remove(it.groupValues[1].trim())}
        Regex("\\b(?:increase|add)\\s+(?:quantity of )?(.+?)\\s+by\\s+(\\d+)\\b").find(s)?.let{out+=VoiceCommand.Quantity(it.groupValues[1].trim(),it.groupValues[2].toInt())}
        Regex("\\b(?:decrease|reduce)\\s+(?:quantity of )?(.+?)\\s+by\\s+(\\d+)\\b").find(s)?.let{out+=VoiceCommand.Quantity(it.groupValues[1].trim(),-it.groupValues[2].toInt())}

        val segments=s.split(Regex("\\s*(?:,|;|\\band then\\b|\\bthen\\b)\\s*")).map{it.trim()}.filter{it.isNotBlank()}
        val priceRx=Regex("(?:\\brs\\s*)?(\\d+(?:[.,]\\d{1,2})?)\\s*(?:each|per item)?$",RegexOption.IGNORE_CASE)
        for(seg0 in segments){
            var seg=seg0.replace(filler," ").replace(Regex("\\s+")," ").trim()
            val explicitPrice=priceRx.find(seg)?.groupValues?.get(1)?.replace(",","")?.toDoubleOrNull()
            if(priceRx.containsMatchIn(seg))seg=seg.substring(0,priceRx.find(seg)!!.range.first).trim()
            seg=seg.replace(Regex("\\b(rs|price|cost|at|for)\\s*$"),"").trim()
            if(seg.isBlank())continue
            val qm=Regex("^(\\d+)\\s*(?:x|times)?\\s+(.+)$",RegexOption.IGNORE_CASE).find(seg)
            val qty=(qm?.groupValues?.get(1)?.toIntOrNull()?:1).coerceAtLeast(1)
            val name=norm((qm?.groupValues?.get(2)?.trim()?:seg)).replace(Regex("^(?:of|for)\\s+"),"").trim()
            if(name.isBlank()||name.all{it.isDigit()||it==' '})continue
            val product=bestMenu(name,menu)
            if(product!=null){
                // An explicit spoken price is intentional: use it rather than silently replacing it with the menu price.
                out+=VoiceCommand.Add(SaleItem(product.name,qty,explicitPrice?:product.price))
            }else if(explicitPrice!=null&&explicitPrice>0){out+=VoiceCommand.Add(SaleItem(name,qty,explicitPrice))}
        }
        return out.distinctBy{it.toString()}
    }
}
