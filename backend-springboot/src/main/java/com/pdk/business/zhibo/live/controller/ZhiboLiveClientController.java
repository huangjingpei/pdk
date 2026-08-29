package com.pdk.business.zhibo.live.controller;

import com.pdk.business.zhibo.live.dto.CreatePublishTicketDTO;
import com.pdk.business.zhibo.live.service.LiveStreamSessionService;
import com.pdk.business.zhibo.live.vo.LiveStreamSessionVO;
import com.pdk.business.zhibo.live.vo.PublishTicketVO;
import com.pdk.common.api.CommonResult;
import com.pdk.domain.entity.User;
import com.pdk.domain.entity.DeviceLicense;
import com.pdk.domain.entity.UserDevice;
import com.pdk.platform.business.BusinessContext;
import com.pdk.platform.business.BusinessRequestResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/client/zhibo-live")
@RequiredArgsConstructor
public class ZhiboLiveClientController {
    private final LiveStreamSessionService streamService;

    @PostMapping("/publish-tickets")
    public CommonResult<PublishTicketVO> createTicket(@Valid @RequestBody CreatePublishTicketDTO dto,
                                                       HttpServletRequest request) {
        BusinessContext business = BusinessRequestResolver.context(request);
        User user = requireUser(request);
        return CommonResult.success(streamService.issue(business, user, requireLicense(request), requireDevice(request), dto, clientIp(request)),
                "推流票据已签发，请在过期前连接 MediaMTX");
    }

    @GetMapping("/streams/current")
    public CommonResult<List<LiveStreamSessionVO>> current(HttpServletRequest request) {
        BusinessContext business = BusinessRequestResolver.context(request);
        LiveStreamSessionService.requireLiveBusiness(business);
        User user = requireUser(request);
        return CommonResult.success(streamService.listOwnedLicense(business.bizId(), user.getId(), requireLicense(request).getId()));
    }

    @PostMapping("/streams/{sessionNo}/stop")
    public CommonResult<String> stop(@PathVariable String sessionNo, HttpServletRequest request) {
        BusinessContext business = BusinessRequestResolver.context(request);
        LiveStreamSessionService.requireLiveBusiness(business);
        User user = requireUser(request);
        streamService.stopOwnedLicense(business.bizId(), user.getId(), requireLicense(request).getId(), sessionNo, "CLIENT_STOP");
        return CommonResult.success("推流已停止");
    }

    private static User requireUser(HttpServletRequest request) {
        Object value = request.getAttribute("pdkClientUser");
        if (value instanceof User user) return user;
        throw new IllegalStateException("客户端安全拦截器未注入登录用户");
    }

    private static DeviceLicense requireLicense(HttpServletRequest request) {
        Object value = request.getAttribute("pdkClientLicense");
        if (value instanceof DeviceLicense license) return license;
        throw new IllegalStateException("当前直播业务必须由设备许可证会话访问");
    }

    private static UserDevice requireDevice(HttpServletRequest request) {
        Object value = request.getAttribute("pdkClientDevice");
        if (value instanceof UserDevice device) return device;
        throw new IllegalStateException("客户端安全拦截器未注入设备");
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",", 2)[0].trim();
        return request.getRemoteAddr();
    }
}
