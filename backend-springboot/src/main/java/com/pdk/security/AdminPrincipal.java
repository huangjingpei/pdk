package com.pdk.security;

public record AdminPrincipal(Long id, String username, String displayName, String roleCode, String source, Long bizId) {
    public AdminPrincipal(Long id, String username, String displayName, String roleCode, String source) {
        this(id, username, displayName, roleCode, source, null);
    }
    public boolean isSuperAdmin() {
        return "SUPER_ADMIN".equals(roleCode);
    }
}
