package com.pdk.config;

/**
 * 平台系统配置键与默认值集中定义。
 * 新增配置项：在此加一个常量 + 一条 schema 种子数据即可，前端按 schema 自动渲染，无需改表结构。
 */
public final class ConfigKeys {
    // ---- 配置键 ----
    public static final String TOKEN_ALLOCATION_MODE = "token.allocation.mode";
    public static final String SMS_REGISTER_ENABLED = "sms.register.enabled";
    public static final String SECURITY_ENCRYPTION_ENABLED = "security.encryption.enabled";
    public static final String TRIAL_DAYS = "trial.days";
    public static final String DEVICE_KICKOUT_ENABLED = "device.kickout.enabled";
    public static final String HEARTBEAT_INTERVAL_SECONDS = "heartbeat.interval.seconds";

    // ---- 默认值（数据库无记录时回退） ----
    public static final String DEFAULT_TOKEN_ALLOCATION_MODE = "FIXED";
    public static final boolean DEFAULT_SMS_REGISTER_ENABLED = false;
    public static final boolean DEFAULT_SECURITY_ENCRYPTION_ENABLED = true;
    public static final int DEFAULT_TRIAL_DAYS = 1;
    public static final boolean DEFAULT_DEVICE_KICKOUT_ENABLED = true;
    public static final int DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 45;

    // ---- 取值辅助 ----
    public static boolean isPollingAllocation(String mode) {
        return "POLLING".equalsIgnoreCase(mode);
    }

    public static boolean isSmsRegisterEnabled(String value) {
        return Boolean.parseBoolean(value);
    }

    public static boolean isSecurityEncryptionEnabled(String value) {
        return Boolean.parseBoolean(value);
    }

    private ConfigKeys() {
    }
}
