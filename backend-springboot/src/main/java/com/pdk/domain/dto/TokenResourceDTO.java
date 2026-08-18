package com.pdk.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TokenResourceDTO {
    @NotBlank(message = "资源账号别名不能为空")
    @Size(max = 64, message = "资源账号别名不能超过64个字符")
    private String accountAlias;

    @NotBlank(message = "Session Token 不能为空")
    @Size(max = 512, message = "Session Token 不能超过512个字符")
    private String tokenVal;

    @Min(value = 1, message = "每日容量至少为1")
    @Max(value = 100000, message = "每日容量不能超过100000")
    private Integer dailyMaxCapacity = 500;
}
