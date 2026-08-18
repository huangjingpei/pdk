package com.pdk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pdk.domain.entity.CompanyExpense;
import com.pdk.domain.entity.FinancialIncome;
import com.pdk.domain.entity.TokenPool;
import com.pdk.domain.vo.FinanceSummaryVO;
import com.pdk.common.exception.BusinessException;
import com.pdk.mapper.CompanyExpenseMapper;
import com.pdk.mapper.FinancialIncomeMapper;
import com.pdk.mapper.TokenPoolMapper;
import com.pdk.service.IFinancialService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FinancialServiceImpl implements IFinancialService {

    private final FinancialIncomeMapper incomeMapper;
    private final CompanyExpenseMapper expenseMapper;
    private final TokenPoolMapper tokenPoolMapper;

    @Override
    public FinanceSummaryVO getFinanceSummary() {
        List<FinancialIncome> allIncomes = incomeMapper.selectList(null);
        List<CompanyExpense> allExpenses = expenseMapper.selectList(null);

        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal normalSaleIncome = BigDecimal.ZERO;
        BigDecimal discountSaleIncome = BigDecimal.ZERO;
        BigDecimal giftValue = BigDecimal.ZERO;

        for (FinancialIncome inc : allIncomes) {
            if ("NORMAL_SALE".equals(inc.getOrderType())) {
                normalSaleIncome = normalSaleIncome.add(inc.getAmount());
                totalIncome = totalIncome.add(inc.getAmount());
            } else if ("DISCOUNT_SALE".equals(inc.getOrderType())) {
                discountSaleIncome = discountSaleIncome.add(inc.getAmount());
                totalIncome = totalIncome.add(inc.getAmount());
            } else if ("GIFT_FREE".equals(inc.getOrderType())) {
                giftValue = giftValue.add(inc.getFaceValue());
            }
        }

        BigDecimal totalExpense = BigDecimal.ZERO;
        for (CompanyExpense exp : allExpenses) {
            totalExpense = totalExpense.add(exp.getTotalCost());
        }

        BigDecimal netProfit = totalIncome.subtract(totalExpense);
        BigDecimal marginRate = BigDecimal.ZERO;
        if (totalIncome.compareTo(BigDecimal.ZERO) > 0) {
            marginRate = netProfit.divide(totalIncome, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        }

        Long healthyTokenCount = tokenPoolMapper.selectCount(new LambdaQueryWrapper<TokenPool>().eq(TokenPool::getHealthStatus, "HEALTHY"));

        return FinanceSummaryVO.builder()
                .totalIncome(totalIncome)
                .normalSaleIncome(normalSaleIncome)
                .discountSaleIncome(discountSaleIncome)
                .giftValue(giftValue)
                .totalExpense(totalExpense)
                .netProfit(netProfit)
                .profitMarginRate(marginRate)
                .totalCardsActivated(allIncomes.size())
                .activeTokenCount(healthyTokenCount.intValue())
                .build();
    }

    @Override
    public Page<FinancialIncome> pageIncomes(int page, int size, String orderType, String searchKey) {
        Page<FinancialIncome> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<FinancialIncome> wrapper = new LambdaQueryWrapper<>();
        if (orderType != null && !orderType.isEmpty()) {
            wrapper.eq(FinancialIncome::getOrderType, orderType);
        }
        if (searchKey != null && !searchKey.isEmpty()) {
            wrapper.and(w -> w.like(FinancialIncome::getCardKey, searchKey)
                    .or().like(FinancialIncome::getUserPhone, searchKey)
                    .or().like(FinancialIncome::getIncomeOrderNo, searchKey));
        }
        wrapper.orderByDesc(FinancialIncome::getActivatedAt);
        return incomeMapper.selectPage(pageParam, wrapper);
    }

    @Override
    public Page<CompanyExpense> pageExpenses(int page, int size) {
        Page<CompanyExpense> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<CompanyExpense> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(CompanyExpense::getPurchasedAt);
        return expenseMapper.selectPage(pageParam, wrapper);
    }

    @Override
    public CompanyExpense recordTokenPurchaseExpense(int tokenCount, BigDecimal unitCost, String supplier, String purchaser) {
        if (tokenCount <= 0) {
            throw new BusinessException(40040, "采购数量必须大于0");
        }
        if (unitCost == null || unitCost.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(40041, "采购单价必须大于0");
        }
        if (supplier == null || supplier.isBlank()) {
            throw new BusinessException(40042, "供应商名称不能为空");
        }
        CompanyExpense exp = new CompanyExpense();
        exp.setExpenseOrderNo("EXP-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 9000 + 1000));
        exp.setCategory("TOKEN_PURCHASE");
        exp.setTokenBatchId("BATCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        exp.setTokenCount(tokenCount);
        exp.setSupplierName(supplier);
        exp.setUnitCost(unitCost);
        exp.setTotalCost(unitCost.multiply(BigDecimal.valueOf(tokenCount)));
        exp.setPurchaser(purchaser);
        exp.setPurchasedAt(LocalDateTime.now());
        expenseMapper.insert(exp);
        return exp;
    }
}
