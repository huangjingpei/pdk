package com.pdk.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@Builder
public class BusinessRuntimeVO {
    private Long bizId;
    private Long appId;
    private String bizCode;
    private String businessName;
    private String businessDescription;
    private String registrationMode;
    private Boolean trialEnabled;
    private Integer trialDurationHours;
    private Integer trialAccountCount;
    private Integer trialCallsPerAccount;
    private Boolean forceInitialPasswordChange;
    private String configuredStatus;
    private Boolean deploymentEnabled;
    private Boolean handlerRegistered;
    private String handlerHealth;
    private Set<String> supportedActions;
    private String effectiveStatus;
    private String unavailableReason;
    private Long userCount;
    private Long packageCount;
    private Long resourceCount;
    private Long availableResourceCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
