package com.pdk.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ClientRegisterDTO {
    @NotBlank
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号码格式错误")
    private String phone;
    @NotBlank(message = "短信验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "短信验证码必须为6位数字")
    private String smsCode;
    @NotBlank(message = "登录密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度必须为8到64位")
    private String password;
    @NotBlank(message = "设备ID不能为空")
    private String deviceId;
    @Pattern(regexp = "^[A-Za-z0-9]{6,20}$", message = "邀请码格式错误")
    private String invitationCode;
}
