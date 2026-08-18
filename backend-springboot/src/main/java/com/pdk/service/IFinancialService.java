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

    /**
     * 分页查询独立收入流水
     */
    Page<FinancialIncome> pageIncomes(int page, int size, String orderType, String searchKey);

    /**
     * 分页查询对公采购支出
     */
    Page<CompanyExpense> pageExpenses(int page, int size);

    /**
     * 录入采购 Token 支出记账
     */
    CompanyExpense recordTokenPurchaseExpense(int tokenCount, BigDecimal unitCost, String supplier, String purchaser);
}
