package com.pdk.business.zhibo.live.service;

import com.pdk.business.zhibo.live.config.MediaMtxProperties;
import com.pdk.business.zhibo.live.entity.LiveStreamSession;
import com.pdk.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
public class MediaMtxControlClient {
    private final MediaMtxProperties properties;

    public void kick(LiveStreamSession session) {
        String group = "RTMPS".equalsIgnoreCase(session.getProtocol()) ? "rtmpsconns" : "rtmpconns";
        try {
            RestClient.create(properties.getControlBaseUrl().replaceAll("/+$", ""))
                    .post()
                    .uri("/v3/" + group + "/kick/{id}", session.getMediamtxConnectionId())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new BusinessException(50371, "MediaMTX 踢流失败，请稍后重试");
        }
    }
}
