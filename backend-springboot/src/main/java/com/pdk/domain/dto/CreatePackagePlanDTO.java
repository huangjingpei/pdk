package com.pdk.domain.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class CreatePackagePlanDTO {
    @NotBlank @Size(max = 64) private String name;
    @NotNull @DecimalMin("0.01") private BigDecimal listPrice;
    @NotNull @DecimalMin("0.01") @DecimalMax("100.00") private BigDecimal discountRate;
    @NotNull @Min(1) private Integer durationHours;
    @NotNull @Min(1) @Max(1000) private Integer accountCount;
    @NotNull @Min(1) @Max(1000000) private Integer callsPerAccount;
    @Size(max = 255) private String description;
}
