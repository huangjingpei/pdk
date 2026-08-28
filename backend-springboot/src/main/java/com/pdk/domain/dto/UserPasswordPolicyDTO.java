package com.pdk.domain.dto;

import lombok.Data;

/**
 * 切换单个客户「强制下次登录改密」标记的请求体。
 *
 * <p>置 {@code true} 时，该客户下次登录会被要求先改密（典型场景：疑似泄露、管理员代重置后）；
 * 置 {@code false} 时取消强制（典型场景：已确认安全、用户抱怨频繁提示改密）。</p>
 */
@Data
public class UserPasswordPolicyDTO {
    private boolean mustChange;
}
