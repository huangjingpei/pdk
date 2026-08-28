package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("pdk_company_expense")
public class CompanyExpense implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 为空表示公司全局费用，否则归属一个业务。 */
    private Long bizId;
    private String expenseOrderNo;
    private String category; // TOKEN_PURCHASE, SERVER_PROXY, SMS_GATEWAY
    private String tokenBatchId;
    private Integer tokenCount;
    private String supplierName;
    private BigDecimal unitCost;
    private BigDecimal totalCost;
    private String invoiceUrl;
    private String purchaser;
    private LocalDateTime purchasedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
