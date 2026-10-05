package com.findupto.voicepos

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.runBlocking
import java.util.Locale

object AiEngine {
    private val model by lazy { Generation.getClient() }

    fun status(): String = runCatching {
        when (runBlocking { model.checkStatus() }) {
            FeatureStatus.AVAILABLE -> "READY"
            FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
            FeatureStatus.DOWNLOADING -> "DOWNLOADING"
            else -> "UNAVAILABLE"
        }
    }.getOrElse { "UNAVAILABLE" }

    fun warmUpBlocking(): Boolean = runCatching {
        runBlocking {
            if (model.checkStatus() == FeatureStatus.AVAILABLE) model.warmup() else return@runBlocking
        }
        true
    }.getOrDefault(false)

    private fun normalizeNumbers(text:String):String = text.lowercase(Locale.US)
        .replace(Regex("\\bto\\b"), "two")
        .replace(Regex("\\btoo\\b"), "two")
        .replace(Regex("\\bfor\\b(?=\\s+(?:pizza|burger|shawarma|tea|fries|drink))"), "four")
        .replace(Regex("\\bate\\b"), "eight")

    fun rewriteVoiceBlocking(raw:String,menu:List<MenuItem>):String? = runCatching {
        val cleaned=normalizeNumbers(raw).trim()
        if(cleaned.isBlank()) return@runCatching null
        val names=menu.joinToString(", "){listOf(it.name,it.variant,it.size).filter{v->v.isNotBlank()}.joinToString(" ")}
        val prompt="""
You are the POS intelligence layer for a restaurant point-of-sale app.
The input may be imperfect speech-to-text or typed text.
Your job is to understand intent, correct recognition mistakes, and NEVER invent prices or products.

MENU:
$names

INPUT:
$cleaned

If the input is an actionable POS command, return ONLY one normalized command in plain English.
Examples:
- "to shawarma price 200" -> "two shawarma price 200"
- "two shawarma 200" -> "two shawarma price 200"
- "add chicken pizza to menu 900" -> "add chicken pizza to menu price 900"
- "change shawarma price to 250" -> "change shawarma price to 250"
- "remove burger from menu" -> "remove burger from menu"
- "five good wali chai" -> "five good wali chai"

If the input is a question rather than an action, answer it briefly using only the supplied menu facts.
Do not invent business figures that are not present in the input.
""".trimIndent()
        if (runBlocking { model.checkStatus() } != FeatureStatus.AVAILABLE) return@runCatching null
        runBlocking { model.generateContent(prompt).candidates.firstOrNull()?.text?.trim()?.takeIf{it.isNotBlank()} }
    }.getOrNull()
}
