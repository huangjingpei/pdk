package com.pdk.business.zhibo.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pdk.business.zhibo.live.config.MediaMtxProperties;
import com.pdk.business.zhibo.live.dto.MediaMtxAuthRequest;
import com.pdk.business.zhibo.live.entity.LiveStreamSession;
import com.pdk.business.zhibo.live.mapper.LiveStreamSessionMapper;
import com.pdk.domain.entity.User;
import com.pdk.mapper.UserMapper;
import com.pdk.platform.business.BusinessContext;
import com.pdk.platform.business.BusinessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaMtxAuthService {
    private static final Set<String> ALLOWED_STATES = Set.of("AUTHORIZED", "LIVE");

    private final LiveStreamSessionMapper sessionMapper;
    private final UserMapper userMapper;
    private final BusinessService businessService;
    private final MediaMtxProperties properties;

    @Transactional(rollbackFor = Exception.class)
    public MediaMtxAuthResult authorize(String serviceToken, MediaMtxAuthRequest request) {
        if (!properties.isEnabled()) return denied(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_SERVICE_DISABLED", request);
        if (!LiveStreamSecurity.constantTimeEquals(properties.getInternalServiceToken(), serviceToken)) {
            return denied(HttpStatus.FORBIDDEN, "UNTRUSTED_MEDIAMTX", request);
        }
        if (request == null || blank(request.token()) || blank(request.path()) || blank(request.id())) {
            return denied(HttpStatus.UNAUTHORIZED, "MISSING_PUBLISH_TICKET", request);
        }
        if (!"publish".equalsIgnoreCase(request.action())) {
            return denied(HttpStatus.FORBIDDEN, "ACTION_NOT_ALLOWED", request);
        }
        if (!"rtmp".equalsIgnoreCase(request.protocol())) {
            return denied(HttpStatus.FORBIDDEN, "PROTOCOL_NOT_ALLOWED", request);
        }
        if (!request.path().matches("^zhibo-live/ls_[A-Za-z0-9]{16,64}$")) {
            return denied(HttpStatus.FORBIDDEN, "INVALID_STREAM_PATH", request);
        }

        LiveStreamSession session = sessionMapper.selectOne(new LambdaQueryWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getTicketHash, LiveStreamSecurity.sha256(request.token())).last("LIMIT 1"));
        if (session == null) return denied(HttpStatus.UNAUTHORIZED, "INVALID_PUBLISH_TICKET", request);
        LocalDateTime now = LocalDateTime.now();
        if (session.getTicketExpiresAt() == null || !session.getTicketExpiresAt().isAfter(now)) {
            expire(session);
            return denied(HttpStatus.UNAUTHORIZED, "PUBLISH_TICKET_EXPIRED", request);
        }
        if (!request.path().equals(session.getPath())) {
            return denied(HttpStatus.FORBIDDEN, "STREAM_PATH_MISMATCH", request);
        }

        BusinessContext business;
        try {
            business = businessService.requireAvailableByAppId(3);
            LiveStreamSessionService.requireLiveBusiness(business);
        } catch (RuntimeException e) {
            return denied(HttpStatus.FORBIDDEN, "ZHIBO_LIVE_UNAVAILABLE", request);
        }
        if (!business.bizIdEquals(session.getBizId())) {
            return denied(HttpStatus.FORBIDDEN, "BUSINESS_MISMATCH", request);
        }

        User user = userMapper.selectById(session.getUserId());
        try {
            LiveStreamSessionService.validateEntitlement(user, session.getBizId());
        } catch (RuntimeException e) {
            return denied(HttpStatus.FORBIDDEN, "USER_ENTITLEMENT_INVALID", request);
        }
        if (!LiveStreamSecurity.sha256(user.getDeviceId()).equals(session.getDeviceIdHash())) {
            return denied(HttpStatus.FORBIDDEN, "DEVICE_BINDING_CHANGED", request);
        }

        if ("ISSUED".equals(session.getStatus())) {
            int updated = sessionMapper.update(null, new LambdaUpdateWrapper<LiveStreamSession>()
                    .eq(LiveStreamSession::getId, session.getId())
                    .eq(LiveStreamSession::getStatus, "ISSUED")
                    .gt(LiveStreamSession::getTicketExpiresAt, now)
                    .set(LiveStreamSession::getStatus, "AUTHORIZED")
                    .set(LiveStreamSession::getMediamtxConnectionId, request.id())
                    .set(LiveStreamSession::getClientIp, request.ip())
                    .set(LiveStreamSession::getAuthorizedAt, now)
                    .set(LiveStreamSession::getUpdatedAt, now));
            if (updated == 1) return MediaMtxAuthResult.allowed();
            session = sessionMapper.selectById(session.getId());
        }
        if (session != null && ALLOWED_STATES.contains(session.getStatus())
                && request.id().equals(session.getMediamtxConnectionId())) {
            return MediaMtxAuthResult.allowed();
        }
        return denied(HttpStatus.CONFLICT, "PUBLISH_TICKET_REPLAYED", request);
    }

    private void expire(LiveStreamSession session) {
        if (!"ISSUED".equals(session.getStatus())) return;
        sessionMapper.update(null, new LambdaUpdateWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getId, session.getId())
                .eq(LiveStreamSession::getStatus, "ISSUED")
                .set(LiveStreamSession::getStatus, "EXPIRED")
                .set(LiveStreamSession::getEndedAt, LocalDateTime.now())
                .set(LiveStreamSession::getEndReason, "TICKET_EXPIRED"));
    }

    private MediaMtxAuthResult denied(HttpStatus status, String reason, MediaMtxAuthRequest request) {
        log.warn("MediaMTX publish 鉴权拒绝: reason={}, action={}, protocol={}, path={}, connectionId={}, ip={}",
                reason, safe(request == null ? null : request.action()), safe(request == null ? null : request.protocol()),
                safe(request == null ? null : request.path()), safe(request == null ? null : request.id()),
                safe(request == null ? null : request.ip()));
        return MediaMtxAuthResult.denied(status, reason);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\r\\n\\t]", "_").substring(0, Math.min(value.length(), 128));
    }
}
