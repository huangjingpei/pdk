package com.pdk.business.zhibo.live.media.mediamtx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdk.business.zhibo.live.entity.MediaServerNode;
import com.pdk.business.zhibo.live.media.MediaHttpClientFactory;
import com.pdk.business.zhibo.live.media.MediaNodeSnapshotData;
import com.pdk.business.zhibo.live.media.MediaServerProvider;
import com.pdk.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MediaMtxProvider implements MediaServerProvider {
    private static final Pattern VALUE = Pattern.compile("^([a-zA-Z0-9_]+)(?:\\{[^}]*})?\\s+([0-9.eE+-]+)$");
    private final ObjectMapper objectMapper;

    @Override
    public String providerType() {
        return "MEDIAMTX";
    }

    @Override
    public void validate(MediaServerNode node) {
        requirePublishUrl(node.getPublicPublishBaseUrl());
        requireHttpUrl(node.getInternalApiBaseUrl(), "Control API");
        if (node.getInternalMetricsUrl() != null && !node.getInternalMetricsUrl().isBlank()) {
            requireHttpUrl(node.getInternalMetricsUrl(), "Metrics");
        }
    }

    @Override
    public MediaNodeSnapshotData snapshot(MediaServerNode node) {
        RestClient api = MediaHttpClientFactory.client(node.getInternalApiBaseUrl());
        String pathsBody = api.get().uri("/v3/paths/list").retrieve().body(String.class);
        int apiPublishers = 0;
        int apiReaders = 0;
        int apiPaths = 0;
        Long apiInbound = null;
        Long apiOutbound = null;
        try {
            JsonNode items = objectMapper.readTree(pathsBody == null ? "{}" : pathsBody).path("items");
            if (items.isArray()) {
                long in = 0;
                long out = 0;
                boolean hasBytes = false;
                for (JsonNode item : items) {
                    apiPaths++;
                    if (item.path("ready").asBoolean(false)) apiPublishers++;
                    JsonNode readersNode = item.path("readers");
                    if (readersNode.isArray()) apiReaders += readersNode.size();
                    else apiReaders += Math.max(0, readersNode.asInt(0));
                    if (item.has("bytesReceived")) { in += item.path("bytesReceived").asLong(0); hasBytes = true; }
                    if (item.has("bytesSent")) { out += item.path("bytesSent").asLong(0); hasBytes = true; }
                }
                if (hasBytes) { apiInbound = in; apiOutbound = out; }
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法解析 MediaMTX Control API 响应", e);
        }
        if (node.getInternalMetricsUrl() == null || node.getInternalMetricsUrl().isBlank()) {
            return new MediaNodeSnapshotData(null, null, null, null, null, "MediaMTX");
        }
        String metricsUrl = node.getInternalMetricsUrl().trim();
        String metrics = RestClient.create().get().uri(metricsUrl).retrieve().body(String.class);
        if (metrics == null) return new MediaNodeSnapshotData(null, null, null, null, null, "MediaMTX");

        int publishers = apiPublishers;
        int readers = apiReaders;
        int paths = apiPaths;
        long inbound = 0;
        long outbound = 0;
        boolean sawInbound = false;
        boolean sawOutbound = false;
        for (String line : metrics.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            Matcher matcher = VALUE.matcher(line.trim());
            if (!matcher.matches()) continue;
            String name = matcher.group(1);
            double raw;
            try {
                raw = Double.parseDouble(matcher.group(2));
            } catch (NumberFormatException ignored) {
                continue;
            }
            long value = Math.max(0L, Math.round(raw));
            if (apiPaths == 0 && "paths".equals(name) && line.contains("state=\"ready\"")) {
                paths += (int) value;
                publishers += (int) value;
            } else if (apiPaths == 0 && "paths_readers".equals(name)) {
                readers += (int) value;
            } else if ("paths_inbound_bytes".equals(name)) {
                inbound += value;
                sawInbound = true;
            } else if ("paths_outbound_bytes".equals(name)) {
                outbound += value;
                sawOutbound = true;
            } else if ("rtmp_conns_bytes_received".equals(name)) {
                inbound += value;
                sawInbound = true;
            } else if ("rtmp_conns_bytes_sent".equals(name) || "hls_muxers_bytes_sent".equals(name)) {
                outbound += value;
                sawOutbound = true;
            }
        }
        return new MediaNodeSnapshotData(publishers, readers, paths,
                sawInbound ? inbound : apiInbound, sawOutbound ? outbound : apiOutbound, "MediaMTX");
    }

    @Override
    public String buildPublishUrl(MediaServerNode node, String path, String ticket) {
        return MediaHttpClientFactory.trim(node.getPublicPublishBaseUrl()) + "/" + path + "?token=" + ticket;
    }

    @Override
    public void kickPublisher(MediaServerNode node, String providerConnectionId, String protocol) {
        String group = "RTMPS".equalsIgnoreCase(protocol) ? "rtmpsconns" : "rtmpconns";
        try {
            MediaHttpClientFactory.client(node.getInternalApiBaseUrl()).post()
                    .uri("/v3/" + group + "/kick/{id}", providerConnectionId)
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound ignored) {
            // MediaMTX connection IDs are ephemeral.  A 404 means the publisher has
            // already disconnected, so stopping the persisted session is still safe.
        }
    }

    private static void requirePublishUrl(String value) {
        try {
            String scheme = URI.create(value).getScheme();
            if (!"rtmp".equalsIgnoreCase(scheme) && !"rtmps".equalsIgnoreCase(scheme)) throw new Exception();
        } catch (Exception e) {
            throw new BusinessException(40073, "MediaMTX 公网推流地址必须使用 rtmp:// 或 rtmps://");
        }
    }

    private static void requireHttpUrl(String value, String label) {
        try {
            String scheme = URI.create(value).getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) throw new Exception();
        } catch (Exception e) {
            throw new BusinessException(40073, label + " 地址必须使用 http:// 或 https://");
        }
    }
}
