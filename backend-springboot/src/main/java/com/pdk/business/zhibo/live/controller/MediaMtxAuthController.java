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
                                     @RequestBody(required = false) MediaMtxAuthRequest request) {
        MediaMtxAuthResult result = authService.authorize(serviceToken, request);
        return ResponseEntity.status(result.status())
                .header("X-PDK-MediaMTX-Reason", result.reason())
                .build();
    }
}
