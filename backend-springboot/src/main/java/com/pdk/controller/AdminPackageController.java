package com.pdk.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pdk.common.api.CommonResult;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.CreatePackagePlanDTO;
import com.pdk.domain.entity.PackagePlan;
import com.pdk.mapper.PackagePlanMapper;
import com.pdk.security.AdminPrincipal;
import com.pdk.security.RequirePermission;
import com.pdk.security.RolePermissions;
import com.pdk.service.AdminAuditService;
import com.pdk.platform.business.BusinessContext;
import com.pdk.platform.business.BusinessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/package")
@RequiredArgsConstructor
public class AdminPackageController {
    private final PackagePlanMapper packagePlanMapper;
    private final AdminAuditService auditService;
    private final BusinessService businessService;
    private final com.pdk.security.AdminBusinessScope businessScope;

    @GetMapping("/list")
    @RequirePermission(RolePermissions.PACKAGE_VIEW)
    public CommonResult<List<PackagePlan>> list(HttpServletRequest request,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) Long bizId,
                                                 @RequestParam(required = false) Long appId) {
        AdminPrincipal principal = principal(request);
        LambdaQueryWrapper<PackagePlan> query = new LambdaQueryWrapper<>();
        if (appId != null) bizId = businessService.requireByAppId(appId).getId();
        bizId = businessScope.enforce(principal, bizId);
        if (bizId != null) query.eq(PackagePlan::getBizId, bizId);
        if (!principal.isSuperAdmin()) {
            query.and(q -> q.isNull(PackagePlan::getOwnerUserId).or().eq(PackagePlan::getOwnerUserId, principal.id()));
        }
        if (status != null && !status.isBlank()) query.eq(PackagePlan::getStatus, status);
        query.orderByDesc(PackagePlan::getCreatedAt);
        return CommonResult.success(packagePlanMapper.selectList(query));
    }

    @PostMapping
    @RequirePermission(RolePermissions.PACKAGE_CREATE)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<PackagePlan> create(@Valid @RequestBody CreatePackagePlanDTO dto, HttpServletRequest request) {
        BusinessContext business = businessService.requireAvailableByAppId(dto.getAppId());
        AdminPrincipal principal = principal(request);
        businessScope.enforce(principal, business.bizId());
        Long ownerId = principal.isSuperAdmin() ? null : principal.id();
        PackagePlan latest = packagePlanMapper.selectOne(new LambdaQueryWrapper<PackagePlan>()
                .eq(PackagePlan::getBizId, business.bizId())
                .eq(PackagePlan::getName, dto.getName())
                .eq(ownerId != null, PackagePlan::getOwnerUserId, ownerId)
                .isNull(ownerId == null, PackagePlan::getOwnerUserId)
                .orderByDesc(PackagePlan::getVersionNo).last("LIMIT 1"));
        PackagePlan plan = new PackagePlan();
        plan.setBizId(business.bizId());
        plan.setOwnerUserId(ownerId);
        plan.setName(dto.getName());
        plan.setVersionNo(latest == null ? 1 : latest.getVersionNo() + 1);
        plan.setListPrice(dto.getListPrice());
        plan.setDiscountRate(dto.getDiscountRate());
        plan.setSalePrice(dto.getListPrice().multiply(dto.getDiscountRate())
                .divide(new java.math.BigDecimal("100"), 2, RoundingMode.HALF_UP));
        plan.setDurationHours(dto.getDurationHours());
        plan.setAccountCount(dto.getAccountCount());
        plan.setCallsPerAccount(dto.getCallsPerAccount());
        plan.setStatus("ACTIVE");
        plan.setDescription(dto.getDescription());
        plan.setCreatedBy(principal.username());
        packagePlanMapper.insert(plan);
        auditService.record(principal, plan.getBizId(), "CREATE_PACKAGE", "PACKAGE", plan.getId().toString(), null,
                "{\"name\":\"" + plan.getName() + "\",\"version\":" + plan.getVersionNo() + "}",
                "创建不可变套餐版本", request);
        return CommonResult.success(plan, "套餐版本已创建；指标变化时请继续新建版本");
    }

    @PutMapping("/{id}/disable")
    @RequirePermission(RolePermissions.PACKAGE_DISABLE)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<String> disable(@PathVariable Integer id, HttpServletRequest request) {
        AdminPrincipal principal = principal(request);
        PackagePlan plan = packagePlanMapper.selectById(id);
        if (plan == null) throw new BusinessException(40420, "套餐不存在");
        businessScope.enforce(principal, plan.getBizId());
        if (!principal.isSuperAdmin() && !principal.id().equals(plan.getOwnerUserId())) {
            throw new BusinessException(40310, "不能停用其他代理或平台的套餐");
        }
        plan.setStatus("INACTIVE");
        plan.setDisabledAt(LocalDateTime.now());
        packagePlanMapper.updateById(plan);
        auditService.record(principal, plan.getBizId(), "DISABLE_PACKAGE", "PACKAGE", id.toString(),
                "{\"status\":\"ACTIVE\"}", "{\"status\":\"INACTIVE\"}", "停用套餐版本", request);
        return CommonResult.success("套餐已停用，历史销售数据不受影响");
    }

    private AdminPrincipal principal(HttpServletRequest request) {
        return (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
    }
}
