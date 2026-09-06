package com.pdk.business.zhibo.live.vo;

import com.pdk.business.zhibo.live.entity.LivePlaySession;

import java.time.LocalDateTime;

public record LivePlaySessionVO(
        Long id,
        Long bizId,
        Long streamSessionId,
        String mediaNodeCode,
        String providerClientId,
        String protocol,
        String status,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        Long durationSeconds,
        Long outboundBytes,
        String endReason) {
    public static LivePlaySessionVO from(LivePlaySession value) {
        return new LivePlaySessionVO(value.getId(), value.getBizId(), value.getStreamSessionId(),
                value.getMediaNodeCode(), value.getProviderClientId(), value.getProtocol(), value.getStatus(),
                value.getStartedAt(), value.getEndedAt(), value.getDurationSeconds(), value.getOutboundBytes(),
                value.getEndReason());
    }
}
