package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pdk_business")
public class Business {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long appId;
    private String bizCode;
    private String bizName;
    private String description;
    private String registrationMode;
    private String authorizationMode;
    private Integer trialEnabled;
    private Integer trialDurationHours;
    private Integer trialAccountCount;
    private Integer trialCallsPerAccount;
    private Integer forceInitialPasswordChange;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
