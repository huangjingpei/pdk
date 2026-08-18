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
import com.pdk.domain.entity.User;
import com.pdk.domain.entity.UserCredential;
import com.pdk.mapper.UserMapper;
import com.pdk.mapper.UserCredentialMapper;
import com.pdk.service.DeviceBindingService;
import com.pdk.service.SmsCodeService;
import com.pdk.service.AccountAssignmentService;
import com.pdk.service.InvitationService;
import com.pdk.domain.entity.InvitationCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
    @Qualifier("clientStpLogic")
    private final StpLogic clientStpLogic;

    @Value("${pdk.trial.duration-hours:24}") private int trialDurationHours;
    @Value("${pdk.trial.account-count:1}") private int trialAccountCount;
    @Value("${pdk.trial.calls-per-account:20}") private int trialCallsPerAccount;

    @PostMapping("/sms/send")
    public CommonResult<Map<String, Object>> sendSms(@Valid @RequestBody SendSmsDTO dto) {
        String debugCode = smsCodeService.send(dto.getPhone(), dto.getPurpose());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("expireMinutes", 5);
        if (debugCode != null) {
            data.put("debugCode", debugCode);
        }
        return CommonResult.success(data, "验证码已发送");
    }

    @PostMapping("/register")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public CommonResult<Map<String, Object>> register(@Valid @RequestBody ClientRegisterDTO dto) {
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getPhone, dto.getPhone())) > 0) {
            throw new BusinessException(40010, "该手机号已经注册");
        }
        smsCodeService.verify(dto.getPhone(), "REGISTER", dto.getSmsCode());
        InvitationCode invitation = invitationService.findUsable(dto.getInvitationCode());
        User user = new User();
        user.setPhone(dto.getPhone());
        user.setStatus("TRIAL");
        user.setDeviceId(dto.getDeviceId());
        user.setCurrentPackageId(0);
        user.setCurrentPackageName("新用户免费试用");
        user.setExpireTime(java.time.LocalDateTime.now().plusHours(trialDurationHours));
        int trialCalls = trialAccountCount * trialCallsPerAccount;
        user.setRemainingCalls(trialCalls);
        user.setDailyCallsLimit(trialCalls);
        user.setMaxAccounts(trialAccountCount);
        user.setIsTrialClaimed(1);
        userMapper.insert(user);

        UserCredential credential = new UserCredential();
        credential.setUserId(user.getId());
        credential.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        credential.setRoleCode("CUSTOMER");
        credential.setStatus("ACTIVE");
        credentialMapper.insert(credential);
        invitationService.bind(user.getId(), invitation);
        boolean resourceAllocated = assignmentService.allocateTrial(user, trialAccountCount, trialCallsPerAccount);
        clientStpLogic.login(user.getId());
        deviceBindingService.bind(user.getPhone(), user.getDeviceId());
        Map<String, Object> result = payload(user, credential);
        result.put("resourceAllocated", resourceAllocated);
        result.put("resourceMessage", resourceAllocated
                ? "试用小号已分配"
                : "注册和试用权益已开通，当前小号库存不足，请联系平台补充后再领取资源");
        result.put("invitedByPartner", invitation == null ? null : invitation.getOwnerUserId());
        return CommonResult.success(result, resourceAllocated
                ? "注册成功，免费试用已开通"
                : "注册成功，免费试用已开通；小号资源等待平台补充");
    }

    @PostMapping("/login")
    public CommonResult<Map<String, Object>> login(@Valid @RequestBody ClientLoginDTO dto) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, dto.getPhone()));
        if (user == null) {
            throw new BusinessException(40100, "账号不存在，请先领取试用或激活卡密");
        }
        if ("FROZEN".equals(user.getStatus())) {
            throw new BusinessException(40104, "账号已被冻结，请联系管理员");
        }
        UserCredential credential = credentialMapper.selectOne(new LambdaQueryWrapper<UserCredential>()
                .eq(UserCredential::getUserId, user.getId()));
        if (credential == null || !"ACTIVE".equals(credential.getStatus())
                || !passwordEncoder.matches(dto.getPassword(), credential.getPasswordHash())) {
            throw new BusinessException(40105, "手机号或密码错误");
        }
        if (user.getDeviceId() != null && !user.getDeviceId().equals(dto.getDeviceId())) {
            throw new BusinessException(40103, "账号已绑定其他电脑，请在原电脑解绑或联系管理员");
        }
        if (user.getDeviceId() == null) {
            user.setDeviceId(dto.getDeviceId());
            userMapper.updateById(user);
        }

        clientStpLogic.login(user.getId());
        deviceBindingService.bind(user.getPhone(), dto.getDeviceId());
        return CommonResult.success(payload(user, credential), "客户端登录成功");
    }

    @PostMapping("/change-password")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public CommonResult<String> changePassword(@Valid @RequestBody ChangePasswordDTO dto) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, dto.getPhone()));
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
        credentialMapper.updateById(credential);
        return CommonResult.success("密码修改成功，请使用新密码登录");
    }

    @PostMapping("/logout")
    public CommonResult<String> logout(HttpServletRequest request) {
        User user = (User) request.getAttribute("pdkClientUser");
        deviceBindingService.unbind(user.getPhone());
        clientStpLogic.logout();
        return CommonResult.success("已注销当前会话");
    }

    @PostMapping("/unbind-device")
    public CommonResult<String> unbindDevice(HttpServletRequest request) {
        User user = (User) request.getAttribute("pdkClientUser");
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getDeviceId, null));
        deviceBindingService.unbind(user.getPhone());
        clientStpLogic.logout();
        return CommonResult.success("电脑已解绑，请重新登录");
    }

    private Map<String, Object> payload(User user, UserCredential credential) {
        Map<String, Object> data = new LinkedHashMap<>();
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
        return data;
    }
}
