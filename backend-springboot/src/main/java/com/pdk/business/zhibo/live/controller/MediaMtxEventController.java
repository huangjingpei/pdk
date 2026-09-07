package com.pdk.business.zhibo.live.controller;

import com.pdk.business.zhibo.live.service.MediaMtxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/internal/mediamtx/events")
@RequiredArgsConstructor
public class MediaMtxEventController {
    private final MediaMtxEventService eventService;

    @PostMapping("/available")
    public ResponseEntity<Void> available(@RequestParam(required = false) String serviceToken,
                                          @RequestParam(required = false) String nodeCode,
                                          @RequestParam String path,
                                          @RequestParam(required = false, defaultValue = "") String sourceId) {
        return event("available", serviceToken, nodeCode, path, sourceId, null, null, null);
    }

    @PostMapping("/unavailable")
    public ResponseEntity<Void> unavailable(@RequestParam(required = false) String serviceToken,
                                            @RequestParam(required = false) String nodeCode,
                                            @RequestParam String path,
                                            @RequestParam(required = false, defaultValue = "") String sourceId) {
        return event("unavailable", serviceToken, nodeCode, path, sourceId, null, null, null);
    }

    @PostMapping("/read")
    public ResponseEntity<Void> read(@RequestParam(required = false) String serviceToken,
                                     @RequestParam(required = false) String nodeCode,
                                     @RequestParam String path,
                                     @RequestParam String readerId,
                                     @RequestParam(required = false) String readerType,
                                     @RequestParam(required = false) String clientIp) {
        return event("read", serviceToken, nodeCode, path, null, readerId, readerType, clientIp);
    }

    @PostMapping("/unread")
    public ResponseEntity<Void> unread(@RequestParam(required = false) String serviceToken,
                                       @RequestParam(required = false) String nodeCode,
                                       @RequestParam String path,
                                       @RequestParam String readerId) {
        return event("unread", serviceToken, nodeCode, path, null, readerId, null, null);
    }

    private ResponseEntity<Void> event(String type, String serviceToken, String nodeCode, String path,
                                       String sourceId, String readerId, String readerType, String clientIp) {
        log.info("[mtx-event] type={} nodeCode={} path={} sourceId={} readerId={} readerType={} clientIp={} tokenPresent={}",
                type, nodeCode, path, sourceId, readerId, readerType, clientIp,
                serviceToken != null && !serviceToken.isBlank());
        boolean trusted = nodeCode == null || nodeCode.isBlank()
                ? eventService.trusted(serviceToken) : eventService.trusted(serviceToken, nodeCode);
        if (!trusted) {
            log.warn("[mtx-event] type={} 鉴权失败（serviceToken 或 nodeCode 无效）", type);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            boolean accepted = switch (type) {
                case "available" -> nodeCode == null || nodeCode.isBlank()
                        ? eventService.available(path, sourceId) : eventService.available(nodeCode, path, sourceId);
                case "unavailable" -> nodeCode == null || nodeCode.isBlank()
                        ? eventService.unavailable(path, sourceId) : eventService.unavailable(nodeCode, path, sourceId);
                case "read" -> eventService.read(nodeCode, path, readerId, readerType, clientIp);
                case "unread" -> eventService.unread(nodeCode, path, readerId);
                default -> false;
            };
            log.info("[mtx-event] type={} path={} result={} ({})", type, path, accepted ? "ACCEPTED" : "REJECTED",
                    accepted ? "会话状态已更新" : "业务冲突（会话/路径不存在或状态不允许）");
            return ResponseEntity.status(accepted ? HttpStatus.NO_CONTENT : HttpStatus.CONFLICT).build();
        } catch (RuntimeException e) {
            log.warn("[mtx-event] type={} path={} 业务异常: {}", type, path, e.getMessage());
            // MediaMTX hook 只依赖 HTTP 状态；内部接口不得被全局 CommonResult 包装成 HTTP 200。
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }
}
