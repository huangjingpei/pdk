package com.pdk.business.zhibo.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pdk_live_stream_session")
public class LiveStreamSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long bizId;
    private Long userId;
    private String streamSessionNo;
    private String clientRequestId;
    private String mediaNodeCode;
    private String path;
    private String protocol;
    private String status;
    private String ticketHash;
    private LocalDateTime ticketExpiresAt;
    private String deviceIdHash;
    private String clientIp;
    private String mediamtxConnectionId;
    private String mediamtxSourceId;
    private LocalDateTime authorizedAt;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationSeconds;
    private Integer billedUnits;
    private String endReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
