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
