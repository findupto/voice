package com.findupto.voicepos

import java.util.Locale

object MenuScanParser {
    private val headerNoise=Regex("(?i)^(menu|food menu|food|items?|products?|description|name|price|qty|quantity|total|subtotal|sub total|tax|gst|discount|amount|bill|invoice|address|phone|contact|thank|thanks|welcome|www|http|scan|order|delivery|cash|change|date|time|opening|hours|follow|instagram|facebook|whatsapp|copyright|terms|note|notes|all prices|prices).*" )
    private val priceToken=Regex("(?i)(?:rs\\.?|pkr|₨|\\$)?\\s*(\\d{1,6}(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)\\s*(?:rs|pkr)?")
    private val sizeWords=setOf("small","medium","large","regular","jumbo","mini","family","single","double","triple","half","full","quarter","s","m","l","xl","xxl","500ml","750ml","1l","1.5l","2l","250g","500g","1kg","2kg","oz")
    private val variantWords=setOf("classic","special","spicy","mild","hot","cold","grilled","fried","cheese","chicken","beef","mutton","lamb","fish","veg","vegetable","combo","deal","pack","box","plate","cup","glass","plain","extra","zinger","tandoori","peri","peri peri","loaded","crispy","family","jumbo")
    private fun clean(s:String)=s.replace(Regex("[•·*#]+")," ").replace(Regex("[|]+")," ").replace(Regex("\\s+")," ").trim(' ','-','–','—',':','.')
    private fun key(s:String)=s.lowercase(Locale.US).replace(Regex("[^a-z0-9]+")," ").trim()
    private fun noise(s:String):Boolean{val x=clean(s);return x.length<2||x.length>60||headerNoise.matches(x)||x.matches(Regex("^[0-9 .,:/()|_-]+$"))||x.count{it.isDigit()}>x.count{it.isLetter()} }
    private fun normalizeNumber(raw:String)=raw.replace(",","").toDoubleOrNull()
    private fun product(raw:String,price:Double):MenuItem?{
        if(price<=0||price>=10000000)return null
        var n=clean(raw);if(noise(n))return null
        val p=n.split(" ").filter{it.isNotBlank()}.toMutableList();var size="";var variant=""
        while(p.isNotEmpty()){
            val t=p.last().lowercase(Locale.US).trim('.',':')
            when{
                t in sizeWords||t.matches(Regex("\\d+(?:\\.\\d+)?(ml|l|g|kg|oz)"))->{size=p.removeLast()}
                t in variantWords->{variant=p.removeLast()}
                else->break
            }
        }
        n=p.joinToString(" ").trim();if(n.length<2||noise(n))return null
        // A real menu item normally contains at least one alphabetic token.
        if(n.split(" ").none{it.any(Char::isLetter)})return null
        return MenuItem(System.nanoTime(),n,price,variant,size)
    }
    private fun candidatesBeforePrice(line:String,matchStart:Int):List<String>{
        val prefix=clean(line.substring(0,matchStart));if(prefix.isBlank())return emptyList()
        // OCR often places a variant/size between the item and the price. Keep the whole prefix;
        // product() then peels recognized size/variant tokens from the end.
        return listOf(prefix)
    }
    fun parse(text:String):List<MenuItem>{
        val out=mutableListOf<MenuItem>()
        text.lines().forEach { raw0 ->
            val line=clean(raw0);if(noise(line)||line.length>100) return@forEach
            val matches=priceToken.findAll(line).filter{m->normalizeNumber(m.groupValues[1])?.let{it>0&&it<10000000}==true}.toList()
            if(matches.isEmpty()||matches.size>6)return@forEach
            // Strongest case: product name + one price at the end of the OCR line.
            if(matches.size==1){
                val m=matches[0];val price=normalizeNumber(m.groupValues[1])?:return@forEach
                val prefix=clean(line.substring(0,m.range.first));
                if(prefix.isBlank()||noise(prefix))return@forEach
                product(prefix,price)?.let{out+=it};return@forEach
            }
            // Multiple prices: interpret the text between prices as variants/sizes of the same base product.
            val first=matches.first();val base=clean(line.substring(0,first.range.first));if(base.isBlank()||noise(base))return@forEach
            var lastEnd=first.range.last+1
            for(i in matches.indices){
                val m=matches[i];val price=normalizeNumber(m.groupValues[1])?:continue
                val label=if(i==0)base else clean(line.substring(lastEnd,m.range.first))
                val candidate=if(i==0)base else "$base $label"
                product(candidate,price)?.let{out+=it};lastEnd=m.range.last+1
            }
        }
        // De-duplicate OCR repeats and reject suspicious one-letter/noise entries.
        return out.filter{it.name.count(Char::isLetter)>=3}.distinctBy{key(it.name)+"|"+key(it.variant)+"|"+key(it.size)+"|"+String.format(Locale.US,"%.2f",it.price)}
    }
}
