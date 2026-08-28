package com.pdk.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员代客户重置密码请求体。
 *
 * <p>userId 通过路径参数传入（受业务鉴权范围约束）。重置成功后强制用户下次登录改密，
 * 因此管理员设置的临时密码不会长期有效，降低「管理员知道明文密码」带来的风险。</p>
 */
@Data
public class AdminResetPasswordDTO {
    @NotBlank
    @Size(min = 8, max = 64, message = "新密码长度必须为8到64位")
    private String newPassword;
}
