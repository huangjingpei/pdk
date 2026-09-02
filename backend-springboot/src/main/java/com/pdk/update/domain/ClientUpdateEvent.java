package com.pdk.update.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("pdk_client_update_event")
public class ClientUpdateEvent {
    @TableId(type = IdType.AUTO) private Long id;
    private Long bizId;
    private Long releaseId;
    private Long artifactId;
    private String deviceIdHash;
    private String rolloutKeyVersion;
    private String fromVersion;
    private String targetVersion;
    private String platform;
    private String eventType;
    private String errorCategory;
    private LocalDateTime clientTime;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    private String checkRequestId;
}
