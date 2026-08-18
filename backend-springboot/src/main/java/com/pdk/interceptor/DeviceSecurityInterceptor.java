package com.pdk.interceptor;

import com.pdk.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceSecurityInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();
        if (uri.contains("/auth/") || uri.contains("/card/") || uri.contains("/admin/") || uri.contains("/swagger") || uri.contains("/v3/api-docs")) {
            return true;
        }

        String userPhone = request.getHeader("X-PDK-Phone");
        String currentDeviceId = request.getHeader("X-PDK-Device-ID");

        if (userPhone == null || currentDeviceId == null) {
            throw new BusinessException(40101, "缺少设备与用户安全鉴权请求头 (X-PDK-Phone / X-PDK-Device-ID)");
        }

        // 查询当前账号在 Redis 绑定的活跃 Device UUID
        String activeDeviceId = redisTemplate.opsForValue().get("pdk:device:bind:" + userPhone);
        if (activeDeviceId != null && !activeDeviceId.equals(currentDeviceId)) {
            log.warn("单设备互踢触发: phone={}, active={}, incoming={}", userPhone, activeDeviceId, currentDeviceId);
            throw new BusinessException(40103, "ERR_DEVICE_KICK_OUT: 账号已在其他电脑登录，本设备已被迫下线");
        }

        return true;
    }
}
