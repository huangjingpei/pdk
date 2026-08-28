package com.pdk.business.zhibo.live.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreatePublishTicketDTO(
        @Size(max = 64, message = "clientRequestId 最长 64 位") String clientRequestId,
        @Size(max = 128, message = "直播标题最长 128 位") String title,
        @Pattern(regexp = "(?i)RTMP", message = "当前部署 requestedProtocol 只支持 RTMP")
        String requestedProtocol) {
}
