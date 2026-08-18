package com.pdk.service;

import com.pdk.domain.entity.AdminUser;
import com.pdk.domain.entity.PdkAdminAuditLog;
import com.pdk.mapper.PdkAdminAuditLogMapper;
import com.pdk.security.AdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminAuditService {
    private final PdkAdminAuditLogMapper auditLogMapper;

    public void record(AdminUser admin, String actionType, String targetType, String targetId,
                       String beforeState, String afterState, String reason, HttpServletRequest request) {
        PdkAdminAuditLog log = new PdkAdminAuditLog();
        log.setAdminName(admin.getUsername());
        log.setAdminRole(admin.getRoleCode());
        log.setActionType(actionType);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setBeforeState(beforeState);
        log.setAfterState(afterState);
        log.setReason(reason == null || reason.isBlank() ? "管理后台操作" : reason);
        log.setIpAddress(clientIp(request));
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    public void record(AdminPrincipal admin, String actionType, String targetType, String targetId,
                       String beforeState, String afterState, String reason, HttpServletRequest request) {
        PdkAdminAuditLog log = new PdkAdminAuditLog();
        log.setAdminName(admin.username());
        log.setAdminRole(admin.roleCode());
        log.setActionType(actionType);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setBeforeState(beforeState);
        log.setAfterState(afterState);
        log.setReason(reason == null || reason.isBlank() ? "管理后台操作" : reason);
        log.setIpAddress(clientIp(request));
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "127.0.0.1" : request.getRemoteAddr();
    }
}
