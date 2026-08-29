package com.pdk.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RenewDeviceLicenseDTO {
    @NotNull private Integer packageId;
    @Size(max = 64) private String renewalOrderNo;
    @Size(max = 128) private String paymentTxnNo;
    @Size(max = 255) private String remark;
}
