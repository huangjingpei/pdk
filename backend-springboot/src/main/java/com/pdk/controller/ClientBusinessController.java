package com.pdk.controller;

import com.pdk.common.api.CommonResult;
import com.pdk.domain.vo.BusinessRuntimeVO;
import com.pdk.platform.business.BusinessService;
import com.pdk.business.zhibo.live.service.MediaServerNodeService;
import com.pdk.business.zhibo.ZhiboBusinessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 客户端登录前可读取的安全业务元数据，不返回部署细节和资源统计。 */
@RestController
@RequestMapping("/api/v1/client/business")
@RequiredArgsConstructor
public class ClientBusinessController {
    private final BusinessService businessService;
    private final MediaServerNodeService mediaServerNodeService;

    @GetMapping("/by-app/{appId}")
    public CommonResult<BusinessRuntimeVO> byAppId(@PathVariable long appId) {
        BusinessRuntimeVO value = businessService.publicRuntime(appId);
        value.setDeploymentEnabled(null);
        value.setHandlerRegistered(null);
        value.setHandlerHealth(null);
        value.setUserCount(null);
        value.setPackageCount(null);
        value.setResourceCount(null);
        value.setAvailableResourceCount(null);
        value.setMediaNodeCount(null);
        value.setAvailableMediaNodeCount(null);
        if (appId == 3 && ZhiboBusinessHandler.LIVE_CODE.equals(value.getBizCode())) {
            value.setLiveMedia(mediaServerNodeService.publicConfig(value.getBizId(),
                    "AVAILABLE".equals(value.getEffectiveStatus())));
        }
        return CommonResult.success(value);
    }
}
