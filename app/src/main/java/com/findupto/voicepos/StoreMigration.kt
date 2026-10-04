package com.findupto.voicepos

import android.content.Context

object StoreMigration {
    fun migrate(context: Context) {
        val old = context.getSharedPreferences("voice_pos_v4", Context.MODE_PRIVATE)
        val now = context.getSharedPreferences("voice_pos_v5", Context.MODE_PRIVATE)
        if (now.all.isNotEmpty() || old.all.isEmpty()) return
        val e = now.edit()
        old.all.forEach { (k, v) ->
            when (v) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Double -> e.putString(k, v.toString())
                else -> Unit
            }
        }
        e.apply()
    }
}
