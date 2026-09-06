package com.pdk.business.zhibo.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pdk.business.zhibo.ZhiboBusinessHandler;
import com.pdk.business.zhibo.live.dto.SaveMediaServerNodeDTO;
import com.pdk.business.zhibo.live.entity.LivePlaySession;
import com.pdk.business.zhibo.live.entity.LiveStreamSession;
import com.pdk.business.zhibo.live.entity.MediaServerNode;
import com.pdk.business.zhibo.live.entity.MediaServerNodeSnapshot;
import com.pdk.business.zhibo.live.mapper.LivePlaySessionMapper;
import com.pdk.business.zhibo.live.mapper.LiveStreamSessionMapper;
import com.pdk.business.zhibo.live.mapper.MediaServerNodeMapper;
import com.pdk.business.zhibo.live.mapper.MediaServerNodeSnapshotMapper;
import com.pdk.business.zhibo.live.media.MediaNodeSnapshotData;
import com.pdk.business.zhibo.live.media.MediaServerProvider;
import com.pdk.business.zhibo.live.media.MediaServerProviderRegistry;
import com.pdk.business.zhibo.live.vo.LiveMediaPublicVO;
import com.pdk.business.zhibo.live.vo.LiveOverviewVO;
import com.pdk.business.zhibo.live.vo.MediaServerNodeVO;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.entity.Business;
import com.pdk.mapper.BusinessMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaServerNodeService {
    private static final List<String> ACTIVE_STREAM_STATUSES = List.of("ISSUED", "AUTHORIZED", "LIVE", "KICK_REQUESTED");

    private final MediaServerNodeMapper nodeMapper;
    private final MediaServerNodeSnapshotMapper snapshotMapper;
    private final LiveStreamSessionMapper streamMapper;
    private final LivePlaySessionMapper playMapper;
    private final BusinessMapper businessMapper;
    private final MediaServerProviderRegistry providerRegistry;

    public List<MediaServerNodeVO> list(long bizId) {
        requireLiveBusiness(bizId);
        return nodeMapper.selectList(new LambdaQueryWrapper<MediaServerNode>()
                        .eq(MediaServerNode::getBizId, bizId)
                        .orderByDesc(MediaServerNode::getStatus).orderByDesc(MediaServerNode::getWeight)
                        .orderByAsc(MediaServerNode::getId))
                .stream().map(node -> MediaServerNodeVO.of(node, snapshotMapper.selectById(node.getId()))).toList();
    }

    public MediaServerNode require(long bizId, long nodeId) {
        MediaServerNode node = nodeMapper.selectById(nodeId);
        if (node == null || !Long.valueOf(bizId).equals(node.getBizId())) {
            throw new BusinessException(40472, "流媒体节点不存在");
        }
        requireLiveBusiness(bizId);
        return node;
    }

    public MediaServerNode requireByCode(String nodeCode) {
        MediaServerNode node = nodeMapper.selectOne(new LambdaQueryWrapper<MediaServerNode>()
                .eq(MediaServerNode::getNodeCode, nodeCode).last("LIMIT 1"));
        if (node == null) throw new BusinessException(40472, "流媒体节点不存在: " + nodeCode);
        requireLiveBusiness(node.getBizId());
        return node;
    }

    @Transactional(rollbackFor = Exception.class)
    public MediaServerNode create(SaveMediaServerNodeDTO dto) {
        requireLiveBusiness(dto.bizId());
        MediaServerNode node = new MediaServerNode();
        apply(node, dto, true);
        node.setStatus("DISABLED");
        node.setHealthStatus("UNKNOWN");
        node.setConfigRevision(1L);
        node.setVersion(0);
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());
        providerRegistry.require(node.getProviderType()).validate(node);
        try {
            nodeMapper.insert(node);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(40972, "同一业务内 nodeCode 已存在");
        }
        return node;
    }

    @Transactional(rollbackFor = Exception.class)
    public MediaServerNode update(long bizId, long nodeId, SaveMediaServerNodeDTO dto) {
        if (!Long.valueOf(bizId).equals(dto.bizId())) throw new BusinessException(40074, "bizId 不能修改");
        MediaServerNode node = require(bizId, nodeId);
        if ("ACTIVE".equals(node.getStatus()) || "DRAINING".equals(node.getStatus())) {
            throw new BusinessException(40973, "修改节点配置前请先将节点停用");
        }
        apply(node, dto, false);
        providerRegistry.require(node.getProviderType()).validate(node);
        node.setHealthStatus("UNKNOWN");
        node.setLastHealthError(null);
        node.setConfigRevision((node.getConfigRevision() == null ? 0 : node.getConfigRevision()) + 1);
        node.setVersion((node.getVersion() == null ? 0 : node.getVersion()) + 1);
        node.setUpdatedAt(LocalDateTime.now());
        nodeMapper.updateById(node);
        return node;
    }

    @Transactional(rollbackFor = Exception.class)
    public MediaServerNode setStatus(long bizId, long nodeId, String status) {
        MediaServerNode node = require(bizId, nodeId);
        String value = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "DRAINING", "DISABLED").contains(value)) {
            throw new BusinessException(40075, "节点状态只允许 ACTIVE、DRAINING、DISABLED");
        }
        if ("ACTIVE".equals(value)) {
            providerRegistry.require(node.getProviderType()).validate(node);
            collect(node, true);
            node = nodeMapper.selectById(nodeId);
            if (!"UP".equals(node.getHealthStatus()) && !"DEGRADED".equals(node.getHealthStatus())) {
                throw new BusinessException(50372, "节点健康检查未通过，不能启用");
            }
        }
        node.setStatus(value);
        node.setUpdatedAt(LocalDateTime.now());
        nodeMapper.updateById(node);
        return node;
    }

    public MediaServerNodeVO collect(long bizId, long nodeId) {
        return collect(require(bizId, nodeId), true);
    }

    public void collectAll() {
        List<MediaServerNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<MediaServerNode>()
                .in(MediaServerNode::getStatus, "ACTIVE", "DRAINING"));
        for (MediaServerNode node : nodes) {
            try {
                collect(node, false);
            } catch (RuntimeException ignored) {
                // 单节点故障不能阻断其他节点采集，错误已写入节点状态。
            }
        }
    }

    public boolean hasConfiguredNode(long bizId) {
        return nodeMapper.selectCount(new LambdaQueryWrapper<MediaServerNode>()
                .eq(MediaServerNode::getBizId, bizId).eq(MediaServerNode::getStatus, "ACTIVE")) > 0;
    }

    public MediaServerNode selectForPublish(long bizId, String protocol) {
        requireLiveBusiness(bizId);
        String wanted = protocol == null || protocol.isBlank() ? "RTMP" : protocol.trim().toUpperCase(Locale.ROOT);
        List<MediaServerNode> candidates = new ArrayList<>(nodeMapper.selectList(new LambdaQueryWrapper<MediaServerNode>()
                .eq(MediaServerNode::getBizId, bizId)
                .eq(MediaServerNode::getStatus, "ACTIVE")
                .in(MediaServerNode::getHealthStatus, "UP", "DEGRADED", "UNKNOWN")));
        candidates.removeIf(node -> !protocols(node.getSupportedPublishProtocols()).contains(wanted));
        candidates.removeIf(node -> activeStreams(node.getNodeCode()) >= safe(node.getMaxPublishers(), 1));
        candidates.sort(Comparator
                .comparingInt((MediaServerNode node) -> activeStreams(node.getNodeCode()))
                .thenComparing(Comparator.comparingInt((MediaServerNode node) -> safe(node.getWeight(), 1)).reversed())
                .thenComparing(MediaServerNode::getId));
        if (candidates.isEmpty()) throw new BusinessException(50372, "没有可用且有容量的流媒体节点");
        return candidates.get(0);
    }

    public String buildPublishUrl(MediaServerNode node, String path, String ticket) {
        return providerRegistry.require(node.getProviderType()).buildPublishUrl(node, path, ticket);
    }

    public void kick(MediaServerNode node, String providerConnectionId, String protocol) {
        providerRegistry.require(node.getProviderType()).kickPublisher(node, providerConnectionId, protocol);
    }

    public LiveMediaPublicVO publicConfig(long bizId, boolean businessAvailable) {
        requireLiveBusiness(bizId);
        List<MediaServerNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<MediaServerNode>()
                .eq(MediaServerNode::getBizId, bizId).eq(MediaServerNode::getStatus, "ACTIVE")
                .in(MediaServerNode::getHealthStatus, "UP", "DEGRADED", "UNKNOWN")
                .orderByDesc(MediaServerNode::getWeight).orderByAsc(MediaServerNode::getId));
        if (nodes.isEmpty()) {
            return new LiveMediaPublicVO(false, "NO_AVAILABLE_NODE", null, null, List.of(), List.of(), null,
                    LocalDateTime.now());
        }
        MediaServerNode primary = nodes.get(0);
        List<String> publish = protocols(primary.getSupportedPublishProtocols());
        return new LiveMediaPublicVO(businessAvailable, businessAvailable ? "AVAILABLE" : "BUSINESS_UNAVAILABLE",
                primary.getPublicPublishBaseUrl(), publish.isEmpty() ? null : publish.get(0), publish,
                protocols(primary.getSupportedPlayProtocols()), primary.getConfigRevision(), LocalDateTime.now());
    }

    public LiveOverviewVO overview(long bizId) {
        return overview(bizId, null);
    }

    /**
     * 超级管理员传 null 查看整个业务；代理传其名下许可证集合，只统计代理自己的推流和拉流。
     * 节点健康和带宽属于平台基础设施数据，因此代理视图不返回这些全局指标。
     */
    public LiveOverviewVO overview(long bizId, Set<Long> allowedLicenseIds) {
        requireLiveBusiness(bizId);
        boolean scoped = allowedLicenseIds != null;
        List<MediaServerNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<MediaServerNode>()
                .eq(MediaServerNode::getBizId, bizId));
        long available = scoped ? 0 : nodes.stream().filter(node -> "ACTIVE".equals(node.getStatus())
                && ("UP".equals(node.getHealthStatus()) || "DEGRADED".equals(node.getHealthStatus()))).count();
        long abnormal = scoped ? 0 : nodes.stream().filter(node -> "DOWN".equals(node.getHealthStatus())).count();
        if (scoped && allowedLicenseIds.isEmpty()) {
            return new LiveOverviewVO(0, 0, 0, 0, 0, null, null, 0, null);
        }
        LambdaQueryWrapper<LiveStreamSession> liveQuery = new LambdaQueryWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getBizId, bizId).eq(LiveStreamSession::getStatus, "LIVE");
        LambdaQueryWrapper<LiveStreamSession> startsQuery = new LambdaQueryWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getBizId, bizId).ge(LiveStreamSession::getStartedAt, LocalDate.now().atStartOfDay());
        if (scoped) {
            liveQuery.in(LiveStreamSession::getDeviceLicenseId, allowedLicenseIds);
            startsQuery.in(LiveStreamSession::getDeviceLicenseId, allowedLicenseIds);
        }
        long publishers = streamMapper.selectCount(liveQuery);
        long starts = streamMapper.selectCount(startsQuery);
        long readers = 0;
        Long inbound = null;
        Long outbound = null;
        LocalDateTime collectedAt = null;
        if (scoped) {
            List<Long> streamIds = streamMapper.selectList(new LambdaQueryWrapper<LiveStreamSession>()
                            .select(LiveStreamSession::getId)
                            .eq(LiveStreamSession::getBizId, bizId)
                            .in(LiveStreamSession::getDeviceLicenseId, allowedLicenseIds))
                    .stream().map(LiveStreamSession::getId).toList();
            if (!streamIds.isEmpty()) {
                readers = playMapper.selectCount(new LambdaQueryWrapper<LivePlaySession>()
                        .eq(LivePlaySession::getBizId, bizId)
                        .eq(LivePlaySession::getStatus, "PLAYING")
                        .in(LivePlaySession::getStreamSessionId, streamIds));
            }
            return new LiveOverviewVO(0, 0, 0, publishers, readers, null, null, starts, null);
        }
        for (MediaServerNode node : nodes) {
            MediaServerNodeSnapshot snapshot = snapshotMapper.selectById(node.getId());
            if (snapshot == null || !"SUCCESS".equals(snapshot.getCollectStatus())) continue;
            readers += snapshot.getActiveReaders() == null ? 0 : snapshot.getActiveReaders();
            if (snapshot.getInboundBps() != null) inbound = (inbound == null ? 0 : inbound) + snapshot.getInboundBps();
            if (snapshot.getOutboundBps() != null) outbound = (outbound == null ? 0 : outbound) + snapshot.getOutboundBps();
            if (collectedAt == null || snapshot.getCollectedAt().isAfter(collectedAt)) collectedAt = snapshot.getCollectedAt();
        }
        return new LiveOverviewVO(nodes.size(), available, abnormal, publishers, readers, inbound, outbound, starts, collectedAt);
    }

    private MediaServerNodeVO collect(MediaServerNode node, boolean rethrow) {
        LocalDateTime now = LocalDateTime.now();
        MediaServerNodeSnapshot previous = snapshotMapper.selectById(node.getId());
        try {
            MediaServerProvider provider = providerRegistry.require(node.getProviderType());
            provider.validate(node);
            MediaNodeSnapshotData data = provider.snapshot(node);
            MediaServerNodeSnapshot snapshot = new MediaServerNodeSnapshot();
            snapshot.setNodeId(node.getId());
            snapshot.setCollectedAt(now);
            snapshot.setCollectStatus("SUCCESS");
            snapshot.setActivePublishers(data.activePublishers());
            snapshot.setActiveReaders(data.activeReaders());
            snapshot.setActivePaths(data.activePaths());
            snapshot.setInboundBytesTotal(data.inboundBytesTotal());
            snapshot.setOutboundBytesTotal(data.outboundBytesTotal());
            snapshot.setProviderVersion(data.providerVersion());
            if (previous != null && previous.getCollectedAt() != null) {
                long seconds = Math.max(1, java.time.Duration.between(previous.getCollectedAt(), now).getSeconds());
                snapshot.setInboundBps(rate(previous.getInboundBytesTotal(), data.inboundBytesTotal(), seconds));
                snapshot.setOutboundBps(rate(previous.getOutboundBytesTotal(), data.outboundBytesTotal(), seconds));
            }
            upsertSnapshot(snapshot, previous != null);
            node.setHealthStatus(data.activePublishers() == null ? "DEGRADED" : "UP");
            node.setLastHealthAt(now);
            node.setLastHealthError(null);
            nodeMapper.updateById(node);
            return MediaServerNodeVO.of(node, snapshot);
        } catch (RuntimeException e) {
            String message = safeMessage(e);
            MediaServerNodeSnapshot snapshot = new MediaServerNodeSnapshot();
            snapshot.setNodeId(node.getId());
            snapshot.setCollectedAt(now);
            snapshot.setCollectStatus("FAILED");
            snapshot.setErrorMessage(message);
            upsertSnapshot(snapshot, previous != null);
            node.setHealthStatus("DOWN");
            node.setLastHealthAt(now);
            node.setLastHealthError(message);
            nodeMapper.updateById(node);
            log.warn("流媒体节点采集失败: nodeCode={}, error={}", node.getNodeCode(), message);
            if (rethrow) throw new BusinessException(50373, "节点连接失败: " + message);
            return MediaServerNodeVO.of(node, snapshot);
        }
    }

    private void upsertSnapshot(MediaServerNodeSnapshot snapshot, boolean exists) {
        snapshot.setUpdatedAt(LocalDateTime.now());
        if (exists) snapshotMapper.updateById(snapshot); else snapshotMapper.insert(snapshot);
    }

    private void apply(MediaServerNode node, SaveMediaServerNodeDTO dto, boolean includeCode) {
        node.setBizId(dto.bizId());
        if (includeCode) node.setNodeCode(dto.nodeCode().trim());
        node.setNodeName(dto.nodeName().trim());
        node.setProviderType(dto.providerType().trim().toUpperCase(Locale.ROOT));
        node.setRegionCode(trimToNull(dto.regionCode()));
        node.setPublicPublishBaseUrl(url(dto.publicPublishBaseUrl()));
        node.setPublicHlsBaseUrl(url(dto.publicHlsBaseUrl()));
        node.setPublicWebrtcBaseUrl(url(dto.publicWebrtcBaseUrl()));
        node.setInternalApiBaseUrl(url(dto.internalApiBaseUrl()));
        node.setInternalMetricsUrl(url(dto.internalMetricsUrl()));
        node.setSecretRef(trimToNull(dto.secretRef()) == null ? "application" : dto.secretRef().trim());
        node.setSupportedPublishProtocols(joinProtocols(dto.supportedPublishProtocols()));
        node.setSupportedPlayProtocols(joinProtocols(dto.supportedPlayProtocols()));
        node.setWeight(safe(dto.weight(), 100));
        node.setMaxPublishers(safe(dto.maxPublishers(), 100));
        node.setMaxReaders(safe(dto.maxReaders(), 1000));
    }

    private Business requireLiveBusiness(long bizId) {
        Business business = businessMapper.selectById(bizId);
        if (business == null || !Long.valueOf(3).equals(business.getAppId())
                || !ZhiboBusinessHandler.LIVE_CODE.equalsIgnoreCase(business.getBizCode())) {
            throw new BusinessException(40370, "流媒体节点只允许 appId=3 / ZHIBO_LIVE 使用");
        }
        return business;
    }

    private int activeStreams(String nodeCode) {
        return Math.toIntExact(streamMapper.selectCount(new LambdaQueryWrapper<LiveStreamSession>()
                .eq(LiveStreamSession::getMediaNodeCode, nodeCode)
                .in(LiveStreamSession::getStatus, ACTIVE_STREAM_STATUSES)));
    }

    private static List<String> protocols(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank())
                .map(item -> item.toUpperCase(Locale.ROOT)).distinct().toList();
    }

    private static String joinProtocols(String value) {
        List<String> values = protocols(value);
        return values.isEmpty() ? null : String.join(",", values);
    }

    private static String url(String value) {
        String result = trimToNull(value);
        return result == null ? null : result.replaceAll("/+$", "");
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static int safe(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static Long rate(Long previous, Long current, long seconds) {
        if (previous == null || current == null || current < previous) return null;
        return Math.max(0, (current - previous) * 8 / seconds);
    }

    private static String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        if (value == null || value.isBlank()) value = throwable.getClass().getSimpleName();
        value = value.replaceAll("[\\r\\n\\t]", " ");
        return value.substring(0, Math.min(240, value.length()));
    }
}
