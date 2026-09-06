package com.pdk.business.zhibo.live.controller;

import com.pdk.business.zhibo.live.service.LiveStreamSessionService;
import com.pdk.business.zhibo.live.vo.LiveStreamSessionVO;
import com.pdk.business.zhibo.live.vo.LivePlaySessionVO;
import com.pdk.business.zhibo.live.vo.LiveOverviewVO;
import com.pdk.business.zhibo.live.vo.MediaServerNodeVO;
import com.pdk.business.zhibo.live.dto.SaveMediaServerNodeDTO;
import com.pdk.business.zhibo.live.entity.MediaServerNode;
import com.pdk.business.zhibo.live.service.LivePlaySessionService;
import com.pdk.business.zhibo.live.service.MediaServerNodeService;
import com.pdk.common.api.CommonResult;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.entity.Business;
import com.pdk.domain.entity.CardKey;
import com.pdk.domain.entity.DeviceLicense;
import com.pdk.mapper.CardKeyMapper;
import com.pdk.mapper.DeviceLicenseMapper;
import com.pdk.platform.business.BusinessService;
import com.pdk.security.AdminBusinessScope;
import com.pdk.security.AdminPrincipal;
import com.pdk.security.RequirePermission;
import com.pdk.security.RolePermissions;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pdk.service.AdminAuditService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/zhibo-live")
@RequiredArgsConstructor
public class ZhiboLiveAdminController {
    private final LiveStreamSessionService streamService;
    private final BusinessService businessService;
    private final AdminBusinessScope businessScope;
    private final MediaServerNodeService nodeService;
    private final LivePlaySessionService playSessionService;
    private final CardKeyMapper cardKeyMapper;
    private final DeviceLicenseMapper deviceLicenseMapper;
    private final AdminAuditService auditService;

    @GetMapping("/streams")
    @RequirePermission(RolePermissions.LIVE_STREAM_VIEW)
    public CommonResult<List<LiveStreamSessionVO>> list(@RequestParam(required = false) String status,
                                                        HttpServletRequest request) {
        long bizId = liveBizId(request);
        return CommonResult.success(streamService.listForAdmin(bizId, status, allowedLicenseIds(request, bizId)));
    }

    @PostMapping("/streams/{sessionNo}/kick")
    @RequirePermission(RolePermissions.LIVE_STREAM_KICK)
    public CommonResult<String> kick(@PathVariable String sessionNo, HttpServletRequest request) {
        long bizId = liveBizId(request);
        streamService.stopByAdmin(bizId, sessionNo, "ADMIN_KICK", allowedLicenseIds(request, bizId));
        auditService.record(principal(request), bizId, "KICK_LIVE_STREAM", "LIVE_STREAM", sessionNo,
                null, "{\"status\":\"ENDED\"}", "管理员停止直播", request);
        return CommonResult.success("直播连接已停止");
    }

    @GetMapping("/overview")
    @RequirePermission(RolePermissions.LIVE_OVERVIEW_VIEW)
    public CommonResult<LiveOverviewVO> overview(HttpServletRequest request) {
        long bizId = liveBizId(request);
        return CommonResult.success(nodeService.overview(bizId, allowedLicenseIds(request, bizId)));
    }

    @GetMapping("/media-nodes")
    @RequirePermission(RolePermissions.LIVE_NODE_VIEW)
    public CommonResult<List<MediaServerNodeVO>> nodes(HttpServletRequest request) {
        return CommonResult.success(nodeService.list(liveBizId(request)));
    }

    @PostMapping("/media-nodes")
    @RequirePermission(RolePermissions.LIVE_NODE_EDIT)
    public CommonResult<MediaServerNode> createNode(@Valid @RequestBody SaveMediaServerNodeDTO dto,
                                                     HttpServletRequest request) {
        long bizId = liveBizId(request);
        if (dto.bizId() != bizId) throw new BusinessException(40074, "节点 bizId 必须是 ZHIBO_LIVE");
        MediaServerNode value = nodeService.create(dto);
        auditService.record(principal(request), bizId, "CREATE_MEDIA_NODE", "MEDIA_NODE", value.getNodeCode(),
                null, "{\"providerType\":\"" + value.getProviderType() + "\"}", "创建流媒体节点", request);
        return CommonResult.success(value, "节点已创建，连接测试通过后方可启用");
    }

    @PutMapping("/media-nodes/{nodeId}")
    @RequirePermission(RolePermissions.LIVE_NODE_EDIT)
    public CommonResult<MediaServerNode> updateNode(@PathVariable long nodeId,
                                                     @Valid @RequestBody SaveMediaServerNodeDTO dto,
                                                     HttpServletRequest request) {
        long bizId = liveBizId(request);
        MediaServerNode value = nodeService.update(bizId, nodeId, dto);
        auditService.record(principal(request), bizId, "UPDATE_MEDIA_NODE", "MEDIA_NODE", value.getNodeCode(),
                null, "{\"configRevision\":" + value.getConfigRevision() + "}", "更新流媒体节点", request);
        return CommonResult.success(value);
    }

    @PutMapping("/media-nodes/{nodeId}/status")
    @RequirePermission(RolePermissions.LIVE_NODE_EDIT)
    public CommonResult<MediaServerNode> nodeStatus(@PathVariable long nodeId, @RequestParam String status,
                                                     @RequestParam String reason, HttpServletRequest request) {
        if (reason == null || reason.trim().length() < 2) throw new BusinessException(40001, "操作原因至少填写2个字符");
        long bizId = liveBizId(request);
        MediaServerNode value = nodeService.setStatus(bizId, nodeId, status);
        auditService.record(principal(request), bizId, "SET_MEDIA_NODE_STATUS", "MEDIA_NODE", value.getNodeCode(),
                null, "{\"status\":\"" + value.getStatus() + "\"}", reason, request);
        return CommonResult.success(value);
    }

    @PostMapping("/media-nodes/{nodeId}/test")
    @RequirePermission(RolePermissions.LIVE_NODE_EDIT)
    public CommonResult<MediaServerNodeVO> testNode(@PathVariable long nodeId, HttpServletRequest request) {
        long bizId = liveBizId(request);
        return CommonResult.success(nodeService.collect(bizId, nodeId), "节点连接与指标采集成功");
    }

    @GetMapping("/play-sessions")
    @RequirePermission(RolePermissions.LIVE_PLAY_VIEW)
    public CommonResult<Page<LivePlaySessionVO>> playSessions(@RequestParam(defaultValue = "1") long page,
                                                               @RequestParam(defaultValue = "20") long size,
                                                               @RequestParam(required = false) String status,
                                                               @RequestParam(required = false) String nodeCode,
                                                               HttpServletRequest request) {
        long bizId = liveBizId(request);
        Set<Long> licenseIds = allowedLicenseIds(request, bizId);
        return CommonResult.success(playSessionService.page(bizId, page, size, status, nodeCode,
                streamService.sessionIdsForLicenses(bizId, licenseIds)));
    }

    private long liveBizId(HttpServletRequest request) {
        AdminPrincipal principal = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        Business business = businessService.requireByAppId(3);
        Long scoped = businessScope.enforce(principal, business.getId());
        return scoped == null ? business.getId() : scoped;
    }

    private Set<Long> allowedLicenseIds(HttpServletRequest request, long bizId) {
        AdminPrincipal principal = principal(request);
        if (principal.isSuperAdmin()) return null;
        Set<Long> cardIds = cardKeyMapper.selectList(new LambdaQueryWrapper<CardKey>()
                        .eq(CardKey::getBizId, bizId).eq(CardKey::getAgentId, principal.id()))
                .stream().map(CardKey::getId).collect(Collectors.toSet());
        if (cardIds.isEmpty()) return Set.of();
        return deviceLicenseMapper.selectList(new LambdaQueryWrapper<DeviceLicense>()
                        .eq(DeviceLicense::getBizId, bizId).in(DeviceLicense::getCardKeyId, cardIds))
                .stream().map(DeviceLicense::getId).collect(Collectors.toSet());
    }

    private AdminPrincipal principal(HttpServletRequest request) {
        return (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
    }
}
