package com.findupto.voicepos

import android.content.Context
import org.json.JSONObject

/**
 * Initializes the staff-auth store without installing predictable demo credentials.
 *
 * A fresh installation must create its first OWNER account through the one-time
 * device access code. Existing accounts are deliberately preserved.
 */
object StaffDefaults {
    fun seed(context: Context) {
        val prefs = context.getSharedPreferences("voice_staff_auth", Context.MODE_PRIVATE)
        if (prefs.getBoolean("default_accounts_seeded", false)) return

        val accounts = runCatching {
            JSONObject(prefs.getString("accounts", "{}") ?: "{}")
        }.getOrDefault(JSONObject())

        // Do not create well-known PINs such as 0099, 1122 or 1234.
        // The LAN server already supports a secure first-owner bootstrap using
        // its random device-local access token.
        check(
            prefs.edit()
                .putString("accounts", accounts.toString())
                .putBoolean("default_accounts_seeded", true)
                .commit()
        ) { "Could not initialize the staff authentication store" }

        // Keep the first device in owner mode so it can host the LAN service
        // and expose the first-owner setup flow. No staff account is created here.
        val localStore = Store(context)
        if (localStore.staffRole() == "GUEST") {
            localStore.setStaffSession("Owner", "OWNER")
        }
    }
}
