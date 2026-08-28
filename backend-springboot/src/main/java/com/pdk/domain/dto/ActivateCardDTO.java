package com.pdk.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import java.math.BigDecimal;
import jakarta.validation.constraints.DecimalMin;

@Data
public class ActivateCardDTO {

    @Positive(message = "appId 必须为正整数")
    private Long appId;

    @NotBlank(message = "卡密序列号不能为空")
    @Pattern(regexp = "^PDK-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}$", message = "卡密格式不合规 (正确示例: PDK-8891-2041-9982)")
    private String cardKey;

    @NotBlank(message = "充值用户手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号码格式错误")
    private String userPhone;

    @NotBlank(message = "客户端设备ID不能为空")
    private String deviceId;

    /**
     * 财务实收金额 (代理商或前台结算金额)
     */
    @DecimalMin(value = "0.00", message = "实收金额不能为负数")
    private BigDecimal actualAmount;

    /**
     * 销售类型: NORMAL_SALE, DISCOUNT_SALE, GIFT_FREE
     */
    @Pattern(regexp = "NORMAL_SALE", message = "客户端激活仅允许 NORMAL_SALE")
    private String orderType = "NORMAL_SALE";

    @Pattern(regexp = "ALIPAY|WECHAT_PAY|BANK_TRANSFER|OFFLINE", message = "支付通道不合法")
    private String paymentChannel = "ALIPAY";

    private String paymentTxnNo;

    private String clientIp;
}
