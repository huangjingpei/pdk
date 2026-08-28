package com.pdk.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pdk.common.api.CommonResult;
import com.pdk.domain.entity.CompanyExpense;
import com.pdk.domain.entity.FinancialIncome;
import com.pdk.security.AdminPrincipal;
import com.pdk.domain.vo.FinanceSummaryVO;
import com.pdk.service.IFinancialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import com.pdk.security.RequirePermission;
import com.pdk.security.RolePermissions;
import jakarta.servlet.http.HttpServletRequest;
import com.pdk.service.AdminAuditService;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1/admin/finance")
@RequiredArgsConstructor
@Tag(name = "财务审计中心", description = "财务双向独立记账、毛利核算与采购成本穿透")
@RequirePermission(RolePermissions.FINANCE_VIEW)
public class FinancialAuditController {

    private final IFinancialService financialService;
    private final AdminAuditService adminAuditService;
    private final com.pdk.platform.business.BusinessService businessService;

    @GetMapping("/summary")
    @Operation(summary = "获取财务全盘收支与毛利汇总")
    public CommonResult<FinanceSummaryVO> getSummary() {
        return CommonResult.success(financialService.getFinanceSummary());
    }

    @GetMapping("/incomes")
    @Operation(summary = "分页查询独立收入流水")
    public CommonResult<Page<FinancialIncome>> pageIncomes(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String orderType,
            @RequestParam(required = false) String searchKey,
            @RequestParam(required = false) Long bizId,
            @RequestParam(required = false) Long appId) {
        if (appId != null) bizId = businessService.requireByAppId(appId).getId();
        return CommonResult.success(financialService.pageIncomes(page, size, orderType, searchKey, bizId));
    }

    @GetMapping("/expenses")
    @Operation(summary = "分页查询对公采购支出")
    public CommonResult<Page<CompanyExpense>> pageExpenses(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long bizId,
            @RequestParam(required = false) Long appId) {
        if (appId != null) bizId = businessService.requireByAppId(appId).getId();
        return CommonResult.success(financialService.pageExpenses(page, size, bizId));
    }

    @PostMapping("/expenses/purchase-token")
    @RequirePermission(RolePermissions.FINANCE_EDIT)
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "录入采购 Token 支出流水")
    public CommonResult<CompanyExpense> recordExpense(
            @RequestParam int tokenCount,
            @RequestParam BigDecimal unitCost,
            @RequestParam String supplier,
            HttpServletRequest request) {
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        CompanyExpense expense = financialService.recordTokenPurchaseExpense(tokenCount, unitCost, supplier, admin.username());
        adminAuditService.record(admin, "RECORD_TOKEN_EXPENSE", "ACCOUNT", expense.getExpenseOrderNo(), null,
                "{\"tokenCount\":" + tokenCount + ",\"unitCost\":" + unitCost + "}",
                "录入小号资源采购支出", request);
        return CommonResult.success(expense);
    }
}
