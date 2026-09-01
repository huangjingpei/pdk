package com.pdk.config;

/**
 * 平台系统配置键与默认值集中定义。
 * 新增配置项：在此加一个常量 + 一条 schema 种子数据即可，前端按 schema 自动渲染，无需改表结构。
 */
public final class ConfigKeys {
    // ---- 配置键 ----
    /**
     * 账号小号 Token 使用方式。
     * 注意：当前系统【仅 FIXED(固定分配) 生效】——激活时把小号独占绑定给用户，
     * 调度只从用户已独占的 assignment 中选取。POLLING(轮询/公共池动态分配) 为【预留未启用】，
     * 无任何代码分支消费该值，切勿在激活/调度逻辑中假定它会被读取。
     */
    public static final String TOKEN_ALLOCATION_MODE = "token.allocation.mode";
    public static final String SMS_REGISTER_ENABLED = "sms.register.enabled";
    /**
     * 协议安全加密模式（三态）：
     * off    = 关闭，明文/信封都不处理（兼容旧客户端）
     * optional = 灰度：信封请求解密、明文请求放行；响应仅在请求加密时加密
     * force  = 强制：仅接受加密信封，拒绝明文
     */
    public static final String SECURITY_ENCRYPTION_MODE = "security.encryption.mode";
    public static final String TRIAL_DAYS = "trial.days";
    public static final String DEVICE_KICKOUT_ENABLED = "device.kickout.enabled";
    public static final String HEARTBEAT_INTERVAL_SECONDS = "heartbeat.interval.seconds";

    // ---- 默认值（数据库无记录时回退） ----
    public static final String DEFAULT_TOKEN_ALLOCATION_MODE = "FIXED";
    public static final boolean DEFAULT_SMS_REGISTER_ENABLED = false;
    public static final String DEFAULT_SECURITY_ENCRYPTION_MODE = "optional";
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

    private ConfigKeys() {
    }
}
