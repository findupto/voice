package com.findupto.voicepos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RolePermissionsTest {
    @Test fun ownerHasAllCapabilities() {
        assertEquals(RolePermissions.all, RolePermissions.effective("OWNER", emptySet()))
    }

    @Test fun kitchenCannotCreateOrBillOrders() {
        val chef = RolePermissions.defaultsFor("CHEF")
        assertTrue("update_kitchen" in chef)
        assertTrue("view_orders" in chef)
        assertFalse("create_order" in chef)
        assertFalse("update_order_status" in chef)
    }

    @Test fun customPermissionsReplaceRoleDefaults() {
        assertEquals(setOf("view_orders"), RolePermissions.effective("WAITER", setOf("view_orders")))
    }

    @Test fun rejectsUnknownCapabilities() {
        assertFalse(RolePermissions.isValid("grant_everything"))
    }
}
