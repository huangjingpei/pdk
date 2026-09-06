package com.pdk.business.zhibo.live.vo;

import com.pdk.business.zhibo.live.entity.MediaServerNode;
import com.pdk.business.zhibo.live.entity.MediaServerNodeSnapshot;

import java.time.LocalDateTime;

public record MediaServerNodeVO(
        Long id,
        Long bizId,
        String nodeCode,
        String nodeName,
        String providerType,
        String regionCode,
        String publicPublishBaseUrl,
        String publicHlsBaseUrl,
        String publicWebrtcBaseUrl,
        String internalApiBaseUrl,
        String internalMetricsUrl,
        String secretRef,
        String supportedPublishProtocols,
        String supportedPlayProtocols,
        Integer weight,
        Integer maxPublishers,
        Integer maxReaders,
        String status,
        String healthStatus,
        LocalDateTime lastHealthAt,
        String lastHealthError,
        Long configRevision,
        Integer activePublishers,
        Integer activeReaders,
        Integer activePaths,
        Long inboundBytesTotal,
        Long outboundBytesTotal,
        Long inboundBps,
        Long outboundBps,
        LocalDateTime metricsCollectedAt,
        String collectStatus,
        String providerVersion) {

    public static MediaServerNodeVO of(MediaServerNode node, MediaServerNodeSnapshot snapshot) {
        return new MediaServerNodeVO(node.getId(), node.getBizId(), node.getNodeCode(), node.getNodeName(),
                node.getProviderType(), node.getRegionCode(), node.getPublicPublishBaseUrl(),
                node.getPublicHlsBaseUrl(), node.getPublicWebrtcBaseUrl(), node.getInternalApiBaseUrl(),
                node.getInternalMetricsUrl(), node.getSecretRef(), node.getSupportedPublishProtocols(),
                node.getSupportedPlayProtocols(), node.getWeight(), node.getMaxPublishers(), node.getMaxReaders(),
                node.getStatus(), node.getHealthStatus(), node.getLastHealthAt(), node.getLastHealthError(),
                node.getConfigRevision(), snapshot == null ? null : snapshot.getActivePublishers(),
                snapshot == null ? null : snapshot.getActiveReaders(), snapshot == null ? null : snapshot.getActivePaths(),
                snapshot == null ? null : snapshot.getInboundBytesTotal(),
                snapshot == null ? null : snapshot.getOutboundBytesTotal(),
                snapshot == null ? null : snapshot.getInboundBps(), snapshot == null ? null : snapshot.getOutboundBps(),
                snapshot == null ? null : snapshot.getCollectedAt(), snapshot == null ? null : snapshot.getCollectStatus(),
                snapshot == null ? null : snapshot.getProviderVersion());
    }
}
