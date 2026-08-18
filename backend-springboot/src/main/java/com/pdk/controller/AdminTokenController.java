package com.pdk.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pdk.common.api.CommonResult;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.TokenResourceDTO;
import com.pdk.domain.entity.TokenPool;
import com.pdk.mapper.TokenPoolMapper;
import com.pdk.security.RequirePermission;
import com.pdk.security.RolePermissions;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.pdk.security.AdminPrincipal;
import com.pdk.service.AdminAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1/admin/token")
@RequiredArgsConstructor
public class AdminTokenController {
    private final TokenPoolMapper tokenPoolMapper;
    private final AdminAuditService adminAuditService;

    @GetMapping("/list")
    @RequirePermission(RolePermissions.TOKEN_VIEW)
    public CommonResult<Page<TokenPool>> list(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(required = false) String status) {
        LambdaQueryWrapper<TokenPool> query = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            query.eq(TokenPool::getHealthStatus, status);
        }
        query.orderByDesc(TokenPool::getCreatedAt);
        Page<TokenPool> result = tokenPoolMapper.selectPage(new Page<>(page, Math.min(size, 100)), query);
        result.getRecords().forEach(this::maskSecret);
        return CommonResult.success(result);
    }

    @PostMapping
    @RequirePermission(RolePermissions.TOKEN_EDIT)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<TokenPool> create(@Valid @RequestBody TokenResourceDTO dto, HttpServletRequest request) {
        TokenPool token = new TokenPool();
        token.setAccountAlias(dto.getAccountAlias());
        token.setTokenVal(dto.getTokenVal());
        token.setHealthStatus("HEALTHY");
        token.setDailyCallsCount(0);
        token.setDailyMaxCapacity(dto.getDailyMaxCapacity());
        token.setRiskScore(0);
        tokenPoolMapper.insert(token);
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        adminAuditService.record(admin, "CREATE_TOKEN_RESOURCE", "ACCOUNT", token.getId().toString(), null,
                "{\"alias\":\"" + token.getAccountAlias() + "\",\"capacity\":" + token.getDailyMaxCapacity() + "}",
                "录入小号资源", request);
        maskSecret(token);
        return CommonResult.success(token, "资源已加入公共池");
    }

    @PutMapping("/{id}/status")
    @RequirePermission(RolePermissions.TOKEN_EDIT)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<String> changeStatus(@PathVariable Long id, @RequestParam String status,
                                              HttpServletRequest request) {
        if (!java.util.Set.of("HEALTHY", "FAULT_BLACK", "EXPIRED").contains(status)) {
            throw new BusinessException(40030, "资源状态不合法");
        }
        TokenPool token = tokenPoolMapper.selectById(id);
        if (token == null) {
            throw new BusinessException(40401, "资源不存在");
        }
        String beforeStatus = token.getHealthStatus();
        token.setHealthStatus(status);
        if ("HEALTHY".equals(status)) {
            token.setRiskScore(0);
            token.setDailyCallsCount(0);
        }
        tokenPoolMapper.updateById(token);
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        adminAuditService.record(admin, "CHANGE_TOKEN_STATUS", "ACCOUNT", id.toString(),
                "{\"status\":\"" + beforeStatus + "\"}", "{\"status\":\"" + status + "\"}",
                "调整小号资源状态", request);
        return CommonResult.success("资源状态已更新");
    }

    private void maskSecret(TokenPool token) {
        String value = token.getTokenVal();
        if (value == null || value.length() < 9) {
            token.setTokenVal("********");
            return;
        }
        token.setTokenVal(value.substring(0, 4) + "****" + value.substring(value.length() - 4));
    }
}
