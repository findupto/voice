package com.findupto.voicepos

import java.util.Locale

object MenuScanParser {
    private val ignored = Regex("(?i)^(menu|food|items?|products?|description|name|price|qty|quantity|total|subtotal|sub total|tax|gst|discount|amount|bill|invoice|address|phone|contact|thank|thanks|welcome|www|http|scan|order|delivery|cash|change|date|time).*")
    private val priceToken = Regex("(?i)(?:rs\\.?|pkr|₨)?\\s*(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)\\s*(?:rs|pkr)?")
    private val sizeWords=setOf("small","medium","large","regular","jumbo","mini","family","single","double","triple","half","full","s","m","l","xl","500ml","750ml","1l","250g","500g","1kg","2kg","oz")
    private val variantWords=setOf("classic","special","spicy","mild","hot","cold","grilled","fried","cheese","chicken","beef","mutton","veg","vegetable","combo","deal","pack","box","plate","cup","glass","plain","extra","zinger","tandoori")
    private fun clean(s:String)=s.replace(Regex("[•·*#]+")," ").replace(Regex("\\s+")," ").trim(' ','-','–','—',':')
    private fun key(s:String)=s.lowercase(Locale.US).replace(Regex("[^a-z0-9]+")," ").trim()
    private fun noise(s:String)=s.length<2||ignored.matches(s)||s.matches(Regex("^[0-9 .,:/()|_-]+$"))
    private fun product(raw:String,price:Double):MenuItem?{if(price<=0||price>=10000000)return null;var n=clean(raw);if(noise(n)||n.length>55)return null;val p=n.split(" ").toMutableList();var size="";var variant="";while(p.isNotEmpty()){val t=p.last().lowercase(Locale.US).trim('.',':');when{t in sizeWords||t.matches(Regex("\\d+(ml|l|g|kg|oz)"))->size=p.removeLast();t in variantWords->{variant=p.removeLast()};else->break}};n=p.joinToString(" ").trim();if(n.length<2||noise(n))return null;return MenuItem(System.nanoTime(),n,price,variant,size)}
    fun parse(text:String):List<MenuItem>{val out=mutableListOf<MenuItem>();for(raw in text.lines()){val line=clean(raw);if(noise(line)||line.length>100)continue;val ms=priceToken.findAll(line).toList();if(ms.isEmpty()||ms.size>8)continue;val first=ms.first();val prefix=clean(line.substring(0,first.range.first));if(prefix.isBlank()||noise(prefix))continue
            if(ms.size==1){val price=first.groupValues[1].replace(",","").toDoubleOrNull()?:continue;val candidate=product(prefix,price);if(candidate!=null)out+=candidate;continue}
            var previous=first.range.last+1
            for(i in ms.indices){val m=ms[i];val price=m.groupValues[1].replace(",","").toDoubleOrNull()?:continue;val start=if(i==0)first.range.last+1 else previous;val end=if(i+1<ms.size)ms[i+1].range.first else line.length;val label=clean(line.substring(start,end));val candidate=if(label.isBlank())prefix else "$prefix $label";product(candidate,price)?.let{out+=it};previous=end}
        };return out.distinctBy{key(it.name)+"|"+key(it.variant)+"|"+key(it.size)+"|"+it.price}}
}