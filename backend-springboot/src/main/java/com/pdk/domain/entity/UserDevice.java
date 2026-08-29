package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pdk_user_device")
public class UserDevice {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long bizId;
    private Long userId;
    private String deviceId;
    private String deviceIdHash;
    private String deviceName;
    private String platform;
    private String clientVersion;
    private String status;
    private LocalDateTime firstBoundAt;
    private LocalDateTime lastLoginAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime unboundAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
