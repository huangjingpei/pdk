package com.pdk.business.zhibo.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pdk_live_play_session")
public class LivePlaySession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long bizId;
    private Long streamSessionId;
    private String mediaNodeCode;
    private String providerClientId;
    private String protocol;
    private String clientIpHash;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationSeconds;
    private Long outboundBytes;
    private String endReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
