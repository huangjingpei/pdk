package com.pdk.business.zhibo.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pdk.business.zhibo.live.config.MediaMtxProperties;
import com.pdk.business.zhibo.live.dto.MediaMtxAuthRequest;
import com.pdk.business.zhibo.live.entity.LiveStreamSession;
import com.pdk.business.zhibo.live.mapper.LiveStreamSessionMapper;
import com.pdk.domain.entity.User;
import com.pdk.domain.entity.DeviceLicense;
import com.pdk.domain.entity.UserDevice;
import com.pdk.mapper.UserMapper;
import com.pdk.mapper.DeviceLicenseMapper;
import com.pdk.mapper.UserDeviceMapper;
import com.pdk.platform.business.BusinessContext;
import com.pdk.platform.business.BusinessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class MediaMtxAuthService {
    private static final Set<String> ALLOWED_STATES = Set.of("AUTHORIZED", "LIVE");

    private final LiveStreamSessionMapper sessionMapper;
    private final UserMapper userMapper;
    private final DeviceLicenseMapper licenseMapper;
    private final UserDeviceMapper deviceMapper;
    private final BusinessService businessService;
    private final MediaMtxProperties properties;
    @Autowired(required = false)
    private MediaServerNodeService nodeService;
    @Autowired(required = false)
    private LivePlaySessionService playSessionService;

    public MediaMtxAuthService(LiveStreamSessionMapper sessionMapper, UserMapper userMapper,
                               BusinessService businessService, MediaMtxProperties properties) {
        this(sessionMapper, userMapper, null, null, businessService, properties);
    }

    @Transactional(rollbackFor = Exception.class)
    public MediaMtxAuthResult authorize(String serviceToken, MediaMtxAuthRequest request) {
        return authorizeProvider(serviceToken, properties.getNodeCode(), "MEDIAMTX", request);
    }

    @Transactional(rollbackFor = Exception.class)
    public MediaMtxAuthResult authorize(String serviceToken, String nodeCode, MediaMtxAuthRequest request) {
        return authorizeProvider(serviceToken, nodeCode, "MEDIAMTX", request);
    }

    @Transactional(rollbackFor = Exception.class)
    public MediaMtxAuthResult authorizeProvider(String serviceToken, String nodeCode, String providerType,
                                                MediaMtxAuthRequest request) {
        if (!properties.isEnabled()) return denied(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_SERVICE_DISABLED", request);
        if (!LiveStreamSecurity.constantTimeEquals(properties.getInternalServiceToken(), serviceToken)) {
            return denied(HttpStatus.FORBIDDEN, "UNTRUSTED_MEDIAMTX", request);
        }
        if (nodeService != null) {
            try {
                var node = nodeService.requireByCode(nodeCode);
                if ("DISABLED".equals(node.getStatus()) || !providerType.equals(node.getProviderType())) {
                    return denied(HttpStatus.FORBIDDEN, "MEDIA_NODE_DISABLED_OR_MISMATCH", request);
                }
            } catch (RuntimeException e) {
                return denied(HttpStatus.FORBIDDEN, "UNKNOWN_MEDIA_NODE", request);
            }
        }
        if (request == null || blank(request.path()) || blank(request.id())) {
            return denied(HttpStatus.UNAUTHORIZED, "MISSING_MEDIA_CREDENTIAL", request);
        }
        if ("read".equalsIgnoreCase(request.action())) {
            if (playSessionService != null && playSessionService.authorizeRead(nodeCode, request.path())) {
                return MediaMtxAuthResult.allowed();
            }
            return denied(HttpStatus.FORBIDDEN, "STREAM_NOT_LIVE", request);
        }
        if (!"publish".equalsIgnoreCase(request.action())) {
            return denied(HttpStatus.FORBIDDEN, "ACTION_NOT_ALLOWED", request);
        }
        String publishTicket = blank(request.token()) ? queryParam(request.query(), "token") : request.token();
        if (blank(publishTicket)) return denied(HttpStatus.UNAUTHORIZED, "MISSING_PUBLISH_TICKET", request);
        if (!"rtmp".equalsIgnoreCase(request.protocol())) {
            return denied(HttpStatus.FORBIDDEN, "PROTOCOL_NOT_ALLOWED", request);
        }
        if (!request.path().matches("^zhibo-live/ls_[A-Za-z0-9]{16,64}$")) {
            return denied(HttpStatus.FORBIDDEN, "INVALID_STREAM_PATH", request);
        }

        LiveStreamSession session = sessionMapper.selectOne(new LambdaQueryWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getTicketHash, LiveStreamSecurity.sha256(publishTicket)).last("LIMIT 1"));
        if (session == null) return denied(HttpStatus.UNAUTHORIZED, "INVALID_PUBLISH_TICKET", request);
        LocalDateTime now = LocalDateTime.now();
        if (session.getTicketExpiresAt() == null || !session.getTicketExpiresAt().isAfter(now)) {
            expire(session);
            return denied(HttpStatus.UNAUTHORIZED, "PUBLISH_TICKET_EXPIRED", request);
        }
        if (!request.path().equals(session.getPath())) {
            return denied(HttpStatus.FORBIDDEN, "STREAM_PATH_MISMATCH", request);
        }
        if (nodeCode != null && !nodeCode.isBlank() && session.getMediaNodeCode() != null
                && !session.getMediaNodeCode().isBlank() && !nodeCode.equals(session.getMediaNodeCode())) {
            return denied(HttpStatus.FORBIDDEN, "MEDIA_NODE_MISMATCH", request);
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
        String activeDeviceHash;
        try {
            if (session.getDeviceLicenseId() != null) {
                DeviceLicense license = licenseMapper.selectById(session.getDeviceLicenseId());
                UserDevice device = license == null || license.getUserDeviceId() == null
                        ? null : deviceMapper.selectById(license.getUserDeviceId());
                LiveStreamSessionService.validateLicenseEntitlement(user, license, device, session.getBizId());
                if (!session.getDeviceLicenseId().equals(license.getId())
                        || !session.getUserDeviceId().equals(device.getId())) {
                    return denied(HttpStatus.FORBIDDEN, "LICENSE_BINDING_CHANGED", request);
                }
                activeDeviceHash = device.getDeviceIdHash();
            } else {
                LiveStreamSessionService.validateEntitlement(user, session.getBizId());
                activeDeviceHash = LiveStreamSecurity.sha256(user.getDeviceId());
            }
        } catch (RuntimeException e) {
            return denied(HttpStatus.FORBIDDEN, "USER_ENTITLEMENT_INVALID", request);
        }
        if (!activeDeviceHash.equals(session.getDeviceIdHash())) {
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

    /** MediaMTX 1.11 把 RTMP token 放在 query，较新版本会同时提供 token 字段。 */
    private static String queryParam(String query, String name) {
        if (query == null || query.isBlank()) return null;
        String value = query.startsWith("?") ? query.substring(1) : query;
        for (String pair : value.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            if (!name.equals(key)) continue;
            String raw = separator < 0 ? "" : pair.substring(separator + 1);
            try {
                return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\r\\n\\t]", "_").substring(0, Math.min(value.length(), 128));
    }
}
