package com.pdk.platform.business;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pdk.business.spi.BusinessHandler;
import com.pdk.business.spi.BusinessHandlerRegistry;
import com.pdk.common.exception.BusinessException;
import com.pdk.config.BusinessDeploymentProperties;
import com.pdk.domain.dto.CreateBusinessDTO;
import com.pdk.domain.dto.UpdateBusinessDTO;
import com.pdk.domain.entity.Business;
import com.pdk.domain.entity.PackagePlan;
import com.pdk.domain.entity.TokenPool;
import com.pdk.domain.entity.User;
import com.pdk.domain.vo.BusinessRuntimeVO;
import com.pdk.mapper.BusinessMapper;
import com.pdk.mapper.PackagePlanMapper;
import com.pdk.mapper.TokenPoolMapper;
import com.pdk.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BusinessService {
    private final BusinessMapper businessMapper;
    private final BusinessHandlerRegistry handlerRegistry;
    private final BusinessDeploymentProperties deploymentProperties;
    private final UserMapper userMapper;
    private final PackagePlanMapper packagePlanMapper;
    private final TokenPoolMapper tokenPoolMapper;

    public Business requireByAppId(long appId) {
        Business business = businessMapper.selectOne(new LambdaQueryWrapper<Business>()
                .eq(Business::getAppId, appId).last("LIMIT 1"));
        if (business == null) throw new BusinessException(40450, "appId 对应业务不存在: " + appId);
        return business;
    }

    public Business requireById(long bizId) {
        Business business = businessMapper.selectById(bizId);
        if (business == null) throw new BusinessException(40450, "业务不存在: " + bizId);
        return business;
    }

    public BusinessContext requireAvailableByAppId(long appId) {
        Business business = requireByAppId(appId);
        BusinessRuntimeVO runtime = runtime(business, false);
        if (!"AVAILABLE".equals(runtime.getEffectiveStatus())) {
            if ("DISABLED_BY_ADMIN".equals(runtime.getEffectiveStatus())) {
                throw new BusinessException(40321, "业务维护中: " + business.getBizName());
            }
            throw new BusinessException(50350, runtime.getUnavailableReason());
        }
        return BusinessContext.from(business);
    }

    public List<BusinessRuntimeVO> listRuntime() {
        return businessMapper.selectList(new LambdaQueryWrapper<Business>().orderByAsc(Business::getId))
                .stream().map(business -> runtime(business, true)).toList();
    }

    public BusinessRuntimeVO publicRuntime(long appId) {
        return runtime(requireByAppId(appId), false);
    }

    public BusinessRuntimeVO runtime(Business business, boolean includeStats) {
        String code = normalize(business.getBizCode());
        Set<String> enabled = deploymentProperties.normalizedEnabledCodes();
        boolean deploymentEnabled = enabled.contains(code);
        boolean registered = handlerRegistry.contains(code);
        BusinessHandler handler = registered ? handlerRegistry.require(code) : null;
        boolean healthy = handler != null && handler.healthy();
        String effective;
        String reason = null;
        if (!"ACTIVE".equals(business.getStatus())) {
            effective = "DISABLED_BY_ADMIN";
            reason = "业务已由管理员关闭";
        } else if (!deploymentEnabled) {
            effective = "NOT_IN_DEPLOYMENT";
            reason = "当前部署未启用业务 " + code;
        } else if (!registered) {
            effective = "HANDLER_MISSING";
            reason = "当前运行包缺少业务 Handler: " + code;
        } else if (!healthy) {
            effective = "HANDLER_UNHEALTHY";
            reason = handler.healthMessage();
        } else {
            effective = "AVAILABLE";
        }
        Long users = includeStats ? userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getBizId, business.getId())) : null;
        Long plans = includeStats ? packagePlanMapper.selectCount(new LambdaQueryWrapper<PackagePlan>()
                .eq(PackagePlan::getBizId, business.getId())) : null;
        Long resources = includeStats ? tokenPoolMapper.selectCount(new LambdaQueryWrapper<TokenPool>()
                .eq(TokenPool::getBizId, business.getId()).eq(TokenPool::getIsDiscarded, 0)) : null;
        Long available = includeStats ? tokenPoolMapper.selectCount(new LambdaQueryWrapper<TokenPool>()
                .eq(TokenPool::getBizId, business.getId()).eq(TokenPool::getIsDiscarded, 0)
                .eq(TokenPool::getHealthStatus, "HEALTHY")) : null;
        return BusinessRuntimeVO.builder()
                .bizId(business.getId()).appId(business.getAppId()).bizCode(code)
                .businessName(business.getBizName()).businessDescription(business.getDescription())
                .registrationMode(business.getRegistrationMode())
                .authorizationMode(business.getAuthorizationMode())
                .trialEnabled(Integer.valueOf(1).equals(business.getTrialEnabled()))
                .trialDurationHours(business.getTrialDurationHours())
                .trialAccountCount(business.getTrialAccountCount())
                .trialCallsPerAccount(business.getTrialCallsPerAccount())
                .forceInitialPasswordChange(Integer.valueOf(1).equals(business.getForceInitialPasswordChange()))
                .configuredStatus(business.getStatus()).deploymentEnabled(deploymentEnabled)
                .handlerRegistered(registered).handlerHealth(healthy ? "UP" : "DOWN")
                .supportedActions(handler == null ? Set.of() : handler.supportedActions())
                .effectiveStatus(effective).unavailableReason(reason)
                .userCount(users).packageCount(plans).resourceCount(resources).availableResourceCount(available)
                .createdAt(business.getCreatedAt()).updatedAt(business.getUpdatedAt()).build();
    }

    @Transactional
    public Business create(CreateBusinessDTO dto) {
        String code = normalize(dto.getBizCode());
        if (businessMapper.selectCount(new LambdaQueryWrapper<Business>()
                .eq(Business::getAppId, dto.getAppId()).or().eq(Business::getBizCode, code)) > 0) {
            throw new BusinessException(40052, "appId 或 bizCode 已存在");
        }
        Business business = new Business();
        business.setAppId(dto.getAppId());
        business.setBizCode(code);
        business.setBizName(dto.getBizName().trim());
        business.setDescription(dto.getDescription());
        business.setRegistrationMode(dto.getRegistrationMode());
        business.setAuthorizationMode(dto.getAuthorizationMode());
        business.setTrialEnabled(Boolean.TRUE.equals(dto.getTrialEnabled()) ? 1 : 0);
        business.setTrialDurationHours(dto.getTrialDurationHours());
        business.setTrialAccountCount(dto.getTrialAccountCount());
        business.setTrialCallsPerAccount(dto.getTrialCallsPerAccount());
        business.setForceInitialPasswordChange(Boolean.TRUE.equals(dto.getForceInitialPasswordChange()) ? 1 : 0);
        business.setStatus("DISABLED");
        validateTrial(business);
        businessMapper.insert(business);
        return business;
    }

    @Transactional
    public Business update(long bizId, UpdateBusinessDTO dto) {
        Business business = requireById(bizId);
        if (!java.util.Objects.equals(business.getAuthorizationMode(), dto.getAuthorizationMode())) {
            if ("ACTIVE".equals(business.getStatus())) {
                throw new BusinessException(40952, "切换授权模型前必须先关闭业务");
            }
            if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getBizId, bizId)) > 0) {
                throw new BusinessException(40953, "该业务已有用户，不能直接切换授权模型；请新建业务或完成专项迁移");
            }
        }
        business.setBizName(dto.getBizName().trim());
        business.setDescription(dto.getDescription());
        business.setRegistrationMode(dto.getRegistrationMode());
        business.setAuthorizationMode(dto.getAuthorizationMode());
        business.setTrialEnabled(Boolean.TRUE.equals(dto.getTrialEnabled()) ? 1 : 0);
        business.setTrialDurationHours(dto.getTrialDurationHours());
        business.setTrialAccountCount(dto.getTrialAccountCount());
        business.setTrialCallsPerAccount(dto.getTrialCallsPerAccount());
        business.setForceInitialPasswordChange(Boolean.TRUE.equals(dto.getForceInitialPasswordChange()) ? 1 : 0);
        validateTrial(business);
        businessMapper.updateById(business);
        return business;
    }

    @Transactional
    public Business setEnabled(long bizId, boolean enabled) {
        Business business = requireById(bizId);
        if (enabled) {
            String code = normalize(business.getBizCode());
            if (!deploymentProperties.normalizedEnabledCodes().contains(code))
                throw new BusinessException(50350, "当前部署未启用业务 " + code);
            if (!handlerRegistry.contains(code))
                throw new BusinessException(50350, "当前运行包缺少业务 Handler: " + code);
            BusinessHandler handler = handlerRegistry.require(code);
            if (!handler.healthy()) throw new BusinessException(50350, handler.healthMessage());
            validateTrial(business);
        }
        business.setStatus(enabled ? "ACTIVE" : "DISABLED");
        businessMapper.updateById(business);
        return business;
    }

    private void validateTrial(Business business) {
        if ("DEVICE_LICENSE".equals(business.getAuthorizationMode())
                && Integer.valueOf(1).equals(business.getTrialEnabled())) {
            throw new BusinessException(40054, "设备许可证业务暂不支持用户级试用；请分配试用卡密许可证");
        }
        if (Integer.valueOf(1).equals(business.getTrialEnabled())
                && (business.getTrialDurationHours() == null || business.getTrialDurationHours() <= 0
                || business.getTrialAccountCount() == null || business.getTrialAccountCount() <= 0
                || business.getTrialCallsPerAccount() == null || business.getTrialCallsPerAccount() <= 0)) {
            throw new BusinessException(40053, "启用试用时，时长、账号数和单账号次数必须大于0");
        }
    }

    private String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
