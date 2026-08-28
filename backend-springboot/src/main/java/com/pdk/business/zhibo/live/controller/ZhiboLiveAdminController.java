package com.pdk.business.zhibo.live.controller;

import com.pdk.business.zhibo.live.service.LiveStreamSessionService;
import com.pdk.business.zhibo.live.vo.LiveStreamSessionVO;
import com.pdk.common.api.CommonResult;
import com.pdk.domain.entity.Business;
import com.pdk.platform.business.BusinessService;
import com.pdk.security.AdminBusinessScope;
import com.pdk.security.AdminPrincipal;
import com.pdk.security.RequirePermission;
import com.pdk.security.RolePermissions;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/zhibo-live")
@RequiredArgsConstructor
public class ZhiboLiveAdminController {
    private final LiveStreamSessionService streamService;
    private final BusinessService businessService;
    private final AdminBusinessScope businessScope;

    @GetMapping("/streams")
    @RequirePermission(RolePermissions.LIVE_STREAM_VIEW)
    public CommonResult<List<LiveStreamSessionVO>> list(@RequestParam(required = false) String status,
                                                        HttpServletRequest request) {
        long bizId = liveBizId(request);
        return CommonResult.success(streamService.listForAdmin(bizId, status));
    }

    @PostMapping("/streams/{sessionNo}/kick")
    @RequirePermission(RolePermissions.LIVE_STREAM_KICK)
    public CommonResult<String> kick(@PathVariable String sessionNo, HttpServletRequest request) {
        streamService.stopByAdmin(liveBizId(request), sessionNo, "ADMIN_KICK");
        return CommonResult.success("直播连接已停止");
    }

    private long liveBizId(HttpServletRequest request) {
        AdminPrincipal principal = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        Business business = businessService.requireByAppId(3);
        Long scoped = businessScope.enforce(principal, business.getId());
        return scoped == null ? business.getId() : scoped;
    }
}
