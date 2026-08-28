package com.pdk.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "pdk.business")
public class BusinessDeploymentProperties {
    private String enabledCodes = "PDD";
    private long legacyDefaultAppId = 1L;
    private boolean allowLegacyMissingAppId = true;
    private boolean failFastActiveUnsupported = false;

    public Set<String> normalizedEnabledCodes() {
        Set<String> result = new LinkedHashSet<>();
        if (enabledCodes == null) return result;
        Arrays.stream(enabledCodes.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .forEach(value -> {
                    if ("ZHIBO".equals(value)) {
                        result.add("ZHIBO_AI");
                        result.add("ZHIBO_LIVE");
                    } else {
                        result.add(value);
                    }
                });
        return result;
    }
}
