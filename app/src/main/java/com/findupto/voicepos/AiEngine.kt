package com.findupto.voicepos

import com.google.mlkit.genai.prompt.Generation

object AiEngine {
    suspend fun rewriteVoice(raw:String, menu:List<MenuItem>):String? = runCatching {
        val names=menu.joinToString(", "){listOf(it.name,it.variant,it.size).filter{v->v.isNotBlank()}.joinToString(" ")}
        val prompt="""You are the command interpreter for a restaurant POS. Correct speech-recognition errors and return ONLY one short normalized English POS command. Never invent a product. Use the closest product from this menu when possible. Preserve quantities and prices. Examples: 'to sharma 200' -> 'two shawarma price 200'; 'two sharma' -> 'two shawarma'; 'five good wali chai' -> 'five good wali chai'. Menu: $names. Spoken text: $raw"""
        val model=Generation.getClient()
        val response=model.generateContent(prompt)
        response.text?.trim()?.takeIf{it.isNotBlank()}
    }.getOrNull()
}
