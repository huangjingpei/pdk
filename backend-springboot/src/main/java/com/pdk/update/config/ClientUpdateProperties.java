package com.pdk.update.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "pdk.client-update")
public class ClientUpdateProperties {
    private boolean enabled = true;
    private String storageRoot;
    private String publicBaseUrl;
    private String downloadTokenSecret;
    private String eventTokenSecret;
    private String rolloutHmacSecret;
    private String rolloutKeyVersion = "1";
    private String artifactPrivateKey;
    private String artifactKeyId;
    private String policyPrivateKey;
    private String policyKeyId;
    private long downloadUrlTtlSeconds = 600;
    private long policyTtlHours = 24;
}
