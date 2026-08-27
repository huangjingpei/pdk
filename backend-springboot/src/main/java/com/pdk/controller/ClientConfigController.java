package com.pdk.controller;

import com.pdk.common.api.CommonResult;
import com.pdk.config.ConfigKeys;
import com.pdk.security.SecurityKeyService;
import com.pdk.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端公共配置端点（无需登录即可访问，已在 WebMvcConfig 排除设备拦截器）。
 * 客户端首次启动 / 公钥失效时拉取：当前加密模式、服务端公钥(PEM)、密钥版本 kid。
 */
@RestController
@RequestMapping("/api/v1/client/config")
@RequiredArgsConstructor
public class ClientConfigController {

    private final SecurityKeyService keyService;
    private final SystemConfigService configService;

    @GetMapping("/public")
    public CommonResult<Map<String, Object>> publicConfig() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("encryptionMode",
                configService.getValue(ConfigKeys.SECURITY_ENCRYPTION_MODE, ConfigKeys.DEFAULT_SECURITY_ENCRYPTION_MODE));
        data.put("publicKey", keyService.getPublicKeyPem());
        data.put("kid", keyService.getActiveKid());
        data.put("supportedKids", keyService.getKids());
        data.put("publicKeyFingerprint", keyService.getPublicKeyFingerprint());
        return CommonResult.success(data);
    }
}
