package com.pdk.business.zhibo.live.service;

import org.springframework.http.HttpStatus;

public record MediaMtxAuthResult(HttpStatus status, String reason) {
    public static MediaMtxAuthResult allowed() {
        return new MediaMtxAuthResult(HttpStatus.NO_CONTENT, "ALLOWED");
    }

    public static MediaMtxAuthResult denied(HttpStatus status, String reason) {
        return new MediaMtxAuthResult(status, reason);
    }

    public boolean isAllowed() {
        return status.is2xxSuccessful();
    }
}
