package com.pdk.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pdk.common.api.CommonResult;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.entity.User;
import com.pdk.mapper.UserMapper;
import com.pdk.mapper.UserCredentialMapper;
import com.pdk.domain.entity.UserCredential;
import com.pdk.security.RequirePermission;
import com.pdk.security.RolePermissions;
import com.pdk.service.DeviceBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.pdk.security.AdminPrincipal;
import com.pdk.service.AdminAuditService;
import com.pdk.service.InvitationService;
import com.pdk.mapper.InvitationCodeMapper;
import com.pdk.mapper.UserReferralMapper;
import com.pdk.domain.entity.InvitationCode;
import com.pdk.domain.entity.UserReferral;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1/admin/user")
@RequiredArgsConstructor
public class AdminUserController {
    private final UserMapper userMapper;
    private final DeviceBindingService deviceBindingService;
    private final AdminAuditService adminAuditService;
    private final UserCredentialMapper credentialMapper;
    private final InvitationCodeMapper invitationCodeMapper;
    private final UserReferralMapper referralMapper;
    private final InvitationService invitationService;

    @GetMapping("/list")
    @RequirePermission(RolePermissions.USER_VIEW)
    public CommonResult<Page<User>> list(@RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          @RequestParam(required = false) String phone) {
        LambdaQueryWrapper<User> query = new LambdaQueryWrapper<>();
        if (phone != null && !phone.isBlank()) {
            query.like(User::getPhone, phone);
        }
        query.orderByDesc(User::getCreatedAt);
        Page<User> result = userMapper.selectPage(new Page<>(page, Math.min(size, 100)), query);
        result.getRecords().forEach(user -> {
            UserCredential credential = credentialMapper.selectOne(new LambdaQueryWrapper<UserCredential>()
                    .eq(UserCredential::getUserId, user.getId()));
            user.setRoleCode(credential == null ? "LEGACY" : credential.getRoleCode());
            InvitationCode ownCode = invitationCodeMapper.selectOne(new LambdaQueryWrapper<InvitationCode>()
                    .eq(InvitationCode::getOwnerUserId, user.getId()));
            user.setInvitationCode(ownCode == null ? null : ownCode.getCode());
            UserReferral referral = referralMapper.selectOne(new LambdaQueryWrapper<UserReferral>()
                    .eq(UserReferral::getUserId, user.getId()));
            if (referral != null) {
                User inviter = userMapper.selectById(referral.getPartnerUserId());
                user.setInvitedByPhone(inviter == null ? null : inviter.getPhone());
            }
        });
        return CommonResult.success(result);
    }

    @PostMapping("/{id}/unbind-device")
    @RequirePermission(RolePermissions.USER_UNBIND)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<String> unbind(@PathVariable Long id, HttpServletRequest request) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(40402, "客户端用户不存在");
        }
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getDeviceId, null));
        deviceBindingService.unbind(user.getPhone());
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        adminAuditService.record(admin, "UNBIND_DEVICE", "USER", user.getPhone(),
                "{\"deviceId\":\"" + user.getDeviceId() + "\"}", "{\"deviceId\":null}",
                "管理员强制解绑电脑", request);
        return CommonResult.success("已由管理员解除电脑绑定");
    }

    @PutMapping("/{id}/role")
    @RequirePermission(RolePermissions.PARTNER_MANAGE)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<String> changeRole(@PathVariable Long id, @RequestParam String role,
                                            HttpServletRequest request) {
        if (!java.util.Set.of("CUSTOMER", "PARTNER").contains(role)) {
            throw new BusinessException(40031, "用户身份只能是 CUSTOMER 或 PARTNER");
        }
        User user = userMapper.selectById(id);
        UserCredential credential = credentialMapper.selectOne(new LambdaQueryWrapper<UserCredential>()
                .eq(UserCredential::getUserId, id));
        if (user == null || credential == null) throw new BusinessException(40402, "用户不存在或尚未完成手机注册");
        String before = credential.getRoleCode();
        credential.setRoleCode(role);
        credentialMapper.updateById(credential);
        if ("PARTNER".equals(role)) invitationService.ensurePartnerCode(id);
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        adminAuditService.record(admin, "CHANGE_USER_ROLE", "USER", user.getPhone(),
                "{\"role\":\"" + before + "\"}", "{\"role\":\"" + role + "\"}",
                "超级管理员调整客户/代理身份", request);
        return CommonResult.success("用户身份已更新为 " + role);
    }

    @PutMapping("/{id}/status")
    @RequirePermission(RolePermissions.PARTNER_MANAGE)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<String> changeStatus(@PathVariable Long id, @RequestParam String status,
                                              HttpServletRequest request) {
        if (!java.util.Set.of("ACTIVE", "FROZEN").contains(status)) throw new BusinessException(40032, "用户状态不合法");
        User user = userMapper.selectById(id);
        if (user == null) throw new BusinessException(40402, "用户不存在");
        String before = user.getStatus();
        user.setStatus(status);
        userMapper.updateById(user);
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        adminAuditService.record(admin, "CHANGE_USER_STATUS", "USER", user.getPhone(),
                "{\"status\":\"" + before + "\"}", "{\"status\":\"" + status + "\"}",
                "超级管理员调整用户状态", request);
        return CommonResult.success("用户状态已更新");
    }
}
