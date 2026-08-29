package com.pdk.domain.vo;

import com.pdk.domain.entity.CardKey;
import com.pdk.domain.entity.DeviceLicense;
import com.pdk.domain.entity.UserDevice;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DeviceLicenseVO {
    private Long licenseId;
    private Long bizId;
    private Long userId;
    private Long cardKeyId;
    private String cardKeyMasked;
    private Long userDeviceId;
    private String deviceId;
    private String deviceName;
    private String status;
    private Long packageId;
    private String packageName;
    private LocalDateTime activatedAt;
    private LocalDateTime effectiveAt;
    private LocalDateTime expireAt;
    private Integer remainingCalls;
    private Integer totalCalls;
    private LocalDateTime lastLoginAt;
    private LocalDateTime serverTime;

    public static DeviceLicenseVO from(DeviceLicense license, UserDevice device, CardKey card) {
        return DeviceLicenseVO.builder()
                .licenseId(license.getId()).bizId(license.getBizId()).userId(license.getUserId())
                .cardKeyId(license.getCardKeyId()).cardKeyMasked(mask(card == null ? null : card.getCardKey()))
                .userDeviceId(license.getUserDeviceId())
                .deviceId(device == null ? null : device.getDeviceId())
                .deviceName(device == null ? null : device.getDeviceName())
                .status(license.getStatus()).packageId(license.getPackageId())
                .packageName(license.getPackageNameSnapshot()).activatedAt(license.getActivatedAt())
                .effectiveAt(license.getEffectiveAt()).expireAt(license.getExpireAt())
                .remainingCalls(license.getRemainingCalls()).totalCalls(license.getTotalCalls())
                .lastLoginAt(device == null ? null : device.getLastLoginAt())
                .serverTime(LocalDateTime.now()).build();
    }

    private static String mask(String value) {
        if (value == null || value.length() < 8) return "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }
}
