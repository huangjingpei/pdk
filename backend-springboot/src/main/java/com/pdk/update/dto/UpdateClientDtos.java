package com.pdk.update.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

public final class UpdateClientDtos {
    private UpdateClientDtos() {}
    public record ArtifactView(Long artifactId, String platform, String arch, String packageType,
                               String fileName, Long fileSize, String sha256, String signatureAlgorithm,
                               String signature, String signingKeyId, String downloadUrl,
                               LocalDateTime downloadUrlExpiresAt) {}
    public record CheckResponse(String checkRequestId, Integer protocolVersion, Long appId, String bizCode,
            String channel, String platform, String arch, String currentVersion, String updaterVersion,
            boolean hasUpdate, String updatePolicy, String reason, String latestVersion,
            String minimumSupportedVersion, Long mandatoryReleaseId, String targetVersion,
            Long policyRevision, Integer checkIntervalSeconds, Integer offlineGraceHours,
            LocalDateTime policyIssuedAt, LocalDateTime policyExpiresAt,
            String policySignatureAlgorithm, String policySigningKeyId, String policySignature,
            Long releaseId, String releaseNotes, LocalDateTime publishedAt, LocalDateTime serverTime,
            ArtifactView artifact, String eventToken) {}
    public record EventRequest(@NotBlank String checkRequestId, @NotBlank String eventToken,
            Long artifactId, @NotBlank String eventType, String fromVersion, String targetVersion,
            String platform, String errorCategory, LocalDateTime clientTime) {}
}
