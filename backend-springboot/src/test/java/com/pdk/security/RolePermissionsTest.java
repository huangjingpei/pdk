package com.pdk.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RolePermissionsTest {
    @Test
    void superAdminHasAllPermissions() {
        assertTrue(RolePermissions.has("SUPER_ADMIN", RolePermissions.ADMIN_MANAGE));
        assertTrue(RolePermissions.has("SUPER_ADMIN", RolePermissions.FINANCE_EDIT));
        assertTrue(RolePermissions.has("SUPER_ADMIN", RolePermissions.TOKEN_EDIT));
    }

    @Test
    void partnerCanSellButCannotSeePlatformFinanceOrAssets() {
        assertTrue(RolePermissions.has("PARTNER", RolePermissions.CARD_CREATE));
        assertTrue(RolePermissions.has("PARTNER", RolePermissions.PACKAGE_CREATE));
        assertFalse(RolePermissions.has("PARTNER", RolePermissions.FINANCE_VIEW));
        assertFalse(RolePermissions.has("PARTNER", RolePermissions.TOKEN_VIEW));
    }

    @Test
    void customerHasNoAdminPermissions() {
        assertTrue(RolePermissions.forRole("CUSTOMER").isEmpty());
    }

    @Test
    void unknownRoleHasNoPermissions() {
        assertTrue(RolePermissions.forRole("UNKNOWN").isEmpty());
    }
}
