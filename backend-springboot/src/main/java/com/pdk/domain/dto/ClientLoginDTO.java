package com.pdk.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ClientLoginDTO {
    @Positive(message = "appId 必须为正整数")
    private Long appId;
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号码格式错误")
    private String phone;

    @NotBlank(message = "设备ID不能为空")
    private String deviceId;

    @NotBlank(message = "登录密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度必须为8到64位")
    private String password;
}
