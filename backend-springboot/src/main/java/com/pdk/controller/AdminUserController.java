package com.pdk.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pdk.common.api.CommonResult;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.AdminAdjustUserDTO;
import com.pdk.domain.dto.AdminCreateUserDTO;
import com.pdk.domain.dto.AdminResetPasswordDTO;
import com.pdk.domain.dto.UserPasswordPolicyDTO;
import com.pdk.domain.dto.UserAssignmentDetail;
import com.pdk.domain.entity.InvitationCode;
import com.pdk.domain.entity.PackagePlan;
import com.pdk.domain.entity.User;
import com.pdk.domain.entity.UserCredential;
import com.pdk.domain.entity.UserReferral;
import com.pdk.mapper.InvitationCodeMapper;
import com.pdk.mapper.PackagePlanMapper;
import com.pdk.mapper.UserCredentialMapper;
import com.pdk.mapper.UserMapper;
import com.pdk.mapper.UserReferralMapper;
import com.pdk.security.AdminPrincipal;
import com.pdk.security.RequirePermission;
import com.pdk.security.RolePermissions;
import com.pdk.service.AccountAssignmentService;
import com.pdk.service.AdminAuditService;
import com.pdk.service.DeviceBindingService;
import com.pdk.service.InvitationService;
import com.pdk.platform.business.BusinessContext;
import com.pdk.platform.business.BusinessService;
import cn.dev33.satoken.stp.StpLogic;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/admin/user")
@RequiredArgsConstructor
public class AdminUserController {
    private final UserMapper userMapper;
    private final UserCredentialMapper credentialMapper;
    private final PackagePlanMapper packagePlanMapper;
    private final DeviceBindingService deviceBindingService;
    private final AccountAssignmentService assignmentService;
    private final AdminAuditService adminAuditService;
    private final InvitationService invitationService;
    private final InvitationCodeMapper invitationCodeMapper;
    private final UserReferralMapper referralMapper;
    private final PasswordEncoder passwordEncoder;
    private final BusinessService businessService;
    private final com.pdk.security.AdminBusinessScope businessScope;
    @Qualifier("clientStpLogic")
    private final StpLogic clientStpLogic;

    @GetMapping("/list")
    @RequirePermission(RolePermissions.USER_VIEW)
    public CommonResult<Page<User>> list(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) Long bizId,
                                         @RequestParam(required = false) Long appId,
                                         HttpServletRequest request) {
        LambdaQueryWrapper<User> query = new LambdaQueryWrapper<>();
        if (appId != null) bizId = businessService.requireByAppId(appId).getId();
        bizId = businessScope.enforce(principal(request), bizId);
        if (bizId != null) query.eq(User::getBizId, bizId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> w.like(User::getPhone, kw)
                    .or().like(User::getDeviceId, kw)
                    .or().like(User::getCurrentPackageName, kw));
        }
        if (status != null && !status.isBlank()) {
            query.eq(User::getStatus, status);
        }
        query.orderByDesc(User::getCreatedAt);
        Page<User> result = userMapper.selectPage(new Page<>(page, Math.min(size, 100)), query);
        result.getRecords().forEach(user -> {
            var business = businessService.requireById(user.getBizId());
            user.setAppId(business.getAppId());
            user.setBusinessName(business.getBizName());
            user.setBusinessDescription(business.getDescription());
            UserCredential credential = credentialMapper.selectOne(new LambdaQueryWrapper<UserCredential>()
                    .eq(UserCredential::getUserId, user.getId()));
            user.setRoleCode(credential == null ? "LEGACY" : credential.getRoleCode());
            user.setMustChangePassword(credential != null && Integer.valueOf(1).equals(credential.getMustChangePassword()));
            InvitationCode ownCode = invitationCodeMapper.selectOne(new LambdaQueryWrapper<InvitationCode>()
                    .eq(InvitationCode::getBizId, user.getBizId())
                    .eq(InvitationCode::getOwnerUserId, user.getId()));
            user.setInvitationCode(ownCode == null ? null : ownCode.getCode());
            UserReferral referral = referralMapper.selectOne(new LambdaQueryWrapper<UserReferral>()
                    .eq(UserReferral::getBizId, user.getBizId())
                    .eq(UserReferral::getUserId, user.getId()));
            if (referral != null) {
                User inviter = userMapper.selectById(referral.getPartnerUserId());
                user.setInvitedByPhone(inviter == null ? null : inviter.getPhone());
            }
        });
        return CommonResult.success(result);
    }

    @GetMapping("/{id}/assignments")
    @RequirePermission(RolePermissions.USER_VIEW)
    public CommonResult<UserAssignmentDetail> assignments(@PathVariable Long id, HttpServletRequest request) {
        requireScopedUser(id, request);
        return CommonResult.success(assignmentService.detailByUser(id));
    }

    @PostMapping
    @RequirePermission(RolePermissions.USER_EDIT)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<User> create(@Valid @RequestBody AdminCreateUserDTO dto, HttpServletRequest request) {
        BusinessContext business = businessService.requireAvailableByAppId(dto.getAppId());
        businessScope.enforce(principal(request), business.bizId());
        if (userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getBizId, business.bizId()).eq(User::getPhone, dto.getPhone())) > 0) {
            throw new BusinessException(40010, "该手机号已存在");
        }
        User user = new User();
        user.setBizId(business.bizId());
        user.setPhone(dto.getPhone());
        user.setAccountSource("ADMIN_CREATED");
        user.setStatus("ACTIVE");
        user.setDeviceId(dto.getDeviceId());
        user.setCurrentPackageId(0);
        user.setCurrentPackageName("未开通套餐");
        user.setExpireTime(null);
        user.setRemainingCalls(0);
        user.setDailyCallsLimit(0);
        user.setMaxAccounts(1);
        user.setIsTrialClaimed(0);
        userMapper.insert(user);

        UserCredential credential = new UserCredential();
        credential.setUserId(user.getId());
        credential.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        credential.setRoleCode("CUSTOMER");
        credential.setStatus("ACTIVE");
        credential.setMustChangePassword(business.forceInitialPasswordChange() ? 1 : 0);
        credentialMapper.insert(credential);

        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        adminAuditService.record(admin, user.getBizId(), "CREATE_USER", "USER", user.getPhone(), null,
                "{\"phone\":\"" + user.getPhone() + "\"}", "管理员手工创建客户账号", request);
        user.setRoleCode("CUSTOMER");
        return CommonResult.success(user, "客户账号已创建");
    }

    @PutMapping("/{id}/adjust")
    @RequirePermission(RolePermissions.USER_EDIT)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<User> adjust(@PathVariable Long id, @Valid @RequestBody AdminAdjustUserDTO dto,
                                     HttpServletRequest request) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(40402, "客户端用户不存在");
        }
        businessScope.enforce(principal(request), user.getBizId());
        String before = snapshot(user);

        Integer pkgId = dto.getPackagePlanId();
        if (pkgId != null) {
            PackagePlan plan = packagePlanMapper.selectById(pkgId);
            if (plan == null || !user.getBizId().equals(plan.getBizId()) || !"ACTIVE".equals(plan.getStatus())) {
                throw new BusinessException(40420, "套餐不存在或已停用");
            }
            user.setCurrentPackageId(plan.getId());
            user.setCurrentPackageName(plan.getName());
            int calls = plan.getAccountCount() * plan.getCallsPerAccount();
            user.setRemainingCalls((user.getRemainingCalls() == null ? 0 : user.getRemainingCalls()) + calls);
            user.setDailyCallsLimit(Math.max(user.getDailyCallsLimit() == null ? 0 : user.getDailyCallsLimit(), calls));
            user.setMaxAccounts(Math.max(user.getMaxAccounts() == null ? 1 : user.getMaxAccounts(), plan.getAccountCount()));
            LocalDateTime base = (user.getExpireTime() != null && user.getExpireTime().isAfter(LocalDateTime.now()))
                    ? user.getExpireTime() : LocalDateTime.now();
            user.setExpireTime(base.plusHours(plan.getDurationHours()));
        }

        int extra = dto.getExtraCalls() == null ? 0 : dto.getExtraCalls();
        if (extra != 0) {
            // 「补次数」改为调整该用户小号槽位的 allocated_calls（而非直接改用户总池），
            // 保持用户总池始终由 assignment 槽位派生；无 assignment 时回退为直接调整用户级总池
            boolean adjustedByAssignment = assignmentService.adjustAllocatedCalls(id, extra);
            if (!adjustedByAssignment) {
                userMapper.update(null, new LambdaUpdateWrapper<User>()
                        .eq(User::getId, id)
                        .setSql("remaining_calls = GREATEST(0, remaining_calls + " + extra + ")"));
            }
        }

        int days = dto.getExtendDays() == null ? 0 : dto.getExtendDays();
        if (days > 0) {
            LocalDateTime base = (user.getExpireTime() != null) ? user.getExpireTime() : LocalDateTime.now();
            user.setExpireTime(base.plusDays(days));
        }

        // remaining_calls 一律由 assignment 槽位派生/单独 setSql 处理，updateById 不覆盖它
        user.setRemainingCalls(null);
        userMapper.updateById(user);
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        adminAuditService.record(admin, user.getBizId(), "MANUAL_ADJUST_USER", "USER", user.getPhone(), before,
                snapshot(user), "管理员手动调整套餐/次数/期限", request);
        return CommonResult.success(user, "用户权益已调整");
    }

    @PostMapping("/{id}/unbind-device")
    @RequirePermission(RolePermissions.USER_UNBIND)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<String> unbind(@PathVariable Long id, HttpServletRequest request) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(40402, "客户端用户不存在");
        }
        businessScope.enforce(principal(request), user.getBizId());
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getDeviceId, null));
        deviceBindingService.unbind(user.getBizId(), user.getId());
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        adminAuditService.record(admin, user.getBizId(), "UNBIND_DEVICE", "USER", user.getPhone(),
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
        businessScope.enforce(principal(request), user.getBizId());
        String before = credential.getRoleCode();
        credential.setRoleCode(role);
        credentialMapper.updateById(credential);
        if ("PARTNER".equals(role)) invitationService.ensurePartnerCode(user.getBizId(), id);
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        adminAuditService.record(admin, user.getBizId(), "CHANGE_USER_ROLE", "USER", user.getPhone(),
                "{\"role\":\"" + before + "\"}", "{\"role\":\"" + role + "\"}",
                "超级管理员调整客户/代理身份", request);
        return CommonResult.success("用户身份已更新为 " + role);
    }

    @PutMapping("/{id}/status")
    @RequirePermission(RolePermissions.USER_EDIT)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<String> changeStatus(@PathVariable Long id, @RequestParam String status,
                                              HttpServletRequest request) {
        if (!java.util.Set.of("ACTIVE", "FROZEN").contains(status)) throw new BusinessException(40032, "用户状态不合法");
        User user = userMapper.selectById(id);
        if (user == null) throw new BusinessException(40402, "用户不存在");
        businessScope.enforce(principal(request), user.getBizId());
        String before = user.getStatus();
        user.setStatus(status);
        userMapper.updateById(user);
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        adminAuditService.record(admin, user.getBizId(), "CHANGE_USER_STATUS", "USER", user.getPhone(),
                "{\"status\":\"" + before + "\"}", "{\"status\":\"" + status + "\"}",
                "超级管理员调整用户状态（冻结/解冻）", request);
        return CommonResult.success("用户状态已更新为 " + status);
    }

    @PostMapping("/{id}/reset-password")
    @RequirePermission(RolePermissions.USER_PASSWORD_RESET)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<String> resetPassword(@PathVariable Long id,
                                              @Valid @RequestBody AdminResetPasswordDTO dto,
                                              HttpServletRequest request) {
        User user = requireScopedUser(id, request);
        UserCredential credential = credentialMapper.selectOne(new LambdaQueryWrapper<UserCredential>()
                .eq(UserCredential::getUserId, id));
        if (credential == null) throw new BusinessException(40402, "用户凭证不存在，请联系管理员");
        String before = snapshotCredential(credential);
        if (passwordEncoder.matches(dto.getNewPassword(), credential.getPasswordHash())) {
            throw new BusinessException(40019, "新密码不能与旧密码相同");
        }
        // 管理员代重置：强制用户下次登录改密（管理员知道临时密码，应当使其尽快失效）
        credential.setPasswordHash(passwordEncoder.encode(dto.getNewPassword()));
        credential.setMustChangePassword(1);
        credentialMapper.updateById(credential);
        // 吊销全部在线会话，强制用新密码重新登录
        clientStpLogic.kickout(user.getId());
        AdminPrincipal admin = principal(request);
        adminAuditService.record(admin, user.getBizId(), "RESET_USER_PASSWORD", "USER", user.getPhone(),
                before, snapshotCredential(credential), "管理员重置用户密码并强制改密", request);
        return CommonResult.success("密码已重置，用户需在下次登录时修改密码");
    }

    @PutMapping("/{id}/password-policy")
    @RequirePermission(RolePermissions.USER_PASSWORD_RESET)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<String> passwordPolicy(@PathVariable Long id,
                                               @Valid @RequestBody UserPasswordPolicyDTO dto,
                                               HttpServletRequest request) {
        User user = requireScopedUser(id, request);
        UserCredential credential = credentialMapper.selectOne(new LambdaQueryWrapper<UserCredential>()
                .eq(UserCredential::getUserId, id));
        if (credential == null) throw new BusinessException(40402, "用户凭证不存在，请联系管理员");
        String before = snapshotCredential(credential);
        credential.setMustChangePassword(dto.isMustChange() ? 1 : 0);
        credentialMapper.updateById(credential);
        // 开启强制改密时吊销在线会话，使其下次登录即触发改密；取消强制则不动会话
        if (dto.isMustChange()) {
            clientStpLogic.kickout(user.getId());
        }
        AdminPrincipal admin = principal(request);
        adminAuditService.record(admin, user.getBizId(),
                dto.isMustChange() ? "FORCE_USER_CHANGE_PASSWORD" : "CANCEL_FORCE_USER_CHANGE_PASSWORD",
                "USER", user.getPhone(), before, snapshotCredential(credential),
                dto.isMustChange() ? "管理员强制用户下次登录改密" : "管理员取消强制改密", request);
        return CommonResult.success(dto.isMustChange() ? "已强制该用户下次登录时修改密码" : "已取消强制改密");
    }

    private String snapshot(User u) {
        return "{\"status\":\"" + (u.getStatus() == null ? "" : u.getStatus())
                + "\",\"packageId\":" + (u.getCurrentPackageId() == null ? 0 : u.getCurrentPackageId())
                + ",\"packageName\":\"" + (u.getCurrentPackageName() == null ? "" : u.getCurrentPackageName())
                + "\",\"remainingCalls\":" + (u.getRemainingCalls() == null ? 0 : u.getRemainingCalls())
                + ",\"expireTime\":\"" + (u.getExpireTime() == null ? "" : u.getExpireTime().toString()) + "\"}";
    }

    private AdminPrincipal principal(HttpServletRequest request) {
        return (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
    }

    private String snapshotCredential(UserCredential c) {
        return "{\"mustChangePassword\":" + (c.getMustChangePassword() == null ? 0 : c.getMustChangePassword())
                + ",\"status\":\"" + (c.getStatus() == null ? "" : c.getStatus()) + "\"}";
    }

    private User requireScopedUser(Long id, HttpServletRequest request) {
        User user = userMapper.selectById(id);
        if (user == null) throw new BusinessException(40402, "客户端用户不存在");
        businessScope.enforce(principal(request), user.getBizId());
        return user;
    }
}
