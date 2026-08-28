package com.pdk.business.zhibo.live.vo;

import java.time.LocalDateTime;

public record PublishTicketVO(
        String streamSessionNo,
        String publishUrl,
        LocalDateTime expiresAt,
        long ticketTtlSeconds,
        String status) {
}
