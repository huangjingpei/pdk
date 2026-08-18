package com.pdk.interceptor;

import cn.dev33.satoken.stp.StpLogic;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.entity.User;
import com.pdk.mapper.UserMapper;
import com.pdk.service.DeviceBindingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceSecurityInterceptor implements HandlerInterceptor {

    @Qualifier("clientStpLogic")
    private final StpLogic clientStpLogic;
    private final UserMapper userMapper;
    private final DeviceBindingService deviceBindingService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        clientStpLogic.checkLogin();
        User user = userMapper.selectById(clientStpLogic.getLoginIdAsLong());
        if (user == null || "FROZEN".equals(user.getStatus())) {
            clientStpLogic.logout();
            throw new BusinessException(40100, "客户端账号不存在或已冻结");
        }

        String userPhone = request.getHeader("X-PDK-Phone");
        String currentDeviceId = request.getHeader("X-PDK-Device-ID");

        if (userPhone == null || currentDeviceId == null) {
            throw new BusinessException(40101, "缺少设备与用户安全鉴权请求头 (X-PDK-Phone / X-PDK-Device-ID)");
        }

        if (!user.getPhone().equals(userPhone)) {
            throw new BusinessException(40102, "登录会话与请求手机号不一致");
        }
        String persistedDeviceId = user.getDeviceId();
        String cachedDeviceId = deviceBindingService.get(userPhone);
        String activeDeviceId = cachedDeviceId != null ? cachedDeviceId : persistedDeviceId;
        if (activeDeviceId == null || !activeDeviceId.equals(currentDeviceId)) {
            log.warn("单设备互踢触发: phone={}, active={}, incoming={}", userPhone, activeDeviceId, currentDeviceId);
            throw new BusinessException(40103, "ERR_DEVICE_KICK_OUT: 账号已在其他电脑登录，本设备已被迫下线");
        }

        deviceBindingService.bind(userPhone, currentDeviceId);
        request.setAttribute("pdkClientUser", user);
        return true;
    }
}
