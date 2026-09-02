package com.pdk.update.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pdk.common.api.CommonResult;
import com.pdk.security.*;
import com.pdk.update.domain.*;
import com.pdk.update.dto.UpdateAdminDtos.*;
import com.pdk.update.service.ClientUpdateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/client-updates")
@RequiredArgsConstructor
public class AdminClientUpdateController {
    private final ClientUpdateService service;

    @GetMapping("/releases") @RequirePermission(RolePermissions.CLIENT_UPDATE_VIEW)
    public CommonResult<IPage<ClientRelease>> releases(HttpServletRequest req, @RequestParam(required=false) Long bizId,
            @RequestParam(required=false) String channel, @RequestParam(required=false) String status,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        return CommonResult.success(service.releases(principal(req), bizId, channel, status, page, size));
    }
    @GetMapping("/releases/{id}/artifacts") @RequirePermission(RolePermissions.CLIENT_UPDATE_VIEW)
    public CommonResult<List<ClientArtifact>> artifacts(@PathVariable long id, HttpServletRequest req) { return CommonResult.success(service.artifacts(principal(req), id)); }
    @PostMapping("/releases") @RequirePermission(RolePermissions.CLIENT_UPDATE_CREATE)
    public CommonResult<ClientRelease> create(@Valid @RequestBody CreateRelease body, HttpServletRequest req) { return CommonResult.success(service.createRelease(body, principal(req), req)); }
    @PutMapping("/releases/{id}") @RequirePermission(RolePermissions.CLIENT_UPDATE_CREATE)
    public CommonResult<ClientRelease> edit(@PathVariable long id, @Valid @RequestBody EditRelease body, HttpServletRequest req) { return CommonResult.success(service.editRelease(id, body, principal(req), req)); }
    @PostMapping("/releases/{id}/artifacts/upload-session") @RequirePermission(RolePermissions.CLIENT_UPDATE_CREATE)
    public CommonResult<ClientArtifact> uploadSession(@PathVariable long id, @Valid @RequestBody CreateArtifact body, HttpServletRequest req) { return CommonResult.success(service.createArtifact(id, body, principal(req), req)); }
    @PutMapping(value="/artifacts/{id}/content", consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @RequirePermission(RolePermissions.CLIENT_UPDATE_CREATE)
    public CommonResult<ClientArtifact> upload(@PathVariable long id, @RequestPart("file") MultipartFile file, HttpServletRequest req) { return CommonResult.success(service.uploadContent(id, file, principal(req))); }
    @PostMapping("/artifacts/{id}/complete") @RequirePermission(RolePermissions.CLIENT_UPDATE_CREATE)
    public CommonResult<ClientArtifact> complete(@PathVariable long id, @Valid @RequestBody Action body, HttpServletRequest req) { return CommonResult.success(service.completeArtifact(id, body, principal(req), req)); }
    @PostMapping("/artifacts/{id}/download-link") @RequirePermission(RolePermissions.CLIENT_UPDATE_PUBLISH)
    public CommonResult<DownloadLinkView> downloadLink(@PathVariable long id, @Valid @RequestBody CreateDownloadLink body, HttpServletRequest req) {
        return CommonResult.success(service.createAdminDownloadLink(id, body, principal(req), req));
    }
    @PostMapping("/releases/{id}/ready") @RequirePermission(RolePermissions.CLIENT_UPDATE_CREATE)
    public CommonResult<ClientRelease> ready(@PathVariable long id, @Valid @RequestBody Action body, HttpServletRequest req) { return CommonResult.success(service.transition(id,"READY",body,principal(req),req)); }
    @PostMapping("/releases/{id}/publish") @RequirePermission(RolePermissions.CLIENT_UPDATE_PUBLISH)
    public CommonResult<ClientRelease> publish(@PathVariable long id, @Valid @RequestBody Action body, HttpServletRequest req) { return CommonResult.success(service.transition(id,"PUBLISHED",body,principal(req),req)); }
    @PostMapping("/releases/{id}/suspend") @RequirePermission(RolePermissions.CLIENT_UPDATE_SUSPEND)
    public CommonResult<ClientRelease> suspend(@PathVariable long id, @Valid @RequestBody Action body, HttpServletRequest req) { return CommonResult.success(service.transition(id,"SUSPENDED",body,principal(req),req)); }
    @PostMapping("/releases/{id}/resume") @RequirePermission(RolePermissions.CLIENT_UPDATE_PUBLISH)
    public CommonResult<ClientRelease> resume(@PathVariable long id, @Valid @RequestBody Action body, HttpServletRequest req) { return CommonResult.success(service.transition(id,"PUBLISHED",body,principal(req),req)); }
    @PostMapping("/releases/{id}/archive") @RequirePermission(RolePermissions.CLIENT_UPDATE_SUSPEND)
    public CommonResult<ClientRelease> archive(@PathVariable long id, @Valid @RequestBody Action body, HttpServletRequest req) { return CommonResult.success(service.transition(id,"ARCHIVED",body,principal(req),req)); }
    @PostMapping("/releases/{id}/draft") @RequirePermission(RolePermissions.CLIENT_UPDATE_CREATE)
    public CommonResult<ClientRelease> draft(@PathVariable long id, @Valid @RequestBody Action body, HttpServletRequest req) { return CommonResult.success(service.transition(id,"DRAFT",body,principal(req),req)); }
    @PutMapping("/releases/{id}/rollout") @RequirePermission(RolePermissions.CLIENT_UPDATE_PUBLISH)
    public CommonResult<ClientRelease> rollout(@PathVariable long id, @Valid @RequestBody UpdateRollout body, HttpServletRequest req) { return CommonResult.success(service.updateRollout(id,body,principal(req),req)); }
    @DeleteMapping("/releases/{id}") @RequirePermission(RolePermissions.CLIENT_UPDATE_CREATE)
    public CommonResult<String> delete(@PathVariable long id,@Valid @RequestBody Action body,HttpServletRequest req) { service.deleteDraft(id,body,principal(req),req); return CommonResult.success("草稿版本、构件记录和文件已删除"); }
    @GetMapping("/policies/{bizId}") @RequirePermission(RolePermissions.CLIENT_UPDATE_VIEW)
    public CommonResult<ClientUpdatePolicy> policy(@PathVariable long bizId, @RequestParam(defaultValue="STABLE") String channel,
            @RequestParam(defaultValue="WINDOWS") String platform, @RequestParam(defaultValue="X64") String arch, HttpServletRequest req) { return CommonResult.success(service.getPolicy(principal(req),bizId,channel,platform,arch)); }
    @PutMapping("/policies/{bizId}") @RequirePermission(RolePermissions.CLIENT_UPDATE_PUBLISH)
    public CommonResult<ClientUpdatePolicy> savePolicy(@PathVariable long bizId, @Valid @RequestBody SavePolicy body, HttpServletRequest req) { return CommonResult.success(service.savePolicy(bizId,body,principal(req),req)); }
    @GetMapping("/events") @RequirePermission(RolePermissions.CLIENT_UPDATE_VIEW)
    public CommonResult<IPage<ClientUpdateEvent>> events(@RequestParam(required=false) Long bizId, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size, HttpServletRequest req) { return CommonResult.success(service.events(principal(req),bizId,page,size)); }
    @GetMapping("/statistics") @RequirePermission(RolePermissions.CLIENT_UPDATE_VIEW)
    public CommonResult<List<Map<String,Object>>> stats(@RequestParam(required=false) Long bizId,HttpServletRequest req) { return CommonResult.success(service.statistics(principal(req),bizId)); }
    private AdminPrincipal principal(HttpServletRequest req) { return (AdminPrincipal) req.getAttribute("pdkAdminPrincipal"); }
}
