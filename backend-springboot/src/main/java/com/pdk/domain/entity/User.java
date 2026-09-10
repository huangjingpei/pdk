package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("pdk_user")
public class User implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long bizId;
    private String phone;
    private String accountSource;
    private String status; // ACTIVE, TRIAL, FROZEN
    private String deviceId;
    private Integer currentPackageId;
    private String currentPackageName;
    private LocalDateTime expireTime;
    private Integer remainingCalls;
    private Integer dailyCallsLimit;
    private Integer maxAccounts;
    private Integer isTrialClaimed;

    @TableField(exist = false)
    private String roleCode;
    @TableField(exist = false)
    private String invitationCode;
    @TableField(exist = false)
    private String invitedByPhone;
    @TableField(exist = false)
    private Long appId;
    @TableField(exist = false)
    private String businessName;
    @TableField(exist = false)
    private String businessDescription;
    @TableField(exist = false)
    private Boolean mustChangePassword;
    /** 最近一次客户端登录成功时间，持久化在 pdk_user 表。 */
    private LocalDateTime lastLoginAt;
    /** 最近一次客户端登录成功 IP。 */
    private String lastLoginIp;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
