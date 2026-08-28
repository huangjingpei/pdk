package com.pdk.service;

import com.pdk.domain.entity.LoginLog;
import com.pdk.mapper.LoginLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 登录与敏感动作留痕。
 * 落库失败绝不阻断主流程——审计日志写不进去也不能让用户登不上。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginLogService {
    private final LoginLogMapper loginLogMapper;

    private static final int MAX_REASON = 200;
    private static final int MAX_UA = 500;

    public void record(Long bizId, String actorType, Long actorId, String actorAccount,
                       String eventType, String result, String failReason,
                       String deviceId, HttpServletRequest request) {
        try {
            LoginLog entry = new LoginLog();
            entry.setBizId(bizId);
            entry.setActorType(actorType);
            entry.setActorId(actorId);
            entry.setActorAccount(truncate(actorAccount, 50));
            entry.setEventType(eventType);
            entry.setResult(result);
            entry.setFailReason(truncate(failReason, MAX_REASON));
            entry.setIpAddress(clientIp(request));
            entry.setDeviceId(truncate(deviceId, 200));
            entry.setUserAgent(truncate(request == null ? null : request.getHeader("User-Agent"), MAX_UA));
            entry.setCreatedAt(LocalDateTime.now());
            loginLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("写入登录日志失败，已忽略 actorAccount={} eventType={} reason={}",
                    actorAccount, eventType, e.getMessage());
        }
    }

    /** 客户端用户登录成功：同时刷新 pdk_user 上的最近登录冗余字段。 */
    public void recordClientSuccess(Long bizId, Long userId, String phone, String deviceId,
                                    HttpServletRequest request) {
        record(bizId, "CLIENT", userId, phone, "LOGIN", "SUCCESS", null, deviceId, request);
    }

    public void recordClientFailure(Long bizId, Long userId, String phone, String reason,
                                    String deviceId, HttpServletRequest request) {
        record(bizId, "CLIENT", userId, phone, "LOGIN", "FAIL", reason, deviceId, request);
    }

    public void recordAdminLogin(Long adminId, String username, boolean success, String reason,
                                 HttpServletRequest request) {
        record(null, "ADMIN", adminId, username, "LOGIN", success ? "SUCCESS" : "FAIL", reason, null, request);
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }
}
