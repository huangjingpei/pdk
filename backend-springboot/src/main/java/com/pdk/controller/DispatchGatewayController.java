package com.pdk.controller;

import com.pdk.common.api.CommonResult;
import com.pdk.domain.dto.AcquireTokenRequestDTO;
import com.pdk.domain.dto.ReportResultDTO;
import com.pdk.domain.vo.EncryptedTokenPayloadVO;
import com.pdk.service.IDispatchGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dispatch")
@RequiredArgsConstructor
@Tag(name = "网关调度模块", description = "短效 Token 加密下发与业务上报扣费")
public class DispatchGatewayController {

    private final IDispatchGatewayService gatewayService;

    @PostMapping("/acquire-token")
    @Operation(summary = "申请短效加密 Token", description = "分配健康底层拼多多槽位并使用 AES-GCM + 字节翻转加密下发")
    public CommonResult<EncryptedTokenPayloadVO> acquireToken(
            @Valid @RequestBody AcquireTokenRequestDTO dto,
            @RequestHeader("X-PDK-Phone") String userPhone,
            @RequestHeader("X-PDK-Device-ID") String deviceId) {

        EncryptedTokenPayloadVO vo = gatewayService.acquireEncryptedToken(dto, userPhone, deviceId);
        return CommonResult.success(vo);
    }

    @PostMapping("/report-result")
    @Operation(summary = "异步上报业务执行结果", description = "成功扣 1 次；若底层官方 Token 故障免责扣 0 次并触发自愈拉黑")
    public CommonResult<String> reportResult(
            @Valid @RequestBody ReportResultDTO dto,
            @RequestHeader("X-PDK-Phone") String userPhone) {

        gatewayService.reportAndDeductQuota(dto, userPhone);
        return CommonResult.success("上报处理成功");
    }
}
