package com.pdk.business.zhibo.live.vo;

import java.time.LocalDateTime;
import java.util.List;

public record LiveMediaPublicVO(
        boolean enabled,
        String status,
        String mediaServerAddress,
        String defaultPublishProtocol,
        List<String> supportedPublishProtocols,
        List<String> supportedPlayProtocols,
        Long configRevision,
        LocalDateTime serverTime) {
}
