package com.pdk.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AcquireTokenRequestDTO {
    @NotBlank(message = "业务动作类型不能为空")
    private String actionType; // GOODS_COLLECT, ORDER_PULL, DETAIL_QUERY

    private String goodsId;

    private Long timestamp;
}
