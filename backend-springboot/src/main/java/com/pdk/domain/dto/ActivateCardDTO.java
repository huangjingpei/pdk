package com.pdk.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class ActivateCardDTO {

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
    private BigDecimal actualAmount;

    /**
     * 销售类型: NORMAL_SALE, DISCOUNT_SALE, GIFT_FREE
     */
    private String orderType = "NORMAL_SALE";

    private String paymentChannel = "ALIPAY";

    private String paymentTxnNo;

    private String clientIp;
}
