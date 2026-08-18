package com.pdk.controller;

import cn.dev33.satoken.stp.StpLogic;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pdk.common.api.CommonResult;
import com.pdk.common.exception.BusinessException;
import com.pdk.common.utils.PasswordHashUtils;
import com.pdk.domain.dto.AdminLoginDTO;
import com.pdk.domain.entity.AdminUser;
import com.pdk.domain.entity.User;
import com.pdk.domain.entity.UserCredential;
import com.pdk.mapper.AdminUserMapper;
import com.pdk.mapper.UserMapper;
import com.pdk.mapper.UserCredentialMapper;
import com.pdk.security.AdminPrincipal;
import com.pdk.security.RolePermissions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.pdk.service.InvitationService;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {
    private final AdminUserMapper adminUserMapper;
    private final UserMapper userMapper;
    private final UserCredentialMapper credentialMapper;
    private final PasswordEncoder passwordEncoder;
    private final InvitationService invitationService;
    @Qualifier("adminStpLogic")
    private final StpLogic adminStpLogic;

    @Value("${pdk.security.admin-password-pepper}")
    private String passwordPepper;

    @PostMapping("/login")
    public CommonResult<Map<String, Object>> login(@Valid @RequestBody AdminLoginDTO dto) {
        AdminUser admin = adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, dto.getUsername()));
        AdminPrincipal principal;
        if (admin != null) {
            String incomingHash = PasswordHashUtils.sha256(passwordPepper, dto.getPassword());
            if (!PasswordHashUtils.constantTimeEquals(admin.getPasswordHash(), incomingHash)
                    || !"ACTIVE".equals(admin.getStatus()) || !"SUPER_ADMIN".equals(admin.getRoleCode())) {
                throw new BusinessException(40111, "管理账号或密码错误");
            }
            adminStpLogic.login("ADMIN:" + admin.getId());
            admin.setLastLoginAt(LocalDateTime.now());
            adminUserMapper.updateById(admin);
            principal = new AdminPrincipal(admin.getId(), admin.getUsername(), admin.getDisplayName(), "SUPER_ADMIN", "ADMIN");
        } else {
            User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, dto.getUsername()));
            UserCredential credential = user == null ? null : credentialMapper.selectOne(new LambdaQueryWrapper<UserCredential>()
                    .eq(UserCredential::getUserId, user.getId()));
            if (user == null || credential == null || !"PARTNER".equals(credential.getRoleCode())
                    || !"ACTIVE".equals(credential.getStatus()) || !passwordEncoder.matches(dto.getPassword(), credential.getPasswordHash())) {
                throw new BusinessException(40111, "管理账号或密码错误，普通客户不能登录管理后台");
            }
            adminStpLogic.login("USER:" + user.getId());
            principal = new AdminPrincipal(user.getId(), user.getPhone(), user.getPhone(), "PARTNER", "USER");
        }
        return CommonResult.success(sessionPayload(principal), "登录成功");
    }

    @GetMapping("/me")
    public CommonResult<Map<String, Object>> me(HttpServletRequest request) {
        return CommonResult.success(sessionPayload((AdminPrincipal) request.getAttribute("pdkAdminPrincipal")));
    }

    @PostMapping("/logout")
    public CommonResult<String> logout() {
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
        data.put("permissions", RolePermissions.forRole(admin.roleCode()));
        if ("PARTNER".equals(admin.roleCode())) {
            data.put("invitationCode", invitationService.ensurePartnerCode(admin.id()).getCode());
        }
        return data;
    }
}
