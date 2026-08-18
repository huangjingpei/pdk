package com.pdk.service;

import com.pdk.common.exception.BusinessException;
import com.pdk.domain.entity.CompanyExpense;
import com.pdk.domain.entity.FinancialIncome;
import com.pdk.domain.entity.TokenPool;
import com.pdk.domain.vo.FinanceSummaryVO;
import com.pdk.mapper.CompanyExpenseMapper;
import com.pdk.mapper.FinancialIncomeMapper;
import com.pdk.mapper.TokenPoolMapper;
import com.pdk.service.impl.FinancialServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinancialServiceImplTest {
    @Mock private FinancialIncomeMapper incomeMapper;
    @Mock private CompanyExpenseMapper expenseMapper;
    @Mock private TokenPoolMapper tokenPoolMapper;
    @InjectMocks private FinancialServiceImpl service;

    @Test
    void summarySeparatesIncomeGiftAndExpense() {
        FinancialIncome normal = income("NORMAL_SALE", "200", "200");
        FinancialIncome discount = income("DISCOUNT_SALE", "500", "450");
        FinancialIncome gift = income("GIFT_FREE", "20", "0");
        CompanyExpense expense = new CompanyExpense();
        expense.setTotalCost(new BigDecimal("100"));
        when(incomeMapper.selectList(null)).thenReturn(List.of(normal, discount, gift));
        when(expenseMapper.selectList(null)).thenReturn(List.of(expense));
        when(tokenPoolMapper.selectCount(any())).thenReturn(3L);

        FinanceSummaryVO result = service.getFinanceSummary();

        assertEquals(new BigDecimal("650"), result.getTotalIncome());
        assertEquals(new BigDecimal("20"), result.getGiftValue());
        assertEquals(new BigDecimal("550"), result.getNetProfit());
        assertEquals(0, new BigDecimal("84.6200").compareTo(result.getProfitMarginRate()));
        assertEquals(3, result.getTotalCardsActivated());
    }

    @Test
    void purchaseExpenseCalculatesTotalAndRejectsInvalidInput() {
        CompanyExpense created = service.recordTokenPurchaseExpense(100, new BigDecimal("0.18"), "supplier", "finance");
        assertEquals(new BigDecimal("18.00"), created.getTotalCost());
        ArgumentCaptor<CompanyExpense> captor = ArgumentCaptor.forClass(CompanyExpense.class);
        verify(expenseMapper).insert(captor.capture());
        assertEquals("finance", captor.getValue().getPurchaser());

        assertEquals(40040, assertThrows(BusinessException.class,
                () -> service.recordTokenPurchaseExpense(0, BigDecimal.ONE, "supplier", "finance")).getCode());
        assertEquals(40041, assertThrows(BusinessException.class,
                () -> service.recordTokenPurchaseExpense(1, BigDecimal.ZERO, "supplier", "finance")).getCode());
        assertEquals(40042, assertThrows(BusinessException.class,
                () -> service.recordTokenPurchaseExpense(1, BigDecimal.ONE, " ", "finance")).getCode());
    }

    private FinancialIncome income(String type, String faceValue, String amount) {
        FinancialIncome income = new FinancialIncome();
        income.setOrderType(type);
        income.setFaceValue(new BigDecimal(faceValue));
        income.setAmount(new BigDecimal(amount));
        return income;
    }
}
