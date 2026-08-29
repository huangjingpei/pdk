package com.pdk.domain.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class CreateBusinessDTO {
    @NotNull @Positive
    private Long appId;
    @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$")
    private String bizCode;
    @NotBlank @Size(max = 64)
    private String bizName;
    @Size(max = 255)
    private String description;
    @NotBlank @Pattern(regexp = "SELF_SERVICE|ADMIN_ONLY")
    private String registrationMode;
    @NotBlank @Pattern(regexp = "USER_SUBSCRIPTION|DEVICE_LICENSE")
    private String authorizationMode = "USER_SUBSCRIPTION";
    private Boolean trialEnabled = false;
    @PositiveOrZero private Integer trialDurationHours = 0;
    @PositiveOrZero private Integer trialAccountCount = 0;
    @PositiveOrZero private Integer trialCallsPerAccount = 0;
    private Boolean forceInitialPasswordChange = true;
}
