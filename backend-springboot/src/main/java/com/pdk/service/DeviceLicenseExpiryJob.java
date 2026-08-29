package com.pdk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pdk.domain.entity.DeviceLicense;
import com.pdk.mapper.DeviceLicenseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceLicenseExpiryJob {
    private final DeviceLicenseMapper licenseMapper;
    private final DeviceLicenseService licenseService;

    @Scheduled(fixedDelayString = "${pdk.device-license.expiry-scan-ms:15000}")
    public void expireAndKick() {
        List<DeviceLicense> expired = licenseMapper.selectList(new LambdaQueryWrapper<DeviceLicense>()
                .eq(DeviceLicense::getStatus, "ACTIVE")
                .le(DeviceLicense::getExpireAt, LocalDateTime.now()).last("LIMIT 200"));
        for (DeviceLicense license : expired) {
            try {
                licenseService.expireIfDue(license.getId());
            } catch (RuntimeException e) {
                log.warn("许可证到期收敛失败: licenseId={}, message={}", license.getId(), e.getMessage());
            }
        }
    }
}
