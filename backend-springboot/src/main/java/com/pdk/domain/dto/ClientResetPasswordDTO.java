package com.pdk.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 客户端自助找回密码请求体（「忘记密码」入口）。
 *
 * <p>与 {@link ChangePasswordDTO} 不同，本接口<b>不需要旧密码</b>——用户恰恰是因为忘了旧密码才走这条路。
 * 身份核验改为「手机号 + 短信验证码」，短信用途固定为 {@code RESET_PASSWORD}，
 * 与注册验证码在 {@code (bizId, phone, purpose)} 维度隔离。</p>
 */
@Data
public class ClientResetPasswordDTO {
    @Positive(message = "appId 必须为正整数")
    private Long appId;

    @NotBlank
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号码格式错误")
    private String phone;

    @NotBlank(message = "短信验证码不能为空")
    private String smsCode;

    @NotBlank
    @Size(min = 8, max = 64, message = "新密码长度必须为8到64位")
    private String newPassword;
}
