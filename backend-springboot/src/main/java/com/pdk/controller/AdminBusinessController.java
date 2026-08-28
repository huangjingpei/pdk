package com.pdk.controller;

import com.pdk.common.api.CommonResult;
import com.pdk.domain.dto.CreateBusinessDTO;
import com.pdk.domain.dto.UpdateBusinessDTO;
import com.pdk.domain.entity.Business;
import com.pdk.domain.vo.BusinessRuntimeVO;
import com.pdk.platform.business.BusinessService;
import com.pdk.security.AdminPrincipal;
import com.pdk.security.RequirePermission;
import com.pdk.security.RolePermissions;
import com.pdk.service.AdminAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/business")
@RequiredArgsConstructor
public class AdminBusinessController {
    private final BusinessService businessService;
    private final AdminAuditService auditService;

    @GetMapping("/list")
    @RequirePermission(RolePermissions.BUSINESS_VIEW)
    public CommonResult<List<BusinessRuntimeVO>> list() {
        return CommonResult.success(businessService.listRuntime());
    }

    @PostMapping
    @RequirePermission(RolePermissions.BUSINESS_EDIT)
    public CommonResult<Business> create(@Valid @RequestBody CreateBusinessDTO dto, HttpServletRequest request) {
        Business value = businessService.create(dto);
        auditService.record(principal(request), value.getId(), "CREATE_BUSINESS", "BUSINESS",
                value.getId().toString(), null, "{\"bizCode\":\"" + value.getBizCode() + "\"}",
                "创建业务（默认关闭）", request);
        return CommonResult.success(value, "业务已创建；请先配置部署白名单，再启用业务");
    }

    @PutMapping("/{bizId}")
    @RequirePermission(RolePermissions.BUSINESS_EDIT)
    public CommonResult<Business> update(@PathVariable long bizId, @Valid @RequestBody UpdateBusinessDTO dto,
                                         HttpServletRequest request) {
        Business before = businessService.requireById(bizId);
        String beforeState = "{\"name\":\"" + before.getBizName() + "\",\"registrationMode\":\""
                + before.getRegistrationMode() + "\"}";
        Business value = businessService.update(bizId, dto);
        auditService.record(principal(request), bizId, "UPDATE_BUSINESS", "BUSINESS", String.valueOf(bizId),
                beforeState, "{\"name\":\"" + value.getBizName() + "\",\"registrationMode\":\""
                        + value.getRegistrationMode() + "\"}", "更新业务配置", request);
        return CommonResult.success(value);
    }

    @PutMapping("/{bizId}/status")
    @RequirePermission(RolePermissions.BUSINESS_EDIT)
    public CommonResult<Business> status(@PathVariable long bizId, @RequestParam boolean enabled,
                                         @RequestParam String reason, HttpServletRequest request) {
        if (reason == null || reason.trim().length() < 2) {
            throw new com.pdk.common.exception.BusinessException(40001, "业务开关原因至少填写2个字符");
        }
        Business before = businessService.requireById(bizId);
        Business value = businessService.setEnabled(bizId, enabled);
        auditService.record(principal(request), bizId, enabled ? "ENABLE_BUSINESS" : "DISABLE_BUSINESS",
                "BUSINESS", String.valueOf(bizId), "{\"status\":\"" + before.getStatus() + "\"}",
                "{\"status\":\"" + value.getStatus() + "\"}", reason, request);
        return CommonResult.success(value, enabled ? "业务已启用" : "业务已关闭");
    }

    private AdminPrincipal principal(HttpServletRequest request) {
        return (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
    }
}
