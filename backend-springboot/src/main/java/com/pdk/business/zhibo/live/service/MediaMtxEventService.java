package com.pdk.business.zhibo.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pdk.business.zhibo.live.config.MediaMtxProperties;
import com.pdk.business.zhibo.live.entity.LiveStreamSession;
import com.pdk.business.zhibo.live.mapper.LiveStreamSessionMapper;
import com.pdk.mapper.UserMapper;
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
    private final MediaMtxProperties properties;

    public boolean trusted(String token) {
        return properties.isEnabled()
                && LiveStreamSecurity.constantTimeEquals(properties.getInternalServiceToken(), token);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean available(String path, String sourceId) {
        LiveStreamSession session = byPath(path);
        if (session == null) return false;
        if ("LIVE".equals(session.getStatus())) return true;
        if (!"AUTHORIZED".equals(session.getStatus())) return false;
        LocalDateTime now = LocalDateTime.now();
        int activated = sessionMapper.update(null, new LambdaUpdateWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getId, session.getId())
                .eq(LiveStreamSession::getStatus, "AUTHORIZED")
                .set(LiveStreamSession::getStatus, "LIVE")
                .set(LiveStreamSession::getMediamtxSourceId, sourceId)
                .set(LiveStreamSession::getStartedAt, now)
                .set(LiveStreamSession::getBilledUnits, 1)
                .set(LiveStreamSession::getUpdatedAt, now));
        if (activated != 1) return false;

        int billed = userMapper.update(null, new LambdaUpdateWrapper<com.pdk.domain.entity.User>()
                .eq(com.pdk.domain.entity.User::getId, session.getUserId())
                .eq(com.pdk.domain.entity.User::getBizId, session.getBizId())
                .gt(com.pdk.domain.entity.User::getRemainingCalls, 0)
                .setSql("remaining_calls = remaining_calls - 1"));
        if (billed != 1) {
            // 抛异常使事务整体回滚，避免出现“会话已 LIVE 但次数未扣”或并发重复扣减。
            throw new IllegalStateException("直播次数扣减失败");
        }
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean unavailable(String path, String sourceId) {
        LiveStreamSession session = byPath(path);
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

    private LiveStreamSession byPath(String path) {
        if (path == null || path.isBlank()) return null;
        return sessionMapper.selectOne(new LambdaQueryWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getPath, path).last("LIMIT 1"));
    }
}
