package com.pdk.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class BatchRenewDeviceLicenseDTO {
    @NotEmpty private List<@NotNull Long> licenseIds;
    @Valid @NotNull private RenewDeviceLicenseDTO renewal;
}
