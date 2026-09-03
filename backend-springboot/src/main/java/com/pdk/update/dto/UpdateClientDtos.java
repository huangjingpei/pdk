package com.pdk.update.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

public final class UpdateClientDtos {
    private UpdateClientDtos() {}

    // 所有时间字段统一用固定 9 位纳秒精度序列化，与服务端签名用的 CANONICAL_TS 完全一致，
    // 否则客户端用返回的时间戳验签会因精度不同源而失败。
    private static final String TS_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS";

    public record ArtifactView(Long artifactId, String platform, String arch, String packageType,
                               String fileName, Long fileSize, String sha256, String signatureAlgorithm,
                               String signature, String signingKeyId, String downloadUrl,
                               @JsonFormat(pattern = TS_PATTERN) LocalDateTime downloadUrlExpiresAt) {}
    public record CheckResponse(String checkRequestId, Integer protocolVersion, Long appId, String bizCode,
            String channel, String platform, String arch, String currentVersion, String updaterVersion,
            boolean hasUpdate, String updatePolicy, String reason, String latestVersion,
            String minimumSupportedVersion, Long mandatoryReleaseId, String targetVersion,
            Long policyRevision, Integer checkIntervalSeconds, Integer offlineGraceHours,
            @JsonFormat(pattern = TS_PATTERN) LocalDateTime policyIssuedAt,
            @JsonFormat(pattern = TS_PATTERN) LocalDateTime policyExpiresAt,
            String policySignatureAlgorithm, String policySigningKeyId, String policySignature,
            Long releaseId, String releaseNotes,
            @JsonFormat(pattern = TS_PATTERN) LocalDateTime publishedAt,
            @JsonFormat(pattern = TS_PATTERN) LocalDateTime serverTime,
            ArtifactView artifact, String eventToken) {}
    public record EventRequest(@NotBlank String checkRequestId, @NotBlank String eventToken,
            Long artifactId, @NotBlank String eventType, String fromVersion, String targetVersion,
            String platform, String errorCategory, LocalDateTime clientTime) {}
}
