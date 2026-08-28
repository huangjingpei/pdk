package com.pdk.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminCreateAccountDTO(
        @NotBlank(message = "登录账号不能为空") String username,
        @NotBlank(message = "密码不能为空") @Size(min = 6, max = 64, message = "密码至少 6 位") String password,
        @NotBlank(message = "显示名称不能为空") String displayName,
        @NotBlank(message = "角色不能为空") String roleCode,
        Long bizId) {
}
