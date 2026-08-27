package com.pdk.business.pdd;

import com.pdk.domain.entity.TokenPool;
import org.springframework.stereotype.Component;

/** 保持现有 appId=1 客户端解密后所期待的 token/leaseId/expire JSON 结构。 */
@Component
public class PddCredentialCodec {
    public String encode(TokenPool resource, String leaseTraceId, long leaseSeconds) {
        return "{\"token\":\"" + escape(resource.getTokenVal()) + "\",\"leaseId\":\""
                + escape(leaseTraceId) + "\",\"expire\":" + leaseSeconds + "}";
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
