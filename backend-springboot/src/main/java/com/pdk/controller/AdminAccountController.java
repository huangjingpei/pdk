package com.pdk.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pdk.common.api.CommonResult;
import com.pdk.common.exception.BusinessException;
import com.pdk.common.utils.PasswordHashUtils;
import com.pdk.domain.dto.AdminAccountView;
import com.pdk.domain.dto.AdminCreateAccountDTO;
import com.pdk.domain.entity.AdminUser;
import com.pdk.mapper.AdminUserMapper;
import com.pdk.security.AdminPrincipal;
import com.pdk.security.RequirePermission;
import com.pdk.security.RolePermissions;
import com.pdk.service.AdminAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/admins")
@RequiredArgsConstructor
public class AdminAccountController {
    private static final Set<String> ROLES = Set.of("SUPER_ADMIN", "PARTNER");

    private final AdminUserMapper adminUserMapper;
    private final AdminAuditService adminAuditService;
    private final com.pdk.platform.business.BusinessService businessService;

    @Value("${pdk.security.admin-password-pepper}")
    private String passwordPepper;

    @GetMapping("/list")
    @RequirePermission(RolePermissions.ADMIN_MANAGE)
    public CommonResult<List<AdminAccountView>> list() {
        List<AdminAccountView> views = adminUserMapper.selectList(null).stream()
                .map(u -> new AdminAccountView(u.getId(), u.getBizId(), u.getUsername(), u.getDisplayName(),
                        u.getRoleCode(), u.getStatus(), u.getLastLoginAt(), u.getCreatedAt()))
                .collect(Collectors.toList());
        return CommonResult.success(views);
    }

    @PostMapping
    @RequirePermission(RolePermissions.ADMIN_MANAGE)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<AdminAccountView> create(@Valid @RequestBody AdminCreateAccountDTO dto,
                                                 HttpServletRequest request) {
        if (!ROLES.contains(dto.roleCode())) {
            throw new BusinessException(40031, "后台角色只能是 SUPER_ADMIN 或 PARTNER");
        }
        if ("PARTNER".equals(dto.roleCode())) {
            if (dto.bizId() == null) throw new BusinessException(40054, "代理账号必须选择所属业务");
            businessService.requireById(dto.bizId());
        }
        if (adminUserMapper.selectCount(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, dto.username())) > 0) {
            throw new BusinessException(40010, "该登录账号已存在");
        }
        AdminUser user = new AdminUser();
        user.setUsername(dto.username());
        user.setBizId("PARTNER".equals(dto.roleCode()) ? dto.bizId() : null);
        user.setPasswordHash(PasswordHashUtils.sha256(passwordPepper, dto.password()));
        user.setDisplayName(dto.displayName());
        user.setRoleCode(dto.roleCode());
        user.setStatus("ACTIVE");
        adminUserMapper.insert(user);
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        adminAuditService.record(admin, "CREATE_ADMIN", "ADMIN", user.getUsername(), null,
                "{\"role\":\"" + user.getRoleCode() + "\"}", "超级管理员创建后台账号", request);
        return CommonResult.success(new AdminAccountView(user.getId(), user.getBizId(), user.getUsername(),
                user.getDisplayName(), user.getRoleCode(), user.getStatus(), null, user.getCreatedAt()),
                "后台账号已创建");
    }

    @PutMapping("/{id}/role")
    @RequirePermission(RolePermissions.ADMIN_MANAGE)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<String> changeRole(@PathVariable Long id, @RequestParam String role,
                                           @RequestParam(required = false) Long bizId,
                                           HttpServletRequest request) {
        if (!ROLES.contains(role)) {
            throw new BusinessException(40031, "后台角色只能是 SUPER_ADMIN 或 PARTNER");
        }
        AdminUser user = adminUserMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(40402, "管理员不存在");
        }
        AdminPrincipal me = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        if (me.id().equals(id)) {
            throw new BusinessException(40033, "不能修改自己的角色");
        }
        String before = user.getRoleCode();
        if ("PARTNER".equals(role)) {
            if (bizId == null) throw new BusinessException(40054, "代理账号必须选择所属业务");
            businessService.requireById(bizId);
            user.setBizId(bizId);
        } else {
            user.setBizId(null);
        }
        user.setRoleCode(role);
        adminUserMapper.updateById(user);
        adminAuditService.record(me, "CHANGE_ADMIN_ROLE", "ADMIN", user.getUsername(),
                "{\"role\":\"" + before + "\"}", "{\"role\":\"" + role + "\"}",
                "超级管理员调整后台账号角色", request);
        return CommonResult.success("角色已更新为 " + role);
    }

    @PutMapping("/{id}/status")
    @RequirePermission(RolePermissions.ADMIN_MANAGE)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<String> changeStatus(@PathVariable Long id, @RequestParam String status,
                                             HttpServletRequest request) {
        if (!Set.of("ACTIVE", "DISABLED").contains(status)) {
            throw new BusinessException(40032, "账号状态不合法");
        }
        AdminUser user = adminUserMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(40402, "管理员不存在");
        }
        AdminPrincipal me = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        if (me.id().equals(id)) {
            throw new BusinessException(40034, "不能禁用或冻结自己");
        }
        if ("DISABLED".equals(status) && "SUPER_ADMIN".equals(user.getRoleCode())) {
            long activeSupers = adminUserMapper.selectCount(new LambdaQueryWrapper<AdminUser>()
                    .eq(AdminUser::getRoleCode, "SUPER_ADMIN")
                    .eq(AdminUser::getStatus, "ACTIVE"));
            if (activeSupers <= 1) {
                throw new BusinessException(40035, "至少保留一个启用状态的超级管理员");
            }
        }
        String before = user.getStatus();
        user.setStatus(status);
        adminUserMapper.updateById(user);
        adminAuditService.record(me, "CHANGE_ADMIN_STATUS", "ADMIN", user.getUsername(),
                "{\"status\":\"" + before + "\"}", "{\"status\":\"" + status + "\"}",
                "超级管理员启用/禁用后台账号", request);
        return CommonResult.success("状态已更新为 " + status);
    }
}
