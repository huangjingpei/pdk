package com.pdk.update.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("pdk_client_artifact")
public class ClientArtifact {
    @TableId(type = IdType.AUTO) private Long id;
    private Long releaseId;
    private Long bizId;
    private String platform;
    private String arch;
    private String packageType;
    private String fileName;
    private String storageKey;
    private Long fileSize;
    private String sha256;
    private String signatureAlgorithm;
    private String signatureValue;
    private String signingKeyId;
    private String status;
    private String requestId;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
