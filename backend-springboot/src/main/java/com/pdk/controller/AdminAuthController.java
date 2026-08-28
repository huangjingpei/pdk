package com.pdk.controller;

import cn.dev33.satoken.stp.StpLogic;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pdk.common.api.CommonResult;
import com.pdk.common.exception.BusinessException;
import com.pdk.common.utils.PasswordHashUtils;
import com.pdk.domain.dto.AdminLoginDTO;
import com.pdk.domain.entity.AdminUser;
import com.pdk.mapper.AdminUserMapper;
import com.pdk.security.AdminPrincipal;
import com.pdk.service.LoginLogService;
import com.pdk.security.RolePermissions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {
    private final AdminUserMapper adminUserMapper;
    private final LoginLogService loginLogService;
    @Qualifier("adminStpLogic")
    private final StpLogic adminStpLogic;

    @Value("${pdk.security.admin-password-pepper}")
    private String passwordPepper;

    /**
     * 统一后台登录：仅认 pdk_admin_user 表，角色为 SUPER_ADMIN 或 PARTNER 均视为管理员。
     * 登录后按角色拿到对应权限，前端据此展示不同内容；不再允许客户端身份登录后台。
     */
    @PostMapping("/login")
    public CommonResult<Map<String, Object>> login(@Valid @RequestBody AdminLoginDTO dto,
                                                    HttpServletRequest request) {
        AdminUser admin = adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, dto.getUsername()));
        boolean matched = admin != null
                && "ACTIVE".equals(admin.getStatus())
                && java.util.Set.of("SUPER_ADMIN", "PARTNER").contains(admin.getRoleCode())
                && PasswordHashUtils.constantTimeEquals(admin.getPasswordHash(),
                        PasswordHashUtils.sha256(passwordPepper, dto.getPassword()));
        if (!matched) {
            loginLogService.recordAdminLogin(admin == null ? null : admin.getId(), dto.getUsername(),
                    false, admin == null ? "管理账号不存在" : "管理账号或密码错误", request);
            throw new BusinessException(40111, "管理账号或密码错误");
        }
        adminStpLogic.login("ADMIN:" + admin.getId());
        admin.setLastLoginAt(LocalDateTime.now());
        adminUserMapper.updateById(admin);
        loginLogService.recordAdminLogin(admin.getId(), admin.getUsername(), true, null, request);
        AdminPrincipal principal = new AdminPrincipal(admin.getId(), admin.getUsername(),
                admin.getDisplayName(), admin.getRoleCode(), "ADMIN", admin.getBizId());
        return CommonResult.success(sessionPayload(principal), "登录成功");
    }


    @GetMapping("/me")
    public CommonResult<Map<String, Object>> me(HttpServletRequest request) {
        return CommonResult.success(sessionPayload((AdminPrincipal) request.getAttribute("pdkAdminPrincipal")));
    }

    @PostMapping("/logout")
    public CommonResult<String> logout(HttpServletRequest request) {
        AdminPrincipal principal = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        if (principal != null) {
            loginLogService.record(null, "ADMIN", principal.id(), principal.username(),
                    "LOGOUT", "SUCCESS", null, null, request);
        }
        adminStpLogic.logout();
        return CommonResult.success("已安全退出");
    }

    private Map<String, Object> sessionPayload(AdminPrincipal admin) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tokenName", adminStpLogic.getTokenName());
        data.put("tokenValue", adminStpLogic.getTokenValue());
        data.put("id", admin.id());
        data.put("username", admin.username());
        data.put("displayName", admin.displayName());
        data.put("role", admin.roleCode());
        data.put("bizId", admin.bizId());
        data.put("permissions", RolePermissions.forRole(admin.roleCode()));
        return data;
    }
}
