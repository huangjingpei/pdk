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
    /** 与 artifactPrivateKey 配对的公钥（SPKI base64）；声明后启动期会校验密钥对一致性。 */
    private String artifactPublicKey;
    private String policyPrivateKey;
    private String policyKeyId;
    /** 与 policyPrivateKey 配对的公钥（SPKI base64）；声明后启动期会校验密钥对一致性。 */
    private String policyPublicKey;
    private long downloadUrlTtlSeconds = 600;
    private long policyTtlHours = 24;
}
