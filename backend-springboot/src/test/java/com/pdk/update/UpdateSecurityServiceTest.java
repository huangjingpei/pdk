package com.pdk.update;

import com.pdk.common.exception.BusinessException;
import com.pdk.update.config.ClientUpdateProperties;
import com.pdk.update.service.UpdateSecurityService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class UpdateSecurityServiceTest {
    private UpdateSecurityService service() {
        ClientUpdateProperties p=new ClientUpdateProperties();
        p.setRolloutHmacSecret("rollout-test-secret-with-at-least-32-bytes");
        p.setDownloadTokenSecret("download-test-secret-with-at-least-32-bytes");
        p.setEventTokenSecret("event-token-test-secret-at-least-32-bytes");
        return new UpdateSecurityService(p);
    }
    @Test void rolloutIsStableAndScoped() {
        var service=service(); String hash=service.anonymousDevice(3,"TEST-PC-001");
        assertEquals(service.rolloutBucket(3,120,hash),service.rolloutBucket(3,120,hash));
        assertNotEquals(service.anonymousDevice(3,"TEST-PC-001"),service.anonymousDevice(1,"TEST-PC-001"));
    }
    @Test void shortTokenRejectsTamperingAndExpiry() {
        var service=service(); String token=service.issueDownloadToken(3,301,Instant.now().plusSeconds(30).getEpochSecond());
        assertDoesNotThrow(()->service.verifyDownloadToken(token,3,301));
        assertThrows(BusinessException.class,()->service.verifyDownloadToken(token,3,302));
        String expired=service.issueDownloadToken(3,301,Instant.now().minusSeconds(1).getEpochSecond());
        assertThrows(BusinessException.class,()->service.verifyDownloadToken(expired,3,301));
    }
}
