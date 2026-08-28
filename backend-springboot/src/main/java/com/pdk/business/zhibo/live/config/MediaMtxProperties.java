package com.pdk.business.zhibo.live.config;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "pdk.zhibo-live.mediamtx")
public class MediaMtxProperties {
    private boolean enabled;
    private String publicRtmpBaseUrl = "rtmp://localhost:1935";
    private String controlBaseUrl = "http://localhost:9997";
    private String nodeCode = "mediamtx-local";
    private String internalServiceToken = "";
    private long ticketTtlSeconds = 90;

    @AssertTrue(message = "启用 MediaMTX 时必须配置 RTMP/Control 地址、节点编号和至少 32 字节内部服务令牌")
    public boolean isSecureEnabledConfiguration() {
        if (!enabled) return true;
        return publicRtmpBaseUrl != null && publicRtmpBaseUrl.startsWith("rtmp://")
                && controlBaseUrl != null
                && (controlBaseUrl.startsWith("http://") || controlBaseUrl.startsWith("https://"))
                && nodeCode != null && !nodeCode.isBlank()
                && internalServiceToken != null
                && internalServiceToken.getBytes(StandardCharsets.UTF_8).length >= 32
                && ticketTtlSeconds >= 30 && ticketTtlSeconds <= 300;
    }
}
