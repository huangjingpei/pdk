package com.pdk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pdk.common.exception.BusinessException;
import com.pdk.common.utils.AesByteFlipUtils;
import com.pdk.domain.dto.AcquireTokenRequestDTO;
import com.pdk.domain.dto.ReportResultDTO;
import com.pdk.domain.entity.TokenPool;
import com.pdk.domain.entity.User;
import com.pdk.domain.vo.EncryptedTokenPayloadVO;
import com.pdk.mapper.TokenPoolMapper;
import com.pdk.mapper.UserMapper;
import com.pdk.service.IDispatchGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchGatewayServiceImpl implements IDispatchGatewayService {

    private final TokenPoolMapper tokenPoolMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EncryptedTokenPayloadVO acquireEncryptedToken(AcquireTokenRequestDTO dto, String userPhone, String deviceId) {
        // 1. 检查用户状态与额度
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, userPhone));
        if (user == null) {
            throw new BusinessException(40100, "用户不存在");
        }
        if (user.getExpireTime() == null || user.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException(40301, "ERR_SUBSCRIPTION_EXPIRED: 您的套餐已到期，请核销新卡密延期");
        }
        if (user.getRemainingCalls() <= 0) {
            throw new BusinessException(40302, "ERR_QUOTA_EXHAUSTED: 今日调用配额已耗尽，请升级套餐");
        }

        // 2. 检查单设备互踢绑定
        String activeDevice = redisTemplate.opsForValue().get("pdk:device:bind:" + userPhone);
        if (activeDevice != null && !activeDevice.equals(deviceId)) {
            throw new BusinessException(40103, "ERR_DEVICE_KICK_OUT: 账号已在其他设备登录，本设备被迫下线");
        }
        // 刷新单机绑定活跃时长 (30分钟心跳保活)
        redisTemplate.opsForValue().set("pdk:device:bind:" + userPhone, deviceId, 30, TimeUnit.MINUTES);

        // 3. 从公共池中挑出一个健康且日调用未超限的底层 Token
        TokenPool healthyToken = tokenPoolMapper.selectAvailableHealthyTokenForUpdate();
        if (healthyToken == null) {
            throw new BusinessException(50301, "ERR_NO_AVAILABLE_TOKEN: 官方通道瞬时繁忙，调度池正在扩容，请稍后重试");
        }

        // 4. 生成短效租约 TraceId，并暂存 Redis
        String leaseTraceId = "TRACE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String traceKey = "pdk:lease:" + leaseTraceId;
        String leaseInfo = healthyToken.getId() + ":" + userPhone + ":" + healthyToken.getTokenVal();
        redisTemplate.opsForValue().set(traceKey, leaseInfo, 300, TimeUnit.SECONDS);

        // 5. 使用 AES-128-GCM + 0x50 0x44 字节倒序翻转混淆
        String rawTokenPayload = "{\"token\":\"" + healthyToken.getTokenVal() + "\",\"leaseId\":\"" + leaseTraceId + "\",\"expire\":300}";
        String encryptedData = AesByteFlipUtils.encryptAndFlip(rawTokenPayload);

        return EncryptedTokenPayloadVO.builder()
                .encryptedPayload(encryptedData)
                .leaseTraceId(leaseTraceId)
                .expireAtTimestamp(System.currentTimeMillis() + 300 * 1000)
                .remainingUserQuota(user.getRemainingCalls())
                .dailyQuotaLimit(user.getDailyCallsLimit())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reportAndDeductQuota(ReportResultDTO dto, String userPhone) {
        String traceKey = "pdk:lease:" + dto.getLeaseTraceId();
        String leaseInfo = redisTemplate.opsForValue().get(traceKey);

        if (leaseInfo == null) {
            log.warn("上报流水已过期或已被处理: traceId={}", dto.getLeaseTraceId());
            return;
        }

        String[] parts = leaseInfo.split(":", 3);
        Long tokenId = Long.valueOf(parts[0]);

        if ("SUCCESS".equals(dto.getStatus())) {
            // 正常执行: 用户扣减 1 次配额，底层 Token 调度次数 +1
            userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getPhone, userPhone)
                    .gt(User::getRemainingCalls, 0)
                    .setSql("remaining_calls = remaining_calls - 1"));

            tokenPoolMapper.update(null, new LambdaUpdateWrapper<TokenPool>()
                    .eq(TokenPool::getId, tokenId)
                    .setSql("daily_calls_count = daily_calls_count + 1"));

            log.info("业务调用成功上报并扣费: user={}, tokenId={}", userPhone, tokenId);
        } else if ("FAIL_ACCOUNT_BANNED".equals(dto.getStatus())) {
            // 底层拼多多账号被封禁/过期 -> 触发免责自愈: 扣 0 次，拉黑故障 Token
            tokenPoolMapper.markTokenFaultStatus(tokenId, "FAULT_BLACK");
            log.error("底层官方 Token 故障拉黑免责触发: tokenId={}, user={}, error={}", tokenId, userPhone, dto.getErrorMessage());
        }

        // 删除租约缓存
        redisTemplate.delete(traceKey);
    }
}
