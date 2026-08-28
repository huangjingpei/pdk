package com.pdk.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pdk.common.api.CommonResult;
import com.pdk.domain.entity.LoginLog;
import com.pdk.domain.entity.PdkAdminAuditLog;
import com.pdk.mapper.LoginLogMapper;
import com.pdk.mapper.PdkAdminAuditLogMapper;
import com.pdk.security.AdminBusinessScope;
import com.pdk.security.AdminPrincipal;
import com.pdk.security.RequirePermission;
import com.pdk.security.RolePermissions;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 登录日志与管理员操作审计的查询入口。
 * 只提供只读查询，日志由各业务埋点写入，不在这里开放任何写操作。
 */
@RestController
@RequestMapping("/api/v1/admin/logs")
@RequiredArgsConstructor
public class AdminLogController {
    private final LoginLogMapper loginLogMapper;
    private final PdkAdminAuditLogMapper auditLogMapper;
    private final AdminBusinessScope businessScope;

    @GetMapping("/login")
    @RequirePermission(RolePermissions.LOG_VIEW)
    public CommonResult<Page<LoginLog>> loginLogs(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(required = false) String actorType,
                                                  @RequestParam(required = false) String account,
                                                  @RequestParam(required = false) String ip,
                                                  @RequestParam(required = false) String result,
                                                  @RequestParam(required = false) String eventType,
                                                  @RequestParam(required = false) Long bizId,
                                                  @RequestParam(required = false)
                                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
                                                  @RequestParam(required = false)
                                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
                                                  HttpServletRequest request) {
        bizId = businessScope.enforce(principal(request), bizId);
        LambdaQueryWrapper<LoginLog> query = new LambdaQueryWrapper<>();
        if (bizId != null) query.eq(LoginLog::getBizId, bizId);
        if (actorType != null && !actorType.isBlank()) query.eq(LoginLog::getActorType, actorType.trim());
        if (result != null && !result.isBlank()) query.eq(LoginLog::getResult, result.trim());
        if (eventType != null && !eventType.isBlank()) query.eq(LoginLog::getEventType, eventType.trim());
        if (account != null && !account.isBlank()) query.like(LoginLog::getActorAccount, account.trim());
        if (ip != null && !ip.isBlank()) query.like(LoginLog::getIpAddress, ip.trim());
        if (start != null) query.ge(LoginLog::getCreatedAt, start);
        if (end != null) query.le(LoginLog::getCreatedAt, end);
        query.orderByDesc(LoginLog::getCreatedAt);
        return CommonResult.success(loginLogMapper.selectPage(new Page<>(page, Math.min(size, 100)), query));
    }

    /** 单个用户的最近登录记录，供用户管理页抽屉展示。 */
    @GetMapping("/login/user/{userId}")
    @RequirePermission(RolePermissions.LOG_VIEW)
    public CommonResult<List<LoginLog>> userLoginLogs(@PathVariable Long userId,
                                                      @RequestParam(defaultValue = "50") int size,
                                                      HttpServletRequest request) {
        principal(request);
        LambdaQueryWrapper<LoginLog> query = new LambdaQueryWrapper<LoginLog>()
                .eq(LoginLog::getActorType, "CLIENT")
                .eq(LoginLog::getActorId, userId)
                .orderByDesc(LoginLog::getCreatedAt)
                .last("LIMIT " + Math.min(Math.max(size, 1), 200));
        return CommonResult.success(loginLogMapper.selectList(query));
    }

    @GetMapping("/audit")
    @RequirePermission(RolePermissions.LOG_VIEW)
    public CommonResult<Page<PdkAdminAuditLog>> auditLogs(@RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int size,
                                                          @RequestParam(required = false) String adminName,
                                                          @RequestParam(required = false) String actionType,
                                                          @RequestParam(required = false) String targetId,
                                                          @RequestParam(required = false) Long bizId,
                                                          @RequestParam(required = false)
                                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
                                                          @RequestParam(required = false)
                                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
                                                          HttpServletRequest request) {
        bizId = businessScope.enforce(principal(request), bizId);
        LambdaQueryWrapper<PdkAdminAuditLog> query = new LambdaQueryWrapper<>();
        if (bizId != null) query.eq(PdkAdminAuditLog::getBizId, bizId);
        if (actionType != null && !actionType.isBlank()) {
            query.eq(PdkAdminAuditLog::getActionType, actionType.trim());
        }
        if (adminName != null && !adminName.isBlank()) {
            query.like(PdkAdminAuditLog::getAdminName, adminName.trim());
        }
        if (targetId != null && !targetId.isBlank()) {
            query.like(PdkAdminAuditLog::getTargetId, targetId.trim());
        }
        if (start != null) query.ge(PdkAdminAuditLog::getCreatedAt, start);
        if (end != null) query.le(PdkAdminAuditLog::getCreatedAt, end);
        query.orderByDesc(PdkAdminAuditLog::getCreatedAt);
        return CommonResult.success(auditLogMapper.selectPage(new Page<>(page, Math.min(size, 100)), query));
    }

    private AdminPrincipal principal(HttpServletRequest request) {
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        if (admin == null) throw new com.pdk.common.exception.BusinessException(40110, "管理员会话不存在");
        return admin;
    }
}
