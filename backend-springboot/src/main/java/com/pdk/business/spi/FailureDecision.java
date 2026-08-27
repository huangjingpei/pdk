package com.pdk.business.spi;

/**
 * 客户端上报结果经过业务 Handler 归一化后的平台处理决定。
 */
public record FailureDecision(String execStatus, boolean deductQuota, boolean blacklistResource) {
    public static FailureDecision success() {
        return new FailureDecision("SUCCESS", true, false);
    }

    public static FailureDecision exempt(String execStatus) {
        return new FailureDecision(execStatus, false, false);
    }

    public static FailureDecision blacklist(String execStatus) {
        return new FailureDecision(execStatus, false, true);
    }
}
