package com.pdk.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinanceSummaryVO {
    private BigDecimal totalIncome;
    private BigDecimal normalSaleIncome;
    private BigDecimal discountSaleIncome;
    private BigDecimal giftValue;
    private BigDecimal totalExpense;
    private BigDecimal netProfit;
    private BigDecimal profitMarginRate;
    private Integer totalCardsActivated;
    private Integer activeTokenCount;
}
