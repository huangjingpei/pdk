package com.pdk.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class TokenResourceDTO {
    @NotNull(message = "appId不能为空")
    @Positive(message = "appId必须为正整数")
    private Long appId;

    @NotBlank(message = "资源账号别名不能为空")
    @Size(max = 64, message = "资源账号别名不能超过64个字符")
    private String accountAlias;

    @NotBlank(message = "Session Token 不能为空")
    @Size(max = 512, message = "Session Token 不能超过512个字符")
    private String tokenVal;

    @Size(max = 32, message = "凭证类型不能超过32个字符")
    private String credentialType = "TOKEN";

    @Min(value = 1, message = "每日容量至少为1")
    @Max(value = 100000, message = "每日容量不能超过100000")
    private Integer dailyMaxCapacity = 500;
}
