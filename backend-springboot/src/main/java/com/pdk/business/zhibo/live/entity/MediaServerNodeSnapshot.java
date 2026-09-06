package com.pdk.business.zhibo.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pdk_media_server_node_snapshot")
public class MediaServerNodeSnapshot {
    @TableId(type = IdType.INPUT)
    private Long nodeId;
    private LocalDateTime collectedAt;
    private String collectStatus;
    private Integer activePublishers;
    private Integer activeReaders;
    private Integer activePaths;
    private Long inboundBytesTotal;
    private Long outboundBytesTotal;
    private Long inboundBps;
    private Long outboundBps;
    private String providerVersion;
    private String errorMessage;
    private LocalDateTime updatedAt;
}
