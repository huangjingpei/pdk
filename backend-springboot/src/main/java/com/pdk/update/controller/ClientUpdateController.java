package com.pdk.update.controller;

import com.pdk.common.api.CommonResult;
import com.pdk.common.exception.BusinessException;
import com.pdk.update.domain.ClientArtifact;
import com.pdk.update.dto.UpdateClientDtos.*;
import com.pdk.update.service.ClientUpdateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    @RequestMapping(value="/download/{artifactId}", method={RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<?> download(@PathVariable long artifactId, @RequestParam long appId, @RequestParam String token,
                                      @RequestHeader HttpHeaders headers, HttpServletRequest request) throws IOException {
        Path path = updateService.artifactPath(appId, artifactId, token);
        ClientArtifact artifact = updateService.requireArtifactById(artifactId);
        FileSystemResource resource = new FileSystemResource(path);
        long length = resource.contentLength();
        String disposition = ContentDisposition.attachment().filename(artifact.getFileName(), StandardCharsets.UTF_8).build().toString();
        HttpHeaders responseHeaders = new HttpHeaders(); responseHeaders.setContentDisposition(ContentDisposition.parse(disposition));
        responseHeaders.setETag('"' + artifact.getSha256() + '"'); responseHeaders.set("Accept-Ranges", "bytes");
        responseHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        if ("HEAD".equalsIgnoreCase(request.getMethod())) { responseHeaders.setContentLength(length); return new ResponseEntity<>(responseHeaders, HttpStatus.OK); }
        List<HttpRange> ranges = headers.getRange();
        if (ranges.isEmpty()) { responseHeaders.setContentLength(length); return new ResponseEntity<Resource>(resource, responseHeaders, HttpStatus.OK); }
        HttpRange range = ranges.get(0); long start = range.getRangeStart(length); long end = range.getRangeEnd(length);
        ResourceRegion region = new ResourceRegion(resource, start, end - start + 1);
        responseHeaders.set("Content-Range", "bytes " + start + "-" + end + "/" + length);
        return new ResponseEntity<>(region, responseHeaders, HttpStatus.PARTIAL_CONTENT);
    }

    private long appId(HttpServletRequest request) {
        String raw = request.getHeader("X-PDK-App-ID");
        if (raw == null || raw.isBlank()) throw new BusinessException(40050, "必须显式携带 X-PDK-App-ID");
        try { long value = Long.parseLong(raw); if (value <= 0) throw new NumberFormatException(); return value; }
        catch (NumberFormatException e) { throw new BusinessException(40050, "X-PDK-App-ID 必须是正整数"); }
    }
}
