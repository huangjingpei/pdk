package com.pdk.business.zhibo.live.controller;

import com.pdk.business.zhibo.live.dto.MediaMtxAuthRequest;
import com.pdk.business.zhibo.live.service.MediaMtxAuthResult;
import com.pdk.business.zhibo.live.service.MediaMtxAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/internal/mediamtx")
@RequiredArgsConstructor
public class MediaMtxAuthController {
    private final MediaMtxAuthService authService;

    @PostMapping("/auth")
    public ResponseEntity<Void> auth(@RequestParam(required = false) String serviceToken,
                                     @RequestParam(required = false) String nodeCode,
                                     @RequestBody(required = false) MediaMtxAuthRequest request) {
        MediaMtxAuthResult result = nodeCode == null || nodeCode.isBlank()
                ? authService.authorize(serviceToken, request)
                : authService.authorize(serviceToken, nodeCode, request);
        return ResponseEntity.status(result.status())
                .header("X-PDK-MediaMTX-Reason", result.reason())
                .build();
    }
}
