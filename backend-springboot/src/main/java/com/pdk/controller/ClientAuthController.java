package com.pdk.controller;

import cn.dev33.satoken.stp.StpLogic;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pdk.common.api.CommonResult;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.ClientLoginDTO;
import com.pdk.domain.dto.ClientRegisterDTO;
import com.pdk.domain.dto.SendSmsDTO;
import com.pdk.domain.dto.ChangePasswordDTO;
import com.pdk.domain.dto.ClientResetPasswordDTO;
import com.pdk.domain.entity.User;
import com.pdk.domain.entity.UserCredential;
import com.pdk.mapper.UserMapper;
import com.pdk.mapper.UserCredentialMapper;
import com.pdk.platform.business.BusinessRequestResolver;
import com.pdk.platform.business.BusinessContext;
import com.pdk.service.DeviceBindingService;
import com.pdk.service.LoginLogService;
import com.pdk.service.SmsCodeService;
import com.pdk.service.AccountAssignmentService;
import com.pdk.service.InvitationService;
import com.pdk.service.DeviceLicenseService;
import com.pdk.service.ClientLicenseContext;
import com.pdk.domain.entity.InvitationCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/client/auth")
@RequiredArgsConstructor
public class ClientAuthController {
    private final UserMapper userMapper;
    private final UserCredentialMapper credentialMapper;
    private final DeviceBindingService deviceBindingService;
    private final SmsCodeService smsCodeService;
    private final PasswordEncoder passwordEncoder;
    private final AccountAssignmentService assignmentService;
    private final InvitationService invitationService;
    private final BusinessRequestResolver businessRequestResolver;
    private final LoginLogService loginLogService;
    private final DeviceLicenseService deviceLicenseService;
    @Qualifier("clientStpLogic")
    private final StpLogic clientStpLogic;

    @PostMapping("/sms/send")
    public CommonResult<Map<String, Object>> sendSms(@Valid @RequestBody SendSmsDTO dto,
                                                      HttpServletRequest request) {
        BusinessContext business = businessRequestResolver.resolveContextAndBind(request, dto.getAppId());
        if ("REGISTER".equals(dto.getPurpose()) && !"SELF_SERVICE".equals(business.registrationMode())) {
            throw new BusinessException(40322, "当前业务不开放自助注册，请使用管理员提供的账号登录");
        }
        String debugCode = smsCodeService.send(business.bizId(), dto.getPhone(), dto.getPurpose());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appId", business.appId());
        data.put("bizCode", business.bizCode());
        data.put("expireMinutes", 5);
        if (debugCode != null) {
            data.put("debugCode", debugCode);
        }
        return CommonResult.success(data, "验证码已发送");
    }

    @PostMapping("/register")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public CommonResult<Map<String, Object>> register(@Valid @RequestBody ClientRegisterDTO dto,
                                                       HttpServletRequest request) {
        BusinessContext business = businessRequestResolver.resolveContextAndBind(request, dto.getAppId());
        if (!"SELF_SERVICE".equals(business.registrationMode())) {
            throw new BusinessException(40322, "当前业务不开放自助注册，请使用管理员提供的账号登录");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getBizId, business.bizId()).eq(User::getPhone, dto.getPhone())) > 0) {
            throw new BusinessException(40010, "该手机号已经注册");
        }
        smsCodeService.verify(business.bizId(), dto.getPhone(), "REGISTER", dto.getSmsCode());
        InvitationCode invitation = invitationService.findUsable(business.bizId(), dto.getInvitationCode());
        User user = new User();
        user.setBizId(business.bizId());
        user.setPhone(dto.getPhone());
        user.setAccountSource("SELF_REGISTER");
        user.setStatus(business.trialEnabled() ? "TRIAL" : "ACTIVE");
        user.setDeviceId(dto.getDeviceId());
        user.setCurrentPackageId(0);
        user.setCurrentPackageName(business.trialEnabled() ? "新用户免费试用" : "未开通套餐");
        user.setExpireTime(business.trialEnabled()
                ? java.time.LocalDateTime.now().plusHours(business.trialDurationHours()) : null);
        int trialCalls = business.trialEnabled()
                ? business.trialAccountCount() * business.trialCallsPerAccount() : 0;
        user.setRemainingCalls(trialCalls);
        user.setDailyCallsLimit(trialCalls);
        user.setMaxAccounts(business.trialEnabled() ? business.trialAccountCount() : 1);
        user.setIsTrialClaimed(business.trialEnabled() ? 1 : 0);
        userMapper.insert(user);

        UserCredential credential = new UserCredential();
        credential.setUserId(user.getId());
        credential.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        credential.setRoleCode("CUSTOMER");
        credential.setStatus("ACTIVE");
        credential.setMustChangePassword(0);
        credentialMapper.insert(credential);
        invitationService.bind(business.bizId(), user.getId(), invitation);
        boolean resourceAllocated = !business.trialEnabled() || assignmentService.allocateTrial(user,
                business.trialAccountCount(), business.trialCallsPerAccount());
        Map<String, Object> result;
        if (business.usesDeviceLicense()) {
            result = new LinkedHashMap<>();
            result.put("bizId", business.bizId()); result.put("appId", business.appId());
            result.put("bizCode", business.bizCode()); result.put("phone", user.getPhone());
            result.put("authorizationMode", business.authorizationMode()); result.put("licenseRequired", true);
        } else {
            clientStpLogic.login(user.getId());
            deviceBindingService.bind(user.getBizId(), user.getId(), user.getDeviceId());
            result = payload(user, credential, business);
        }
        result.put("resourceAllocated", resourceAllocated);
        result.put("resourceMessage", resourceAllocated
                ? "试用小号已分配"
                : "注册和试用权益已开通，当前小号库存不足，请联系平台补充后再领取资源");
        result.put("invitedByPartner", invitation == null ? null : invitation.getOwnerUserId());
        return CommonResult.success(result, business.usesDeviceLicense()
                ? "注册成功；当前电脑需要使用分配给您的卡密登录并绑定许可证"
                : resourceAllocated
                ? "注册成功，免费试用已开通"
                : "注册成功，免费试用已开通；小号资源等待平台补充");
    }

    @PostMapping("/login")
    public CommonResult<Map<String, Object>> login(@Valid @RequestBody ClientLoginDTO dto,
                                                    HttpServletRequest request) {
        BusinessContext business = businessRequestResolver.resolveContextAndBind(request, dto.getAppId());
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getBizId, business.bizId()).eq(User::getPhone, dto.getPhone()));
        if (user == null) {
            loginLogService.recordClientFailure(business.bizId(), null, dto.getPhone(),
                    "账号不存在", dto.getDeviceId(), request);
            throw new BusinessException(40100, "账号不存在，请先领取试用或激活卡密");
        }
        if ("FROZEN".equals(user.getStatus())) {
            loginLogService.recordClientFailure(business.bizId(), user.getId(), dto.getPhone(),
                    "账号已被冻结", dto.getDeviceId(), request);
            throw new BusinessException(40104, "账号已被冻结，请联系管理员");
        }
        UserCredential credential = credentialMapper.selectOne(new LambdaQueryWrapper<UserCredential>()
                .eq(UserCredential::getUserId, user.getId()));
        if (credential == null || !"ACTIVE".equals(credential.getStatus())
                || !passwordEncoder.matches(dto.getPassword(), credential.getPasswordHash())) {
            loginLogService.recordClientFailure(business.bizId(), user.getId(), dto.getPhone(),
                    "手机号或密码错误", dto.getDeviceId(), request);
            throw new BusinessException(40105, "手机号或密码错误");
        }
        ClientLicenseContext licenseContext = null;
        if (business.usesDeviceLicense()) {
            try {
                licenseContext = deviceLicenseService.authenticateAndBind(business, user, dto);
            } catch (BusinessException e) {
                loginLogService.recordClientFailure(business.bizId(), user.getId(), dto.getPhone(),
                        "许可证登录失败[" + e.getCode() + "]: " + e.getMessage(), dto.getDeviceId(), request);
                throw e;
            }
        } else if (user.getDeviceId() != null && !user.getDeviceId().equals(dto.getDeviceId())) {
            loginLogService.recordClientFailure(business.bizId(), user.getId(), dto.getPhone(),
                    "设备不匹配，账号已绑定其他电脑", dto.getDeviceId(), request);
            throw new BusinessException(40103, "账号已绑定其他电脑，请在原电脑解绑或联系管理员");
        }
        if (!business.usesDeviceLicense() && user.getDeviceId() == null) {
            user.setDeviceId(dto.getDeviceId());
            userMapper.updateById(user);
        }

        clientStpLogic.login(licenseContext == null ? user.getId() : licenseContext.loginId());
        if (!business.usesDeviceLicense()) deviceBindingService.bind(user.getBizId(), user.getId(), dto.getDeviceId());
        loginLogService.recordClientSuccess(business.bizId(), user.getId(), user.getPhone(),
                dto.getDeviceId(), licenseContext, request);
        Map<String, Object> result = payload(user, credential, business);
        if (licenseContext != null) result.put("deviceLicense", deviceLicenseService.view(licenseContext.license()));
        return CommonResult.success(result, "客户端登录成功");
    }

    @PostMapping("/change-password")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public CommonResult<String> changePassword(@Valid @RequestBody ChangePasswordDTO dto,
                                                HttpServletRequest request) {
        BusinessContext business = businessRequestResolver.resolveContextAndBind(request, dto.getAppId());
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getBizId, business.bizId()).eq(User::getPhone, dto.getPhone()));
        if (user == null) throw new BusinessException(40105, "手机号或旧密码错误");
        UserCredential credential = credentialMapper.selectOne(new LambdaQueryWrapper<UserCredential>()
                .eq(UserCredential::getUserId, user.getId()));
        if (credential == null || !passwordEncoder.matches(dto.getOldPassword(), credential.getPasswordHash())) {
            throw new BusinessException(40105, "手机号或旧密码错误");
        }
        if (passwordEncoder.matches(dto.getNewPassword(), credential.getPasswordHash())) {
            throw new BusinessException(40019, "新密码不能与旧密码相同");
        }
        credential.setPasswordHash(passwordEncoder.encode(dto.getNewPassword()));
        credential.setMustChangePassword(0);
        credentialMapper.updateById(credential);
        clientStpLogic.kickout(user.getId());
        deviceLicenseService.kickUserLicenses(user.getBizId(), user.getId(), "PASSWORD_CHANGED");
        loginLogService.record(business.bizId(), "CLIENT", user.getId(), user.getPhone(),
                "PASSWORD_RESET", "SUCCESS", "用户自助修改密码", null, request);
        return CommonResult.success("密码修改成功，请使用新密码登录");
    }

    @PostMapping("/reset-password")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public CommonResult<String> resetPassword(@Valid @RequestBody ClientResetPasswordDTO dto,
                                               HttpServletRequest request) {
        BusinessContext business = businessRequestResolver.resolveContextAndBind(request, dto.getAppId());
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getBizId, business.bizId()).eq(User::getPhone, dto.getPhone()));
        if (user == null) {
            throw new BusinessException(40100, "账号不存在，请先领取试用或激活卡密");
        }
        // 复用既有短信验证码设施（按 bizId + phone + RESET_PASSWORD 隔离与频控）
        smsCodeService.verify(business.bizId(), dto.getPhone(), "RESET_PASSWORD", dto.getSmsCode());
        UserCredential credential = credentialMapper.selectOne(new LambdaQueryWrapper<UserCredential>()
                .eq(UserCredential::getUserId, user.getId()));
        if (credential == null) {
            throw new BusinessException(40402, "用户凭证不存在，请联系管理员");
        }
        if (passwordEncoder.matches(dto.getNewPassword(), credential.getPasswordHash())) {
            throw new BusinessException(40019, "新密码不能与旧密码相同");
        }
        credential.setPasswordHash(passwordEncoder.encode(dto.getNewPassword()));
        // 自助找回后不强制改密（用户本人刚设置了新密码）
        credential.setMustChangePassword(0);
        credentialMapper.updateById(credential);
        // 重置成功后吊销该用户全部在线会话，强制用新密码重新登录，避免旧会话残留
        clientStpLogic.kickout(user.getId());
        deviceLicenseService.kickUserLicenses(user.getBizId(), user.getId(), "PASSWORD_RESET");
        loginLogService.record(business.bizId(), "CLIENT", user.getId(), user.getPhone(),
                "PASSWORD_RESET", "SUCCESS", "短信验证码自助找回密码", null, request);
        return CommonResult.success("密码已重置，请使用新密码登录");
    }

    @PostMapping("/logout")
    public CommonResult<String> logout(HttpServletRequest request) {
        User user = (User) request.getAttribute("pdkClientUser");
        if (request.getAttribute("pdkClientLicense") == null) deviceBindingService.unbind(user.getBizId(), user.getId());
        clientStpLogic.logout();
        return CommonResult.success("已注销当前会话");
    }

    @PostMapping("/unbind-device")
    public CommonResult<String> unbindDevice(HttpServletRequest request) {
        User user = (User) request.getAttribute("pdkClientUser");
        Object licenseValue = request.getAttribute("pdkClientLicense");
        if (licenseValue instanceof com.pdk.domain.entity.DeviceLicense license) {
            var device = (com.pdk.domain.entity.UserDevice) request.getAttribute("pdkClientDevice");
            ClientLicenseContext context = new ClientLicenseContext(license, device);
            deviceLicenseService.unbind(license.getId(), "CLIENT_UNBIND");
            loginLogService.recordLicenseAction(user.getBizId(), user.getId(), user.getPhone(),
                    "DEVICE_UNBIND", context, "客户端主动解绑", request);
            return CommonResult.success("当前许可证已解绑，可在新电脑输入原卡密继续使用；有效期不会暂停");
        }
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<User>()
                .eq("id", user.getId())
                .eq("biz_id", user.getBizId())
                .set("device_id", null));
        deviceBindingService.unbind(user.getBizId(), user.getId());
        clientStpLogic.logout();
        return CommonResult.success("电脑已解绑，可在新电脑使用账号密码重新登录并绑定");
    }

    private Map<String, Object> payload(User user, UserCredential credential, BusinessContext business) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bizId", business.bizId());
        data.put("appId", business.appId());
        data.put("bizCode", business.bizCode());
        data.put("businessName", business.businessName());
        data.put("businessDescription", business.businessDescription());
        data.put("authorizationMode", business.authorizationMode());
        data.put("tokenName", clientStpLogic.getTokenName());
        data.put("tokenValue", clientStpLogic.getTokenValue());
        data.put("phone", user.getPhone());
        data.put("deviceId", user.getDeviceId());
        data.put("status", user.getStatus());
        data.put("packageName", user.getCurrentPackageName());
        data.put("expireTime", user.getExpireTime());
        data.put("remainingCalls", user.getRemainingCalls());
        data.put("maxAccounts", user.getMaxAccounts());
        data.put("role", credential.getRoleCode());
        data.put("mustChangePassword", Integer.valueOf(1).equals(credential.getMustChangePassword()));
        return data;
    }
}
