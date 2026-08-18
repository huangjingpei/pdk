package com.pdk.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RenewCardDTO {
    @NotNull(message = "续费套餐不能为空")
    private Integer packageId;
    private String paymentTxnNo;
    private String remark;
}
