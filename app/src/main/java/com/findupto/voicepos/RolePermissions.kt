package com.findupto.voicepos

/**
 * Server-side capability policy. UI visibility is never treated as authorization:
 * every LAN operation must also pass this policy on the owner device.
 */
object RolePermissions {
    val all = setOf(
        "view_orders", "create_order", "update_order_status", "update_kitchen",
        "view_messages", "send_messages", "view_shifts", "manage_shifts",
        "view_audit", "view_financials", "manage_menu", "manage_inventory",
        "manage_discounts", "manage_staff"
    )

    private val defaults = mapOf(
        "OWNER" to all,
        "ADMIN" to all - setOf("view_financials"),
        "COUNTER" to setOf("view_orders", "update_order_status", "view_messages", "send_messages", "view_shifts"),
        "WAITER" to setOf("view_orders", "create_order", "update_order_status", "view_messages", "send_messages"),
        "CHEF" to setOf("view_orders", "update_kitchen", "view_messages", "send_messages"),
        "DISHWASHER" to setOf("view_orders", "view_messages", "send_messages"),
        "RIDER" to setOf("view_orders", "update_order_status", "view_messages", "send_messages")
    )

    fun defaultsFor(role: String): Set<String> = defaults[role.uppercase()] ?: emptySet()

    fun effective(role: String, custom: Set<String>?): Set<String> =
        if (role.uppercase() == "OWNER") all else custom ?: defaultsFor(role)

    fun isValid(permission: String): Boolean = permission in all
}
