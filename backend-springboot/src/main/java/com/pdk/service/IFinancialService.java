package com.pdk.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pdk.domain.entity.CompanyExpense;
import com.pdk.domain.entity.FinancialIncome;
import com.pdk.domain.vo.FinanceSummaryVO;
import java.math.BigDecimal;

public interface IFinancialService {

    /**
     * 财务全盘总览核算
     */
    FinanceSummaryVO getFinanceSummary();

    FinanceSummaryVO getFinanceSummary(Long bizId);

    /**
     * 分页查询独立收入流水
     */
    default Page<FinancialIncome> pageIncomes(int page, int size, String orderType, String searchKey) {
        return pageIncomes(page, size, orderType, searchKey, null);
    }
    Page<FinancialIncome> pageIncomes(int page, int size, String orderType, String searchKey, Long bizId);

    /**
     * 分页查询对公采购支出
     */
    default Page<CompanyExpense> pageExpenses(int page, int size) { return pageExpenses(page, size, null); }
    Page<CompanyExpense> pageExpenses(int page, int size, Long bizId);

    /**
     * 录入采购 Token 支出记账
     */
    CompanyExpense recordTokenPurchaseExpense(int tokenCount, BigDecimal unitCost, String supplier, String purchaser);
}
