package com.pdk.security;

public record AdminPrincipal(Long id, String username, String displayName, String roleCode, String source) {
    public boolean isSuperAdmin() {
        return "SUPER_ADMIN".equals(roleCode);
    }
}
