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
    private String phone;
    private String status; // ACTIVE, TRIAL, FROZEN
    private String deviceId;
    private Integer currentPackageId;
    private String currentPackageName;
    private LocalDateTime expireTime;
    private Integer remainingCalls;
    private Integer dailyCallsLimit;
    private Integer maxAccounts;
    private Integer isTrialClaimed;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
