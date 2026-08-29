package com.pdk.business.zhibo.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pdk.business.zhibo.ZhiboBusinessHandler;
import com.pdk.business.zhibo.live.config.MediaMtxProperties;
import com.pdk.business.zhibo.live.dto.CreatePublishTicketDTO;
import com.pdk.business.zhibo.live.entity.LiveStreamSession;
import com.pdk.business.zhibo.live.mapper.LiveStreamSessionMapper;
import com.pdk.business.zhibo.live.vo.LiveStreamSessionVO;
import com.pdk.business.zhibo.live.vo.PublishTicketVO;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.entity.User;
import com.pdk.domain.entity.DeviceLicense;
import com.pdk.domain.entity.UserDevice;
import com.pdk.platform.business.BusinessContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LiveStreamSessionService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final List<String> ACTIVE_STATUSES = List.of("ISSUED", "AUTHORIZED", "LIVE", "KICK_REQUESTED");

    private final LiveStreamSessionMapper sessionMapper;
    private final MediaMtxProperties properties;
    private final MediaMtxControlClient controlClient;

    @Transactional(rollbackFor = Exception.class)
    public PublishTicketVO issue(BusinessContext business, User user, CreatePublishTicketDTO dto, String clientIp) {
        return issue(business, user, null, null, dto, clientIp);
    }

    @Transactional(rollbackFor = Exception.class)
    public PublishTicketVO issue(BusinessContext business, User user, DeviceLicense license, UserDevice device,
                                 CreatePublishTicketDTO dto, String clientIp) {
        requireLiveBusiness(business);
        if (!properties.isEnabled()) {
            throw new BusinessException(50370, "当前部署尚未启用 MediaMTX 推流服务");
        }
        if (business.usesDeviceLicense()) validateLicenseEntitlement(user, license, device, business.bizId());
        else validateEntitlement(user, business.bizId());
        expireUnusedTickets(user.getId(), license == null ? null : license.getId());

        String requestId = dto == null || dto.clientRequestId() == null || dto.clientRequestId().isBlank()
                ? UUID.randomUUID().toString() : dto.clientRequestId().trim();
        if (sessionMapper.selectCount(new LambdaQueryWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getBizId, business.bizId())
                .eq(LiveStreamSession::getUserId, user.getId())
                .eq(LiveStreamSession::getClientRequestId, requestId)) > 0) {
            throw new BusinessException(40970, "clientRequestId 已使用，请生成新的请求 ID");
        }

        String ticket = randomTicket();
        String sessionNo = "ls_" + UUID.randomUUID().toString().replace("-", "");
        String path = "zhibo-live/" + sessionNo;
        long ttl = Math.max(30, Math.min(properties.getTicketTtlSeconds(), 300));
        LocalDateTime now = LocalDateTime.now();

        LiveStreamSession session = new LiveStreamSession();
        session.setBizId(business.bizId());
        session.setUserId(user.getId());
        session.setUserDeviceId(device == null ? null : device.getId());
        session.setDeviceLicenseId(license == null ? null : license.getId());
        session.setStreamSessionNo(sessionNo);
        session.setClientRequestId(requestId);
        session.setMediaNodeCode(properties.getNodeCode());
        session.setPath(path);
        session.setProtocol(resolveProtocol(dto));
        session.setStatus("ISSUED");
        session.setTicketHash(LiveStreamSecurity.sha256(ticket));
        session.setTicketExpiresAt(now.plusSeconds(ttl));
        session.setDeviceIdHash(device == null
                ? LiveStreamSecurity.sha256(user.getDeviceId()) : device.getDeviceIdHash());
        session.setClientIp(clientIp);
        session.setBilledUnits(0);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        try {
            sessionMapper.insert(session);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(40971, "当前账号已有待推流或正在推流的会话，请先停止后重试");
        }

        String base = properties.getPublicRtmpBaseUrl().replaceAll("/+$", "");
        return new PublishTicketVO(sessionNo, base + "/" + path + "?token=" + ticket,
                session.getTicketExpiresAt(), ttl, session.getStatus());
    }

    public List<LiveStreamSessionVO> listOwned(long bizId, long userId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<LiveStreamSession>()
                        .eq(LiveStreamSession::getBizId, bizId)
                        .eq(LiveStreamSession::getUserId, userId)
                        .orderByDesc(LiveStreamSession::getId).last("LIMIT 100"))
                .stream().map(LiveStreamSessionVO::from).toList();
    }

    public List<LiveStreamSessionVO> listOwnedLicense(long bizId, long userId, long licenseId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<LiveStreamSession>()
                        .eq(LiveStreamSession::getBizId, bizId).eq(LiveStreamSession::getUserId, userId)
                        .eq(LiveStreamSession::getDeviceLicenseId, licenseId)
                        .orderByDesc(LiveStreamSession::getId).last("LIMIT 100"))
                .stream().map(LiveStreamSessionVO::from).toList();
    }

    public List<LiveStreamSessionVO> listForAdmin(long bizId, String status) {
        LambdaQueryWrapper<LiveStreamSession> query = new LambdaQueryWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getBizId, bizId)
                .orderByDesc(LiveStreamSession::getId).last("LIMIT 500");
        if (status != null && !status.isBlank()) query.eq(LiveStreamSession::getStatus, status.trim().toUpperCase());
        return sessionMapper.selectList(query).stream().map(LiveStreamSessionVO::from).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void stopOwned(long bizId, long userId, String sessionNo, String reason) {
        LiveStreamSession session = requireSession(bizId, sessionNo);
        if (!session.getUserId().equals(userId)) throw new BusinessException(40370, "无权停止其他用户的推流");
        stop(session, reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public void stopOwnedLicense(long bizId, long userId, long licenseId, String sessionNo, String reason) {
        LiveStreamSession session = requireSession(bizId, sessionNo);
        if (!session.getUserId().equals(userId) || !Long.valueOf(licenseId).equals(session.getDeviceLicenseId())) {
            throw new BusinessException(40370, "无权停止其他设备许可证的推流");
        }
        stop(session, reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public void stopByAdmin(long bizId, String sessionNo, String reason) {
        stop(requireSession(bizId, sessionNo), reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public void revokeUserSessions(long bizId, long userId, String reason) {
        List<LiveStreamSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getBizId, bizId).eq(LiveStreamSession::getUserId, userId)
                .in(LiveStreamSession::getStatus, ACTIVE_STATUSES));
        for (LiveStreamSession session : sessions) stop(session, reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public void revokeLicenseSessions(long bizId, long licenseId, String reason) {
        List<LiveStreamSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getBizId, bizId).eq(LiveStreamSession::getDeviceLicenseId, licenseId)
                .in(LiveStreamSession::getStatus, ACTIVE_STATUSES));
        for (LiveStreamSession session : sessions) stop(session, reason);
    }

    private void stop(LiveStreamSession session, String reason) {
        if (!ACTIVE_STATUSES.contains(session.getStatus())) return;
        if (session.getMediamtxConnectionId() != null && !session.getMediamtxConnectionId().isBlank()) {
            controlClient.kick(session);
        }
        LocalDateTime now = LocalDateTime.now();
        session.setStatus("ENDED");
        session.setEndedAt(now);
        session.setEndReason(reason);
        if (session.getStartedAt() != null) {
            session.setDurationSeconds(Math.max(0, Duration.between(session.getStartedAt(), now).getSeconds()));
        }
        session.setUpdatedAt(now);
        sessionMapper.updateById(session);
    }

    private LiveStreamSession requireSession(long bizId, String sessionNo) {
        LiveStreamSession session = sessionMapper.selectOne(new LambdaQueryWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getBizId, bizId)
                .eq(LiveStreamSession::getStreamSessionNo, sessionNo).last("LIMIT 1"));
        if (session == null) throw new BusinessException(40470, "直播会话不存在");
        return session;
    }

    private void expireUnusedTickets(long userId, Long licenseId) {
        LambdaUpdateWrapper<LiveStreamSession> update = new LambdaUpdateWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getUserId, userId);
        if (licenseId != null) update.eq(LiveStreamSession::getDeviceLicenseId, licenseId);
        sessionMapper.update(null, update
                .eq(LiveStreamSession::getStatus, "ISSUED")
                .lt(LiveStreamSession::getTicketExpiresAt, LocalDateTime.now())
                .set(LiveStreamSession::getStatus, "EXPIRED")
                .set(LiveStreamSession::getEndReason, "TICKET_EXPIRED")
                .set(LiveStreamSession::getEndedAt, LocalDateTime.now()));
    }

    public static void requireLiveBusiness(BusinessContext business) {
        if (business == null || business.appId() != 3 || !ZhiboBusinessHandler.LIVE_CODE.equals(business.bizCode())) {
            throw new BusinessException(40370, "该接口仅允许 appId=3 / ZHIBO_LIVE 使用");
        }
    }

    static void validateEntitlement(User user, long bizId) {
        LocalDateTime now = LocalDateTime.now();
        if (user == null || user.getBizId() == null || user.getBizId() != bizId) {
            throw new BusinessException(40106, "登录用户不属于 ZHIBO_LIVE");
        }
        if ("FROZEN".equals(user.getStatus())) throw new BusinessException(40371, "账号已被冻结，禁止推流");
        if (user.getDeviceId() == null || user.getDeviceId().isBlank()) {
            throw new BusinessException(40372, "账号尚未绑定设备，禁止推流");
        }
        if (user.getExpireTime() == null || !user.getExpireTime().isAfter(now)) {
            throw new BusinessException(40373, "套餐未开通或已过期，禁止推流");
        }
        if (user.getRemainingCalls() == null || user.getRemainingCalls() <= 0) {
            throw new BusinessException(40374, "直播可用次数不足，禁止推流");
        }
    }

    public static void validateLicenseEntitlement(User user, DeviceLicense license, UserDevice device, long bizId) {
        if (user == null || user.getBizId() == null || user.getBizId() != bizId || "FROZEN".equals(user.getStatus())) {
            throw new BusinessException(40371, "账号不存在、跨业务或已冻结，禁止推流");
        }
        if (license == null || license.getBizId() == null || license.getBizId() != bizId
                || !user.getId().equals(license.getUserId())) {
            throw new BusinessException(40385, "当前设备没有许可证");
        }
        if (device == null || !"ACTIVE".equals(device.getStatus())
                || !device.getId().equals(license.getUserDeviceId())) {
            throw new BusinessException(40372, "许可证设备绑定无效，禁止推流");
        }
        if (!"ACTIVE".equals(license.getStatus())) throw new BusinessException(40384, "许可证已暂停、过期或作废");
        if (license.getExpireAt() == null || !license.getExpireAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(40381, "当前设备许可证已到期");
        }
        if (license.getRemainingCalls() == null || license.getRemainingCalls() <= 0) {
            throw new BusinessException(40374, "直播可用次数不足，禁止推流");
        }
    }

    private static String resolveProtocol(CreatePublishTicketDTO dto) {
        return "RTMP";
    }

    private static String randomTicket() {
        byte[] value = new byte[32];
        SECURE_RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
