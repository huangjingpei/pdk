package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pdk_admin_user")
public class AdminUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** SUPER_ADMIN 为空；PARTNER 必须绑定一个业务。 */
    private Long bizId;
    private String username;
    private String passwordHash;
    private String displayName;
    private String roleCode;
    private String status;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
