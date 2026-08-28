package com.pdk.business.zhibo.live.dto;

public record MediaMtxAuthRequest(
        String user,
        String password,
        String token,
        String ip,
        String action,
        String path,
        String protocol,
        String id,
        String query,
        String userAgent) {
}
