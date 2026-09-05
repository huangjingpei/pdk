package com.pdk.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员自助改密请求体：当前已登录状态下输入旧密码 + 新密码。
 * 改密成功后强制下线，由用户用新密码重新登录。
 */
@Data
public class AdminChangePasswordDTO {
    @NotBlank(message = "当前密码不能为空")
    private String oldPassword;

    @NotBlank
    @Size(min = 8, max = 64, message = "新密码长度必须为 8 到 64 位")
    private String newPassword;
}
