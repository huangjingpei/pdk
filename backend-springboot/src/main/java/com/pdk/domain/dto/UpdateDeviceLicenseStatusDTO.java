package com.pdk.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateDeviceLicenseStatusDTO {
    @NotBlank @Pattern(regexp = "ACTIVE|SUSPENDED|REVOKED") private String status;
    @NotBlank @Size(min = 2, max = 255) private String reason;
}
