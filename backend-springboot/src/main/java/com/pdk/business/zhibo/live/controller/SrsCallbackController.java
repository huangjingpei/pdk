package com.pdk.business.zhibo.live.controller;

import com.pdk.business.zhibo.live.dto.MediaMtxAuthRequest;
import com.pdk.business.zhibo.live.dto.SrsCallbackRequest;
import com.pdk.business.zhibo.live.service.LivePlaySessionService;
import com.pdk.business.zhibo.live.service.MediaMtxAuthResult;
import com.pdk.business.zhibo.live.service.MediaMtxAuthService;
import com.pdk.business.zhibo.live.service.MediaMtxEventService;
import com.pdk.business.zhibo.live.service.MediaServerNodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** SRS 回调协议要求成功时 HTTP 200 且 code=0，不能使用 CommonResult 包装。 */
@RestController
@RequestMapping("/api/v1/internal/srs")
@RequiredArgsConstructor
public class SrsCallbackController {
    private final MediaMtxAuthService authService;
    private final MediaMtxEventService eventService;
    private final LivePlaySessionService playService;
    private final MediaServerNodeService nodeService;

    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> callback(@RequestParam(required = false) String serviceToken,
                                                         @RequestParam String nodeCode,
                                                         @RequestBody(required = false) SrsCallbackRequest request) {
        if (request == null || request.action() == null) return denied(HttpStatus.BAD_REQUEST, "MISSING_CALLBACK");
        if (!eventService.trusted(serviceToken)) return denied(HttpStatus.FORBIDDEN, "UNTRUSTED_SRS");
        try {
            var node = nodeService.requireByCode(nodeCode);
            if (!"SRS".equals(node.getProviderType()) || "DISABLED".equals(node.getStatus())) {
                return denied(HttpStatus.FORBIDDEN, "SRS_NODE_DISABLED_OR_MISMATCH");
            }
            return switch (request.action()) {
                case "on_publish" -> publish(serviceToken, nodeCode, request);
                case "on_unpublish" -> accepted(eventService.unavailable(nodeCode, request.path(), request.streamId()));
                case "on_play" -> accepted(playService.started(nodeCode, request.path(), request.clientId(),
                        "SRS", request.ip()));
                case "on_stop" -> accepted(playService.stopped(nodeCode, request.path(), request.clientId(),
                        "READER_DISCONNECTED"));
                default -> denied(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CALLBACK");
            };
        } catch (RuntimeException e) {
            return denied(HttpStatus.FORBIDDEN, "CALLBACK_REJECTED");
        }
    }

    private ResponseEntity<Map<String, Object>> publish(String serviceToken, String nodeCode,
                                                         SrsCallbackRequest request) {
        MediaMtxAuthRequest auth = new MediaMtxAuthRequest("", "", null, request.ip(), "publish",
                request.path(), "rtmp", request.clientId(), request.param(), "SRS");
        MediaMtxAuthResult result = authService.authorizeProvider(serviceToken, nodeCode, "SRS", auth);
        if (!result.isAllowed()) return denied(result.status(), result.reason());
        return accepted(eventService.available(nodeCode, request.path(), request.streamId()));
    }

    private static ResponseEntity<Map<String, Object>> accepted(boolean accepted) {
        return accepted ? ResponseEntity.ok(Map.of("code", 0))
                : denied(HttpStatus.CONFLICT, "EVENT_NOT_ACCEPTED");
    }

    private static ResponseEntity<Map<String, Object>> denied(HttpStatus status, String reason) {
        return ResponseEntity.status(status).body(Map.of("code", 1, "message", reason));
    }
}
