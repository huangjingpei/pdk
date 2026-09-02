package com.pdk.update.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("pdk_client_update_policy")
public class ClientUpdatePolicy {
    @TableId(type = IdType.AUTO) private Long id;
    private Long bizId;
    private String channel;
    private String platform;
    private String arch;
    private Integer updateEnabled;
    private String minimumSupportedVersion;
    private Long mandatoryReleaseId;
    private Integer serverEnforcementEnabled;
    private Integer offlineGraceHours;
    private Integer checkIntervalSeconds;
    private Long policyRevision;
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
