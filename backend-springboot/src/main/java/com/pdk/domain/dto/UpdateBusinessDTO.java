package com.pdk.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateBusinessDTO {
    @NotBlank @Size(max = 64)
    private String bizName;
    @Size(max = 255)
    private String description;
    @NotBlank @Pattern(regexp = "SELF_SERVICE|ADMIN_ONLY")
    private String registrationMode;
    @NotBlank @Pattern(regexp = "USER_SUBSCRIPTION|DEVICE_LICENSE")
    private String authorizationMode;
    private Boolean trialEnabled;
    @PositiveOrZero private Integer trialDurationHours;
    @PositiveOrZero private Integer trialAccountCount;
    @PositiveOrZero private Integer trialCallsPerAccount;
    private Boolean forceInitialPasswordChange;
}
