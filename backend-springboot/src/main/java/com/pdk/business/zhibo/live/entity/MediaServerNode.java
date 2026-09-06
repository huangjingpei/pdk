package com.pdk.business.zhibo.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pdk_media_server_node")
public class MediaServerNode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long bizId;
    private String nodeCode;
    private String nodeName;
    private String providerType;
    private String regionCode;
    private String publicPublishBaseUrl;
    private String publicHlsBaseUrl;
    private String publicWebrtcBaseUrl;
    private String internalApiBaseUrl;
    private String internalMetricsUrl;
    private String secretRef;
    private String supportedPublishProtocols;
    private String supportedPlayProtocols;
    private Integer weight;
    private Integer maxPublishers;
    private Integer maxReaders;
    private String status;
    private String healthStatus;
    private LocalDateTime lastHealthAt;
    private String lastHealthError;
    private Long configRevision;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
