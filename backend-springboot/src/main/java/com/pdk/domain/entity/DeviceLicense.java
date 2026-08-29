package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pdk_device_license")
public class DeviceLicense {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long bizId;
    private Long userId;
    private Long cardKeyId;
    private Long userDeviceId;
    private Long packageId;
    private String packageNameSnapshot;
    private String status;
    private LocalDateTime activatedAt;
    private LocalDateTime effectiveAt;
    private LocalDateTime expireAt;
    private Integer remainingCalls;
    private Integer totalCalls;
    private LocalDateTime lastUsedAt;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
