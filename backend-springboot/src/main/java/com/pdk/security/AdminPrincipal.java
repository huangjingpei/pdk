package com.pdk.security;

public record AdminPrincipal(
        Long id,
        String username,
        String displayName,
        String roleCode,
        String source,
        Long bizId,
        Integer mustChangePassword) {

    /** 旧 6 参构造（mustChangePassword 默认为 0），保持历史调用点编译通过 */
    public AdminPrincipal(Long id, String username, String displayName, String roleCode, String source, Long bizId) {
        this(id, username, displayName, roleCode, source, bizId, 0);
    }

    /** 旧 5 参构造（bizId/mustChangePassword 都默认空） */
    public AdminPrincipal(Long id, String username, String displayName, String roleCode, String source) {
        this(id, username, displayName, roleCode, source, null, 0);
    }

    public boolean isSuperAdmin() {
        return "SUPER_ADMIN".equals(roleCode);
    }

    public boolean requiresPasswordChange() {
        return mustChangePassword != null && mustChangePassword == 1;
    }
}
