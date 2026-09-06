package com.pdk.controller;

import cn.dev33.satoken.stp.StpLogic;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pdk.common.api.CommonResult;
import com.pdk.common.exception.BusinessException;
import com.pdk.common.utils.PasswordHashUtils;
import com.pdk.domain.dto.AdminChangePasswordDTO;
import com.pdk.domain.dto.AdminLoginDTO;
import com.pdk.domain.entity.AdminUser;
import com.pdk.mapper.AdminUserMapper;
import com.pdk.mapper.BusinessMapper;
import com.pdk.security.AdminPrincipal;
import com.pdk.service.LoginLogService;
import com.pdk.security.RolePermissions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {
    private final AdminUserMapper adminUserMapper;
    private final BusinessMapper businessMapper;
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
                admin.getDisplayName(), admin.getRoleCode(), "ADMIN", admin.getBizId(),
                admin.getMustChangePassword());
        return CommonResult.success(sessionPayload(principal), "登录成功");
    }


    @GetMapping("/me")
    public CommonResult<Map<String, Object>> me(HttpServletRequest request) {
        AdminPrincipal p = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        // 每次 /me 都从 DB 重新读取 mustChangePassword，避免改密后前端缓存还显示旧值
        AdminUser fresh = adminUserMapper.selectById(p.id());
        if (fresh != null) {
            p = new AdminPrincipal(p.id(), p.username(), p.displayName(), p.roleCode(), "ADMIN",
                    p.bizId(), fresh.getMustChangePassword());
        }
        return CommonResult.success(sessionPayload(p));
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

    /**
     * 管理员自助改密：当前已登录状态下输入旧密码 + 新密码。
     * 用于首次登录强制改密场景，也支持日常自助修改。改密成功后清掉 must_change_password，
     * 并把当前 token 踢下线，要求用户用新密码重新登录。
     */
    @PostMapping("/change-password")
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<String> changePassword(@Valid @RequestBody AdminChangePasswordDTO dto,
                                              HttpServletRequest request) {
        AdminPrincipal principal = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        if (principal == null) throw new BusinessException(40101, "请先登录");

        AdminUser admin = adminUserMapper.selectById(principal.id());
        if (admin == null || !"ACTIVE".equals(admin.getStatus())) {
            throw new BusinessException(40111, "账号不存在或已停用");
        }

        String oldHash = PasswordHashUtils.sha256(passwordPepper, dto.getOldPassword());
        if (!PasswordHashUtils.constantTimeEquals(admin.getPasswordHash(), oldHash)) {
            throw new BusinessException(40112, "当前密码不正确");
        }
        String newHash = PasswordHashUtils.sha256(passwordPepper, dto.getNewPassword());
        if (PasswordHashUtils.constantTimeEquals(admin.getPasswordHash(), newHash)) {
            throw new BusinessException(40019, "新密码不能与当前密码相同");
        }

        admin.setPasswordHash(newHash);
        admin.setMustChangePassword(0);
        adminUserMapper.updateById(admin);

        // 踢下线当前会话，让用户用新密码重新登录
        adminStpLogic.logout();

        return CommonResult.success("密码已修改，请使用新密码重新登录");
    }

    private Map<String, Object> sessionPayload(AdminPrincipal admin) {
        Map<String, Object> data = new LinkedHashMap<>();
        Long appId = null;
        if (admin.bizId() != null) {
            var business = businessMapper.selectById(admin.bizId());
            appId = business == null ? null : business.getAppId();
        }
        Set<String> permissions = new LinkedHashSet<>(RolePermissions.forRole(admin.roleCode()));
        // PARTNER 的基础角色权限跨业务复用，直播权限只对其所属 ZHIBO_LIVE 业务下发。
        if (!admin.isSuperAdmin() && !Long.valueOf(3).equals(appId)) {
            permissions.removeIf(permission -> permission.startsWith("live:"));
        }
        data.put("tokenName", adminStpLogic.getTokenName());
        data.put("tokenValue", adminStpLogic.getTokenValue());
        data.put("id", admin.id());
        data.put("username", admin.username());
        data.put("displayName", admin.displayName());
        data.put("role", admin.roleCode());
        data.put("bizId", admin.bizId());
        data.put("appId", appId);
        data.put("permissions", permissions);
        data.put("mustChangePassword", admin.requiresPasswordChange());
        return data;
    }
}
