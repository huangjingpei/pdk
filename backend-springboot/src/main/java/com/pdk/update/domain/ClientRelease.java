package com.pdk.update.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("pdk_client_release")
public class ClientRelease {
    @TableId(type = IdType.AUTO) private Long id;
    private Long bizId;
    private String version;
    private Integer versionMajor;
    private Integer versionMinor;
    private Integer versionPatch;
    private String channel;
    private Integer minimumProtocolVersion;
    private String minimumUpdaterVersion;
    private String releaseNotes;
    private String status;
    private Integer rolloutPercentage;
    private Integer everPublished;
    private LocalDateTime publishedAt;
    private String createdBy;
    private String updatedBy;
    private String publishedBy;
    private String requestId;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
