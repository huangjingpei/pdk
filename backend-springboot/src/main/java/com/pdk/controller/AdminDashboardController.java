package com.pdk.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pdk.common.api.CommonResult;
import com.pdk.security.AdminPrincipal;
import com.pdk.domain.entity.CardKey;
import com.pdk.domain.entity.TokenPool;
import com.pdk.domain.entity.User;
import com.pdk.domain.entity.UserDevice;
import com.pdk.mapper.CardKeyMapper;
import com.pdk.mapper.TokenPoolMapper;
import com.pdk.mapper.UserDeviceMapper;
import com.pdk.mapper.UserMapper;
import com.pdk.security.RequirePermission;
import com.pdk.security.RolePermissions;
import com.pdk.security.AdminBusinessScope;
import com.pdk.service.IFinancialService;
import com.pdk.business.zhibo.live.service.MediaServerNodeService;
import com.pdk.platform.business.BusinessService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
@RequirePermission(RolePermissions.DASHBOARD_VIEW)
public class AdminDashboardController {
    private final UserMapper userMapper;
    private final UserDeviceMapper userDeviceMapper;
    private final CardKeyMapper cardKeyMapper;
    private final TokenPoolMapper tokenPoolMapper;
    private final IFinancialService financialService;
    private final AdminBusinessScope businessScope;
    private final MediaServerNodeService mediaServerNodeService;
    private final BusinessService businessService;

    @GetMapping("/summary")
    public CommonResult<Map<String, Object>> summary(HttpServletRequest request) {
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        Long bizId = businessScope.enforce(admin, null);
        Map<String, Object> data = new LinkedHashMap<>();
        boolean seesUsers = RolePermissions.has(admin.roleCode(), RolePermissions.USER_VIEW);
        boolean seesTokens = RolePermissions.has(admin.roleCode(), RolePermissions.TOKEN_VIEW);
        boolean seesCards = RolePermissions.has(admin.roleCode(), RolePermissions.CARD_VIEW);
        if (seesUsers) {
            data.put("userCount", userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(bizId != null, User::getBizId, bizId)));
            data.put("activeUserCount", userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(bizId != null, User::getBizId, bizId).ne(User::getStatus, "FROZEN")));

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime tenDaysAgo = now.minusDays(10);
            LocalDateTime thirtyDaysAgo = now.minusDays(30);
            LocalDateTime ninetyDaysAgo = now.minusDays(90);

            // 近 10/30/90 天活跃用户数 (以 last_login_at 统计)
            long activeUsers10d = userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(bizId != null, User::getBizId, bizId)
                    .ge(User::getLastLoginAt, tenDaysAgo));
            long activeUsers30d = userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(bizId != null, User::getBizId, bizId)
                    .ge(User::getLastLoginAt, thirtyDaysAgo));
            long activeUsers90d = userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(bizId != null, User::getBizId, bizId)
                    .ge(User::getLastLoginAt, ninetyDaysAgo));

            data.put("activeUsers10d", activeUsers10d);
            data.put("activeUsers30d", activeUsers30d);
            data.put("activeUsers90d", activeUsers90d);

            // 活跃设备数及近 10/30/90 天活跃设备数
            // 先统计 pdk_user_device 表
            long deviceCount = userDeviceMapper.selectCount(new LambdaQueryWrapper<UserDevice>()
                    .eq(bizId != null, UserDevice::getBizId, bizId));
            long activeDeviceCount = userDeviceMapper.selectCount(new LambdaQueryWrapper<UserDevice>()
                    .eq(bizId != null, UserDevice::getBizId, bizId)
                    .ne(UserDevice::getStatus, "UNBOUND"));
            long activeDevices10d = userDeviceMapper.selectCount(new LambdaQueryWrapper<UserDevice>()
                    .eq(bizId != null, UserDevice::getBizId, bizId)
                    .ge(UserDevice::getLastLoginAt, tenDaysAgo));
            long activeDevices30d = userDeviceMapper.selectCount(new LambdaQueryWrapper<UserDevice>()
                    .eq(bizId != null, UserDevice::getBizId, bizId)
                    .ge(UserDevice::getLastLoginAt, thirtyDaysAgo));
            long activeDevices90d = userDeviceMapper.selectCount(new LambdaQueryWrapper<UserDevice>()
                    .eq(bizId != null, UserDevice::getBizId, bizId)
                    .ge(UserDevice::getLastLoginAt, ninetyDaysAgo));

            // 若尚未存在 pdk_user_device 记录（例如纯订阅制单机绑定），回退统计 pdk_user 上绑定的 device_id 与活跃时间
            if (deviceCount == 0) {
                deviceCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                        .eq(bizId != null, User::getBizId, bizId)
                        .isNotNull(User::getDeviceId));
                activeDeviceCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                        .eq(bizId != null, User::getBizId, bizId)
                        .isNotNull(User::getDeviceId)
                        .ne(User::getStatus, "FROZEN"));
                activeDevices10d = userMapper.selectCount(new LambdaQueryWrapper<User>()
                        .eq(bizId != null, User::getBizId, bizId)
                        .isNotNull(User::getDeviceId)
                        .ge(User::getLastLoginAt, tenDaysAgo));
                activeDevices30d = userMapper.selectCount(new LambdaQueryWrapper<User>()
                        .eq(bizId != null, User::getBizId, bizId)
                        .isNotNull(User::getDeviceId)
                        .ge(User::getLastLoginAt, thirtyDaysAgo));
                activeDevices90d = userMapper.selectCount(new LambdaQueryWrapper<User>()
                        .eq(bizId != null, User::getBizId, bizId)
                        .isNotNull(User::getDeviceId)
                        .ge(User::getLastLoginAt, ninetyDaysAgo));
            }

            data.put("deviceCount", deviceCount);
            data.put("activeDeviceCount", activeDeviceCount);
            data.put("activeDevices10d", activeDevices10d);
            data.put("activeDevices30d", activeDevices30d);
            data.put("activeDevices90d", activeDevices90d);

            Map<String, Object> activity = new LinkedHashMap<>();
            activity.put("activeUsers10d", activeUsers10d);
            activity.put("activeUsers30d", activeUsers30d);
            activity.put("activeUsers90d", activeUsers90d);
            activity.put("activeDevices10d", activeDevices10d);
            activity.put("activeDevices30d", activeDevices30d);
            activity.put("activeDevices90d", activeDevices90d);
            data.put("activity", activity);
        }
        if (seesTokens) {
            data.put("healthyResourceCount", tokenPoolMapper.selectCount(new LambdaQueryWrapper<TokenPool>()
                    .eq(bizId != null, TokenPool::getBizId, bizId)
                    .eq(TokenPool::getHealthStatus, "HEALTHY")));
        }
        if (seesCards) {
            LambdaQueryWrapper<CardKey> cards = new LambdaQueryWrapper<CardKey>().eq(CardKey::getStatus, "UNUSED");
            cards.eq(bizId != null, CardKey::getBizId, bizId);
            if ("PARTNER".equals(admin.roleCode())) {
                cards.eq(CardKey::getGeneratedByAdmin, admin.username());
            }
            data.put("unusedCardCount", cardKeyMapper.selectCount(cards));
        }
        if (RolePermissions.has(admin.roleCode(), RolePermissions.FINANCE_VIEW)) {
            data.put("finance", financialService.getFinanceSummary(bizId));
        }
        // 全局节点健康与带宽属于平台基础设施数据，Dashboard 只向超级管理员展示。
        // 代理自己的直播统计由 /admin/zhibo-live/overview 按许可证范围返回。
        if (admin.isSuperAdmin() && RolePermissions.has(admin.roleCode(), RolePermissions.LIVE_OVERVIEW_VIEW)) {
            var liveBusiness = businessService.requireByAppId(3);
            data.put("live", mediaServerNodeService.overview(liveBusiness.getId()));
        }
        return CommonResult.success(data);
    }
}
