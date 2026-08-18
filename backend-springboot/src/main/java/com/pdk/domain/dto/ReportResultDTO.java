package com.pdk.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReportResultDTO {
    @NotBlank(message = "租借追踪流水号不能为空")
    private String leaseTraceId;

    @NotBlank(message = "执行状态不能为空: SUCCESS / FAIL_ACCOUNT_BANNED / FAIL_NETWORK")
    private String status;

    private Long responseDurationMs;

    private String errorMessage;
}
