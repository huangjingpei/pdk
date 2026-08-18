package com.pdk.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

@Data
public class ReportResultDTO {
    @NotBlank(message = "租借追踪流水号不能为空")
    private String leaseTraceId;

    @NotBlank(message = "执行状态不能为空: SUCCESS / FAIL_ACCOUNT_BANNED / FAIL_NETWORK")
    @Pattern(regexp = "SUCCESS|FAIL_ACCOUNT_BANNED|FAIL_NETWORK|FAIL_BUSINESS", message = "执行状态不合法")
    private String status;

    @PositiveOrZero(message = "响应耗时不能为负数")
    private Long responseDurationMs;

    private String errorMessage;
}
