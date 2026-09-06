package com.pdk.business.zhibo.live.media;

public record MediaNodeSnapshotData(
        Integer activePublishers,
        Integer activeReaders,
        Integer activePaths,
        Long inboundBytesTotal,
        Long outboundBytesTotal,
        String providerVersion) {
}
