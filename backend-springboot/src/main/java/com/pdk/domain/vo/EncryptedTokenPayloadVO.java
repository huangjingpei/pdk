package com.pdk.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EncryptedTokenPayloadVO {
    private String encryptedPayload; // Base64(Flip(Magic + IV + AES_GCM(Token)))
    private String leaseTraceId;
    private Long expireAtTimestamp;
    private Integer remainingUserQuota;
    private Integer dailyQuotaLimit;
}
