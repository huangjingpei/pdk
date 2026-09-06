package com.pdk.business.zhibo.live.vo;

import java.time.LocalDateTime;

public record LiveOverviewVO(
        long totalNodes,
        long availableNodes,
        long abnormalNodes,
        long activePublishers,
        long activeReaders,
        Long inboundBps,
        Long outboundBps,
        long todayStreamStarts,
        LocalDateTime collectedAt) {
}
