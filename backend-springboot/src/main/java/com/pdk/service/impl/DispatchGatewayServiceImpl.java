package com.pdk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pdk.common.exception.BusinessException;
import com.pdk.common.utils.AesByteFlipUtils;
import com.pdk.domain.dto.AcquireTokenRequestDTO;
import com.pdk.domain.dto.ReportResultDTO;
import com.pdk.domain.entity.TokenPool;
import com.pdk.domain.entity.User;
import com.pdk.domain.entity.PdkDispatchLog;
import com.pdk.domain.vo.EncryptedTokenPayloadVO;
import com.pdk.mapper.TokenPoolMapper;
import com.pdk.mapper.UserMapper;
import com.pdk.mapper.PdkDispatchLogMapper;
import com.pdk.service.DeviceBindingService;
import com.pdk.service.ResourceLeaseService;
import com.pdk.service.IDispatchGatewayService;
import com.pdk.service.AccountAssignmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchGatewayServiceImpl implements IDispatchGatewayService {

    @Value("${pdk.security.token-lease-seconds:300}")
    private long leaseSeconds;

    private final TokenPoolMapper tokenPoolMapper;
    private final UserMapper userMapper;
    private final PdkDispatchLogMapper dispatchLogMapper;
    private final DeviceBindingService deviceBindingService;
    private final ResourceLeaseService resourceLeaseService;
    private final AccountAssignmentService assignmentService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EncryptedTokenPayloadVO acquireEncryptedToken(AcquireTokenRequestDTO dto, String userPhone, String deviceId) {
        if (Math.abs(System.currentTimeMillis() - dto.getTimestamp()) > 5 * 60 * 1000L) {
            throw new BusinessException(40012, "客户端时间偏差超过5分钟，请校准系统时间");
        }
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
        String activeDevice = deviceBindingService.get(userPhone);
        if (activeDevice != null && !activeDevice.equals(deviceId)) {
            throw new BusinessException(40103, "ERR_DEVICE_KICK_OUT: 账号已在其他设备登录，本设备被迫下线");
        }
        // 刷新单机绑定活跃时长 (30分钟心跳保活)
        if (user.getDeviceId() == null || !user.getDeviceId().equals(deviceId)) {
            throw new BusinessException(40103, "ERR_DEVICE_KICK_OUT: 当前电脑与账号绑定不一致");
        }
        deviceBindingService.bind(userPhone, deviceId);

        // 3. 只从当前套餐期已独占分配给该用户的小号中轮询，不允许跨客户共享。
        AccountAssignmentService.AssignedResource assigned = assignmentService.acquire(user);
        TokenPool healthyToken = assigned.token();
        healthyToken.setHealthStatus("BUSY");
        healthyToken.setLeaseClientPhone(userPhone);
        healthyToken.setLeasedAt(LocalDateTime.now());
        tokenPoolMapper.updateById(healthyToken);

        // 4. 生成短效租约 TraceId，并暂存 Redis
        String leaseTraceId = "TRACE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        resourceLeaseService.create(leaseTraceId, new ResourceLeaseService.LeaseInfo(
                healthyToken.getId(), userPhone, healthyToken.getAccountAlias(), dto.getActionType(),
                assigned.assignment().getId(), assigned.assignment().getSlotIndex()));

        // 5. 使用 AES-128-GCM + 0x50 0x44 字节倒序翻转混淆
        String rawTokenPayload = "{\"token\":\"" + healthyToken.getTokenVal() + "\",\"leaseId\":\"" + leaseTraceId + "\",\"expire\":" + leaseSeconds + "}";
        String encryptedData = AesByteFlipUtils.encryptAndFlip(rawTokenPayload);

        return EncryptedTokenPayloadVO.builder()
                .encryptedPayload(encryptedData)
                .leaseTraceId(leaseTraceId)
                .expireAtTimestamp(System.currentTimeMillis() + leaseSeconds * 1000)
                .remainingUserQuota(user.getRemainingCalls())
                .dailyQuotaLimit(user.getDailyCallsLimit())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reportAndDeductQuota(ReportResultDTO dto, String userPhone) {
        Long existing = dispatchLogMapper.selectCount(new LambdaQueryWrapper<PdkDispatchLog>()
                .eq(PdkDispatchLog::getReqUuid, dto.getLeaseTraceId()));
        if (existing != null && existing > 0) {
            log.info("重复结果上报命中幂等记录: traceId={}", dto.getLeaseTraceId());
            return;
        }
        ResourceLeaseService.LeaseInfo leaseInfo = resourceLeaseService.consume(dto.getLeaseTraceId(), userPhone);
        if (leaseInfo == null) {
            throw new BusinessException(41001, "租约已过期或不存在，请重新领取资源");
        }
        Long tokenId = leaseInfo.tokenId();
        String accountAlias = leaseInfo.accountAlias();
        String actionType = leaseInfo.actionType();
        Long assignmentId = leaseInfo.assignmentId();

        int deductCount = 0;
        String execStatus;

        if ("SUCCESS".equals(dto.getStatus())) {
            // 正常执行: 用户扣减 1 次配额，底层 Token 调度次数 +1
            int deducted = userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getPhone, userPhone)
                    .gt(User::getRemainingCalls, 0)
                    .setSql("remaining_calls = remaining_calls - 1"));
            if (deducted == 0) {
                throw new BusinessException(40302, "ERR_QUOTA_EXHAUSTED: 可用调用次数已耗尽");
            }

            tokenPoolMapper.update(null, new LambdaUpdateWrapper<TokenPool>()
                    .eq(TokenPool::getId, tokenId)
                    .setSql("daily_calls_count = daily_calls_count + 1"));
            if (assignmentId != null) assignmentService.recordSuccess(assignmentId);

            log.info("业务调用成功上报并扣费: user={}, tokenId={}", userPhone, tokenId);
            deductCount = 1;
            execStatus = "SUCCESS";
        } else if ("FAIL_ACCOUNT_BANNED".equals(dto.getStatus())) {
            // 底层拼多多账号被封禁/过期 -> 触发免责自愈: 扣 0 次，拉黑故障 Token
            tokenPoolMapper.markTokenFaultStatus(tokenId, "FAULT_BLACK");
            if (assignmentId != null) assignmentService.replaceFault(assignmentId);
            log.error("底层官方 Token 故障拉黑免责触发: tokenId={}, user={}, error={}", tokenId, userPhone, dto.getErrorMessage());
            execStatus = "TOKEN_FAIL";
        } else if ("FAIL_NETWORK".equals(dto.getStatus())) {
            execStatus = "NET_TIMEOUT";
        } else {
            execStatus = "PARAM_ERROR";
        }

        if (!"FAIL_ACCOUNT_BANNED".equals(dto.getStatus())) {
            tokenPoolMapper.update(null, new LambdaUpdateWrapper<TokenPool>()
                    .eq(TokenPool::getId, tokenId)
                    .eq(TokenPool::getHealthStatus, "BUSY")
                    .set(TokenPool::getHealthStatus, "HEALTHY")
                    .set(TokenPool::getLeaseClientPhone, null)
                    .set(TokenPool::getLeasedAt, null));
        } else {
            tokenPoolMapper.update(null, new LambdaUpdateWrapper<TokenPool>()
                    .eq(TokenPool::getId, tokenId)
                    .set(TokenPool::getLeaseClientPhone, null)
                    .set(TokenPool::getLeasedAt, null));
        }

        PdkDispatchLog dispatchLog = new PdkDispatchLog();
        dispatchLog.setReqUuid(dto.getLeaseTraceId());
        dispatchLog.setUserPhone(userPhone);
        dispatchLog.setSlotIndex(leaseInfo.slotIndex());
        dispatchLog.setRealPddAccountId(accountAlias);
        dispatchLog.setActionType(actionType);
        dispatchLog.setDeductCount(deductCount);
        dispatchLog.setExecStatus(execStatus);
        long duration = dto.getResponseDurationMs() == null ? 0L : dto.getResponseDurationMs();
        dispatchLog.setResponseTimeMs((int) Math.min(duration, Integer.MAX_VALUE));
        dispatchLog.setCreatedAt(LocalDateTime.now());
        dispatchLogMapper.insert(dispatchLog);
    }
}
