package com.pdk.controller;

import com.pdk.common.api.CommonResult;
import com.pdk.domain.entity.SystemConfig;
import com.pdk.security.AdminPrincipal;
import com.pdk.security.RequirePermission;
import com.pdk.security.RolePermissions;
import com.pdk.service.AdminAuditService;
import com.pdk.service.SystemConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/system-config")
@RequiredArgsConstructor
public class AdminSystemConfigController {
    private final SystemConfigService systemConfigService;
    private final AdminAuditService adminAuditService;

    /** 返回全部平台配置（含当前值），供「系统设置」页渲染表单。仅超级管理员可访问。 */
    @GetMapping("/list")
    @RequirePermission(RolePermissions.SYSTEM_CONFIG)
    public CommonResult<List<SystemConfig>> list() {
        return CommonResult.success(systemConfigService.listAll());
    }

    /** 批量保存配置（仅更新配置值）。仅超级管理员可操作，并写入管理审计日志。 */
    @PostMapping("/update")
    @RequirePermission(RolePermissions.SYSTEM_CONFIG)
    public CommonResult<String> update(@RequestBody List<SystemConfig> items, HttpServletRequest request) {
        StringBuilder changed = new StringBuilder();
        if (items != null) {
            for (SystemConfig i : items) {
                if (i.getConfigKey() != null && i.getConfigValue() != null) {
                    changed.append(i.getConfigKey()).append('=').append(i.getConfigValue()).append("; ");
                }
            }
        }
        systemConfigService.saveConfigs(items);
        AdminPrincipal principal = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        if (principal != null) {
            adminAuditService.record(principal, "UPDATE", "SYSTEM_CONFIG", "platform",
                    "", changed.toString(), "修改平台系统配置", request);
        }
        return CommonResult.success("配置已保存");
    }
}
