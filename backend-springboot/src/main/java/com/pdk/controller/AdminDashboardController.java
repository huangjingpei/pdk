package com.pdk.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pdk.common.api.CommonResult;
import com.pdk.security.AdminPrincipal;
import com.pdk.domain.entity.CardKey;
import com.pdk.domain.entity.TokenPool;
import com.pdk.domain.entity.User;
import com.pdk.mapper.CardKeyMapper;
import com.pdk.mapper.TokenPoolMapper;
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

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
@RequirePermission(RolePermissions.DASHBOARD_VIEW)
public class AdminDashboardController {
    private final UserMapper userMapper;
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
