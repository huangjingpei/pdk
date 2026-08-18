package com.pdk.security;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class RolePermissions {
    public static final String DASHBOARD_VIEW = "dashboard:view";
    public static final String FINANCE_VIEW = "finance:view";
    public static final String FINANCE_EDIT = "finance:edit";
    public static final String CARD_VIEW = "card:view";
    public static final String CARD_CREATE = "card:create";
    public static final String TOKEN_VIEW = "token:view";
    public static final String TOKEN_EDIT = "token:edit";
    public static final String USER_VIEW = "user:view";
    public static final String USER_UNBIND = "user:unbind";
    public static final String DISPATCH_VIEW = "dispatch:view";
    public static final String ADMIN_MANAGE = "admin:manage";
    public static final String PARTNER_MANAGE = "partner:manage";
    public static final String PACKAGE_VIEW = "package:view";
    public static final String PACKAGE_CREATE = "package:create";
    public static final String PACKAGE_DISABLE = "package:disable";
    public static final String CARD_RENEW = "card:renew";
    public static final String CARD_VOID = "card:void";
    public static final String SALES_VIEW = "sales:view";

    private static final Set<String> ALL = Set.of(
            DASHBOARD_VIEW, FINANCE_VIEW, FINANCE_EDIT, CARD_VIEW, CARD_CREATE,
            TOKEN_VIEW, TOKEN_EDIT, USER_VIEW, USER_UNBIND, DISPATCH_VIEW, ADMIN_MANAGE,
            PARTNER_MANAGE, PACKAGE_VIEW, PACKAGE_CREATE, PACKAGE_DISABLE, CARD_RENEW, CARD_VOID, SALES_VIEW
    );

    private static final Map<String, Set<String>> MATRIX = Map.of(
            "SUPER_ADMIN", ALL,
            "PARTNER", Set.of(DASHBOARD_VIEW, PACKAGE_VIEW, PACKAGE_CREATE, PACKAGE_DISABLE,
                    CARD_VIEW, CARD_CREATE, CARD_RENEW, CARD_VOID, SALES_VIEW)
    );

    private RolePermissions() {
    }

    public static Set<String> forRole(String roleCode) {
        return new LinkedHashSet<>(MATRIX.getOrDefault(roleCode, Set.of()));
    }

    public static boolean has(String roleCode, String permission) {
        return forRole(roleCode).contains(permission);
    }
}
