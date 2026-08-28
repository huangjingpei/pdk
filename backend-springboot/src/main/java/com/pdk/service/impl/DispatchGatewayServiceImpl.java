package com.pdk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pdk.common.exception.BusinessException;
import com.pdk.common.utils.AesByteFlipUtils;
import com.pdk.business.spi.BusinessHandler;
import com.pdk.business.spi.BusinessHandlerRegistry;
import com.pdk.business.spi.FailureDecision;
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
import com.pdk.platform.business.BusinessContext;

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
    private final BusinessHandlerRegistry businessRegistry;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EncryptedTokenPayloadVO acquireEncryptedToken(AcquireTokenRequestDTO dto, BusinessContext business,
                                                          User user, String deviceId) {
        BusinessHandler businessHandler = businessRegistry.require(business.bizCode());
        businessHandler.validateAcquire(dto);
        if (Math.abs(System.currentTimeMillis() - dto.getTimestamp()) > 5 * 60 * 1000L) {
            throw new BusinessException(40012, "客户端时间偏差超过5分钟，请校准系统时间");
        }
        // 1. 检查用户状态与额度
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
        String activeDevice = deviceBindingService.get(user.getBizId(), user.getId());
        if (activeDevice != null && !activeDevice.equals(deviceId)) {
            throw new BusinessException(40103, "ERR_DEVICE_KICK_OUT: 账号已在其他设备登录，本设备被迫下线");
        }
        // 刷新单机绑定活跃时长 (30分钟心跳保活)
        if (user.getDeviceId() == null || !user.getDeviceId().equals(deviceId)) {
            throw new BusinessException(40103, "ERR_DEVICE_KICK_OUT: 当前电脑与账号绑定不一致");
        }
        deviceBindingService.bind(user.getBizId(), user.getId(), deviceId);

        // 3. 只从当前套餐期已独占分配给该用户的小号中轮询，不允许跨客户共享。
        AccountAssignmentService.AssignedResource assigned = assignmentService.acquire(user);
        TokenPool healthyToken = assigned.token();
        healthyToken.setHealthStatus("BUSY");
        healthyToken.setLeaseClientPhone(user.getPhone());
        healthyToken.setLeasedAt(LocalDateTime.now());
        tokenPoolMapper.updateById(healthyToken);

        // 4. 生成短效租约 TraceId，并暂存 Redis
        String leaseTraceId = "TRACE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        resourceLeaseService.create(leaseTraceId, new ResourceLeaseService.LeaseInfo(
                business.bizId(), user.getId(), healthyToken.getId(), user.getPhone(),
                healthyToken.getAccountAlias(), dto.getActionType(),
                assigned.assignment().getId(), assigned.assignment().getSlotIndex()));

        // 5. 使用 AES-128-GCM + 0x50 0x44 字节倒序翻转混淆
        String rawTokenPayload = businessHandler.buildCredentialPayload(healthyToken, leaseTraceId, leaseSeconds);
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
    public void reportAndDeductQuota(ReportResultDTO dto, BusinessContext business, User user) {
        BusinessHandler businessHandler = businessRegistry.require(business.bizCode());
        Long existing = dispatchLogMapper.selectCount(new LambdaQueryWrapper<PdkDispatchLog>()
                .eq(PdkDispatchLog::getBizId, business.bizId())
                .eq(PdkDispatchLog::getReqUuid, dto.getLeaseTraceId()));
        if (existing != null && existing > 0) {
            log.info("重复结果上报命中幂等记录: traceId={}", dto.getLeaseTraceId());
            return;
        }
        ResourceLeaseService.LeaseInfo leaseInfo = resourceLeaseService.consume(
                business.bizId(), dto.getLeaseTraceId(), user.getId());
        if (leaseInfo == null) {
            throw new BusinessException(41001, "租约已过期或不存在，请重新领取资源");
        }
        Long tokenId = leaseInfo.tokenId();
        String accountAlias = leaseInfo.accountAlias();
        String actionType = leaseInfo.actionType();
        Long assignmentId = leaseInfo.assignmentId();

        FailureDecision decision = businessHandler.classifyReport(dto);
        int deductCount = 0;
        String execStatus = decision.execStatus();

        if (decision.deductQuota()) {
            // 正常执行: 小号槽位 used_calls +1（封顶 allocated），用户总池由槽位额度派生
            if (assignmentId != null) {
                assignmentService.recordSuccess(assignmentId);
            } else {
                // 兜底: 无 assignment 关联时直接递减用户级总池
                int deducted = userMapper.update(null, new LambdaUpdateWrapper<User>()
                        .eq(User::getBizId, business.bizId())
                        .eq(User::getId, user.getId())
                        .gt(User::getRemainingCalls, 0)
                        .setSql("remaining_calls = remaining_calls - 1"));
                if (deducted == 0) {
                    throw new BusinessException(40302, "ERR_QUOTA_EXHAUSTED: 可用调用次数已耗尽");
                }
            }

            tokenPoolMapper.update(null, new LambdaUpdateWrapper<TokenPool>()
                    .eq(TokenPool::getId, tokenId)
                    .setSql("daily_calls_count = daily_calls_count + 1"));
            // 以 assignment 槽位额度为唯一权威重算用户总池，消除双计数错位
            assignmentService.recomputeUserRemainingCalls(user.getId());

            log.info("业务调用成功上报并扣费: biz={}, userId={}, tokenId={}",
                    business.bizCode(), user.getId(), tokenId);
            deductCount = 1;
        } else if (decision.blacklistResource()) {
            // 当前业务 Handler 判定资源失效 -> 触发免责自愈: 扣 0 次并拉黑故障资源
            tokenPoolMapper.markTokenFaultStatus(tokenId, "FAULT_BLACK");
            if (assignmentId != null) assignmentService.replaceFault(assignmentId);
            log.error("业务资源故障拉黑免责触发: biz={}, tokenId={}, userId={}, error={}",
                    business.bizCode(), tokenId, user.getId(), dto.getErrorMessage());
        }

        if (!decision.blacklistResource()) {
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
        dispatchLog.setBizId(business.bizId());
        dispatchLog.setUserId(user.getId());
        dispatchLog.setReqUuid(dto.getLeaseTraceId());
        dispatchLog.setUserPhone(user.getPhone());
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
