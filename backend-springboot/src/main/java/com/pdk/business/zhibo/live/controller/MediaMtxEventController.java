package com.pdk.business.zhibo.live.controller;

import com.pdk.business.zhibo.live.service.MediaMtxEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/internal/mediamtx/events")
@RequiredArgsConstructor
public class MediaMtxEventController {
    private final MediaMtxEventService eventService;

    @PostMapping("/available")
    public ResponseEntity<Void> available(@RequestParam(required = false) String serviceToken,
                                          @RequestParam String path,
                                          @RequestParam(required = false, defaultValue = "") String sourceId) {
        return event("available", serviceToken, path, sourceId);
    }

    @PostMapping("/unavailable")
    public ResponseEntity<Void> unavailable(@RequestParam(required = false) String serviceToken,
                                            @RequestParam String path,
                                            @RequestParam(required = false, defaultValue = "") String sourceId) {
        return event("unavailable", serviceToken, path, sourceId);
    }

    private ResponseEntity<Void> event(String type, String serviceToken, String path, String sourceId) {
        if (!eventService.trusted(serviceToken)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            boolean accepted = "available".equals(type)
                    ? eventService.available(path, sourceId) : eventService.unavailable(path, sourceId);
            return ResponseEntity.status(accepted ? HttpStatus.NO_CONTENT : HttpStatus.CONFLICT).build();
        } catch (RuntimeException ignored) {
            // MediaMTX hook 只依赖 HTTP 状态；内部接口不得被全局 CommonResult 包装成 HTTP 200。
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }
}
