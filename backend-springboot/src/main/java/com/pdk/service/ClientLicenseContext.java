package com.pdk.service;

import com.pdk.domain.entity.DeviceLicense;
import com.pdk.domain.entity.UserDevice;

public record ClientLicenseContext(DeviceLicense license, UserDevice device) {
    public String loginId() { return "license:" + license.getId(); }
}
