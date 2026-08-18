package com.pdk.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pdk.common.api.CommonResult;
import com.pdk.domain.entity.FinancialIncome;
import com.pdk.mapper.FinancialIncomeMapper;
import com.pdk.security.AdminPrincipal;
import com.pdk.security.RequirePermission;
import com.pdk.security.RolePermissions;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/sales")
@RequiredArgsConstructor
public class AdminSalesController {
    private final FinancialIncomeMapper incomeMapper;

    @GetMapping("/list")
    @RequirePermission(RolePermissions.SALES_VIEW)
    public CommonResult<Page<FinancialIncome>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String orderType,
            @RequestParam(required = false) String searchKey,
            HttpServletRequest request) {
        AdminPrincipal principal = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        LambdaQueryWrapper<FinancialIncome> query = new LambdaQueryWrapper<>();
        if (!principal.isSuperAdmin()) query.eq(FinancialIncome::getAuditAdmin, principal.username());
        if (orderType != null && !orderType.isBlank()) query.eq(FinancialIncome::getOrderType, orderType);
        if (searchKey != null && !searchKey.isBlank()) {
            query.and(w -> w.like(FinancialIncome::getIncomeOrderNo, searchKey)
                    .or().like(FinancialIncome::getCardKey, searchKey)
                    .or().like(FinancialIncome::getUserPhone, searchKey));
        }
        query.orderByDesc(FinancialIncome::getCreatedAt);
        return CommonResult.success(incomeMapper.selectPage(new Page<>(page, size), query));
    }
}
