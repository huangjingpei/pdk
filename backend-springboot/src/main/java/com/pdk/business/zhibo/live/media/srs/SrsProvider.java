package com.pdk.business.zhibo.live.media.srs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdk.business.zhibo.live.entity.MediaServerNode;
import com.pdk.business.zhibo.live.media.MediaHttpClientFactory;
import com.pdk.business.zhibo.live.media.MediaNodeSnapshotData;
import com.pdk.business.zhibo.live.media.MediaServerProvider;
import com.pdk.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
@RequiredArgsConstructor
public class SrsProvider implements MediaServerProvider {
    private final ObjectMapper objectMapper;

    @Override
    public String providerType() {
        return "SRS";
    }

    @Override
    public void validate(MediaServerNode node) {
        try {
            String publishScheme = URI.create(node.getPublicPublishBaseUrl()).getScheme();
            String apiScheme = URI.create(node.getInternalApiBaseUrl()).getScheme();
            if (!("rtmp".equalsIgnoreCase(publishScheme) || "rtmps".equalsIgnoreCase(publishScheme))
                    || !("http".equalsIgnoreCase(apiScheme) || "https".equalsIgnoreCase(apiScheme))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            throw new BusinessException(40073, "SRS 推流地址必须为 RTMP(S)，HTTP API 必须为 HTTP(S)");
        }
    }

    @Override
    public MediaNodeSnapshotData snapshot(MediaServerNode node) {
        String body = MediaHttpClientFactory.client(node.getInternalApiBaseUrl()).get()
                .uri("/api/v1/streams?start=0&count=1000").retrieve().body(String.class);
        try {
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            JsonNode streams = root.path("streams");
            if (!streams.isArray()) streams = root.path("data").path("streams");
            int publishers = 0;
            int readers = 0;
            long inbound = 0;
            long outbound = 0;
            if (streams.isArray()) {
                for (JsonNode stream : streams) {
                    if (stream.path("publish").path("active").asBoolean(false)) publishers++;
                    readers += Math.max(0, stream.path("clients").asInt(0));
                    inbound += Math.max(0L, stream.path("recv_bytes").asLong(0));
                    outbound += Math.max(0L, stream.path("send_bytes").asLong(0));
                }
            }
            String version = root.path("server").asText("SRS");
            return new MediaNodeSnapshotData(publishers, readers, publishers, inbound, outbound, version);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析 SRS HTTP API 响应", e);
        }
    }

    @Override
    public String buildPublishUrl(MediaServerNode node, String path, String ticket) {
        return MediaHttpClientFactory.trim(node.getPublicPublishBaseUrl()) + "/" + path + "?token=" + ticket;
    }

    @Override
    public void kickPublisher(MediaServerNode node, String providerConnectionId, String protocol) {
        MediaHttpClientFactory.client(node.getInternalApiBaseUrl()).delete()
                .uri("/api/v1/clients/{id}", providerConnectionId).retrieve().toBodilessEntity();
    }
}
