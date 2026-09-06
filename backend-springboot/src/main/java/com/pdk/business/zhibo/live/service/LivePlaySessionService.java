package com.pdk.business.zhibo.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pdk.business.zhibo.live.entity.LivePlaySession;
import com.pdk.business.zhibo.live.entity.LiveStreamSession;
import com.pdk.business.zhibo.live.mapper.LivePlaySessionMapper;
import com.pdk.business.zhibo.live.mapper.LiveStreamSessionMapper;
import com.pdk.business.zhibo.live.vo.LivePlaySessionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class LivePlaySessionService {
    private final LivePlaySessionMapper playMapper;
    private final LiveStreamSessionMapper streamMapper;

    public boolean authorizeRead(String nodeCode, String path) {
        return stream(nodeCode, path) != null;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean started(String nodeCode, String path, String readerId, String readerType, String clientIp) {
        LiveStreamSession stream = stream(nodeCode, path);
        if (stream == null || readerId == null || readerId.isBlank()) return false;
        LivePlaySession existing = playMapper.selectOne(new LambdaQueryWrapper<LivePlaySession>()
                .eq(LivePlaySession::getMediaNodeCode, nodeCode)
                .eq(LivePlaySession::getProviderClientId, readerId)
                .eq(LivePlaySession::getStatus, "PLAYING").last("LIMIT 1"));
        if (existing != null) return true;
        LocalDateTime now = LocalDateTime.now();
        LivePlaySession play = new LivePlaySession();
        play.setBizId(stream.getBizId());
        play.setStreamSessionId(stream.getId());
        play.setMediaNodeCode(nodeCode);
        play.setProviderClientId(readerId);
        play.setProtocol(readerType == null || readerType.isBlank() ? "UNKNOWN" : readerType.toUpperCase());
        play.setClientIpHash(clientIp == null || clientIp.isBlank() ? null : LiveStreamSecurity.sha256(clientIp));
        play.setStatus("PLAYING");
        play.setStartedAt(now);
        play.setCreatedAt(now);
        play.setUpdatedAt(now);
        try {
            return playMapper.insert(play) == 1;
        } catch (DuplicateKeyException ignored) {
            return true; // Hook 重试幂等，不为同一个 reader 新增第二行。
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean stopped(String nodeCode, String path, String readerId, String reason) {
        LivePlaySession play = playMapper.selectOne(new LambdaQueryWrapper<LivePlaySession>()
                .eq(LivePlaySession::getMediaNodeCode, nodeCode)
                .eq(LivePlaySession::getProviderClientId, readerId)
                .eq(LivePlaySession::getStatus, "PLAYING")
                .orderByDesc(LivePlaySession::getId).last("LIMIT 1"));
        if (play == null) {
            Long history = playMapper.selectCount(new LambdaQueryWrapper<LivePlaySession>()
                    .eq(LivePlaySession::getMediaNodeCode, nodeCode)
                    .eq(LivePlaySession::getProviderClientId, readerId));
            return history > 0 || stream(nodeCode, path) != null;
        }
        LocalDateTime now = LocalDateTime.now();
        long duration = Math.max(0, Duration.between(play.getStartedAt(), now).getSeconds());
        return playMapper.update(null, new LambdaUpdateWrapper<LivePlaySession>()
                .eq(LivePlaySession::getId, play.getId()).eq(LivePlaySession::getStatus, "PLAYING")
                .set(LivePlaySession::getStatus, "ENDED").set(LivePlaySession::getEndedAt, now)
                .set(LivePlaySession::getDurationSeconds, duration).set(LivePlaySession::getEndReason, reason)
                .set(LivePlaySession::getUpdatedAt, now)) == 1;
    }

    public Page<LivePlaySessionVO> page(long bizId, long page, long size, String status, String nodeCode) {
        return page(bizId, page, size, status, nodeCode, null);
    }

    public Page<LivePlaySessionVO> page(long bizId, long page, long size, String status, String nodeCode,
                                        Set<Long> allowedStreamIds) {
        if (allowedStreamIds != null && allowedStreamIds.isEmpty()) return new Page<>(page, size, 0);
        LambdaQueryWrapper<LivePlaySession> query = new LambdaQueryWrapper<LivePlaySession>()
                .eq(LivePlaySession::getBizId, bizId)
                .eq(status != null && !status.isBlank(), LivePlaySession::getStatus,
                        status == null ? null : status.trim().toUpperCase())
                .eq(nodeCode != null && !nodeCode.isBlank(), LivePlaySession::getMediaNodeCode, nodeCode)
                .orderByDesc(LivePlaySession::getId);
        if (allowedStreamIds != null) query.in(LivePlaySession::getStreamSessionId, allowedStreamIds);
        Page<LivePlaySession> values = playMapper.selectPage(new Page<>(page, Math.min(Math.max(size, 1), 100)), query);
        Page<LivePlaySessionVO> result = new Page<>(values.getCurrent(), values.getSize(), values.getTotal());
        result.setRecords(values.getRecords().stream().map(LivePlaySessionVO::from).toList());
        return result;
    }

    private LiveStreamSession stream(String nodeCode, String path) {
        if (nodeCode == null || nodeCode.isBlank() || path == null || path.isBlank()) return null;
        return streamMapper.selectOne(new LambdaQueryWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getMediaNodeCode, nodeCode)
                .eq(LiveStreamSession::getPath, path)
                .in(LiveStreamSession::getStatus, Set.of("AUTHORIZED", "LIVE"))
                .last("LIMIT 1"));
    }
}
