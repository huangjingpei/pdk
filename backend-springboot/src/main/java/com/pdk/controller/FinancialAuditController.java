package com.pdk.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pdk.common.api.CommonResult;
import com.pdk.domain.entity.CompanyExpense;
import com.pdk.domain.entity.FinancialIncome;
import com.pdk.domain.vo.FinanceSummaryVO;
import com.pdk.service.IFinancialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/admin/finance")
@RequiredArgsConstructor
@Tag(name = "财务审计中心", description = "财务双向独立记账、毛利核算与采购成本穿透")
public class FinancialAuditController {

    private final IFinancialService financialService;

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
            @RequestParam(required = false) String searchKey) {
        return CommonResult.success(financialService.pageIncomes(page, size, orderType, searchKey));
    }

    @GetMapping("/expenses")
    @Operation(summary = "分页查询对公采购支出")
    public CommonResult<Page<CompanyExpense>> pageExpenses(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return CommonResult.success(financialService.pageExpenses(page, size));
    }

    @PostMapping("/expenses/purchase-token")
    @Operation(summary = "录入采购 Token 支出流水")
    public CommonResult<CompanyExpense> recordExpense(
            @RequestParam int tokenCount,
            @RequestParam BigDecimal unitCost,
            @RequestParam String supplier,
            @RequestParam String purchaser) {
        return CommonResult.success(financialService.recordTokenPurchaseExpense(tokenCount, unitCost, supplier, purchaser));
    }
}
