package com.pdk.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pdk.domain.entity.TokenPool;
import com.pdk.mapper.TokenPoolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenPoolMaintenanceJob {
    private final TokenPoolMapper tokenPoolMapper;

    @Value("${pdk.security.token-lease-seconds:300}")
    private long leaseSeconds;

    @Scheduled(fixedDelayString = "${pdk.security.lease-recovery-interval-ms:60000}")
    public void recoverExpiredLeases() {
        int recovered = tokenPoolMapper.update(null, new LambdaUpdateWrapper<TokenPool>()
                .eq(TokenPool::getHealthStatus, "BUSY")
                .lt(TokenPool::getLeasedAt, LocalDateTime.now().minusSeconds(leaseSeconds))
                .set(TokenPool::getHealthStatus, "HEALTHY")
                .set(TokenPool::getLeaseClientPhone, null)
                .set(TokenPool::getLeasedAt, null));
        if (recovered > 0) {
            log.info("已回收 {} 个超时未上报的小号资源租约", recovered);
        }
    }

    @Scheduled(cron = "${pdk.security.daily-counter-reset-cron:0 0 0 * * *}", zone = "${pdk.security.scheduler-zone:Asia/Shanghai}")
    public void resetDailyResourceCounters() {
        int reset = tokenPoolMapper.update(null, new LambdaUpdateWrapper<TokenPool>()
                .notIn(TokenPool::getHealthStatus, "FAULT_BLACK", "EXPIRED")
                .set(TokenPool::getDailyCallsCount, 0));
        log.info("已重置 {} 个小号资源的每日调用计数", reset);
    }
}
