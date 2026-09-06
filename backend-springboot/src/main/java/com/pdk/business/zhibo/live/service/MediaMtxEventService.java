package com.pdk.business.zhibo.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pdk.business.zhibo.live.config.MediaMtxProperties;
import com.pdk.business.zhibo.live.entity.LiveStreamSession;
import com.pdk.business.zhibo.live.mapper.LiveStreamSessionMapper;
import com.pdk.mapper.UserMapper;
import com.pdk.mapper.DeviceLicenseMapper;
import com.pdk.domain.entity.DeviceLicense;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class MediaMtxEventService {
    private final LiveStreamSessionMapper sessionMapper;
    private final UserMapper userMapper;
    private final DeviceLicenseMapper licenseMapper;
    private final MediaMtxProperties properties;
    private final LivePlaySessionService playSessionService;
    private final MediaServerNodeService nodeService;

    public boolean trusted(String token) {
        return properties.isEnabled()
                && LiveStreamSecurity.constantTimeEquals(properties.getInternalServiceToken(), token);
    }

    public boolean trusted(String token, String nodeCode) {
        if (!trusted(token)) return false;
        try {
            var node = nodeService.requireByCode(nodeCode);
            return !"DISABLED".equals(node.getStatus()) && "MEDIAMTX".equals(node.getProviderType());
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean available(String path, String sourceId) {
        return available(properties.getNodeCode(), path, sourceId);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean available(String nodeCode, String path, String sourceId) {
        LiveStreamSession session = byPath(nodeCode, path);
        if (session == null) return false;
        if ("LIVE".equals(session.getStatus())) return true;
        if (!"AUTHORIZED".equals(session.getStatus())) return false;
        LocalDateTime now = LocalDateTime.now();
        if (session.getDeviceLicenseId() != null) {
            int billed = licenseMapper.update(null, new LambdaUpdateWrapper<DeviceLicense>()
                    .eq(DeviceLicense::getId, session.getDeviceLicenseId())
                    .eq(DeviceLicense::getBizId, session.getBizId())
                    .eq(DeviceLicense::getStatus, "ACTIVE")
                    .gt(DeviceLicense::getExpireAt, now)
                    .gt(DeviceLicense::getRemainingCalls, 0)
                    .setSql("remaining_calls = remaining_calls - 1, last_used_at = NOW(), version = version + 1"));
            if (billed != 1) throw new IllegalStateException("直播许可证次数扣减失败");
        }
        int activated = sessionMapper.update(null, new LambdaUpdateWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getId, session.getId())
                .eq(LiveStreamSession::getStatus, "AUTHORIZED")
                .set(LiveStreamSession::getStatus, "LIVE")
                .set(LiveStreamSession::getMediamtxSourceId, sourceId)
                .set(LiveStreamSession::getStartedAt, now)
                .set(LiveStreamSession::getBilledUnits, 1)
                .set(LiveStreamSession::getUpdatedAt, now));
        if (activated != 1) {
            if (session.getDeviceLicenseId() != null) throw new IllegalStateException("直播会话并发激活失败");
            return false;
        }

        int billed;
        if (session.getDeviceLicenseId() != null) {
            billed = 1; // 已按 license -> session 的固定锁顺序扣减，避免与到期踢流形成反向锁死。
        } else {
            billed = userMapper.update(null, new LambdaUpdateWrapper<com.pdk.domain.entity.User>()
                    .eq(com.pdk.domain.entity.User::getId, session.getUserId())
                    .eq(com.pdk.domain.entity.User::getBizId, session.getBizId())
                    .gt(com.pdk.domain.entity.User::getRemainingCalls, 0)
                    .setSql("remaining_calls = remaining_calls - 1"));
        }
        if (billed != 1) {
            // 抛异常使事务整体回滚，避免出现“会话已 LIVE 但次数未扣”或并发重复扣减。
            throw new IllegalStateException("直播次数扣减失败");
        }
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean unavailable(String path, String sourceId) {
        return unavailable(properties.getNodeCode(), path, sourceId);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean unavailable(String nodeCode, String path, String sourceId) {
        LiveStreamSession session = byPath(nodeCode, path);
        if (session == null) return false;
        if ("ENDED".equals(session.getStatus())) return true;
        if (!java.util.Set.of("AUTHORIZED", "LIVE", "KICK_REQUESTED").contains(session.getStatus())) return false;
        LocalDateTime now = LocalDateTime.now();
        long duration = session.getStartedAt() == null ? 0
                : Math.max(0, Duration.between(session.getStartedAt(), now).getSeconds());
        return sessionMapper.update(null, new LambdaUpdateWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getId, session.getId())
                .in(LiveStreamSession::getStatus, "AUTHORIZED", "LIVE", "KICK_REQUESTED")
                .set(LiveStreamSession::getStatus, "ENDED")
                .set(LiveStreamSession::getMediamtxSourceId, sourceId)
                .set(LiveStreamSession::getEndedAt, now)
                .set(LiveStreamSession::getDurationSeconds, duration)
                .set(LiveStreamSession::getEndReason, "SOURCE_UNAVAILABLE")
                .set(LiveStreamSession::getUpdatedAt, now)) == 1;
    }

    public boolean read(String nodeCode, String path, String readerId, String readerType, String clientIp) {
        return playSessionService.started(nodeCode, path, readerId, readerType, clientIp);
    }

    public boolean unread(String nodeCode, String path, String readerId) {
        return playSessionService.stopped(nodeCode, path, readerId, "READER_DISCONNECTED");
    }

    private LiveStreamSession byPath(String nodeCode, String path) {
        if (path == null || path.isBlank()) return null;
        return sessionMapper.selectOne(new LambdaQueryWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getPath, path)
                .eq(nodeCode != null && !nodeCode.isBlank(), LiveStreamSession::getMediaNodeCode, nodeCode)
                .last("LIMIT 1"));
    }
}
