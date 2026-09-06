package com.pdk.business.zhibo.live.media;

import com.pdk.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class MediaServerProviderRegistry {
    private final Map<String, MediaServerProvider> providers;

    public MediaServerProviderRegistry(List<MediaServerProvider> providers) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                value -> value.providerType().toUpperCase(Locale.ROOT), Function.identity()));
    }

    public MediaServerProvider require(String providerType) {
        MediaServerProvider provider = providers.get(providerType == null ? "" : providerType.toUpperCase(Locale.ROOT));
        if (provider == null) throw new BusinessException(40072, "不支持的流媒体服务器类型: " + providerType);
        return provider;
    }
}
