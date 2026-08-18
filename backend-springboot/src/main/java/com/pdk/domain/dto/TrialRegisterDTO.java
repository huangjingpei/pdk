package com.pdk.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class TrialRegisterDTO {
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不合规")
    private String phone;

    @NotBlank(message = "客户端设备ID不能为空")
    private String deviceId;

    @NotBlank(message = "短信验证码不能为空")
    private String smsCode;
}
