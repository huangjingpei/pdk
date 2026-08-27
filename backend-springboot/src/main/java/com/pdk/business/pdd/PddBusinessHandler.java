package com.pdk.business.pdd;

import com.pdk.business.spi.BusinessHandler;
import com.pdk.business.spi.FailureDecision;
import com.pdk.domain.dto.AcquireTokenRequestDTO;
import com.pdk.domain.dto.ReportResultDTO;
import com.pdk.domain.entity.TokenPool;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

/** appId=1 / bizCode=PDD 的拼多多业务差异实现。 */
@Component
@RequiredArgsConstructor
public class PddBusinessHandler implements BusinessHandler {
    public static final String BIZ_CODE = "PDD";
    private final PddActionValidator actionValidator;
    private final PddCredentialCodec credentialCodec;
    private final PddFailureClassifier failureClassifier;

    @Override
    public String bizCode() {
        return BIZ_CODE;
    }

    @Override
    public void validateAcquire(AcquireTokenRequestDTO request) {
        actionValidator.validate(request);
    }

    @Override
    public String buildCredentialPayload(TokenPool resource, String leaseTraceId, long leaseSeconds) {
        return credentialCodec.encode(resource, leaseTraceId, leaseSeconds);
    }

    @Override
    public FailureDecision classifyReport(ReportResultDTO report) {
        return failureClassifier.classify(report);
    }
}
