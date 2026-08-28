package com.pdk.business.zhibo;

import com.pdk.business.spi.BusinessHandler;
import com.pdk.business.spi.FailureDecision;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.AcquireTokenRequestDTO;
import com.pdk.domain.dto.ReportResultDTO;
import com.pdk.domain.entity.TokenPool;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 直播聚合业务实现。ZHIBO_AI(appId=2) 与 ZHIBO_LIVE(appId=3) 共用实现，
 * 数据、用户、套餐和资源仍由 BusinessContext.bizId 严格隔离。
 */
@Component
public class ZhiboBusinessHandler implements BusinessHandler {
    public static final String AGGREGATE_CODE = "ZHIBO";
    public static final String AI_CODE = "ZHIBO_AI";
    public static final String LIVE_CODE = "ZHIBO_LIVE";
    private static final Set<String> CODES = Set.of(AI_CODE, LIVE_CODE);
    private static final Set<String> ACTIONS = Set.of(
            "AI_GENERATE", "LIVE_CONTROL", "LIVE_PUBLISH", "ACCOUNT_SYNC", "DETAIL_QUERY");

    @Override
    public String bizCode() {
        return AGGREGATE_CODE;
    }

    @Override
    public Set<String> supportedBizCodes() {
        return CODES;
    }

    @Override
    public Set<String> supportedActions() {
        return ACTIONS;
    }

    @Override
    public void validateAcquire(AcquireTokenRequestDTO request) {
        if (request == null || request.getActionType() == null || !ACTIONS.contains(request.getActionType())) {
            throw new BusinessException(40001, "直播业务动作类型不合法");
        }
    }

    @Override
    public String buildCredentialPayload(TokenPool resource, String leaseTraceId, long leaseSeconds) {
        String credential = resource.getCredentialPayload() == null ? resource.getTokenVal() : resource.getCredentialPayload();
        return "{\"credential\":\"" + escape(credential) + "\",\"leaseId\":\""
                + escape(leaseTraceId) + "\",\"expire\":" + leaseSeconds + "}";
    }

    @Override
    public FailureDecision classifyReport(ReportResultDTO report) {
        return switch (report.getStatus()) {
            case "SUCCESS" -> FailureDecision.success();
            case "FAIL_ACCOUNT_BANNED" -> FailureDecision.blacklist("RESOURCE_FAIL");
            case "FAIL_NETWORK" -> FailureDecision.exempt("NET_TIMEOUT");
            default -> FailureDecision.exempt("BUSINESS_ERROR");
        };
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
