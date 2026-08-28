package com.pdk.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 管理员在后台手工创建客户端用户（客户账号）的请求体。
 *
 * <p>正常账号来源是客户端自助注册（短信 + 邀请码 + 试用），本 DTO 用于管理员为线下场景
 * （如 VIP 代开通、客服协助建号）直接建号。建号后套餐/次数可通过「调整套餐」接口补绑。
 */
@Data
public class AdminCreateUserDTO {
    @NotNull(message = "appId不能为空")
    @Positive(message = "appId必须为正整数")
    private Long appId;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "初始密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需在 8-64 位之间")
    private String password;

    /** 可选：预绑定设备 UUID（单机物理设备）。不填则登录时由客户端自行绑定。 */
    private String deviceId;
}
