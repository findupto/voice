package com.findupto.voicepos

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Initializes staff authentication without predictable demo credentials.
 *
 * Also removes legacy demo accounts only when their stored hash still matches
 * the original public PIN. Staff who changed their PIN keep their account.
 */
object StaffDefaults {
    private val legacyPins = mapOf(
        "owner" to "0099",
        "admin" to "1122",
        "waiter" to "1234",
        "chef" to "1234"
    )

    fun seed(context: Context) {
        val prefs = context.getSharedPreferences("voice_staff_auth", Context.MODE_PRIVATE)
        if (prefs.getBoolean("default_pin_cleanup_v1", false)) {
            ensureOwnerBootstrap(context)
            return
        }

        val accounts = runCatching {
            JSONObject(prefs.getString("accounts", "{}") ?: "{}")
        }.getOrElse {
            error("Staff authentication data is unreadable; refusing to overwrite it")
        }

        legacyPins.forEach { (key, pin) ->
            val account = accounts.optJSONObject(key) ?: return@forEach
            val salt = account.optString("salt")
            val storedHash = account.optString("hash")
            if (salt.isBlank() || storedHash.isBlank()) return@forEach

            val stillUsingPublicPin = runCatching {
                val actual = hashPin(pin, hexToBytes(salt))
                MessageDigest.isEqual(
                    actual.toByteArray(Charsets.UTF_8),
                    storedHash.toByteArray(Charsets.UTF_8)
                )
            }.getOrDefault(false)

            if (stillUsingPublicPin) accounts.remove(key)
        }

        // A fresh install starts with no staff accounts. The owner creates the
        // first account using the random device-local bootstrap access code.
        check(
            prefs.edit()
                .putString("accounts", accounts.toString())
                .putBoolean("default_accounts_seeded", true)
                .putBoolean("default_pin_cleanup_v1", true)
                .commit()
        ) { "Could not initialize the staff authentication store" }

        ensureOwnerBootstrap(context)
    }

    private fun ensureOwnerBootstrap(context: Context) {
        val localStore = Store(context)
        if (localStore.staffRole() == "GUEST") {
            localStore.setStaffSession("Owner", "OWNER")
        }
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
        } finally {
            spec.clearPassword()
        }
    }

    private fun hexToBytes(value: String): ByteArray {
        require(value.length % 2 == 0 && value.matches(Regex("[0-9a-fA-F]+")))
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
