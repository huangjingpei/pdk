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
import jakarta.servlet.http.HttpServletRequest;
import com.pdk.domain.entity.User;
import com.pdk.platform.business.BusinessContext;
import com.pdk.platform.business.BusinessRequestResolver;

@RestController
@RequestMapping("/api/v1/dispatch")
@RequiredArgsConstructor
@Tag(name = "网关调度模块", description = "业务资源短效加密下发与结果上报扣费")
public class DispatchGatewayController {

    private final IDispatchGatewayService gatewayService;

    @PostMapping("/acquire-token")
    @Operation(summary = "申请短效加密资源", description = "由当前业务 Handler 校验并构造凭证，平台统一加密下发")
    public CommonResult<EncryptedTokenPayloadVO> acquireToken(
            @Valid @RequestBody AcquireTokenRequestDTO dto,
            @RequestHeader("X-PDK-Device-ID") String deviceId,
            HttpServletRequest request) {

        EncryptedTokenPayloadVO vo = gatewayService.acquireEncryptedToken(dto,
                BusinessRequestResolver.context(request), (User) request.getAttribute("pdkClientUser"), deviceId);
        return CommonResult.success(vo);
    }

    @PostMapping("/report-result")
    @Operation(summary = "异步上报业务执行结果", description = "业务 Handler 分类结果，平台统一执行扣次、免责与资源自愈")
    public CommonResult<String> reportResult(
            @Valid @RequestBody ReportResultDTO dto,
            HttpServletRequest request) {

        gatewayService.reportAndDeductQuota(dto, BusinessRequestResolver.context(request),
                (User) request.getAttribute("pdkClientUser"));
        return CommonResult.success("上报处理成功");
    }
}
