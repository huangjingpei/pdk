package com.pdk.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class AcquireTokenRequestDTO {
    @NotBlank(message = "业务动作类型不能为空")
    @Pattern(regexp = "GOODS_COLLECT|ORDER_PULL|DETAIL_QUERY", message = "业务动作类型不合法")
    private String actionType; // GOODS_COLLECT, ORDER_PULL, DETAIL_QUERY

    private String goodsId;

    @NotNull(message = "客户端时间戳不能为空")
    @Positive(message = "客户端时间戳必须为正数")
    private Long timestamp;
}
