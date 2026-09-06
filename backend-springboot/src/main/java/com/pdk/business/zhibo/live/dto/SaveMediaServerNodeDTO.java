package com.pdk.business.zhibo.live.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SaveMediaServerNodeDTO(
        @NotNull Long bizId,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{1,62}[A-Za-z0-9]$") String nodeCode,
        @NotBlank @Size(max = 100) String nodeName,
        @NotBlank @Pattern(regexp = "(?i)MEDIAMTX|SRS") String providerType,
        @Size(max = 32) String regionCode,
        @NotBlank @Size(max = 255) String publicPublishBaseUrl,
        @Size(max = 255) String publicHlsBaseUrl,
        @Size(max = 255) String publicWebrtcBaseUrl,
        @NotBlank @Size(max = 255) String internalApiBaseUrl,
        @Size(max = 255) String internalMetricsUrl,
        @Size(max = 128) String secretRef,
        @NotBlank @Size(max = 128) String supportedPublishProtocols,
        @Size(max = 128) String supportedPlayProtocols,
        @Min(1) @Max(10000) Integer weight,
        @Min(1) @Max(100000) Integer maxPublishers,
        @Min(1) @Max(10000000) Integer maxReaders) {
}
