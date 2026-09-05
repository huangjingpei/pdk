package com.pdk.update.controller;

import com.pdk.common.api.CommonResult;
import com.pdk.common.exception.BusinessException;
import com.pdk.update.domain.ClientArtifact;
import com.pdk.update.dto.UpdateClientDtos.*;
import com.pdk.update.service.ClientUpdateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/v1/client/updates")
@RequiredArgsConstructor
public class ClientUpdateController {
    private final ClientUpdateService updateService;

    @GetMapping("/check")
    public CommonResult<CheckResponse> check(HttpServletRequest request,
            @RequestParam String currentVersion, @RequestParam String platform, @RequestParam String arch,
            @RequestParam(defaultValue="STABLE") String channel, @RequestParam(defaultValue="1") int protocolVersion,
            @RequestParam(defaultValue="1.0.0") String updaterVersion) {
        long appId = appId(request);
        return CommonResult.success(updateService.check(appId, request.getHeader("X-PDK-Device-ID"), currentVersion,
                platform, arch, channel, protocolVersion, updaterVersion));
    }

    @PostMapping("/events")
    public CommonResult<String> event(HttpServletRequest request, @Valid @RequestBody EventRequest body) {
        updateService.recordEvent(appId(request), request.getHeader("X-PDK-Device-ID"), body);
        return CommonResult.success("升级事件已接收");
    }

    /**
     * 构件下载（支持断点续传）。
     *
     * 直接写 HttpServletResponse 而不返回 {@code ResourceRegion}，原因有两个：
     * 1) 返回 void 可让全局加密 Advice（ClientCryptoAdvice）完全不介入二进制流，
     *    避免它对下载流做 JSON 序列化导致 500；
     * 2) 一旦写入中途出错，响应头尚未提交，异常处理器能正常返回错误 JSON，
     *    不会出现「状态码已 206、响应体却是错误 JSON」这种客户端无法识别的坏响应。
     */
    @RequestMapping(value="/download/{artifactId}", method={RequestMethod.GET, RequestMethod.HEAD})
    public void download(@PathVariable long artifactId, @RequestParam long appId, @RequestParam String token,
                         @RequestHeader HttpHeaders headers, HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        Path path = updateService.artifactPath(appId, artifactId, token);
        ClientArtifact artifact = updateService.requireArtifactById(artifactId);
        long length = Files.size(path);

        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("ETag", "\"" + artifact.getSha256() + "\"");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader("Content-Disposition",
                ContentDisposition.attachment().filename(artifact.getFileName(), StandardCharsets.UTF_8).build().toString());

        if ("HEAD".equalsIgnoreCase(request.getMethod())) {
            response.setContentLengthLong(length);
            response.setStatus(HttpStatus.OK.value());
            return;
        }

        List<HttpRange> ranges;
        try { ranges = headers.getRange(); }
        catch (IllegalArgumentException e) { ranges = List.of(); }

        long start, end;
        boolean partial;
        if (ranges.isEmpty()) {
            start = 0; end = length - 1; partial = false;
        } else {
            HttpRange range = ranges.get(0);
            try {
                start = range.getRangeStart(length);
                end = range.getRangeEnd(length);
            } catch (IllegalArgumentException e) {
                writeRangeNotSatisfiable(response, length);
                return;
            }
            // Range 起点越界或区间非法：按 RFC 7233 返回 416，而不是 500。
            if (start >= length || start > end) {
                writeRangeNotSatisfiable(response, length);
                return;
            }
            partial = true;
        }

        long count = end - start + 1;
        response.setStatus(partial ? HttpStatus.PARTIAL_CONTENT.value() : HttpStatus.OK.value());
        if (partial) response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + length);
        response.setContentLengthLong(count);

        try (InputStream in = Files.newInputStream(path)) {
            if (start > 0) in.skipNBytes(start);
            OutputStream out = response.getOutputStream();
            byte[] buffer = new byte[64 * 1024];
            long remaining = count;
            int read;
            while (remaining > 0 && (read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                out.write(buffer, 0, read);
                remaining -= read;
            }
            out.flush();
        }
    }

    private void writeRangeNotSatisfiable(HttpServletResponse response, long length) {
        response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
        response.setHeader("Content-Range", "bytes */" + length);
    }

    private long appId(HttpServletRequest request) {
        String raw = request.getHeader("X-PDK-App-ID");
        if (raw == null || raw.isBlank()) throw new BusinessException(40050, "必须显式携带 X-PDK-App-ID");
        try { long value = Long.parseLong(raw); if (value <= 0) throw new NumberFormatException(); return value; }
        catch (NumberFormatException e) { throw new BusinessException(40050, "X-PDK-App-ID 必须是正整数"); }
    }
}
