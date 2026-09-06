package com.pdk.business.zhibo.live.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MediaServerMonitorJob {
    private final MediaServerNodeService nodeService;

    @Scheduled(fixedDelayString = "${pdk.zhibo-live.media-monitor-interval-ms:15000}")
    public void collect() {
        nodeService.collectAll();
    }
}
