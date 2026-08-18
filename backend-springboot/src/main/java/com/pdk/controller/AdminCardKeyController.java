package com.pdk.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pdk.common.api.CommonResult;
import com.pdk.domain.dto.CreateCardBatchDTO;
import com.pdk.domain.entity.CardKey;
import com.pdk.security.AdminPrincipal;
import com.pdk.mapper.CardKeyMapper;
import com.pdk.service.ICardKeyActivationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.pdk.security.RequirePermission;
import com.pdk.security.RolePermissions;
import jakarta.servlet.http.HttpServletRequest;
import com.pdk.service.AdminAuditService;
import com.pdk.service.CardRenewalService;
import com.pdk.domain.dto.RenewCardDTO;
import com.pdk.domain.entity.FinancialIncome;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/card")
@RequiredArgsConstructor
@Tag(name = "卡密管理模块", description = "制卡凭证池、批量生成与批次导出")
public class AdminCardKeyController {

    private final ICardKeyActivationService activationService;
    private final CardKeyMapper cardKeyMapper;
    private final AdminAuditService adminAuditService;
    private final CardRenewalService renewalService;

    @PostMapping("/batch-generate")
    @RequirePermission(RolePermissions.CARD_CREATE)
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "管理员/代理商批量生成卡密")
    public CommonResult<List<String>> batchGenerate(
            @Valid @RequestBody CreateCardBatchDTO dto,
            HttpServletRequest request) {
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        List<String> keys = activationService.createCardKeyBatch(dto, admin.username());
        adminAuditService.record(admin, "GENERATE_CARD", "CARD", "BATCH-" + keys.get(0),
                null, "{\"packageId\":" + dto.getPackageId() + ",\"count\":" + keys.size() + "}",
                dto.getBatchRemark(), request);
        return CommonResult.success(keys, "批量制卡成功");
    }

    @GetMapping("/list")
    @RequirePermission(RolePermissions.CARD_VIEW)
    @Operation(summary = "分页查询卡密列表")
    public CommonResult<Page<CardKey>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {
        Page<CardKey> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<CardKey> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq(CardKey::getStatus, status);
        }
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        if ("PARTNER".equals(admin.roleCode())) {
            wrapper.eq(CardKey::getGeneratedByAdmin, admin.username());
        }
        wrapper.orderByDesc(CardKey::getCreatedAt);
        return CommonResult.success(cardKeyMapper.selectPage(pageParam, wrapper));
    }

    @PostMapping("/{cardKey}/renew")
    @RequirePermission(RolePermissions.CARD_RENEW)
    public CommonResult<FinancialIncome> renew(@PathVariable String cardKey, @Valid @RequestBody RenewCardDTO dto,
                                                HttpServletRequest request) {
        AdminPrincipal principal = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        FinancialIncome income = renewalService.renew(cardKey, dto, principal);
        adminAuditService.record(principal, "RENEW_CARD", "CARD", cardKey, null,
                "{\"orderNo\":\"" + income.getIncomeOrderNo() + "\"}", "原卡密续费", request);
        return CommonResult.success(income, "续费成功，原卡密保持不变");
    }

    @PutMapping("/void-all")
    @RequirePermission(RolePermissions.CARD_VOID)
    public CommonResult<Integer> voidAllOwned(HttpServletRequest request) {
        AdminPrincipal principal = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        int count = renewalService.voidAllOwnedCards(principal);
        adminAuditService.record(principal, "VOID_ALL_OWNED_CARDS", "CARD", principal.username(), null,
                "{\"count\":" + count + "}", "批量作废名下卡密", request);
        return CommonResult.success(count, "已作废名下 " + count + " 张卡密");
    }

    @PutMapping("/{cardKey}/void")
    @RequirePermission(RolePermissions.CARD_VOID)
    public CommonResult<String> voidCard(@PathVariable String cardKey, HttpServletRequest request) {
        AdminPrincipal principal = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        renewalService.voidCard(cardKey, principal);
        adminAuditService.record(principal, "VOID_CARD", "CARD", cardKey, null,
                "{\"status\":\"VOID\"}", "管理员作废卡密", request);
        return CommonResult.success("卡密已作废，相关授权与小号资源已释放");
    }
}
