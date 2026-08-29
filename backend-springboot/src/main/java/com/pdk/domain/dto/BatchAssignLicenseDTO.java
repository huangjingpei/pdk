package com.pdk.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BatchAssignLicenseDTO {
    @NotNull private Integer packageId;
    @NotNull @Min(1) @Max(500) private Integer count;
    @Size(max = 255) private String remark;
}
