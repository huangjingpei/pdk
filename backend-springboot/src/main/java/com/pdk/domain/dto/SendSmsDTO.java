package com.pdk.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class SendSmsDTO {
    @Positive(message = "appId 必须为正整数")
    private Long appId;
    @NotBlank
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号码格式错误")
    private String phone;
    @Pattern(regexp = "REGISTER|RESET_PASSWORD", message = "短信用途不合法")
    private String purpose = "REGISTER";
}
