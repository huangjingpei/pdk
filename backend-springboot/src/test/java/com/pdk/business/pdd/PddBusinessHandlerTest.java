package com.pdk.business.pdd;

import com.pdk.business.spi.FailureDecision;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.AcquireTokenRequestDTO;
import com.pdk.domain.dto.ReportResultDTO;
import com.pdk.domain.entity.TokenPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PddBusinessHandlerTest {
    private final PddBusinessHandler handler = new PddBusinessHandler(
            new PddActionValidator(), new PddCredentialCodec(), new PddFailureClassifier());

    @Test
    void exposesStableBizCodeAndValidatesPddActions() {
        assertEquals("PDD", handler.bizCode());
        AcquireTokenRequestDTO valid = new AcquireTokenRequestDTO();
        valid.setActionType("GOODS_COLLECT");
        assertDoesNotThrow(() -> handler.validateAcquire(valid));

        AcquireTokenRequestDTO invalid = new AcquireTokenRequestDTO();
        invalid.setActionType("ZHIBO_START");
        BusinessException error = assertThrows(BusinessException.class,
                () -> handler.validateAcquire(invalid));
        assertEquals(40001, error.getCode());
    }

    @Test
    void buildsExistingPddCredentialPayload() {
        TokenPool token = new TokenPool();
        token.setTokenVal("secret-token");
        assertEquals("{\"token\":\"secret-token\",\"leaseId\":\"TRACE-1\",\"expire\":300}",
                handler.buildCredentialPayload(token, "TRACE-1", 300));
    }

    @Test
    void mapsClientResultsToPlatformDecisions() {
        assertDecision("SUCCESS", "SUCCESS", true, false);
        assertDecision("FAIL_ACCOUNT_BANNED", "TOKEN_FAIL", false, true);
        assertDecision("FAIL_NETWORK", "NET_TIMEOUT", false, false);
        assertDecision("FAIL_BUSINESS", "PARAM_ERROR", false, false);
    }

    private void assertDecision(String status, String execStatus, boolean deduct, boolean blacklist) {
        ReportResultDTO report = new ReportResultDTO();
        report.setStatus(status);
        FailureDecision decision = handler.classifyReport(report);
        assertEquals(execStatus, decision.execStatus());
        assertEquals(deduct, decision.deductQuota());
        assertEquals(blacklist, decision.blacklistResource());
    }
}
