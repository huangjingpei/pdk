package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("pdk_financial_income")
public class FinancialIncome implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String incomeOrderNo;
    private Long cardKeyId;
    private String cardKey;
    private String userPhone;
    private Integer packageId;
    private String packageName;
    private BigDecimal faceValue;
    private BigDecimal amount;
    private BigDecimal discountAmount;
    private String orderType; // NORMAL_SALE / DISCOUNT_SALE / GIFT_FREE
    private String paymentChannel; // ALIPAY / WECHAT_PAY / BANK_TRANSFER / OFFLINE
    private String paymentTxnNo;
    private String auditAdmin;
    private LocalDateTime activatedAt;
    private String auditRemark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
