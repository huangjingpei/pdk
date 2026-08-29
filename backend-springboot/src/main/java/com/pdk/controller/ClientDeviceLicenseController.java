package com.pdk.controller;

import com.pdk.common.api.CommonResult;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.entity.DeviceLicense;
import com.pdk.domain.entity.LicenseRenewal;
import com.pdk.domain.entity.User;
import com.pdk.domain.vo.DeviceLicenseVO;
import com.pdk.service.DeviceLicenseService;
import com.pdk.service.ClientLicenseContext;
import com.pdk.service.LoginLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/client/device-license")
@RequiredArgsConstructor
public class ClientDeviceLicenseController {
    private final DeviceLicenseService licenseService;
    private final LoginLogService loginLogService;

    @GetMapping("/current")
    public CommonResult<DeviceLicenseVO> current(HttpServletRequest request) {
        return CommonResult.success(licenseService.view(requireLicense(request)));
    }

    @GetMapping("/devices")
    public CommonResult<List<DeviceLicenseVO>> devices(HttpServletRequest request) {
        User user = (User) request.getAttribute("pdkClientUser");
        return CommonResult.success(licenseService.listByUser(user.getBizId(), user.getId()));
    }

    @GetMapping("/renewal-history")
    public CommonResult<List<LicenseRenewal>> renewalHistory(HttpServletRequest request) {
        return CommonResult.success(licenseService.renewalHistory(requireLicense(request).getId()));
    }

    @PostMapping("/unbind")
    public CommonResult<String> unbind(HttpServletRequest request) {
        DeviceLicense license = requireLicense(request);
        var device = (com.pdk.domain.entity.UserDevice) request.getAttribute("pdkClientDevice");
        User user = (User) request.getAttribute("pdkClientUser");
        ClientLicenseContext context = new ClientLicenseContext(license, device);
        licenseService.unbind(license.getId(), "CLIENT_UNBIND");
        loginLogService.recordLicenseAction(user.getBizId(), user.getId(), user.getPhone(),
                "DEVICE_UNBIND", context, "客户端主动解绑", request);
        return CommonResult.success("当前许可证已解绑，有效期继续计算；新设备登录时请输入原卡密");
    }

    private DeviceLicense requireLicense(HttpServletRequest request) {
        Object value = request.getAttribute("pdkClientLicense");
        if (value instanceof DeviceLicense license) return license;
        throw new BusinessException(40385, "当前设备没有许可证");
    }
}
