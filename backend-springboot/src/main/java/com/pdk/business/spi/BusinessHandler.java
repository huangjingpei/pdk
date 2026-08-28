package com.pdk.business.spi;

import com.pdk.domain.dto.AcquireTokenRequestDTO;
import com.pdk.domain.dto.ReportResultDTO;
import com.pdk.domain.entity.TokenPool;

import java.util.Set;

/**
 * 业务差异扩展点。登录、套餐、卡密、租约、扣次和审计仍由平台公共服务负责；
 * 动作校验、凭证载荷和失败分类由具体 bizCode 的实现负责。
 */
public interface BusinessHandler {
    String bizCode();

    /** 一个聚合实现可以同时承载多个数据库 bizCode，例如 ZHIBO_AI/ZHIBO_LIVE。 */
    default Set<String> supportedBizCodes() {
        return Set.of(bizCode());
    }

    default boolean healthy() {
        return true;
    }

    default String healthMessage() {
        return healthy() ? "UP" : "业务依赖不可用";
    }

    /** 客户端可用于渲染能力并在请求前校验，服务端 validateAcquire 仍是最终边界。 */
    default Set<String> supportedActions() {
        return Set.of();
    }

    void validateAcquire(AcquireTokenRequestDTO request);

    String buildCredentialPayload(TokenPool resource, String leaseTraceId, long leaseSeconds);

    FailureDecision classifyReport(ReportResultDTO report);
}
