package com.pdk.business.pdd;

import com.pdk.business.spi.FailureDecision;
import com.pdk.domain.dto.ReportResultDTO;
import org.springframework.stereotype.Component;

@Component
public class PddFailureClassifier {
    public FailureDecision classify(ReportResultDTO report) {
        return switch (report.getStatus()) {
            case "SUCCESS" -> FailureDecision.success();
            case "FAIL_ACCOUNT_BANNED" -> FailureDecision.blacklist("TOKEN_FAIL");
            case "FAIL_NETWORK" -> FailureDecision.exempt("NET_TIMEOUT");
            default -> FailureDecision.exempt("PARAM_ERROR");
        };
    }
}
