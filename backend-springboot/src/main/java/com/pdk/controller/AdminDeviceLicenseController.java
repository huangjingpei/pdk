package com.pdk.controller;

import com.pdk.common.api.CommonResult;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.*;
import com.pdk.domain.entity.LicenseRenewal;
import com.pdk.domain.entity.User;
import com.pdk.domain.vo.DeviceLicenseVO;
import com.pdk.domain.vo.LicenseExportResult;
import com.pdk.mapper.UserMapper;
import com.pdk.mapper.DeviceLicenseMapper;
import com.pdk.mapper.LoginLogMapper;
import com.pdk.domain.entity.LoginLog;
import com.pdk.platform.business.BusinessContext;
import com.pdk.platform.business.BusinessService;
import com.pdk.security.*;
import com.pdk.service.AdminAuditService;
import com.pdk.service.DeviceLicenseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class AdminDeviceLicenseController {
    private final DeviceLicenseService licenseService;
    private final UserMapper userMapper;
    private final DeviceLicenseMapper deviceLicenseMapper;
    private final LoginLogMapper loginLogMapper;
    private final BusinessService businessService;
    private final AdminBusinessScope businessScope;
    private final AdminAuditService auditService;

    @GetMapping("/api/v1/admin/users/{userId}/device-licenses")
    @RequirePermission(RolePermissions.CARD_VIEW)
    public CommonResult<List<DeviceLicenseVO>> list(@PathVariable long userId, HttpServletRequest request) {
        User user = requireUser(userId, request);
        return CommonResult.success(licenseService.listByUserScoped(user.getBizId(), userId, principal(request)));
    }

    @GetMapping("/api/v1/admin/device-licenses/customer")
    @RequirePermission(RolePermissions.CARD_VIEW)
    public CommonResult<User> customer(@RequestParam long appId, @RequestParam String phone,
                                       HttpServletRequest request) {
        BusinessContext business = BusinessContext.from(businessService.requireByAppId(appId));
        businessScope.enforce(principal(request), business.bizId());
        User user = userMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                .eq(User::getBizId, business.bizId()).eq(User::getPhone, phone).last("LIMIT 1"));
        if (user == null) throw new BusinessException(40402, "该业务下不存在此手机号");
        return CommonResult.success(user);
    }

    @PostMapping("/api/v1/admin/users/{userId}/device-licenses/export")
    @RequirePermission(RolePermissions.CARD_VIEW)
    public CommonResult<LicenseExportResult> export(@PathVariable long userId,
                                                    @RequestParam long bizId, HttpServletRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(40402, "用户不存在");
        businessScope.enforce(principal(request), bizId);
        LicenseExportResult result = licenseService.exportCards(bizId, userId, principal(request));
        auditService.record(principal(request), bizId, "EXPORT_DEVICE_LICENSE_CARDS", "USER",
                String.valueOf(userId), null,
                "{\"bizId\":" + bizId + ",\"count\":" + result.getRecordCount() + ",\"file\":\"" + result.getFileName() + "\"}",
                "导出卡密给客户", request);
        return CommonResult.success(result, "已导出 " + result.getRecordCount() + " 张卡密，服务器已留存存根");
    }

    @PostMapping("/api/v1/admin/users/{userId}/device-licenses/revoke-business")
    @RequirePermission(RolePermissions.CARD_VOID)
    public CommonResult<String> revokeBusiness(@PathVariable long userId,
                                               @RequestParam long bizId,
                                               @RequestParam(defaultValue = "DISABLE_USER_BUSINESS") String reason,
                                               HttpServletRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(40402, "用户不存在");
        businessScope.enforce(principal(request), bizId);
        int n = licenseService.revokeUserBusiness(bizId, userId, reason, principal(request));
        auditService.record(principal(request), bizId, "DISABLE_USER_BUSINESS", "USER",
                String.valueOf(userId), null,
                "{\"bizId\":" + bizId + ",\"revoked\":" + n + "}", reason, request);
        return CommonResult.success("已禁用该客户在此业务下的全部授权（撤销 " + n + " 个许可证并作废卡密）");
    }

    @PostMapping("/api/v1/admin/users/{userId}/device-licenses/batch-assign")
    @RequirePermission(RolePermissions.CARD_CREATE)
    public CommonResult<List<String>> assign(@PathVariable long userId,
                                              @Valid @RequestBody BatchAssignLicenseDTO dto,
                                              HttpServletRequest request) {
        User user = requireUser(userId, request);
        BusinessContext business = BusinessContext.from(businessService.requireById(user.getBizId()));
        AdminPrincipal principal = principal(request);
        List<String> keys = licenseService.batchAssign(business, user, dto, principal);
        auditService.record(principal, user.getBizId(), "BATCH_ASSIGN_DEVICE_LICENSE", "USER",
                String.valueOf(userId), null, "{\"count\":" + keys.size() + ",\"packageId\":" + dto.getPackageId() + "}",
                dto.getRemark(), request);
        return CommonResult.success(keys, "已为该手机号分配 " + keys.size() + " 个独立设备许可证");
    }

    @PostMapping("/api/v1/admin/device-licenses/{licenseId}/renew")
    @RequirePermission(RolePermissions.CARD_RENEW)
    public CommonResult<LicenseRenewal> renew(@PathVariable long licenseId,
                                               @Valid @RequestBody RenewDeviceLicenseDTO dto,
                                               HttpServletRequest request) {
        requireScopedLicense(licenseId, request);
        LicenseRenewal result = licenseService.renew(licenseId, dto, principal(request));
        auditService.record(principal(request), result.getBizId(), "RENEW_DEVICE_LICENSE", "DEVICE_LICENSE",
                String.valueOf(licenseId), null, "{\"renewalOrderNo\":\"" + result.getRenewalOrderNo() + "\"}",
                dto.getRemark(), request);
        return CommonResult.success(result, "续费成功，原卡密保持不变");
    }

    @PostMapping("/api/v1/admin/device-licenses/batch-renew")
    @RequirePermission(RolePermissions.CARD_RENEW)
    public CommonResult<List<LicenseRenewal>> batchRenew(@Valid @RequestBody BatchRenewDeviceLicenseDTO dto,
                                                         HttpServletRequest request) {
        for (Long id : dto.getLicenseIds()) requireScopedLicense(id, request);
        AdminPrincipal principal = principal(request);
        List<LicenseRenewal> values = licenseService.batchRenew(dto.getLicenseIds(), dto.getRenewal(), principal);
        for (LicenseRenewal value : values) {
            auditService.record(principal, value.getBizId(), "BATCH_RENEW_DEVICE_LICENSE", "DEVICE_LICENSE",
                    String.valueOf(value.getLicenseId()), null,
                    "{\"renewalOrderNo\":\"" + value.getRenewalOrderNo() + "\"}",
                    dto.getRenewal().getRemark(), request);
        }
        return CommonResult.success(values, "已续费 " + values.size() + " 个许可证");
    }

    @PostMapping("/api/v1/admin/device-licenses/{licenseId}/unbind")
    @RequirePermission(RolePermissions.USER_UNBIND)
    public CommonResult<String> unbind(@PathVariable long licenseId,
                                        @RequestParam(defaultValue = "ADMIN_UNBIND") String reason,
                                        HttpServletRequest request) {
        DeviceLicenseVO value = licenseService.view(requireScopedLicense(licenseId, request));
        licenseService.unbind(licenseId, "ADMIN_UNBIND");
        auditService.record(principal(request), value.getBizId(), "UNBIND_DEVICE_LICENSE", "DEVICE_LICENSE",
                String.valueOf(licenseId), null, "{\"status\":\"UNBOUND\"}", reason, request);
        return CommonResult.success("许可证已解绑；有效期未暂停");
    }

    @PutMapping("/api/v1/admin/device-licenses/{licenseId}/status")
    @RequirePermission(RolePermissions.CARD_VOID)
    public CommonResult<String> status(@PathVariable long licenseId,
                                        @Valid @RequestBody UpdateDeviceLicenseStatusDTO dto,
                                        HttpServletRequest request) {
        DeviceLicenseVO value = licenseService.view(requireScopedLicense(licenseId, request));
        licenseService.setStatus(licenseId, dto.getStatus(), dto.getReason());
        auditService.record(principal(request), value.getBizId(), "UPDATE_DEVICE_LICENSE_STATUS", "DEVICE_LICENSE",
                String.valueOf(licenseId), null, "{\"status\":\"" + dto.getStatus() + "\"}", dto.getReason(), request);
        return CommonResult.success("许可证状态已更新");
    }

    @GetMapping("/api/v1/admin/device-licenses/{licenseId}/renewal-history")
    @RequirePermission(RolePermissions.CARD_VIEW)
    public CommonResult<List<LicenseRenewal>> history(@PathVariable long licenseId, HttpServletRequest request) {
        requireScopedLicense(licenseId, request);
        return CommonResult.success(licenseService.renewalHistory(licenseId));
    }

    @GetMapping("/api/v1/admin/device-licenses/{licenseId}/login-history")
    @RequirePermission(RolePermissions.LOG_VIEW)
    public CommonResult<List<LoginLog>> loginHistory(@PathVariable long licenseId, HttpServletRequest request) {
        requireScopedLicense(licenseId, request);
        return CommonResult.success(loginLogMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LoginLog>()
                .eq(LoginLog::getDeviceLicenseId, licenseId).orderByDesc(LoginLog::getId).last("LIMIT 200")));
    }

    private com.pdk.domain.entity.DeviceLicense requireScopedLicense(long id, HttpServletRequest request) {
        com.pdk.domain.entity.DeviceLicense value = deviceLicenseMapper.selectById(id);
        if (value == null) throw new BusinessException(40480, "设备许可证不存在");
        businessScope.enforce(principal(request), value.getBizId());
        return licenseService.requireAdminAccess(id, principal(request));
    }

    private User requireUser(long id, HttpServletRequest request) {
        User user = userMapper.selectById(id);
        if (user == null) throw new BusinessException(40402, "用户不存在");
        businessScope.enforce(principal(request), user.getBizId());
        return user;
    }

    private AdminPrincipal principal(HttpServletRequest request) {
        return (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
    }
}
