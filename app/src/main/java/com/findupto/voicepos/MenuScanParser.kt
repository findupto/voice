package com.findupto.voicepos

import java.util.Locale

object MenuScanParser {
    private val ignored = Regex("(?i)^(menu|food|items?|products?|description|name|price|qty|quantity|total|subtotal|sub total|tax|gst|discount|amount|bill|invoice|address|phone|contact|thank|thanks|www|http).*")
    private val priceToken = Regex("(?i)(?:rs\\.?|pkr)?\\s*(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)\\s*(?:rs|pkr)?")
    private val sizeWords = setOf("small","medium","large","regular","jumbo","mini","family","single","double","triple","half","full","500ml","750ml","1l","250g","500g","1kg","2kg","oz")
    private val variantWords = setOf("classic","special","spicy","mild","hot","cold","grilled","fried","cheese","chicken","beef","mutton","veg","vegetable","combo","deal","pack","box","plate","cup","glass","plain","extra")

    private fun clean(s:String)=s.replace(Regex("[•·*#]+")," ").replace(Regex("\\s+")," ").trim(' ','-','–','—')
    private fun key(s:String)=s.lowercase(Locale.US).replace(Regex("[^a-z0-9]+")," ").trim()
    private fun looksLikeNoise(s:String)=s.length<2||ignored.matches(s)||s.matches(Regex("^[0-9 .,:/-]+$"))

    private fun makeProduct(rawName:String,price:Double):MenuItem? {
        var name=clean(rawName);if(looksLikeNoise(name)||price<=0||price>=10000000)return null
        val parts=name.split(" ").toMutableList();var size="";var variant=""
        while(parts.isNotEmpty()){
            val t=parts.last().lowercase(Locale.US).trim('.',':')
            when {
                t in sizeWords || t.matches(Regex("\\d+(ml|l|g|kg|oz|inch|in)")) -> {size=parts.removeLast();if(size.isNotBlank())break}
                t in variantWords -> {variant=parts.removeLast();if(parts.isEmpty())break}
                else -> break
            }
        }
        name=parts.joinToString(" ").trim();if(name.length<2)return null
        if(name.matches(Regex("(?i)^(total|subtotal|tax|discount|amount|price|bill|menu)$")))return null
        return MenuItem(System.nanoTime(),name,price,variant,size)
    }

    fun parse(text:String):List<MenuItem>{
        val out=mutableListOf<MenuItem>()
        for(raw in text.lines()){
            val line=clean(raw.replace("\\t"," ").replace("|"," "));if(looksLikeNoise(line))continue
            val matches=priceToken.findAll(line).toList();if(matches.isEmpty())continue
            if(matches.size==1){
                val m=matches[0];val price=m.groupValues[1].replace(",","").toDoubleOrNull()?:continue
                makeProduct(line.substring(0,m.range.first),price)?.let{out+=it};continue
            }
            // Multiple prices on one OCR row usually mean a base item with variants/sizes:
            // "Burger Small 500 Large 700" or "Pizza S 900 M 1200 L 1500".
            val prefix=line.substring(0,matches.first().range.first).trim();if(prefix.isBlank())continue
            val base=clean(prefix);var previousEnd=matches.first().range.last+1
            for(i in matches.indices){
                val m=matches[i];val price=m.groupValues[1].replace(",","").toDoubleOrNull()?:continue
                val betweenStart=if(i==0)matches.first().range.last+1 else previousEnd
                val betweenEnd=if(i+1<matches.size)matches[i+1].range.first else line.length
                val label=clean(line.substring(betweenStart,betweenEnd))
                val suffix=label.trim()
                val candidate=if(suffix.isNotBlank())"$base $suffix" else base
                makeProduct(candidate,price)?.let{out+=it}
                previousEnd=betweenEnd
            }
            // If labels were OCR'd after the numbers, keep the base item instead of losing the row.
            if(out.none{key(it.name)==key(base)}){
                val firstPrice=matches.first().groupValues[1].replace(",","").toDoubleOrNull()?:0.0
                makeProduct(base,firstPrice)?.let{out+=it}
            }
        }
        return out.distinctBy{key(it.name)+"|"+key(it.variant)+"|"+key(it.size)+"|"+it.price}
    }
}