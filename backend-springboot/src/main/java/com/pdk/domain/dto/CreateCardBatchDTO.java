package com.pdk.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class CreateCardBatchDTO {
    @NotNull(message = "套餐模版ID不能为空")
    private Integer packageId;

    @NotNull(message = "生成卡密数量不能为空")
    @Min(value = 1, message = "单次最少生成 1 张卡密")
    @Max(value = 500, message = "单次最多批量生成 500 张卡密")
    private Integer count;

    private String batchRemark;

    private BigDecimal customDiscountPrice;
}
