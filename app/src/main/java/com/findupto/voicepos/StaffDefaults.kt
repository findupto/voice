package com.findupto.voicepos

import android.content.Context
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Creates the requested demo credentials once, only on a genuinely empty install.
 * PINs are stored as salted PBKDF2 hashes; never overwrite existing accounts.
 */
object StaffDefaults {
    fun seed(context: Context) {
        val prefs = context.getSharedPreferences("voice_staff_auth", Context.MODE_PRIVATE)
        if (prefs.getBoolean("default_accounts_seeded", false)) return
        val existing = runCatching { JSONObject(prefs.getString("accounts", "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        val accounts = existing
        listOf(
            Triple("Owner", "OWNER", "0099"),
            Triple("Admin", "ADMIN", "1122"),
            Triple("Waiter", "WAITER", "1234"),
            Triple("Chef", "CHEF", "1234")
        ).forEach { (name, role, pin) ->
            if (accounts.has(name.lowercase())) return@forEach
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val spec = PBEKeySpec(pin.toCharArray(), salt, 120000, 256)
            val hash = try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
            } finally { spec.clearPassword() }
            val saltHex = salt.joinToString("") { "%02x".format(it) }
            accounts.put(name.lowercase(), JSONObject()
                .put("name", name).put("role", role).put("salt", saltHex)
                .put("hash", hash).put("active", true))
        }
        // Commit both values atomically so a crash cannot mark a partial seed complete.
        check(prefs.edit().putString("accounts", accounts.toString())
            .putBoolean("default_accounts_seeded", true).commit()) {
            "Could not initialize default staff accounts"
        }
        // The first-launch device is the local host; its staff session is still
        // established by signing in with the Owner PIN in the Staff Access dialog.
        val localStore = Store(context)
        if (localStore.staffRole() == "GUEST") localStore.setStaffSession("Owner", "OWNER")
    }
}
