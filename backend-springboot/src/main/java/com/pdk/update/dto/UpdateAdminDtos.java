package com.pdk.update.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;

public final class UpdateAdminDtos {
    private UpdateAdminDtos() {}
    public record CreateRelease(
            @NotNull @Positive Long appId,
            @NotBlank String version,
            @NotBlank String channel,
            @NotNull @Min(1) Integer minimumProtocolVersion,
            @NotBlank String minimumUpdaterVersion,
            String releaseNotes,
            @NotNull @Min(0) @Max(100) Integer rolloutPercentage,
            @NotBlank @Size(max=64) String requestId) {}
    public record EditRelease(String releaseNotes, @Min(0) @Max(100) Integer rolloutPercentage,
                              Integer minimumProtocolVersion, String minimumUpdaterVersion,
                              @NotBlank @Size(max=64) String requestId) {}
    public record CreateArtifact(@NotBlank String platform, @NotBlank String arch,
                                 @NotBlank String packageType, @NotBlank @Size(max=180) String fileName,
                                 @NotBlank @Size(max=64) String requestId) {}
    public record Action(@NotBlank @Size(max=64) String requestId, @NotBlank @Size(max=500) String reason) {}
    public record UpdateRollout(@NotNull @Min(0) @Max(100) Integer rolloutPercentage,
                                @NotBlank @Size(max=64) String requestId,
                                @NotBlank @Size(max=500) String reason) {}
    public record CreateDownloadLink(@NotNull @Min(1) @Max(168) Integer validHours,
                                     @NotBlank @Size(max=64) String requestId,
                                     @NotBlank @Size(max=500) String reason) {}
    public record DownloadLinkView(Long artifactId, Long releaseId, Long appId, String version,
                                   String fileName, String downloadUrl, LocalDateTime expiresAt) {}
    public record SavePolicy(
            @NotBlank String channel, @NotBlank String platform, @NotBlank String arch,
            @NotNull Boolean updateEnabled, String minimumSupportedVersion, Long mandatoryReleaseId,
            @NotNull Boolean serverEnforcementEnabled,
            @NotNull @Min(0) @Max(720) Integer offlineGraceHours,
            @NotNull @Min(60) @Max(86400) Integer checkIntervalSeconds,
            Long policyRevision, @NotBlank @Size(max=64) String requestId,
            @NotBlank @Size(max=500) String reason) {}
}
