package com.pdk.business.zhibo.live.vo;

import com.pdk.business.zhibo.live.entity.LiveStreamSession;

import java.time.LocalDateTime;

public record LiveStreamSessionVO(
        String streamSessionNo,
        Long bizId,
        Long userId,
        String path,
        String protocol,
        String status,
        String mediaNodeCode,
        LocalDateTime ticketExpiresAt,
        LocalDateTime authorizedAt,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        Long durationSeconds,
        Integer billedUnits,
        String endReason,
        LocalDateTime createdAt) {

    public static LiveStreamSessionVO from(LiveStreamSession session) {
        return new LiveStreamSessionVO(session.getStreamSessionNo(), session.getBizId(), session.getUserId(),
                session.getPath(), session.getProtocol(), session.getStatus(), session.getMediaNodeCode(),
                session.getTicketExpiresAt(), session.getAuthorizedAt(), session.getStartedAt(),
                session.getEndedAt(), session.getDurationSeconds(), session.getBilledUnits(),
                session.getEndReason(), session.getCreatedAt());
    }
}
