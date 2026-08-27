package com.pdk.business.spi;

import com.pdk.domain.dto.AcquireTokenRequestDTO;
import com.pdk.domain.dto.ReportResultDTO;
import com.pdk.domain.entity.TokenPool;

/**
 * 业务差异扩展点。登录、套餐、卡密、租约、扣次和审计仍由平台公共服务负责；
 * 动作校验、凭证载荷和失败分类由具体 bizCode 的实现负责。
 */
public interface BusinessHandler {
    String bizCode();

    void validateAcquire(AcquireTokenRequestDTO request);

    String buildCredentialPayload(TokenPool resource, String leaseTraceId, long leaseSeconds);

    FailureDecision classifyReport(ReportResultDTO report);
}
