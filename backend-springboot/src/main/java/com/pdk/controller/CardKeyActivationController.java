package com.pdk.controller;

import com.pdk.common.api.CommonResult;
import com.pdk.domain.dto.ActivateCardDTO;
import com.pdk.domain.vo.ActivationResultVO;
import com.pdk.platform.business.BusinessRequestResolver;
import com.pdk.service.ICardKeyActivationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/card")
@RequiredArgsConstructor
@Tag(name = "卡密核销与注册模块", description = "用户端卡密核销、延期与新人试用注册")
public class CardKeyActivationController {

    private final ICardKeyActivationService activationService;
    private final BusinessRequestResolver businessRequestResolver;

    @PostMapping("/activate")
    @Operation(summary = "客户端原子核销卡密", description = "同套餐顺延有效期，不同套餐进入排队，并向独立财务表入账")
    public CommonResult<ActivationResultVO> activateCard(
            @Valid @RequestBody ActivateCardDTO dto,
            HttpServletRequest request) {
        var business = businessRequestResolver.resolveContextAndBind(request, dto.getAppId());
        String clientIp = request.getHeader("X-Forwarded-For");
        if (clientIp == null || clientIp.isEmpty()) {
            clientIp = request.getRemoteAddr();
        }
        dto.setClientIp(clientIp);

        ActivationResultVO vo = activationService.activateCardKeyAtomic(dto, business);
        return CommonResult.success(vo, "卡密核销成功，权益已实时到账");
    }

}
